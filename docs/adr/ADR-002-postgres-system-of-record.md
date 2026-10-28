# ADR-002: PostgreSQL as the only system of record

- **Status:** Accepted (ratifies AD-2)
- **Date:** 2026-10-28

## Context

Ledger, plans, audit, accounting subledger, and process-engine state need
cross-domain transactional integrity (FR-601.4: audit commits with the change).

## Decision

PostgreSQL 16+ is the single system of record: partitioned ledger, RLS for
payee isolation, JSONB traces, Kogito JDBC persistence, Flyway migrations.
AGE rides the same cluster in a dedicated database.

## Consequences

- One ACID store enables the transactional-outbox audit guarantee.
- Capacity planning is concentrated on one cluster (HA via CloudNativePG).
- Age-of-record retention (NFR-63) is a single policy surface.
