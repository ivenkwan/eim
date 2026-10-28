# ADR-005: Frontend types are generated, never hand-written

- **Status:** Accepted (ratifies AD-5, mandate C4)
- **Date:** 2026-10-28

## Decision

OpenAPI 3.1 specs are the contract. `openapi-typescript` generates TS types; a
generator emits strict zod schemas; wire data is validated at the boundary
(AD-5/NFR-103). Maximal strictness flags are CI gates; drift fails the build
(`pnpm codegen:freshness`).

## Consequences

- An entire class of integration defects is eliminated by construction.
- Spec changes require a reviewed regeneration commit (visible diff).
