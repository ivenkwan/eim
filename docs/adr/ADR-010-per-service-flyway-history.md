# ADR-010: Per-service Flyway history tables

- **Status:** Accepted (2026-10-29, TG-1)
- **Context:** All five services share one PostgreSQL database. TG-0 used a
  single shared history table (`flyway_schema_history`): services collided on
  identical version numbers (checksum mismatch crashes, see TG-0 build log §4)
  and could not evolve independently.

## Decision

Each service migrates into its **own** Flyway history table
(`quarkus.flyway.table=flyway_<service>`). `eim-core-api` owns the shared
schema migrations (V2 core, V3 audit plane, V4 RLS, V5 AGE graph, V6/V7
ops); the other services carry only their baseline. `baseline-on-migrate`
allows adopting the split on an already-migrated database.

## Consequences

- No cross-service checksum coupling; independent rollout ordering.
- Migration policy lint (`scripts/check-migrations.sh`) enforces
  version-uniqueness per service, naming, destructive-statement guard, and
  immutability of shipped migrations (NFR-41/62).
- Cross-service schema contract changes ride core-api's V-numbers; consumers
  see them as already-applied baselines.
