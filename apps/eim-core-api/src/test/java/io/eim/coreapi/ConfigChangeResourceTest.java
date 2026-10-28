package io.eim.coreapi;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class ConfigChangeResourceTest {

    @Test
    void acceptsValidConfigChange() {
        given()
                .contentType("application/json")
                .body("{\"entity\":\"connector.salesforce\",\"payload\":\"{}\",\"reason\":\"rotate mapping\"}")
                .when()
                .post("/api/v1/admin/config-change")
                .then()
                .statusCode(202)
                .body("accepted", is(true));
    }

    @Test
    void rejectsMissingReason() {
        given()
                .contentType("application/json")
                .body("{\"entity\":\"connector.salesforce\",\"reason\":\"\"}")
                .when()
                .post("/api/v1/admin/config-change")
                .then()
                .statusCode(400);
    }
}
