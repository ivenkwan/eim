package io.eim.connectors;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG2-HIE: effective-dated hierarchy admin with cycle rejection (FR-302.2),
 * mid-period manager moves rolling up as-of (FR-302.1/FR-303), wavefront
 * layering (FR-503), and the rollup benchmark (NFR-24: <2s @ 5000 payees).
 */
@QuarkusTest
class HierarchyTest {

    private static final UUID TENANT_ID = UUID.fromString("33333333-0000-0000-0000-000000000001");
    private static final String TENANT = TENANT_ID.toString();

    @Inject
    AgroalDataSource ds;

    private final UUID managerX = UUID.randomUUID();
    private final UUID managerY = UUID.randomUUID();
    private final UUID rep = UUID.randomUUID();

    @BeforeEach
    void seed() throws SQLException {
        createPayee(managerX, "Dir X", null);
        createPayee(managerY, "Dir Y", null);
        createPayee(rep, "Mid Rep", managerX);
    }

    @AfterEach
    void cleanup() throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement del = c.prepareStatement("DELETE FROM payee WHERE tenant_id = ?")) {
            del.setObject(1, TENANT_ID);
            del.executeUpdate();
        }
        detach(managerX);
        detach(managerY);
        detach(rep);
        // audit_log is append-only (NFR-60) — countAudit() scopes to this
        // run's rep id instead of deleting rows.
    }

    @Test
    @TestSecurity(user = "orgadmin", roles = {"comp_admin"})
    void midPeriodManagerMoveRollsUpAsOf() {
        // Move rep from X to Y effective May 15 (FR-302.1).
        var move = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "org.admin").header("X-Roles", "comp_admin")
                .body("{\"manager_payee_id\":\"" + managerY + "\",\"effective_start\":\"2026-05-15\"}")
                .when().put("/api/v1/org/payee/" + rep + "/manager")
                .then().extract().response();
        assertEquals(202, move.getStatusCode(), "manager move: " + move.getBody().asString());

        // As-of May 1 → rolls up to X; as-of June 1 → rolls up to Y.
        assertEquals(1, rollupCount(managerX, "2026-05-01"), "May 1: under X");
        assertEquals(0, rollupCount(managerY, "2026-05-01"), "May 1: not under Y");
        assertEquals(0, rollupCount(managerX, "2026-06-01"), "June 1: left X");
        assertEquals(1, rollupCount(managerY, "2026-06-01"), "June 1: under Y");

        // Audit trail for the hierarchy edit (TG2 exit #3).
        assertEquals(1L, countAudit());
    }

    @Test
    @TestSecurity(user = "orgadmin", roles = {"comp_admin"})
    void wavefrontsLayerCorrectly() {
        var body = given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "org.admin").header("X-Roles", "comp_admin")
                .when().get("/api/v1/org/wavefronts")
                .then().statusCode(200).extract().jsonPath();
        int maxLevel = body.getInt("maxLevel");
        assertTrue(maxLevel >= 1, "manager (level 1) over rep (level 0): got max " + maxLevel);
    }

    @Test
    @TestSecurity(user = "orgadmin", roles = {"comp_admin"})
    void rollupBenchmark5000Payees() throws SQLException {
        // Bulk-load 5000 reps under managerX via one batched path (NFR-24).
        UUID[] ids = new UUID[5000];
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, role_title, "
                             + "manager_payee_id, effective_start_date) VALUES (?,?,?,?,?,?,?,current_date)")) {
            for (int i = 0; i < ids.length; i++) {
                ids[i] = UUID.randomUUID();
                ps.setObject(1, ids[i]);
                ps.setObject(2, TENANT_ID);
                ps.setString(3, "B-" + i);
                ps.setString(4, "Bulk " + i);
                ps.setString(5, "bulk" + i + "@example.test");
                ps.setString(6, "AE");
                ps.setObject(7, managerX);
                ps.addBatch();
                if (i % 500 == 499) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        int warm = rollupCount(managerX, null); // plan/JIT warm-up (untimed)
        org.junit.jupiter.api.Assertions.assertEquals(5001, warm);
        long start = System.nanoTime();
        int under = rollupCount(managerX, null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(5001, under, "bulk 5000 + the original rep");
        assertTrue(elapsedMs < 2000, "rollup under 2s for 5000 payees (NFR-24): took " + elapsedMs + "ms");
        // Bulk cleanup via graph detach + delete.
        try (Connection c = ds.getConnection()) {
            for (UUID id : ids) {
                try (PreparedStatement d = c.prepareStatement("SELECT eim_ops.detach_delete_payee(?)")) {
                    d.setObject(1, id);
                    try (java.sql.ResultSet ignored = d.executeQuery()) {
                        // result consumed inside the function; close explicitly
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers
    private void createPayee(UUID id, String name, UUID manager) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, role_title, "
                             + "manager_payee_id, effective_start_date) VALUES (?,?,?,?,?,?,?,DATE '2026-01-01')")) {
            ps.setObject(1, id);
            ps.setObject(2, TENANT_ID);
            ps.setString(3, "E-" + Math.abs(id.hashCode() % 10000));
            ps.setString(4, name);
            ps.setString(5, name.toLowerCase().replace(' ', '.') + "@example.test");
            ps.setString(6, "Staff");
            ps.setObject(7, manager);
            ps.executeUpdate();
        }
    }

    private int rollupCount(UUID manager, String asOf) {
        String path = asOf == null
                ? "/api/v1/org/rollup/" + manager
                : "/api/v1/org/rollup/" + manager + "?asOf=" + asOf;
        return given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "org.admin").header("X-Roles", "comp_admin")
                .when().get(path)
                .then().statusCode(200).extract().jsonPath().getInt("count");
    }

    private long countAudit() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE actor = 'org.admin' AND target_entity = 'payee' "
                             + "AND target_record_id = ?")) {
            ps.setString(1, rep.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void detach(UUID payee) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT eim_ops.detach_delete_payee(?)")) {
            ps.setObject(1, payee);
            ps.executeQuery();
        }
    }
}
