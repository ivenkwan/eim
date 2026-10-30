package io.eim.coreapi;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TG7-GOV-2 (FR-705.2): SLA timers per category, breach detection from
 * created_at, escalation routing to the category's escalation role with a
 * full audit trail; double escalation is refused.
 */
@QuarkusTest
class DisputeSlaTest {

    private static final String TENANT = "aaaaaaaa-1000-0000-0000-000000000001";
    private static final String PAYEE = "aaaaaaaa-1000-0000-0000-000000000002";
    private static final String BREACHED = "aaaaaaaa-1000-0000-0000-000000000003";
    private static final String FRESH = "aaaaaaaa-1000-0000-0000-000000000004";

    @Inject
    AgroalDataSource ds;

    @AfterEach
    void cleanup() throws SQLException {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM dispute_ticket WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
            st.execute("DELETE FROM payee WHERE tenant_id = CAST('" + TENANT + "' AS uuid)");
        }
    }

    @Test
    @TestSecurity(user = "sla.admin", roles = {"comp_admin"})
    void slaReportDetectsBreachAndEscalationRoutes() throws SQLException {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, "
                    + "role_title, effective_start_date) VALUES (CAST('" + PAYEE
                    + "' AS uuid),CAST('" + TENANT + "' AS uuid),'S-1','SLA Rep',"
                    + "'sla@example.test','AE',DATE '2026-01-01')");
            // a PLAN_LOGIC dispute created 3 days ago → 24h SLA breached
            st.execute("INSERT INTO dispute_ticket (dispute_id, tenant_id, payee_id, category, "
                    + "notes, status, created_at) VALUES (CAST('" + BREACHED + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),CAST('" + PAYEE + "' AS uuid),"
                    + "'PLAN_LOGIC','payout short','SUBMITTED', now() - interval '3 days')");
            // a fresh SOURCE_DATA dispute → 72h SLA intact
            st.execute("INSERT INTO dispute_ticket (dispute_id, tenant_id, payee_id, category, "
                    + "notes, status, created_at) VALUES (CAST('" + FRESH + "' AS uuid),"
                    + "CAST('" + TENANT + "' AS uuid),CAST('" + PAYEE + "' AS uuid),"
                    + "'SOURCE_DATA','late deal','SUBMITTED', now())");
        }

        var sla = given()
                .header("X-Tenant-Id", TENANT).header("X-Actor", "sla.admin")
                .header("X-Roles", "comp_admin")
                .when().get("/api/v1/disputes/sla")
                .then().statusCode(200)
                .body("open", org.hamcrest.Matchers.equalTo(2))
                .body("breached", org.hamcrest.Matchers.equalTo(1))
                .extract().jsonPath();
        assertEquals(24, (int) sla.getInt("rows[0].slaHours"), "PLAN_LOGIC carries the 24h SLA");

        // escalate the breached one → routed to finance_approver (PLAN_LOGIC)
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "sla.admin")
                .header("X-Roles", "comp_admin")
                .when().post("/api/v1/disputes/" + BREACHED + "/escalate")
                .then().statusCode(200)
                .body("escalatedTo", org.hamcrest.Matchers.equalTo("finance_approver"))
                .body("breached", org.hamcrest.Matchers.equalTo(true));

        // double escalation refused
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "sla.admin")
                .header("X-Roles", "comp_admin")
                .when().post("/api/v1/disputes/" + BREACHED + "/escalate")
                .then().statusCode(409);

    }

    @Test
    @TestSecurity(user = "rep", roles = {"payee"})
    void payeeCannotEscalate() {
        given().contentType("application/json")
                .header("X-Tenant-Id", TENANT).header("X-Actor", "rep")
                .header("X-Roles", "payee")
                .when().post("/api/v1/disputes/" + FRESH + "/escalate")
                .then().statusCode(403);
    }
}
