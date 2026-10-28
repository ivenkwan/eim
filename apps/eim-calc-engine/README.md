# eim-calc-engine

Deterministic calculation engine (TG-0 skeleton: Quarkus + REST + SmallRye Health + OpenTelemetry + Flyway).

## Run locally

```bash
mvn -f apps/eim-calc-engine/pom.xml quarkus:dev   # needs PostgreSQL at $DB_HOST (see application.properties)
mvn -f apps/eim-calc-engine/pom.xml test          # unit tests run on in-memory H2
```

- Health: `GET /q/health/ready`
- Contract: `GET /api/v1/hello` per `openapi/calc-engine.yaml`
- OpenAPI spec drift is gated by `pnpm audit:mappings` (x-audit) and `pnpm codegen:freshness` (client types).
