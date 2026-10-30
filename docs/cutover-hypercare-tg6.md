# Legacy Cutover Checklist & Hypercare Rotation — TG-6 (ONB-7)

Status: **Phase 1 GA procedure — adopted**. The mechanical steps below are
exactly what `scripts/tg6-partner-drill.sh` proves on a simulated partner
(14/14 gates) and what a real partner cutover follows. Every step lands in
the audit plane (`CONFIG_CHANGE`/`PARALLEL_SIGNOFF`/`MANUAL_ADJUSTMENT`/
`PERIOD_REOPEN`/`APPROVAL`), so an auditor can replay a cutover from the
audit viewer alone.

## 1. Cutover preconditions (gate before the checklist starts)

| # | Condition | Evidence source |
|---|---|---|
| P1 | Partner registered with IdP posture decided (GATEWAY_OIDC or SAML_FEDERATED + entity ID) | `partner_tenant` + `CONFIG_CHANGE` audit |
| P2 | RevOps completed hierarchy, plans and connectors via APIs only (no engineering tickets) | `/api/v1/org`, `/api/v1/plans/publish`, `/api/v1/ingestion` audit rows |
| P3 | Two historical periods backfilled from source-system extracts | periods `2026-bf1`/`2026-bf2` pattern; `calc_run` COMPLETED |
| P4 | Parallel-run sign-offs: **two consecutive periods at 100.0% cent-for-cent** with recorded evidence hash | `parallel_signoff` (`match_rate = 100.0`, `evidence_hash`) |
| P5 | One live pay period closed (< 3 business days) and payroll-exported | `payout_period.locked_at`, `export_run.file_hash` |
| P6 | Runbook + manual-intervention playbook dry-run completed (MA-01/02/05/09, MI-18) | `MANUAL_ADJUSTMENT`, `TRUEUP_POSTING`, `PERIOD_REOPEN` audit rows |

## 2. Cutover checklist (per partner)

| # | Step | Owner | System action | Verification |
|---|---|---|---|---|
| C1 | Freeze legacy plan edits in the source system | Partner RevOps | — | change freeze ticket |
| C2 | Final parallel-period diff + triage to zero open discrepancies | Comp Admin | `POST /api/v1/parallel-run/.../diff`, triage rows resolved | diff report `matchRate = 100.00` |
| C3 | Finance sign-off on the final parallel period | Finance Approver | `POST .../signoff` (refuses < 100%) | `parallel_signoff` row + `APPROVAL` audit |
| C4 | Advance partner lifecycle SIGNED_OFF → LIVE | Comp Admin | `POST /api/v1/partners/{id}/status` | one-step rule; audit `PARTNER_LIFECYCLE` |
| C5 | Cut legacy statement production; legacy becomes read-only reference | Partner IT | — | legacy extract archived to objectstore |
| C6 | First EIM-native pay period runs end-to-end (ingest → calc → close → export) | Comp Admin + Finance | existing APIs | `process_instance` COMPLETED, `export_run` |
| C7 | Payroll confirms receipt of EIM export (ADP/Workday/NetSuite template) | Payroll | file hash comparison | hash from `export_run` |
| C8 | Decommission legacy calculation jobs (after 2 clean EIM-native periods) | Partner IT | — | change ticket |

Rollback at any step: revert C4 (`partner_tenant.status` is lifecycle-gated,
one step at a time), resume legacy statements — EIM data is never destroyed;
locked periods stay immutable.

## 3. Hypercare rotation (first 2 EIM-native periods per partner)

Coverage model mirrors the on-call rotation in
[GA deployment posture](ga-deployment-tg6.md):

| Slot | Primary | Backup | Focus |
|---|---|---|---|
| Weekday business hours | EIM Comp Ops (RevOps) | EIM Engineering | statement questions, override/true-up walk-throughs, diff triage on the first live periods |
| Weekday after-hours | EIM on-call primary | EIM on-call secondary | alerts (SLO burn, audit gap > 0, consumer lag), MI-18 break-glass assessment |
| Weekend | EIM on-call secondary | Engineering lead | ingest delays, export handoff to payroll |

Hypercare-specific procedures:

- **Daily (first period):** run the close-cycle telemetry
  (`GET /api/v1/parallel-run/close-cycle`) in the morning standup; any period
  projected past 3 business days escalates to the incident process (P3).
- **Diff discipline:** any live-period discrepancy the partner reports goes
  through the same triage workflow as the parallel run (note mandatory);
  do NOT hand-edit statements outside the override path.
- **MI-18 reopen during hypercare:** allowed only under the documented P1
  procedure — dual approval (Finance Approver AND System Admin, two distinct
  people), impact memo, re-snapshot, corrected recalc, mandatory re-lock.
  The drill proves the corrected export hash changes afterwards (cent-level
  evidence the correction landed).
- **Exit criteria for hypercare:** two consecutive EIM-native periods closed
  inside SLA with zero P1/P2 incidents and no open diff discrepancies; then
  the partner drops to standard support.

## 4. What the drill already proved (dev evidence)

`scripts/tg6-partner-drill.sh` — 14 gates, all green through the gateway:

```
TG6-ENV runtime secrets / token / period fixtures
ONB-1   partner onboarding registry + IdP posture (RBAC enforced)
ONB-1   RevOps admin autonomy: hierarchy + plan via APIs only
ONB-2   backfill 2 historical periods (ingest + calc per period)
ONB-3   bf1 diff + discrepancy triage (note mandatory)
ONB-4   bf2 clean diff -> 2nd consecutive 100% sign-off + SIGNED_OFF
ONB-5   live pay period: MA-01/02 + close process + payroll export
ONB-6   close-cycle telemetry within SLA (< 3 business days)
ONB-8   MA-05 corrected source after lock + MA-09 true-up posting
ONB-8   MI-18 dual-approval reopen -> corrected write -> re-lock
ONB-AUD audit-plane assertions (partner/signoff/MA/MI-18 trails)
```

Real-partner production cutover follows the identical checklist with the
partner's own tenant, users and IdP federation — the mechanism is identical,
which is the point of the simulated drill.
