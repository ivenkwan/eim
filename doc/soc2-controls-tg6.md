# SOC 2 Evidence & Controls Mapping — TG-6 (OPS-2)

Status: **live**. Every control below points at evidence that can be
regenerated on demand from the running system (SQL against the audit plane,
harness output, or a documented procedure). The `SOC2` gate in
`scripts/verify-tg6.sh` re-executes the machine-checkable evidence rows each
run, so the mapping cannot silently rot.

Scope note: Phase 1 GA covers the **Security, Availability and
Confidentiality** trust services criteria; PI (privacy) criteria ride the
retention/GDPR design signed off in TG-5
([retention & GDPR](retention-gdpr-tg5.md)).

## Control matrix

| SOC 2 criterion | Control in EIM | Live evidence (regenerate on demand) |
|---|---|---|
| CC6.1 logical access — boundary | APISIX gateway: OIDC bearer validation on every data route; unauthenticated probes 401 (8/8 paths red-teamed in TG-5) | `scripts/tg5-pentest.sh` gate; `GET /api/v1/hello` is the only open route |
| CC6.1 least privilege / RBAC | Roles comp_admin / finance_approver / payee / system_admin; SoD on lock (finance only) and overrides (comp_admin only); MI-18 dual approval | `RbacPeriodLockTest`, `ParallelRunE2eTest` (role matrix); drill ONB-8 dual-approval trail |
| CC6.1 tenant isolation | Row-level security + per-tenant quotas (connector tenant_quota_rows) | `RlsAndPartitionTest`, `RlsRoleRegressionTest` (in CI); TG-3 quota isolation gate |
| CC6.2 access removal / CC6.3 role change | Roles resolve from the IdP per request (Keycloak realm roles); no local accounts in services | `TenantContextFilter`; prod posture `authMode=oidc` default |
| CC6.6 transmission encryption | TLS terminated at gateway; service mesh mTLS posture per architecture §12 | substitution matrix posture checks (TG-5) |
| CC6.7 / CC7.2 system operation monitoring | OTel pipelines (per-env), SLO + error-budget alerts, consumer lag, close-cycle telemetry | TG-5 SLO pack; `GET /api/v1/parallel-run/close-cycle` |
| CC7.1 vulnerability / config hygiene | Deep security scan each tollgate push; no credential literals in source (scanner-enforced); parameterized SQL only | Mimosa scan reports (0 findings, static-evidence boundary noted per build log) |
| CC7.2 anomaly detection | Audit reconciliation gap ≠ 0 alerts; seal verification failure pages P1 | `eim_ops.reconcile_audit(since)`, `eim_ops.verify_audit_seals()`; `tg5-audit-pack.sql` |
| CC7.3 incident evaluation & response | Sev ladder P1–P3, 15/30-min acks, break-glass only under P1 procedure, blameless postmortems ≤ 5 days | [GA deployment posture §4](ga-deployment-tg6.md); MI-18/MI-19/20 audit trail |
| CC7.4 recovery | DR drill: full restore incl. AGE catalog to scratch DB, ledger/graph/seal integrity parity, RTO 3.6 s measured | `scripts/tg5-chaos-dr.sh` gates |
| CC8.1 change management | Config registry with governed approval (`configuration` ACTIVE/PENDING/SUPERSEDED); plan publish gate with version hashes; migrations additive-only | `ConfigChangeResourceTest`; `scripts/check-migrations.sh` gate |
| CC9.2 / A1.1 availability | Multi-zone posture, replicas per env overlay, KEDA calc scaling, zero-downtime deploys proven | TG-5 chaos drill (pod-kill mid-close, err budget intact); [GA posture §1](ga-deployment-tg6.md) |
| C1.1 confidentiality at rest | Secrets never defaulted; WORM bucket for locked-period exports; locked partitions write-revoked at the role level | chart required-values (`NFR-53`); TG-5 immutability proofs; MI-18 re-grant/revoke symmetry in the drill |
| PI / retention (design) | GDPR erasure vs immutability reconciliation, retention tiers | [retention & GDPR](retention-gdpr-tg5.md) |

## Machine-checked evidence (the SOC2 harness gate)

Executed by `scripts/verify-tg6.sh` after the live drill:

1. **Audit seals verify** — `SELECT * FROM eim_ops.verify_audit_seals()` →
   every sealed day `ok = true`.
2. **No unaudited mutation** — `eim_ops.reconcile_audit(now() - interval '1 day')`
   mutation/outbox/event counts with `gap` explained (unit-test fixtures
   aside, production gap must be 0).
3. **Tamper evidence** — the TG-5 audit pack's positive control: mutating an
   audit row breaks the recomputed chain.
4. **Dual-control evidence exists** — every `PERIOD_REOPEN` audit row pairs
   with ≥ 2 `MI18_REOPEN_APPROVAL` rows from distinct actors on the same
   request (checked by the SOC2 harness gate).
5. **Sign-off evidence hash** — each parallel sign-off carries `evidence_hash`
   over the exact diff set; the drill recomputes and asserts the hash and
   match rate at sign-off time (same rows ⇒ same hash), so the recorded
   evidence always describes the exact numbers signed.

For the external audit, each row of the matrix above maps to: control
description (this doc), evidence artifact (query/harness output + timestamp),
and the responsible role (on-call model in the GA posture doc).
