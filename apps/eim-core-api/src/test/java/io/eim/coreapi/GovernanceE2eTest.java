package io.eim.coreapi;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG4-GOV-1/4 + EXP-1/2 + SEL-1..5 (live AGE postgres): the governance
 * cycle on one fixture — manual override with preserved original and
 * dispute linkage; audit viewer search + CSV + audited reads; statement
 * overview/trace/download matching; dispute submit → pre-tag → resolve;
 * payroll exports (3 templates, negative flag, file hash, byte-identical
 * regen) with EXPORT_GENERATION audit.
 */
@QuarkusTest
class GovernanceE2eTest {

    private static final String TENANT = "aaaaaaaa-b000-0000-0000-000000000001";
    private static final String PAYEE = "aaaaaaaa-b000-0000-0000-000000000002";
    private static final String PERIOD = "aaaaaaaa-b000-0000-0000-000000000003";
    private static final String MGR = "aaaaaaaa-b000-0000-0000-000000000004";
    private static final String NEG_PAYEE = "aaaaaaaa-b000-0000-0000-000000000005";

    @Inject
    AgroalDataSource ds;

    @BeforeEach
    void seed() throws SQLException {
        clean();
        try (Connection c = ds.getConnection()) {
            sql(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, effective_start_date) VALUES (CAST('" + MGR
                    + "' AS uuid),CAST('" + TENANT + "' AS uuid),'G-0','Gov Manager',"
                    + "'gov.mgr@example.test','Mgr',DATE '2026-01-01')");
            sql(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, manager_payee_id, effective_start_date) "
                    + "VALUES (CAST('" + PAYEE + "' AS uuid),CAST('" + TENANT + "' AS uuid),"
                    + "'G-1','Gov Rep','gov.rep@example.test','AE',CAST('" + MGR
                    + "' AS uuid),DATE '2026-01-01')");
            sql(c, "INSERT INTO payout_period (payout_period_id, tenant_id, period_key, "
                    + "starts_on, ends_on, status) VALUES (CAST('" + PERIOD + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),'2026-gov','2026-04-01','2026-04-30','OPEN')");
            sql(c, "SELECT eim_ops.ensure_period_partition(CAST('" + PERIOD + "' AS uuid), '2026-gov')");
            // committed calculated entry + a negative clawback
            sql(c, "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                    + "payee_id, component_name, payout_amount, entry_type, calculation_trace, "
                    + "is_locked, transaction_id) VALUES (gen_random_uuid(),CAST('" + TENANT
                    + "' AS uuid),CAST('" + PERIOD + "' AS uuid),CAST('" + PAYEE
                    + "' AS uuid),'commission',19500,'CALCULATED',"
                    + "CAST('{\"components\":{\"window_from\":\"0\",\"window_to\":\"160000\"},"
                    + "\"steps\":[{\"component\":\"commission\",\"kind\":\"TIER_TABLE\",\"slices\":"
                    + "[{\"tierName\":\"Tier 1\",\"sliceAmount\":\"100000\",\"rateApplied\":\"0.10\","
                    + "\"slicePayout\":\"10000\"},{\"tierName\":\"Tier 2\",\"sliceAmount\":\"50000\","
                    + "\"rateApplied\":\"0.15\",\"slicePayout\":\"7500\"},{\"tierName\":\"Tier 3\","
                    + "\"sliceAmount\":\"10000\",\"rateApplied\":\"0.20\",\"slicePayout\":\"2000\"}]}]}'"
                    + " AS jsonb),false,NULL)");
            sql(c, "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                    + "payee_id, component_name, payout_amount, entry_type, calculation_trace, "
                    + "is_locked, transaction_id) VALUES (gen_random_uuid(),CAST('" + TENANT
                    + "' AS uuid),CAST('" + PERIOD + "' AS uuid),CAST('" + PAYEE
                    + "' AS uuid),'clawback',-800,'CLAWBACK',CAST('{}' AS jsonb),false,NULL)");
            // a second payee whose NET is negative (clawback exceeds payout)
            sql(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, effective_start_date) VALUES (CAST('" + NEG_PAYEE
                    + "' AS uuid),CAST('" + TENANT + "' AS uuid),'G-2','Neg Rep',"
                    + "'neg.rep@example.test','AE',DATE '2026-01-01')");
            sql(c, "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                    + "payee_id, component_name, payout_amount, entry_type, calculation_trace, "
                    + "is_locked, transaction_id) VALUES (gen_random_uuid(),CAST('" + TENANT
                    + "' AS uuid),CAST('" + PERIOD + "' AS uuid),CAST('" + NEG_PAYEE
                    + "' AS uuid),'clawback',-500,'CLAWBACK',CAST('{}' AS jsonb),false,NULL)");
        }
    }

