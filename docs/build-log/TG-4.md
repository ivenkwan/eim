# TG-4 Build & Verification Log

| | |
|---|---|
| **Date** | Built & verified 2026-10-30 |
| **Harness** | `bash scripts/verify-tg4.sh` (19 gates: unit + static + live cluster) |
| **Result** | ✅ **ALL 19 GATES GREEN** (two consecutive clean runs) — core-api 25/25, process 4/4, calc-engine 15/15, portal UI (incl. axe-core) green, full monthly-cycle E2E probe through the gateway |
| **Companion** | [todo.md](../../todo.md) (TG-4 section) · [TG-3 build log](TG-3.md) |

---

## 1. Gate results — 19/19 PASS

**Unit / static gates**

| Item | Gate | Result |
|---|---|---|
| DAT-x | Migration policy lint (V10 governance tables additive; expand/contract) | ✅ |
| CONTRACT | OpenAPI freshness (client + zod regen; core-api + process specs extended) | ✅ |
| CONTRACT | OpenAPI spectral lint | ✅ |
| AUD-x | Audit-coverage + self-test (all new mutating ops x-audited) | ✅ |
| OPS-x | Frontend regression incl. portal suite + axe-core WCAG 2.1 AA | ✅ |
| GOV/SEL | **core-api 25/25** — overrides (original preserved, reason mandatory, dispute link, locked-period refusal), audit viewer (3-stream search + CSV + audited reads), exports (3 templates, one net line per employee_number, negative-net flag on a seeded net-negative payee, byte-identical hash regen, EXPORT_GENERATION audit), statements (RLS-scoped overview, trace payload, CSV matching screen, foreign-payee isolation), disputes (submit → pre-tag → resolve with mandatory comment → 409 on double-resolve) | ✅ |
| GOV-2/3 | **process 4/4** — period-close: gates (completeness tenant-scoped, balance) → review → SoD (same actor cannot review+sign; wrong role 403) → PERIOD_LOCK (status LOCKED, partition read-only — direct write rejected) → export handoff → COMPLETED, every transition audited; completeness gate blocks a payee without a CALCULATED entry; true-up queue → Finance APPROVE posts PRIOR_PERIOD_TRUEUP with origin linkage | ✅ |
| REG | **calc-engine 15/15** — shadow replay now routes diffs into the Finance review queue (queue assert) with the direct-post engine path still regression-covered; split_pct semantics unified as a fraction | ✅ |
| INF-x | Helm lint ×4 postures; substitution matrix | ✅ |

**Live cluster gates (kind dev platform)**

| Gate | Evidence | Result |
|---|---|---|
| All workloads ready | core-api + process + calc + plan-compiler + gateway + keycloak + PG + Kafka | ✅ |
| SEC gateway 401 | APISIX OIDC on `/api/v1/disputes` rejects tokenless submit | ✅ |
| **Monthly-cycle E2E** (`scripts/probe-tg4.sh`) | **ingest** (CSV through gateway) → **publish + calculate** → **dispute** (pre-tagged SOURCE_DATA) → **resolve** → **override** (original 19500.0000 preserved) → **period close** (review → SoD 403 on same actor → sign-off → lock → handoff → COMPLETED) → **payroll export** (byte-identical hash across regens from the locked period) → **statement** (LOCKED state, post-override total on screen) → **retro CRM change after lock** (late-arriving April deal, `lateArriving:1`) → **shadow replay** (diff) → **true-up queue → Finance APPROVE → POSTED** | ✅ |
| AUD | correlation-id chain across audit streams for the cycle tenant | ✅ |
| EXIT | accounting identity: ledger total = per-payee export total | ✅ |
| AUD | seals verify for completed days **+ tamper detection positive control** (the GENESIS tamper row fails verification on purpose) | ✅ |

## 2. What was built (per todo.md items)

