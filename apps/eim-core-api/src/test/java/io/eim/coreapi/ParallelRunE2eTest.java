package io.eim.coreapi;

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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG6-ONB-1..4 + ONB-8 (MI-18) on live AGE postgres: partner onboarding
 * registry + lifecycle gates, legacy statement upload, cent-for-cent diff
 * with triage, the 100.0% sign-off gate with evidence hash, two consecutive
 * sign-offs unlocking SIGNED_OFF→LIVE, and the dual-approval break-glass
 * reopen (two distinct approvers, PERIOD_REOPEN + APPROVAL ×2 audit).
 */
@QuarkusTest
class ParallelRunE2eTest {

    private static final String TENANT = "aaaaaaaa-c000-0000-0000-000000000001";
    private static final String PAYEE_A = "aaaaaaaa-c000-0000-0000-000000000002";
    private static final String PAYEE_B = "aaaaaaaa-c000-0000-0000-000000000003";
    private static final String PERIOD_1 = "aaaaaaaa-c000-0000-0000-000000000004";
    private static final String PERIOD_2 = "aaaaaaaa-c000-0000-0000-000000000005";

    @Inject
    AgroalDataSource ds;

    private String partnerId;

    @BeforeEach
    void seed() throws SQLException {
        clean();
        try (Connection c = ds.getConnection()) {
            sql(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, effective_start_date) VALUES (CAST('" + PAYEE_A
                    + "' AS uuid),CAST('" + TENANT + "' AS uuid),'P-1','Parallel Rep A',"
                    + "'pr.a@example.test','AE',DATE '2026-01-01')");
            sql(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, effective_start_date) VALUES (CAST('" + PAYEE_B
                    + "' AS uuid),CAST('" + TENANT + "' AS uuid),'P-2','Parallel Rep B',"
                    + "'pr.b@example.test','AE',DATE '2026-01-01')");
            sql(c, "INSERT INTO payout_period (payout_period_id, tenant_id, period_key, "
                    + "starts_on, ends_on, status) VALUES (CAST('" + PERIOD_1 + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),'2026-par1','2026-05-01','2026-05-31','OPEN')");
            sql(c, "INSERT INTO payout_period (payout_period_id, tenant_id, period_key, "
                    + "starts_on, ends_on, status) VALUES (CAST('" + PERIOD_2 + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),'2026-par2','2026-06-01','2026-06-30','OPEN')");
            sql(c, "SELECT eim_ops.ensure_period_partition(CAST('" + PERIOD_1 + "' AS uuid), '2026-par1')");
            sql(c, "SELECT eim_ops.ensure_period_partition(CAST('" + PERIOD_2 + "' AS uuid), '2026-par2')");
            // period 1: A matches legacy exactly, B is one cent off
            ledger(c, PERIOD_1, PAYEE_A, "5000.00");
            ledger(c, PERIOD_1, PAYEE_B, "3000.00");
            // period 2: both match legacy
            ledger(c, PERIOD_2, PAYEE_A, "6000.00");
            ledger(c, PERIOD_2, PAYEE_B, "4000.00");
        }
        var resp = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("name", "Acme Partners", "idpEntityId", "https://idp.acme.test/saml",
                        "idpPosture", "SAML_FEDERATED", "payeeTarget", 250))
                .when().post("/api/v1/partners")
                .then().statusCode(200)
                .extract().jsonPath();
        partnerId = resp.getString("partnerId");
        assertEquals("SAML_FEDERATED", resp.getString("idpPosture"));
    }

    private void ledger(Connection c, String period, String payee, String amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                        + "payee_id, component_name, payout_amount, entry_type, calculation_trace, "
                        + "is_locked) VALUES (gen_random_uuid(),CAST(? AS uuid),CAST(? AS uuid),"
                        + "CAST(? AS uuid),'commission',?,'CALCULATED',CAST('{}' AS jsonb),false)")) {
            ps.setString(1, TENANT);
            ps.setString(2, period);
            ps.setString(3, payee);
            ps.setBigDecimal(4, new java.math.BigDecimal(amount));
            ps.executeUpdate();
        }
    }

    @AfterEach
    void cleanup() throws SQLException {
        clean();
    }

    private void clean() throws SQLException {
        try (Connection c = ds.getConnection()) {
            sql(c, "DELETE FROM parallel_signoff");
            sql(c, "DELETE FROM parallel_diff");
            sql(c, "DELETE FROM legacy_statement");
            sql(c, "DELETE FROM period_reopen_request");
            sql(c, "DELETE FROM partner_tenant");
            sql(c, "DELETE FROM commission_ledger_entry WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "SELECT eim_ops.detach_delete_payee(CAST('" + PAYEE_A + "' AS uuid))");
            sql(c, "SELECT eim_ops.detach_delete_payee(CAST('" + PAYEE_B + "' AS uuid))");
            sql(c, "DELETE FROM payee WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM manual_override WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
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

    private void uploadLegacy(String periodKey, String payeeA, String payeeB) {
        given().contentType("text/csv")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin")
                .header("X-Roles", "finance_approver")
                .body("payee_id,legacy_payout\n" + PAYEE_A + "," + payeeA + "\n"
                        + PAYEE_B + "," + payeeB + "\n")
                .when().post("/api/v1/parallel-run/partners/" + partnerId
                        + "/periods/" + periodKey + "/legacy-statement")
                .then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "pr.payee", roles = {"payee"})
    void payeeRoleCannotRunOnboardingOrParallelRunSurfaces() {
        // seed() registered the partner via the comp_admin header; a payee
        // (no admin role merged in) is refused on every mutating surface
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.payee")
                .header("X-Roles", "payee")
                .body(Map.of("name", "Rogue"))
                .when().post("/api/v1/partners")
                .then().statusCode(403);

        given().contentType("text/csv")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.payee")
                .header("X-Roles", "payee")
                .body("payee_id,legacy_payout")
                .when().post("/api/v1/parallel-run/partners/" + partnerId
                        + "/periods/2026-par1/legacy-statement")
                .then().statusCode(403);

        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.payee")
                .header("X-Roles", "payee")
                .when().post("/api/v1/parallel-run/partners/" + partnerId
                        + "/periods/2026-par1/diff")
                .then().statusCode(403);

        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.payee")
                .header("X-Roles", "payee")
                .when().post("/api/v1/parallel-run/partners/" + partnerId
                        + "/periods/2026-par1/signoff")
                .then().statusCode(403);

        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.payee")
                .header("X-Roles", "payee")
                .body(Map.of("resolution", "RESOLVED_EXPLAINED", "note", "x"))
                .when().post("/api/v1/parallel-run/diffs/"
                        + "00000000-0000-0000-0000-000000000009/resolve")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "pr.admin", roles = {"comp_admin"})
    void onboardingRegistryAndLifecycleGates() {
        var list = given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.admin")
                .header("X-Roles", "comp_admin")
                .when().get("/api/v1/partners")
                .then().statusCode(200)
                .extract().jsonPath();
        assertEquals("ONBOARDING", list.getString("[0].status"));
        assertEquals("Acme Partners", list.getString("[0].name"));

        // comp_admin (no finance/sysadmin role) cannot touch the MI-18 break-glass
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("impactMemo", "missed accelerator batch"))
                .when().post("/api/v1/admin/periods/2026-par1/reopen")
                .then().statusCode(403);

        // comp_admin may not sign off a parallel period either
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.admin")
                .header("X-Roles", "comp_admin")
                .when().post("/api/v1/parallel-run/partners/" + partnerId
                        + "/periods/2026-par1/signoff")
                .then().statusCode(403);

        // one-step advance only: jumping straight to LIVE is refused
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("status", "LIVE"))
                .when().post("/api/v1/partners/" + partnerId + "/status")
                .then().statusCode(409);

        // SIGNED_OFF is gated on two 100% sign-offs — none exist yet
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("status", "BACKFILLED"))
                .when().post("/api/v1/partners/" + partnerId + "/status")
                .then().statusCode(200);
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("status", "PARALLEL_RUN"))
                .when().post("/api/v1/partners/" + partnerId + "/status")
                .then().statusCode(200);
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("status", "SIGNED_OFF"))
                .when().post("/api/v1/partners/" + partnerId + "/status")
                .then().statusCode(409);

        assertTrue(Integer.parseInt(scalar("SELECT count(*) FROM audit_log "
                + "WHERE action_type = 'CONFIG_CHANGE' AND reason_code = 'PARTNER_ONBOARD'")) >= 1,
                "onboarding audited");
    }

    @Test
    @TestSecurity(user = "pr.fin", roles = {"finance_approver"})
    void diffTriageSignoffGateAndTwoConsecutivePeriods() {
        uploadLegacy("2026-par1", "5000.00", "3000.01"); // B one cent over EIM
        uploadLegacy("2026-par2", "6000.00", "4000.00");

        // period 1 diff: 1 MATCH + 1 DISCREPANT
        var diff = given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin")
                .header("X-Roles", "finance_approver")
                .when().post("/api/v1/parallel-run/partners/" + partnerId
                        + "/periods/2026-par1/diff")
                .then().statusCode(200)
                .extract().jsonPath();
        assertEquals(2, (int) diff.getInt("payees"));
        assertEquals(1, (int) diff.getInt("matched"));
        assertEquals(1, (int) diff.getInt("discrepant"));

        var report = given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin")
                .header("X-Roles", "finance_approver")
                .when().get("/api/v1/parallel-run/partners/" + partnerId
                        + "/periods/2026-par1")
                .then().statusCode(200)
                .extract().jsonPath();
        assertEquals("50.00", report.getString("matchRate"));
        String diffId = null;
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> reportRows = report.getList("rows");
        for (Map<String, Object> row : reportRows) {
            if ("DISCREPANT".equals(row.get("status"))) {
                diffId = (String) row.get("diffId");
                assertEquals("-1", new java.math.BigDecimal((String) row.get("deltaCents"))
                        .stripTrailingZeros().toPlainString(), "one cent off");
            }
        }
        assertNotNull(diffId);

        // triage: note mandatory, then resolve as explained
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("resolution", "RESOLVED_EXPLAINED"))
                .when().post("/api/v1/parallel-run/diffs/" + diffId + "/resolve")
                .then().statusCode(400);
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("resolution", "RESOLVED_EXPLAINED", "note",
                        "legacy rounding on B's accelerator"))
                .when().post("/api/v1/parallel-run/diffs/" + diffId + "/resolve")
                .then().statusCode(200);
        // already resolved
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("resolution", "RESOLVED_EXPLAINED", "note", "again"))
                .when().post("/api/v1/parallel-run/diffs/" + diffId + "/resolve")
                .then().statusCode(409);

        // sign-off refused below 100% even though the row is triaged
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin")
                .header("X-Roles", "finance_approver")
                .when().post("/api/v1/parallel-run/partners/" + partnerId
                        + "/periods/2026-par1/signoff")
                .then().statusCode(409);

        // adjust EIM to legacy through the audited override path (MA-01),
        // re-diff → cent-for-cent → sign-off records evidence
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("payoutPeriodId", PERIOD_1, "payeeId", PAYEE_B,
                        "componentName", "commission", "adjustedAmount", "3000.01",
                        "reasonCode", "PARALLEL_ADJ", "reasonComment",
                        "align to legacy cent-for-cent"))
                .when().post("/api/v1/admin/overrides")
                .then().statusCode(202);

        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin")
                .header("X-Roles", "finance_approver")
                .when().post("/api/v1/parallel-run/partners/" + partnerId
                        + "/periods/2026-par1/diff")
                .then().statusCode(200)
                .body("matched", org.hamcrest.Matchers.equalTo(2));

        var signoff1 = given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin")
                .header("X-Roles", "finance_approver")
                .when().post("/api/v1/parallel-run/partners/" + partnerId
                        + "/periods/2026-par1/signoff")
                .then().statusCode(200)
                .extract().jsonPath();
        assertEquals("100.0000", signoff1.getString("matchRate"));
        assertEquals(64, signoff1.getString("evidenceHash").length(), "sha256 hex");

        // re-signoff refused
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin")
                .header("X-Roles", "finance_approver")
                .when().post("/api/v1/parallel-run/partners/" + partnerId
                        + "/periods/2026-par1/signoff")
                .then().statusCode(409);

        // period 2 matches on the first diff — the second consecutive sign-off
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin")
                .header("X-Roles", "finance_approver")
                .when().post("/api/v1/parallel-run/partners/" + partnerId
                        + "/periods/2026-par2/diff")
                .then().statusCode(200)
                .body("matched", org.hamcrest.Matchers.equalTo(2));
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin")
                .header("X-Roles", "finance_approver")
                .when().post("/api/v1/parallel-run/partners/" + partnerId
                        + "/periods/2026-par2/signoff")
                .then().statusCode(200);

        assertEquals("2", scalar("SELECT count(*) FROM parallel_signoff "
                + "WHERE partner_id = '" + partnerId + "' AND match_rate = 100.0"));
        assertTrue(Integer.parseInt(scalar("SELECT count(*) FROM audit_log "
                + "WHERE action_type = 'APPROVAL' AND reason_code = 'PARALLEL_SIGNOFF'")) >= 2,
                "sign-offs audited");

        // two consecutive 100% periods unlock SIGNED_OFF (gate from the
        // lifecycle test now passes)
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("status", "SIGNED_OFF"))
                .when().post("/api/v1/partners/" + partnerId + "/status")
                .then().statusCode(409); // partner still ONBOARDING: one-step rule

        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("status", "BACKFILLED"))
                .when().post("/api/v1/partners/" + partnerId + "/status")
                .then().statusCode(200);
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("status", "PARALLEL_RUN"))
                .when().post("/api/v1/partners/" + partnerId + "/status")
                .then().statusCode(200);
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("status", "SIGNED_OFF"))
                .when().post("/api/v1/partners/" + partnerId + "/status")
                .then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "pr.fin", roles = {"finance_approver"})
    void closeCycleTelemetryWithinSla() throws SQLException {
        // lock period 2 with a simulated 2-business-day close
        try (Connection c = ds.getConnection()) {
            sql(c, "UPDATE payout_period SET status = 'LOCKED', locked_at = "
                    + "timestamptz '2026-07-02 10:00:00Z' WHERE payout_period_id = CAST('"
                    + PERIOD_2 + "' AS uuid)");
        }
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> rows = given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin")
                .header("X-Roles", "finance_approver")
                .when().get("/api/v1/parallel-run/close-cycle")
                .then().statusCode(200)
                .extract().jsonPath()
                .getList(".");
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("2026-par2", row.get("periodKey"));
        assertEquals(true, row.get("withinSla"), "close in < 3 business days (ONB-6)");
        assertTrue(((Number) row.get("closeBusinessDays")).longValue() >= 1
                        && ((Number) row.get("closeBusinessDays")).longValue() <= 3,
                "business days between end and lock");
    }

    @Test
    @TestSecurity(user = "pr.fin", roles = {"finance_approver"})
    void mi18ReopenStaysLockedAfterFirstApproval() throws SQLException {
        // finance locks the period first (SoD path under test elsewhere)
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT eim_ops.lock_period_partition(CAST(? AS uuid))")) {
            ps.setString(1, PERIOD_1);
            ps.executeQuery();
        }
        assertEquals("LOCKED", scalar("SELECT status FROM payout_period "
                + "WHERE payout_period_id = '" + PERIOD_1 + "'"));
        assertEquals("f", scalar("SELECT has_table_privilege('eim_app', "
                + "'ledger_2026_par1', 'INSERT')"), "writes revoked while locked");

        // impact memo is mandatory
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("impactMemo", ""))
                .when().post("/api/v1/admin/periods/2026-par1/reopen")
                .then().statusCode(400);

        // first approval: finance — still pending dual approval
        var pending = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("impactMemo", "INC-42 missed accelerator batch (P1)"))
                .when().post("/api/v1/admin/periods/2026-par1/reopen")
                .then().statusCode(200)
                .extract().jsonPath();
        assertEquals("PENDING_DUAL_APPROVAL", pending.getString("reopenStatus"));
        assertEquals("system_admin", pending.getString("waitingFor"));
        assertEquals("LOCKED", scalar("SELECT status FROM payout_period "
                + "WHERE payout_period_id = '" + PERIOD_1 + "'"),
                "no reopen before the second signature");
        assertEquals("1", scalar("SELECT count(*) FROM period_reopen_request "
                + "WHERE payout_period_id = '" + PERIOD_1 + "' AND status = 'PENDING'"));

        // a second finance call neither duplicates the signature nor executes
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin2")
                .header("X-Roles", "finance_approver")
                .body(Map.of("impactMemo", "INC-42 missed accelerator batch (P1)"))
                .when().post("/api/v1/admin/periods/2026-par1/reopen")
                .then().statusCode(200)
                .body("reopenStatus", org.hamcrest.Matchers.equalTo("PENDING_DUAL_APPROVAL"));
    }

    @Test
    @TestSecurity(user = "pr.sysadmin", roles = {"system_admin"})
    void mi18ReopenExecutesOnlyWithTwoDistinctApprovers() throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT eim_ops.lock_period_partition(CAST(? AS uuid))")) {
            ps.setString(1, PERIOD_2);
            ps.executeQuery();
        }

        // system_admin opens the request and signs their side first
        var pending = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.sysadmin")
                .header("X-Roles", "system_admin")
                .body(Map.of("impactMemo", "INC-43 clawback reversal (P1)"))
                .when().post("/api/v1/admin/periods/2026-par2/reopen")
                .then().statusCode(200)
                .extract().jsonPath();
        assertEquals("PENDING_DUAL_APPROVAL", pending.getString("reopenStatus"));
        assertEquals("finance_approver", pending.getString("waitingFor"));

        // the same person cannot sign both sides (dual control)
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.sysadmin")
                .header("X-Roles", "finance_approver,system_admin")
                .body(Map.of("impactMemo", "INC-43 clawback reversal (P1)"))
                .when().post("/api/v1/admin/periods/2026-par2/reopen")
                .then().statusCode(409);

        // second, distinct approval: finance — executes the reopen
        var executed = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.fin")
                .header("X-Roles", "finance_approver,system_admin")
                .body(Map.of("impactMemo", "INC-43 clawback reversal (P1)"))
                .when().post("/api/v1/admin/periods/2026-par2/reopen")
                .then().statusCode(200)
                .extract().jsonPath();
        assertEquals("OPEN", executed.getString("status"));
        assertEquals("pr.fin", executed.getString("financeApprover"));
        assertEquals("pr.sysadmin", executed.getString("systemAdmin"));

        assertEquals("OPEN", scalar("SELECT status FROM payout_period "
                + "WHERE payout_period_id = '" + PERIOD_2 + "'"));
        assertEquals("t", scalar("SELECT has_table_privilege('eim_app', "
                + "'ledger_2026_par2', 'INSERT')"), "writes re-granted after reopen");
        assertEquals("EXECUTED", scalar("SELECT status FROM period_reopen_request "
                + "WHERE payout_period_id = '" + PERIOD_2 + "'"));

        // audit trail: 2 approvals + the reopen itself, plus the outbox event
        assertTrue(Integer.parseInt(scalar("SELECT count(*) FROM audit_log "
                + "WHERE action_type = 'APPROVAL' AND reason_code = 'MI18_REOPEN_APPROVAL' "
                + "AND target_entity = 'period_reopen_request'")) >= 2,
                "both approvals audited");
        assertTrue(Integer.parseInt(scalar("SELECT count(*) FROM audit_log "
                + "WHERE action_type = 'PERIOD_REOPEN'")) >= 1,
                "PERIOD_REOPEN audited");
        assertTrue(Integer.parseInt(scalar("SELECT count(*) FROM transactional_outbox "
                + "WHERE aggregate_type = 'eim.period.reopened'")) >= 1,
                "eim.period.reopened emitted");

        // reopening an open period makes no sense
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "pr.sysadmin")
                .header("X-Roles", "system_admin")
                .body(Map.of("impactMemo", "again"))
                .when().post("/api/v1/admin/periods/2026-par2/reopen")
                .then().statusCode(409);
    }
}
