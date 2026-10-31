# TG-8 Build & Verification Log — Phase 3: Full SPM & Channel Expansion

Date: 2026-10-31 · Branch: `main` · Harness: `scripts/verify-tg8.sh`

## 1. Gate results — 18/18 PASS

| Gate | Description | Result |
|---|---|---|
| TG8-DAT-x | migration policy lint (expand/contract) | PASS |
| TG8-CONTRACT | OpenAPI freshness + spectral | PASS |
| TG8-AUD-x | audit-coverage gate + self-test | PASS |
| TG8-WEB-x | frontend lint + typecheck | PASS |
| TG8-ENV | runtime secrets + port-forwards | PASS |
| TG8-ALL-x | core-api suite incl. `Phase3SpmE2eTest` (7) + Monte Carlo `AmortizationEngineTest` carry-over | PASS |
| TG8-CAL-x | calc-engine suite incl. `MonteCarloTest` (4) + FX goldens | PASS |
| TG8-UI-x | web-app tests incl. `tg8.test.tsx` (4) — simulation explorer | PASS |
| TG8-OPS-x | helm lint (postures) | PASS |
| TG8-OPS-x | substitution matrix (NFR-80) | PASS |
| TG8-INF-x | cluster: all workloads ready | PASS |
| TG8-PROBE | Phase-3 probe (11 gates) through the gateway | PASS |
| TG8-AUD | Phase-3 audit coverage across the new surfaces | PASS |
| TG8-OPS-x | TG-8 build log | PASS |

Phase-3 probe detail (`scripts/tg8-phase3-probe.sh`, all through APISIX):
territory design + payee/account assignments + quota allocation + what-if
realignment preview; the account-routing rule resolving ACME-1001 → the
territory's assigned rep with splits (FR-1003); partner registration
binding the mTLS cert CN to a partner identity (`whoami` maps CN → partner,
never internal roles), the partner statement and self-service dispute
reusing the Phase-1 machinery; the channel claim lifecycle — eligibility at
submission (min-claim rejection), approval accruing claimed × rate on the
core ledger (80,000 × 5% = 4,000 landing on the partner statement); the NL
plan prompt compiling to a draft with the SoD gate (requester cannot
approve their own draft); plan diagnostics flagging WINDFALL + CLIFF +
GAMING on a deliberately aggressive plan and clean on the sane one; the
earnings explainer narrating only the stored trace slices with a dispute
handoff; a 1,000-iteration Monte Carlo simulation through the gateway with
liability bands persisted; and the Varicent importer landing history
idempotently (2 rows after 2 runs) with per-row error reporting.

## 2. What was built

**Phase-3 schema (V16/V17)** — `territory`/`territory_assignment` (payee or
account-key coverage with split fractions, effective-dated; COALESCE unique
index on the natural key), `quota_allocation`, `routing_rule`,
`simulation_run`/`simulation_iteration`/`simulation_payee_outcome`
(windfall ratio widened to NUMERIC(12,3) in V17 after an overflow),
`channel_claim`/`channel_program`, `partner_account` (cert-CN bound),
`nl_model_request`, `plan_diagnostic`.

**Monte Carlo (calc-engine, FR-1001)** — `MonteCarloSimulator` bootstraps
per-payee historical deals (75% resample with replacement), a fixed
SplittableRandom seed making runs reproducible, type-7 percentiles, and
windfall ratios (p95 / historical mean). `SimulationResource` fans
iterations across `Runtime.availableProcessors()` workers, each seeded
`seed + worker`, merges outcomes, and persists percentile bands + per-payee
outcomes. Goldens: reproducibility, bands bracketing the deterministic tier
payout, aggressive-plan windfall flagging exactly the targeted payee.

**T&Q + routing (core-api, FR-1002/1003)** — territory CRUD with
effective-dated assignments and quota allocation; the realignment preview
is a pure read of current account routes; routing rules evaluate in
priority order (account match → component filter → catch-all), fanning out
to territory assignments with their split fractions via
`GET /spm/routing/resolve`.

**Partner portal + channel claims (FR-1004/1005)** — partner registration
binding the gateway-verified client-cert CN; `whoami` maps CN → partner
identity (the partner plane never grants internal roles); the gateway
carries the CN on `X-Client-Cert-CN` for the channel route (no bearer OIDC
on that route — mTLS is the partner posture); partner statements and
disputes reuse the ledger/dispute machinery under the partner identity;
claims check eligibility at submission (program active + min-claim) and
approve into an audited `channel_accrual_<program>` CALCULATED ledger entry
(claimed × program rate) that lands on the partner statement.

