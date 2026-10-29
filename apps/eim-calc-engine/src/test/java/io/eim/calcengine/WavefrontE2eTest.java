package io.eim.calcengine;

import io.agroal.api.AgroalDataSource;
import io.eim.calcengine.run.WavefrontRunner;
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
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG3-CAL-5/10/11/12/13 + TST-3 (live AGE postgres): the wavefront runner
 * executes a fixture org against a published plan, commits the ledger
 * atomically with JSONB traces, resolves manager rollups across the wave
 * barrier, rolls back CLEANLY on a mid-wave injected fault, and the shadow
 * replay emits a typed PRIOR_PERIOD_TRUEUP into the open period with
 * origin linkage after a late-arriving change. All statements are constant
 * SQL strings with bound parameters.
 */
@QuarkusTest
class WavefrontE2eTest {

    private static final String TENANT = "66666666-0000-0000-0000-000000000001";
    private static final String MGR = "c0ffee00-0000-0000-0000-00000000aa01";
    private static final String REP = "c0ffee00-0000-0000-0000-00000000aa02";
    private static final String P_APR = "c0ffee00-0000-0000-0000-00000000bb01";
    private static final String P_MAY = "c0ffee00-0000-0000-0000-00000000bb02";
    private static final String HASH =
            "e3test00000000000000000000000000000000000000000000000000c0ffee";

    @Inject
    AgroalDataSource ds;

    @Inject
    WavefrontRunner runner;

    @Inject
    io.eim.calcengine.run.ShadowReplay replay;

