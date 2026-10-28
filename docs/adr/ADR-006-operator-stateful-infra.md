# ADR-006: Operators for stateful infrastructure, nothing cloud-managed

- **Status:** Accepted (ratifies AD-6, mandates C1/NFR-80/81)
- **Date:** 2026-10-28

## Decision

Stateful dependencies run via operators or in-chart deployments:
CloudNativePG (PostgreSQL+AGE), Strimzi (Kafka), MinIO (S3-compatible).
Substituting customer-provided equivalents is values-only, verified by
`scripts/substitution-matrix.sh`. The only external interface dependency is
an S3-compatible endpoint.

## Consequences

- Same charts deploy to any CNCF-conformant cluster (verified on kind).
- Slightly higher operational burden vs managed services — accepted for
  portability and data-residency (NFR-72).
