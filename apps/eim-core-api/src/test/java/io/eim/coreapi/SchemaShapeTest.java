package io.eim.coreapi;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG1-DAT-1: the FR §12 schema is enforced by test — column types are
 * asserted against information_schema on a REAL PostgreSQL (AGE image).
 */
@QuarkusTest
class SchemaShapeTest {

    @Inject
    AgroalDataSource ds;

    private Map<String, String> columns(String table) throws SQLException {
        Map<String, String> out = new HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT column_name, data_type FROM information_schema.columns "
                             + "WHERE table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return out;
    }

    private String numericPrecision(String table, String column) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT numeric_precision || ',' || numeric_scale FROM information_schema.columns "
                             + "WHERE table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    @Test
    void moneyColumnsAreNumeric184() throws SQLException {
        assertEquals("18,4", numericPrecision("raw_transaction", "booking_amount"));
        assertEquals("18,4", numericPrecision("plan_assignment", "quota_amount"));
        assertEquals("18,4", numericPrecision("commission_ledger_entry", "payout_amount"));
        assertEquals("12,6", numericPrecision("raw_transaction", "fx_rate_applied"));
        assertEquals("8,6", numericPrecision("commission_ledger_entry", "rate_applied"));
        assertEquals("5,4", numericPrecision("plan_assignment", "proration_factor"));
    }

    @Test
    void coreTablesExistWithKeyColumns() throws SQLException {
        var payee = columns("payee");
        assertTrue(payee.containsKey("payee_id"));
        assertTrue(payee.containsKey("manager_payee_id"));
        assertTrue(payee.containsKey("effective_end_date"), "SCD2 effective dating (FR-302)");
        assertEquals("uuid", payee.get("payee_id"));

        var ledger = columns("commission_ledger_entry");
        assertTrue(ledger.containsKey("calculation_trace"));
        assertEquals("jsonb", ledger.get("calculation_trace"));
        assertTrue(ledger.containsKey("entry_type"));

        var txn = columns("raw_transaction");
        assertEquals("jsonb", txn.get("custom_attributes"));
        assertEquals("character", txn.get("currency_code"));

        for (String table : new String[]{"comp_plan", "plan_version", "plan_assignment",
                "payout_period", "credit_allocation", "dispute_ticket",
                "audit_log", "event_audit_log", "configuration",
                "configuration_change", "transactional_outbox", "audit_seals"}) {
            assertTrue(!columns(table).isEmpty(), table + " must exist");
        }
    }

    @Test
    void ledgerIsPartitionedByPeriod() throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM pg_partition_tree('commission_ledger_entry'::regclass) "
                             + "WHERE isleaf");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertTrue(rs.getInt(1) >= 1, "default partition exists");
        }
    }

    @Test
    void ageGraphPresent() throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM ag_catalog.ag_graph WHERE name = 'eim_graph'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM ag_catalog.ag_label WHERE graph = ("
                             + "SELECT graphid FROM ag_catalog.ag_graph WHERE name='eim_graph') "
                             + "AND name = 'Payee'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertTrue(rs.getInt(1) >= 1, "Payee vertex label exists");
        }
    }
}
