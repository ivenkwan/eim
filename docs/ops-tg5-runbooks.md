# TG-5 Operability Pack — SLOs, Alert→Runbook Map, Capacity & Portability

> TG5-OPS-5/6/7 (NFR-40/91/93/64, NFR-80). Companion evidence: `scripts/verify-tg5.sh` gates + `scripts/tg5-scale-load.sh` / `scripts/tg5-chaos-dr.sh` outputs.

## 1. SLOs and error budgets (NFR-40/91)

| Service tier | SLO | Budget/month | Measurement |
|---|---|---|---|
| Seller read APIs (`/api/v1/me/*`) | p95 < 300 ms, p99 < 800 ms, availability 99.9% | 43 min | synthetic loop (tg5-scale-load) + Micrometer `http.server.requests` histograms per service |
| Write APIs (ingestion, disputes) | p95 < 500 ms | — | same histograms, `api_write` tag |
| Calculation runs | ≤ 15 s @ 500×100k (NFR-20); ≤ 10 min @ 5,000×1M (NFR-21) | — | `calc_run.duration_ms` recorded per run + alert on budget breach |
| Audit plane | outbox lag → 0; seals verify for settled days | — | `reconcile_audit` gap + `verify_audit_seals()` |

Dashboards: every service exposes `/q/metrics` (Prometheus format, Micrometer). The umbrella chart ships ServiceMonitors-ready annotations (`prometheus.io/scrape`); the production stack (kube-prometheus-stack) lands per staging posture — dev evidence is the endpoints + the recorded gate numbers in the build log.

## 2. Alert → runbook coverage (NFR-93) — 100%

| Alert | Condition | Runbook |
|---|---|---|
| `ApiLatencyP95High` | read p95 > 300 ms for 5 m | RB-1 |
| `ApiLatencyP99High` | read p99 > 800 ms for 5 m | RB-1 |
| `WriteLatencyP95High` | write p95 > 500 ms for 5 m | RB-2 |
| `CalcRunBudgetBreach` | calc_run.duration_ms > NFR budget | RB-3 |
| `CalcRunFailures` | FAILED runs > 0 in 10 m | RB-3 |
| `OutboxLagGrowing` | reconcile_audit.gap > 0 for 10 m | RB-4 |
| `SealVerificationFailed` | settled seal day ok=false | RB-5 |
| `GraphDriftDetected` | reconcile_graph() non-empty | RB-6 |
| `IngestionQuotaRejections` | 400 quota rejections spike | RB-7 |
| `PodCrashLooping` | restarts > 3 / 15 m (any service) | RB-8 |
| `BackupCronFailed` | eim-postgres-backup missed window | RB-9 |
| `GatewayAuthErrorSpike` | 401 rate > baseline + 3σ | RB-10 |

- **RB-1 Read latency**: check `PG` slow queries (`pg_stat_statements`), lock contention on hot partitions; scale core-api replicas (HPA on CPU); verify NetworkPolicy egress not throttling.
- **RB-2 Write latency**: check connectors pool saturation, Kafka producer flush, `ingestion_batch` long-running rows; per-tenant quota misfire → review `connector.tenant_quota_rows` config.
- **RB-3 Calc failures/budget**: inspect `calc_run.error`; re-run is idempotent (wipe of non-override rows per period); for budget breach collect wave stats, consider KEDA scale-out or cohort-load limit tuning.
- **RB-4 Outbox lag**: verify OutboxExporter pods healthy, Kafka reachable; replay via `reconcile_audit` after recovery; events are at-least-once with event-id dedup.
- **RB-5 Seal failure**: treat as a security incident — freeze writes to the affected day, export `audit_log` for the day, compare against the WORM object; break-glass reseal only with dual approval (FR-613).
- **RB-6 Graph drift**: run `eim_ops.reconcile_graph()` output, fix the payee/hierarchy write path that bypassed the trigger; drift blocks period close.
- **RB-7 Quota spike**: confirm tenant burst; raise the registry quota deliberately (audited CONFIG_CHANGE) or split the sync.
- **RB-8 Crash loop**: `kubectl logs --previous`; calc-engine OOM → raise memory limit (capacity plan §3) or lower COHORT_LOAD_LIMIT.
- **RB-9 Backup**: re-run CronJob manually, verify dump in `eim-snapshots`, then re-run the DR drill (tg5-chaos-dr.sh) against the fresh backup.
- **RB-10 Auth spike**: check Keycloak health + APISIX introspection logs; possible token issuer mismatch (issuer derived from Host header — see TG-5 finding in the build log).

## 3. Capacity plan incl. audit-volume projection (risk R-8, NFR-64)

Reference sizing measured on the dev kind cluster (single node; production reference = 3-node):

| Resource | Measured (TG-5 drills) | Planning guidance @ enterprise (5,000 payees, 1M rows/month) |
|---|---|---|
| calc-engine CPU/mem | 10.4 s full-cohort @ 512Mi–2Gi | 2 vCPU / 2 Gi per replica; runs are partitioned per period — scale replicas with KEDA on topic lag; COHORT_LOAD_LIMIT=200k rows keeps resident memory bounded |
| core-api | p95 read 52–65 ms under light load | 2 replicas baseline + HPA (CPU 60%); each RLS session is a pooled connection — pool 2× replicas |
| PostgreSQL | 1M-row month inserts in ~4 min via set-based SQL | storage ≈ ledger (5k rows/period) + raw txn (~350 MB/1M rows incl. indexes) + **audit volume: every mutation writes audit_log + outbox + event_audit_log ≈ 1.5 KB/event** — at 50 mutations/payee/month ≈ 375 MB/month audit growth; **seals + WORM exports add ~1× ledger size per locked period**. Retention (AUD-2): hot 24 months, then partition detach to archive tier |
| Kafka | outbox drain 5 s intervals | 3 brokers; `eim.*` topics 7-day retention cover replay windows |
| Objectstore | WORM + snapshots + statements | per locked period: ledger export + audit dump + seal ≈ 2× period ledger; monthly full backup ≈ DB size |

Audit-volume projection (R-8) is the review obligation: re-run the projection quarterly with the real mutation counts from `audit_log` and adjust the retention/archive budget before the hot window exceeds 24 months of projected growth.

## 4. Portability (NFR-80)

- Charts are values-only configurable (no provider locked in): proven by the substitution matrix (any-provider / external-DB / external-all / dev-embedded postures) and `helm lint` across all four in CI.
- Provider swap = values swap: `global.databasePassword` / external DB host, objectstore endpoint, load-balancer annotations. No chart templates reference a cloud vendor.
- On-prem k3s: the `dev-embedded` posture (everything in-cluster) is the k3s baseline; kind (CNCF-conformant Kubernetes) is the continuously-tested substrate — the same images + charts run unmodified.
- Verified live: this tollgate's entire suite (scale, chaos, DR, pen-test) ran against the kind cluster booted by `scripts/dev-up.sh`.
