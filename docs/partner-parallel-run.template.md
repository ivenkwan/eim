# Partner Parallel-Run Configuration Template

Fill per partner before the first parallel period. This template mirrors
the fields the onboarding automation (`scripts/onboard-partner.sh`) and the
TG-6/TG-8 probes consume — copy it into the partner's onboarding ticket
and complete every field before invoking the script.

```yaml
partner:
  name:                       # required — partner registry display name
  tenant_id:                  # required — the isolated tenant UUID
  idp:
    posture: GATEWAY_OIDC     # GATEWAY_OIDC | SAML_FEDERATED
    entity_id:                # SAML entity ID (SAML_FEDERATED only)
    cert_cn:                  # channel partners only — mTLS client-cert CN

plan:
  # either supply plan.json (publishes through the FR-406 gate during
  # onboarding) or pin an already-published version hash
  plan_file: plan.json
  plan_version_hash:          # takes precedence when set

backfill:
  source: varicent            # varicent | xactly | anaplan (record-typed CSV)
  legacy_csv: legacy.csv      # 2+ historical periods of payouts + hierarchy
  dry_run_first: true         # scripts/migration-dryrun.sh before the real import

parallel_run:
  periods:                    # the two consecutive sign-off periods
    - period_key:
      legacy_statement_csv:   # per-payee legacy totals for the diff
    - period_key:
      legacy_statement_csv:
  sign_off_role: finance_approver   # who records the 100% sign-off
  diff_triage_note_required: true   # discrepancies need a note before re-diff

cutover:
  checklist: scripts/cutover-checklist.sh <tenant-id> <partner-name>
  hypercare_weeks: 2           # first 2 EIM-native periods per the runbook
```

## Invocation order

1. `scripts/migration-dryrun.sh <legacy_csv> <source>` — isolated report,
   reviewed by RevOps; no partner tenant is touched.
2. `scripts/onboard-partner.sh <name> <tenant> [posture] [entity] [cn]` —
   registry, hierarchy, plan, smoke ingest, backfill, first calc.
3. TG-6 parallel-run walk per period (upload legacy statement → diff →
   triage → sign-off) — `scripts/tg6-partner-drill.sh` documents the
   journey; run it against the partner tenant for each real period.
4. `scripts/cutover-checklist.sh <tenant> <name>` — the executable C1–C8;
   a FAIL blocks cutover.

## Field notes

- `plan.plan_file` must pass the FR-406 publish gate (type-check, tier
  contiguity); a failed publish aborts onboarding at step 2 with the gate
  errors — no partial plan ships.
- `backfill.dry_run_first: true` is the default posture; the dry-run
  report is the go/no-go evidence for the checklist's C-gates.
- `parallel_run.sign_off_role` must differ from the actor who ran the diff
  (SoD) — the sign-off endpoint enforces the role, the runbook enforces
  the person.