    @AfterEach
    void cleanup() throws SQLException {
        clean();
    }

    private void clean() throws SQLException {
        try (Connection c = ds.getConnection()) {
            sql(c, "DELETE FROM export_run WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM trueup_review WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM commission_ledger_entry WHERE entry_type = 'MANUAL_OVERRIDE' AND tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM manual_override WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM dispute_ticket WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM commission_ledger_entry WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "SELECT eim_ops.detach_delete_payee(CAST('" + MGR + "' AS uuid))");
            sql(c, "SELECT eim_ops.detach_delete_payee(CAST('" + PAYEE + "' AS uuid))");
            sql(c, "SELECT eim_ops.detach_delete_payee(CAST('" + NEG_PAYEE + "' AS uuid))");
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
    @TestSecurity(user = "gov.admin", roles = {"comp_admin"})
    void manualOverridePreservesOriginalAndRequiresReason() {
        // create a dispute first to link
        var disputeResp = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Payee-Id", PAYEE)
                .header("X-Actor", "gov.rep").header("X-Roles", "payee")
                .body(Map.of("category", "PLAN_LOGIC", "notes", "payout short",
                        "symptom", "payout_wrong"))
                .when().post("/api/v1/disputes")
                .then().extract().response();
        org.junit.jupiter.api.Assertions.assertEquals(202, disputeResp.getStatusCode(),
                "dispute submit: " + disputeResp.getBody().asString());
        String disputeId = disputeResp.jsonPath().getString("disputeId");
        assertEquals("PLAN_LOGIC",
                scalar("SELECT category FROM dispute_ticket WHERE dispute_id = '"
                        + disputeId + "'"),
                "backward-chaining pre-tag (payout_wrong → PLAN_LOGIC)");

        // missing reason comment is rejected
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "gov.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("payoutPeriodId", PERIOD, "payeeId", PAYEE,
                        "componentName", "commission", "adjustedAmount", "21000",
                        "reasonCode", "GOODWILL"))
                .when().post("/api/v1/admin/overrides")
                .then().statusCode(400);

        // the real override
        var overrideResp = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "gov.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("payoutPeriodId", PERIOD, "payeeId", PAYEE,
                        "componentName", "commission", "adjustedAmount", "21000",
                        "reasonCode", "GOODWILL", "reasonComment", "missed accelerator",
                        "disputeId", disputeId))
                .when().post("/api/v1/admin/overrides")
                .then().extract().response();
        org.junit.jupiter.api.Assertions.assertEquals(202, overrideResp.getStatusCode(),
                "override: " + overrideResp.getBody().asString());
        var resp = overrideResp.jsonPath();
        assertEquals("19500.0000", resp.getString("originalAmount"),
                "original preserved");
        assertEquals("21000", resp.getString("adjustedAmount"));

