package io.eim.coreapi;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ConfigChangeResourceTest {

  private static final String PATH = "/api/v1/admin/config-change";
  private static final String TENANT = "11111111-1111-1111-1111-111111111111";

  @InjectMock ConfigChangeService service;

  private final AtomicReference<AuditContext> lastContext = new AtomicReference<>();

  @BeforeEach
  void stubService() {
    when(service.submit(any(), any()))
        .thenAnswer(
            invocation -> {
              AuditContext context = invocation.getArgument(1);
              lastContext.set(context);
              return new ConfigChangeService.Accepted(
                  true, UUID.randomUUID(), context.correlationId());
            });
  }

  private static String body(String payload) {
    return "{\"entity\":\"plan\",\"reason\":\"adjust\",\"payload\":" + payload + "}";
  }

  private static String quoted(int length) {
    StringBuilder builder = new StringBuilder(length + 2).append('"');
    for (int i = 0; i < length; i++) {
      builder.append('a');
    }
    return builder.append('"').toString();
  }

  @Test
  void validRequestReturns202AndEchoesCorrelation() {
    String correlation = "22222222-2222-2222-2222-222222222222";
    given()
        .header("X-Tenant-Id", TENANT)
        .header("X-Actor-Id", "actor-1")
        .header("X-Correlation-Id", correlation)
        .contentType(ContentType.JSON)
        .body(body("{\"k\":1}"))
        .when()
        .post(PATH)
        .then()
        .statusCode(202)
        .header("X-Correlation-Id", equalTo(correlation))
        .body("accepted", equalTo(true))
        .body("requestId", notNullValue());
  }

  @Test
  void generatesCorrelationWhenAbsent() {
    given()
        .header("X-Tenant-Id", TENANT)
        .header("X-Actor-Id", "actor-1")
        .contentType(ContentType.JSON)
        .body(body("{\"k\":1}"))
        .when()
        .post(PATH)
        .then()
        .statusCode(202)
        .header("X-Correlation-Id", notNullValue());
  }

  @Test
  void defaultsActorTypeToUser() {
    given()
        .header("X-Tenant-Id", TENANT)
        .header("X-Actor-Id", "actor-1")
        .contentType(ContentType.JSON)
        .body(body("{\"k\":1}"))
        .when()
        .post(PATH)
        .then()
        .statusCode(202);
    org.junit.jupiter.api.Assertions.assertEquals(ActorType.USER, lastContext.get().actorType());
  }

  @Test
  void missingTenantReturns400() {
    given()
        .header("X-Actor-Id", "actor-1")
        .contentType(ContentType.JSON)
        .body(body("{\"k\":1}"))
        .when()
        .post(PATH)
        .then()
        .statusCode(400);
  }

  @Test
  void invalidTenantReturns400() {
    given()
        .header("X-Tenant-Id", "not-a-uuid")
        .header("X-Actor-Id", "actor-1")
        .contentType(ContentType.JSON)
        .body(body("{\"k\":1}"))
        .when()
        .post(PATH)
        .then()
        .statusCode(400);
  }

  @Test
  void missingActorReturns400() {
    given()
        .header("X-Tenant-Id", TENANT)
        .contentType(ContentType.JSON)
        .body(body("{\"k\":1}"))
        .when()
        .post(PATH)
        .then()
        .statusCode(400);
  }

  @Test
  void invalidActorTypeReturns400() {
    given()
        .header("X-Tenant-Id", TENANT)
        .header("X-Actor-Id", "actor-1")
        .header("X-Actor-Type", "ROBOT")
        .contentType(ContentType.JSON)
        .body(body("{\"k\":1}"))
        .when()
        .post(PATH)
        .then()
        .statusCode(400);
  }

  @Test
  void invalidJsonPayloadReturns400() {
    given()
        .header("X-Tenant-Id", TENANT)
        .header("X-Actor-Id", "actor-1")
        .contentType(ContentType.JSON)
        .body("{\"entity\":\"plan\",\"reason\":\"r\",\"payload\":not-json}")
        .when()
        .post(PATH)
        .then()
        .statusCode(400);
  }

  @Test
  void missingReasonReturns400() {
    given()
        .header("X-Tenant-Id", TENANT)
        .header("X-Actor-Id", "actor-1")
        .contentType(ContentType.JSON)
        .body("{\"entity\":\"plan\",\"payload\":{\"k\":1}}")
        .when()
        .post(PATH)
        .then()
        .statusCode(400);
  }

  @Test
  void payloadAtBoundaryAccepted() {
    given()
        .header("X-Tenant-Id", TENANT)
        .header("X-Actor-Id", "actor-1")
        .contentType(ContentType.JSON)
        .body(body(quoted(65536)))
        .when()
        .post(PATH)
        .then()
        .statusCode(202);
  }

  @Test
  void payloadOverBoundaryReturns413() {
    given()
        .header("X-Tenant-Id", TENANT)
        .header("X-Actor-Id", "actor-1")
        .contentType(ContentType.JSON)
        .body(body(quoted(65537)))
        .when()
        .post(PATH)
        .then()
        .statusCode(413);
  }

  @Test
  void persistenceFailureReturns500WithoutSqlDetail() {
    when(service.submit(any(), any()))
        .thenThrow(new ConfigChangePersistenceException("boom", new RuntimeException("select secret from t")));
    given()
        .header("X-Tenant-Id", TENANT)
        .header("X-Actor-Id", "actor-1")
        .contentType(ContentType.JSON)
        .body(body("{\"k\":1}"))
        .when()
        .post(PATH)
        .then()
        .statusCode(500)
        .body("error", notNullValue())
        .body("error", not(containsString("select")));
  }
}
