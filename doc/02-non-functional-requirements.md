# EIM Platform — Non-Functional Requirements Specification

| | |
|---|---|
| **Document status** | Draft v1.1 — adds NFR-64 unified audit coverage for all events and configuration changes |
| **Date** | 2026-10-28 |
| **Source** | Derived from *EIM Platform Business Proposal.pdf* (PRD §5 NFRs, DAG engine SLAs, audit checklist, UX accessibility specs) |
| **Companion documents** | [01-functional-requirements.md](01-functional-requirements.md) · [03-architecture-plan.md](03-architecture-plan.md) |

---

## 1. Introduction

### 1.1 Purpose

This document defines the non-functional requirements (NFRs) — quality attributes and constraints — for the EIM platform. Where the [functional requirements](01-functional-requirements.md) define *what* the system does, this document defines *how well* it must do it and under which constraints. Each NFR is stated as a measurable target with a verification method.

### 1.2 Conventions

- IDs are stable: `NFR-<number>`, grouped by category.
- Every NFR carries: **statement → measurable target → verification method → phase** (P1/P2/P3 per the roadmap).
- Phase tags indicate when the NFR must first be met and continuously demonstrated (e.g. via CI or periodic audit), not merely designed for.
- "Tenant" below means a customer organization; sizing references the target market (250–5,000+ commissionable payees).

### 1.3 NFR summary

| Category | IDs | Phase focus |
|---|---|---|
| Precision & determinism | NFR-01 – NFR-08 | P1 |
| Performance & capacity | NFR-20 – NFR-27 | P1–P2 |
| Scalability & elasticity | NFR-30 – NFR-33 | P1–P2 |
| Availability & disaster recovery | NFR-40 – NFR-44 | P1–P2 |
| Security | NFR-50 – NFR-58 | P1–P2 |
| Auditability & immutability | NFR-60 – NFR-64 | P1–P2 |
| Compliance | NFR-70 – NFR-74 | P1–P2 |
| Cloud-agnostic portability | NFR-80 – NFR-83 | P1 |
| Observability | NFR-90 – NFR-93 | P1–P2 |
| Maintainability & testability | NFR-100 – NFR-105 | P1 |
| Internationalization | NFR-110 – NFR-112 | P1–P2 |
| Usability & accessibility | NFR-120 – NFR-124 | P1 |

---

## 2. Precision & Determinism (the trust foundation)

> Commission numbers are financial records. This category is non-negotiable and stricter than typical SaaS: every payout must be reproducible to the cent, forever.

### NFR-01 Arbitrary-precision decimal arithmetic *(Must, P1)*

All monetary and rate calculations shall use arbitrary-precision decimal arithmetic. Floating-point types (`FLOAT`, `DOUBLE`, IEEE-754 binary) are **prohibited** anywhere in the calculation and storage path.

- **Target**: storage at `NUMERIC(18,4)` minimum; intermediate computation carries ≥ 8 decimal places (decimal-128 class, e.g. Java `BigDecimal` / `rust_decimal`-equivalent semantics in the JVM engine); FX rates `NUMERIC(12,6)`.
- **Verification**: static analysis rule in CI that fails the build on float usage in calculation modules; schema migration tests asserting column types.

### NFR-02 Configurable rounding applied once, at the final payout step *(Must, P1)*

