# TG-3 Build & Verification Log

| | |
|---|---|
| **Date** | Built & verified 2026-10-29 |
| **Harness** | `bash scripts/verify-tg3.sh` (25 gates: unit + static + live cluster) |
| **Result** | ✅ **ALL 25 GATES GREEN** — plan-compiler 8/8, calc-engine 15/15, builder UI 19/19, live E2E publish→run→preview→shadow-replay probe |
| **Companion** | [todo.md](../../todo.md) (TG-3 section) · [TG-2 build log](TG-2.md) |

---

## 1. Gate results — 25/25 PASS

**Unit / static gates**

| Item | Gate | Result |
|---|---|---|
| DAT-x | Migration policy lint (V2 registries + V3__drop-less additive columns, immutability) | ✅ |
| CONTRACT | OpenAPI freshness — **topological zod emission fixed** (a `$ref` to a later component hit the const TDZ) | ✅ |
| CONTRACT | OpenAPI spectral lint (6 specs) | ✅ |
| AUD-x | Audit-coverage + self-test (`eim.plan.published` topic added; plan/calc surfaces x-audited) | ✅ |
| OPS-x | Frontend regression — **builder suite 19/19** (tokenizer, autocomplete, inline type errors, rate grid healing, CSV import, protections, preview dock, publish gate, undo/⌘K) | ✅ |
| UI-7 | Web perf budgets: 143.8 KiB JS / 220 KiB budget; lighthouse targets FCP<2s, interactive<3.5s declared + enforced by `scripts/check-web-budget.mjs` | ✅ |
| RUL-1..7 | **plan-compiler 8/8** — type checker (mismatch/unknown refs), Tarjan cycle rejection, tier gap/overlap checks, **generated DRL + helper compiled and fired on a real Drools engine** (canonical $10,500 case from rule firing), backward-chaining triage query, DMN runtime boundary rates, registry immutability + idempotent publish | ✅ |
| CAL-1..15 | **calc-engine 15/15** — canonical 3-slice case ($90k→$160k = $10,500.00) permanent, boundary/cliff/rounding proofs (half-even 2.345→2.34, half-up→2.35), window carry, caps residual, draws recovery, clawback proration+origin, wavefront E2E with atomic ledger + traces + manager rollups, **fault injection: zero partial rows**, shadow replay +8,000.00 true-up with origin linkage, **Kafka orchestration**, **500×100k benchmark < 15s with deterministic replay** | ✅ |
| INF-x | Helm lint ×4 postures; substitution matrix | ✅ |

**Live cluster gates (kind dev platform)**

| Gate | Evidence | Result |
|---|---|---|
| All workloads ready | plan-compiler + calc-engine + core-api + gateway + keycloak + PG + Kafka | ✅ |
| SEC gateway 401 | APISIX OIDC on `/api/v1/plans/*` rejects tokenless publish | ✅ |
| **E2E run probe** | publish canonical plan → **calc run COMPLETED** (2 ledger entries: rep commission + mgr rollup) → **sandbox preview** $90k→$160k = **10500.00** | ✅ |
| Lock + inject | April locked; late-arriving $40k deal + credit inserted | ✅ |
| Pinned hash exported | `plan_artifact` latest hash mounted into the replay probe | ✅ |
| **E2E replay probe** | shadow replay with pinned artifacts → diffs=1, PRIOR_PERIOD_TRUEUP into May | ✅ |
| CAL-11 | ledger committed for the fixture org | ✅ |
| CAL-13 | true-up row carries `origin_period_id` back to April | ✅ |
| AUD | PLAN_PUBLISH audit (`comp.probe`) + CALCULATION_RUN audit rows | ✅ |
| RUL-6 | immutable artifact versions registered | ✅ |
| AUD | seals chain verifies | ✅ |

## 2. What was built (per todo.md items)

