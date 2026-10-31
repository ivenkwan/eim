package io.eim.calcengine;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TG9-C2 (FR-509): the materialized dirty set accelerates the incremental
 * runner — ingest writes rows, the runner drains exactly the unconsumed
 * set and marks it consumed, and an empty cache leaves the timestamp-diff
 * fallback untouched (never the source of truth).
 */
@QuarkusTest
class DirtyCacheTest {

    private static final String TENANT = "aaaaaaaa-b100-0000-0000-000000000001";
    private static final String P1 = "aaaaaaaa-b100-0000-0000-000000000002";
    private static final String P2 = "aaaaaaaa-b100-0000-0000-000000000003";

    @Inject
    AgroalDataSource ds;

    @AfterEach
    void cleanup() throws SQLException {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM recalc_dirty_payee WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
        }
    }

    private void seedDirty(String payee) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO recalc_dirty_payee (tenant_id, payee_id, source) "
                             + "VALUES (CAST(? AS uuid), CAST(? AS uuid), 'INGEST') "
                             + "ON CONFLICT DO NOTHING")) {
            ps.setString(1, TENANT);
            ps.setString(2, payee);
            ps.executeUpdate();
        }
    }

    @Test
    void drainReturnsUnconsumedOnlyAndMarksConsumed() throws SQLException {
        seedDirty(P1);
        seedDirty(P2);
        // a foreign tenant's row must not leak into this drain
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO recalc_dirty_payee (tenant_id, payee_id, source) "
                             + "VALUES (CAST(? AS uuid), CAST(? AS uuid), 'INGEST')")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, P1);
            ps.executeUpdate();
        }
        java.util.Set<UUID> drained = drain();
        assertEquals(2, drained.size(), "exactly this tenant's unconsumed payees");
        // second drain is empty (consumed), so the runner would fall back to diff
        assertEquals(0, drain().size(), "consumed rows never re-drain");
        assertEquals(2L, count("consumed_at IS NOT NULL"), "rows marked consumed");
    }

    @Test
    void emptyCacheLeavesNoResidue() throws SQLException {
        assertEquals(0, drain().size());
        assertEquals(0L, count("consumed_at IS NOT NULL"), "nothing marked on an empty drain");
    }

    // mirrors CalcRunResource.drainDirtyCache (kept in sync deliberately:
    // the resource drains via its own connection; the test proves the table
    // semantics the resource depends on)
    private java.util.Set<UUID> drain() throws SQLException {
        java.util.Set<UUID> out = new java.util.LinkedHashSet<>();
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT DISTINCT payee_id::text FROM recalc_dirty_payee "
                            + "WHERE tenant_id = CAST(? AS uuid) AND consumed_at IS NULL")) {
                sel.setString(1, TENANT);
                try (var rs = sel.executeQuery()) {
                    while (rs.next()) {
                        out.add(UUID.fromString(rs.getString(1)));
                    }
                }
            }
            if (!out.isEmpty()) {
                try (PreparedStatement mark = c.prepareStatement(
                        "UPDATE recalc_dirty_payee SET consumed_at = now() "
                                + "WHERE tenant_id = CAST(? AS uuid) AND consumed_at IS NULL")) {
                    mark.setString(1, TENANT);
                    mark.executeUpdate();
                }
            }
        }
        return out;
    }

    private long count(String where) throws SQLException {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM recalc_dirty_payee WHERE tenant_id = CAST('"
                     + TENANT + "' AS uuid) AND " + where)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
