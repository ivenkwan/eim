package io.eim.coreapi;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG7-SEC-1: attachment malware scanning (EICAR vector + executable magics
 * + blocked extensions; clean files pass), GDPR erasure (pseudonymization
 * with immutable financials preserved), and the residency report.
 */
@QuarkusTest
class SecurityOpsTest {

    private static final String TENANT = "aaaaaaaa-2000-0000-0000-000000000001";
    private static final String PAYEE = "aaaaaaaa-2000-0000-0000-000000000002";
    private static final String PERIOD = "aaaaaaaa-2000-0000-0000-000000000003";

    @Inject
    AgroalDataSource ds;

    @AfterEach
    void cleanup() throws SQLException {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM commission_ledger_entry WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            st.execute("SELECT eim_ops.detach_delete_payee(CAST('" + PAYEE + "' AS uuid))");
            st.execute("DELETE FROM payee WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            st.execute("DELETE FROM payout_period WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
        }
    }

    private void seedPayeeWithLedger() throws SQLException {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, effective_start_date) VALUES (CAST('" + PAYEE
                    + "' AS uuid),CAST('" + TENANT + "' AS uuid),'E-1','Erasure Rep',"
                    + "'erase@example.test','AE',DATE '2026-01-01')");
            st.execute("INSERT INTO payout_period (payout_period_id, tenant_id, period_key, "
                    + "starts_on, ends_on, status) VALUES (CAST('" + PERIOD + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),'2027-erz','2027-01-01','2027-01-31','OPEN')");
            st.execute("SELECT eim_ops.ensure_period_partition(CAST('" + PERIOD + "' AS uuid),'2027-erz')");
            st.execute("INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                    + "payee_id, component_name, payout_amount, entry_type, calculation_trace, "
                    + "is_locked) VALUES (gen_random_uuid(),CAST('" + TENANT + "' AS uuid),"
                    + "CAST('" + PERIOD + "' AS uuid),CAST('" + PAYEE
                    + "' AS uuid),'commission',500,'CALCULATED',CAST('{}' AS jsonb),false)");
        }
    }

    @Test
    @TestSecurity(user = "sec.admin", roles = {"system_admin"})
    void malwareScanGate() {
        // EICAR test vector is rejected
        String eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "sec.admin")
                .header("X-Roles", "system_admin")
                .queryParam("filename", "proof.txt")
                .body(eicar.getBytes(StandardCharsets.ISO_8859_1))
                .when().post("/api/v1/security/attachments/scan")
                .then().statusCode(415)
                .body("findings", org.hamcrest.Matchers.containsString("EICAR-TEST-VECTOR"));

        // executables by magic and extension
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "sec.admin")
                .header("X-Roles", "system_admin")
                .queryParam("filename", "run.exe")
                .body(new byte[]{'M', 'Z', 0, 0})
                .when().post("/api/v1/security/attachments/scan")
                .then().statusCode(415);
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "sec.admin")
                .header("X-Roles", "system_admin")
                .queryParam("filename", "script.sh")
                .body("echo hi".getBytes())
                .when().post("/api/v1/security/attachments/scan")
                .then().statusCode(415);

        // a clean file passes
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "sec.admin")
                .header("X-Roles", "system_admin")
                .queryParam("filename", "receipt.pdf")
                .body("%PDF-1.7 clean".getBytes())
                .when().post("/api/v1/security/attachments/scan")
                .then().statusCode(200)
                .body("clean", org.hamcrest.Matchers.equalTo(true));
    }

    @Test
    @TestSecurity(user = "sec.admin", roles = {"system_admin"})
    void gdprErasurePseudonymizesAndPreservesLedger() throws SQLException {
        seedPayeeWithLedger();
        var resp = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "sec.admin")
                .header("X-Roles", "system_admin")
                .body(java.util.Map.of("payeeId", PAYEE, "reason", "Art. 17 request REQ-42"))
                .when().post("/api/v1/security/gdpr/erasure")
                .then().statusCode(202).extract().jsonPath();
        assertEquals(1L, resp.getLong("ledgerRowsPreserved"), "immutable financials survive");
        assertTrue(resp.getString("pseudonym").startsWith("ERASED-"));

        // identifying fields replaced; ledger rows intact
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT full_name, email FROM payee WHERE payee_id = CAST('"
                     + PAYEE + "' AS uuid)")) {
            rs.next();
            assertTrue(rs.getString(1).startsWith("ERASED-"), "name pseudonymized");
            assertTrue(rs.getString(2).endsWith("@erased.invalid"), "email pseudonymized");
        }
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM commission_ledger_entry WHERE payee_id = CAST('"
                     + PAYEE + "' AS uuid)")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "ledger row preserved (NFR-71 reconciliation)");
        }

        // reason is mandatory
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "sec.admin")
                .header("X-Roles", "system_admin")
                .body(java.util.Map.of("payeeId", PAYEE))
                .when().post("/api/v1/security/gdpr/erasure")
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "sec.admin", roles = {"system_admin"})
    void residencyReportReflectsDeployment() {
        given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "sec.admin")
                .header("X-Roles", "system_admin")
                .when().get("/api/v1/security/residency")
                .then().statusCode(200)
                .body("crossRegionReplication", org.hamcrest.Matchers.equalTo(false));
    }
}
