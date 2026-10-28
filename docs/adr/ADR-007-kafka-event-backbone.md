# ADR-007: Kafka as the event backbone

- **Status:** Accepted (ratifies AD-7)
- **Date:** 2026-10-28

## Decision

Kafka carries domain events (`eim.*` topics) between ingestion, calculation,
process, and indexing, and is the Kogito eventing channel (Data Index, Jobs).
Consumers are idempotent under at-least-once delivery (NFR-04).

## Consequences

- No point-to-point coupling; audit exporter observes the same stream
  (FR-612).
- Dev posture runs a single-node KRaft broker from the same chart; production
  substitutes Strimzi via values.
