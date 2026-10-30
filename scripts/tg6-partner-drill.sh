#!/usr/bin/env bash
# TG6 parallel-run partner drill: the complete Phase 1 onboarding journey for
# a simulated partner tenant, driven end-to-end through the gateway with
# RevOps/admin APIs only (no engineering, ONB-1):
#   onboard + IdP posture -> backfill 2 historical periods (ONB-2) ->
#   cent-for-cent diff + triage (ONB-3) -> 100.0% sign-off x2 consecutive
#   periods (ONB-4) -> one live pay period through close + payroll export
#   (ONB-5) -> close-cycle telemetry (ONB-6) -> playbook dry-run MA-01/02/05/09
#   + MI-18 dual-approval reopen (ONB-8).
#
# Real-partner onboarding is the production step; this drill proves the same
# journey on deterministic simulated tenants, consistent with prior gates.
# Credentials are read at runtime from the gitignored .dev-secrets.env and
# passed to child processes as a connection URI built inside the container
# from the environment — no secret literals in this source file.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

TENANT="eeeeeeee-e000-0000-0000-000000000001"
MGR="eeeeeeee-e000-0000-0000-000000000002"
REP1="eeeeeeee-e000-0000-0000-000000000003"
REP2="eeeeeeee-e000-0000-0000-000000000004"
P_BF1="eeeeeeee-e000-0000-0000-000000000005"
P_BF2="eeeeeeee-e000-0000-0000-000000000006"
P_LIVE="eeeeeeee-e000-0000-0000-000000000007"
P_NOV="eeeeeeee-e000-0000-0000-000000000008"

PASS=0; FAIL=0
declare -a FAILURES=()
step() { local id="$1" desc="$2"; shift 2
  printf '\n=== [%s] %s\n' "$id" "$desc"
  if "$@"; then printf '>>> [%s] PASS\n' "$id"; PASS=$((PASS + 1))
  else printf '>>> [%s] FAIL\n' "$id"; FAIL=$((FAIL + 1)); FAILURES+=("$id: $desc"); fi
}
note() { printf '[tg6] %s\n' "$*"; }

PG_KEY=EIM_DEV_POSTGRES_PASSWORD
OIDC_KEY=EIM_DEV_OIDC_CLIENT_SECRET

load_runtime_secrets() {
  local base; base=".dev-secrets.env"
  [ -f "$base" ] || { echo "missing $base" >&2; return 1; }
  PG_TOKEN="$(sed -n "s/^${PG_KEY}=//p" "$base")"
  OIDC_TOKEN="$(sed -n "s/^${OIDC_KEY}=//p" "$base")"
  [ -n "$PG_TOKEN" ] && [ -n "$OIDC_TOKEN" ]
}

pg_docker() {
  local pass_env
  pass_env="$(printf '%s=%s' "$PG_KEY" "$PG_TOKEN")"
  docker run --rm -i --network host --env "$pass_env" \
    apache/age:release_PG16_1.6.0 \
    sh -c 'exec psql "postgresql://eim:${EIM_DEV_POSTGRES_PASSWORD}@127.0.0.1:15433/eim?sslmode=disable" -v ON_ERROR_STOP=1 "$@"' psql "$@"
}

gw() { echo "http://localhost:31234$1"; }
kc() { echo "http://localhost:31235$1"; }

TOKEN=""
client_token() {
  TOKEN=$(curl -s -X POST "$(kc /realms/eim/protocol/openid-connect/token)" \
    -H "Host: eim-keycloak:8080" \
    -d "grant_type=client_credentials&client_id=eim-gateway&client_secret=$OIDC_TOKEN" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
  [ -n "$TOKEN" ]
}

# api <role> <actor> <method> <path> [curl args...]
api() {
  local role="$1" actor="$2" method="$3" path="$4"; shift 4
  curl -s -m 60 -X "$method" "$(gw "$path")" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-Tenant-Id: $TENANT" \
    -H "X-Actor: $actor" \
    -H "X-Roles: $role" \
    "$@"
}

jq_get() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -1; }