    @BeforeEach
    void seed() throws SQLException {
        cleanSlate();
        try (Connection c = ds.getConnection()) {
            update(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, effective_start_date) VALUES (?::uuid,?::uuid,?,?,?,?,DATE '2026-01-01')",
                    MGR, TENANT, "M-1", "Mia Manager", "mia@example.test", "Mgr");
            update(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                            + "role_title, manager_payee_id, effective_start_date) "
                            + "VALUES (?::uuid,?::uuid,?,?,?,?,?::uuid,DATE '2026-01-01')",
                    REP, TENANT, "R-1", "Riley Rep", "riley@example.test", "AE", MGR);
            update(c, "INSERT INTO payout_period (payout_period_id, tenant_id, period_key, "
                    + "starts_on, ends_on, status) VALUES (?::uuid,?::uuid,?,?::date,?::date,'OPEN')",
                    P_APR, TENANT, "2026-04", "2026-04-01", "2026-04-30");
            update(c, "INSERT INTO payout_period (payout_period_id, tenant_id, period_key, "
                    + "starts_on, ends_on, status) VALUES (?::uuid,?::uuid,?,?::date,?::date,'OPEN')",
                    P_MAY, TENANT, "2026-05", "2026-05-01", "2026-05-31");
            update(c, "INSERT INTO plan_artifact (plan_version_hash, tenant_id, plan_name, "
                            + "plan_json, model_json, drl, helper_java, dmn) "
                            + "VALUES (?,CAST(? AS uuid),?,CAST(? AS jsonb),CAST(? AS jsonb),'stub','stub','stub')",
                    HASH, TENANT, "E2E plan",
                    "{\"canonical\":\"" + HASH + "\"}", MODEL_JSON);
            txn(c, "t1-e2e", "2026-04-05", "100000");
            txn(c, "t2-e2e", "2026-04-20", "60000");
        }
    }

    @AfterEach
    void cleanup() throws SQLException {
        cleanSlate();
        try (Connection c = ds.getConnection()) {
            update(c, "SELECT eim_ops.detach_delete_payee(CAST(? AS uuid))", MGR);
            update(c, "SELECT eim_ops.detach_delete_payee(CAST(? AS uuid))", REP);
        }
    }

    private void cleanSlate() throws SQLException {
        try (Connection c = ds.getConnection()) {
            update(c, "DELETE FROM calc_run WHERE tenant_id = ?::uuid", TENANT);
            update(c, "DELETE FROM trueup_review WHERE tenant_id = ?::uuid", TENANT);
            update(c, "DELETE FROM commission_ledger_entry WHERE tenant_id = ?::uuid", TENANT);
            update(c, "DELETE FROM credit_allocation WHERE transaction_id IN "
                    + "(SELECT transaction_id FROM raw_transaction WHERE tenant_id = ?::uuid)",
                    TENANT);
            update(c, "DELETE FROM raw_transaction WHERE tenant_id = ?::uuid", TENANT);
            update(c, "DELETE FROM payee WHERE tenant_id = ?::uuid", TENANT);
            update(c, "DELETE FROM payout_period WHERE tenant_id = ?::uuid", TENANT);
            update(c, "ALTER TABLE plan_artifact DISABLE TRIGGER trg_plan_artifact_append_only");
            update(c, "DELETE FROM plan_artifact WHERE plan_version_hash = ?", HASH);
            update(c, "ALTER TABLE plan_artifact ENABLE TRIGGER trg_plan_artifact_append_only");
        }
    }

    @Test
    @TestSecurity(user = "calc", roles = {"comp_admin"})
    void wavefrontRunCommitsLedgerTracesAndRollups() {
        var resp = given().contentType("application/json")
                .body(Map.of("tenantId", TENANT, "payoutPeriodId", P_APR,
                        "planVersionHash", HASH))
                .when().post("/api/v1/calc/runs")
                .then().extract().response();
        org.junit.jupiter.api.Assertions.assertEquals(202, resp.getStatusCode(),
                "run: " + resp.getBody().asString());
        var stats = resp.jsonPath();
        assertEquals("COMPLETED", stats.getString("status"));
        assertTrue(stats.getInt("waves") >= 2, "leaf wave + manager wave at minimum");
        assertEquals(2, stats.getInt("ledgerEntries"),
                "rep commission + mgr team_commission rollup");

        // rep commission: 0 -> 160000 over 10/15/20% = 19,500.00
        assertEquals("19500.0000", scalar(
                "SELECT payout_amount::text FROM commission_ledger_entry "
                        + "WHERE tenant_id = ?::uuid AND payee_id = ?::uuid "
                        + "AND component_name = 'commission'", TENANT, REP));
        String trace = scalar(
                "SELECT calculation_trace::text FROM commission_ledger_entry "
                        + "WHERE tenant_id = ?::uuid AND payee_id = ?::uuid "
                        + "AND component_name = 'commission'", TENANT, REP);
        assertTrue(trace.contains(HASH), "plan version pinned in trace");
        assertTrue(trace.contains("slices"), "tier slices in trace");
        assertTrue(trace.length() < 16384, "trace within 16KB (NFR-07)");

        // manager rollup over the wavefront output (FR-303/503)
        assertEquals("19500.0000", scalar(
                "SELECT payout_amount::text FROM commission_ledger_entry "
                        + "WHERE tenant_id = ?::uuid AND payee_id = ?::uuid "
                        + "AND component_name = 'team_commission'", TENANT, MGR));
        assertEquals("1", scalar("SELECT count(*) FROM calc_run WHERE status = 'COMPLETED' "
                + "AND tenant_id = ?::uuid", TENANT));

        // rerun is idempotent: same totals, no duplicates
        given().contentType("application/json")
                .body(Map.of("tenantId", TENANT, "payoutPeriodId", P_APR,
                        "planVersionHash", HASH))
                .when().post("/api/v1/calc/runs")
                .then().statusCode(202);
        assertEquals("19500.0000", scalar(
                "SELECT sum(payout_amount)::text FROM commission_ledger_entry "
                        + "WHERE tenant_id = ?::uuid AND payee_id = ?::uuid "
                        + "AND component_name = 'commission'", TENANT, REP));
    }

    @Test
    @TestSecurity(user = "calc", roles = {"comp_admin"})
    void injectedMidWaveFaultLeavesZeroLedgerRows() {
        given().contentType("application/json")
                .body(Map.of("tenantId", TENANT, "payoutPeriodId", P_APR,
                        "planVersionHash", HASH))
                .when().post("/api/v1/calc/runs").then().statusCode(202);

        runner.failAtPayee(REP);
        try {
            var resp = given().contentType("application/json")
                    .body(Map.of("tenantId", TENANT, "payoutPeriodId", P_APR,
                            "planVersionHash", HASH))
                    .when().post("/api/v1/calc/runs")
                    .then().extract().response();
            assertTrue(resp.getStatusCode() == 500, "fault surfaces as 500, got "
                    + resp.getStatusCode() + ": " + resp.getBody().asString());
        } finally {
            runner.failAtPayee(null);
        }
        // atomic-or-clean (NFR-04): the faulted run wrote NOTHING — the
        // ledger still holds exactly the prior successful run's rows.
        assertEquals("2", scalar("SELECT count(*) FROM commission_ledger_entry "
                + "WHERE payout_period_id = ?::uuid", P_APR),
                "faulted run left no partial rows; prior commit intact");
        assertEquals("1", scalar("SELECT count(*) FROM calc_run WHERE status = 'FAILED' "
                + "AND tenant_id = ?::uuid", TENANT), "failed run recorded");
    }

    @Test
    @TestSecurity(user = "calc", roles = {"comp_admin"})
    void shadowReplayEmitsTrueupWithOriginAfterLateArrival() throws Exception {
        given().contentType("application/json")
                .body(Map.of("tenantId", TENANT, "payoutPeriodId", P_APR,
                        "planVersionHash", HASH))
                .when().post("/api/v1/calc/runs").then().statusCode(202);
        try (Connection c = ds.getConnection()) {
            update(c, "UPDATE payout_period SET status = 'LOCKED', locked_at = now() "
                    + "WHERE payout_period_id = ?::uuid", P_APR);
            txn(c, "t3-late", "2026-04-25", "40000");
        }

        // TG-4: the gateway path routes diffs into the Finance review queue
        var result = given().contentType("application/json")
                .body(Map.of("tenantId", TENANT, "lockedPeriodId", P_APR,
                        "openPeriodId", P_MAY, "planVersionHash", HASH))
                .when().post("/api/v1/calc/shadow-replay")
                .then().statusCode(202)
                .extract().jsonPath();
        assertEquals(1, (int) result.getInt("diffs"), "one payee drifted");
        assertEquals("1", scalar("SELECT count(*) FROM trueup_review "
                + "WHERE open_period_id = ?::uuid AND status = 'PENDING'", P_MAY),
                "diff queued for Finance review (FR-604.2)");

        // the engine-level direct-post path stays available (its own
        // regression): shadow 0 -> 200000 = 27500 vs 19500 -> +8000
        replay.replay(java.util.UUID.fromString(TENANT), java.util.UUID.fromString(P_APR),
                java.util.UUID.fromString(P_MAY), HASH, false);
        assertEquals("8000.0000", scalar(
                "SELECT payout_amount::text FROM commission_ledger_entry "
                        + "WHERE payout_period_id = ?::uuid "
                        + "AND entry_type = 'PRIOR_PERIOD_TRUEUP'", P_MAY));
        assertEquals(P_APR, scalar(
                "SELECT calculation_trace->>'origin_period_id' "
                        + "FROM commission_ledger_entry WHERE payout_period_id = ?::uuid "
                        + "AND entry_type = 'PRIOR_PERIOD_TRUEUP'", P_MAY),
                "origin linkage (FR-508)");
        assertEquals("19500.0000", scalar(
                "SELECT payout_amount::text FROM commission_ledger_entry "
                        + "WHERE tenant_id = ?::uuid AND payee_id = ?::uuid "
                        + "AND component_name = 'commission'", TENANT, REP),
                "locked ledger untouched");
    }

    // ---------------------------------------------------------------- helpers
    private static final String MODEL_JSON = """
            {"planName":"E2E plan","components":[
              {"name":"attainment","kind":"FORMULA","outputType":"CURRENCY","formula":"$window_to"},
              {"name":"commission","kind":"TIER_TABLE","outputType":"CURRENCY",
               "dependsOn":["attainment"],
               "tiers":{"mode":"MARGINAL","basis":"AMOUNT","brackets":[
                 {"upTo":"100000","rate":"0.10"},
                 {"upTo":"150000","rate":"0.15"},
                 {"rate":"0.20"}]}},
              {"name":"team_commission","kind":"ROLLUP","outputType":"CURRENCY",
               "dependsOn":["commission"]}]}
            """;

    private void txn(Connection c, String externalId, String date, String amount)
            throws SQLException {
        String txId = UUID.nameUUIDFromBytes(("e2e-" + externalId).getBytes()).toString();
        update(c, "INSERT INTO raw_transaction (transaction_id, tenant_id, source_system, "
                        + "external_deal_id, transaction_date, booking_amount, currency_code, "
                        + "ingestion_batch_id) VALUES (CAST(? AS uuid),CAST(? AS uuid),'CSV',?,"
                        + "CAST(? AS date),CAST(? AS numeric),'USD',CAST(? AS uuid))",
                txId, TENANT, externalId, date, amount,
                "00000000-0000-0000-0000-0000000000bb");
        update(c, "INSERT INTO credit_allocation (credit_allocation_id, tenant_id, "
                        + "transaction_id, payee_id, split_pct, effective_date) "
                        + "VALUES (CAST(? AS uuid),CAST(? AS uuid),CAST(? AS uuid),"
                        + "CAST(? AS uuid),1,CAST(? AS date))",
                UUID.randomUUID().toString(), TENANT, txId, REP, date);
    }

    private void update(Connection c, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = bind(c.prepareStatement(sql), params)) {
            ps.execute(); // tolerates statements that also return rows
        }
    }

    private PreparedStatement bind(PreparedStatement ps, String... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setString(i + 1, params[i]);
        }
        return ps;
    }

    private String scalar(String sql, String... params) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = bind(c.prepareStatement(sql), params);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
