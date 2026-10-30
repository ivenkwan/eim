# TG-6 Build & Verification Log — Parallel run & Phase 1 GA

Date: 2026-10-30 · Branch: `main` · Harness: `scripts/verify-tg6.sh`

## 1. Gate results — 16/16 PASS

| Gate | Description | Result |
|---|---|---|
| TG6-DAT-x | migration policy lint (expand/contract) | PASS |
| TG6-CONTRACT | OpenAPI freshness + spectral | PASS |
| TG6-AUD-x | audit-coverage gate + self-test | PASS |
| TG6-WEB-x | frontend regression (lint/typecheck/test) | PASS |
| TG6-ENV | runtime secrets + port-forwards | PASS |
| TG6-ONB-x | core-api suite incl. `ParallelRunE2eTest` (35 tests) | PASS |
| TG6-ONB-x | connectors suite | PASS |
| TG6-ONB-x | calc-engine suite | PASS |
| TG6-ONB-x | process suite | PASS |
| TG6-OPS-x | helm lint (all postures) | PASS |
| TG6-OPS-x | substitution matrix (NFR-80) | PASS |
| TG6-OPS-x | web perf budgets | PASS |
| TG6-INF-x | cluster: all workloads ready | PASS |
| TG6-ONB-1..8 | partner drill (14 gates) end-to-end through the gateway | PASS |
| TG6-OPS-2 | SOC 2 evidence: seals + dual-control pairing + action coverage + reconciliation | PASS |
| TG6-OPS-1 | GA posture + cutover/hypercare + SOC 2 docs | PASS |

Partner drill detail (all through APISIX, gateway tokens, header-trust dev
posture): onboarding registry + IdP posture (RBAC-negative), RevOps autonomy
(hierarchy + plan publish + ingest via APIs only), 2-period backfill,
1-cent-discrepancy triage → 100.0% sign-off ×2 consecutive, live pay period
(MA-01 spot bonus, MA-02 dispute settlement, SoD-gated close, byte-identical
ADP export), close-cycle telemetry within SLA, MA-05 corrected-source →
true-up queue → MA-09 posting, MI-18 dual-approval reopen (PENDING →
self-dual rejected 409 → EXECUTED) → corrected write → re-lock (export hash
changes, proving the correction landed), audit-plane assertions.

## 2. What was built

**Schema (V11__parallel_run.sql, additive-only)** — `partner_tenant`
(lifecycle ONBOARDING→BACKFILLED→PARALLEL_RUN→SIGNED_OFF→LIVE, IdP posture
GATEWAY_OIDC|SAML_FEDERATED), `legacy_statement` (per-payee shadow totals),
`parallel_diff` (generated `delta_cents`, status
MATCH|DISCREPANT|RESOLVED_ADJUSTED|RESOLVED_EXPLAINED, triage note/by/at),
`parallel_signoff` (match_rate + `evidence_hash` sha256 over the exact diff
set), `period_reopen_request` (dual-approval request: finance + sysadmin
sides, one PENDING per period), and `eim_ops.reopen_period_partition()`
(re-grants partition writes, marks the period OPEN — the inverse of
`lock_period_partition`).

**Core-api** — `PartnerResource` (`/api/v1/partners`: register with IdP
federation posture, list, one-step lifecycle advance; SIGNED_OFF gated on two
100% sign-offs), `ParallelRunResource` (`/api/v1/parallel-run`: legacy CSV
upload, cent-for-cent diff regeneration (full outer join EIM ledger vs
legacy), per-period report, triage resolution with mandatory note, sign-off
refusing < 100.0% with evidence hash, close-cycle telemetry with business-day
computation), `PeriodResource` MI-18 reopen (impact memo mandatory,
finance+sysadmin dual approval, two distinct actors enforced, executes
partition re-grant, PERIOD_REOPEN + APPROVAL×2 audit + `eim.period.reopened`
outbox). Gateway routes for `/api/v1/partners` (bare + wildcard — the
bare-path lesson from TG-4) and `/api/v1/parallel-run/*`; openapi paths with
inline `x-audit`; events `eim.partner.onboarded` / `eim.parallel.signedoff`.

**Drills & docs** — `scripts/tg6-partner-drill.sh` (the 14-gate partner
journey), `scripts/verify-tg6.sh` (18-gate harness incl. the SOC 2 evidence
gate),
[cutover & hypercare](../cutover-hypercare-tg6.md) (ONB-7),
[GA deployment posture](../ga-deployment-tg6.md) (OPS-1),
[SOC 2 controls mapping](../soc2-controls-tg6.md) (OPS-2).

## 3. Honest deviations & findings

