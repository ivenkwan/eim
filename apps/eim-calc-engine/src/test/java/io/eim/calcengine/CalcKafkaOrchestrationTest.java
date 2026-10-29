package io.eim.calcengine;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.eim.calcengine.run.CalcKafka;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG3-CAL-14 (arch §7.1): a Kafka eim.calc.run.requested event triggers the
 * full wavefront run; the engine answers with eim.calc.run.completed and
 * records the CALCULATION_RUN audit row over the KAFKA channel.
 */
@QuarkusTest
class CalcKafkaOrchestrationTest {

    private static final String TENANT = "88888888-0000-0000-0000-000000000001";
    private static final String MGR = "88888888-0000-0000-0000-00000000aa01";
    private static final String REP = "88888888-0000-0000-0000-00000000aa02";
    private static final String PERIOD = "88888888-0000-0000-0000-00000000bb01";
    private static final String HASH =
            "kafka0000000000000000000000000000000000000000000000000000000c0fe";

    @Inject
    AgroalDataSource ds;

    @Inject
    CalcKafka calcKafka;

    @BeforeEach
    void seed() throws SQLException {
        clean();
        try (Connection c = ds.getConnection()) {
            st(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, effective_start_date) VALUES (CAST('" + MGR + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),'K-0','Kafka Manager','kafka.mgr@example.test',"
                    + "'Mgr',DATE '2026-01-01')");
            st(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, manager_payee_id, effective_start_date) "
                    + "VALUES (CAST('" + REP + "' AS uuid),CAST('" + TENANT + "' AS uuid),"
                    + "'K-1','Kafka Rep','kafka.rep@example.test','AE',"
                    + "CAST('" + MGR + "' AS uuid),DATE '2026-01-01')");
            st(c, "INSERT INTO payout_period (payout_period_id, tenant_id, period_key, "
                    + "starts_on, ends_on, status) VALUES (CAST('" + PERIOD + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),'2026-kafka','2026-04-01','2026-04-30','OPEN')");
            st(c, "INSERT INTO plan_artifact (plan_version_hash, tenant_id, plan_name, plan_json, "
                    + "model_json, drl, helper_java, dmn) VALUES ('" + HASH + "',"
                    + "CAST('" + TENANT + "' AS uuid),'kafka','{}',CAST('" + MODEL_JSON
                    + "' AS jsonb),'stub','stub','stub')");
            String txId = UUID.nameUUIDFromBytes("kafka-tx-1".getBytes()).toString();
            st(c, "INSERT INTO raw_transaction (transaction_id, tenant_id, source_system, "
                    + "external_deal_id, transaction_date, booking_amount, currency_code, "
                    + "ingestion_batch_id) VALUES (CAST('" + txId + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),'CSV','K-1','2026-04-05',160000,'USD',"
                    + "CAST('" + TENANT + "' AS uuid))");
            st(c, "INSERT INTO credit_allocation (credit_allocation_id, tenant_id, transaction_id, "
                    + "payee_id, split_pct, effective_date) VALUES (gen_random_uuid(),"
                    + "CAST('" + TENANT + "' AS uuid),CAST('" + txId + "' AS uuid),"
                    + "CAST('" + REP + "' AS uuid),100,DATE '2026-04-05')");
        }
    }

    @AfterEach
    void cleanup() throws SQLException {
        clean();
    }

    private void clean() throws SQLException {
        try (Connection c = ds.getConnection()) {
            st(c, "DELETE FROM calc_run WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            st(c, "DELETE FROM commission_ledger_entry WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            st(c, "DELETE FROM credit_allocation WHERE transaction_id IN "
                    + "(SELECT transaction_id FROM raw_transaction WHERE tenant_id = "
                    + "CAST('" + TENANT + "' AS uuid))");
            st(c, "DELETE FROM raw_transaction WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            st(c, "SELECT eim_ops.detach_delete_payee(CAST('" + MGR + "' AS uuid))");
            st(c, "SELECT eim_ops.detach_delete_payee(CAST('" + REP + "' AS uuid))");
            st(c, "DELETE FROM payee WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            st(c, "DELETE FROM payout_period WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            st(c, "ALTER TABLE plan_artifact DISABLE TRIGGER trg_plan_artifact_append_only");
            st(c, "DELETE FROM plan_artifact WHERE plan_version_hash = '" + HASH + "'");
            st(c, "ALTER TABLE plan_artifact ENABLE TRIGGER trg_plan_artifact_append_only");
        }
    }

    @Test
    void requestedEventTriggersRunAndAudit() {
        // sanity: HTTP path works for this fixture
        given().contentType("application/json")
                .body(Map.of("tenantId", TENANT, "payoutPeriodId", PERIOD, "planVersionHash", HASH))
                .when().post("/api/v1/calc/runs").then().statusCode(202);
        long httpRuns = count("SELECT count(*) FROM calc_run WHERE tenant_id = "
                + "CAST('" + TENANT + "' AS uuid)");
        assertTrue(httpRuns >= 1, "http run completed");

        // the orchestration path: deliver the request message exactly as
        // the Kafka consumer would (the verify-tg3 live gate proves the
        // topic round-trip in-cluster; the host cannot resolve the
        // broker's advertised name through the port-forward)
        long httpRunsFinal = count("SELECT count(*) FROM calc_run WHERE tenant_id = "
                + "CAST('" + TENANT + "' AS uuid)");
        calcKafka.onRunRequested(Message.of("{\"tenantId\":\"" + TENANT
                + "\",\"payoutPeriodId\":\"" + PERIOD
                + "\",\"planVersionHash\":\"" + HASH + "\"}")).toCompletableFuture().join();

        // the engine answers: another completed run + a KAFKA-channel audit
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    long runs = count("SELECT count(*) FROM calc_run WHERE tenant_id = "
                            + "CAST('" + TENANT + "' AS uuid) AND status = 'COMPLETED'");
                    assertTrue(runs >= httpRunsFinal + 1,
                            "kafka-triggered run completed (had " + httpRunsFinal
                                    + ", now " + runs + ")");
                    long audits = count(
                            "SELECT count(*) FROM audit_log WHERE action_type = 'CALCULATION_RUN' "
                                    + "AND source_channel = 'KAFKA' AND tenant_id = "
                                    + "CAST('" + TENANT + "' AS uuid)");
                    assertTrue(audits >= 1, "CALCULATION_RUN audit over KAFKA channel");
                });
    }

    private long count(String sql) {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void st(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static final String MODEL_JSON = """
            {"planName":"kafka","components":[
              {"name":"commission","kind":"TIER_TABLE","outputType":"CURRENCY",
               "tiers":{"mode":"MARGINAL","basis":"AMOUNT","brackets":[
                 {"upTo":"100000","rate":"0.10"},
                 {"upTo":"150000","rate":"0.15"},
                 {"rate":"0.20"}]}}]}
            """;
}
