# SOC 2 Type II & Data Residency — TG-7 (SEC-1)

Status: **live**. Extends the [Phase 1 SOC 2 controls mapping](../soc2-controls-tg6.md)
with the Type II operating-effectiveness evidence Phase 2 adds, and declares
the data-residency deployment option (NFR-72).

## 1. From Type I mapping to Type II operating effectiveness

Type II audits require evidence that controls **operated** over a period —
not just that they exist. The platform's evidence generators produce
timestamped, queryable artifacts for every control day-by-day:

| Control | Type II operating evidence (regenerable per day/period) |
|---|---|
| CC6.1 access enforcement | `audit_log` rows for every API mutation (actor + correlation id); the TG-5 red-team probe re-runnable any day; `RlsRoleRegressionTest` in CI |
| CC6.1 attachment malware gate (new, FR-704.2) | every scan leaves an `AUTH_EVENT` audit row (`ATTACHMENT_SCAN_CLEAN`/`ATTACHMENT_SCAN_BLOCKED`) — the blocked-vs-clean ratio per day is the operating record; EICAR positive-control test in `SecurityOpsTest` |
| CC7.2 anomaly detection | `eim_ops.reconcile_audit(since)` runs per-minute; seals verify per UTC day (V12); the audit pack's tamper positive control proves detection works |
| CC7.3 break-glass discipline | every MI-18 reopen pairs `PERIOD_REOPEN` with two distinct-actor `MI18_REOPEN_APPROVAL` rows — the dual-control pairing query in the TG-6 harness is the Type II sample generator |
| CC7.4 recovery | chaos/DR drill scripts run on demand; each run's output (RTO, integrity parity) is retained as scan evidence |
| CC8.1 change management | classification decision-table edits are versioned + audited (`CLASSIFICATION_TABLE_EDIT` with `dmnVersion`); `check-migrations.sh` gates every merge |
| PI/erasure (GDPR, NFR-71) | each erasure request audited as `GDPR_ERASURE` with `ledger_rows_preserved` — the pseudonymization-vs-immutability reconciliation is itself the evidence trail |
| CC9.2 availability | SLO/alert history + close-cycle telemetry per period |

The external auditor samples by date from `audit_log`/`event_audit_log`;
the SOC2 harness gate (verify-tg7) re-checks seals + dual-control pairing +
action coverage on each run, so the mapping cannot silently rot during the
observation window.

## 2. Data residency deployment option (NFR-72)

Cloud-agnostic constraint (AD-6) holds: residency is a **deployment
posture**, not a code path.

| Concern | Mechanism |
|---|---|
| Regional isolation | One helm release per region; `EIM_DATA_RESIDENCY` labels the deployment and is reported by `GET /api/v1/security/residency` (postgres/objectstore in-region flags, cross-region replication off) |
| No cross-region data path | No managed multi-region services in the stack (constraint AD-6); Kafka, postgres (AGE) and the object store all deploy per-release |
| Tenant pinning | Partners assigned to a regional deployment at onboarding (the TG-6 partner registry records the posture); no tenant spans releases |
| Backups | Per-region backup CronJob with the release-scoped encryption key (existing TG-1 mechanism) |

Dev cluster runs the default posture (`dataResidency: default`); a staging
residency rehearsal deploys the same umbrella chart with
`EIM_DATA_RESIDENCY=eu-central` and asserts the report — the portability
matrix (TG-5) already proves chart portability across clusters.

## 3. Machine-checked evidence (TG-7 SOC2 harness gates)

Executed by `scripts/verify-tg7.sh`:

1. Seals verify (all real UTC days ok; GENESIS tamper control still failing
   by design) — carried over from TG-6.
2. Dual-control pairing for every `PERIOD_REOPEN` (≥2 distinct approvers).
3. `ATTACHMENT_SCAN_BLOCKED` rows exist for the drill's EICAR upload — the
   malware gate demonstrably operated.
4. A `GDPR_ERASURE` audit row with `ledger_rows_preserved > 0` exists from
   the drill — erasure/immmutability reconciliation operated.
5. Action-type coverage now includes the Phase-2 actions:
   `ASC606_CLASSIFICATION`, `JE_GENERATION`, `GL_PUSH`, `TRUEUP_POSTING`,
   `ERP_READ`, `METERING_INGEST`, `SLA_ESCALATION*`, `GDPR_ERASURE`,
   `ATTACHMENT_SCAN_*`.
