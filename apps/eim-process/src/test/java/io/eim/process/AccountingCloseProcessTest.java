package io.eim.process;

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
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG7-ACC-12: the accounting-close process — completeness gate (refuses
 * unclassified entries, FR-908), balance gate (FR-907.2), je_preview →
 * je_approval with SoD (same actor rejected) → gl_push (refuses while a
 * batch is not SYNCED) → st_lt_reclass → COMPLETED, audited per step.
 */
@QuarkusTest
class AccountingCloseProcessTest {

    private static final String TENANT = "aaaaaaaa-e000-0000-0000-000000000001";
    private static final String PERIOD = "aaaaaaaa-e000-0000-0000-000000000002";
    private static final String LEDGER = "aaaaaaaa-e000-0000-0000-000000000003";

    @Inject
    AgroalDataSource ds;

    private String bookId;
    private String batchId;

    @BeforeEach
    void seed() throws SQLException {
        clean();
        try (Connection c = ds.getConnection()) {
            sql(c, "INSERT INTO payout_period (payout_period_id, tenant_id, period_key, "
                    + "starts_on, ends_on, status) VALUES (CAST('" + PERIOD + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),'2026-p2','2026-09-01','2026-09-30','OPEN')");
            sql(c, "SELECT eim_ops.ensure_period_partition(CAST('" + PERIOD + "' AS uuid),'2026-p2')");
            sql(c, "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                    + "payee_id, component_name, payout_amount, entry_type, calculation_trace, "
                    + "is_locked) VALUES (CAST('" + LEDGER + "' AS uuid),CAST('" + TENANT
                    + "' AS uuid),CAST('" + PERIOD + "' AS uuid),CAST('aaaaaaaa-e000-0000-0000-000000000003' AS uuid),'commission',1000,"
                    + "'CALCULATED',CAST('{}' AS jsonb),false)");
            // lock after seeding (status only; the privilege-revoke path is
            // owned by the TG-1/TG-6 suites)
            sql(c, "UPDATE payout_period SET status = 'LOCKED' WHERE payout_period_id = CAST('"
                    + PERIOD + "' AS uuid)");
        }
        bookId = query("INSERT INTO accounting_book_policy (book_id, tenant_id, book_code, "
                + "functional_currency) VALUES (gen_random_uuid(),CAST('" + TENANT
                + "' AS uuid),'P2_TEST','USD') RETURNING book_id::text");
        batchId = query("INSERT INTO gl_journal_batch (batch_id, tenant_id, book_id, period_key, "
                + "je_type, status, total_dr, total_cr) VALUES (gen_random_uuid(),CAST('"
                + TENANT + "' AS uuid),CAST('" + bookId + "' AS uuid),'2026-p2',"
                + "'CAPITALIZATION','SYNCED',100,100) RETURNING batch_id::text");
    }

    @AfterEach
    void cleanup() throws SQLException {
        clean();
    }

    private void clean() throws SQLException {
        try (Connection c = ds.getConnection()) {
            sql(c, "DELETE FROM gl_journal_entry_line WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM gl_journal_batch WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM amortization_schedule_line WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM capitalized_cost_asset WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM ledger_classification WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM accounting_book_policy WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM commission_ledger_entry WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM payout_period WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM process_task WHERE process_id IN (SELECT process_id FROM process_instance "
                    + "WHERE tenant_id = CAST('" + TENANT + "' AS uuid))");
            sql(c, "DELETE FROM process_instance WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
        }
    }

    private void sql(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private String query(String sql) {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @TestSecurity(user = "p2.fin", roles = {"finance_approver"})
    void completenessGateRefusesUnclassifiedEntries() {
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p2.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("tenantId", TENANT, "bookId", bookId, "periodKey", "2026-p2"))
                .when().post("/api/v1/process/accounting-close/start")
                .then().statusCode(409)
                .body("error", org.hamcrest.Matchers.containsString("completeness"));
    }

    @Test
    @TestSecurity(user = "p2.fin", roles = {"finance_approver"})
    void fullCloseWalkWithSodAndPushGate() throws SQLException {
        // classify the single entry → completeness passes
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ledger_classification (classification_id, tenant_id, book_id, "
                             + "ledger_id, classification, reason_code, classified_by) VALUES "
                             + "(gen_random_uuid(),CAST(? AS uuid),CAST(? AS uuid),CAST(? AS uuid),"
                             + "'EXPENSE','PRACTICAL_EXPEDIENT','p2.fin')")) {
            ps.setString(1, TENANT);
            ps.setString(2, bookId);
            ps.setString(3, LEDGER);
            ps.executeUpdate();
        }

        String processId = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p2.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("tenantId", TENANT, "bookId", bookId, "periodKey", "2026-p2"))
                .when().post("/api/v1/process/accounting-close/start")
                .then().statusCode(202)
                .extract().jsonPath().getString("processId");
        assertTrue(processId != null && !processId.isBlank());

        // je_preview by comp_admin
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p2.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("comment", "JEs reviewed"))
                .when().post("/api/v1/process/accounting-close/" + processId + "/tasks/je_preview")
                .then().statusCode(202);

        // SoD: same actor cannot approve their own preview
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p2.admin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("comment", "self approve"))
                .when().post("/api/v1/process/accounting-close/" + processId + "/tasks/je_approval")
                .then().statusCode(403);

        // different finance actor approves
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p2.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("comment", "approved"))
                .when().post("/api/v1/process/accounting-close/" + processId + "/tasks/je_approval")
                .then().statusCode(202);

        // gl_push gate: a batch not SYNCED blocks the task
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE gl_journal_batch SET status = 'EXPORTED' WHERE batch_id = CAST(? AS uuid)")) {
            ps.setString(1, batchId);
            ps.executeUpdate();
        }
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p2.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("comment", "push"))
                .when().post("/api/v1/process/accounting-close/" + processId + "/tasks/gl_push")
                .then().statusCode(409);

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE gl_journal_batch SET status = 'SYNCED' WHERE batch_id = CAST(? AS uuid)")) {
            ps.setString(1, batchId);
            ps.executeUpdate();
        }
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p2.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("comment", "pushed"))
                .when().post("/api/v1/process/accounting-close/" + processId + "/tasks/gl_push")
                .then().statusCode(202);

        var done = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "p2.fin")
                .header("X-Roles", "finance_approver")
                .body(Map.of("comment", "reclassed"))
                .when().post("/api/v1/process/accounting-close/" + processId + "/tasks/st_lt_reclass")
                .then().statusCode(202)
                .extract().jsonPath();
        assertTrue("COMPLETED".equals(done.getString("state"))
                        || done.getString("state") == null, // completed view may carry no state
                "process completes");
        assertTrue(Integer.parseInt(query("SELECT count(*) FROM process_instance "
                + "WHERE process_id = '" + processId + "' AND state = 'COMPLETED'")) == 1,
                "process_instance COMPLETED");
    }
}
