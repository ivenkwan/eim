# EIM Platform — Functional Requirements Specification

| | |
|---|---|
| **Document status** | Draft v1.1 — adds mandated detailed audit for all events and configuration changes (FR-601.4, FR-610, FR-612, FR-613) |
| **Date** | 2026-10-28 |
| **Source** | Derived from *EIM Platform Business Proposal.pdf* (business case, Phase 1 MVP PRD, Phase 2 ASC 606/IFRS 15 specification, DAG calculation engine architecture, UX wireframe specifications) |
| **Companion documents** | [02-non-functional-requirements.md](02-non-functional-requirements.md) · [03-architecture-plan.md](03-architecture-plan.md) · [04-manual-intervention-playbook.md](04-manual-intervention-playbook.md) |

---

## 1. Introduction

### 1.1 Purpose

This document defines the functional requirements (FRs) for the Enterprise Incentive Management (EIM) platform: an AI-native, composable system that automates the design, calculation, tracking, governance, and auditing of sales commissions, bonuses, and incentive programs for mid-market and enterprise organizations (250–5,000+ commissionable headcount).

### 1.2 Scope

The requirements cover the full three-phase product roadmap:

- **Phase 1 (P1, Months 1–6)** — MVP: no-code calculation engine, CRM/warehouse ingestion, seller transparency portal, governance, payroll export.
- **Phase 2 (P2, Months 7–12)** — Enterprise governance & AI layer: ASC 606 / IFRS 15 accounting, multi-currency, SSO/RBAC hardening, GenAI statement explainer, CRM-embedded what-if calculator.
- **Phase 3 (P3, Months 13–18)** — Full SPM & channel expansion: predictive Monte Carlo plan modeling, Territory & Quota (T&Q) planning, partner/channel incentive portals.

Out of scope for this document: commercial packaging, pricing, GTM activities, and detailed UI layout (see the wireframe chapter of the business proposal).

### 1.3 Personas and roles

| Persona | Description | Primary phases |
|---|---|---|
| **Comp Admin (RevOps)** | Designs and maintains compensation plans, maps data, resolves disputes | P1–P3 |
| **Finance Approver (Controller)** | Reviews, approves, locks payout periods; owns accounting policy | P1–P2 |
| **Payee (Sales Rep / Manager)** | Views statements, attainment, traces; submits disputes | P1–P3 |
| **Revenue Accountant** | Owns ASC 606 capitalization, amortization schedules, GL journals | P2 |
| **IT / Data Engineer** | Operates connectors and warehouse integration | P1–P3 |
| **Partner (Channel)** | External user of partner incentive portal | P3 |

### 1.4 Requirement conventions

- **IDs** are stable: `FR-<area><number>` (e.g. `FR-503`). Child criteria use `FR-503.1`.
- **Priority** follows MoSCoW: *Must* (release blocker), *Should*, *Could*, *Won't (this release)*.
- **Phase** tags: P1, P2, P3 as defined above.
- Every requirement carries acceptance criteria (AC) that are objectively testable.
- **Traceability**: each area maps back to the epics/user stories in the source proposal (Section 8).

---

## 2. FR-1xx — Identity, Tenancy & Access Management

### FR-101 User authentication via SSO *(Must, P1)*

The system shall authenticate all human users through enterprise identity providers using OIDC or SAML 2.0 (Okta, Azure AD / Entra ID, Google Workspace), federated through the platform identity broker.

**Acceptance criteria**
1. FR-101.1 — A user can sign in via a tenant-configured IdP; local password login exists only for break-glass admin accounts protected by MFA.
2. FR-101.2 — Session tokens are short-lived JWTs validated at the API gateway; refresh follows the IdP's policy.
3. FR-101.3 — SCIM 2.0 (or IdP directory sync) provisions/de-provisions users so that terminated employees lose access within 5 minutes of disablement in the IdP.

### FR-102 Role-based access control with separation of duties *(Must, P1)*

The system shall enforce tenant-scoped RBAC with at minimum the roles: **System Admin**, **Comp Admin**, **Finance Approver**, **Payee**, **Manager**, and **Revenue Accountant** (P2), and shall enforce separation of duties between plan configuration and period closing.

**Acceptance criteria**
1. FR-102.1 — Comp Admins can create/edit plans but cannot execute `PERIOD_LOCK`; only Finance Approvers can.
2. FR-102.2 — No role (including System Admin) can delete or mutate audit log entries.
3. FR-102.3 — All API authorization decisions are made server-side from the validated token claims; the UI merely hides what the API already forbids.

### FR-103 Payee-scoped data visibility *(Must, P1)*

Payees shall only query commission data belonging to themselves; Managers shall additionally see their effective-dated downline.

**Acceptance criteria**
1. FR-103.1 — A Payee querying ledger entries receives only rows where `payee_id` resolves to their own identity, enforced by database row-level security (not application filtering alone).
2. FR-103.2 — A Manager sees downline data only for direct/indirect reports per the reporting hierarchy *effective on each transaction's date*.
3. FR-103.3 — Attempts to access out-of-scope resources return `403` and are logged as security events.

### FR-104 Multi-tenancy isolation *(Must, P1)*

The system shall be single-tenant-per-cluster by default (dedicated deployment model) with tenant identifiers on every domain record so that a shared deployment model is possible without schema changes.

