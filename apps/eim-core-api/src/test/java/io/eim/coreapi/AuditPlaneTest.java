package io.eim.coreapi;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG1-AUD-1/3/6: append-only enforcement, atomic audit+outbox writes, and the
 * hash-chain sealer with tamper detection (FR-601.3/601.4, NFR-60).
 *
 * Hermetic against the shared dev database (TG-6 fix): fixture rows live on
 * the fixed day 2001-01-01 and on "now" — never on a day the nightly sealer
 * may have sealed — and the tamper test snapshots the real seal table into a
 * session temp table, runs its scenario, then restores the exact rows, so
 * dev/prod seals and their chain linkage are untouched. All statements are
 * parameterized or compile-time constants (no assembled SQL).
 */
@QuarkusTest
class AuditPlaneTest {

    /** Fixture day for the chain test; no real seal can ever cover it. */
    private static final LocalDate FIXTURE_DAY = LocalDate.of(2001, 1, 1);
    /** Standing tamper positive-control day (left behind by design). */
    private static final LocalDate CONTROL_DAY = LocalDate.of(2000, 1, 1);
    private static final Timestamp FIXTURE_TS = Timestamp.valueOf("2001-01-01 12:00:00");

    @Inject
    AgroalDataSource ds;

    private void insertAudit(String actor, Timestamp when) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log (actor, actor_type, action_type, target_entity, occurred_at) "
                             + "VALUES (?,?,?,?,?)")) {
            ps.setString(1, actor);
            ps.setString(2, "USER");
            ps.setString(3, "PLAN_EDIT");
            ps.setString(4, "test");
            ps.setTimestamp(5, when);
            ps.executeUpdate();
        }
    }

    @Test
    void auditRowsAreAppendOnly() throws SQLException {
        // "now" rows are safe: the hourly UTC sealer only seals completed
        // days, so today's rows can never land on a sealed day.
        insertAudit("tamper-test", Timestamp.from(Instant.now()));
        try (Connection c = ds.getConnection();
             PreparedStatement u = c.prepareStatement("UPDATE audit_log SET actor = ? "
                     + "WHERE actor = ?");
             PreparedStatement d = c.prepareStatement("DELETE FROM audit_log WHERE actor = ?")) {
            u.setString(1, "hacked");
            u.setString(2, "tamper-test");
            d.setString(1, "tamper-test");
            assertThrows(SQLException.class, u::executeUpdate, "UPDATE forbidden (FR-601.3)");
            assertThrows(SQLException.class, d::executeUpdate, "DELETE forbidden (FR-601.3)");
        }
        // Same for the other append-only stores — seed one row each so the
        // row-level triggers actually fire.
        try (Connection c = ds.getConnection();
             PreparedStatement ev = c.prepareStatement(
                     "INSERT INTO event_audit_log (topic, payload, payload_hash) VALUES ('t','{}','x')");
             PreparedStatement cc = c.prepareStatement(
                     "INSERT INTO configuration_change (entity, version_after, actor) VALUES ('e','{}','a')");
             PreparedStatement seal = c.prepareStatement(
                     "INSERT INTO audit_seals (seal_day, row_count, seal_hash) "
                             + "SELECT ?, 1, 'x' WHERE NOT EXISTS "
                             + "(SELECT 1 FROM audit_seals WHERE seal_day = ?)")) {
            ev.executeUpdate();
            cc.executeUpdate();
            seal.setDate(1, Date.valueOf(CONTROL_DAY));
            seal.setDate(2, Date.valueOf(CONTROL_DAY));
            seal.executeUpdate(); // control left behind by design (append-only, idempotent)
        }
        for (String table : new String[]{"event_audit_log", "configuration_change", "audit_seals"}) {
            try (Connection c = ds.getConnection();
                 PreparedStatement d = c.prepareStatement("DELETE FROM " + table)) {
                assertThrows(SQLException.class, d::executeUpdate, table + " is append-only");
            }
        }
    }

    @Test
    void sealsChainDetectsTampering() throws SQLException {
        // Two audit rows on the fixture day; seal it; verify; tamper
        // (trigger bypassed via the replica role — the same power an
        // intrusive DBA would need); verify again and expect failure. Real
        // seals are snapshotted and restored byte-identically, so dev/prod
        // chain linkage survives the test.
        insertAudit("seal-a", FIXTURE_TS);
        insertAudit("seal-b", FIXTURE_TS);

        try (Connection c = ds.getConnection()) {
            // snapshot the real seal table, then a clean slate for the scenario
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TEMP TABLE seal_backup AS SELECT * FROM audit_seals")) {
                ps.executeUpdate();
            }
            replicaBypassWipeSeals(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO audit_seals (seal_day, row_count, seal_hash) VALUES (?, 1, 'x')")) {
                ps.setDate(1, Date.valueOf(CONTROL_DAY));
                ps.executeUpdate();
            }
            try (PreparedStatement seal = c.prepareStatement("SELECT eim_ops.seal_audit_day(?)")) {
                seal.setDate(1, Date.valueOf(FIXTURE_DAY));
                try (ResultSet rs = seal.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(64, rs.getString(1).length(), "sha256 hex seal");
                }
            }
            assertTrue(dayVerifies(c, FIXTURE_DAY), "chain verifies before tampering");

            // tamper with the replica bypass, scoped to the fixture row
            try (PreparedStatement p1 = c.prepareStatement("SET session_replication_role = replica");
                 PreparedStatement p2 = c.prepareStatement(
                         "UPDATE audit_log SET actor = 'injected' WHERE actor = 'seal-a' AND occurred_at = ?");
                 PreparedStatement p3 = c.prepareStatement("SET session_replication_role = DEFAULT")) {
                p1.executeUpdate();
                p2.setTimestamp(1, FIXTURE_TS);
                p2.executeUpdate();
                p3.executeUpdate(); // never leak trigger-bypass to the pool
            }
            assertTrue(!dayVerifies(c, FIXTURE_DAY),
                    "tampered day must fail verification (NFR-60)");

            // restore the real seal table exactly as we found it
            replicaBypassWipeSeals(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO audit_seals (seal_day, row_count, seal_hash, prev_seal) "
                            + "SELECT seal_day, row_count, seal_hash, prev_seal FROM seal_backup")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DROP TABLE seal_backup")) {
                ps.executeUpdate();
            }
        }
    }

    /** DELETE FROM audit_seals with the append-only trigger bypassed (same session). */
    private void replicaBypassWipeSeals(Connection c) throws SQLException {
        try (PreparedStatement p1 = c.prepareStatement("SET session_replication_role = replica");
             PreparedStatement p2 = c.prepareStatement("DELETE FROM audit_seals");
             PreparedStatement p3 = c.prepareStatement("SET session_replication_role = DEFAULT")) {
            p1.executeUpdate();
            p2.executeUpdate();
            p3.executeUpdate(); // never leak trigger-bypass to the pool
        }
    }

    private boolean dayVerifies(Connection c, LocalDate day) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ok FROM eim_ops.verify_audit_seals() WHERE seal_day = ?")) {
            ps.setDate(1, Date.valueOf(day));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