        // separate entry type; original CALCULATED row untouched
        assertEquals("1", scalar("SELECT count(*) FROM commission_ledger_entry "
                + "WHERE entry_type = 'MANUAL_OVERRIDE' AND payout_period_id = '"
                + PERIOD + "'"));
        assertEquals("19500.0000", scalar("SELECT payout_amount::text "
                + "FROM commission_ledger_entry WHERE entry_type = 'CALCULATED' "
                + "AND payout_period_id = '" + PERIOD + "'"));
        assertEquals("1", scalar("SELECT count(*) FROM manual_override "
                + "WHERE dispute_id = '" + disputeId + "'"),
                "dispute linkage");
        assertTrue(Integer.parseInt(scalar("SELECT count(*) FROM audit_log "
                + "WHERE action_type = 'MANUAL_ADJUSTMENT' AND actor = 'gov.admin'")) >= 1);
    }

    @Test
    @TestSecurity(user = "gov.finance", roles = {"finance_approver"})
    void payrollExportsAreTemplatedHashedAndByteIdentical() {
        for (String template : new String[]{"ADP", "WORKDAY", "NETSUITE_JOURNAL"}) {
            var first = given().contentType("application/json")
                    .header("X-Tenant-Id", TENANT).header("X-Actor", "gov.finance")
                    .header("X-Roles", "finance_approver")
                    .body(Map.of("periodId", PERIOD, "template", template))
                    .when().post("/api/v1/admin/exports")
                    .then().statusCode(200)
                    .extract().jsonPath();
            assertEquals(2, (int) first.getInt("rows"), "one net line per payee (incl. net-negative payee)");
            String csv = first.getString("csv");
            assertTrue(csv.contains("G-1"), "employee_number keyed: " + csv);
            assertTrue(csv.contains("Y"), "negative-net flag on the clawback payee");

            // byte-identical regeneration
            var second = given().contentType("application/json")
                    .header("X-Tenant-Id", TENANT).header("X-Actor", "gov.finance")
                    .header("X-Roles", "finance_approver")
                    .body(Map.of("periodId", PERIOD, "template", template))
                    .when().post("/api/v1/admin/exports")
                    .then().statusCode(200)
                    .extract().jsonPath();
            assertEquals(first.getString("fileHash"), second.getString("fileHash"),
                    "same ledger content → same hash");
            assertEquals(csv, second.getString("csv"), "byte-identical");
        }
        assertEquals("6", scalar("SELECT count(*) FROM export_run WHERE tenant_id = '"
                + TENANT + "'"), "3 templates x 2 generations");
        assertTrue(Integer.parseInt(scalar("SELECT count(*) FROM audit_log "
                + "WHERE action_type = 'EXPORT_GENERATION' AND actor = 'gov.finance'")) >= 6,
                "EXPORT_GENERATION audit per run");
    }

    @Test
    @TestSecurity(user = "gov.auditor", roles = {"system_admin"})
    void auditViewerSearchesStreamsAndAuditsItsReads() {
        var json = given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "gov.auditor")
                .header("X-Roles", "system_admin")
                .queryParam("stream", "actions")
                .queryParam("actor", "gov.admin")
                .when().get("/api/v1/admin/audit-viewer")
                .then().statusCode(200)
                .extract().jsonPath();
        assertTrue(json.getInt("count") >= 0);

        // the audit read itself is audited
        assertTrue(Integer.parseInt(scalar("SELECT count(*) FROM audit_log "
                + "WHERE target_entity = 'audit_viewer' AND actor = 'gov.auditor'")) >= 1,
                "audit-read audited (FR-610.3)");

        // CSV export
        String csv = given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "gov.auditor")
                .header("X-Roles", "system_admin")
                .queryParam("stream", "actions")
                .queryParam("format", "csv")
                .when().get("/api/v1/admin/audit-viewer")
                .then().statusCode(200)
                .extract().asString();
        assertTrue(csv.startsWith("\"occurred_at\"") || csv.contains("occurred_at"),
                "csv header row");
    }

    @Test
    @TestSecurity(user = "gov.rep", roles = {"payee"})
    void statementOverviewTraceAndDownloadMatch() {
        var overview = given()
                .header("X-Tenant-Id", TENANT).header("X-Payee-Id", PAYEE)
                .header("X-Actor", "gov.rep").header("X-Roles", "payee")
                .when().get("/api/v1/me/statement")
                .then().statusCode(200)
                .extract().jsonPath();
        // 19500 − 800 = 18700 net
        assertEquals(new BigDecimal("18700").stripTrailingZeros(),
                new BigDecimal(overview.getString("totalPayout")).stripTrailingZeros());

        String ledgerId = scalar("SELECT ledger_id::text FROM commission_ledger_entry "
                + "WHERE entry_type = 'CALCULATED' AND payee_id = '" + PAYEE + "'");
        var trace = given()
                .header("X-Tenant-Id", TENANT).header("X-Payee-Id", PAYEE)
                .header("X-Actor", "gov.rep").header("X-Roles", "payee")
                .when().get("/api/v1/me/statement/trace/" + ledgerId)
                .then().statusCode(200)
                .extract().jsonPath();
        assertTrue(trace.getString("trace").contains("Tier 3"),
                "trace drawer payload carries the slices");

        // CSV download matches on-screen values
        String csv = given()
                .header("X-Tenant-Id", TENANT).header("X-Payee-Id", PAYEE)
                .header("X-Actor", "gov.rep").header("X-Roles", "payee")
                .when().get("/api/v1/me/statement/download")
                .then().statusCode(200)
                .extract().asString();
        assertTrue(csv.contains("18700"), "download total matches screen");
        assertTrue(csv.contains("-800.00") || csv.contains("-800.0"), "component rows");

        // foreign payee sees nothing (RLS)
        given()
                .header("X-Tenant-Id", TENANT)
                .header("X-Payee-Id", "00000000-0000-0000-0000-00000000dead")
                .header("X-Actor", "nobody").header("X-Roles", "payee")
                .when().get("/api/v1/me/statement")
                .then().statusCode(200)
                .body("totalPayout", org.hamcrest.Matchers.equalTo("0"));
    }

    @Test
    @TestSecurity(user = "gov.admin", roles = {"comp_admin"})
    void disputeLifecycleResolvesWithCommentAndAudit() throws SQLException {
        String disputeId = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Payee-Id", PAYEE)
                .header("X-Actor", "gov.rep").header("X-Roles", "payee")
                .body(Map.of("category", "SOURCE_DATA", "notes", "deal missing",
                        "symptom", "late_arrival"))
                .when().post("/api/v1/disputes")
                .then().statusCode(202)
                .extract().jsonPath().getString("disputeId");
        assertEquals("SOURCE_DATA",
                scalar("SELECT category FROM dispute_ticket WHERE dispute_id = '"
                        + disputeId + "'"),
                "late_arrival pre-tags SOURCE_DATA");

        // queue sees it
        assertTrue(Integer.parseInt(scalar("SELECT count(*) FROM dispute_ticket "
                + "WHERE status = 'SUBMITTED'")) >= 1);

        // resolution requires a comment
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "gov.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("resolution", "APPROVE"))
                .when().post("/api/v1/disputes/" + disputeId + "/resolve")
                .then().statusCode(400);

        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "gov.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("resolution", "APPROVE", "comment", "source fixed"))
                .when().post("/api/v1/disputes/" + disputeId + "/resolve")
                .then().statusCode(202);
        assertEquals("APPROVED", scalar("SELECT status FROM dispute_ticket "
                + "WHERE dispute_id = '" + disputeId + "'"));
        assertTrue(Integer.parseInt(scalar("SELECT count(*) FROM audit_log "
                + "WHERE action_type = 'DISPUTE_RESOLUTION' AND target_record_id = '"
                + disputeId + "'")) >= 2, "submit + resolve audited");

        // double-resolve rejected
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "gov.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("resolution", "REJECT", "comment", "again"))
                .when().post("/api/v1/disputes/" + disputeId + "/resolve")
                .then().statusCode(409);
    }
}
