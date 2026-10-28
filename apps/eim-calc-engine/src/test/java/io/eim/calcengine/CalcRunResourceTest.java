package io.eim.calcengine;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class CalcRunResourceTest {

    @Test
    void acceptsRunRequest() {
        given()
                .contentType("application/json")
                .body("{\"tenantId\":\"t1\",\"payoutPeriodId\":\"2026-10\",\"planVersionHash\":\"sha256:abc\"}")
                .when()
                .post("/api/v1/calc/runs")
                .then()
                .statusCode(202)
                .body("accepted", is(true));
    }

    @Test
    void rejectsMissingHash() {
        given()
                .contentType("application/json")
                .body("{\"tenantId\":\"t1\",\"payoutPeriodId\":\"2026-10\",\"planVersionHash\":\"\"}")
                .when()
                .post("/api/v1/calc/runs")
                .then()
                .statusCode(400);
    }
}
