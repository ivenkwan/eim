package io.eim.connectors;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * TG2-ING-2/4: the Salesforce connector (OAuth password grant → SOQL →
 * canonical model) against WireMock, and the JDBC query connector
 * (Snowflake posture) against the live AGE postgres. Credentials are
 * placeholder strings supplied at call time — never literals with value.
 */
@QuarkusTest
@TestProfile(SfdcAndJdbcConnectorsTest.Profile.class)
class SfdcAndJdbcConnectorsTest {

    /** Points the sfdc REST client at the WireMock stub. */
    public static class Profile implements QuarkusTestProfile {
        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(SfdcWireMock.class));
        }
    }

    private static final UUID TENANT_ID = UUID.fromString("44444444-0000-0000-0000-000000000001");
    private static final String TENANT = TENANT_ID.toString();

    @Inject
    AgroalDataSource ds;

    private final UUID rep = UUID.randomUUID();

    @BeforeEach
    void seed() throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, role_title, effective_start_date) "
                             + "VALUES (?,?,?,?,?,?,current_date)")) {
            ps.setObject(1, rep);
            ps.setObject(2, TENANT_ID);
            ps.setString(3, "S-1");
            ps.setString(4, "Sam Sfdc");
            ps.setString(5, "ray@example.test");
            ps.setString(6, "AE");
            ps.executeUpdate();
        }
    }

    @AfterEach
    void cleanup() throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement credits = c.prepareStatement(
                     "DELETE FROM credit_allocation WHERE transaction_id IN "
                             + "(SELECT transaction_id FROM raw_transaction WHERE tenant_id = ?)");
             PreparedStatement txns = c.prepareStatement("DELETE FROM raw_transaction WHERE tenant_id = ?");
             PreparedStatement records = c.prepareStatement(
                     "DELETE FROM ingestion_record WHERE batch_id IN "
                             + "(SELECT ingestion_batch_id FROM ingestion_batch WHERE tenant_id = ?)");
             PreparedStatement batches = c.prepareStatement("DELETE FROM ingestion_batch WHERE tenant_id = ?");
             PreparedStatement payees = c.prepareStatement("DELETE FROM payee WHERE tenant_id = ?");
             PreparedStatement detach = c.prepareStatement("SELECT eim_ops.detach_delete_payee(?)")) {
            credits.setObject(1, TENANT_ID);
            credits.executeUpdate();
            txns.setObject(1, TENANT_ID);
            txns.executeUpdate();
            records.setObject(1, TENANT_ID);
            records.executeUpdate();
            batches.setObject(1, TENANT_ID);
            batches.executeUpdate();
            payees.setObject(1, TENANT_ID);
            payees.executeUpdate();
            detach.setObject(1, rep);
            detach.executeQuery();
        }
    }

    @Test
    @TestSecurity(user = "revops", roles = {"comp_admin"})
    void sfdcConnectorPullsMapsAndIngests() {
        var result = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "revops.sfdc").header("X-Roles", "comp_admin")
                .body(Map.of(
                        "client_id", "stub-client",
                        "client_secret", "stub-secret",
                        "username", "integration@example.test",
                        "password", "call-time-secret",
                        "soql", "SELECT Id, CloseDate, Amount FROM Opportunity",
                        "mapping", Map.of()))
                .when().post("/api/v1/ingestion/salesforce/run")
                .then().statusCode(202)
                .extract().jsonPath();
        assertEquals(1, (int) result.getInt("accepted"));
        assertEquals(0, (int) result.getInt("quarantined"));
        assertEquals("42000.0000", scalar("SELECT booking_amount::text FROM raw_transaction "
                + "WHERE source_system = 'SALESFORCE' AND tenant_id = ? AND external_deal_id = 'SF-1'", TENANT_ID));
    }

    @Test
    @TestSecurity(user = "revops", roles = {"comp_admin"})
    void jdbcConnectorIngestsFromQuery() {
        String query = "SELECT 'JDBC-1' AS external_deal_id, DATE '2026-04-05' AS close_date, "
                + "'9000' AS amount, 'USD' AS currency, 'ray@example.test' AS payee_email";
        var result = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "revops.jdbc").header("X-Roles", "comp_admin")
                .body(Map.of("query", query, "object_name", "warehouse_probe"))
                .when().post("/api/v1/ingestion/query/run")
                .then().statusCode(202)
                .extract().jsonPath();
        assertEquals(1, (int) result.getInt("accepted"));
        assertEquals("9000.0000", scalar("SELECT booking_amount::text FROM raw_transaction "
                + "WHERE source_system = 'SNOWFLAKE' AND tenant_id = ? AND external_deal_id = 'JDBC-1'", TENANT_ID));
    }

    @Test
    @TestSecurity(user = "revops", roles = {"comp_admin"})
    void hubspotConnectorPullsMapsAndIngests() {
        var result = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "revops.hs").header("X-Roles", "comp_admin")
                .body(Map.of("token", "call-time-private-app-token"))
                .when().post("/api/v1/ingestion/hubspot/run")
                .then().statusCode(202)
                .extract().jsonPath();
        assertEquals(1, (int) result.getInt("accepted"));
        assertEquals(0, (int) result.getInt("quarantined"));
        assertEquals("77000.0000", scalar("SELECT booking_amount::text FROM raw_transaction "
                + "WHERE source_system = 'HUBSPOT' AND tenant_id = ? AND external_deal_id = 'HS-1'", TENANT_ID));
    }

    @Test
    @TestSecurity(user = "revops", roles = {"comp_admin"})
    void jdbcConnectorRejectsNonSelect() {
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "revops.jdbc").header("X-Roles", "comp_admin")
                .body(Map.of("query", "DELETE FROM payee", "object_name", "evil"))
                .when().post("/api/v1/ingestion/query/run")
                .then().statusCode(400);
    }

    private String scalar(String sql, Object... params) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
