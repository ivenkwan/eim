# TG-1 Build & Verification Log

| | |
|---|---|
| **Date** | Built & verified 2026-10-29 |
| **Harness** | `bash scripts/verify-tg1.sh` (18 gates: unit + static + live cluster) |
| **Result** | ✅ **ALL 18 GATES GREEN** — unit suite 20/20, frontend regression green, live-cluster gates on the kind dev platform |
| **Companion** | [todo.md](../../todo.md) (TG-1 section) · [TG-0 build log](TG-0.md) |

---

## 1. Gate results — 18/18 PASS

**Unit / static gates**

| Item | Gate | Result |
|---|---|---|
| DAT-5 | Migration policy lint (naming, duplicates, destructive guard, immutability vs base ref) | ✅ |
| AUD-x | Audit-coverage gate + self-test (5 specs, mutating ops, event topics) | ✅ |
| OPS-x | Frontend regression (strict TS lint/typecheck/test, TG-0 suite) | ✅ |
| DAT-1..4 | **core-api unit suite 20/20** (schema shapes, RLS isolation, partition locking, AGE projection+drift, audit atomicity/append-only/seals, governed config+approval, RBAC SoD) | ✅ |
| INF-x | Helm lint ×4 postures | ✅ |
| INF-x | Substitution matrix (values-only portability + required-secret refusal) | ✅ |

**Live cluster gates (kind dev platform, helm release rev 14+)**

| Gate | Evidence | Result |
|---|---|---|
| All workloads ready | 11/11 Running (apps, gateway, keycloak, PG+AGE, Kafka, objectstore) | ✅ |
| SEC-2 gateway 401 | APISIX openid-connect: `/api/v1/me/*` + `/api/v1/admin/*` reject tokenless requests; `/hello` open | ✅ |
| SEC-1/2 **E2E SSO+RLS+audit** | Keycloak issues client-credential token → gateway validates → `/me/payee` returns **Sarah Jenkins for her own tenant/payee claims** and `null` for a foreign payee (RLS through the gateway) → `POST /admin/config-change` → **202** | ✅ |
| AUD-4 round-trip | `event_audit_log` row: topic `eim.config.changed`, SUCCESS, **64-char sha256** — the full chain (audit_log + outbox → Kafka → consumer) verified live | ✅ |
| AUD-9 reconciliation | In-pod endpoint reports mutation/outbox/event counts (gap converges to 0 as the exporter drains) | ✅ |
| DAT-4 graph clean | `eim_ops.reconcile_graph()` = 0 drift (projection trigger + reconciler) | ✅ |
| AUD-6 seals chain | `verify_audit_seals()` = true (hash chain over real audit rows; tamper detection unit-proven) | ✅ |
| SEC-6 NetworkPolicies | 3 default-deny/allow-list policies enforced (direct pod→core-api access blocked; only apisix admitted) | ✅ |
| INF-1 backups | Nightly AES-256-CBC pg_dump CronJob → objectstore `eim-snapshots` (key from secret store) | ✅ |
| INF-3 buckets | Hook job creates `eim-snapshots/statements/attachments/exports` (hook-succeeded cleanup) | ✅ |

## 2. What was built (per todo.md items)

**Data plane (DAT-1..5)** — V2 core schema (8 tables, FR §12 types enforced by
`SchemaShapeTest` incl. NUMERIC(18,4) precision asserts); V3 audit plane (4
append-only stores + triggers); V4 RLS (tenant + payee policies, `eim_app`
role, per-request `SET LOCAL ROLE` + GUC binding); V5 AGE graph (labels,
EXECUTE-based cypher helpers — cstring params can't bind — trigger
projection, reconciler incl. orphan detection, rollup scope); V6/V7 ops
functions (partition create/lock with **parent-level lock trigger** — PG
checks parent permissions on partitioned inserts, revokes alone don't block);
per-service Flyway history (ADR-010); migration policy lint script.

**Audit plane (AUD-1..9)** — transactional outbox + exporter (Kafka), event
auditor consumer with sha256 + DLQ-aware ack, correlation-ID filter
(X-Request-Id → MDC → audit rows), config registry (versioned, governed
approval flow), hash-chain sealer + verifier + daily scheduler, reconciliation
scheduler with Micrometer gap gauge, GitOps sync-watcher CronJob
(configuration_change rows for helm manifest changes).

**Security (SEC-1..6)** — Keycloak realm import (6 realm roles, eim-gateway +
eim-web clients, tenant_id/payee_id claim mappers, eim_identity scope); APISIX
openid-connect on /me + /admin routes (bearer_only); RBAC SoD
(finance_approver exclusive PERIOD_LOCK, unit-proven; mode-agnostic role gates
on audit-evidence endpoints); admin/bootstrap secrets from values (no
defaults, NFR-53); 3 NetworkPolicies.

**Infra (INF-1..3)** — encrypted backup CronJob (pg_dump | openssl AES-256 →
S3 bucket layout); Strimzi/external postures remain values-substitution
(NFR-81 matrix); SeaweedFS buckets via post-install hook.

**Observability (OPS-1..4)** — `/q/metrics` Prometheus endpoint (Micrometer),
JSON logs with correlation_id MDC, audit-gap + graph-drift gauges,
ServiceMonitor + PrometheusRule (2 alert groups) gated behind
`serviceMonitor` value until the operator ships, 4 runbooks linked from
alerts. *Full kube-prometheus-stack deployment is the remaining TG-1→TG-2
carry-over — the manifests ship now, the stack install is one helm command.*

## 3. Honest deviations & findings (build-log record)

1. **Gateway-trust auth mode on dev**: APISIX's openid-connect plugin strips
   the bearer JWT before proxying upstream (lua-resty-openidc introspection
   behavior), so core-api cannot re-validate the JWT in-cluster on this
   chain. Dev posture: the GATEWAY validates tokens (401/202 proven) and
   identity claims ride headers; core-api `authMode: oidc` re-validation is
   unit-proven (RbacPeriodLockTest) and remains the default for
   staging/prod. TG-2 follow-up: switch to the apisix jwt-auth plugin or
   mTLS-protected header set.
2. **Keycloak user password grants** return `resolve_required_actions` on
   this import; client-credentials grants work and are the E2E identity.
   User-claim RLS is proven by unit tests + the header-mode live probe.
3. **AGE cstring parameters cannot bind** — all dynamic cypher goes through
   EXECUTE with format() over typed values only; operators need ag_catalog
   in search_path (functions set it).
4. **Partitioned-table lock semantics** — parent-level BEFORE trigger guards
   LOCKED periods; partition-level REVOKE kept as defense in depth.
5. **Shared dev DB + unit suite**: the harness re-seeds demo payees and
   re-seals yesterday after the unit suite (which wipes payees for clean
   graph tests). Deterministic and documented in the script.

## 4. Exit criteria (TG-1)

| Criterion | Status |
|---|---|
| SSO login through APISIX hits an RLS-protected read-only page | ✅ E2E gate (own row visible, foreign payee null) |
| Scripted mutation shows audit record + outbox event + event-audit entry atomically | ✅ AUD-4 gate + AuditFlowTest (20/20) |
| Tamper attempt on each audit store fails | ✅ AuditPlaneTest (UPDATE/DELETE rejected on all 4 stores; seal tamper detected) |
| Full stack deploys from umbrella chart | ✅ live (rev 14); portability via 4-posture matrix |

## 5. Reproduce

```bash
bash scripts/dev-up.sh        # full platform (TG-0+TG-1 charts)
bash scripts/verify-tg1.sh    # 18/18 gates
```
