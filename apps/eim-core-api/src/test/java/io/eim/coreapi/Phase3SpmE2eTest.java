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
 * TG8 E2E (live AGE postgres): the Phase-3 surfaces on one fixture —
 * territory & quota design with realignment preview, account-routing
 * rules resolving credit through territories (FR-1003), partner identity
 * (mTLS CN mapping, separation from internal roles), partner statement +
 * self-service dispute reuse, the channel-claim lifecycle through accrual
 * on the core ledger (FR-1005), NL plan modeling with SoD-guarded human
 * approval (FR-408), AI plan diagnostics (FR-1006), the trace-grounded
 * earnings explainer with dispute handoff (FR-708), and the legacy-SPM
 * importer (Varicent-format history landing as MIGRATION-sourced rows).
 */
@QuarkusTest
class Phase3SpmE2eTest {

    private static final String TENANT = "aaaaaaaa-3000-0000-0000-000000000001";
    private static final String REP = "aaaaaaaa-3000-0000-0000-000000000002";
    private static final String PARTNER = "aaaaaaaa-3000-0000-0000-000000000003";
    private static final String PERIOD = "aaaaaaaa-3000-0000-0000-000000000004";
    private static final String LEDGER = "aaaaaaaa-3000-0000-0000-000000000005";

    @Inject
    AgroalDataSource ds;

    @BeforeEach
    void seed() throws SQLException {
        clean();
        try (Connection c = ds.getConnection()) {
            sql(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, effective_start_date) VALUES (CAST('" + REP
                    + "' AS uuid),CAST('" + TENANT + "' AS uuid),'P3-1','P3 Rep',"
                    + "'p3.rep@example.test','AE',DATE '2026-01-01')");
            sql(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, effective_start_date) VALUES (CAST('" + PARTNER
                    + "' AS uuid),CAST('" + TENANT + "' AS uuid),'P3-P','Acme Channel',"
                    + "'channel@example.test','AE',DATE '2026-01-01')");
            sql(c, "INSERT INTO payout_period (payout_period_id, tenant_id, period_key, "
                    + "starts_on, ends_on, status) VALUES (CAST('" + PERIOD + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),'2027-p3','2027-01-01','2027-01-31','OPEN')");
            sql(c, "SELECT eim_ops.ensure_period_partition(CAST('" + PERIOD + "' AS uuid),'2027-p3')");
            // trace-carrying entry for the explainer
            sql(c, "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                    + "payee_id, component_name, payout_amount, entry_type, calculation_trace, "
                    + "is_locked) VALUES (CAST('" + LEDGER + "' AS uuid),CAST('" + TENANT
                    + "' AS uuid),CAST('" + PERIOD + "' AS uuid),CAST('" + REP
                    + "' AS uuid),'commission',7500,'CALCULATED',CAST('{\"steps\":[{\"component\":"
                    + "\"commission\",\"kind\":\"TIER_TABLE\",\"slices\":[{\"tierName\":\"Tier 1\","
                    + "\"sliceAmount\":\"60000\",\"rateApplied\":\"0.10\",\"slicePayout\":\"6000\"},"
                    + "{\"tierName\":\"Tier 2\",\"sliceAmount\":\"10000\",\"rateApplied\":\"0.15\","
                    + "\"slicePayout\":\"1500\"}]}]}' AS jsonb),false)");
        }
    }

    @AfterEach
    void cleanup() throws SQLException {
        clean();
    }

    private void clean() throws SQLException {
        try (Connection c = ds.getConnection()) {
            sql(c, "DELETE FROM channel_claim WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM channel_program WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM partner_account WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM territory_assignment WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM quota_allocation WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM routing_rule WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM territory WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM nl_model_request WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM plan_diagnostic WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM raw_transaction WHERE tenant_id = CAST('" + TENANT + "' AS uuid) "
                    + "AND source_system LIKE 'MIGRATION_%'");
            sql(c, "DELETE FROM dispute_ticket WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM commission_ledger_entry WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "SELECT eim_ops.detach_delete_payee(CAST('" + REP + "' AS uuid))");
            sql(c, "SELECT eim_ops.detach_delete_payee(CAST('" + PARTNER + "' AS uuid))");
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
    @TestSecurity(user = "p3.admin", roles = {"comp_admin"})
    void territoriesQuotasAndRoutingFeedCreditAssignment() {
        String territory = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("name", "West Enterprise"))
                .when().post("/api/v1/spm/territories")
                .then().statusCode(200).extract().jsonPath().getString("territoryId");
        assertNotNull(territory);

        // assign the rep to the territory + an account to the territory
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("payeeId", REP, "effectiveStart", "2027-01-01"))
                .when().post("/api/v1/spm/territories/" + territory + "/assignments")
                .then().statusCode(200);
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("accountKey", "ACME-1001"))
                .when().post("/api/v1/spm/territories/" + territory + "/assignments")
                .then().statusCode(200);

        // quota for the period
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("periodKey", "2027-p3", "quotaAmount", "1200000"))
                .when().post("/api/v1/spm/territories/" + territory + "/quotas")
                .then().statusCode(200)
                .body("quotaAmount", org.hamcrest.Matchers.equalTo("1200000"));

        // routing rule: ACME accounts → the territory (FR-1003)
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("priority", 10, "matchAccountKey", "ACME-1001",
                        "routeToTerritoryId", territory))
                .when().post("/api/v1/spm/routing")
                .then().statusCode(200);

