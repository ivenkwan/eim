package io.eim.calcengine;

import io.agroal.api.AgroalDataSource;
import io.eim.calcengine.run.WavefrontRunner;
import io.quarkus.test.junit.QuarkusTest;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG3-CAL-15 / TST-2 (NFR-20/03): the reference sizing — 500 payees x 200
 * transactions (100k rows) — must complete a full wavefront run within the
 * 15 s budget, and a replay of the same inputs must reproduce the ledger
 * byte-identically (determinism).
 */
@QuarkusTest
class BenchmarkAndDeterminismTest {

    private static final String TENANT = "77777777-0000-0000-0000-000000000001";
    private static final String MGR = "77777777-0000-0000-0000-00000000ff01";
    private static final String PERIOD = "77777777-0000-0000-0000-00000000ee01";
    private static final String HASH =
            "bench0000000000000000000000000000000000000000000000000000000c0fe";
    private static final int PAYEES = 500;
    private static final int TXNS_PER = 200;

    @Inject
    AgroalDataSource ds;

    @Inject
    WavefrontRunner runner;

    @BeforeEach
    void seed() throws SQLException {
        try (Connection c = ds.getConnection()) {
            run(c, "DELETE FROM calc_run WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            run(c, "DELETE FROM commission_ledger_entry WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            run(c, "DELETE FROM credit_allocation WHERE transaction_id IN "
                    + "(SELECT transaction_id FROM raw_transaction WHERE tenant_id = "
                    + "CAST('" + TENANT + "' AS uuid))");
            run(c, "DELETE FROM raw_transaction WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            run(c, "SELECT eim_ops.detach_delete_payee(CAST('" + MGR + "' AS uuid))");
            run(c, "DELETE FROM payee WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            run(c, "DELETE FROM payout_period WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            run(c, "ALTER TABLE plan_artifact DISABLE TRIGGER trg_plan_artifact_append_only");
            run(c, "DELETE FROM plan_artifact WHERE plan_version_hash = '" + HASH + "'");
            run(c, "ALTER TABLE plan_artifact ENABLE TRIGGER trg_plan_artifact_append_only");
        }
        String[] repIds = new String[PAYEES];
        try (Connection c = ds.getConnection()) {
            run(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                            + "role_title, effective_start_date) VALUES (CAST('" + MGR
                            + "' AS uuid),CAST('" + TENANT + "' AS uuid),'B-0','Bench Manager',"
                            + "'bench.mgr@example.test','Mgr',DATE '2026-01-01')");
            for (int i = 0; i < PAYEES; i++) {
                repIds[i] = UUID.nameUUIDFromBytes(("bench-rep-" + i).getBytes()).toString();
                run(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                                + "role_title, manager_payee_id, effective_start_date) "
                                + "VALUES (CAST('" + repIds[i] + "' AS uuid),CAST('" + TENANT
                                + "' AS uuid),'" + ("B-" + (i + 1)) + "','Bench " + i
                                + "','bench" + i + "@example.test','AE',CAST('" + MGR
                                + "' AS uuid),DATE '2026-01-01')");
            }
            run(c, "INSERT INTO payout_period (payout_period_id, tenant_id, period_key, "
                    + "starts_on, ends_on, status) VALUES (CAST('" + PERIOD + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),'2026-bench','2026-04-01','2026-04-30','OPEN')");
            run(c, "INSERT INTO plan_artifact (plan_version_hash, tenant_id, plan_name, plan_json, "
                    + "model_json, drl, helper_java, dmn) VALUES ('" + HASH + "',"
                    + "CAST('" + TENANT + "' AS uuid),'bench','{}',CAST('" + MODEL_JSON
                    + "' AS jsonb),'stub','stub','stub')");
            batchTransactions(c, repIds);
        }
    }

    @AfterEach
    void cleanup() throws SQLException {
        try (Connection c = ds.getConnection()) {
            run(c, "DELETE FROM calc_run WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            run(c, "DELETE FROM commission_ledger_entry WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            run(c, "DELETE FROM credit_allocation WHERE transaction_id IN "
                    + "(SELECT transaction_id FROM raw_transaction WHERE tenant_id = "
                    + "CAST('" + TENANT + "' AS uuid))");
            run(c, "DELETE FROM raw_transaction WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            run(c, "SELECT eim_ops.detach_delete_payee(CAST('" + MGR + "' AS uuid))");
            run(c, "DELETE FROM payee WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            run(c, "DELETE FROM payout_period WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            run(c, "ALTER TABLE plan_artifact DISABLE TRIGGER trg_plan_artifact_append_only");
            run(c, "DELETE FROM plan_artifact WHERE plan_version_hash = '" + HASH + "'");
            run(c, "ALTER TABLE plan_artifact ENABLE TRIGGER trg_plan_artifact_append_only");
        }
    }

    @Test
    void referenceSizingWithin15sAndDeterministicReplay() {
        long first = runOnce();
        assertTrue(first <= 15_000, "NFR-20: 500 payees x 100k tx must run < 15s, took " + first + "ms");
        String firstTotal = ledgerFingerprint();
        long second = runOnce();
        assertEquals(firstTotal, ledgerFingerprint(),
                "replay produces the identical ledger fingerprint (NFR-03)");
        // 20% regression alert threshold (NFR-20): second run within +20%
        assertTrue(second <= first * 6 / 5 + 500,
                "replay within the 20% regression alert margin: " + first + "ms vs " + second + "ms");
    }

    private long runOnce() {
        return given().contentType("application/json")
                .body(Map.of("tenantId", TENANT, "payoutPeriodId", PERIOD, "planVersionHash", HASH))
                .when().post("/api/v1/calc/runs")
                .then().statusCode(202)
                .extract().jsonPath().getLong("durationMs");
    }

    /** Stable fingerprint: row count + ordered payout/trace digest. */
    private String ledgerFingerprint() {
        String sql = "SELECT count(*) || ':' || coalesce(sum(payout_amount), 0) || ':' "
                + "|| coalesce(md5(string_agg(payout_amount::text || '/' || component_name "
                + "|| '/' || payee_id::text, ',' ORDER BY payee_id, component_name)), 'x') "
                + "FROM commission_ledger_entry WHERE tenant_id = CAST('" + TENANT + "' AS uuid)";
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void batchTransactions(Connection c, String[] repIds) throws SQLException {
        String batchId = "77777777-0000-0000-0000-00000000eeee";
        try (PreparedStatement tx = c.prepareStatement(
                "INSERT INTO raw_transaction (transaction_id, tenant_id, source_system, "
                        + "external_deal_id, transaction_date, booking_amount, currency_code, "
                        + "ingestion_batch_id) VALUES (?,?,?,?,?,?, 'USD', ?)");
             PreparedStatement ca = c.prepareStatement(
                     "INSERT INTO credit_allocation (credit_allocation_id, tenant_id, "
                             + "transaction_id, payee_id, split_pct, effective_date) "
                             + "VALUES (?,?,?,?,1,?)")) {
            int n = 0;
            for (int i = 0; i < PAYEES; i++) {
                for (int j = 0; j < TXNS_PER; j++) {
                    String txId = UUID.nameUUIDFromBytes(("bench-tx-" + i + "-" + j).getBytes()).toString();
                    int day = 1 + (j % 28);
                    tx.setObject(1, UUID.fromString(txId));
                    tx.setObject(2, UUID.fromString(TENANT));
                    tx.setString(3, "CSV");
                    tx.setString(4, "B-" + i + "-" + j);
                    tx.setDate(5, java.sql.Date.valueOf(
                            "2026-04-" + (day < 10 ? "0" + day : day)));
                    tx.setBigDecimal(6, java.math.BigDecimal.valueOf(500 + (j % 50) * 10));
                    tx.setObject(7, UUID.fromString(batchId));
                    tx.addBatch();

                    ca.setObject(1, UUID.randomUUID());
                    ca.setObject(2, UUID.fromString(TENANT));
                    ca.setObject(3, UUID.fromString(txId));
                    ca.setObject(4, UUID.fromString(repIds[i]));
                    ca.setDate(5, java.sql.Date.valueOf(
                            "2026-04-" + (day < 10 ? "0" + day : day)));
                    ca.addBatch();
                    n++;
                    if (n % 5000 == 0) {
                        tx.executeBatch();
                        ca.executeBatch();
                    }
                }
            }
            tx.executeBatch();
            ca.executeBatch();
        }
    }

    private void run(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static final String MODEL_JSON = """
            {"planName":"bench","components":[
              {"name":"commission","kind":"TIER_TABLE","outputType":"CURRENCY",
               "tiers":{"mode":"MARGINAL","basis":"AMOUNT","brackets":[
                 {"upTo":"100000","rate":"0.10"},
                 {"upTo":"150000","rate":"0.15"},
                 {"rate":"0.20"}]}},
              {"name":"team_commission","kind":"ROLLUP","outputType":"CURRENCY",
               "dependsOn":["commission"]}]}
            """;
}
