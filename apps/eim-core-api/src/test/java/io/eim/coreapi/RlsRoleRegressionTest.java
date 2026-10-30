package io.eim.coreapi;

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
import java.sql.Statement;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TG5-SEC-2 (NFR-52): RLS regression executed as distinct roles in CI.
 * Same fixture, three identities — the seller sees only their own rows,
 * a peer seller sees nothing of the first, and an admin-context reader
 * (RLS GUC unset) is the control that rows exist at all. Runs on every
 * build so a policy regression cannot ship.
 */
@QuarkusTest
class RlsRoleRegressionTest {

    private static final String TENANT = "6a6a6a6a-f000-0000-0000-000000000001";
    private static final String ALICE = "6a6a6a6a-f000-0000-0000-000000000002";
    private static final String BOB = "6a6a6a6a-f000-0000-0000-000000000003";
    private static final String PERIOD = "6a6a6a6a-f000-0000-0000-000000000004";

    @Inject
    AgroalDataSource ds;

    @BeforeEach
    void seed() throws SQLException {
        clean();
        try (Connection c = ds.getConnection()) {
            sql(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, effective_start_date) VALUES (CAST('" + ALICE
                    + "' AS uuid),CAST('" + TENANT + "' AS uuid),'R-A','Alice Seller',"
                    + "'alice@example.test','AE',DATE '2026-01-01')");
            sql(c, "INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, effective_start_date) VALUES (CAST('" + BOB
                    + "' AS uuid),CAST('" + TENANT + "' AS uuid),'R-B','Bob Seller',"
                    + "'bob@example.test','AE',DATE '2026-01-01')");
            sql(c, "INSERT INTO payout_period (payout_period_id, tenant_id, period_key, "
                    + "starts_on, ends_on, status) VALUES (CAST('" + PERIOD + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),'2026-rls','2026-04-01','2026-04-30','OPEN')");
            sql(c, "INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, "
                    + "payee_id, component_name, payout_amount, entry_type, calculation_trace, "
                    + "is_locked) VALUES (gen_random_uuid(),CAST('" + TENANT + "' AS uuid),"
                    + "CAST('" + PERIOD + "' AS uuid),CAST('" + ALICE
                    + "' AS uuid),'commission',1234,'CALCULATED',CAST('{}' AS jsonb),false)");
        }
    }

    @AfterEach
    void clean() throws SQLException {
        try (Connection c = ds.getConnection()) {
            sql(c, "DELETE FROM commission_ledger_entry WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "DELETE FROM payout_period WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            sql(c, "SELECT eim_ops.detach_delete_payee(CAST('" + ALICE + "' AS uuid))");
            sql(c, "SELECT eim_ops.detach_delete_payee(CAST('" + BOB + "' AS uuid))");
            sql(c, "DELETE FROM payee WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
        }
    }

    private void sql(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    @TestSecurity(user = "alice", roles = {"payee"})
    void aliceSeesOnlyHerOwnStatement() {
        var overview = given()
                .header("X-Tenant-Id", TENANT).header("X-Payee-Id", ALICE)
                .header("X-Actor", "alice").header("X-Roles", "payee")
                .when().get("/api/v1/me/statement")
                .then().statusCode(200)
                .extract().jsonPath();
        assertEquals("1234.0000", overview.getString("totalPayout"),
                "seller sees her own ledger row through RLS");
    }

    @Test
    @TestSecurity(user = "bob", roles = {"payee"})
    void bobSeesNothingOfAlice() {
        var overview = given()
                .header("X-Tenant-Id", TENANT).header("X-Payee-Id", BOB)
                .header("X-Actor", "bob").header("X-Roles", "payee")
                .when().get("/api/v1/me/statement")
                .then().statusCode(200)
                .extract().jsonPath();
        assertEquals("0", overview.getString("totalPayout"),
                "peer seller isolated at the data layer (BOLA)");
    }

    @Test
    @TestSecurity(user = "alice", roles = {"payee"})
    void foreignTraceIs404ForTheOwner() {
        String foreignLedger = scalar("SELECT ledger_id::text FROM commission_ledger_entry "
                + "WHERE tenant_id = '" + TENANT + "' LIMIT 1");
        given()
                .header("X-Tenant-Id", TENANT).header("X-Payee-Id", BOB)
                .header("X-Actor", "bob").header("X-Roles", "payee")
                .when().get("/api/v1/me/statement/trace/" + foreignLedger)
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"system_admin"})
    void controlRowsExistAboveThePolicies() {
        // control: the fixture row exists — proves the isolation above is
        // RLS, not a missing fixture
        assertEquals("1", scalar("SELECT count(*) FROM commission_ledger_entry "
                + "WHERE tenant_id = '" + TENANT + "'"));
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
}
