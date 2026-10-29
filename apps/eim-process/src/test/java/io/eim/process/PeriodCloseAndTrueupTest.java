package io.eim.process;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG4-GOV-2/3/5 (live AGE postgres): the period-close process runs its
 * gates → review → SoD sign-off → PERIOD_LOCK (partition read-only, status
 * LOCKED); SoD is enforced (same actor cannot review and sign); the
 * true-up queue receives shadow-replay diffs and Finance approval posts
 * the typed entry with origin linkage; retro CRM changes route into the
 * queue (regression, FR-604.1).
 */
@QuarkusTest
class PeriodCloseAndTrueupTest {

    private static final String TENANT = "bbbbbbbb-b000-0000-0000-000000000001";
    private static final String MGR = "bbbbbbbb-b000-0000-0000-000000000002";
    private static final String REP = "bbbbbbbb-b000-0000-0000-000000000003";
    private static final String PERIOD = "bbbbbbbb-b000-0000-0000-000000000004";
    private static final String OPEN = "bbbbbbbb-b000-0000-0000-000000000005";

    @Inject
    AgroalDataSource ds;

    @BeforeEach
    void seed() throws SQLException {
        clean();
        try (Connection c = ds.getConnection()) {
            sql(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, effective_start_date) VALUES (CAST('" + MGR + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),'PC-0','Close Manager','pc.mgr@example.test',"
                    + "'Mgr',DATE '2026-01-01')");
            sql(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, manager_payee_id, effective_start_date) "
                    + "VALUES (CAST('" + REP + "' AS uuid),CAST('" + TENANT + "' AS uuid),"
                    + "'PC-1','Close Rep','pc.rep@example.test','AE',CAST('" + MGR
                    + "' AS uuid),DATE '2026-01-01')");
            sql(c, "INSERT INTO payout_period (payout_period_id, tenant_id, period_key, "
                    + "starts_on, ends_on, status) VALUES (CAST('" + PERIOD + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),'2026-pc','2026-04-01','2026-04-30','OPEN')");
            sql(c, "INSERT INTO payout_period (payout_period_id, tenant_id, period_key, "
                    + "starts_on, ends_on, status) VALUES (CAST('" + OPEN + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),'2026-may','2026-05-01','2026-05-31','OPEN')");
            sql(c, "SELECT eim_ops.ensure_period_partition(CAST('" + PERIOD
                    + "' AS uuid), '2026-pc')");
            // every payee has a CALCULATED entry → completeness gate passes
            sql(c, "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                    + "payee_id, component_name, payout_amount, entry_type, calculation_trace, "
                    + "is_locked, transaction_id) VALUES (gen_random_uuid(),CAST('" + TENANT
                    + "' AS uuid),CAST('" + PERIOD + "' AS uuid),CAST('" + REP
                    + "' AS uuid),'commission',19500,'CALCULATED',CAST('{}' AS jsonb),false,NULL)");
            sql(c, "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                    + "payee_id, component_name, payout_amount, entry_type, calculation_trace, "
                    + "is_locked, transaction_id) VALUES (gen_random_uuid(),CAST('" + TENANT
                    + "' AS uuid),CAST('" + PERIOD + "' AS uuid),CAST('" + MGR
                    + "' AS uuid),'team_commission',19500,'CALCULATED',CAST('{}' AS jsonb),false,NULL)");
        }
    }

    @AfterEach
    void cleanup() throws SQLException {
        clean();
    }

    private void clean() throws SQLException {
        try (Connection c = ds.getConnection()) {
            sql(c, "DELETE FROM process_task WHERE process_id IN "
                    + "(SELECT process_id FROM process_instance WHERE tenant_id = CAST('"
                    + TENANT + "' AS uuid))");
            sql(c, "DELETE FROM process_instance WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM trueup_review WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM commission_ledger_entry WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "SELECT eim_ops.detach_delete_payee(CAST('" + MGR + "' AS uuid))");
            sql(c, "SELECT eim_ops.detach_delete_payee(CAST('" + REP + "' AS uuid))");
            sql(c, "DELETE FROM payee WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM payout_period WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
        }
    }

    private void sql(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private String scalar(String sql) {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @TestSecurity(user = "close.admin", roles = {"comp_admin"})
    void periodCloseGatesSignoffLocksAndBlocksWrites() {
        // start: gates pass
        var resp = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "close.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("tenantId", TENANT, "payoutPeriodId", PERIOD))
                .when().post("/api/v1/process/period-close/start")
                .then().extract().response();
        org.junit.jupiter.api.Assertions.assertEquals(202, resp.getStatusCode(),
                "start: " + resp.getBody().asString());
        String processId = resp.jsonPath().getString("processId");
        assertNotNull(processId);

        // comp_admin review (own role)
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "close.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("comment", "reviewed"))
                .when().post("/api/v1/process/period-close/" + processId + "/tasks/comp_admin_review")
                .then().statusCode(202);

        // the SAME actor may not sign (SoD)
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "close.admin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("comment", "signoff"))
                .when().post("/api/v1/process/period-close/" + processId + "/tasks/finance_signoff")
                .then().statusCode(403);

        // finance signs (different actor)
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "fin.chief")
                .header("X-Roles", "finance_approver")
                .body(Map.of("comment", "signed off"))
                .when().post("/api/v1/process/period-close/" + processId + "/tasks/finance_signoff")
                .then().statusCode(202);

        // PERIOD_LOCK service task
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "fin.chief")
                .header("X-Roles", "finance_approver")
                .body(Map.of())
                .when().post("/api/v1/process/period-close/" + processId + "/tasks/period_lock")
                .then().statusCode(202);

        // period now LOCKED and the partition read-only
        assertEquals("LOCKED", scalar("SELECT status FROM payout_period WHERE payout_period_id = '"
                + PERIOD + "'"));
        // writes into the locked partition fail
        boolean rejected = false;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                             + "payee_id, component_name, payout_amount, entry_type, is_locked) "
                             + "VALUES (gen_random_uuid(),CAST(? AS uuid),CAST(? AS uuid),"
                             + "CAST(? AS uuid),'x',1,'CALCULATED',false)")) {
            ps.setObject(1, UUID.fromString(TENANT));
            ps.setObject(2, UUID.fromString(PERIOD));
            ps.setObject(3, UUID.fromString(REP));
            ps.executeUpdate();
        } catch (SQLException e) {
            rejected = true;
        }
        assertTrue(rejected, "locked partition rejects writes (NFR-44)");

        // export handoff completes the process
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "close.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("comment", "handed off"))
                .when().post("/api/v1/process/period-close/" + processId + "/tasks/export_handoff")
                .then().statusCode(202);
        assertEquals("COMPLETED", scalar("SELECT state FROM process_instance WHERE process_id = '"
                + processId + "'"));
        assertTrue(Integer.parseInt(scalar("SELECT count(*) FROM audit_log "
                + "WHERE action_type = 'APPROVAL' AND source_channel = 'PROCESS'")) >= 4,
                "every transition audited");
    }

    @Test
    @TestSecurity(user = "close.admin", roles = {"comp_admin"})
    void completenessGateBlocksCloseWhenPayeeHasNoEntry() throws SQLException {
        try (Connection c = ds.getConnection()) {
            sql(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, effective_start_date) VALUES (CAST('" + LONER
                    + "' AS uuid),CAST('" + TENANT + "' AS uuid),'PC-9','Loner',"
                    + "'loner@example.test','AE',DATE '2026-01-01')");
        }
        var resp = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "close.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("tenantId", TENANT, "payoutPeriodId", PERIOD))
                .when().post("/api/v1/process/period-close/start")
                .then().statusCode(409)
                .extract().jsonPath();
        assertEquals("1", resp.getString("payeesWithoutCalculatedEntry"));
        try (Connection c = ds.getConnection()) {
            sql(c, "SELECT eim_ops.detach_delete_payee(CAST('" + LONER + "' AS uuid))");
            sql(c, "DELETE FROM payee WHERE payee_id = CAST('" + LONER + "' AS uuid)");
        }
    }

    private static final String LONER = "bbbbbbbb-b000-0000-0000-000000000099";

    @Test
    @TestSecurity(user = "fin.chief", roles = {"finance_approver"})
    void trueupQueueRoutesFinanceApprovalAndPosts() throws SQLException {
        // a retro change: shadow replay enqueued a diff (simulated insert via
        // the calc-engine's queue path semantics)
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO trueup_review (trueup_id, tenant_id, origin_period_id, "
                             + "open_period_id, payee_id, diff_amount, report) "
                             + "VALUES (gen_random_uuid(),CAST(? AS uuid),CAST(? AS uuid),"
                             + "CAST(? AS uuid),CAST(? AS uuid),?,CAST(? AS jsonb))")) {
            ps.setObject(1, UUID.fromString(TENANT));
            ps.setObject(2, UUID.fromString(PERIOD));
            ps.setObject(3, UUID.fromString(OPEN));
            ps.setObject(4, UUID.fromString(REP));
            ps.setBigDecimal(5, new java.math.BigDecimal("8000"));
            ps.setString(6, "{\"origin_period_id\":\"" + PERIOD + "\"}");
            ps.executeUpdate();
        }

        var queue = given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "fin.chief")
                .header("X-Roles", "finance_approver")
                .when().get("/api/v1/process/trueup")
                .then().statusCode(200)
                .extract().jsonPath();
        assertEquals(1, queue.getList("$").size(), "diff report queued");
        String trueupId = queue.getString("[0].trueupId");

        // approve with a note
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "fin.chief")
                .header("X-Roles", "finance_approver")
                .body(Map.of("decision", "APPROVE", "note", "verified against CRM export"))
                .when().post("/api/v1/process/trueup/" + trueupId + "/decision")
                .then().statusCode(202);

        assertEquals("POSTED", scalar("SELECT status FROM trueup_review WHERE trueup_id = '"
                + trueupId + "'"));
        String postedTrace = scalar(
                "SELECT calculation_trace->>'origin_period_id' FROM commission_ledger_entry "
                        + "WHERE entry_type = 'PRIOR_PERIOD_TRUEUP' AND payout_period_id = '"
                        + OPEN + "'");
        assertEquals(PERIOD, postedTrace, "posted entry carries origin linkage");
    }
}
