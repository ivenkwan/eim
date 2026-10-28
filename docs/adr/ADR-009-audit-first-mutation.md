# ADR-009: Audit-first mutation path — nothing changes unaudited

- **Status:** Accepted (ratifies AD-9, the enforced-audit mandate)
- **Date:** 2026-10-28

## Context

Mandate: detailed audit for every event and configuration change (FR-601.4,
FR-612, FR-613; NFR-64). Convention-based auditing drifts; reviewers forget.

## Decision

Every state change, published event, and configuration change writes a
detailed audit record in the same database transaction via a transactional
outbox + service interceptors. The outbox/interceptor path is the ONLY
sanctioned mutation path. Coverage is CI-enforced: every endpoint/event type
declares its audit mapping (`x-audit` in OpenAPI, `docs/audit-mappings.yaml`)
or the build fails (`scripts/check-audit-mappings.mjs`, with self-test). A
runtime reconciliation job compares mutation/event/audit counts.

## Consequences

- Audit-write failure aborts the business change — impossible to lose audit.
- Adds one indirection layer to every write; accepted.
- Spectral rule keeps the mapping visible at spec level.
