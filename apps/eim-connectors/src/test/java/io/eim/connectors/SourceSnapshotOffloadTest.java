package io.eim.connectors;

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
import java.sql.Statement;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG9-C1 (FR-203.3): raw source snapshots offload to the objectstore
 * before parsing. The ingest path must NEVER block on the offload — an
 * unreachable store degrades to a warning and the DB payload remains the
 * source of truth; the object key is recorded on the batch only after a
 * successful PUT.
 */
@QuarkusTest
class SourceSnapshotOffloadTest {

    private static final String TENANT = "aaaaaaaa-a000-0000-0000-000000000001";

    @Inject
    AgroalDataSource ds;

    @AfterEach
    void cleanup() throws SQLException {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement()) {
            // audit_log is append-only (NFR-60): seeded rows are left
            // behind under this throwaway tenant; only mutable rows go
            st.execute("DELETE FROM transactional_outbox WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            st.execute("DELETE FROM ingestion_record WHERE batch_id IN "
                    + "(SELECT ingestion_batch_id FROM ingestion_batch WHERE tenant_id = CAST('"
                    + TENANT + "' AS uuid))");
            st.execute("DELETE FROM credit_allocation WHERE transaction_id IN "
                    + "(SELECT transaction_id FROM raw_transaction WHERE tenant_id = CAST('"
                    + TENANT + "' AS uuid))");
            st.execute("DELETE FROM raw_transaction WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            st.execute("DELETE FROM ingestion_batch WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            st.execute("SELECT eim_ops.detach_delete_payee(p.payee_id) FROM payee p "
                    + "WHERE p.tenant_id = CAST('" + TENANT + "' AS uuid)");
            st.execute("DELETE FROM payee WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
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
    @TestSecurity(user = "snap.admin", roles = {"comp_admin"})
    void disabledByDefaultLeavesKeyNullAndIngestWorks() {
        seedPayee();
        var resp = given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "snap.admin")
                .header("X-Roles", "comp_admin")
                .body(Map.of("objectName", "snap_off", "csv",
                        "deal_id,close_date,amount,currency,payee_email\n"
                                + "SNAP-2,2026-04-02,20000,USD,snap@example.test"))
                .when().post("/api/v1/ingestion/csv")
                .then().extract().response();
        assertEquals(202, resp.getStatusCode());
        String key = scalar("SELECT snapshot_object_key FROM ingestion_batch "
                + "WHERE tenant_id = CAST('" + TENANT + "' AS uuid) "
                + "AND object_name = 'snap_off'");
        assertTrue(key == null || key.isEmpty(), "disabled → no key: " + key);
    }

    private void seedPayee() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                             + "role_title, effective_start_date) VALUES "
                             + "(gen_random_uuid(),CAST(? AS uuid),'SNAP','Snap Rep',"
                             + "'snap@example.test','AE',DATE '2026-01-01') "
                             + "ON CONFLICT DO NOTHING")) {
            ps.setString(1, TENANT);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
