package io.eim.plancompiler;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG3-RUL-6 registry gates: publish appends an immutable artifact row keyed
 * by the canonical hash, re-publish is idempotent, versions coexist (a
 * changed plan gets a new hash; both rows readable), rows cannot be updated
 * or deleted (append-only trigger), and every publish carries a
 * PLAN_PUBLISH audit row.
 */
@QuarkusTest
class PlanResourceTest {

    private static final String TENANT = "55555555-0000-0000-0000-000000000001";

    @Inject
    AgroalDataSource ds;

    @AfterEach
    void cleanup() throws SQLException {
        // plan_artifact is append-only via trigger: drop the trigger, clean
        // test rows, restore — test isolation for the shared dev DB.
        try (Connection c = ds.getConnection();
             PreparedStatement t = c.prepareStatement(
                     "ALTER TABLE plan_artifact DISABLE TRIGGER trg_plan_artifact_append_only");
             PreparedStatement d = c.prepareStatement(
                     "DELETE FROM plan_artifact WHERE tenant_id = ?::uuid");
             PreparedStatement t2 = c.prepareStatement(
                     "ALTER TABLE plan_artifact ENABLE TRIGGER trg_plan_artifact_append_only")) {
            t.executeUpdate();
            d.setString(1, TENANT);
            d.executeUpdate();
            t2.executeUpdate();
        }
    }

    @Test
    @TestSecurity(user = "comp.admin", roles = {"comp_admin"})
    void publishIsIdempotentAndAudited() throws SQLException {
        String hash = publishCanonical();
        // Re-publish the same plan: same hash, no new row.
        assertEquals(hash, publishCanonical());

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT drl, helper_java, dmn FROM plan_artifact WHERE plan_version_hash = ?");
             PreparedStatement audits = c.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action_type = 'PLAN_PUBLISH' "
                             + "AND target_record_id = ?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getString(1).contains("query \"triageCategory\""));
                assertTrue(rs.getString(2).contains("class Calc"));
                assertTrue(rs.getString(3).contains("<decisionTable"));
            }
            audits.setString(1, hash);
            try (ResultSet rs = audits.executeQuery()) {
                rs.next();
                assertTrue(rs.getLong(1) >= 1, "PLAN_PUBLISH audit row exists");
            }
        }

        // Versions coexist: change a rate -> new hash, both fetchable.
        String changed = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "comp.admin").header("X-Roles", "comp_admin")
                .body(changedPlanJson())
                .when().post("/api/v1/plans/publish")
                .then().statusCode(202)
                .extract().jsonPath().getString("planVersionHash");
        assertTrue(!changed.equals(hash), "changed plan hashes differently");
        given().header("X-Tenant-Id", TENANT)
                .when().get("/api/v1/plans/" + hash + "/artifacts")
                .then().statusCode(200);
        given().header("X-Tenant-Id", TENANT)
                .when().get("/api/v1/plans/" + changed + "/artifacts")
                .then().statusCode(200);
        given().header("X-Tenant-Id", TENANT)
                .when().get("/api/v1/plans/deadbeef/artifacts")
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "comp.admin", roles = {"comp_admin"})
    void publishGateBlocksBrokenPlans() {
        Map<String, Object> bad = Map.of("planName", "bad",
                "components", List.of(Map.of(
                        "name", "a", "kind", "FORMULA", "outputType", "CURRENCY",
                        "formula", "@b + 1"),
                        Map.of("name", "b", "kind", "FORMULA", "outputType", "CURRENCY",
                                "formula", "@a + 1")));
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "comp.admin").header("X-Roles", "comp_admin")
                .body(bad)
                .when().post("/api/v1/plans/publish")
                .then().statusCode(409);

        var report = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "comp.admin").header("X-Roles", "comp_admin")
                .body(bad)
                .when().post("/api/v1/plans/validate")
                .then().statusCode(200).extract().jsonPath();
        assertNotNull(report.getMap("report"));
        assertTrue(report.getList("report.problems").toString().contains("cycle"),
                "cycle problem surfaced to the builder UI: "
                        + report.getList("report.problems"));
    }

    private String publishCanonical() {
        return given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "comp.admin").header("X-Roles", "comp_admin")
                .body(canonicalPlanJson())
                .when().post("/api/v1/plans/publish")
                .then().statusCode(202)
                .extract().jsonPath().getString("planVersionHash");
    }

    private static Map<String, Object> canonicalPlanJson() {
        return planJsonWithRate("0.20");
    }

    private static Map<String, Object> changedPlanJson() {
        return planJsonWithRate("0.25");
    }

    private static Map<String, Object> planJsonWithRate(String topRate) {
        return Map.of("planName", "Enterprise AE 2026",
                "components", List.of(
                        Map.of("name", "attainment", "kind", "FORMULA",
                                "outputType", "CURRENCY", "formula", "$window_to"),
                        Map.of("name", "commission", "kind", "TIER_TABLE",
                                "outputType", "CURRENCY",
                                "dependsOn", List.of("attainment"),
                                "tiers", Map.of("mode", "MARGINAL", "basis", "AMOUNT",
                                        "brackets", List.of(
                                                Map.of("upTo", "100000", "rate", "0.10"),
                                                Map.of("upTo", "150000", "rate", "0.15"),
                                                Map.of("rate", topRate))),
                                "protections", Map.of("capScope", "PERIOD",
                                        "capBasis", "AMOUNT", "capValue", "25000"))));
    }
}
