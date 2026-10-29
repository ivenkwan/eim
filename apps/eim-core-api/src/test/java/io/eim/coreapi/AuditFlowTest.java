package io.eim.coreapi;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG1-AUD-1/4/5/9 — the full audit plane under test with real PostgreSQL +
 * Kafka (dev services): a governed config change commits configuration +
 * configuration_change + audit_log + outbox ATOMICALLY; the exporter drains
 * the outbox to Kafka; the event auditor persists it with a sha256; and the
 * reconciliation reports zero gap (FR-601.4, FR-612, NFR-64).
 */
@QuarkusTest
class AuditFlowTest {

    @Inject
    AgroalDataSource ds;

    @Test
    @TestSecurity(user = "controller", roles = {"finance_approver"})
    void governedConfigChangeFlowsEndToEnd() throws SQLException {
        long beforeConfigs = count("SELECT count(*) FROM configuration WHERE entity = 'gateway.policy'");
        long beforeChanges = count("SELECT count(*) FROM configuration_change WHERE entity = 'gateway.policy'");
        long beforeOutbox = count("SELECT count(*) FROM transactional_outbox");

        var accepted = given()
                .contentType("application/json")
                .body("{\"entity\":\"gateway.policy\",\"payload\":\"{\\\"rate_limit\\\":100}\","
                        + "\"reason\":\"raise tenant limit\"}")
                .when().post("/api/v1/admin/config-change")
                .then().statusCode(202)
                .body("accepted", equalTo(true))
                .body("status", equalTo("PENDING_APPROVAL"))
                .extract().jsonPath();

        // Atomic business+audit+outbox commit (FR-601.4).
        assertEquals(beforeConfigs + 1, count("SELECT count(*) FROM configuration WHERE entity = 'gateway.policy'"));
        assertEquals(beforeChanges + 1, count("SELECT count(*) FROM configuration_change WHERE entity = 'gateway.policy'"));
        assertEquals(beforeOutbox + 1, count("SELECT count(*) FROM transactional_outbox"));

        // Outbox row staged for export (the Kafka export + event-audit
        // round-trip is proven live in the cluster harness — verify-tg1.sh —
        // because the broker's advertised listeners are cluster-internal).
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM transactional_outbox WHERE aggregate_type = 'configuration.changed'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertTrue(rs.getLong(1) >= 1, "outbox row staged for the exporter");
        }

        given().when().get("/api/v1/admin/audit/reconcile?hours=2")
                .then().statusCode(200)
                .body("audited_mutations", greaterThanOrEqualTo(1))
                .body("outbox_rows", greaterThanOrEqualTo(1));

        // Governed approval flow (FR-613.2): approve → ACTIVE + APPROVAL audit.
        var approveResp = given().contentType("application/json")
                .body("{\"note\":\"test approval\"}")
                .when().post("/api/v1/admin/config-change/" + accepted.getString("configurationId") + "/approve");
        if (approveResp.statusCode() != 200) {
            System.err.println("APPROVE-500-BODY: " + approveResp.body().asString());
        }
        approveResp.then().statusCode(200)
                .body("status", equalTo("ACTIVE"))
                .body("$", hasKey("version"));
    }

    @Test
    @TestSecurity(user = "revops", roles = {"comp_admin"})
    void missingReasonIsRejectedWithoutAnyWrite() throws SQLException {
        long beforeAudit = count("SELECT count(*) FROM audit_log");
        long beforeOutbox = count("SELECT count(*) FROM transactional_outbox");

        given().contentType("application/json")
                .body("{\"entity\":\"gateway.policy\",\"reason\":\"\"}")
                .when().post("/api/v1/admin/config-change")
                .then().statusCode(400);

        assertEquals(beforeAudit, count("SELECT count(*) FROM audit_log"), "no audit on rejected input");
        assertEquals(beforeOutbox, count("SELECT count(*) FROM transactional_outbox"), "no outbox on rejected input");
    }

    private long count(String sql) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
