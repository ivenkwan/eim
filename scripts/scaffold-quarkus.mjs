#!/usr/bin/env node
// TG0-INF-2: scaffolds the five Quarkus service skeletons under apps/.
// Idempotent — skips files that already exist so hand edits are preserved.
// Kept in-repo as provenance for how the skeletons were generated.
import { mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.cwd();

const SERVICES = [
  { dir: 'eim-core-api', pkg: 'coreapi', cls: 'CoreApi', port: 8080, desc: 'Domain APIs: plans, statements, disputes, exports, audit viewer' },
  { dir: 'eim-connectors', pkg: 'connectors', cls: 'Connectors', port: 8081, desc: 'Ingestion connector runtime (SFDC, HubSpot, Snowflake, CSV)' },
  { dir: 'eim-calc-engine', pkg: 'calcengine', cls: 'CalcEngine', port: 8082, desc: 'Deterministic calculation engine' },
  { dir: 'eim-process', pkg: 'process', cls: 'Process', port: 8083, desc: 'Kogito BPMN process runtime (period close, disputes, true-ups)' },
  { dir: 'eim-plan-compiler', pkg: 'plancompiler', cls: 'PlanCompiler', port: 8084, desc: 'Plan -> DRL/DMN artifact compiler' },
];

function put(relPath, content) {
  const abs = join(ROOT, relPath);
  if (existsSync(abs)) {
    console.log(`[scaffold] skip (exists) ${relPath}`);
    return;
  }
  mkdirSync(join(abs, '..'), { recursive: true });
  writeFileSync(abs, content);
  console.log(`[scaffold] wrote ${relPath}`);
}

// ---------------- parent pom ----------------
put(
  'apps/pom.xml',
  `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>io.eim</groupId>
  <artifactId>eim-services</artifactId>
  <version>0.1.0</version>
  <packaging>pom</packaging>
  <name>EIM Services (parent)</name>

  <modules>
${SERVICES.map((s) => `    <module>${s.dir}</module>`).join('\n')}
  </modules>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <quarkus.platform.version>3.15.1</quarkus.platform.version>
    <surefire.version>3.2.5</surefire.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.quarkus.platform</groupId>
        <artifactId>quarkus-bom</artifactId>
        <version>\${quarkus.platform.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>io.quarkus</groupId>
          <artifactId>quarkus-maven-plugin</artifactId>
          <version>\${quarkus.platform.version}</version>
          <extensions>true</extensions>
          <executions>
            <execution>
              <goals>
                <goal>build</goal>
                <goal>generate-code</goal>
              </goals>
            </execution>
          </executions>
        </plugin>
        <plugin>
          <artifactId>maven-surefire-plugin</artifactId>
          <version>\${surefire.version}</version>
          <configuration>
            <systemPropertyVariables>
              <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
            </systemPropertyVariables>
          </configuration>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
`,
);

// ---------------- per-service files ----------------
for (const svc of SERVICES) {
  const base = `apps/${svc.dir}`;
  const pkgPath = `io/eim/${svc.pkg}`;
  const src = `${base}/src/main/java/${pkgPath}`;
  const test = `${base}/src/test/java/${pkgPath}`;

  put(
    `${base}/pom.xml`,
    `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>io.eim</groupId>
    <artifactId>eim-services</artifactId>
    <version>0.1.0</version>
  </parent>

  <artifactId>${svc.dir}</artifactId>
  <name>${svc.dir}</name>
  <description>${svc.desc}</description>

  <dependencies>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-rest-jackson</artifactId>
    </dependency>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-smallrye-health</artifactId>
    </dependency>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-opentelemetry</artifactId>
    </dependency>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-jdbc-postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-flyway</artifactId>
    </dependency>

    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-junit5</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-jdbc-h2</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>io.rest-assured</groupId>
      <artifactId>rest-assured</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
`,
  );

  put(
    `${src}/${svc.cls}Application.java`,
    `package io.eim.${svc.pkg};

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class ${svc.cls}Application {

    public static void main(String[] args) {
        Quarkus.run(args);
    }
}
`,
  );

  put(
    `${src}/HelloResource.java`,
    `package io.eim.${svc.pkg};

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Read-only liveness greeting; contract lives in openapi/${svc.dir.replace(/^eim-/, '')}.yaml */
@Path("/api/v1/hello")
public class HelloResource {

    public record HelloResponse(String service, String status, String message) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HelloResponse hello() {
        return new HelloResponse("${svc.dir}", "ok", "EIM ${svc.dir} skeleton (TG-0)");
    }
}
`,
  );

  // core-api: audited mutating endpoint (x-audit in the OpenAPI spec)
  if (svc.dir === 'eim-core-api') {
    put(
      `${src}/ConfigChangeResource.java`,
      `package io.eim.${svc.pkg};

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

/**
 * Skeleton for the audited configuration registry (FR-613 / AD-9).
 * The full transactional-outbox wiring lands in TG-1; the endpoint and its
 * audit mapping (openapi/core-api.yaml x-audit) are the TG-0 contract.
 */
@Path("/api/v1/admin/config-change")
public class ConfigChangeResource {

    public record ConfigChangeRequest(String entity, String payload, String reason) {
    }

    public record ConfigChangeAccepted(boolean accepted, String requestId) {
    }

    public record ErrorResponse(String error) {
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response submit(ConfigChangeRequest request) {
        if (request == null || isBlank(request.entity()) || isBlank(request.reason())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("entity and reason are required"))
                    .build();
        }
        return Response.status(Response.Status.ACCEPTED)
                .entity(new ConfigChangeAccepted(true, UUID.randomUUID().toString()))
                .build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
`,
    );
    put(
      `${test}/ConfigChangeResourceTest.java`,
      `package io.eim.${svc.pkg};

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
                .body("{\\"entity\\":\\"connector.salesforce\\",\\"payload\\":\\"{}\\",\\"reason\\":\\"rotate mapping\\"}")
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
                .body("{\\"entity\\":\\"connector.salesforce\\",\\"reason\\":\\"\\"}")
                .when()
                .post("/api/v1/admin/config-change")
                .then()
                .statusCode(400);
    }
}
`,
    );
  }

  // calc-engine: audited run-request endpoint (x-audit in the OpenAPI spec)
  if (svc.dir === 'eim-calc-engine') {
    put(
      `${src}/CalcRunResource.java`,
      `package io.eim.${svc.pkg};

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

/** Skeleton run-request endpoint; wavefront execution lands in TG-3 (FR-5xx). */
@Path("/api/v1/calc/runs")
public class CalcRunResource {

    public record CalcRunRequest(String tenantId, String payoutPeriodId, String planVersionHash) {
    }

    public record CalcRunAccepted(boolean accepted, String runId) {
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response requestRun(CalcRunRequest request) {
        if (request == null || isBlank(request.tenantId()) || isBlank(request.payoutPeriodId())
                || isBlank(request.planVersionHash())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new CalcRunAccepted(false, "tenantId, payoutPeriodId and planVersionHash are required"))
                    .build();
        }
        return Response.status(Response.Status.ACCEPTED)
                .entity(new CalcRunAccepted(true, UUID.randomUUID().toString()))
                .build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
`,
    );
    put(
      `${test}/CalcRunResourceTest.java`,
      `package io.eim.${svc.pkg};

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
                .body("{\\"tenantId\\":\\"t1\\",\\"payoutPeriodId\\":\\"2026-10\\",\\"planVersionHash\\":\\"sha256:abc\\"}")
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
                .body("{\\"tenantId\\":\\"t1\\",\\"payoutPeriodId\\":\\"2026-10\\",\\"planVersionHash\\":\\"\\"}")
                .when()
                .post("/api/v1/calc/runs")
                .then()
                .statusCode(400);
    }
}
`,
    );
  }

  put(
    `${test}/HelloResourceTest.java`,
    `package io.eim.${svc.pkg};

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
                .body("service", is("${svc.dir}"))
                .body("status", is("ok"));
    }
}
`,
  );

  put(
    `${base}/src/main/resources/application.properties`,
    `quarkus.application.name=${svc.dir}
quarkus.http.port=${svc.port}

# --- DataSource (PostgreSQL, arch \\u00a79) ---
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=\${DB_USERNAME:eim}
quarkus.datasource.password=\${DB_PASSWORD:eim-dev}
quarkus.datasource.jdbc.url=jdbc:postgresql://\${DB_HOST:eim-postgres}:\${DB_PORT:5432}/\${DB_NAME:eim}

# --- Flyway (TG1-DAT-1 lands the real schema; baseline only for TG-0) ---
quarkus.flyway.migrate-at-start=true
quarkus.flyway.locations=db/migration

# --- OpenTelemetry (NFR-90) ---
quarkus.otel.enabled=true
quarkus.otel.exporter.otlp.traces.endpoint=\${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://localhost:4317}

# --- Health (smallrye-health defaults: /q/health/live, /q/health/ready) ---

# --- Tests: in-memory H2 in PostgreSQL compatibility mode ---
%test.quarkus.datasource.db-kind=h2
%test.quarkus.datasource.jdbc.url=jdbc:h2:mem:eim;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
%test.quarkus.http.port=0
%test.quarkus.http.test-port=0
%test.quarkus.otel.enabled=false
`,
  );

  put(
    `${base}/src/main/resources/db/migration/V1__baseline.sql`,
    `-- TG-0 baseline only. The real EIM schema (FR doc section 12) lands in TG-1.
-- NOTE: this file MUST be byte-identical across all five services — they share
-- one database and Flyway history; per-service schemas get per-service history
-- tables in TG-1.
CREATE TABLE IF NOT EXISTS schema_baseline (
    id BIGSERIAL PRIMARY KEY,
    service VARCHAR(64) NOT NULL,
    applied_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
INSERT INTO schema_baseline (service) VALUES ('eim-platform');
`,
  );

  put(
    `${base}/Dockerfile`,
    `# Runtime image. Requires the service jar built first: mvn -f apps/pom.xml package
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/quarkus-app/ ./quarkus-app/
EXPOSE ${svc.port}
ENTRYPOINT ["java", "-jar", "quarkus-app/quarkus-run.jar"]
`,
  );

  put(
    `${base}/Dockerfile.build`,
    `# Self-contained build image (TG0-INF-7). Used by CI (images.yml).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /project
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /project/target/quarkus-app/ ./quarkus-app/
EXPOSE ${svc.port}
ENTRYPOINT ["java", "-jar", "quarkus-app/quarkus-run.jar"]
`,
  );

  put(
    `${base}/README.md`,
    `# ${svc.dir}

${svc.desc} (TG-0 skeleton: Quarkus + REST + SmallRye Health + OpenTelemetry + Flyway).

## Run locally

\`\`\`bash
mvn -f apps/${svc.dir}/pom.xml quarkus:dev   # needs PostgreSQL at \$DB_HOST (see application.properties)
mvn -f apps/${svc.dir}/pom.xml test          # unit tests run on in-memory H2
\`\`\`

- Health: \`GET /q/health/ready\`
- Contract: \`GET /api/v1/hello\` per \`openapi/${svc.dir.replace(/^eim-/, '')}.yaml\`
- OpenAPI spec drift is gated by \`pnpm audit:mappings\` (x-audit) and \`pnpm codegen:freshness\` (client types).
`,
  );
}

console.log('[scaffold] done');
