# ADR-001: Hybrid engine — Kogito/Drools orchestration + dedicated JVM calculation service

- **Status:** Accepted (ratifies AD-1, [architecture §2](../../doc/03-architecture-plan.md))
- **Date:** 2026-10-28

## Context

The business proposal's engine chapter recommended a Rust/Go calculation worker
with Temporal.io orchestration. The platform mandates Drools (rules/DMN) and
Kogito (process automation). Marginal tier-slicing, draw state machines, and
bi-temporal replay require tight, deterministic numeric control.

## Decision

Kogito (BPMN) orchestrates and Drools evaluates business rules (DRL
forward/backward chaining, DMN decision tables); a dedicated stateless
Quarkus service (`eim-calc-engine`, BigDecimal) performs the deterministic
ledger math, invoked as a process service task.

## Consequences

- One JVM toolchain; KIE-native integration; BPMN portability.
- Rejects Temporal.io and the Rust worker (documented escape hatch: the
  service-task contract allows a native worker per shard if NFR-20 is missed).
- JVM throughput risk tracked as R-1 with nightly benchmarks.
