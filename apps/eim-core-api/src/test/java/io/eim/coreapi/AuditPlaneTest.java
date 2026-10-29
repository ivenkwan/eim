package io.eim.coreapi;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG1-AUD-1/3/6: append-only enforcement, atomic audit+outbox writes, and the
 * hash-chain sealer with tamper detection (FR-601.3/601.4, NFR-60).
 */
@QuarkusTest
class AuditPlaneTest {

    @Inject
    AgroalDataSource ds;

    private long count(String table, String where) throws SQLException {
        String sql = "SELECT count(*) FROM " + table + " WHERE " + where;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void insertAudit(String actor) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log (actor, actor_type, action_type, target_entity, occurred_at) "
                             + "VALUES (?,?,?,?, now() - interval '1 day')")) {
            ps.setString(1, actor);
            ps.setString(2, "USER");
            ps.setString(3, "PLAN_EDIT");
            ps.setString(4, "test");
            ps.executeUpdate();
        }
    }

    @Test
    void auditRowsAreAppendOnly() throws SQLException {
        insertAudit("tamper-test");
        try (Connection c = ds.getConnection();
             PreparedStatement u = c.prepareStatement("UPDATE audit_log SET actor = 'hacked' "
                     + "WHERE actor = 'tamper-test'");
             PreparedStatement d = c.prepareStatement("DELETE FROM audit_log WHERE actor = 'tamper-test'")) {
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
                     "INSERT INTO audit_seals (seal_day, row_count, seal_hash) VALUES ('2000-01-01', 1, 'x')")) {
            ev.executeUpdate();
            cc.executeUpdate();
            seal.executeUpdate(); // left behind by design (append-only): seal test wipes via replica bypass
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
        // Two audit rows on yesterday; seal; verify; tamper (trigger bypassed
        // via replica role — the same power an intrusive DBA would need);
        // verify again and expect failure.
        insertAudit("seal-a");
        insertAudit("seal-b");
        LocalDate yesterday = LocalDate.now().minusDays(1);

        try (Connection c = ds.getConnection();
             PreparedStatement bypass = c.prepareStatement("SET session_replication_role = replica");
             PreparedStatement wipe = c.prepareStatement("DELETE FROM audit_seals");
             PreparedStatement restore = c.prepareStatement("SET session_replication_role = DEFAULT")) {
            bypass.executeUpdate();
            wipe.executeUpdate(); // clean slate: any pre-existing seal chains too
            restore.executeUpdate(); // never leak trigger-bypass to the pool
        }

        try (Connection c = ds.getConnection();
             PreparedStatement seal = c.prepareStatement("SELECT eim_ops.seal_audit_day(?)")) {
            seal.setDate(1, java.sql.Date.valueOf(yesterday));
            try (ResultSet rs = seal.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(64, rs.getString(1).length(), "sha256 hex seal");
            }
        }

        boolean ok = verifyAll();
        assertTrue(ok, "chain verifies before tampering");

        // Tamper: flip an actor value with the trigger bypassed (superuser path).
        try (Connection c = ds.getConnection();
             PreparedStatement bypass = c.prepareStatement("SET session_replication_role = replica");
             PreparedStatement tamper = c.prepareStatement(
                     "UPDATE audit_log SET actor = 'injected' WHERE actor = 'seal-a'");
             PreparedStatement restore = c.prepareStatement("SET session_replication_role = DEFAULT")) {
            bypass.executeUpdate();
            tamper.executeUpdate();
            restore.executeUpdate(); // never leak trigger-bypass to the pool
        }

        assertFalseAnySealBroken();
    }

    private boolean verifyAll() throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT coalesce(bool_and(ok), true) FROM eim_ops.verify_audit_seals()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private void assertFalseAnySealBroken() throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM eim_ops.verify_audit_seals() WHERE ok = false");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertTrue(rs.getLong(1) >= 1, "tampered day must fail verification (NFR-60)");
        }
    }
}