        // resolve: the account credits the territory's assigned rep
        var resolved = given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.admin")
                .header("X-Roles", "comp_admin")
                .when().get("/api/v1/spm/routing/resolve?accountKey=ACME-1001")
                .then().statusCode(200).extract().jsonPath();
        assertEquals(1, resolved.getList("payees").size(), "routes to the assigned rep");
        assertEquals(REP, resolved.getString("payees[0].payeeId"));
        assertEquals("territory", resolved.getString("payees[0].via"));

        // unmatched account with no catch-all rule → no routing
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.admin")
                .header("X-Roles", "comp_admin")
                .when().get("/api/v1/spm/routing/resolve?accountKey=UNKNOWN-1")
                .then().statusCode(200)
                .body("payees.size()", org.hamcrest.Matchers.equalTo(0));

        // what-if realignment preview is a pure read
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.admin")
                .header("X-Roles", "comp_admin")
                .body("{}")
                .when().post("/api/v1/spm/territories/realignment-preview")
                .then().statusCode(200)
                .body("preview", org.hamcrest.Matchers.equalTo(true))
                .body("accountRoutes.size()", org.hamcrest.Matchers.equalTo(1));

    }

    @Test
    @TestSecurity(user = "rep", roles = {"payee"})
    void payeeCannotDesignTerritories() {
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "rep")
                .header("X-Roles", "payee")
                .body(Map.of("name", "rogue"))
                .when().post("/api/v1/spm/territories")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "p3.admin", roles = {"comp_admin"})
    void partnerIdentityStatementDisputesAndClaimAccrual() {
        // register partner bound to a cert CN
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("orgName", "Acme Channel Co", "certCn", "partner.acme.example.test"))
                .when().post("/api/v1/channel/partners")
                .then().statusCode(200);

        // whoami: the CN resolves to the partner identity, never internal roles
        var who = given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "partner")
                .header("X-Roles", "partner")
                .header("X-Client-Cert-CN", "partner.acme.example.test")
                .when().get("/api/v1/channel/partners/whoami")
                .then().statusCode(200).extract().jsonPath();
        assertEquals("partner", who.getString("role"), "partner plane is role-separated");
        assertEquals("Acme Channel Co", who.getString("orgName"));
        // wrong/missing CN → 401
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "partner")
                .header("X-Roles", "partner")
                .when().get("/api/v1/channel/partners/whoami")
                .then().statusCode(401);

        // partner statement (reuse of the ledger machinery)
        given()
                .header("X-Tenant-Id", TENANT).header("X-Payee-Id", PARTNER)
                .header("X-Actor", "partner").header("X-Roles", "partner")
                .when().get("/api/v1/channel/partners/" + PARTNER + "/statement")
                .then().statusCode(200)
                .body("partnerPayeeId", org.hamcrest.Matchers.equalTo(PARTNER));

        // partner self-service dispute (reuses dispute_ticket)
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Payee-Id", PARTNER)
                .header("X-Actor", "partner").header("X-Roles", "partner")
                .body(Map.of("category", "SOURCE_DATA", "notes", "missing rebate"))
                .when().post("/api/v1/channel/partners/" + PARTNER + "/disputes")
                .then().statusCode(202);
        assertEquals("1", scalar("SELECT count(*) FROM dispute_ticket "
                + "WHERE payee_id = '" + PARTNER + "' AND status = 'SUBMITTED'"));

        // channel program + claim lifecycle
        sql0("INSERT INTO channel_program (program_code, tenant_id, name, kind, rate, min_claim, "
                + "requires_proof) VALUES ('ACME_REBATE', CAST('" + TENANT
                + "' AS uuid), 'Acme rebate', 'REBATE', 0.05, 1000, true)");
        var claim = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("partnerPayeeId", PARTNER, "programCode", "ACME_REBATE",
                        "claimPeriodKey", "2027-p3", "claimedAmount", "80000"))
                .when().post("/api/v1/channel/claims")
                .then().statusCode(202)
                .body("eligible", org.hamcrest.Matchers.equalTo(true))
                .extract().jsonPath().getString("claimId");

        // below-minimum claim is rejected at eligibility
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("partnerPayeeId", PARTNER, "programCode", "ACME_REBATE",
                        "claimPeriodKey", "2027-02", "claimedAmount", "500"))
                .when().post("/api/v1/channel/claims")
                .then().statusCode(202)
                .body("status", org.hamcrest.Matchers.equalTo("REJECTED"));

        // approve → accrual lands on the core ledger (80000 × 5% = 4000)
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("comment", "approved"))
                .when().post("/api/v1/channel/claims/" + claim + "/approve")
                .then().statusCode(200)
                .body("accrualAmount", org.hamcrest.Matchers.equalTo("4000.0000"));
        assertEquals("1", scalar("SELECT count(*) FROM commission_ledger_entry "
                + "WHERE payee_id = '" + PARTNER + "' AND component_name = 'channel_accrual_ACME_REBATE' "
                + "AND payout_amount = 4000"), "accrual posted on the core engine");
        // partner sees the accrual on their statement
        given()
                .header("X-Tenant-Id", TENANT).header("X-Payee-Id", PARTNER)
                .header("X-Actor", "partner").header("X-Roles", "partner")
                .when().get("/api/v1/channel/partners/" + PARTNER + "/statement")
                .then().statusCode(200)
                .body("totalPayout", org.hamcrest.Matchers.equalTo("4000.0000"));
    }

    @Test
    @TestSecurity(user = "p3.fin", roles = {"finance_approver"})
    void nlModelingCompilesAndRequiresDistinctHumanApproval() {
        var compiled = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("prompt",
                        "create accelerator plan: 5% up to 100000 then 8% above, cap 60000"))
                .when().post("/api/v1/ai/plan-modeling/requests")
                .then().statusCode(200).extract().jsonPath();
        String requestId = compiled.getString("requestId");
        assertEquals("COMPILED", compiled.getString("compileStatus"));
        assertEquals("PENDING_HUMAN", compiled.getString("approvalStatus"));
        assertTrue(compiled.getString("draftPlanJson").contains("\"rate\":\"0.05\""),
                "percent compiled to a fraction");
        assertTrue(compiled.getString("draftPlanJson").contains("\"capValue\":\"60000\""));

        // grammar rejects an unparseable prompt
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("prompt", "make everyone happy"))
                .when().post("/api/v1/ai/plan-modeling/requests")
                .then().statusCode(400);

        // SoD: requester cannot approve their own AI draft (FR-408)
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("decision", "APPROVED"))
                .when().post("/api/v1/ai/plan-modeling/requests/" + requestId + "/decision")
                .then().statusCode(403);

        // a different approver approves
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.chief")
                .header("X-Roles", "finance_approver")
                .body(Map.of("decision", "APPROVED"))
                .when().post("/api/v1/ai/plan-modeling/requests/" + requestId + "/decision")
                .then().statusCode(200)
                .body("note", org.hamcrest.Matchers.containsString("FR-406"));
        assertEquals("APPROVED", scalar("SELECT approval_status FROM nl_model_request "
                + "WHERE request_id = '" + requestId + "'"));
        assertTrue(Integer.parseInt(scalar("SELECT count(*) FROM audit_log "
                + "WHERE reason_code = 'NL_PLAN_COMPILED'")) >= 1, "compile audited");
        assertTrue(Integer.parseInt(scalar("SELECT count(*) FROM audit_log "
                + "WHERE reason_code = 'NL_PLAN_APPROVED'")) >= 1, "approval audited");
    }

    @Test
    @TestSecurity(user = "p3.fin", roles = {"finance_approver"})
    void planDiagnosticsDetectWindfallCliffGaming() {
        String hash = "diag0000000000000000000000000000000000000000000000000000000tg8";
        sql0("ALTER TABLE plan_artifact DISABLE TRIGGER trg_plan_artifact_append_only");
        sql0("DELETE FROM plan_artifact WHERE plan_version_hash = '" + hash + "'");
        sql0("INSERT INTO plan_artifact (plan_version_hash, tenant_id, plan_name, plan_json, "
                + "model_json, drl, helper_java, dmn, published_at) VALUES ('" + hash + "', CAST('" + TENANT
                + "' AS uuid), 'diag', '{}', CAST('{\"planName\":\"diag\",\"components\":["
                + "{\"name\":\"commission\",\"kind\":\"TIER_TABLE\",\"tiers\":{\"brackets\":["
                + "{\"upTo\":\"100000\",\"rate\":\"0.05\"},{\"rate\":\"0.40\"}]}},"
                + "{\"name\":\"team\",\"kind\":\"ROLLUP\",\"dependsOn\":[\"commission\"]}]}'"
                + " AS jsonb), 'stub','stub','stub', now())");
        sql0("ALTER TABLE plan_artifact ENABLE TRIGGER trg_plan_artifact_append_only");

        var result = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.fin")
                .header("X-Roles", "finance_approver")
                .body("{}")
                .when().post("/api/v1/ai/plan-diagnostics/" + hash)
                .then().statusCode(200).extract().jsonPath();
        var kinds = result.getList("findings.kind");
        assertTrue(kinds.contains("WINDFALL"), "top rate 0.40 uncapped → windfall: " + kinds);
        assertTrue(kinds.contains("CLIFF"), "rate jumps 8x → cliff: " + kinds);
        assertTrue(kinds.contains("GAMING"), "rollup over uncapped → gaming: " + kinds);
        assertEquals(3, result.getList("findings").size(), "exactly the three anomalies");
        assertEquals("3", scalar("SELECT count(*) FROM plan_diagnostic "
                + "WHERE plan_version_hash = '" + hash + "'"), "diagnostics persisted");
    }

    @Test
    @TestSecurity(user = "p3.rep", roles = {"payee"})
    void earningsExplainerIsGroundedInStoredTrace() {
        var out = given()
                .header("X-Tenant-Id", TENANT).header("X-Payee-Id", REP)
                .header("X-Actor", "p3.rep").header("X-Roles", "payee")
                .when().get("/api/v1/ai/earnings-explainer/" + LEDGER)
                .then().statusCode(200).extract().jsonPath();
        String narrative = out.getString("narrative");
        assertTrue(narrative.contains("7500"), "amount from the ledger");
        assertTrue(narrative.contains("2 marginal tier slices"), "slices from the trace");
        assertTrue(narrative.contains("60000"), "slice amount verbatim from the trace");
        assertEquals(true, out.getBoolean("groundedInTrace"));
        assertEquals("PLAN_LOGIC", out.getString("disputeHandoff.payload.category"),
                "dispute handoff (FR-708)");
        assertEquals(LEDGER, out.getString("disputeHandoff.payload.ledgerId"));
    }

    @Test
    @TestSecurity(user = "p3.admin", roles = {"system_admin"})
    void legacySpmImporterLandsVaricentHistory() {
        String csv = "record_type,payeeId,employeeNumber,fullName,email,role\n"
                + "PAYEE,aaaaaaaa-3000-0000-0000-000000000006,VAR-1,Imported Rep,var@example.test,AE\n"
                + "record_type,periodKey,employeeNumber,amount\n"
                + "HISTORY,2027-01,VAR-1,42000\n"
                + "HISTORY,2027-01,VAR-1,18000\n"
                + "record_type,planName,tierCsv\n"
                + "PLAN,Varicent Base,0-100000:5%,100000+:8%\n"
                + "record_type,territory,periodKey,amount\n"
                + "QUOTA,West,2027-01,500000\n"
                + "BOGUS,row\n";
        var out = given().contentType("text/csv")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.migrator")
                .header("X-Roles", "system_admin")
                .body(csv)
                .when().post("/api/v1/migration/spm/varicent")
                .then().statusCode(200).extract().jsonPath();
        assertEquals(1, (int) out.getInt("payees"), "one payee imported");
        assertEquals(2, (int) out.getInt("historyRows"), "two history rows");
        assertEquals(1, (int) out.getInt("plans"));
        assertEquals(1, (int) out.getInt("quotas"));
        assertEquals(1, out.getList("errors").size(), "BOGUS row rejected with an error");
        assertEquals("2", scalar("SELECT count(*) FROM raw_transaction "
                + "WHERE tenant_id = '" + TENANT + "' AND source_system = 'MIGRATION_VARICENT'"),
                "history lands as migration-sourced rows");
        // idempotent: re-import does not duplicate
        given().contentType("text/csv")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p3.migrator")
                .header("X-Roles", "system_admin")
                .body(csv)
                .when().post("/api/v1/migration/spm/varicent")
                .then().statusCode(200);
        assertEquals("2", scalar("SELECT count(*) FROM raw_transaction "
                + "WHERE tenant_id = '" + TENANT + "' AND source_system = 'MIGRATION_VARICENT'"),
                "deterministic ids → idempotent");
    }

    private void sql0(String sql) {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