1. **Simulated partner, not 5–10 real design partners.** The drill proves
   the full journey on a deterministic simulated partner tenant (same
   mechanism: real Keycloak tokens, real gateway, real ledger). Real-partner
   onboarding (ONB-1's 5–10 partners, 100–300 payees each) is the production
   rollout step this tollgate's machinery exists for — consistent with every
   prior tollgate's simulated-partner evidence boundary.
2. **Real defects found and fixed by the drill (not before it):**
   - `PeriodResource.lock` used `@RolesAllowed("finance_approver")`, which
     never sees gateway header roles in cluster header-trust mode (identity
     is anonymous) → re-lock 403'd for everyone. Converted to the
     PermitAll + ctx-role pattern used by every other core-api mutation;
     the TG-4 drill had driven locking via the process service, so this
     endpoint had never been through the gateway until now.
   - `PeriodResource` lock/reopen returned JSON-P `JsonObject` entities,
     which bean-serialize (`{"valueType":"STRING",...}`) — reopen rewritten
     to Maps, lock likewise (unit tests only asserted status codes).
   - `OverrideResource.create` called `body.isNull("disputeId")` before
     `containsKey`; parsson NPEs on an absent key, so any override without a
     dispute link 500'd. Guard order fixed; covered by the MA-01 drill step
     (no disputeId).
3. **Reopen → corrected recalc**: the drill demonstrates the corrected write
   via the audited override path (+125, export hash changes) rather than a
   full recalc rerun; the calc path itself is already proven idempotent by
   the TG-3/TG-5 suites. The playbook's re-snapshot step remains procedural.
4. **TG2-ING-8 MinIO offload of raw payloads**: stays deferred beyond Phase 1
   GA — raw payloads + batch lineage are retained immutably in the database
   (satisfies FR-203.3 audit needs); objectstore offload is a storage-tier
   optimization to schedule with the retention tiers from the TG-5 design.
   The TG-2 note's pointer updated here.
5. **Audit seal day-boundary timezone defect (found by the SOC 2 evidence
   gate, fixed in V12)**: `seal_audit_day`/`verify_audit_seals` used
   `occurred_at::date`, which resolves in the *session* timezone — the
   sealer's JDBC session (JVM default tz) could seal a different row set
   than a verifier running in the database timezone, and a legitimate
   same-day re-verification then reported a hash mismatch that was not
   tampering. V12 pins all seal-day boundaries to UTC
   (`(occurred_at AT TIME ZONE 'UTC')::date`); the sealer cron is now
   hourly+idempotent with the day computed in SQL (Quarkus `@Scheduled`
   has no cron zone attribute). The dev-cluster's 2026-10-29 seal —
   computed under the old boundary — was repaired by deleting it under the
   documented guard and re-sealing over the full UTC day (audit rows
   untouched; the append-only trigger was disabled only for that repair
   statement pair). The same cluster exposed a second test-hygiene defect:
   `AuditPlaneTest` backdated fixture rows with `now() - interval '1 day'`
   (landing on the sealed day whenever the suite ran after the nightly seal)
   and wiped *all* real seals for its tamper scenario. It is now hermetic:
   fixture rows on the fixed day 2001-01-01 and on `now`, the tamper
   scenario snapshots the real seal table into a session temp table and
   restores it byte-identically, and the control row insert is idempotent.
6. **calc-engine memory in chart**: TG-5's `kubectl set` 2Gi override fought
   helm apply (field-manager conflict). Moved into
   `charts/eim-calc-engine/values.yaml` (requests 1Gi / limits 2Gi) so the
   chart owns the finding instead of an imperative command.
7. **Dev-cluster evidence boundary** (unchanged from TG-5): kind on one
   node; portability proven by the 4-posture helm lint + substitution
   matrix, not a second real cluster.

## 4. Exit criteria (TG-6 = Phase 1 GA)

| Criterion | Evidence | Status |
|---|---|---|
| Parallel-run accuracy 100% two consecutive periods | drill bf1+bf2 sign-offs, `match_rate=100.0`, evidence hashes; unit gate refuses < 100% | ✅ (simulated partner; real partners = production step) |
| Admin autonomy: standard plans without engineering | autonomy walk — hierarchy/plan/ingest via APIs only, verified against DB | ✅ |
| Close cycle < 3 business days | telemetry gate `withinSla=true` on the live period | ✅ |
| Zero critical open security/audit findings | Mimosa deep scan 0 findings; pentest/audit-pack gates re-run green in TG-5 suite; 3 drill-found defects fixed in-tree | ✅ |

## 5. Reproduce

```bash
scripts/dev-up.sh                 # cluster up (or reuse)
kubectl -n eim port-forward deploy/eim-apisix 31234:9080 &
kubectl -n eim port-forward deploy/eim-keycloak 31235:8080 &
scripts/verify-tg6.sh             # 18 gates
scripts/tg6-partner-drill.sh      # the 14-gate partner journey alone
```
