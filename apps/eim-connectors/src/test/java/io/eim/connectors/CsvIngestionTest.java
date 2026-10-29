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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG2 exit criteria #1: two consecutive clean CSV syncs into the canonical
 * model with quarantine, watermarking, late-arriving true-up signals, and a
 * complete audit trail per batch (US 1.1/1.2, FR-201..205, FR-604).
 */
@QuarkusTest
class CsvIngestionTest {

    private static final UUID TENANT_ID = UUID.fromString("22222222-0000-0000-0000-000000000001");
    private static final String TENANT = TENANT_ID.toString();

    private static final String CSV = "deal_id,close_date,amount,currency,payee_email,split_pct,product\n"
            + "D-001,2026-03-10,\"120,000.50\",USD,ray@example.test,0.7,Widget\n"
            + "D-002,2026-01-15,50000,USD,ray@example.test,,Gadget\n"
            + "D-003,not-a-date,999,USD,ray@example.test,,Bad\n"
            + "D-004,2026-03-12,7500,EUR,,,Gizmo\n";

    @Inject
    AgroalDataSource ds;

    private final UUID manager = UUID.randomUUID();
    private final UUID rep = UUID.randomUUID();
    private final UUID lockedPeriod = UUID.randomUUID();

    @BeforeEach
    void seed() throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement m = c.prepareStatement(
                     "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, role_title, effective_start_date) "
                             + "VALUES (?,?,?,?,?,?,current_date)");
             PreparedStatement r = c.prepareStatement(
                     "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, role_title, manager_payee_id, effective_start_date) "
                             + "VALUES (?,?,?,?,?,?,?,current_date)");
             PreparedStatement p = c.prepareStatement(
                     "INSERT INTO payout_period (payout_period_id, tenant_id, period_key, starts_on, ends_on, status) "
                             + "VALUES (?,?,?,?,?,'LOCKED')")) {
            m.setObject(1, manager);
            m.setObject(2, TENANT_ID);
            m.setString(3, "M-1");
            m.setString(4, "Kara Manager");
            m.setString(5, "kara@example.test");
            m.setString(6, "Manager");
            m.executeUpdate();

            r.setObject(1, rep);
            r.setObject(2, TENANT_ID);
            r.setString(3, "R-1");
            r.setString(4, "Ray Rep");
            r.setString(5, "ray@example.test");
            r.setString(6, "AE");
            r.setObject(7, manager);
            r.executeUpdate();

            p.setObject(1, lockedPeriod);
            p.setObject(2, TENANT_ID);
            p.setString(3, "2026-01");
            p.setDate(4, java.sql.Date.valueOf("2026-01-01"));
            p.setDate(5, java.sql.Date.valueOf("2026-01-31"));
            p.executeUpdate();
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
             PreparedStatement state = c.prepareStatement("DELETE FROM connector_state WHERE connector_name = 'CSV'");
             PreparedStatement payees = c.prepareStatement("DELETE FROM payee WHERE tenant_id = ?");
             PreparedStatement periods = c.prepareStatement("DELETE FROM payout_period WHERE tenant_id = ?");
             PreparedStatement trueups = c.prepareStatement(
                     "DELETE FROM transactional_outbox WHERE aggregate_type = 'trueup.required' AND tenant_id = ?");
             PreparedStatement detachA = c.prepareStatement("SELECT eim_ops.detach_delete_payee(?)");
             PreparedStatement detachB = c.prepareStatement("SELECT eim_ops.detach_delete_payee(?)")) {
            // audit_log is append-only (NFR-60) — assertions scope per batch
            // instead of deleting rows here.
            for (PreparedStatement ps : new PreparedStatement[]{credits, txns, records, batches, state,
                    payees, periods, trueups}) {
                if (ps != state) {
                    ps.setObject(1, TENANT_ID);
                }
                ps.executeUpdate();
            }
            detachA.setObject(1, manager);
            detachA.executeQuery();
            detachB.setObject(1, rep);
            detachB.executeQuery();
        }
    }

    @Test
    @TestSecurity(user = "revops", roles = {"comp_admin"})
    void csvBatchIngestsQuarantinesAndSignalsTrueup() {
        var accepted = ingestCsv("manual_upload");

        assertEquals(4, accepted.getInt("total"), "all four rows processed");
        assertEquals(3, accepted.getInt("ingested"), "three valid rows ingested");
        assertEquals(1, accepted.getInt("quarantined"), "bad date quarantined");
        assertEquals(1, accepted.getInt("lateArriving"), "January row hits the LOCKED period");

        assertEquals(3L, count("SELECT count(*) FROM raw_transaction WHERE tenant_id = ?", TENANT_ID));
        assertEquals(2L, count("SELECT count(*) FROM credit_allocation ca JOIN raw_transaction t "
                + "ON t.transaction_id = ca.transaction_id WHERE t.tenant_id = ?", TENANT_ID));
        assertEquals("120000.5000", scalar("SELECT booking_amount::text FROM raw_transaction "
                + "WHERE external_deal_id = 'D-001' AND tenant_id = ?", TENANT_ID));

        String errors = scalar("SELECT errors::text FROM ingestion_record "
                + "WHERE external_id = 'D-003' AND status = 'QUARANTINED'");
        assertTrue(errors.contains("transaction_date"), "per-field quarantine reason present: " + errors);

        assertEquals(1L, count("SELECT count(*) FROM transactional_outbox "
                + "WHERE aggregate_type = 'trueup.required' AND payload::text LIKE '%D-002%' AND tenant_id = ?", TENANT_ID));
        // audit_log is append-only (NFR-60): scope to THIS batch instead of
        // cleaning rows, and read the jsonb field rather than its rendering.
        assertEquals(1L, count("SELECT count(*) FROM audit_log WHERE action_type = 'CONNECTOR_RUN' "
                + "AND target_entity = 'ingestion_batch' AND target_record_id = ? "
                + "AND after_state->>'accepted' = '3'",
                accepted.getString("batchId")));

        var second = ingestCsv("manual_upload");
        assertEquals(3, second.getInt("ingested"));
        assertEquals("D-004", second.getString("watermarkAfter"));
    }

    @Test
    @TestSecurity(user = "revops", roles = {"comp_admin"})
    void watermarkStatePersisted() {
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "wm").header("X-Roles", "comp_admin")
                .body(Map.of("objectName", "wm_test", "csv",
                        "deal_id,close_date,amount,currency\nZ-9,2026-03-01,1,USD"))
                .when().post("/api/v1/ingestion/csv").then().statusCode(202);
        assertEquals("Z-9", scalar("SELECT watermark_value FROM connector_state "
                + "WHERE connector_name = 'CSV' AND object_name = 'wm_test'"));
    }

    private io.restassured.path.json.JsonPath ingestCsv(String objectName) {
        return given().contentType("application/json")
                .header("X-Tenant-Id", TENANT)
                .header("X-Actor", "revops.csv")
                .header("X-Roles", "comp_admin")
                .body(Map.of("objectName", objectName, "csv", CSV))
                .when().post("/api/v1/ingestion/csv")
                .then().statusCode(202)
                .extract().jsonPath();
    }

    // ---------------------------------------------------------------- helpers
    private long count(String sql, Object... params) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String scalar(String sql, Object... params) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
