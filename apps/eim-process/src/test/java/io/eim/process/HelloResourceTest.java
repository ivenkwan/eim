package io.eim.process;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class HelloResourceTest {

    @Test
    void helloRespondsPerContract() {
        given()
                .when().get("/api/v1/hello")
                .then()
                .statusCode(200)
                .body("service", is("eim-process"))
                .body("status", is("ok"));
    }
}