**Acceptance criteria**
1. FR-104.1 — Every domain table carries `tenant_id`; all queries (and RLS policies) are tenant-scoped.
2. FR-104.2 — Cross-tenant data access is impossible through any API, event consumer, or batch job.

---

## 3. FR-2xx — Data Ingestion & Connector Framework

### FR-201 Connector framework *(Must, P1)*

The system shall provide an extensible connector framework with out-of-the-box connectors: Salesforce, HubSpot, Snowflake (read-only), and manual CSV/XLSX upload.

**Acceptance criteria**
1. FR-201.1 — A Comp Admin can authenticate a Salesforce or HubSpot connector via OAuth 2.0, or a Snowflake connector via key-pair auth, in under 3 minutes.
2. FR-201.2 — Connectors run as scheduled or on-demand sync jobs with visible run history, row counts, and error summaries.
3. FR-201.3 — Connector definitions (source objects, field mappings) are versioned; changes are captured in the audit log.

### FR-202 Field mapping to the canonical data model *(Must, P1)*

Comp Admins shall map source objects (Opportunities, Line Items, Users/custom objects; Snowflake tables/views) to the EIM canonical entities (transactions, payees, territories, custom attributes) without code.

**Acceptance criteria**
1. FR-202.1 — Mapping supports type coercion with validation (e.g. `Close_Date → Date`, `ARR_Amount → Decimal`) and rejects unmappable required fields *before* the first sync runs.
2. FR-202.2 — Arbitrary source fields can be mapped into the transaction `custom_attributes` JSONB without schema migration.

### FR-203 Schema validation and error quarantine *(Must, P1)*

Every sync batch shall be validated record-by-record; invalid records shall be quarantined without failing the batch.

**Acceptance criteria**
1. FR-203.1 — Records failing type/format/range validation land in a reviewable error log with the rejection reason and raw payload; valid records commit.
2. FR-203.2 — A sync run reports counts: total, accepted, quarantined; re-processing quarantined rows after correction is idempotent (no duplicates).
3. FR-203.3 — Source-system snapshots of every ingested record are preserved immutably for auditability.

### FR-204 Incremental sync *(Must, P1)*

Syncs shall pull only changed records.

**Acceptance criteria**
1. FR-204.1 — Incremental pulls use watermark columns (e.g. `LastModifiedDate > last sync timestamp`); watermark state is persisted per connector object.
2. FR-204.2 — Late-arriving updates to periods already processed raise retro-adjustment events (see FR-604) rather than being silently skipped.

### FR-205 High-volume usage/metering ingestion *(Should, P2)*