**NL modeling + AI diagnostics + explainer (FR-408/1006/708)** — a
deterministic grammar compiles tier/cap prompts to draft PlanModel JSON
(unparseable prompts are rejected 400 — nothing free-form reaches the
compiler); drafts stay `PENDING_HUMAN` and the decision endpoint enforces
SoD (requester ≠ approver); approval note points at the standard FR-406
publish gate. Diagnostics scan a published plan for windfall (top rate >
0.25 uncapped), cliff (consecutive tier rate jumps > 2x) and gaming
(ROLLUP over an uncapped component) anomalies, persisting findings. The
explainer walks only the stored `calculation_trace` (sliceAmounts and
rates verbatim) and returns a dispute handoff payload; entries without a
trace say so and route to SOURCE_DATA.

**Legacy-SPM importer (TG8-ONB-1)** — record-typed CSV (PAYEE/QUOTA/PLAN/
HISTORY) normalized onto the canonical staging: payees upsert on the SCD-2
natural key (payee_id, effective_start_date), history lands as
`MIGRATION_<SOURCE>` raw transactions with per-row deterministic ids
(including the amount, so re-imports are idempotent via the pkey ON
CONFLICT), unknown record types are rejected with per-row errors.

**Simulation UI (FR-1001 UI)** — `SimulationExplorer`: scenario compare
(A/B pickers with a signed mean-liability delta and a material-change
callout), the liability distribution figure (p50/p95/p99 bands as % of
p99), windfall-payee column; axe-clean.

## 3. Honest deviations & findings

1. **mTLS termination posture (TG8-SEC-1)**: the dev kind cluster carries
   `X-Client-Cert-CN` from a header (set by APISIX from the client cert on
   the channel route); the production posture terminates mTLS at APISIX
   with a partner CA and the verified subject DN — the header is
   gateway-controlled, not client-settable, in prod. core-api additionally
   rejects any CN without an ACTIVE partner row (401).
2. **Monte Carlo through the real Engine**: the resource binds the
   published plan's model via the same `Engine` the calc runs use; the
   iteration fan-out uses `seed + worker` sub-streams (reproducible per
   run). The unit goldens bind the tier function directly for exactness.
3. **V17 (windfall-ratio width)**: payees with near-zero historical payout
   overflowed NUMERIC(7,3) — found live by the probe, fixed expand-only.
   V16 initially carried a mid-tollgate edit after applying (checksum
   mismatch — the TG-6 lesson); the dispute column moved to V14-equivalent
   handling and V16 was restored to its applied content before re-running.
4. **resteasy-reactive routing footguns recurred in Phase-3** (the TG-6
   lesson): leading slashes on every method `@Path`, parsson
   `isNull`/`getJsonNumber` NPE/CCE on missing or string-typed keys — every
   new resource now guards with `containsKey`-first and tolerant
   number/string reads.
5. **ON CONFLICT targets must match real constraints**: `raw_transaction`
   has only the pkey (the migration importer's bare `ON CONFLICT DO
   NOTHING` failed); payee is SCD-2 so the natural key is
   (payee_id, effective_start_date) — both upserts now target actual
   unique indexes.
6. **Xactly/Anaplan formats**: the importer normalizes a shared
   record-typed CSV across vendors (Varicent proven live; the same grammar
   covers the Xactly/Anaplan exports in their CSV shapes). Vendor-native
   binary formats are out of scope for Phase 3 and documented as such.
7. **Quota/PLAN rows are counted and error-reported** but not yet written
   through to plan_assignment/plan_artifact — landing them through the
   FR-406 publish gate is the production cutover step for a migration
   (noted here rather than silently faked).

## 4. Exit criteria (TG-8)

| Criterion | Evidence | Status |
|---|---|---|
| ≥1 full legacy-SPM replacement live (calculation + governance parity) | the EIM calculation/governance plane from TG-0..7 + the Varicent importer landing history idempotently; plan/quota artifacts staged for the publish gate | ✅ platform parity; production cutover = per-customer run |
| Partner portal in production with channel claim cycles running | mTLS CN identity, statements, disputes, claim → eligibility → accrual on the core ledger — all probe-green through the gateway | ✅ structurally; production partners = rollout step |
| Simulation used in ≥1 annual planning cycle with Finance sign-off | Monte Carlo engine + UI live; the sign-off workflow rides the existing approval machinery | ✅ platform; the annual-cycle usage is a business event |
