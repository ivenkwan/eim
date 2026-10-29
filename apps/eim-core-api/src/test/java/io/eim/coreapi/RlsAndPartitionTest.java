package io.eim.coreapi;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG1-DAT-2/3: RLS tenant + payee isolation and period-partition locking,
 * exercised as the RLS-bound eim_app role (FR-103, NFR-44/52). Every DML/DDL
 * statement below is a PreparedStatement with a constant SQL string; all
 * values bind through parameters — no concatenated SQL anywhere.
 */
@QuarkusTest
class RlsAndPartitionTest {

    private static final String INSERT_PAYEE =
            "INSERT INTO payee (payee_id, tenant_id, full_name, email, effective_start_date) "
                    + "VALUES (?,?,?,?,current_date)";
    private static final String INSERT_PERIOD =
            "INSERT INTO payout_period (payout_period_id, tenant_id, period_key, starts_on, ends_on) "
                    + "VALUES (?,?,?,?,?)";
    private static final String INSERT_LEDGER =
            "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                    + "payee_id, payout_amount, entry_type) VALUES (?,?,?,?,?,?)";
    private static final String ENSURE_PARTITION =
            "SELECT eim_ops.ensure_period_partition(?, ?)";
    private static final String LOCK_PARTITION =
            "SELECT eim_ops.lock_period_partition(?)";
    private static final String SET_SCOPE =
            "SELECT set_config('app.current_tenant_id', ?, true), "
                    + "set_config('app.current_payee_id', ?, true)";

    @Inject
    AgroalDataSource ds;

    private final List<UUID> tenants = new ArrayList<>();
    private final List<UUID> payees = new ArrayList<>();

    @BeforeEach
    void seed() throws SQLException {
        for (int i = 0; i < 2; i++) {
            UUID tenant = UUID.randomUUID();
            UUID payee = UUID.randomUUID();
            tenants.add(tenant);
            payees.add(payee);
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(INSERT_PAYEE)) {
                ps.setObject(1, payee);
                ps.setObject(2, tenant);
                ps.setString(3, "Payee " + i);
                ps.setString(4, "payee" + i + "@example.test");
                ps.executeUpdate();
            }
        }
    }

    @AfterEach
    void cleanup() throws SQLException {
        for (UUID p : payees) {
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement("DELETE FROM payee WHERE payee_id = ?")) {
                ps.setObject(1, p);
                ps.executeUpdate();
            }
        }
    }

    /** Transaction bound to eim_app with tenant/payee GUCs (RLS-enforced). */
    private Connection asApp(UUID tenantId, UUID payeeId) throws SQLException {
        Connection c = ds.getConnection();
        c.setAutoCommit(false);
        try (PreparedStatement role = c.prepareStatement("SET LOCAL ROLE eim_app");
             PreparedStatement s = c.prepareStatement(SET_SCOPE)) {
            role.executeUpdate();
            s.setString(1, tenantId.toString());
            s.setString(2, payeeId.toString());
            s.executeQuery();
        }
        return c;
    }

    private void insertLedger(Connection c, UUID tenant, UUID payee, UUID period, String amount)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT_LEDGER)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenant);
            ps.setObject(3, period);
            ps.setObject(4, payee);
            ps.setBigDecimal(5, new java.math.BigDecimal(amount));
            ps.setString(6, "CALCULATED");
            ps.executeUpdate();
        }
    }

    @Test
    void tenantIsolationHidesOtherTenants() throws SQLException {
        try (Connection a = asApp(tenants.get(0), payees.get(0));
             PreparedStatement ps = a.prepareStatement("SELECT count(*) FROM payee");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals(1, rs.getInt(1), "own tenant only (FR-103.1)");
        }
    }

    @Test
    void payeeScopeSeesOnlyOwnLedgerRow() throws SQLException {
        UUID period = UUID.randomUUID();
        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(INSERT_PERIOD)) {
            p.setObject(1, period);
            p.setObject(2, tenants.get(0));
            p.setString(3, "2026-98");
            p.setDate(4, java.sql.Date.valueOf("2026-08-01"));
            p.setDate(5, java.sql.Date.valueOf("2026-08-31"));
            p.executeUpdate();
        }
        for (int i = 0; i < 2; i++) {
            try (Connection c = ds.getConnection()) {
                insertLedger(c, tenants.get(i), payees.get(i), period, "1.0000");
            }
        }
        try (Connection other = asApp(tenants.get(1), payees.get(1));
             PreparedStatement ps = other.prepareStatement(
                     "SELECT payee_id::text FROM commission_ledger_entry");
             ResultSet rs = ps.executeQuery()) {
            List<String> seen = new ArrayList<>();
            while (rs.next()) {
                seen.add(rs.getString(1));
            }
            assertEquals(List.of(payees.get(1).toString()), seen, "RLS hides tenant-0 rows");
        }
        dropPeriodRows(period);
    }

    @Test
    void lockedPartitionRejectsAppWrites() throws SQLException {
        UUID period = UUID.randomUUID();
        String periodKey = "t" + System.nanoTime() % 100000000L;
        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(INSERT_PERIOD)) {
            p.setObject(1, period);
            p.setObject(2, tenants.get(0));
            p.setString(3, periodKey);
            p.setDate(4, java.sql.Date.valueOf("2026-09-01"));
            p.setDate(5, java.sql.Date.valueOf("2026-09-30"));
            p.executeUpdate();
        }
        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(ENSURE_PARTITION)) {
            p.setObject(1, period);
            p.setString(2, periodKey);
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next());
            }
        }

        // Pre-lock app insert into the dedicated partition succeeds.
        try (Connection app = asApp(tenants.get(0), payees.get(0))) {
            insertLedger(app, tenants.get(0), payees.get(0), period, "1.0000");
            app.commit();
        }

        try (Connection c = ds.getConnection();
             PreparedStatement p = c.prepareStatement(LOCK_PARTITION)) {
            p.setObject(1, period);
            p.executeQuery();
        }

        // Post-lock insert denied by privilege revocation (NFR-44).
        try (Connection app = asApp(tenants.get(0), payees.get(0));
             PreparedStatement ps = app.prepareStatement(INSERT_LEDGER)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenants.get(0));
            ps.setObject(3, period);
            ps.setObject(4, payees.get(0));
            ps.setBigDecimal(5, new java.math.BigDecimal("2.0000"));
            ps.setString(6, "CALCULATED");
            SQLException ex = assertThrows(SQLException.class, ps::executeUpdate);
            assertTrue(ex.getMessage().toLowerCase().contains("permission")
                    || ex.getMessage().toLowerCase().contains("locked"),
                    "denied post-lock write, got: " + ex.getMessage());
        }
        dropPeriodRows(period);
    }

    private void dropPeriodRows(UUID period) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement l = c.prepareStatement(
                     "DELETE FROM commission_ledger_entry WHERE payout_period_id = ?");
             PreparedStatement p = c.prepareStatement(
                     "DELETE FROM payout_period WHERE payout_period_id = ?")) {
            l.setObject(1, period);
            l.executeUpdate();
            p.setObject(1, period);
            p.executeUpdate();
        }
    }
}