# ---------------------------------------------------------------- ONB-1
PARTNER=""
onboard_partner() {
  note "registering simulated partner (IdP posture SAML_FEDERATED)"
  local resp
  resp=$(api comp_admin revops.admin POST /api/v1/partners \
    -H 'Content-Type: application/json' \
    -d '{"name":"Northwind Partners","idpEntityId":"https://idp.northwind.test/saml/metadata","idpPosture":"SAML_FEDERATED","payeeTarget":120}')
  PARTNER=$(echo "$resp" | jq_get partnerId)
  [ -n "$PARTNER" ] || { note "no partnerId: $resp"; return 1; }
  echo "$resp" | grep -q '"idpPosture":"SAML_FEDERATED"' || { note "posture: $resp"; return 1; }
  # payee may not onboard partners
  api payee rogue.payee POST /api/v1/partners -H 'Content-Type: application/json' \
    -d '{"name":"Rogue"}' | grep -q '"error"' || return 1
  note "partner $PARTNER registered"
}

autonomy_walk() {
  note "RevOps admin-autonomy: hierarchy + plan + connector ingest, APIs only"
  api comp_admin revops.admin POST /api/v1/org/payee -H 'Content-Type: application/json' \
    -d "{\"payeeId\":\"$MGR\",\"employeeNumber\":\"N-MGR\",\"fullName\":\"Northwind Manager\",\"email\":\"tg6.mgr@northwind.test\",\"roleTitle\":\"Mgr\",\"effectiveStart\":\"2026-01-01\"}" \
    | grep -q payeeId || return 1
  api comp_admin revops.admin POST /api/v1/org/payee -H 'Content-Type: application/json' \
    -d "{\"payeeId\":\"$REP1\",\"employeeNumber\":\"N-1\",\"fullName\":\"Northwind Rep A\",\"email\":\"tg6.rep1@northwind.test\",\"roleTitle\":\"AE\",\"managerPayeeId\":\"$MGR\",\"effectiveStart\":\"2026-01-01\"}" \
    | grep -q payeeId || return 1
  api comp_admin revops.admin POST /api/v1/org/payee -H 'Content-Type: application/json' \
    -d "{\"payeeId\":\"$REP2\",\"employeeNumber\":\"N-2\",\"fullName\":\"Northwind Rep B\",\"email\":\"tg6.rep2@northwind.test\",\"roleTitle\":\"AE\",\"managerPayeeId\":\"$MGR\",\"effectiveStart\":\"2026-01-01\"}" \
    | grep -q payeeId || return 1
  HASH=$(api comp_admin revops.admin POST /api/v1/plans/publish \
    -H 'Content-Type: application/json' --data-binary @scripts/tg6-partner-plan.json \
    | jq_get planVersionHash)
  [ -n "$HASH" ] || { note "publish failed"; return 1; }
  # config landed through the API path (read-side proof, no engineering writes)
  local payees plans
  payees=$(pg_docker -tAc "SELECT count(*) FROM payee WHERE tenant_id = '$TENANT'" | tr -d '[:space:]')
  plans=$(pg_docker -tAc "SELECT count(*) FROM plan_artifact WHERE tenant_id = '$TENANT' AND plan_version_hash = '$HASH'" | tr -d '[:space:]')
  note "payees=$payees planVersions=$plans hash=$HASH"
  [ "$payees" -ge 3 ] && [ "$plans" -ge 1 ]
}

# ---------------------------------------------------------------- ONB-2
seed_periods() {
  note "creating payout periods (registry data, not partner config)"
  pg_docker >/dev/null <<SQL
DELETE FROM parallel_signoff; DELETE FROM parallel_diff; DELETE FROM legacy_statement;
DELETE FROM period_reopen_request WHERE payout_period_id IN ('$P_BF1','$P_BF2','$P_LIVE');
DELETE FROM partner_tenant WHERE tenant_id = '$TENANT';
INSERT INTO payout_period (payout_period_id, tenant_id, period_key, starts_on, ends_on, status) VALUES
 ('$P_BF1','$TENANT','2026-bf1',DATE '2026-05-01',DATE '2026-05-31','OPEN'),
 ('$P_BF2','$TENANT','2026-bf2',DATE '2026-06-01',DATE '2026-06-30','OPEN'),
 ('$P_LIVE','$TENANT','2026-live',DATE '2026-10-01',DATE '2026-10-30','OPEN'),
 ('$P_NOV','$TENANT','2026-nov',DATE '2026-11-01',DATE '2026-11-30','OPEN');
SQL
}

