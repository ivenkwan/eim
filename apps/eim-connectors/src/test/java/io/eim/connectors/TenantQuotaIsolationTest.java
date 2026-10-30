package io.eim.connectors;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG5-TST-4 (NFR-33): per-tenant connector isolation. A tenant configured
 * with a small per-batch row quota has its oversized warehouse burst
 * rejected up front, while another tenant's CRM-sized batch ingests
 * normally in the same shared runtime — fan-out cannot starve neighbors.
 */
@QuarkusTest
class TenantQuotaIsolationTest {

    private static final String BURST = "eeeeeeee-e000-0000-0000-000000000001";
    private static final String QUIET = "eeeeeeee-e000-0000-0000-000000000002";

    @Inject
    AgroalDataSource ds;

    @BeforeEach
    void seed() throws SQLException {
        clean();
        try (Connection c = ds.getConnection()) {
            // registry: burst tenant capped at 3 rows/batch (test posture);
            // quiet tenant uses the default quota
            sql(c, "INSERT INTO configuration (configuration_id, tenant_id, entity, version, "
                    + "payload, governed, status, created_by) VALUES (gen_random_uuid(),"
                    + "CAST('" + BURST + "' AS uuid),'connector.tenant_quota_rows',1,"
                    + "CAST('{\"rows\":3}' AS jsonb),false,'ACTIVE','quota.test')");
        }
    }

    @AfterEach
    void clean() throws SQLException {
        try (Connection c = ds.getConnection()) {
            sql(c, "DELETE FROM configuration WHERE entity = 'connector.tenant_quota_rows'");
            for (String tenant : new String[]{BURST, QUIET}) {
                sql(c, "DELETE FROM credit_allocation WHERE transaction_id IN "
                        + "(SELECT transaction_id FROM raw_transaction WHERE tenant_id = CAST('"
                        + tenant + "' AS uuid))");
                sql(c, "DELETE FROM raw_transaction WHERE tenant_id = CAST('" + tenant + "' AS uuid)");
                sql(c, "DELETE FROM ingestion_record WHERE batch_id IN "
                        + "(SELECT ingestion_batch_id FROM ingestion_batch WHERE tenant_id = CAST('"
                        + tenant + "' AS uuid))");
                sql(c, "DELETE FROM ingestion_batch WHERE tenant_id = CAST('" + tenant + "' AS uuid)");
                sql(c, "DELETE FROM payee WHERE tenant_id = CAST('" + tenant + "' AS uuid)");
            }
        }
    }

    private void sql(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    @TestSecurity(user = "revops", roles = {"comp_admin"})
    void burstTenantRejectedQuietTenantProceeds() throws SQLException {
        seedPayee(QUIET);
        seedPayee(BURST);

        // burst tenant: 5-row warehouse batch over the 3-row quota → 400
        var burst = given().contentType("application/json")
                .header("X-Tenant-Id", BURST).header("X-Actor", "burst")
                .header("X-Roles", "comp_admin")
                .body(Map.of("objectName", "warehouse_burst", "csv", csv(5, "quota.000000000001")))
                .when().post("/api/v1/ingestion/csv")
                .then().extract().response();
        assertEquals(400, burst.getStatusCode(), "quota rejection: "
                + burst.getBody().asString());
        assertTrue(burst.getBody().asString().contains("NFR-33"),
                "error names the quota policy");

        // quiet tenant: same runtime, normal-size batch → 202 with rows in
        var quiet = given().contentType("application/json")
                .header("X-Tenant-Id", QUIET).header("X-Actor", "quiet")
                .header("X-Roles", "comp_admin")
                .body(Map.of("objectName", "crm_sync", "csv", csv(2, "quota.000000000002")))
                .when().post("/api/v1/ingestion/csv")
                .then().statusCode(202)
                .extract().jsonPath();
        assertEquals(2, (int) quiet.getInt("ingested"), "neighbor tenant unaffected");

        // the rejected batch left no partial state
        assertEquals("0", scalar("SELECT count(*) FROM raw_transaction WHERE tenant_id = '"
                + BURST + "'"), "no partial rows from the rejected burst");
    }

    private void seedPayee(String tenant) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                             + "role_title, effective_start_date) VALUES (?,?,?,?,?,?,'2026-01-01')")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, UUID.fromString(tenant));
            ps.setString(3, "Q-" + tenant.substring(0, 4));
            ps.setString(4, "Quota Rep");
            ps.setString(5, "quota." + tenant.substring(24, 36) + "@example.test");
            ps.setString(6, "AE");
            ps.executeUpdate();
        }
    }

    private static String csv(int rows, String tag) {
        StringBuilder sb = new StringBuilder(
                "deal_id,close_date,amount,currency,payee_email\n");
        for (int i = 0; i < rows; i++) {
            sb.append(tag).append("-").append(i).append(",2026-04-1").append(i % 9)
                    .append(",1000,USD,").append(tag).append("@example.test\n");
        }
        return sb.toString();
    }

    private String scalar(String sql) {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