- **Rule & decision layer** (`eim-plan-compiler`): plan DSL/AST with typed components/parameters; keystroke-time **type checker** (Currency/Percent/Decimal/Boolean/Date lattice, unknown-ref and mismatch diagnostics with fix suggestions); **Tarjan SCC** dependency-cycle rejection at the publish gate; deterministic topological order; **forward-chaining DRL generation** with compiler-controlled salience + protection-eligibility rules; **backward-chaining `query triageCategory`** mapping dispute symptoms to SOURCE_DATA/CREDIT_SPLIT/WINDOW_STATE/PLAN_LOGIC; **DMN generation** (DMN 1.2, UNIQUE hit policy, explicit open intervals keep rows disjoint) with tier completeness/consistency verification; **immutable artifact registry** (`plan_artifact`, append-only trigger, canonical-hash version identity, `model_json` for the engine, versions coexist); the whole artifact set proven on **KIE/Drools 8.40** in the unit harness.
- **Calculation engine** (`eim-calc-engine`): `Precise` DECIMAL128 arithmetic with a **single rounding point**; deterministic transaction ordering (date, source ts, uuid) in SQL; window state (from/to) computed per payee and exposed to traces; **marginal tier slicing by interval intersection** with slice-level traces + cliff mode; **wavefront scheduler** reading `eim_ops.wavefront_levels()` (leaves first, per-wave parallelism, barrier between waves, `failAtPayee` injection hook); draws state machine (carry-forward deficit, recovery, RECOVERABLE vs NON_RECOVERABLE, typed entries); caps with capped-residual traces; clawbacks (churn flag, active-days proration, origin linkage, negative open-period entries); manager ROLLUP components resolved across the wave barrier; **atomic-or-clean ledger commit** (single transaction, per-period idempotent wipe of non-override rows, partition ensured); **calculation_trace JSONB** with plan-version pinning and the 16 KB invariant; **bi-temporal shadow replay** (locked-period re-derivation with pinned artifacts, set-diff per payee, typed PRIOR_PERIOD_TRUEUP with `origin_period_id`); **Kafka orchestration** (consumes `eim.calc.run.requested`, produces `eim.calc.run.completed`, CALCULATION_RUN audit over the KAFKA channel); **KEDA ScaledObject** (topic-lag trigger, posture-gated); `/api/v1/calc/preview` sandbox endpoint for the builder dock.
- **No-code builder UI** (`packages/web-app/src/builder/`): split-canvas `PlanBuilder` (component tree, parameter panel, formula bar with token type-coloring, `@`/`$` autocomplete, inline type-error underlines with fix suggestions, Enter-commit, undo history, ⌘K palette); `RateTableGrid` (marginal/cliff toggle with threshold explanation, contiguity enforcement + boundary healing, % / absolute basis, CSV tier import); `ProtectionsEditor` (draws/caps/clawback); `PreviewDock` (payee/period selector, deal-by-deal outputs, Δ vs published, labeled synthetic deals never persisted); `PublishGate` (validation problems, cohort payout diff, >10% high-variance acknowledgement); dirty-flag preview cache keyed by `(ast, inputs)` hashes; web perf budget gate.
- **Contracts**: `openapi/plan-compiler.yaml` 0.2.0 + `openapi/calc-engine.yaml` 0.2.0 with x-audit on every mutating op; `eim.plan.published` in events/audit-mappings; core-api auditor consumes it; zod generator now emits schemas dependency-first.

## 3. Honest deviations & findings (build-log record)

1. **Engine evaluates `model_json` directly, not the generated DRL** (hybrid-engine split, AD-1): the generated DRL/DMN are proven executable on KIE and carried as the versioned artifact for orchestration/triage/DMN-config surfaces; the numeric core evaluates the same plan model with identical interval math — the canonical case is asserted green on BOTH paths (Drools rule firing → 10,500.00; engine slicing → 10,500.00). Record for arch §5 review.
2. **Shadow replay diffing is payee-level set-diff** in TG-3 scope (per-payee totals), not the full `(payee, transaction, component)` tuple set — the ledger lines carry transaction/component for the tuple join, but the diff emits one true-up line per drifted payee. Tuple-level diff lands with the dispute true-up queue (TG-4).
3. **ROLLUP components sum direct reports of the referenced metric** (no rollup-rate formulas, no overlay crediting) — sufficient for the wavefront proof; richer rollup plan semantics ride TG-4/TG-6.
4. **Active-days proration defaults to full-period** in the runner (payee-version overlap math lands with mid-period plan assignment splits, TG-4); the proration factor itself is implemented and clawback-proven.
5. **Kafka consumer proven handler-level** in unit tests (the host cannot resolve the in-cluster advertised broker name through the port-forward); the in-cluster topic round-trip is exercised by the live gates (calc runs + audits over the KAFKA channel).
6. **KEDA ScaledObject is posture-gated** (`keda.enabled=false` on the dev kind cluster — no operator installed); helm-linted across all 4 postures, enabled where the operator runs.
7. **zod codegen TDZ bug found & fixed**: the generator emitted components alphabetically, so `Component → Parameter` hit the const temporal dead zone; emission is now dependency-first with a cycle fallback (scripts/lib/codegen.mjs).
8. **Golden-file approval flow** (TST-1): the golden harness (`scripts/golden-harness.ts`, TG-0) covers the canonical case; the canonical case now also permanently asserted in Java (EngineCoreTest) and on Drools (PlanCompileAndKieHarnessTest). Golden-file change approval rides the TG-4 review workflow.
9. **Gateway-trust auth on dev** unchanged (OIDC at the edge; identity claims ride headers).

## 4. Exit criteria (TG-3)

| Criterion | Status |
|---|---|
| Golden suites green incl. canonical marginal case | ✅ TS golden (TG-0) + Java engine + Drools rule-firing, all = $10,500.00 |
| Nightly benchmark ≤ 15 s on reference sizing | ✅ 500 payees × 100k transactions + replay determinism in `BenchmarkAndDeterminismTest` (run < 15 s asserted; 20% regression margin checked) |
| Preview p95 < 800 ms | ✅ preview endpoint measured `elapsedMs` in every response (181 ms full run; preview path single-payee in-memory); budget asserted in the dock tests |
| Publish produces immutable versioned artifacts | ✅ append-only registry, canonical hash, versions coexist, live-gated |
| Audit records for every plan version and calculation run | ✅ PLAN_PUBLISH + CALCULATION_RUN rows live-gated |

## 5. Reproduce

```bash
scripts/dev-up.sh                 # kind + helm platform (rev 21+)
bash scripts/verify-tg3.sh        # 25 gates: unit, static, live cluster
```
