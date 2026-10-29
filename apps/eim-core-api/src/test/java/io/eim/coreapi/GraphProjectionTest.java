package io.eim.coreapi;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TG1-DAT-4: payee writes project into the AGE graph (MANAGES_OF) and the
 * reconciler detects drift (AD-3, NFR-93). The relational table stays the
 * source of truth; the graph is a maintained projection.
 */
@QuarkusTest
class GraphProjectionTest {

    @Inject
    AgroalDataSource ds;

    @org.junit.jupiter.api.BeforeEach
    void cleanSlate() throws SQLException {
        try (Connection c = ds.getConnection();
             java.sql.Statement st = c.createStatement()) {
            st.execute("DELETE FROM commission_ledger_entry");
            // TG-4 governance rows reference payout_period/payee — clear them
            // first so the shared-DB clean-slate stays valid
            st.execute("DELETE FROM trueup_review");
            st.execute("DELETE FROM manual_override");
            st.execute("DELETE FROM export_run");
            st.execute("DELETE FROM payout_period");
            st.execute("DELETE FROM plan_assignment");
            st.execute("DELETE FROM payee");
            st.execute("LOAD 'age'");
            st.execute("SET search_path TO public, ag_catalog");
            st.execute("SELECT * FROM ag_catalog.cypher('eim_graph', "
                    + "$cy$ MATCH (n:Payee) DETACH DELETE n $cy$) AS (r ag_catalog.agtype)");
        }
    }

    private UUID insertPayee(UUID tenant, UUID payee, UUID manager, String name) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO payee (payee_id, tenant_id, full_name, email, manager_payee_id, "
                             + "effective_start_date) VALUES (?,?,?,?,?,current_date)")) {
            ps.setObject(1, payee);
            ps.setObject(2, tenant);
            ps.setString(3, name);
            ps.setString(4, name.toLowerCase().replace(' ', '.') + "@example.test");
            ps.setObject(5, manager);
            ps.executeUpdate();
        }
        return payee;
    }

    private void deletePayee(UUID payee) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM payee WHERE payee_id = ?")) {
            ps.setObject(1, payee);
            ps.executeUpdate();
        }
    }

    private boolean payeeInGraph(UUID payee) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT eim_ops.payee_in_graph(?)")) {
            ps.setObject(1, payee);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    @Test
    void projectionWritesAndReconcileDetectsDrift() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID manager = UUID.randomUUID();
        UUID report = UUID.randomUUID();
        insertPayee(tenant, manager, null, "Graph Manager");
        insertPayee(tenant, report, manager, "Graph Report");

        // Projection ran in-transaction (trigger): reconciler must be clean.
        assertTrue(payeeInGraph(manager) && payeeInGraph(report),
                "trigger projected both payees into the graph");
        assertEquals(0, driftCount(), "trigger projection keeps graph in sync");

        // Simulate out-of-band graph mutation (drift) via the ops helper,
        // then assert the reconciler flags it.
        try (Connection c = ds.getConnection();
             PreparedStatement wipe = c.prepareStatement("SELECT eim_ops.detach_delete_payee(?)")) {
            wipe.setObject(1, report);
            wipe.executeQuery();
        }
        assertTrue(driftCount() >= 1, "drift detected after out-of-band graph delete");

        deletePayee(report);
        deletePayee(manager);
        // Cascading delete of rows leaves graph vertices orphaned — reconciler
        // flags; re-project via reinsert to clean (documented ops flow).
        assertTrue(driftCount() >= 1, "orphan vertices remain flagged until re-projected");
    }

    private int driftCount() throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM eim_ops.reconcile_graph()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
