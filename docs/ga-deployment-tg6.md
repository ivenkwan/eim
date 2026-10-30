# GA Deployment Posture — TG-6 (OPS-1)

Status: **declared for Phase 1 GA**. This document fixes the production
posture the platform ships with, the environments it runs in, the on-call
model and the incident process. Dev evidence (kind, single node) is the
substitution boundary — see `scripts/substitution-matrix.sh` and the
[portability matrix](build-log/TG-5.md); the cloud-agnostic constraint
(AD-6: CNCF-conformant Kubernetes, no proprietary managed services) holds in
every posture.

## 1. Environments

| Environment | Cluster | Values posture | Auth | Data |
|---|---|---|---|---|
| dev | kind (single node) | defaults + `eim-core-api.authMode=header` | gateway OIDC + dev trust headers | shared dev AGE postgres |
| staging | 3+ node CNCF cluster | `env/staging` overlay: 2 replicas per service, KEDA on, otel on | full OIDC (authMode default `oidc`) | scratch, restored weekly from prod-shaped backup |
| prod | 3-zone CNCF cluster | `env/prod` overlay (below) | full OIDC; header trust **disabled** | dedicated AGE postgres (CloudNativePG-shaped operator or statefulset), PITR + daily logical backup |

Production is declared with the umbrella chart; overlay values that differ
from the chart defaults:

```yaml
# env/prod posture (illustrative overlay — secrets always external)
eim-web: { replicas: 2, otel: { enabled: true } }
eim-core-api: { replicas: 2, otel: { enabled: true } }   # authMode stays default "oidc"
eim-connectors: { replicas: 2, otel: { enabled: true } }
eim-process: { replicas: 2, otel: { enabled: true } }
eim-plan-compiler: { replicas: 1, otel: { enabled: true } }
eim-calc-engine:
  replicas: 2
  keda: { enabled: true, minReplicas: 2, maxReplicas: 8 }  # calc-run lag scaling
  resources: { requests: { memory: 1Gi }, limits: { memory: 2Gi } }  # TG-5 OOM finding, chart-owned
eim-postgres:
  replicas: 3            # streaming replica + failover posture
  backup: { enabled: true, retentionDays: 35, wormBucket: eim-worm }
eim-kafka: { replicationFactor: 3 }
```

Non-negotiables (enforced by chart required-values, not convention):

- No default credentials anywhere; every secret arrives via `--set` /
  external secret store at deploy time (NFR-53).
- `authMode=header` is **dev-only**; production never sets it, so core-api
  validates OIDC identity itself (defense in depth behind APISIX).
- Locked-period WORM exports to the objectstore `eim-worm` bucket
  (hash-named objects — TG-5 immutability proofs).

## 2. Deployment process

1. CI: unit + E2E suites (all services, live AGE postgres), golden tests,
   audit-mapping gate, migration lint, web budget, `helm lint` +
   `helm template` for all postures (see `verify-tg6.sh`).
2. Images build from the same commit (`scripts/build-apps.sh`), tagged with
   the git SHA in prod (dev uses `0.1.0-dev`).
3. Staging: helm upgrade + the full tollgate harness (same
   `scripts/tg6-partner-drill.sh` journey) + smoke.
4. Prod: helm upgrade (`--wait`, 10 min timeout), then rollout verification
   (`kubectl rollout status` per deployment) and the zero-downtime probe —
   the TG-5 chaos drill already proved deploys and pod-kill mid-close with
   durable state and error budget intact.
5. Rollback: `helm rollback` — schema migrations are additive-only
   (enforced by `scripts/check-migrations.sh`), so image rollback never
   fights the database.

## 3. On-call model

| Rotation | Composition | Hours | Handoff |
|---|---|---|---|
| EIM primary | 1 engineer (rota weekly) | 24×7 | written handoff in the ops channel: open incidents, drill/audit gaps, consumer lag, seal status |
| EIM secondary | 1 engineer | 24×7 (backup primary) | same handoff, owns weekend |
| Comp Ops (RevOps) | business-hours rota | partner-hours | hypercare log per partner (see [cutover/hypercare](cutover-hypercare-tg6.md)) |
| Engineering lead | escalation only | on call for P1 | — |

Runbooks: [ops runbooks](ops-tg5-runbooks.md) — every alert maps 1:1 to a
runbook step (TG-5 exit criterion, `slo-alert→runbook` coverage 100%).

## 4. Incident process

Severity ladder (aligned with the manual-intervention playbook P1 procedure):

| Sev | Definition | Examples | Response |
|---|---|---|---|
| P1 | partner pay period at risk / audit integrity broken | close cycle projected > 3 business days; audit reconciliation gap ≠ 0; seal verification failure; export handoff missed before payroll cutoff | page primary + secondary + lead; 15 min ack; hourly updates; break-glass actions (MI-18 reopen, MI-19/20 emergency grants) only under this process |
| P2 | degraded but recoverable within the period | single-service crash-loop; connector quota storm; consumer lag > SLA | page primary; 30 min ack; fix-forward or rollback |
| P3 | observable, no partner impact yet | slow queries; WORM upload retries | business hours ticket |

Every incident gets a blameless postmortem within 5 business days; postmortem
actions feed the tollgate backlog. Break-glass usage is audited
(`PERIOD_REOPEN`, `APPROVAL` ×2, `AUTH_EVENT`) and reviewed in the weekly ops
meeting — the MI-18 drill response shows exactly the trail a reviewer sees.

## 5. Monitoring & alerting baseline (prod)

- SLOs and error budgets as defined in the TG-5 SLO pack; alerts fire on
  budget burn, not raw thresholds.
- Audit assurance: daily seal + `reconcile_audit` gap ≠ 0 pages P1 (an
  unaudited state change is an integrity incident, not a metrics blip).
- Consumer lag on `eim.*` topics; calc-engine KEDA scaling events.
- Close-cycle telemetry (`GET /api/v1/parallel-run/close-cycle`) reviewed in
  the daily standup during any partner's hypercare.

## 6. GA readiness checklist

- [x] All tollgates TG-0..TG-5 green with harnesses (see `todo.md` status blocks)
- [x] TG-6 partner drill 14/14 green (onboarding → parallel run → GA surfaces)
- [x] MI-18 break-glass with dual approval live and audited
- [x] Runbook/alert coverage 100%; on-call rotation declared (this doc)
- [x] Cutover + hypercare procedure adopted (ONB-7)
- [x] SOC 2 evidence collection live (OPS-2, see [SOC 2 controls](soc2-controls-tg6.md))
- [ ] First real partner onboarded on production (the production step this
      posture exists for — simulated-partner evidence complete)
