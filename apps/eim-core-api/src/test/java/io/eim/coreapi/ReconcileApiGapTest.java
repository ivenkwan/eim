package io.eim.coreapi;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TG9-A2 (NFR-64): the reconciliation alert keys on the API-plane gap.
 * AuditService-written mutations pair with outbox rows (api_gap = 0 even
 * while raw_gap > 0); process-transition rows (source_channel='PROCESS')
 * and direct-SQL seeds inflate only the raw gap — they are audited-only by
 * design and must not page anyone.
 */
@QuarkusTest
class ReconcileApiGapTest {

    private static final String TENANT = "aaaaaaaa-9000-0000-0000-000000000001";

    @Inject
    AgroalDataSource ds;

    // audit_log is append-only (NFR-60) so seeded rows are left behind —
    // they carry tg9-* markers and the assertions are before/after deltas,
    // which tolerates accumulation on the shared dev DB. Only the outbox
    // rows (no append-only trigger) are cleaned.
    @AfterEach
    void cleanup() throws SQLException {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM transactional_outbox WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
        }
    }

    private long[] reconcile() throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM eim_ops.reconcile_audit_full(?)")) {
            ps.setTimestamp(1, Timestamp.from(OffsetDateTime.now().minusMinutes(5).toInstant()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[]{rs.getLong(5), rs.getLong(7)}; // api_mutations, api_gap
            }
        }
    }

    @Test
    void apiMutationsPairWithOutboxWhileRawRowsDoNot() throws SQLException {
        long[] before = reconcile();
        // paired API mutation: audit + outbox in one transaction (the FR-601.4 shape)
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement a = c.prepareStatement(
                    "INSERT INTO audit_log (tenant_id, actor, action_type, target_entity, "
                            + "target_record_id, source_channel) VALUES (?,?, 'CONFIG_CHANGE',"
                            + "'x','tg9-paired','API')");
                 PreparedStatement o = c.prepareStatement(
                         "INSERT INTO transactional_outbox (aggregate_type, aggregate_id, payload, "
                                 + "tenant_id) VALUES ('t','tg9-paired','{}',CAST(? AS uuid))")) {
                a.setObject(1, java.util.UUID.fromString(TENANT));
                a.setString(2, "tg9.actor");
                a.executeUpdate();
                o.setObject(1, java.util.UUID.fromString(TENANT));
                o.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
            // audited-only rows: a process transition + a direct-SQL seed
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO audit_log (tenant_id, actor, action_type, target_entity, "
                        + "target_record_id, source_channel) VALUES (CAST('" + TENANT
                        + "' AS uuid),'engine','APPROVAL','process_instance','tg9-proc','PROCESS')");
            }
        }
        long[] r = reconcile();
        // deltas against the pre-seed snapshot (the shared dev DB carries
        // other tests' rows in the window)
        assertEquals(before[0] + 1, r[0], "api_mutations counts only the API channel");
        assertEquals(before[1], r[1], "api_gap stays 0 — process/seed rows are audited-only");
    }

    @Test
    void unpairedApiMutationRaisesTheAlert() throws SQLException {
        long[] before = reconcile();
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO audit_log (tenant_id, actor, action_type, target_entity, "
                    + "target_record_id, source_channel) VALUES (CAST('" + TENANT
                    + "' AS uuid),'tg9.actor','CONFIG_CHANGE','x','tg9-orphan','API')");
        }
        long[] after = reconcile();
        assertEquals(before[1] + 1, after[1], "an API mutation without an outbox row IS the NFR-64 alert");
    }
}