ingest() { # ingest <objectName> <csv-with-real-newlines>
  local body
  body=$(printf '{"objectName":"%s","csv":"%s"}' "$1" "${2//$'\n'/\\n}")
  api comp_admin revops.admin POST /api/v1/ingestion/csv \
    -H 'Content-Type: application/json' -d "$body"
}

calc() { # calc <periodId>
  api comp_admin revops.admin POST /api/v1/calc/runs \
    -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"$TENANT\",\"payoutPeriodId\":\"$1\",\"planVersionHash\":\"$HASH\"}"
}

backfill_two_periods() {
  note "backfill: 2 historical periods from source-system extracts (May + June)"
  local r
  r=$(ingest tg6_bf1 "deal_id,close_date,amount,currency,payee_email
D-B1,2026-05-10,160000,USD,tg6.rep1@northwind.test
D-B2,2026-05-20,40000,USD,tg6.rep1@northwind.test
D-B3,2026-05-15,90000,USD,tg6.rep2@northwind.test")
  echo "$r" | grep -q '"ingested":3' || { note "bf1 ingest: $r"; return 1; }
  r=$(calc "$P_BF1"); echo "$r" | grep -q '"status":"COMPLETED"' || { note "bf1 calc: $r"; return 1; }
  r=$(ingest tg6_bf2 "deal_id,close_date,amount,currency,payee_email
D-B4,2026-06-10,120000,USD,tg6.rep1@northwind.test
D-B5,2026-06-20,30000,USD,tg6.rep2@northwind.test")
  echo "$r" | grep -q '"ingested":2' || { note "bf2 ingest: $r"; return 1; }
  r=$(calc "$P_BF2"); echo "$r" | grep -q '"status":"COMPLETED"' || { note "bf2 calc: $r"; return 1; }
  # lifecycle: ONBOARDING -> BACKFILLED now that history is loaded
  api comp_admin revops.admin POST "/api/v1/partners/$PARTNER/status" \
    -H 'Content-Type: application/json' -d '{"status":"BACKFILLED"}' \
    | grep -q BACKFILLED || return 1
  note "backfilled bf1 + bf2"
}

# ---------------------------------------------------------------- ONB-3/4
legacy_csv() { # legacy_csv <periodKey> [bumpPayee] [bumpAmount]
  local bump_payee="${2:-00000000-0000-0000-0000-000000000000}"
  local bump_amount="${3:-0}"
  pg_docker -tAc "SELECT payee_id::text || ',' || (sum(payout_amount) +
      CASE WHEN payee_id::text = '$bump_payee' THEN $bump_amount ELSE 0 END)::text
    FROM commission_ledger_entry l JOIN payout_period p USING (payout_period_id)
    WHERE p.period_key = '$1' AND p.tenant_id = '$TENANT'
    GROUP BY payee_id ORDER BY payee_id" | grep -v '^$' | sed 's/^ *//'
}

parallel_run_bf1() {
  note "bf1 parallel run: 1-cent discrepancy -> triage -> adjust -> 100%"
  local csv payload resp diffid
  csv=$(legacy_csv 2026-bf1 "$REP1" 0.01)
  printf '%s\n' "$csv" | note
  payload=$(printf 'payee_id,legacy_payout\n%s' "$csv")
  resp=$(api finance_approver parallel.fin POST \
    "/api/v1/parallel-run/partners/$PARTNER/periods/2026-bf1/legacy-statement" \
    -H 'Content-Type: text/csv' --data-binary "$payload")
  echo "$resp" | grep -q '"rows":3' || { note "legacy upload: $resp ($csv)"; return 1; }

  api comp_admin revops.admin POST "/api/v1/parallel-run/partners/$PARTNER/periods/2026-bf1/diff" \
    | grep -q '"discrepant":1' || return 1

  # triage: note mandatory then resolve
  diffid=$(api finance_approver parallel.fin GET \
    "/api/v1/parallel-run/partners/$PARTNER/periods/2026-bf1" \
    | sed -n 's/.*"diffId":"\([^"]*\)","payeeId":"'"$REP1"'.*/\1/p')
  [ -n "$diffid" ] || { note "no diffId for rep1"; return 1; }
  api finance_approver parallel.fin POST "/api/v1/parallel-run/diffs/$diffid/resolve" \
    -H 'Content-Type: application/json' -d '{"resolution":"RESOLVED_EXPLAINED"}' \
    | grep -q error || return 1
  api finance_approver parallel.fin POST "/api/v1/parallel-run/diffs/$diffid/resolve" \
    -H 'Content-Type: application/json' \
    -d '{"resolution":"RESOLVED_EXPLAINED","note":"legacy rounding on accelerator edge"}' \
    | grep -q RESOLVED_EXPLAINED || return 1

  # sign-off refused below 100%
  api finance_approver parallel.fin POST \
    "/api/v1/parallel-run/partners/$PARTNER/periods/2026-bf1/signoff" \
    | grep -q '100.0% cent-for-cent required' || return 1

  # align EIM to legacy through the audited override path, re-diff, sign off
  local original adjusted
  original=$(pg_docker -tAc "SELECT coalesce(sum(payout_amount),0)::text FROM commission_ledger_entry
    WHERE payout_period_id = '$P_BF1' AND payee_id = '$REP1'
    AND component_name = 'commission' AND entry_type = 'CALCULATED'" | tr -d '[:space:]')
  adjusted=$(pg_docker -tAc "SELECT ($original + 0.01)::text" | tr -d '[:space:]')
  api comp_admin revops.admin POST /api/v1/admin/overrides -H 'Content-Type: application/json' \
    -d "{\"payoutPeriodId\":\"$P_BF1\",\"payeeId\":\"$REP1\",\"componentName\":\"commission\",\"adjustedAmount\":\"$adjusted\",\"reasonCode\":\"PARALLEL_ADJ\",\"reasonComment\":\"align to legacy cent-for-cent\"}" \
    | grep -q overrideId || return 1
  api comp_admin revops.admin POST "/api/v1/parallel-run/partners/$PARTNER/periods/2026-bf1/diff" \
    | grep -q '"matched":3' || return 1
  api finance_approver parallel.fin POST \
    "/api/v1/parallel-run/partners/$PARTNER/periods/2026-bf1/signoff" \
    | tee /tmp/tg6-signoff1.json | grep -q '"matchRate":"100.0000"' || return 1
  grep -q '"evidenceHash":"' /tmp/tg6-signoff1.json || return 1
  # lifecycle: BACKFILLED -> PARALLEL_RUN
  api comp_admin revops.admin POST "/api/v1/partners/$PARTNER/status" \
    -H 'Content-Type: application/json' -d '{"status":"PARALLEL_RUN"}' \
    | grep -q PARALLEL_RUN || return 1
  note "bf1 signed off at 100.0%"
}

parallel_run_bf2() {
  note "bf2 parallel run: clean first diff -> second consecutive sign-off"
  local csv payload
  csv=$(legacy_csv 2026-bf2)
  payload=$(printf 'payee_id,legacy_payout\n%s' "$csv")
  api finance_approver parallel.fin POST \
    "/api/v1/parallel-run/partners/$PARTNER/periods/2026-bf2/legacy-statement" \
    -H 'Content-Type: text/csv' --data-binary "$payload" \
    | grep -q '"rows":3' || { note "bf2 upload: $csv"; return 1; }
  api comp_admin revops.admin POST "/api/v1/parallel-run/partners/$PARTNER/periods/2026-bf2/diff" \
    | grep -q '"matched":3' || return 1
  api finance_approver parallel.fin POST \
    "/api/v1/parallel-run/partners/$PARTNER/periods/2026-bf2/signoff" \
    | grep -q '"matchRate":"100.0000"' || return 1
  local n
  n=$(pg_docker -tAc "SELECT count(*) FROM parallel_signoff WHERE partner_id = '$PARTNER' AND match_rate = 100.0" | tr -d '[:space:]')
  [ "$n" = "2" ] || { note "signoffs=$n"; return 1; }
  # two consecutive 100% periods unlock SIGNED_OFF
  api comp_admin revops.admin POST "/api/v1/partners/$PARTNER/status" \
    -H 'Content-Type: application/json' -d '{"status":"SIGNED_OFF"}' \
    | grep -q SIGNED_OFF || return 1
  note "two consecutive cent-for-cent sign-offs recorded"
}

# ---------------------------------------------------------------- ONB-5
live_period() {
  note "live pay period: ingest -> calc -> MA-01/MA-02 -> close -> export"
  local r original adjusted
  r=$(ingest tg6_live "deal_id,close_date,amount,currency,payee_email
D-L1,2026-10-10,150000,USD,tg6.rep1@northwind.test
D-L2,2026-10-15,60000,USD,tg6.rep2@northwind.test")
  echo "$r" | grep -q '"ingested":2' || { note "live ingest: $r"; return 1; }
  calc "$P_LIVE" | grep -q '"status":"COMPLETED"' || return 1

  # MA-01 spot bonus (no dispute link; original preserved)
  original=$(pg_docker -tAc "SELECT coalesce(sum(payout_amount),0)::text FROM commission_ledger_entry
    WHERE payout_period_id = '$P_LIVE' AND payee_id = '$REP1'
    AND component_name = 'commission' AND entry_type = 'CALCULATED'" | tr -d '[:space:]')
  adjusted=$(pg_docker -tAc "SELECT ($original + 500)::text" | tr -d '[:space:]')
  api comp_admin revops.admin POST /api/v1/admin/overrides -H 'Content-Type: application/json' \
    -d "{\"payoutPeriodId\":\"$P_LIVE\",\"payeeId\":\"$REP1\",\"componentName\":\"commission\",\"adjustedAmount\":\"$adjusted\",\"reasonCode\":\"SPOT_BONUS\",\"reasonComment\":\"MA-01 president's club spot bonus\"}" \
    | grep -q '"originalAmount":"' || return 1

  # MA-02 dispute settlement: payee disputes, admin approves, override links the ticket
  local did
  did=$(api payee tg6.rep2 POST /api/v1/disputes -H 'Content-Type: application/json' \
    -H "X-Payee-Id: $REP2" \
    -d '{"category":"SOURCE_DATA","notes":"deal D-L2 amount wrong","symptom":"late_arrival"}' \
    | jq_get disputeId)
  [ -n "$did" ] || return 1
  api comp_admin revops.admin POST "/api/v1/disputes/$did/resolve" \
    -H 'Content-Type: application/json' \
    -d '{"resolution":"APPROVE","comment":"source corrected; settle"}' | grep -q APPROVED || return 1
  original=$(pg_docker -tAc "SELECT coalesce(sum(payout_amount),0)::text FROM commission_ledger_entry
    WHERE payout_period_id = '$P_LIVE' AND payee_id = '$REP2'
    AND component_name = 'commission' AND entry_type = 'CALCULATED'" | tr -d '[:space:]')
  adjusted=$(pg_docker -tAc "SELECT ($original + 250)::text" | tr -d '[:space:]')
  api comp_admin revops.admin POST /api/v1/admin/overrides -H 'Content-Type: application/json' \
    -d "{\"payoutPeriodId\":\"$P_LIVE\",\"payeeId\":\"$REP2\",\"componentName\":\"commission\",\"adjustedAmount\":\"$adjusted\",\"reasonCode\":\"DISPUTE_SETTLEMENT\",\"reasonComment\":\"MA-02 approved dispute settlement\",\"disputeId\":\"$did\"}" \
    | grep -q overrideId || return 1

  # period close process: review -> SoD sign-off -> lock -> export handoff
  local pid
  pid=$(api comp_admin revops.admin POST /api/v1/process/period-close/start \
    -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"$TENANT\",\"payoutPeriodId\":\"$P_LIVE\"}" | jq_get processId)
  [ -n "$pid" ] || { note "close start failed"; return 1; }
  api comp_admin revops.admin POST "/api/v1/process/period-close/$pid/tasks/comp_admin_review" \
    -H 'Content-Type: application/json' -d '{"comment":"reviewed"}' | grep -q finance_signoff || return 1
  api finance_approver revops.admin POST "/api/v1/process/period-close/$pid/tasks/finance_signoff" \
    -H 'Content-Type: application/json' -d '{"comment":"SoD: rejected same actor"}' | grep -q 403 && note "  (SoD enforced)"
  api finance_approver fin.chief POST "/api/v1/process/period-close/$pid/tasks/finance_signoff" \
    -H 'Content-Type: application/json' -d '{"comment":"signed"}' | grep -q period_lock || return 1
  api finance_approver fin.chief POST "/api/v1/process/period-close/$pid/tasks/period_lock" \
    -H 'Content-Type: application/json' -d '{}' | grep -q export_handoff || return 1
  api comp_admin revops.admin POST "/api/v1/process/period-close/$pid/tasks/export_handoff" \
    -H 'Content-Type: application/json' -d '{"comment":"handed off"}' | grep -q COMPLETED || return 1

  # payroll export from the locked period (byte-identical regeneration)
  local h1 h2
  h1=$(api finance_approver fin.chief POST /api/v1/admin/exports -H 'Content-Type: application/json' \
    -d "{\"periodId\":\"$P_LIVE\",\"template\":\"ADP\"}" | jq_get fileHash)
  h2=$(api finance_approver fin.chief POST /api/v1/admin/exports -H 'Content-Type: application/json' \
    -d "{\"periodId\":\"$P_LIVE\",\"template\":\"ADP\"}" | jq_get fileHash)
  [ -n "$h1" ] && [ "$h1" = "$h2" ] || { note "hash regen: $h1 vs $h2"; return 1; }
  PRE_REOPEN_HASH="$h1"

  # locked-period immutability: a fresh override is refused without break-glass
  api comp_admin revops.admin POST /api/v1/admin/overrides -H 'Content-Type: application/json' \
    -d "{\"payoutPeriodId\":\"$P_LIVE\",\"payeeId\":\"$REP1\",\"componentName\":\"commission\",\"adjustedAmount\":\"1\",\"reasonCode\":\"X\",\"reasonComment\":\"y\"}" \
    | grep -q 'break-glass reopen required' || return 1

  # lifecycle: SIGNED_OFF -> LIVE after one live pay period closed
  api comp_admin revops.admin POST "/api/v1/partners/$PARTNER/status" \
    -H 'Content-Type: application/json' -d '{"status":"LIVE"}' \
    | grep -q LIVE || return 1
  note "live period closed, exported (hash $h1), partner LIVE"
}

# ---------------------------------------------------------------- ONB-6
close_cycle_telemetry() {
  note "close-cycle telemetry (reconciliation + approval < 3 business days)"
  local resp
  resp=$(api finance_approver fin.chief GET /api/v1/parallel-run/close-cycle)
  echo "$resp" | head -c 400 | note
  echo "$resp" | grep -q '"periodKey":"2026-live"' || return 1
  echo "$resp" | grep -q '"withinSla":true' || return 1
}

# ---------------------------------------------------------------- ONB-8
playbook_ma05_ma09() {
  note "MA-05 corrected source data after lock -> true-up queue; MA-09 posting"
  local r tid
  r=$(ingest tg6_live_fix "deal_id,close_date,amount,currency,payee_email
D-L2,2026-10-15,65000,USD,tg6.rep2@northwind.test")
  echo "$r" | grep -q '"lateArriving":1' || { note "MA-05 late-arriving: $r"; return 1; }
  api finance_approver fin.chief POST /api/v1/calc/shadow-replay -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"$TENANT\",\"lockedPeriodId\":\"$P_LIVE\",\"openPeriodId\":\"$P_NOV\",\"planVersionHash\":\"$HASH\"}" \
    | grep -q '"diffs":' || return 1
  tid=$(api finance_approver fin.chief GET /api/v1/process/trueup | jq_get trueupId)
  [ -n "$tid" ] || { note "empty trueup queue"; return 1; }
  api finance_approver fin.chief POST "/api/v1/process/trueup/$tid/decision" \
    -H 'Content-Type: application/json' \
    -d '{"decision":"APPROVE","note":"MA-09 verified against corrected CRM extract"}' \
    | grep -q POSTED || return 1
  note "MA-05 routed to true-up; MA-09 posted into 2026-nov"
}

mi18_reopen_breakglass() {
  note "MI-18 break-glass: dual approval, corrected write, re-lock"
  local r
  # first approval: finance -> still pending
  r=$(api finance_approver fin.chief POST /api/v1/admin/periods/2026-live/reopen \
    -H 'Content-Type: application/json' \
    -d '{"impactMemo":"INC-77 October accelerator mis-tiered (P1, documented)"}')
  note "  fin: $r"
  echo "$r" | grep -q PENDING_DUAL_APPROVAL || return 1
  # dual control: the same person may not sign both sides
  r=$(api system_admin fin.chief POST /api/v1/admin/periods/2026-live/reopen \
    -H 'Content-Type: application/json' \
    -d '{"impactMemo":"INC-77 October accelerator mis-tiered (P1, documented)"}')
  note "  same-actor: $r"
  echo "$r" | grep -q 'two distinct approvers' || return 1
  # second approval: system_admin executes
  r=$(api system_admin sys.lead POST /api/v1/admin/periods/2026-live/reopen \
    -H 'Content-Type: application/json' \
    -d '{"impactMemo":"INC-77 October accelerator mis-tiered (P1, documented)"}')
  note "  sysadmin: $r"
  echo "$r" | grep -q '"status":"OPEN"' || return 1
  local priv
  priv=$(pg_docker -tAc "SELECT has_table_privilege('eim_app','ledger_2026_live','INSERT')" | tr -d '[:space:]')
  note "  partition writable after reopen: $priv"
  [ "$priv" = "t" ] || { note "partition not writable: $priv"; return 1; }
  # corrected write now flows (was 409 while locked)
  local original adjusted
  original=$(pg_docker -tAc "SELECT coalesce(sum(payout_amount),0)::text FROM commission_ledger_entry
    WHERE payout_period_id = '$P_LIVE' AND payee_id = '$REP1'
    AND component_name = 'commission' AND entry_type = 'CALCULATED'" | tr -d '[:space:]')
  adjusted=$(pg_docker -tAc "SELECT ($original + 125)::text" | tr -d '[:space:]')
  r=$(api comp_admin revops.admin POST /api/v1/admin/overrides -H 'Content-Type: application/json' \
    -d "{\"payoutPeriodId\":\"$P_LIVE\",\"payeeId\":\"$REP1\",\"componentName\":\"commission\",\"adjustedAmount\":\"$adjusted\",\"reasonCode\":\"MI18_CORRECTION\",\"reasonComment\":\"INC-77 corrected recalc delta\"}")
  note "  corrected override: $r"
  echo "$r" | grep -q overrideId || return 1
  # mandatory re-lock
  r=$(api finance_approver fin.chief POST /api/v1/admin/periods/2026-live/lock \
    -H 'Content-Type: application/json' -d '{"reason":"MI-18 re-lock after corrected recalc"}')
  note "  re-lock: $r"
  echo "$r" | grep -q '"status":"LOCKED"' || return 1
  # the post-correction export differs from the pre-reopen one (cent-level evidence)
  local h1 h2
  h1=$(api finance_approver fin.chief POST /api/v1/admin/exports -H 'Content-Type: application/json' \
    -d "{\"periodId\":\"$P_LIVE\",\"template\":\"ADP\"}" | jq_get fileHash)
  h2=$(api finance_approver fin.chief POST /api/v1/admin/exports -H 'Content-Type: application/json' \
    -d "{\"periodId\":\"$P_LIVE\",\"template\":\"ADP\"}" | jq_get fileHash)
  [ -n "$h1" ] && [ "$h1" = "$h2" ] || return 1
  [ "$h1" != "$PRE_REOPEN_HASH" ] || { note "export unchanged after correction"; return 1; }
  note "reopened (dual), corrected, re-locked, export hash changed as expected"
}

audit_trail() {
  note "audit-plane assertions for the whole journey"
  local q v
  q() { pg_docker -tAc "SELECT count(*) FROM $1" | tr -d '[:space:]'; }
  v=$(q "audit_log WHERE action_type='CONFIG_CHANGE' AND reason_code='PARTNER_ONBOARD'")
  [ "$v" -ge 1 ] || { note "PARTNER_ONBOARD=$v"; return 1; }
  v=$(q "audit_log WHERE action_type='APPROVAL' AND reason_code='PARALLEL_SIGNOFF'")
  [ "$v" -ge 2 ] || { note "PARALLEL_SIGNOFF=$v"; return 1; }
  v=$(q "audit_log WHERE action_type='MANUAL_ADJUSTMENT'")
  [ "$v" -ge 4 ] || { note "MANUAL_ADJUSTMENT=$v (align+MA01+MA02+MI18)"; return 1; }
  v=$(q "audit_log WHERE action_type='APPROVAL' AND reason_code='MI18_REOPEN_APPROVAL'")
  [ "$v" -ge 2 ] || { note "MI18 approvals=$v"; return 1; }
  v=$(q "audit_log WHERE action_type='PERIOD_REOPEN'")
  [ "$v" -ge 1 ] || { note "PERIOD_REOPEN=$v"; return 1; }
  v=$(q "transactional_outbox WHERE aggregate_type='eim.period.reopened'")
  [ "$v" -ge 1 ] || { note "reopen outbox=$v"; return 1; }
  v=$(q "parallel_signoff WHERE match_rate = 100.0")
  [ "$v" = "2" ] || { note "signoffs=$v"; return 1; }
  v=$(q "export_run WHERE tenant_id='$TENANT'")
  [ "$v" -ge 4 ] || { note "export runs=$v"; return 1; }
}

cleanup_drill() {
  note "cleaning partner drill fixture"
  pg_docker >/dev/null 2>&1 <<SQL
DELETE FROM parallel_signoff; DELETE FROM parallel_diff; DELETE FROM legacy_statement;
DELETE FROM period_reopen_request WHERE payout_period_id IN ('$P_BF1','$P_BF2','$P_LIVE','$P_NOV');
DELETE FROM partner_tenant WHERE tenant_id = '$TENANT';
DELETE FROM commission_ledger_entry WHERE tenant_id = '$TENANT';
DELETE FROM manual_override WHERE tenant_id = '$TENANT';
DELETE FROM calc_run WHERE tenant_id = '$TENANT';
DELETE FROM trueup_review WHERE tenant_id = '$TENANT';
DELETE FROM export_run WHERE tenant_id = '$TENANT';
DELETE FROM dispute_ticket WHERE tenant_id = '$TENANT';
DELETE FROM credit_allocation WHERE transaction_id IN (SELECT transaction_id FROM raw_transaction WHERE tenant_id = '$TENANT');
DELETE FROM raw_transaction WHERE tenant_id = '$TENANT';
SELECT eim_ops.detach_delete_payee(p.payee_id) FROM payee p WHERE p.tenant_id = '$TENANT';
DELETE FROM payee WHERE tenant_id = '$TENANT';
DELETE FROM payout_period WHERE tenant_id = '$TENANT';
DELETE FROM process_task WHERE process_id IN (SELECT process_id FROM process_instance WHERE tenant_id = '$TENANT');
DELETE FROM process_instance WHERE tenant_id = '$TENANT';
SQL
}

HASH=""; PRE_REOPEN_HASH=""
echo "TG-6 partner drill — $(date -u +%Y-%m-%dT%H:%M:%SZ)"
step TG6-ENV "runtime secrets (gitignored file)" load_runtime_secrets
step TG6-ENV "gateway client token (Host-pinned issuer)" client_token
step TG6-ENV "clean + create payout periods" seed_periods
step ONB-1 "partner onboarding registry + IdP posture (RBAC enforced)" onboard_partner
step ONB-1 "RevOps admin autonomy: hierarchy + plan via APIs only" autonomy_walk
step ONB-2 "backfill 2 historical periods (ingest + calc per period)" backfill_two_periods
step ONB-3 "bf1 diff + discrepancy triage (note mandatory)" parallel_run_bf1
step ONB-4 "bf2 clean diff -> 2nd consecutive 100% sign-off + SIGNED_OFF" parallel_run_bf2
step ONB-5 "live pay period: MA-01/02 + close process + payroll export" live_period
step ONB-6 "close-cycle telemetry within SLA (< 3 business days)" close_cycle_telemetry
step ONB-8 "MA-05 corrected source after lock + MA-09 true-up posting" playbook_ma05_ma09
step ONB-8 "MI-18 dual-approval reopen -> corrected write -> re-lock" mi18_reopen_breakglass
step ONB-AUD "audit-plane assertions (partner/signoff/MA/MI-18 trails)" audit_trail
step TG6-ENV "drill fixture cleanup" cleanup_drill

printf '\nTG-6 partner drill: %d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then printf 'Failures:\n'; for f in "${FAILURES[@]}"; do printf '  - %s\n' "$f"; done; exit 1; fi
echo "PARTNER DRILL GREEN"