The platform shall ingest high-volume consumption/metering events from warehouses (and event streams, *Won't P2 / Could P3*) alongside CRM deal data to support PLG/consumption plans.

**Acceptance criteria**
1. FR-205.1 — Usage aggregates (e.g. monthly per-product usage per account) can feed plan components with the same calculation and trace machinery as deal-based components.
2. FR-205.2 — Ingestion throughput supports at least 1M metering rows per tenant-month in batch mode (see NFR-21).

### FR-206 Additional connectors *(Should/Won't per phase)*

- FR-206.1 — ERP read integrations (NetSuite, SAP) for bookings/contracts: *Should, P2*.
- FR-206.2 — HCM/payroll write-back (Workday, ADP): *Could, P3* (P1 covers file export, FR-801).
- FR-206.3 — Bi-directional webhook/event streaming ingestion: *Could, P3*.

---

## 4. FR-3xx — Credit Assignment & Effective-Dated Hierarchy

### FR-301 Credit splits *(Must, P1)*

The system shall support splitting deal credit across multiple payees by percentage.

**Acceptance criteria**
1. FR-301.1 — Splits may total less than, equal to, or greater than 100% (overlay crediting such as Sales Engineers is supported).
2. FR-301.2 — Each split produces its own credited amount and downstream ledger lineage; the statement trace shows every payee sharing credit (e.g. "shared 30% with Alex Rivera").

### FR-302 Effective-dated reporting hierarchy (SCD Type 2) *(Must, P1)*

Payee records, manager relationships, roles, and plan assignments shall be effective-dated so historical rollups remain stable through organizational change.

**Acceptance criteria**
1. FR-302.1 — If Rep A reports to Manager X until May 14 and to Manager Y from May 15, deals closed May 1–14 roll up to X and May 15–31 to Y — automatically, with no manual intervention.
2. FR-302.2 — The hierarchy validator rejects cycles and impossible effective-date overlaps (e.g. A manages B on the same dates B manages A) at save time.
3. FR-302.3 — Hierarchy as-of queries resolve for any historical date (graph traversal over the effective-dated reporting graph).

### FR-303 Automatic hierarchy rollup *(Must, P1)*

100% of each report's credited amounts shall roll up to their managers and directors per the effective-dated hierarchy for manager plan components.

**Acceptance criteria**
1. FR-303.1 — Rollup totals are recomputed when hierarchy corrections arrive and any affected already-published amounts are handled through the true-up mechanism (FR-604), never by mutating locked periods.
2. FR-303.2 — Rollup scope for any manager/payee/period combination is resolvable via graph query in < 2s for hierarchies of 5,000 payees (see NFR-24).

### FR-304 Territory & quota assignment *(Could, P3)*

Territories, quotas, and account-based routing rules feed credit assignment; see FR-1003.

---

## 5. FR-4xx — No-Code Plan & Rule Builder

### FR-401 Plan components and parameters *(Must, P1)*

Comp Admins shall model plans as versioned collections of typed **components** (e.g. Base ARR Commission, Multi-Year Spiff, Q4 Accelerator) with plan-level parameters (e.g. `@Target_Quota`, `@OTE_Variable`, `@Draw_Guarantee`).

**Acceptance criteria**
1. FR-401.1 — Components are strongly typed (Currency, Percent, Decimal, Boolean, Date); type errors are surfaced inline at edit time (real-time AST type-checking).
2. FR-401.2 — Plans are effective-dated and versioned; publishing creates an immutable `plan_version_hash`.

### FR-402 Spreadsheet-style formula grid *(Must, P1)*

The builder shall offer a formula bar and grid familiar to spreadsheet users, with autocomplete tokens: `@` for parameters/upstream nodes and `$` for transaction attributes.

**Acceptance criteria**
1. FR-402.1 — Typing `@` lists plan parameters and upstream node outputs; `$` lists transaction attributes (e.g. `$Deal.Booking_Amount`, `$Deal.Term_Months`).
2. FR-402.2 — Tokens are color-coded by data type; type mismatches (e.g. Date × Currency) are underlined with an inline fix suggestion before the field is left.
3. FR-402.3 — Formulas support filter expressions over credit streams (e.g. `Deals.filter(Stage == 'Closed Won' AND Type IN ('New','Upsell'))`).

### FR-403 Multi-tier rate tables — marginal and cliff *(Must, P1)*

Rate tables shall support **Marginal (progressive split-tier)** and **Retroactive (cliff)** calculation modes.

**Acceptance criteria**
1. FR-403.1 — Mode is toggleable per rate table; the UI renders a visual explanation of how a threshold-crossing dollar is treated in the selected mode.
2. FR-403.2 — Tier contiguity is enforced: no gaps or overlaps are possible (a row's ceiling auto-binds the next row's floor); deleting a middle row heals boundaries.
3. FR-403.3 — Tier floors/ceilings may be expressed as % of quota (`@Target_Quota`) or absolute currency amounts.
4. FR-403.4 — Tiers are importable from CSV.

### FR-404 Stateful accumulation windows *(Must, P1)*

Components shall define accumulation windows (e.g. `SUM(Credited_ARR)` ordered by `Close_Date`) with configurable reset cadence (monthly, quarterly, annual) to drive attainment-based tiers.

**Acceptance criteria**
1. FR-404.1 — Window state is order-dependent and deterministic (see FR-501).
2. FR-404.2 — Window results are exposed to downstream nodes and to the calculation trace.

### FR-405 Protections: draws, caps, clawbacks, holds *(Must, P1)*

Plans shall configure recoverable/non-recoverable draws, payout caps, and clawback windows (e.g. 90 days) as first-class building blocks.

**Acceptance criteria**
1. FR-405.1 — Draw behavior follows the state machine in FR-504.
2. FR-405.2 — Clawback windows trigger on ingested `Cancelled`/`Churned` flags within the configured window (see FR-505).
3. FR-405.3 — Caps (period or annual, by amount or %) truncate payouts and record the capped residual in the trace.

### FR-406 Publish gate with validation *(Must, P1)*

Saving/publishing a plan shall run compile-time validation: dependency-graph cycle detection (Tarjan SCC), schema/type checking, and tier-gap checks.

**Acceptance criteria**
1. FR-406.1 — Circular references (e.g. `Bonus_A → Net_Payout → Bonus_A`) are rejected at save with the exact cycle highlighted in the UI.
2. FR-406.2 — The publish gate displays a cohort impact diff (total payout delta across all assigned payees) before confirmation.
3. FR-406.3 — If a formula change increases cohort payout by more than a configurable threshold (default 10%), an explicit "High Financial Variance" warning requires acknowledgement before publish.

### FR-407 Live sandbox preview *(Must, P1)*

Admins shall preview the effect of edits on a real payee and period in real time, including injecting synthetic test deals, without touching production data or published results.

**Acceptance criteria**
1. FR-407.1 — Preview recalculates a sample rep's deal-by-deal payouts in < 800ms for changes scoped to that rep's sub-graph (see NFR-23).
2. FR-407.2 — Synthetic deals (e.g. "add hypothetical $50k deal on Oct 28") are clearly labeled and never persisted to transactional stores.
3. FR-407.3 — Preview shows delta vs the current published version.

### FR-408 Natural-language plan modeling *(Could, P3)*

Admins may author or adjust plan logic from natural-language prompts (e.g. "create a 1.5x accelerator on net-new ARR above 100% quota with a 60-day clawback"), compiled into the same reviewed artifacts. *AI-generated logic always passes the FR-406 gate and human approval.*

### FR-409 Cohort plan simulation / Monte Carlo *(Won't P1/P2 → Should, P3)*

Finance shall stress-test candidate plans against historical deal distributions (Monte Carlo) to forecast payout liability and edge-case windfalls. See FR-1001.

---

## 6. FR-5xx — Calculation Engine

### FR-501 Deterministic total ordering *(Must, P1)*

Transactions within a payee's window shall be processed in a strict, deterministic sort key before any order-dependent evaluation.

**Acceptance criteria**
1. FR-501.1 — Sort key: `(transaction_date ASC, source_system_timestamp_utc ASC, transaction_uuid ASC)`.
2. FR-501.2 — Re-running any historical calculation (today or during an audit years later) reproduces byte-identical ledger output for identical inputs (given identical artifact versions).

### FR-502 Marginal tier splitting (interval intersection) *(Must, P1)*

When a single transaction pushes cumulative attainment across one or more tier boundaries, the engine shall slice it precisely.

**Acceptance criteria**
1. FR-502.1 — Canonical case: quota \$100,000; tiers 0–100% @ 10%, 100–150% @ 15%, >150% @ 20%; rep at \$90,000 attainment closes a \$70,000 deal → the engine emits 3 atomic slices: \$10,000×10% = \$1,000.00; \$50,000×15% = \$7,500.00; \$10,000×20% = \$2,000.00; total \$10,500.00 — all three slices persisted individually on the statement trace.
2. FR-502.2 — Boundary dollars belong to exactly one tier (interval math `min(new, ceiling) − max(prev, floor)`); no cent is double-counted or dropped.
3. FR-502.3 — Cliff mode instead pays the whole transaction (or window) at the final-attainment tier rate, exactly as configured.

### FR-503 Parallel wavefront execution *(Must, P1)*

The engine shall evaluate payee sub-graphs in topological waves: all leaf payees (ICs) concurrently, then first-line managers, then directors/VPs.

**Acceptance criteria**
1. FR-503.1 — A manager's component never evaluates before all effective-dated downline outputs for the same window are materialized.
2. FR-503.2 — Engine performance meets NFR-20 (500 payees × 100k transactions < 15s).
3. FR-503.3 — Worker failure mid-run resumes or cleanly re-executes the wave without partial ledger commits (atomicity per FR-507).

### FR-504 Draw state machine *(Must, P1)*

Recoverable and non-recoverable draws shall be evaluated as a period-to-period carry-forward state.

**Acceptance criteria**
1. FR-504.1 — When `Earned_Commission < Guaranteed_Draw` and draw is active: advance `Draw − Earned` is paid; net payout = guarantee; (if recoverable) ending deficit carries forward.
2. FR-504.2 — When `Earned_Commission ≥ Guaranteed_Draw`: recovery = `min(Prior_Deficit, Max_Recoverable)` is deducted (per plan rule: excess-over-guarantee or 100% of earned); deficit decreases accordingly.
3. FR-504.3 — Every draw advance/recovery is a distinct ledger entry type (`DRAW_ADVANCE`, `DRAW_RECOVERY`) with full trace.

### FR-505 Clawbacks *(Must, P1)*

When a transaction is flagged Cancelled/Churned within the plan's clawback window, the engine shall generate a negative offsetting entry **in the current open period**, leaving closed periods untouched.

**Acceptance criteria**
1. FR-505.1 — Clawback entries reference the original ledger entries and transaction; the seller statement shows them explicitly labeled.
2. FR-505.2 — Partial refunds/downgrades prorate the clawback amount.

### FR-506 Mid-period proration and plan segmentation *(Must, P1)*

When plan assignments, quotas, or hierarchies change mid-period, the engine shall split the period into sub-segments and route transactions by date.

**Acceptance criteria**
1. FR-506.1 — Prorated quota for a segment = full-period quota × (active calendar days ÷ total calendar days in period).
2. FR-506.2 — Deals before June 15 evaluate against the Mid-Market rate table and roll up to the old manager; deals on/after June 15 use the Enterprise table and new manager.
3. FR-506.3 — Leave-of-absence proration follows the same segmentation.

### FR-507 Atomic, immutable ledger output *(Must, P1)*

Calculation results shall commit as immutable `Commission_Ledger_Entry` rows (partitioned by payout period) inside a single atomic transaction per calculation run, each carrying a compressed JSONB `calculation_trace`.

**Acceptance criteria**
1. FR-507.1 — A run either commits 100% of ledger entries for the period or rolls back completely.
2. FR-507.2 — The trace contains at least: plan version hash, transaction context (raw amount, split %, credited amount), attainment window state (prior/post cumulative and %), all tier slices (amount, rate, payout), final rounded payout, and rounding mode.
3. FR-507.3 — Ledger entries expose `entry_type` ∈ {`CALCULATED`, `MANUAL_OVERRIDE`, `DRAW_ADVANCE`, `DRAW_RECOVERY`, `CLAWBACK`, `PRIOR_PERIOD_TRUEUP`}.

### FR-508 Bi-temporal shadow replay for retroactive changes *(Must, P1)*

Retroactive source changes affecting a **locked** period shall be re-computed by replaying the frozen historical state in an isolated sandbox and diffing.

**Acceptance criteria**
1. FR-508.1 — The engine loads the period's inputs *as of the lock timestamp*, merges the retro change (as of now), re-executes the identical versioned plan, producing a shadow ledger.
2. FR-508.2 — Set-based diff over `(payee_id, transaction_id, plan_component_id)` tuples yields Δ payouts; non-zero deltas emit `PRIOR_PERIOD_TRUEUP` entries in the current open period referencing `origin_period_id`.
3. FR-508.3 — The seller UI shows true-ups with an origin badge and original-vs-recalculated diff (e.g. March \$4,200 → \$5,000; October +\$800).
4. FR-508.4 — Ripple effects on subsequent marginal tiers are captured by full replay — never isolated per-deal deltas.

### FR-509 Incremental recalculation *(Should, P2)*

Interactive recalculations shall skip payees whose (sub-graph AST + input hash) is unchanged — dirty-flag caching — so full-cohort previews scale.

### FR-510 What-if commission estimator *(Should, P2)*

Payees shall select open pipeline opportunities and immediately see the commission impact of deal changes (multi-year term, product bundle) before the deal closes. Runs in the same sandbox machinery as FR-407, payee-scoped.

---

## 7. FR-6xx — Financial Governance, Period Close & Audit

### FR-601 Immutable, detailed audit log for every state change *(Must, P1)*

Every state-changing action — by any actor type (user, service, process, or system) — shall be appended to a tamper-evident, append-only audit log.

**Acceptance criteria**
1. FR-601.1 — Every state-changing action writes a record containing: actor identity and actor type (`USER` / `SERVICE` / `PROCESS` / `SYSTEM`), UTC timestamp, tenant, action type, target entity and record ID, before-state and after-state (JSONB), reason code with mandatory comment for adjustments, correlation ID, source channel (UI / API / event / process), and outcome (success/failure).
2. FR-601.2 — Action types include at least: `PLAN_EDIT`, `PLAN_PUBLISH`, `MANUAL_ADJUSTMENT`, `PERIOD_LOCK`, `PERIOD_REOPEN`, `DISPUTE_RESOLUTION`, `DATA_OVERRIDE`, `CONFIG_CHANGE`, `CONNECTOR_RUN`, `CALCULATION_RUN`, `EXPORT_GENERATION`, `APPROVAL`, `GL_PUSH`, `TRUEUP_POSTING`.
3. FR-601.3 — No user or process can update or delete audit rows (enforced by database privileges, not just application logic).
4. FR-601.4 — **No unaudited state change**: the audit record commits in the same database transaction as the change it describes (transactional outbox — see [architecture](03-architecture-plan.md) §12); failure to write the audit record aborts the business change. No supported code path mutates regulated state without an audit record.

### FR-602 Manual adjustment overrides *(Must, P1)*

Comp Admins/Finance Approvers shall apply manual adjustments (spot bonuses, dispute settlements) to statements while preserving original calculated values.

**Acceptance criteria**
1. FR-602.1 — Overrides are separate ledger entry types linked to the original; the original calculated entry remains visible.
2. FR-602.2 — Dispute-approved adjustments link to the originating dispute ticket ID.

### FR-603 Hard period lock *(Must, P1)*

Finance Approvers shall freeze a payout period after review.

**Acceptance criteria**
1. FR-603.1 — `PERIOD_LOCK` snapshots all underlying calculation inputs and blocks recalculation of the period.
2. FR-603.2 — Locking requires multi-stage sign-off (see FR-605); only Finance Approver can execute the final transition.
3. FR-603.3 — Locking is irreversible except through a documented, audited reopen procedure gated on Finance Approver + System Admin dual approval (*Should, P2*).

### FR-604 True-up queue *(Must, P1)*

Retroactive CRM/ERP changes affecting locked periods shall be automatically routed to a True-Up Queue and applied as prior-period adjustments in the current open period (via FR-508).

**Acceptance criteria**
1. FR-604.1 — Queue items show origin period, affected payees, delta amounts, and status; Finance reviews and approves before ledger posting.
2. FR-604.2 — Approval is a workflow step with audit trail (see FR-621).

### FR-605 Multi-step sign-off workflow *(Must, P1)*

Period close shall follow a configurable approval workflow (e.g. Comp Admin review → Finance Approver approval → Lock) with task assignment, notifications, and SLA visibility.

### FR-610 Audit viewer & export — all audit streams *(Must, P1)*

Admins and auditors shall search **all audit streams** — action audit, event audit, and configuration change audit — by actor, entity, action/event type, configuration category, correlation ID, and date range, and export results (CSV/JSON).

**Acceptance criteria**
1. FR-610.1 — Search results render within the NFR-22 API latency budget for 90-day windows.
2. FR-610.2 — Every search/export action is itself audited (read of audit data is logged, without payload echo).

### FR-611 ASC 606 completeness gate *(Must, P2)* — see FR-908.

### FR-612 Comprehensive event audit *(Must, P1)*

Every event — domain event, process event, and external-system exchange — shall be recorded in an immutable event audit log with full detail.

**Acceptance criteria**
1. FR-612.1 — Every event published to the event backbone is persisted with: event ID and type, topic/partition/offset, producer service, tenant, correlation ID, occurrence timestamp, schema version, full payload (compressed JSONB), and payload hash.
2. FR-612.2 — Every consumed event's processing outcome (success / quarantined / retried / dead-lettered) is recorded with the consumer identity and error detail where applicable.
3. FR-612.3 — Every BPMN process transition (human task claimed/completed, service task, timer, escalation) is audited with actor, from-state, to-state, process instance ID, and business context (period ID, dispute ID, etc.).
4. FR-612.4 — External-system interactions (sync runs, ERP/GL pushes, IdP exchanges) are audited with request/response summaries; credentials and secrets are always redacted.
5. FR-612.5 — Event audit records are searchable by type, tenant, correlation ID, entity, and time range, and exportable (FR-610).

### FR-613 Configuration change audit & versioning *(Must, P1)*

All configuration shall be versioned, and every configuration change shall be audited in detail. Configuration scope includes at minimum: compensation plans and rate tables (plan versions), connector definitions and field mappings, accounting book policies (P2), approval/workflow definitions and SLA settings, roles and permission assignments, tenant settings, notification settings, feature flags and thresholds (e.g. the high-variance warning threshold of FR-406.3), gateway/API policies, and data-validation rules.

**Acceptance criteria**
1. FR-613.1 — Every configuration change records: actor, UTC timestamp, configuration entity and ID, version before and after (full JSONB diff), change reason (mandatory for governed categories), approval reference (process instance ID where an approval workflow applies), and effective dates.
2. FR-613.2 — Governed categories (published plans, accounting policy, RBAC, gateway policies, workflow definitions) require an approval workflow before activation; the approval chain forms part of the audit record.
3. FR-613.3 — Configuration history is browsable ("who changed what, when, why") and any prior version is retrievable; configuration audit records can never be deleted.
4. FR-613.4 — Direct database configuration changes in production are impossible: configuration is mutable only through the audited configuration API path; database privileges deny ad-hoc writes.
5. FR-613.5 — Infrastructure-level configuration that lives in Git (gateway routes, deployment values) is mirrored into the audit log via the GitOps sync watcher, with commit, author, and sync result.

### FR-621 Close & approval process automation *(Must, P1 — realized by the BPMN process layer)*

Period close, approval, true-up approval, and dispute resolution shall be executed as managed business processes (BPMN) with explicit states, timers, and escalation, so audit can reconstruct *who did what, when, in which process step*.

**Acceptance criteria**
1. FR-621.1 — Process state is queryable per period/dispute; every state transition is timestamped and attributed.
2. FR-621.2 — Stalled steps escalate after configurable timers.

---

## 8. FR-7xx — Seller Transparency & Dispute Workflow

### FR-701 Commission statement *(Must, P1)*

Each payee shall see per-period: total payout, quota attainment progress (with next-tier hurdle), component breakdown (base, spiffs, draws/true-ups), and the credited-transaction list.

**Acceptance criteria**
1. FR-701.1 — Statements distinguish open (in-flight) vs locked/final periods.
2. FR-701.2 — Payees can filter transactions by period, deal name, and product type.

### FR-702 Calculation trace drawer *(Must, P1)*

Selecting any deal row opens a trace visualization rendering the stored `calculation_trace`: raw booking → split % → credited amount → quota window position (with tier-boundary ticks) → per-tier slices → final payout.

**Acceptance criteria**
1. FR-702.1 — The three-step layout (Deal Credit & Split → Attainment Window Position → Marginal Tier Slicing) is bound directly to the trace JSON; no runtime recomputation.
2. FR-702.2 — Hovering a tier slice highlights the corresponding attainment-bar segment.
3. FR-702.3 — Splits show co-payees ("shared 30% with Alex Rivera").
4. FR-702.4 — True-up entries render an origin badge and side-by-side original-vs-adjusted diff.

### FR-703 Statement download *(Must, P1)*

Payees can download statements as PDF and CSV, matching on-screen values.

### FR-704 Dispute submission *(Must, P1)*

Payees shall dispute a specific transaction (or the whole period) with structured reason categories: CRM Deal Amount / Credit Split % / Prior Attainment Balance / Rate-Plan Rule, plus notes and file attachments.

**Acceptance criteria**
1. FR-704.1 — The dispute is pre-tagged with `ledger_id`, `transaction_id`, and the disputed trace-node category (`SOURCE_DATA`, `CREDIT_SPLIT`, `WINDOW_STATE`, `PLAN_LOGIC`) for fast RevOps triage.
2. FR-704.2 — Attachments are stored in tenant-scoped object storage with malware scanning (*Should, P2*).

### FR-705 Dispute resolution workflow *(Must, P1)*

Disputes route to a Comp Admin queue with notification, status tracking, approve/reject with comments, and — on approval — one-click adjustment linked to the ticket.

**Acceptance criteria**
1. FR-705.1 — Lifecycle states at least: Submitted → Under Review → Approved/Rejected → Adjusted (on approval), fully auditable.
2. FR-705.2 — SLA timers escalate aged disputes (*Should, P2*).

### FR-706 Manager dashboards *(Should, P2)*

Managers see team attainment, payouts, and dispute status for their effective-dated downline.

### FR-707 CRM-embedded experience *(Should, P2)*

The seller dashboard shall be embeddable in the CRM (iframe/tab) for statements and the what-if estimator (FR-510).

### FR-708 GenAI earnings explainer *(Could, P3)*

An AI assistant on each statement explains payouts in natural language, strictly grounded in the stored calculation trace (no fabricated math), and hands off to the dispute flow.

---

## 9. FR-8xx — Payroll & ERP Export

### FR-801 Payroll file export *(Must, P1)*

After period lock, the system shall generate payroll-ready exports from customizable CSV templates targeting ADP, Workday, and NetSuite journal summaries.

**Acceptance criteria**
1. FR-801.1 — Export contains one net line per payee (by `employee_number`), with currency and totals; negative nets (recoveries) are flagged per template policy.
2. FR-801.2 — Export runs are logged with file hash; re-generation of a locked-period file is byte-identical.

### FR-802 GL journal export *(Must, P2)* — covered functionally by FR-907.

---

## 10. FR-9xx — ASC 606 / IFRS 15 Commission Accounting (Phase 2)

> Depends on Phase 1 locked periods and immutable ledger entries. Accounting standard references: ASC 606 / ASC 340-40, IFRS 15, ASC 830 / IAS 21.

### FR-901 Capitalization classification rules *(Must, P2)*

On period lock, the module shall classify every ledger entry as **Capitalizable** or **Immediate Expense** against configurable accounting policy rules.

**Acceptance criteria**
1. FR-901.1 — Default matrix (all overridable per book): direct AE commissions (new logo/upsell) → capitalize (amortize over ECL or contract term); renewal commissions → capitalize over renewal term when non-commensurate; manager/director overrides → capitalize (toggleable); SE/pooled spiffs → capitalize only if strictly formulaic per deal; fringe benefits & payroll taxes → capitalize at a configurable fringe rate (e.g. 8.5%); practical expedient (≤ 12 months amortization) → expense immediately per ASC 340-40-25-4.
2. FR-901.2 — Classification is executed as versioned, auditable decision logic (decision tables), not hard-coded branches.

### FR-902 Amortization engine *(Must, P2)*

Capitalized costs shall amortize under configurable conventions per accounting book:

1. FR-902.1 — Exact daily straight-line (365/366 day basis).
2. FR-902.2 — Full-month straight-line (full-month or mid-month convention).
3. FR-902.3 — Performance-obligation-weighted (milestone/usage) from ERP revenue events for non-linear delivery.

**Acceptance criteria**
1. FR-902.4 — Sub-cent rounding residuals are absorbed in the final schedule period so Σ amortization ≡ capitalized asset exactly.
2. FR-902.5 — Both individual-contract and portfolio/cohort tracking are supported.

### FR-903 Lifecycle events without mutating closed periods *(Must, P2)*

1. FR-903.1 — **Full churn**: within clawback window → Phase 1 negative ledger entry reduces the asset; remaining unamortized balance is written off via impairment JE in the current open period; future schedule lines are zeroed.
2. FR-903.2 — **Partial downgrade**: configurable per Controller — continue amortizing over remaining life, or recognize pro-rata partial impairment equal to the contraction percentage.
3. FR-903.3 — **Retroactive true-ups**: child amortization stream linked to original contract start; cumulative catch-up entry posts in the period of discovery (e.g. April catch-up for Jan–Mar elapsed days).

### FR-904 Multi-book accounting *(Must, P2)*

Parallel subledgers (e.g. US GAAP parent vs statutory local GAAP with immediate expensing) shall run concurrently over the same ledger dataset, each with own policy, functional currency, conventions, and expedient thresholds.

### FR-905 FX — historical rate lock (ASC 830 / IAS 21) *(Must, P2)*

Deferred acquisition costs are non-monetary assets: capitalized amounts and entire future schedules are locked at the historical spot rate on the capitalization date and never revalued at closing rates.

### FR-906 Deferred cost rollforward report *(Must, P2)*

**Acceptance criteria**
1. FR-906.1 — Every row/period satisfies: `Beginning + Capitalized + True-Ups − Amortization − Impairments = Ending`, with ending split current/non-current (next-12-months reclassification, FR-906.2).
2. FR-906.2 — Monthly automatic ST/LT reclassification journal entry.
3. FR-906.3 — Excel/CSV export with full drill-down (schedule line → asset → ledger entry → transaction → rep) for up to 500k active schedule lines in < 10s (NFR-25).

### FR-907 GL journal generation & push *(Must, P2)*

**Acceptance criteria**
1. FR-907.1 — Standard templates: payroll accrual (expense/payable), capitalization (asset/contra-expense), periodic amortization (expense/accumulated amortization), churn impairment — mapped to customer GL codes with department/cost center and subsidiary dimensions.
2. FR-907.2 — Period lock is blocked if debits ≠ credits for any subsidiary/currency.
3. FR-907.3 — Post-lock adjustments roll forward into the next open period as catch-up/impairment amounts; posted schedule lines are never overwritten.
4. FR-907.4 — Summarized JE push to NetSuite/SAP/Workday with per-batch sync status (PENDING → EXPORTED → SYNCED → FAILED) and retry.

### FR-908 Pre-close completeness gate *(Must, P2)*

Before accounting-period close, the system verifies 100% of locked ledger payouts were classified (capitalized or explicitly expensed); failures block close with itemized exceptions.

### FR-909 Zero-orphan lineage *(Must, P2)*

Every summarized GL line traces via FK chain `schedule_line → asset → ledger_entry → transaction` to the original CRM contract and rep statement — one-click drill-down for auditors.

---

## 11. FR-10xx — Phase 3: SPM & Channel Expansion (summary level)

| ID | Requirement | Priority |
|---|---|---|
| FR-1001 | Predictive Monte Carlo plan simulation: forecast total payout liability and edge-case windfalls against historical deal distributions before rollout | Should, P3 |
| FR-1002 | Territory & Quota (T&Q) planning module: territory design, quota allocation, what-if realignment against the effective-dated hierarchy graph | Should, P3 |
| FR-1003 | Account-based routing rules feeding credit assignment (FR-3xx) from T&Q definitions | Should, P3 |
| FR-1004 | External partner/channel incentive portal: rebate & spiff programs, partner statements, partner self-service disputes | Could, P3 |
| FR-1005 | Channel program administration: program eligibility, claim submission, accrual calculation reusing the core engine | Could, P3 |
| FR-1006 | GenAI plan diagnostics: anomaly detection on plan designs (windfalls, cliffs, gaming vectors) | Could, P3 |

---

## 12. Data model requirements (cross-cutting)

The canonical entities below are mandatory (types per the proposal's schema chapter; enforced by migration tests):

| Entity | Key requirements |
|---|---|
| `payee` | UUID PK; `external_crm_id` indexed; `employee_number` unique (payroll mapping); ISO 4217 base currency; self-referencing manager FK; effective-dated (SCD 2) |
| `plan_assignment` | Effective-dated; `quota_amount NUMERIC(18,4)`; `target_incentive_amount`; `proration_factor NUMERIC(5,4)` default 1.0000 |
| `comp_plan` / `plan_version` | Versioned; immutable published versions with content hash |
| `raw_transaction` | Source-system enum; indexed external deal ID & date; `booking_amount NUMERIC(18,4)`; currency + applied FX rate; `custom_attributes JSONB`; batch lineage |
| `credit_allocation` | Split percentages per payee per transaction; >100% allowed |
| `commission_ledger_entry` | Partitioned by payout period; immutable; typed entry enum (FR-507.3); `calculation_trace JSONB`; `is_locked` |
| `payout_period` | Status lifecycle: OPEN → IN_REVIEW → APPROVED → LOCKED (+ REOPENED, audited) |
| `dispute_ticket` | Linked to transaction/period; structured category; attachments; lifecycle states |
| `audit_log` | Append-only; actor + actor type, correlation ID, source channel, outcome; before/after JSONB; mandatory reason comment for adjustments (FR-601) |
| `event_audit_log` | Append-only; every published/consumed event: event ID/type, topic/partition/offset, full payload (compressed JSONB) + hash + schema version, producer/consumer, correlation ID, outcome (FR-612) |
| `configuration_change` | Versioned configuration registry history: config entity/ID, version before/after (JSONB diff), actor, reason, approval reference, effective dates (FR-613) |
| `transactional_outbox` | Guarantees business write + audit record + event emission commit atomically (FR-601.4) |
| `accounting_book_policy` (P2) | Per-book conventions, currencies, expedient thresholds, fringe rate |
| `capitalized_cost_asset` (P2) | FK to source ledger entry; contract linkage; historical FX lock; status enum |
| `amortization_schedule_line` (P2) | Per accounting period; scheduled/catch-up/impairment amounts; ST/LT balances; posted flag |
| `gl_journal_entry_line` (P2) | Balanced DR/CR in functional currency; GL account, cost center, subsidiary; ERP sync status |

**Graph model (effective-dated)** — the reporting hierarchy, credit-split relations, and plan-component dependency graph shall additionally be maintained as a property graph in PostgreSQL (AGE) to serve rollup resolution (FR-303), cycle detection (FR-406), and future T&Q traversal (FR-1002).

---

## 13. Traceability matrix

| FR area | Source epic / user story (proposal) | Primary architecture components |
|---|---|---|
| FR-1xx | PRD §5 NFRs (SSO/RBAC/RLS) | APISIX gateway, Keycloak, PostgreSQL RLS |
| FR-2xx | Epic 1 — US 1.1 | `eim-connectors`, Kafka, PostgreSQL, object storage |
| FR-3xx | Epic 1 — US 1.2 | `eim-core-api`, AGE graph, calc engine |
| FR-4xx | Epic 2 — US 2.1/2.2; UX wireframes Pt. 1 | `eim-web` plan builder, `eim-core-api`, Drools/DMN compile pipeline |
| FR-5xx | PRD §3 engine requirements; DAG engine chapter | `eim-calc-engine`, Drools rules, PostgreSQL ledger |
| FR-6xx | Epic 3 — US 3.1/3.2 | Kogito BPMN (close/approval/true-up), audit plane — outbox + interceptors ([architecture](03-architecture-plan.md) §12), PostgreSQL audit stores |
| FR-7xx | Epic 4 — US 4.1/4.2; UX wireframes Pt. 2 | `eim-web` statement portal, `eim-core-api` |
| FR-8xx | Epic 3 (payroll export) | `eim-core-api` export service |
| FR-9xx | ASC 606 module specification (Phase 2) | DMN classification, Kogito close process, PG accounting schema |
| FR-10xx | Business proposal §6 Phase 3 | P3 components (T&Q, partner portal, simulation) |

---

*End of functional requirements specification.*
