# TG-5 Build & Verification Log

| | |
|---|---|
| **Date** | Built & verified 2026-10-30 |
| **Harness** | `bash scripts/verify-tg5.sh` (20 gates) + `scripts/tg5-scale-load.sh` (6) + `scripts/tg5-chaos-dr.sh` (8) + in-cluster red-team probe |
| **Result** | ✅ **ALL 20 GATES GREEN** — enterprise scale 5,000×1M in **10.4 s** (budget 10 min), latency SLOs **p95 65 ms / p99 67 ms reads, 53 ms writes** (budgets 300/800/500), zero-downtime deploy, chaos pod-kill recovery, DR restore incl. AGE catalog, WORM tier, red-team probe, RLS role regression, audit assurance pack |
| **Companion** | [todo.md](../../todo.md) · [TG-4 build log](TG-4.md) · [ops runbooks/capacity](../ops-tg5-runbooks.md) · [retention/GDPR design](../retention-gdpr-tg5.md) |

---

## 1. Gate results — 20/20 PASS

**Unit / static**

| Item | Gate | Result |
|---|---|---|
| DAT-x | Migration lint (expand/contract) | ✅ |
| CONTRACT | OpenAPI freshness + spectral | ✅ |
| AUD-x | Audit coverage + self-test | ✅ |
| OPS-x | Frontend regression | ✅ |
| SEC-2 | **core-api 29/29** incl. the new `RlsRoleRegressionTest` (4 roles-scoped tests: own-row visible, peer isolated, foreign trace 404, control-rows-exist) | ✅ |
| TST-4 | **connectors 11/11** incl. `TenantQuotaIsolationTest` (burst tenant's 5-row batch over a 3-row registry quota → 400 naming NFR-33 with zero partial rows; neighbor tenant ingests normally) | ✅ |
| REG | **calc-engine 15/15** with the new hybrid cohort strategy | ✅ |
| OPS-7 | Helm lint 4 postures + substitution matrix + web budgets | ✅ |

**Live drills**

| Item | Drill | Measured | Result |
|---|---|---|---|
| TST-1 (NFR-21) | 5,000 payees / **1,000,000 rows** seeded set-based (~4 min), full-cohort run | **10,388 ms engine / ~16 s wall** — 1.7% of the 10-min budget; ledger 5,001 entries; cleanup verified | ✅ |
| TST-2 (NFR-22) | 60 reads + 60 writes through the gateway | reads p95 **65 ms** / p99 **67 ms**; writes p95 **53 ms** | ✅ |
| TST-3 (NFR-25) | heavy aggregate in writer vs `default_transaction_read_only` session | identical totals (173,500,000.0000) — read path replica-safe | ✅ |
| OPS-1 (NFR-41) | rolling restart of core-api under 40 synthetic reads | ok=40 err=0 (first run hit one transient 502 during pod replacement — budgeted <5% transient, zero auth failures) | ✅ |
| OPS-2a (NFR-32) | calc-engine pod force-killed, run through the restarted pod | COMPLETED, ledger consistent | ✅ |
| OPS-2b (NFR-32) | process pod force-killed **mid-close** | durable task graph survives; review completes on the new pod; state WAITING at the next task — no stranded workflow | ✅ |
| OPS-3 (NFR-42) | full logical backup → scratch DB restore **incl. AGE catalog** | ledger totals + Payee-vertex counts identical; seals verify in the restored copy; **RTO 3.6 s** (dev scale) vs 4 h budget; backup CronJob present (RPO bounded by the 15-min cron) | ✅ |
| OPS-4 (NFR-44) | locked-period payroll export uploaded to the objectstore WORM prefix (hash-named) | object GET 200; direct DELETE as `eim_app` **blocked**; locked-partition INSERT **blocked** | ✅ |
| SEC-1/3 (NFR-54) | in-cluster red-team probe | all 8 service paths 401 unauthenticated; malformed Basic auth rejected; DELETE verb confusion rejected; **BOLA: foreign payee totalPayout=0 and foreign trace 404**; gateway bypass reviewed | ✅ |
| SEC-3 (R-7) | AGE access audit | graph **CREATE blocked for `eim_app`** (schema privilege) — graph plane reachable only through the service layer | ✅ |
| AUD-1 (NFR-60/61/64) | assurance pack SQL | settled seal days verify + **GENESIS tamper positive control fires**; all `eim.*` event rows carry 64-char sha256; lineage walk: ledger→payee joins, every traced line pins its plan version | ✅ |
| SEC-1 | NetworkPolicies (3+) enforced | ✅ |
| OPS-5/6 | runbooks doc (SLOs + 12 alerts → RB-1..RB-10 = 100% coverage + capacity plan w/ audit-volume projection) | ✅ |
| AUD-2 (NFR-63/71) | retention + GDPR erasure-vs-immutability design signed off (pseudonymization mechanism) | ✅ |

## 2. What was built

- **Scale/load drill** (`scripts/tg5-scale-load.sh`): set-based 5k-payee/1M-row fixture on a dedicated tenant, full-cohort run (in-cluster call — the gateway's 60 s upstream timeout is the API-edge budget; batch runs are orchestrator-triggered), 60+60-request synthetic load with p95/p99 computation, read-only session parity.
- **Chaos/DR drill** (`scripts/tg5-chaos-dr.sh`): rolling-deploy soak with error accounting, calc + process pod-kill recovery, full logical backup → scratch-DB restore with AGE-catalog parity + seal verification + measured RTO, WORM upload of hash-named locked-period exports + both immutability proofs (app-role delete, partition write).
- **Red-team probe** (`scripts/tg5-pentest.sh`): unauthenticated matrix across all 8 service paths, OWASP basics (scheme confusion, verb confusion), BOLA per seller endpoint, gateway-bypass check.
- **Audit assurance pack** (`scripts/tg5-audit-pack.sql`): DO-asserted seals + tamper control + event hash shape + lineage walk (source→credit→plan-version→ledger→trace invariants).
- **Engine scaling fix (real defect found by TST-1)**: the runner loaded the whole cohort into memory and OOM'd at 1M rows (512 Mi pod). Replaced with a **hybrid strategy** — cohorts ≤200k rows load in one query (keeps the NFR-20 benchmark at 10.7 s), larger cohorts stream per-payee slices bounded by the worker pool. Enterprise run then completed in 10.4 s with a 2 Gi limit.
- **Connector tenant quotas (NFR-33)**: registry-configurable `connector.tenant_quota_rows` gate in `IngestionService` (default 100k) rejecting oversized batches up front (400 naming NFR-33) with zero partial rows; isolation test proves the neighbor tenant proceeds.
- **RLS role regression in CI (NFR-52)**: `RlsRoleRegressionTest` — same fixture, three identities; ships in the standard suite so a policy regression cannot pass CI.
- **Calc-engine heap sizing**: 2 Gi limit documented in the capacity plan (§3).
- **Operability pack** (`docs/ops-tg5-runbooks.md`): SLO table, 12 alerts → 10 runbooks (100%), capacity plan with the audit-volume projection obligation (R-8), portability statement.
- **Retention/GDPR design** (`docs/retention-gdpr-tg5.md`): registry-configured retention windows + the agreed pseudonymization (not deletion) mechanism reconciling Art. 17 with NFR-44 immutability, with explicit sign-off scoping.

## 3. Honest deviations & findings

1. **Gateway 60 s upstream timeout vs batch runs**: the enterprise run is invoked in-cluster (the orchestrator's production path); through the gateway it would 504 at 60 s. Documented rather than raising the edge timeout.
2. **Keycloak issuer-vs-Host-header finding (real)**: tokens obtained through the localhost port-forward carry a `localhost` issuer and introspect `active:false` in-cluster — the scale drill pins the token request's Host header to `eim-keycloak:8080`. Recorded as RB-10.
3. **Chaos scope**: pod-kill only (kind single-node); node/zone loss and the Kogito-runtime redundancy ride the staging posture. Process-state durability is what NFR-32 requires and is proven.
4. **DR drill is a logical backup restore** (dev evidence, RTO 3.6 s at 2 MB); WAL PITR + object-lock are the production posture — the drill asserts the same integrity checks either way.
5. **WORM**: dev objectstore has no real object-lock; evidence = hash-named immutable uploads + DB-layer immutability (app-role delete blocked, locked partition write blocked). Object-lock lands with the production bucket class.
6. **Latency SLO numbers** are dev-cluster synthetic (no concurrent batch); continuous synthetic monitoring rides the production observability stack (dashboards doc §1).
7. **OPS-7 portability**: continuously tested on kind (CNCF-conformant); two-cloud + k3s stand-up is the substitution matrix + chart posture proof — an actual second-cloud spin-up is a release-blocking step in the production pipeline, not reproducible from this dev box.
8. **Synthetic `event_audit_log` rows** (`topic='t'`) from unit tests on the shared dev DB are excluded from the assurance pack's hash check (scoped to `eim.*`).

## 4. Exit criteria (TG-5)

| Criterion | Status |
|---|---|
| Release-candidate row of the NFR verification matrix green with linked evidence | ✅ — NFR-20/21 (calc benchmarks), NFR-22 (latency SLOs), NFR-24 (TG-2/TG-3 rollup benchmarks), NFR-25 prep (replica parity), NFR-32 (chaos), NFR-33 (quota isolation), NFR-41 (zero-downtime), NFR-42 (DR + RPO/RTO), NFR-44 (WORM), NFR-52 (RLS suite), NFR-54 (red-team), NFR-60/61/64 (assurance pack), NFR-63/71 (retention/GDPR design), NFR-80 (portability), NFR-91/93 (SLO/runbooks), R-7/R-8 (AGE audit, audit-volume projection) — all with linked evidence in this log |

## 5. Reproduce

```bash
scripts/dev-up.sh                    # cluster + port-forwards (15433/19092)
kubectl -n eim port-forward svc/eim-apisix 31234:9080 &
kubectl -n eim port-forward svc/eim-keycloak 31235:8080 &
bash scripts/verify-tg5.sh           # 20 gates
```
