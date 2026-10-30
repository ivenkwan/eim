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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG7-ACC E2E (live AGE postgres): the accounting cycle on one fixture —
 * two books over the same locked ledger (US GAAP capitalizing, statutory
 * expensing via a bespoke Controller decision table), classification
 * creating assets with FX lock + schedules, the completeness gate, the
 * deferred cost rollforward identity with ST/LT split, reclassification,
 * the four JE templates with the balance gate, ERP push/retry states,
 * zero-orphan lineage drill-through, and the FR-903 lifecycle paths —
 * churn impairment, downgrade policy, retro catch-up child stream, and
 * posted-schedule immutability enforced by the database trigger.
 */
@QuarkusTest
class AccountingE2eTest {

    private static final String TENANT = "aaaaaaaa-d000-0000-0000-000000000001";
    private static final String MGR = "aaaaaaaa-d000-0000-0000-000000000002";
    private static final String REP = "aaaaaaaa-d000-0000-0000-000000000003";
    private static final String PERIOD = "aaaaaaaa-d000-0000-0000-000000000004";
    private static final String TXN = "aaaaaaaa-d000-0000-0000-000000000005";

    @Inject
    AgroalDataSource ds;

    private String usBook;
    private String localBook;

    @BeforeEach
    void seed() throws SQLException {
        clean();
        try (Connection c = ds.getConnection()) {
            sql(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, effective_start_date) VALUES (CAST('" + MGR
                    + "' AS uuid),CAST('" + TENANT + "' AS uuid),'A-M','Acc Manager',"
                    + "'acc.mgr@example.test','Mgr',DATE '2026-01-01')");
            sql(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, manager_payee_id, effective_start_date) VALUES (CAST('" + REP
                    + "' AS uuid),CAST('" + TENANT + "' AS uuid),'A-1','Acc Rep',"
                    + "'acc.rep@example.test','AE',CAST('" + MGR + "' AS uuid),DATE '2026-01-01')");
            sql(c, "INSERT INTO payout_period (payout_period_id, tenant_id, period_key, "
                    + "starts_on, ends_on, status) VALUES (CAST('" + PERIOD + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),'2026-acc','2026-03-01','2026-03-31','OPEN')");
            sql(c, "SELECT eim_ops.ensure_period_partition(CAST('" + PERIOD + "' AS uuid), '2026-acc')");
            // EUR booking with an FX rate → the asset must lock it (FR-905)
            sql(c, "INSERT INTO raw_transaction (transaction_id, tenant_id, source_system, "
                    + "external_deal_id, transaction_date, booking_amount, currency_code, fx_rate_applied, "
                    + "ingestion_batch_id) VALUES (CAST('" + TXN + "' AS uuid),CAST('" + TENANT
                    + "' AS uuid),'CSV','ACC-1',DATE '2026-03-10',100000,'EUR',1.085000,"
                    + "CAST('aaaaaaaa-d000-0000-0000-000000000006' AS uuid))");
            // rep commission (capitalize), manager rollup (capitalize manager), clawback (expense)
            ledger(c, REP, "commission", "20000.0000", "CALCULATED", TXN);
            ledger(c, MGR, "team_commission", "4000.0000", "CALCULATED", null);
            ledger(c, REP, "clawback", "-800.0000", "CLAWBACK", null);
            // lock the seeded period (status only — the write-revoke path is
            // exercised by the TG-1/TG-6 suites; classification reads + the
            // accounting tables are unaffected by partition privileges)
            sql(c, "UPDATE payout_period SET status = 'LOCKED' WHERE payout_period_id = CAST('"
                    + PERIOD + "' AS uuid)");
        }
        // book 1: US GAAP defaults (capitalize manager on, expedient 12)
        usBook = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("bookCode", "US_GAAP", "functionalCurrency", "USD",
                        "amortizationConvention", "FULL_MONTH"))
                .when().post("/api/v1/accounting/books")
                .then().statusCode(200).extract().jsonPath().getString("bookId");
        // book 2: statutory — Controller uploads a bespoke expense-all table
        localBook = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("bookCode", "LOCAL_GAAP", "functionalCurrency", "EUR"))
                .when().post("/api/v1/accounting/books")
                .then().statusCode(200).extract().jsonPath().getString("bookId");
        String bespoke = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/DMN/20180521/MODEL/\" "
                + "id=\"eim-asc606-classification\" name=\"eim-asc606-classification\" "
                + "namespace=\"https://eim.io/accounting/classification\">\n"
                + "  <inputData id=\"in_months\" name=\"amortization_months_hint\">"
                + "<variable id=\"v1\" name=\"amortization_months_hint\" typeRef=\"number\"/></inputData>\n"
                + "  <inputData id=\"in_entry\" name=\"entry_type\">"
                + "<variable id=\"v2\" name=\"entry_type\" typeRef=\"string\"/></inputData>\n"
                + "  <inputData id=\"in_component\" name=\"component_family\">"
                + "<variable id=\"v3\" name=\"component_family\" typeRef=\"string\"/></inputData>\n"
                + "  <inputData id=\"in_role\" name=\"payee_role_family\">"
                + "<variable id=\"v4\" name=\"payee_role_family\" typeRef=\"string\"/></inputData>\n"
                + "  <decision id=\"classify\" name=\"classify\">\n"
                + "    <variable id=\"cv\" name=\"classify\"/>\n"
                + "    <informationRequirement id=\"b1\"><requiredInput href=\"#in_months\"/></informationRequirement>"
                + "<informationRequirement id=\"b2\"><requiredInput href=\"#in_entry\"/></informationRequirement>"
                + "<informationRequirement id=\"b3\"><requiredInput href=\"#in_component\"/></informationRequirement>"
                + "<informationRequirement id=\"b4\"><requiredInput href=\"#in_role\"/></informationRequirement>\n"
                + "    <decisionTable id=\"t\" hitPolicy=\"FIRST\" preferredOrientation=\"Rule-as-Row\">\n"
                + "      <input id=\"i1\" name=\"amortization_months_hint\">"
                + "<inputExpression id=\"ie1\" typeRef=\"number\">"
                + "<text>amortization_months_hint</text></inputExpression></input>\n"
                + "      <input id=\"i2\" name=\"entry_type\">"
                + "<inputExpression id=\"ie2\" typeRef=\"string\">"
                + "<text>entry_type</text></inputExpression></input>\n"
                + "      <input id=\"i3\" name=\"component_family\">"
                + "<inputExpression id=\"ie3\" typeRef=\"string\">"
                + "<text>component_family</text></inputExpression></input>\n"
                + "      <input id=\"i4\" name=\"payee_role_family\">"
                + "<inputExpression id=\"ie4\" typeRef=\"string\">"
                + "<text>payee_role_family</text></inputExpression></input>\n"
                + "      <output id=\"o1\" name=\"classification\" typeRef=\"string\"/>\n"
                + "      <output id=\"o2\" name=\"reason_code\" typeRef=\"string\"/>\n"
                + "      <output id=\"o3\" name=\"amortization_months\" typeRef=\"number\"/>\n"
                + "      <rule id=\"r0\">"
                + "<inputEntry id=\"r0i1\"><text>-</text></inputEntry>"
                + "<inputEntry id=\"r0i2\"><text>-</text></inputEntry>"
                + "<inputEntry id=\"r0i3\"><text>-</text></inputEntry>"
                + "<inputEntry id=\"r0i4\"><text>-</text></inputEntry>"
                + "<outputEntry id=\"r0o1\"><text>\"EXPENSE\"</text></outputEntry>"
                + "<outputEntry id=\"r0o2\"><text>\"STATUTORY_EXPENSE_ALL\"</text></outputEntry>"
                + "<outputEntry id=\"r0o3\"><text>12</text></outputEntry></rule>\n"
                + "    </decisionTable>\n"
                + "  </decision>\n"
                + "</definitions>\n";
        given().contentType("text/xml")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .body(bespoke)
                .when().put("/api/v1/accounting/books/" + localBook + "/dmn")
                .then().statusCode(200).body("dmnVersion", org.hamcrest.Matchers.equalTo(2));
    }

    private void ledger(Connection c, String payee, String component, String amount,
                        String type, String txn) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                        + "payee_id, component_name, payout_amount, entry_type, calculation_trace, "
                        + "is_locked, transaction_id) VALUES (gen_random_uuid(),CAST(? AS uuid),"
                        + "CAST(? AS uuid),CAST(? AS uuid),?,?,?,CAST('{}' AS jsonb),true,"
                        + "CAST(? AS uuid))")) {
            ps.setString(1, TENANT);
            ps.setString(2, PERIOD);
            ps.setString(3, payee);
            ps.setString(4, component);
            ps.setBigDecimal(5, new BigDecimal(amount));
            ps.setString(6, type);
            ps.setString(7, txn);
            ps.executeUpdate();
        }
    }

    @AfterEach
    void cleanup() throws SQLException {
        clean();
    }

    private void clean() throws SQLException {
        try (Connection c = ds.getConnection()) {
            sql(c, "DELETE FROM gl_journal_entry_line WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM gl_journal_batch WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM erp_revenue_event WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM amortization_schedule_line WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM capitalized_cost_asset WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM ledger_classification WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM accounting_book_policy WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM commission_ledger_entry WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM raw_transaction WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
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
    @TestSecurity(user = "acc.fin", roles = {"finance_approver"})
    void multiBookClassificationFxLockAndCompleteness() {
        // classification refuses an OPEN period (not in this fixture — LOCKED already)
        var us = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .queryParam("monthsHint", 24)
                .when().post("/api/v1/accounting/books/" + usBook + "/classification/classify/2026-acc")
                .then().statusCode(200).extract().jsonPath();
        // 3 entries: rep commission + mgr rollup capitalize (24 > expedient 12),
        // clawback expensed
        assertEquals(3, (int) us.getInt("entries"));
        assertEquals(2, (int) us.getInt("capitalized"));
        assertEquals(1, (int) us.getInt("expensed"));

        // statutory book: bespoke table expenses everything
        var local = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .queryParam("monthsHint", 24)
                .when().post("/api/v1/accounting/books/" + localBook + "/classification/classify/2026-acc")
                .then().statusCode(200).extract().jsonPath();
        assertEquals(0, (int) local.getInt("capitalized"), "multi-book: statutory expenses all");
        assertEquals(3, (int) local.getInt("expensed"));

        // completeness: both books 100%
        for (String book : new String[]{usBook, localBook}) {
            given()
                    .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                    .header("X-Roles", "finance_approver")
                    .when().get("/api/v1/accounting/books/" + book + "/classification/completeness/2026-acc")
                    .then().statusCode(200)
                    .body("complete", org.hamcrest.Matchers.equalTo(true));
        }

        // FX lock: rep's asset carries the transaction's EUR rate, never revalued
        String fx = scalar("SELECT fx_rate FROM capitalized_cost_asset a "
                + "JOIN ledger_classification lc ON lc.classification_id = a.classification_id "
                + "JOIN commission_ledger_entry l ON l.ledger_id = lc.ledger_id "
                + "WHERE a.book_id = '" + usBook + "' AND l.payee_id = '" + REP + "'");
        assertEquals("1.085000", fx, "historical spot rate locked at capitalization (FR-905)");

        // fringe applied: 20000 × 1.085? no — fringe on the payout, fx only recorded
        String capAmt = scalar("SELECT capitalized_amount FROM capitalized_cost_asset a "
                + "JOIN ledger_classification lc ON lc.classification_id = a.classification_id "
                + "JOIN commission_ledger_entry l ON l.ledger_id = lc.ledger_id "
                + "WHERE a.book_id = '" + usBook + "' AND l.payee_id = '" + REP + "'");
        assertEquals(0, new BigDecimal("21700.0000").compareTo(new BigDecimal(capAmt)),
                "20000 + 8.5% fringe capitalized");
    }

    @Test
    @TestSecurity(user = "acc.fin", roles = {"finance_approver"})
    void rollforwardIdentityReclassAndJournals() {
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .queryParam("monthsHint", 24)
                .when().post("/api/v1/accounting/books/" + usBook + "/classification/classify/2026-acc").then();

        // amortization JE for the first schedule period
        String firstPeriod = scalar("SELECT min(period_key) FROM amortization_schedule_line "
                + "WHERE book_id = '" + usBook + "'");
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .contentType("application/json")
                .body(Map.of("subsidiary", "EIM-NA", "costCenter", "CC-100"))
                .when().post("/api/v1/accounting/books/" + usBook + "/jes/" + firstPeriod
                        + "/AMORTIZATION")
                .then().statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("PENDING"));

        // payroll accrual + capitalization templates over the locked period
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .contentType("application/json").body("{}")
                .when().post("/api/v1/accounting/books/" + usBook + "/jes/2026-acc/PAYROLL_ACCRUAL")
                .then().statusCode(200);
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .contentType("application/json").body("{}")
                .when().post("/api/v1/accounting/books/" + usBook + "/jes/2026-acc/CAPITALIZATION")
                .then().statusCode(200);

        // balance gate green for both periods
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .when().get("/api/v1/accounting/books/" + usBook + "/jes/2026-acc/balance-check")
                .then().statusCode(200).body("balanced", org.hamcrest.Matchers.equalTo(true));
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .when().get("/api/v1/accounting/books/" + usBook + "/jes/" + firstPeriod
                        + "/balance-check")
                .then().statusCode(200).body("balanced", org.hamcrest.Matchers.equalTo(true));

        // rollforward identity over the capitalization period
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .when().get("/api/v1/accounting/books/" + usBook + "/amortization/rollforward/2026-acc")
                .then().statusCode(200)
                .body("capitalized", org.hamcrest.Matchers.equalTo("26040.0000"))
                .body("identityHolds", org.hamcrest.Matchers.equalTo(true));

        // ST/LT reclass for the first schedule period
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .contentType("application/json")
                .when().post("/api/v1/accounting/books/" + usBook + "/amortization/reclass/" + firstPeriod)
                .then().statusCode(200);
        // duplicate reclass refused
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .contentType("application/json")
                .when().post("/api/v1/accounting/books/" + usBook + "/amortization/reclass/" + firstPeriod)
                .then().statusCode(409);

        // CSV drill-down export with the full lineage header
        String csv = given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .when().get("/api/v1/accounting/books/" + usBook
                        + "/amortization/rollforward/" + firstPeriod + "/export")
                .then().statusCode(200).extract().asString();
        assertTrue(csv.startsWith("period_key,line_type"), "CSV header: " + csv.substring(0, 60));
        assertTrue(csv.contains("ACC-1"), "contract ref in drill-down");

        // ERP push lifecycle: fail → retry(exported) → ack
        String batchId = scalar("SELECT batch_id FROM gl_journal_batch "
                + "WHERE book_id = '" + usBook + "' AND je_type = 'PAYROLL_ACCRUAL' "
                + "AND period_key = '2026-acc'");
        assertNotNull(batchId);
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("erpTarget", "NETSUITE", "simulateFailure", "FAILED"))
                .when().post("/api/v1/accounting/books/" + usBook + "/jes/batch/" + batchId + "/push")
                .then().statusCode(200).body("status", org.hamcrest.Matchers.equalTo("FAILED"));
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("erpTarget", "NETSUITE"))
                .when().post("/api/v1/accounting/books/" + usBook + "/jes/batch/" + batchId + "/push")
                .then().statusCode(200).body("status", org.hamcrest.Matchers.equalTo("EXPORTED"));
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .contentType("application/json")
                .when().post("/api/v1/accounting/books/" + usBook + "/jes/batch/" + batchId + "/ack")
                .then().statusCode(200).body("status", org.hamcrest.Matchers.equalTo("SYNCED"));
        assertEquals("1", scalar("SELECT retry_count FROM gl_journal_batch "
                + "WHERE batch_id = '" + batchId + "'"), "one retry after the simulated failure");

        // zero-orphan lineage: a GL line drills to transaction + rep
        String glLine = scalar("SELECT gl_line_id FROM gl_journal_entry_line g "
                + "JOIN gl_journal_batch b ON b.batch_id = g.batch_id "
                + "WHERE b.je_type = 'PAYROLL_ACCRUAL' AND g.transaction_id IS NOT NULL "
                + "AND b.book_id = '" + usBook + "' LIMIT 1");
        assertNotNull(glLine);
        var chain = given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .when().get("/api/v1/accounting/lineage/gl-lines/" + glLine)
                .then().statusCode(200).extract().jsonPath();
        assertEquals("ACC-1", chain.getString("transaction.externalDealId"));
        assertEquals("A-1", chain.getString("rep.employeeNumber"));

        // sampled lineage audit: zero orphans
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .contentType("application/json")
                .when().post("/api/v1/accounting/lineage/audit-sample/2026-acc?sample=10")
                .then().statusCode(200).body("zeroOrphan", org.hamcrest.Matchers.equalTo(true));
    }

    @Test
    @TestSecurity(user = "acc.fin", roles = {"finance_approver"})
    void lifecycleChurnDowngradeRetroAndImmutability() {
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .queryParam("monthsHint", 24)
                .when().post("/api/v1/accounting/books/" + usBook + "/classification/classify/2026-acc").then();
        String repAsset = scalar("SELECT asset_id FROM capitalized_cost_asset a "
                + "JOIN ledger_classification lc ON lc.classification_id = a.classification_id "
                + "JOIN commission_ledger_entry l ON l.ledger_id = lc.ledger_id "
                + "WHERE a.book_id = '" + usBook + "' AND l.payee_id = '" + REP + "'");

        // mark the first period's schedule as posted → immutability trigger
        sql0("UPDATE amortization_schedule_line SET posted = true, posted_period_key = period_key "
                + "WHERE asset_id = '" + repAsset + "' AND period_key = "
                + "(SELECT min(period_key) FROM amortization_schedule_line WHERE asset_id = '"
                + repAsset + "')");
        SQLException ex = null;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE amortization_schedule_line SET amount = 1 WHERE asset_id = CAST(? AS uuid) "
                             + "AND posted = true")) {
            ps.setString(1, repAsset);
            ps.executeUpdate();
        } catch (SQLException e) {
            ex = e;
        }
        assertNotNull(ex, "posted schedule line mutation must be rejected");
        assertTrue(ex.getMessage().contains("immutable"), "FR-907.3: " + ex.getMessage());

        // retro true-up: child stream + catch-up in the discovery period
        var retro = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("additionalAmount", "5000", "discoveryPeriodKey", "2026-05"))
                .when().post("/api/v1/accounting/assets/" + repAsset + "/retro-trueup")
                .then().statusCode(200).extract().jsonPath();
        String child = retro.getString("childAssetId");
        assertEquals(repAsset, retro.getString("parentAssetId"));
        assertEquals("1", scalar("SELECT count(*) FROM amortization_schedule_line "
                + "WHERE asset_id = '" + child + "' AND line_type = 'CATCH_UP' "
                + "AND period_key = '2026-05'"), "cumulative catch-up posts in discovery period");
        assertEquals(repAsset, scalar("SELECT parent_asset_id::text FROM capitalized_cost_asset "
                + "WHERE asset_id = '" + child + "'"), "child linked to parent");

        // churn: future unposted lines zeroed + impairment of the remainder
        var churn = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("openPeriodKey", "2026-05", "comment", "churn in clawback window"))
                .when().post("/api/v1/accounting/assets/" + repAsset + "/churn")
                .then().statusCode(200).extract().jsonPath();
        assertEquals("CHURNED", churn.getString("status"));
        assertTrue(churn.getInt("futureLinesZeroed") >= 1);
        assertEquals("0.0000", scalar("SELECT coalesce(sum(amount),0)::text "
                + "FROM amortization_schedule_line WHERE asset_id = '" + repAsset
                + "' AND line_type = 'SCHEDULED' AND period_key > '2026-05'"),
                "future lines zeroed");
        assertNotNull(churn.getString("impairment"), "remaining balance impaired");
        // the posted first-period line is untouched by churn
        assertTrue(Double.parseDouble(scalar("SELECT amount FROM amortization_schedule_line "
                + "WHERE asset_id = '" + repAsset + "' AND posted = true")) > 0,
                "posted line survives lifecycle events");

        // downgrade on the manager asset follows PRO_RATE_IMPAIR policy
        String mgrAsset = scalar("SELECT asset_id FROM capitalized_cost_asset a "
                + "JOIN ledger_classification lc ON lc.classification_id = a.classification_id "
                + "JOIN commission_ledger_entry l ON l.ledger_id = lc.ledger_id "
                + "WHERE a.book_id = '" + usBook + "' AND l.payee_id = '" + MGR + "'");
        var dg = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "acc.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("contractionPct", 0.4, "openPeriodKey", "2026-05"))
                .when().post("/api/v1/accounting/assets/" + mgrAsset + "/downgrade")
                .then().statusCode(200).extract().jsonPath();
        assertEquals("PRO_RATE_IMPAIR", dg.getString("policy"));
        assertTrue(new BigDecimal(dg.getString("impairment")).signum() > 0,
                "40% contraction impairs 40% of the remainder");


    }

    /** tiny helper for inline SQL from test bodies */
    private void sql0(String sql) {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