- **GOV-1 Manual overrides** (`core-api` `OverrideResource` + V10 `manual_override`): separate MANUAL_OVERRIDE ledger entry preserves the CALCULATED original; reason code + comment mandatory; optional dispute link; locked periods refuse new overrides.
- **GOV-2 period-close process** (`process` svc `ProcessEngine` + `PeriodCloseResource`, V2 `process_instance`/`process_task`): durable task graph (review → sign-off → lock → export handoff), completeness gate (every open payee has a CALCULATED entry, tenant-scoped), balance gate, SoD (task assignee-role + reviewer≠signer), PERIOD_LOCK service task takes the input snapshot and makes the partition read-only, stall-detection endpoint for the Jobs Service posture, every transition audited through the shared plane (`ProcessAudit`).
- **GOV-3 True-up queue + review** (`trueup_review` table + `TrueupReviewResource`): shadow-replay diffs enqueue (calc-engine `enqueueForReview`); Finance reviews a diff report, APPROVE/REJECT with mandatory note; approval posts the typed PRIOR_PERIOD_TRUEUP entry into the open period with `origin_period_id`.
- **GOV-4 Audit viewer** (`AuditViewerResource`): searches actions (audit_log) and events (event_audit_log) streams by actor/entity/actionType/correlationId/date range, JSON + CSV export, and every search is itself audited (APPROVAL row carrying the query shape).
- **GOV-5 Retro-change routing regression**: the cycle probe ingests a late-arriving April deal AFTER the lock (connector flags `lateArriving`, `trueup.required` signal) and the replay diff lands in the Finance queue — proven live end-to-end.
- **GOV-6 Degradation**: statements are read-only ledger queries served independently of the engine (the probe verifies the statement after the engine is out of the request path); ingestion buffers by design (connectors commit rows + outbox regardless of engine availability — the late-arriving ingest during the lock demonstrates the buffered-retro path).
- **SEL-1 Statement overview** (`StatementResource` + portal `StatementView`): RLS-scoped, total payout, attainment thermometer with tier ticks + next-tier hurdle, component breakdown incl. negative entries, open/locked state.
- **SEL-2 Trace drawer**: bound directly to the ledger JSONB trace — 3-step layout (split → window position with tier ticks → marginal slice cards), hover slice↔bar linkage, true-up origin badge, co-payee note slot; API `/me/statement/trace/{ledgerId}`.
- **SEL-3 Download**: `/me/statement/download` CSV byte-matches the on-screen values (total + every component row).
- **SEL-4 Dispute submission** (`DisputeResource` + portal `DisputeForm`): the four structured categories, symptom picker pre-tags via the backward-chaining triage table (mirrors the DRL query), notes + attachment names, ledger/transaction reference carried.
- **SEL-5 Dispute lifecycle**: admin queue, resolve with mandatory comment (APPROVE/REJECT), double-resolve 409, submit+resolve audited, one-click adjustment = override linked to the ticket (probe step 3→4).
- **SEL-6 Accessibility**: vitest-axe CI on the seller surfaces (3 suites, zero violations), `tabular-nums` on all money tables, negative semantics via sign + ▼ marker (never color alone) with unit tests.
- **EXP-1 Payroll export** (`ExportResource` + V10 `export_run`): ADP / Workday / NetSuite-journal CSV templates, one net line per payee by `employee_number` summing all payout entry types, negative-net flag (proven on a seeded net-negative payee), sha256 file-hash logged per run, byte-identical regeneration for locked periods (probe asserts hash equality across two generations).
- **EXP-2**: every generation audited `EXPORT_GENERATION` with the hash as the record id.
- **Infrastructure**: gateway OIDC routes for `/api/v1/process/*` + `/api/v1/disputes`(+`/*`); split_pct semantics unified (fraction) across connectors→engine; dev `authMode=header` pinned in dev-up so core-api's `@RolesAllowed` parity holds in both postures.

## 3. Honest deviations & findings (build-log record)

1. **Process engine is a durable home-grown state machine** (process_instance/process_task tables with sequential task promotion), not the Kogito runtime — Kogito binds the same shapes in the staging/prod profile. Every transition is audited; the SoD and lock semantics are fully enforced and live-proven.
2. **Role enforcement on new admin endpoints uses ctx-role deny checks** (the established core-api pattern) instead of `@RolesAllowed` annotations, because the dev gateway-trust posture has no Quarkus-visible identity — behavior identical in both postures and unit-proven.
3. **True-up posting diffing is payee-level** (same as TG-3) — the queue report carries component detail; tuple-level diff rides TG-5 hardening.
4. **Deletion-pinned FKs on the shared dev DB**: GraphProjectionTest's global clean-slate now also clears TG-4 governance tables (documented in the test).
5. **Seals gate strengthened**: completed days must verify AND the deliberate GENESIS tamper row must fail (positive control proving detection), instead of a blanket `bool_and` that the tamper row would fail.
6. **split_pct contract fixed (real defect found by the cycle probe)**: connectors store a fraction (1.0 = 100%) while the engine divided by 100 — credited amounts were 100× small. Unified as fraction; engine tests updated; the cycle probe's override/original numbers now hold exactly.
7. **Statements-as-degradation-evidence**: NFR-43's full chaos drill (kill the engine, verify continued reads) rides TG-5 hardening; TG-4 proves the read-only independence structurally (no engine call in the statement path).

## 4. Exit criteria (TG-4)

| Criterion | Status |
|---|---|
| Full monthly-cycle E2E — ingest → calculate → review → sign-off → lock → payroll export → statement → dispute → true-up | ✅ cycle probe green end-to-end through the gateway with a client-credentials token |
| Unbroken correlation-ID trace | ✅ correlation chain gate (audit rows carry correlation ids across streams) |
| Complete audit records across all three streams | ✅ action + event streams gated; config stream covered by plan/config tollgates |
| Accounting identity of the demo ledger verified | ✅ ledger total = per-payee export total gate |

## 5. Reproduce

```bash
scripts/dev-up.sh                 # kind + helm platform (authMode=header pinned)
bash scripts/verify-tg4.sh        # 19 gates: unit, static, live monthly-cycle E2E
```
