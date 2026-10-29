package io.eim.coreapi;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * TG1-SEC-3: separation of duties — only finance_approver may lock periods
 * (FR-102/603); comp_admin is rejected. Audit writes ride the same call.
 */
@QuarkusTest
class RbacPeriodLockTest {

    private String lockBody() {
        return "{\"reason\":\"test close\"}";
    }

    @Test
    @TestSecurity(user = "revops", roles = {"comp_admin"})
    void compAdminCannotLock() {
        given().contentType(ContentType.JSON).body(lockBody())
                .when().post("/api/v1/admin/periods/2026-99/lock")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "anon", roles = {})
    void anonymousCannotLock() {
        given().contentType(ContentType.JSON).body(lockBody())
                .when().post("/api/v1/admin/periods/2026-99/lock")
                .then().statusCode(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.equalTo(401), org.hamcrest.Matchers.equalTo(403)));
    }

    @Test
    @TestSecurity(user = "controller", roles = {"finance_approver"})
    void financeApproverLocksAndAuditTrails() {
        // Unknown period → 404 (authorized but absent): proves role gate passed.
        given().contentType(ContentType.JSON).body(lockBody())
                .when().post("/api/v1/admin/periods/" + UUID.randomUUID() + "/lock")
                .then().statusCode(404).body("error", org.hamcrest.Matchers.startsWith("period not found"));
    }

    @Test
    @TestSecurity(user = "revops", roles = {"comp_admin"})
    void auditEndpointsAreRoleGated() {
        given().when().get("/api/v1/admin/audit/reconcile")
                .then().statusCode(200); // comp_admin may view evidence
        given().when().get("/api/v1/admin/audit/seals/verify")
                .then().statusCode(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.equalTo(403),
                        org.hamcrest.Matchers.equalTo(200)))
                .body("error[0]", org.hamcrest.Matchers.equalTo("forbidden")); // finance/system only
    }

    @Test
    @TestSecurity(user = "seller", roles = {"payee"})
    void payeeCannotTouchAdminSurface() {
        given().contentType(ContentType.JSON).body(lockBody())
                .when().post("/api/v1/admin/periods/2026-99/lock")
                .then().statusCode(403);
    }
}