- **Target**: rounding mode configurable per tenant/plan (`HALF_EVEN` (Banker's) default, `HALF_UP` optional), applied exactly once when emitting the final rounded payout; all intermediate values stay unrounded. Sub-cent residuals in amortization schedules are absorbed in the final period (FR-902.4).
- **Verification**: golden calculation tests covering rounding boundaries; property test that Σ slices = final payout to the cent.

### NFR-03 Deterministic replay *(Must, P1)*

Identical inputs (transactions, credits, hierarchy, plan version) shall always produce byte-identical outputs regardless of when or where the calculation runs.

- **Target**: re-running any historical calculation during an audit reproduces the exact ledger (see FR-501.2); no reliance on map ordering, wall-clock, or hash-iteration order.
- **Verification**: CI replay tests re-executing versioned golden datasets and diffing ledger + trace output.

### NFR-04 Idempotent, atomic calculation runs *(Must, P1)*

- **Target**: a calculation run commits all ledger entries for a period atomically or rolls back cleanly (FR-507.1); re-running a failed run produces no duplicates; all service endpoints and event consumers are idempotent under at-least-once delivery.
- **Verification**: fault-injection tests (kill workers mid-wave); duplicate-delivery tests on event consumers.

### NFR-05 Effective-dated correctness *(Must, P1)*

- **Target**: every historical query (hierarchy, plan assignment, quota) resolves as-of the relevant business date; retroactive changes never mutate locked periods (enforced by schema constraints, not convention).
- **Verification**: scenario tests including mid-period manager moves, promotions, leave proration, and post-lock retro changes.

### NFR-06 Parallel-run cent-for-cent accuracy *(Must, P1 — release gate)*

- **Target**: 100.0% match to the cent (\$0.01) against design partners' legacy systems/shadow spreadsheets across two consecutive pay periods (proposal's MVP exit criterion).
- **Verification**: production parallel-run harness with diff report per period; sign-off recorded before legacy cutover.

### NFR-07 Calculation trace completeness *(Must, P1)*

- **Target**: 100% of `CALCULATED`/`PRIOR_PERIOD_TRUEUP` ledger entries carry a `calculation_trace` sufficient to reconstruct the payout without re-execution (FR-507.2); trace payload ≤ 16 KB compressed per entry.
- **Verification**: invariant check in the ledger-commit path (fails the run on a missing/oversized trace); sampling audit.

### NFR-08 Deterministic ordering guarantees across the system *(Must, P1)*

- **Target**: the transaction sort key (FR-501.1) and graph-traversal ordering are the single source of ordering; no component may introduce unbounded non-determinism (e.g. concurrent map iteration) into numeric results.
- **Verification**: code-review checklist item + repeat-run differential tests in CI.

---

## 3. Performance & Capacity

### NFR-20 Core calculation throughput *(Must, P1)*

- **Target**: compute payouts for **500 payees across 100,000 transaction rows in < 15 seconds** on the reference cluster (see [architecture](03-architecture-plan.md) §deployment sizing).
- **Verification**: nightly benchmark job against a synthetic 500/100k dataset, alerting on > 20% regression.

### NFR-21 Enterprise-scale headroom *(Must, P2)*

- **Target**: 5,000 payees / 1,000,000+ transaction rows per tenant-month completes within one close-cycle window (≤ 10 minutes full-cohort run); usage/metering ingestion ≥ 1M rows/tenant-month (FR-205).
- **Verification**: quarterly scale test with production-shaped data generator.

### NFR-22 API latency *(Must, P1)*

- **Target**: read APIs (statements, traces, plan metadata) p95 < 300 ms, p99 < 800 ms server-side (excluding network); write APIs p95 < 500 ms.
- **Verification**: continuous synthetic monitoring (OpenTelemetry + Prometheus histograms), SLO dashboards.

### NFR-23 Interactive plan-modeling preview *(Must, P1)*

- **Target**: live sandbox preview (FR-407) returns in < 800 ms for changes scoped to one payee's sub-graph; with incremental dirty-flag caching (FR-509), full-cohort interactive previews skip unaffected payees.
- **Verification**: UI telemetry (p95 preview latency) + load test of preview endpoint under 50 concurrent admins.

### NFR-24 Hierarchy resolution latency *(Must, P1)*

- **Target**: effective-dated rollup-scope resolution (graph query) for any manager/period in a 5,000-payee hierarchy in < 2 s.
- **Verification**: graph query benchmarks in CI (AGE query plans reviewed per release).

### NFR-25 Reporting & export performance *(Must, P2)*

- **Target**: deferred cost rollforward (FR-906) with drill-down export for up to 500,000 active schedule lines in < 10 s; payroll export for 5,000 payees < 60 s.
- **Verification**: benchmark suite on size-scaled fixtures.

### NFR-26 Frontend performance *(Should, P1)*

- **Target**: first contentful paint < 2 s and interactive < 3.5 s on a standard corporate laptop (median connection); statement grid virtualized to handle 10,000+ rows without degradation.
- **Verification**: Lighthouse/CI Web-Vitals budgets enforced in the frontend pipeline.

### NFR-27 Background job scheduling latency *(Should, P1)*

- **Target**: scheduled syncs and calculation jobs start within 60 s of trigger; process-engine (BPMN) human-task notifications dispatch within 30 s of state change.

---

## 4. Scalability & Elasticity

### NFR-30 Horizontal scaling of stateless services *(Must, P1)*

- **Target**: API, calculation, process, and connector services scale horizontally with no code change; calculation workers scale by queue depth (event-driven autoscaling); no sticky sessions.
- **Verification**: scale test doubling worker replicas observing near-linear throughput on NFR-20 benchmark.

### NFR-31 Data partitioning and archival *(Must, P1)*

- **Target**: `commission_ledger_entry` partitioned by payout period; hot partitions (open + last 24 months) remain queryable at NFR-22 latency; older partitions attach/detach for archival without downtime.
- **Verification**: partition maintenance runbook test with 10 years of synthetic periods.

### NFR-32 Multi-instance process orchestration *(Must, P1)*

- **Target**: BPMN process engine instances run redundantly; process state persists transactionally (PostgreSQL) so pod loss never strands in-flight period-close or approval processes; jobs (timers/escalations) fail over automatically.
- **Verification**: chaos test killing process-runtime pods mid-workflow, asserting resumption from persisted state.

### NFR-33 Connector fan-out *(Should, P2)*

- **Target**: per-tenant connector executions isolated (per-tenant quotas) so one tenant's 1M-row warehouse sync cannot starve another's CRM sync in shared deployments.

---

## 5. Availability & Disaster Recovery

### NFR-40 Service availability *(Must, P1)*

- **Target**: 99.9% monthly availability for the seller portal and read APIs (≤ 43 min/month downtime); calculation runs are batch-tolerant but schedulable within SLA windows.
- **Verification**: uptime monitoring with error-budget alerting.

### NFR-41 Zero-downtime deploys *(Must, P1)*

- **Target**: rolling updates and database schema migrations (expand/contract) deploy without maintenance windows or in-flight calculation aborts.
- **Verification**: deploy pipeline executes against a running soak environment with synthetic traffic; DB migration policy lint (no destructive steps in the same release).

### NFR-42 Backup and recovery *(Must, P1)*

- **Target**: RPO ≤ 15 min (continuous WAL archiving), RTO ≤ 4 h for full regional recovery; backups are encrypted, immutable (object-lock), and restore-tested monthly — **including the AGE graph catalog** (see risk note in [architecture](03-architecture-plan.md) §risks).
- **Verification**: monthly restore drill with integrity checks (ledger invariants, graph consistency).

### NFR-43 Degradation behavior *(Must, P1)*

- **Target**: if the calculation engine or process engine is unavailable, sellers still view last-published statements (read path degrades gracefully); ingestion buffers rather than drops; gateway serves cached/static assets.
- **Verification**: dependency-outage simulation tests.

### NFR-44 Data durability of immutable records *(Must, P1)*

- **Target**: once a period is locked, its ledger rows and audit entries survive application bugs and re-deploys; deletion is impossible via any supported interface (enforced by DB privileges + WORM backup tier).

---

## 6. Security

### NFR-50 Encryption *(Must, P1)*

- **Target**: TLS 1.3 for all in-transit traffic (external and service-to-service); AES-256 encryption at rest for database, object storage, and backups.
- **Verification**: infrastructure policy-as-code scans; TLS configuration audit per release.

### NFR-51 Authentication & token validation *(Must, P1)*

- **Target**: all external requests authenticate via the gateway (OIDC/SAML via tenant IdP); JWTs validated centrally (signature, audience, expiry); service-to-service calls use mTLS or signed short-lived tokens. No service exposed bypassing the gateway.
- **Verification**: penetration test; automated check that no workload is reachable via direct ingress.

### NFR-52 Authorization enforcement at the data layer *(Must, P1)*

- **Target**: payee data isolation enforced by PostgreSQL row-level security keyed on session identity (defense in depth beyond application checks — FR-103); administrative roles granted least privilege; SoD rules (FR-102) tested.
- **Verification**: RLS policy tests executed as different roles in CI; access-control regression suite.

### NFR-53 Secrets management *(Must, P1)*

- **Target**: no secrets in images, charts, or source; secrets injected at runtime from the cluster secret store (external-secrets/KMS-backed); connector OAuth tokens and key-pairs encrypted at rest with per-tenant keys.
- **Verification**: image/pipeline secret scanning; runtime audit that workloads receive secrets only via mounted references.

### NFR-54 API protection *(Must, P1)*

- **Target**: gateway enforces per-tenant rate limiting, request-size limits, schema validation on public endpoints; protection against OWASP API Security Top 10 (BOLA tested per endpoint — object-level authz is the highest-risk class for a multi-tenant ledger).
- **Verification**: annual (or per-major-release) third-party pen test; automated BOLA tests per endpoint in CI.

### NFR-55 Audit of security events *(Must, P1)*

- **Target**: authentication failures, authorization denials, privilege changes, and configuration changes are logged with actor, source IP, and timestamp, retained ≥ 12 months, and alertable. Security events are a governed subset of the unified audit plane (NFR-60/64, FR-612/613).

### NFR-56 Malware scanning of uploads *(Should, P2)*

- **Target**: dispute attachments and CSV uploads scanned before storage; infected files quarantined.

### NFR-57 Vulnerability management *(Must, P1)*

- **Target**: container images built daily from patched bases; CVE scanning gates deploys (block on critical, ≤ 7-day fix SLA); SBOM generated per release.

### NFR-58 SOC 2 readiness *(Must, P2)*

- **Target**: architecture, logging, access reviews, and change management evidence ready for SOC 2 Type II audit; control mapping maintained per release.

---

## 7. Auditability & Immutability

### NFR-60 Tamper-evident audit trail — all streams *(Must, P1)*

- **Target**: all audit streams — action audit (FR-601), event audit (FR-612), and configuration change audit (FR-613) — are append-only at the database privilege level; daily hash-chained (or equivalent) seals over audit segments make tampering detectable (complemented by WORM backups).
- **Verification**: attempted-mutation test as a privileged role (must fail) executed against **each** stream; seal-verification tooling run in restore drills.

### NFR-61 Complete decision lineage *(Must, P1 — finance audit)*

- **Target**: every published number traces to: source snapshot → credit allocation → plan version (hash) → rule/decision artifact version → ledger entry with trace; GL lines additionally trace to schedule lines and assets (FR-909, zero orphans); and every number traces to the configuration versions in effect at calculation time (FR-613).
- **Verification**: lineage walk test executed over a randomly sampled closed period each month.

### NFR-62 Versioned business logic *(Must, P1)*

- **Target**: plans, rate tables, DMN/DRL artifacts, and classification policies are versioned and immutable once published; historical periods always reference the exact versions in effect.
- **Verification**: artifact registry checks; replay test reconstructing a year-old period with pinned versions.

### NFR-63 Retention *(Must, P1; extended P2)*

- **Target**: transactional data and **all audit streams** (action, event, configuration change) retained ≥ 7 years by default (configurable); event-audit payloads stored compressed; Enterprise tier offers unlimited historical audit retention; deletion policies reconcile GDPR erasure with financial-record retention via pseudonymization of personal fields while preserving ledger integrity.

### NFR-64 Unified audit coverage — every event and configuration change *(Must, P1)*

- **Target**: 100% of (a) state-changing API calls, (b) published/consumed domain events, (c) BPMN transitions, and (d) configuration changes produce detailed audit records (actor/actor-type, correlation ID, before/after or full payload, outcome) written **in the same transaction** as the change. Coverage is enforced architecturally — transactional outbox + interceptors is the only sanctioned mutation path (FR-601.4, [architecture](03-architecture-plan.md) §12) — leaving no unaudited code path.
- **Verification**: CI audit-coverage suite — every new endpoint and event type must declare its audit mapping or the build fails; runtime reconciliation job comparing mutation, event, and audit counts with alerting on any gap; monthly sampled audit-completeness report.

---

## 8. Compliance

### NFR-70 Accounting standards *(Must, P2)*

- **Target**: ASC 606 / ASC 340-40, IFRS 15, ASC 830/IAS 21 behaviors per FR-9xx; subledger designed to satisfy Big-4 audit scrutiny (SOC 1 Type II readiness): zero orphaned GL lines, immutable posted schedules, automated completeness gate.
- **Verification**: quarterly reconciliation drills (subledger ↔ GL identity checks); external-accountant review before GA of Phase 2.

### NFR-71 Data privacy *(Must, P2)*

- **Target**: GDPR/CCPA data-subject workflows (access, export, erasure-with-pseudonymization); PII inventory maintained; processing agreements (DPAs) supported for sub-processors.

### NFR-72 Data residency *(Should, P2)*

- **Target**: deployment option pinning all tenant data (database, object storage, backups) to a chosen region/jurisdiction; cloud-agnostic packaging (NFR-80) makes residency a placement decision, not a porting project.

### NFR-73 Accessibility compliance *(Must, P1)*

- **Target**: WCAG 2.1 AA for all user-facing pages, including the colorblind-safe semantic palette and never-red-text-alone negative-delta rule from the UX spec.
- **Verification**: automated axe-core checks in CI + manual screen-reader passes per release.

### NFR-74 Export & interoperability *(Must, P1)*

- **Target**: all customer data (transactions, ledger, traces, disputes, audit log) exportable in open formats (CSV/JSON) without vendor lock-in — a sales-level commitment mirrored technically.

---

## 9. Cloud-Agnostic Portability *(mandated constraint)*

### NFR-80 Kubernetes-only runtime *(Must, P1)*

- **Target**: the entire platform runs on any CNCF-conformant Kubernetes cluster (v1.29+; EKS/AKS/GKE/OKE/on-prem OpenShift or k3s equivalents) with **no cloud-provider-specific managed services in the critical path**. Infrastructure dependencies are limited to: Kubernetes API, POSIX volumes or CSI, and (optionally) an S3-compatible endpoint.
- **Verification**: reference deployment standing up fully on two different providers + on-prem (kind/k3s) from the same Helm umbrella chart in CI smoke tests.

### NFR-81 Portable packaging *(Must, P1)*

- **Target**: all components shipped as OCI images + Helm charts (umbrella chart with per-component subcharts); stateful dependencies (PostgreSQL, Kafka, object storage) provisioned by operators (CloudNativePG, Strimzi, MinIO) or substituted by operator-provided equivalents via values override — never hard-coded.
- **Verification**: chart lint + `helm template` tests; substitution matrix (e.g. CloudNativePG ↔ managed PostgreSQL via values only).

### NFR-82 Configuration over code for environment specifics *(Must, P1)*

- **Target**: external endpoints (IdP, SMTP, S3), sizing, and feature flags are chart values/config-map entries; no rebuild required to move environments.

### NFR-83 Exit/portability testing *(Should, P2)*

- **Target**: documented, rehearsed procedure to migrate a tenant between clusters/providers, including database dump/restore and object-storage sync, with a full data-integrity verification report.

---

## 10. Observability

### NFR-90 Distributed tracing *(Must, P1)*

- **Target**: OpenTelemetry traces span the full path: gateway → API → process engine → rule/decision evaluation → calculation waves → database commits, with a stable correlation ID surfaced in the UI (support can quote it on a dispute ticket).
- **Verification**: trace-completeness check in the staging soak environment (every request produces an unbroken span tree).

### NFR-91 Metrics & SLOs *(Must, P1)*

- **Target**: RED/USE metrics per service; business metrics (payees calculated, ledger entries committed, disputes open, true-ups pending) exported as first-class metrics; SLO dashboards with error budgets for NFR-20/22/40.
- **Verification**: Prometheus rule tests; dashboard-as-code reviewed per release.

### NFR-92 Log management *(Must, P1)*

- **Target**: structured JSON logs with tenant/correlation IDs; retention ≥ 90 days hot; **no PII or payout amounts at INFO level** (amounts only in the ledger/audit stores, not in app logs).

### NFR-93 Alerting & runbooks *(Must, P1)*

- **Target**: actionable alerts for: calculation-run failures, stuck BPMN processes, connector failures, backup/restore failures, SLO burn; every alert links to a runbook.

---

## 11. Maintainability & Testability

### NFR-100 Golden calculation suites *(Must, P1)*

- **Target**: versioned golden datasets covering every calculation feature (marginal/cliff boundaries, draws, clawbacks, proration, rollups, true-ups) run in CI on every change to engine or rule artifacts; the canonical 3-slice tier example (FR-502.1) is a permanent test.
- **Verification**: CI gate — no merge that changes numeric output without an accompanying approved golden-file update.

### NFR-101 Contract testing *(Must, P1)*

- **Target**: OpenAPI contracts for all services; consumer-driven contract tests between frontend and backend, and between process engine and calculation service; contracts versioned semantically.

### NFR-102 Test coverage floors *(Must, P1)*

- **Target**: ≥ 85% line coverage on calculation, ledger, and accounting-amortization modules; ≥ 70% elsewhere; mutation testing (sampled) on the decimal/rounding utility.
- **Verification**: coverage gates in CI.

### NFR-103 Type safety end-to-end *(Must, P1)*

- **Target**: frontend in strict TypeScript (`strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, no `any` without waiver); API types generated from OpenAPI (no hand-written client types); backend exposes strongly typed schemas — eliminating an entire class of integration defects (see [architecture](03-architecture-plan.md) §frontend).
- **Verification**: `tsc --noEmit` + typed-lint as CI gates; generated-types freshness check (build fails if spec drifted).

### NFR-104 Rule/decision artifact testing *(Must, P1)*

- **Target**: DRL/DMN artifacts are unit-testable with the standard KIE test harness; publish pipeline runs decision-table verification (completeness/consistency checks on DMN tables) before a plan version can publish.

### NFR-105 Documentation & onboarding *(Should, P1)*

- **Target**: architecture decision records (ADRs) maintained; every service has a README with local-run instructions; new-engineer onboarding path < 2 days to first merged change.

---

## 12. Internationalization

### NFR-110 Multi-currency *(Must, P1 core / P2 full)*

- **Target**: P1 — transactions carried in transaction currency with `fx_rate_applied`, statements and payouts in payee base currency; P2 — full multi-currency books with historical-rate locking per ASC 830 (FR-905).
- **Verification**: FX golden tests including 6-decimal rates and cross-rate rounding.

### NFR-111 Time zones & calendars *(Must, P1)*

- **Target**: all timestamps stored UTC (`TIMESTAMPTZ`); business dates (`DATE`) used for effective dating; period boundaries in tenant-configurable timezone; fiscal calendars (4-4-5 etc.) supported.

### NFR-112 Locale formatting *(Should, P1)*

- **Target**: UI number/currency/date formatting per user locale; `tabular-nums` typography preserved across locales (UX spec).

---

## 13. Usability & Accessibility

### NFR-120 Keyboard-first admin operation *(Must, P1)*

- **Target**: the plan builder grid supports spreadsheet conventions (Tab/Shift+Tab, Enter-to-commit, Ctrl/Cmd+Z undo history, Ctrl/Cmd+K command palette) with full keyboard reachability.

### NFR-121 Readable numeric UI *(Must, P1)*

- **Target**: `font-variant-numeric: tabular-nums` on every monetary/percentage column; decimal alignment verified visually in E2E screenshots.

### NFR-122 Color-independent semantics *(Must, P1)*

- **Target**: negative amounts always carry an explicit minus/chevron glyph; locked states carry icons; palettes pass WCAG contrast — per the UX spec's colorblind-safe palette.

### NFR-123 Statement comprehensibility *(Must, P1)*

- **Target**: any published payout explainable by the trace drawer in ≤ 3 clicks from the statement row (the core anti-"shadow accounting" metric); dispute submission from the trace ≤ 2 additional clicks.
- **Verification**: usability testing with seller personas per release; dispute-rate telemetry (target trend: declining).

### NFR-124 Admin time-to-configure *(Must, P1 — product KPI)*

- **Target**: 100% of standard SaaS commission plans (tiered accelerators, splits, draws, clawbacks) configurable by RevOps without engineering intervention (proposal exit criterion); connector setup < 3 min (FR-201.1).

---

## 14. Verification summary

| Gate | When | NFRs verified |
|---|---|---|
| CI (every merge) | per change | 01–04, 08, 52, 64, 73, 100–104, 110 |
| Nightly | benchmark + soak | 20, 22, 23, 90, 92 |
| Release candidate | staging full suite | 24–27, 30–32, 40–44, 50–58, 60–64, 80–82 |
| Monthly/quarterly ops | restore & scale drills | 21, 25, 42, 61, 64, 83, 93 |
| Parallel run (design partners) | 2 consecutive pay periods | 06, 124 |
| Annual | pen test, SOC 2 evidence | 54, 55, 58, 70 |

---

*End of non-functional requirements specification.*
