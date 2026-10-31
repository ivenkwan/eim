#!/usr/bin/env bash
# TG8 live probe: the Phase-3 SPM journey through the gateway — territory &
# quota design + what-if preview, account-routing feeding credit assignment,
# partner identity (mTLS CN) + statement + self-service dispute, channel
# claim through accrual on the core ledger, NL plan modeling with the
# SoD-guarded human approval gate, AI plan diagnostics (windfall/cliff/
# gaming), the trace-grounded earnings explainer with dispute handoff,
# Monte Carlo simulation with liability bands + windfall detection, and the
# Varicent migration importer landing idempotent history.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

TENANT="dddddddd-f000-0000-0000-000000000001"
MGR="dddddddd-f000-0000-0000-000000000002"
REP="dddddddd-f000-0000-0000-000000000003"
P_JUL="dddddddd-f000-0000-0000-000000000004"
PLAN_HASH="p3sim00000000000000000000000000000000000000000000000000000000tg8"

PASS=0; FAIL=0
declare -a FAILURES=()
step() { local id="$1" desc="$2"; shift 2
  printf '\n=== [%s] %s\n' "$id" "$desc"
  if "$@"; then printf '>>> [%s] PASS\n' "$id"; PASS=$((PASS + 1))
  else printf '>>> [%s] FAIL\n' "$id"; FAIL=$((FAIL + 1)); FAILURES+=("$id: $desc"); fi
}
note() { printf '[tg8] %s\n' "$*"; }

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

api() {
  local role="$1" actor="$2" method="$3" path="$4"; shift 4
  curl -s -m 120 -X "$method" "$(gw "$path")" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-Tenant-Id: $TENANT" \
    -H "X-Actor: $actor" \
    -H "X-Roles: $role" \
    "$@"
}

jq_get() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -1; }

seed() {
  note "seeding Phase-3 fixture (payees, deals, published plan, locked period)"
  pg_docker <<SQL
DELETE FROM channel_claim WHERE tenant_id = '$TENANT';
DELETE FROM channel_program WHERE tenant_id = '$TENANT';
DELETE FROM partner_account WHERE tenant_id = '$TENANT';
DELETE FROM territory_assignment WHERE tenant_id = '$TENANT';
DELETE FROM quota_allocation WHERE tenant_id = '$TENANT';
DELETE FROM routing_rule WHERE tenant_id = '$TENANT';
DELETE FROM territory WHERE tenant_id = '$TENANT';
DELETE FROM nl_model_request WHERE tenant_id = '$TENANT';
DELETE FROM plan_diagnostic WHERE tenant_id = '$TENANT';
DELETE FROM simulation_payee_outcome;
DELETE FROM simulation_iteration;
DELETE FROM simulation_run WHERE tenant_id = '$TENANT';
DELETE FROM commission_ledger_entry WHERE tenant_id = '$TENANT';
DELETE FROM credit_allocation WHERE transaction_id IN (SELECT transaction_id FROM raw_transaction WHERE tenant_id = '$TENANT');
DELETE FROM raw_transaction WHERE tenant_id = '$TENANT';
SELECT eim_ops.detach_delete_payee(p.payee_id) FROM payee p WHERE p.tenant_id = '$TENANT';
DELETE FROM payee WHERE tenant_id = '$TENANT';
DELETE FROM payout_period WHERE tenant_id = '$TENANT';
INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, role_title, effective_start_date)
VALUES ('$MGR','$TENANT','P3-M','P3 Manager','p3.m@example.test','Mgr',DATE '2026-01-01'),
       ('$REP','$TENANT','P3-1','P3 Rep','p3.r@example.test','AE',DATE '2026-01-01');
INSERT INTO payout_period (payout_period_id, tenant_id, period_key, starts_on, ends_on, status)
VALUES ('$P_JUL','$TENANT','2027-p3',DATE '2027-01-01','2027-01-31','OPEN');
SELECT eim_ops.ensure_period_partition('$P_JUL','2027-p3');
INSERT INTO raw_transaction (transaction_id, tenant_id, source_system, external_deal_id, transaction_date, booking_amount, currency_code, ingestion_batch_id)
SELECT (regexp_replace(md5('p3-t-' || g), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5'))::uuid,
       '$TENANT','CSV','P3-' || g, DATE '2027-01-15', 60000 + (g % 4) * 10000, 'USD',
       (regexp_replace(md5('p3-b'), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5'))::uuid
FROM generate_series(1, 8) g;
INSERT INTO credit_allocation (credit_allocation_id, tenant_id, transaction_id, payee_id, split_pct, effective_date)
SELECT gen_random_uuid(), '$TENANT', t.transaction_id, '$REP', 1, t.transaction_date
FROM raw_transaction t WHERE t.tenant_id = '$TENANT';
INSERT INTO commission_ledger_entry (ledger_id, tenant_id, payout_period_id, payee_id, component_name, payout_amount, entry_type, calculation_trace, is_locked)
VALUES (gen_random_uuid(),'$TENANT','$P_JUL','$REP','commission',9600,'CALCULATED',
 CAST('{"steps":[{"component":"commission","kind":"TIER_TABLE","slices":[{"tierName":"Tier 1","sliceAmount":"90000","rateApplied":"0.10","slicePayout":"9000"},{"tierName":"Tier 2","sliceAmount":"5000","rateApplied":"0.15","slicePayout":"750"}]}]}' AS jsonb),false);
ALTER TABLE plan_artifact DISABLE TRIGGER trg_plan_artifact_append_only;
DELETE FROM plan_artifact WHERE plan_version_hash = '$PLAN_HASH';
INSERT INTO plan_artifact (plan_version_hash, tenant_id, plan_name, plan_json, model_json, drl, helper_java, dmn, published_at)
VALUES ('$PLAN_HASH','$TENANT','p3sim','{}',
 CAST('{"planName":"p3sim","components":[{"name":"commission","kind":"TIER_TABLE","outputType":"CURRENCY","tiers":{"mode":"MARGINAL","basis":"AMOUNT","brackets":[{"upTo":"100000","rate":"0.10"},{"rate":"0.20"}]}}]}' AS jsonb),
 'stub','stub','stub', now());
ALTER TABLE plan_artifact ENABLE TRIGGER trg_plan_artifact_append_only;
SQL
}

TERRITORY=""
territories_routing_and_quota() {
  note "territory + assignment + quota + routing rule + resolve"
  TERRITORY=$(api comp_admin p3.admin POST /api/v1/spm/territories \
    -H 'Content-Type: application/json' -d '{"name":"West Enterprise"}' | jq_get territoryId)
  [ -n "$TERRITORY" ] || { note "territory create failed"; return 1; }
  api comp_admin p3.admin POST "/api/v1/spm/territories/$TERRITORY/assignments" \
    -H 'Content-Type: application/json' -d "{\"payeeId\":\"$REP\",\"effectiveStart\":\"2027-01-01\"}" \
    | grep -q payeeId || return 1
  api comp_admin p3.admin POST "/api/v1/spm/territories/$TERRITORY/assignments" \
    -H 'Content-Type: application/json' -d '{"accountKey":"ACME-1001"}' | grep -q accountKey || return 1
  api comp_admin p3.admin POST "/api/v1/spm/territories/$TERRITORY/quotas" \
    -H 'Content-Type: application/json' -d '{"periodKey":"2027-p3","quotaAmount":"1200000"}' \
    | grep -q '"quotaAmount":"1200000"' || return 1
  api comp_admin p3.admin POST /api/v1/spm/routing \
    -H 'Content-Type: application/json' \
    -d "{\"priority\":10,\"matchAccountKey\":\"ACME-1001\",\"routeToTerritoryId\":\"$TERRITORY\"}" \
    | grep -q ruleId || return 1
  api comp_admin p3.admin GET "/api/v1/spm/routing/resolve?accountKey=ACME-1001" \
    | grep -q "\"payeeId\":\"$REP\"" || return 1
  api comp_admin p3.admin GET "/api/v1/spm/routing/resolve?accountKey=UNKNOWN" \
    | grep -q '"payees":\[\]' || return 1
  api comp_admin p3.admin POST /api/v1/spm/territories/realignment-preview \
    -H 'Content-Type: application/json' -d '{}' | grep -q '"preview":true' || return 1
  note "account ACME-1001 routes to $REP via territory $TERRITORY"
}

partner_portal_and_claims() {
  note "partner identity + statement + dispute + claim accrual"
  api comp_admin p3.admin POST /api/v1/channel/partners \
    -H 'Content-Type: application/json' \
    -d '{"orgName":"Acme Channel Co","certCn":"partner.acme.example.test"}' | grep -q partnerPayeeId || return 1
  local partner
  partner=$(pg_docker -tAc "SELECT partner_payee_id FROM partner_account WHERE cert_cn='partner.acme.example.test'" | tr -d '[:space:]')
  [ -n "$partner" ] || return 1
  curl -s -m 30 "$(gw /api/v1/channel/partners/whoami)" \
    -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT" \
    -H "X-Actor: partner" -H "X-Roles: partner" \
    -H "X-Client-Cert-CN: partner.acme.example.test" \
    | grep -q '"role":"partner"' || return 1
  curl -s -m 30 "$(gw /api/v1/channel/partners/whoami)" \
    -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT" \
    -H "X-Actor: partner" -H "X-Roles: partner" | grep -q '"error"' || return 1

  curl -s -m 30 -X POST "$(gw "/api/v1/channel/partners/$partner/disputes")" \
    -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT" -H "X-Payee-Id: $partner" \
    -H "X-Actor: partner" -H "X-Roles: partner" -H 'Content-Type: application/json' \
    -d '{"category":"SOURCE_DATA","notes":"missing rebate"}' | grep -q SUBMITTED || return 1

  pg_docker >/dev/null 2>&1 <<SQL
INSERT INTO channel_program (program_code, tenant_id, name, kind, rate, min_claim, requires_proof)
VALUES ('ACME_REBATE','$TENANT','Acme rebate','REBATE',0.05,1000,true)
ON CONFLICT DO NOTHING;
SQL
  local claim
  claim=$(api comp_admin p3.admin POST /api/v1/channel/claims \
    -H 'Content-Type: application/json' \
    -d "{\"partnerPayeeId\":\"$partner\",\"programCode\":\"ACME_REBATE\",\"claimPeriodKey\":\"2027-p3\",\"claimedAmount\":80000}" \
    | jq_get claimId)
  [ -n "$claim" ] || { note "claim submit failed"; return 1; }
  api finance_approver p3.fin POST "/api/v1/channel/claims/$claim/approve" \
    -H 'Content-Type: application/json' -d '{"comment":"ok"}' \
    | grep -q '"accrualAmount":"4000.0000"' || return 1
  curl -s -m 30 "$(gw "/api/v1/channel/partners/$partner/statement")" \
    -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT" -H "X-Payee-Id: $partner" \
    -H "X-Actor: partner" -H "X-Roles: partner" | grep -q '"totalPayout":"4000.0000"' || return 1
  note "claim accrued 4000 and lands on the partner statement"
}

nl_modeling_with_human_gate() {
  note "NL → draft plan → SoD approval gate"
  local req
  req=$(api finance_approver p3.fin POST /api/v1/ai/plan-modeling/requests \
    -H 'Content-Type: application/json' \
    -d '{"prompt":"create accelerator plan: 5% up to 100000 then 8% above, cap 60000"}')
  echo "$req" | grep -q '"compileStatus":"COMPILED"' || { note "compile: $req"; return 1; }
  echo "$req" | grep -q '"approvalStatus":"PENDING_HUMAN"' || return 1
  local rid
  rid=$(echo "$req" | jq_get requestId)
  api finance_approver p3.fin POST "/api/v1/ai/plan-modeling/requests/$rid/decision" \
    -H 'Content-Type: application/json' -d '{"decision":"APPROVED"}' \
    | grep -q 'cannot approve their own' || return 1
  api finance_approver p3.chief POST "/api/v1/ai/plan-modeling/requests/$rid/decision" \
    -H 'Content-Type: application/json' -d '{"decision":"APPROVED"}' \
    | grep -q '"approvalStatus":"APPROVED"' || return 1
  pg_docker -tAc "SELECT count(*) FROM audit_log WHERE reason_code='NL_PLAN_COMPILED'" | grep -qE '[1-9]' || return 1
  note "NL draft approved by a distinct human (SoD enforced)"
}

diagnostics_and_explainer() {
  note "plan diagnostics + trace-grounded explainer"
  api finance_approver p3.fin POST "/api/v1/ai/plan-diagnostics/$PLAN_HASH" \
    -H 'Content-Type: application/json' -d '{}' | grep -q '"clean":true' || return 1
  local windy
  windy="wind000000000000000000000000000000000000000000000000000000000tg8"
  pg_docker >/dev/null 2>&1 <<SQL
ALTER TABLE plan_artifact DISABLE TRIGGER trg_plan_artifact_append_only;
DELETE FROM plan_artifact WHERE plan_version_hash = '$windy';
INSERT INTO plan_artifact (plan_version_hash, tenant_id, plan_name, plan_json, model_json, drl, helper_java, dmn, published_at)
VALUES ('$windy','$TENANT','windy','{}',
 CAST('{"planName":"windy","components":[{"name":"c","kind":"TIER_TABLE","tiers":{"brackets":[{"upTo":"100000","rate":"0.05"},{"rate":"0.40"}]}},{"name":"team","kind":"ROLLUP","dependsOn":["c"]}]}' AS jsonb),
 'stub','stub','stub', now());
ALTER TABLE plan_artifact ENABLE TRIGGER trg_plan_artifact_append_only;
SQL
  local out
  out=$(api finance_approver p3.fin POST "/api/v1/ai/plan-diagnostics/$windy" \
    -H 'Content-Type: application/json' -d '{}')
  echo "$out" | grep -q WINDFALL || return 1
  echo "$out" | grep -q CLIFF || return 1
  echo "$out" | grep -q GAMING || return 1
  pg_docker -tAc "SELECT count(*) FROM plan_diagnostic WHERE plan_version_hash='$windy'" | grep -q 3 || return 1
  local ledger
  ledger=$(pg_docker -tAc "SELECT ledger_id FROM commission_ledger_entry WHERE tenant_id='$TENANT' AND entry_type='CALCULATED' LIMIT 1" | tr -d '[:space:]')
  local exp
  exp=$(api payee p3.r GET "/api/v1/ai/earnings-explainer/$ledger")
  echo "$exp" | grep -q '"groundedInTrace":true' || return 1
  echo "$exp" | grep -q '90000' || return 1
  echo "$exp" | grep -q 'disputeHandoff' || return 1
  note "explainer narrates the stored slices only"
}

monte_carlo_simulation() {
  note "Monte Carlo: liability bands + windfall detection through the gateway"
  local sim
  sim=$(api comp_admin p3.admin POST /api/v1/calc/simulations \
    -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"$TENANT\",\"periodKey\":\"2027-p3\",\"planVersionHash\":\"$PLAN_HASH\",\"iterations\":1000,\"seed\":42}")
  local sim_id
  sim_id=$(echo "$sim" | jq_get simulationId)
  [ -n "$sim_id" ] || { note "sim failed: $(echo "$sim" | head -c 200)"; return 1; }
  echo "$sim" | grep -q '"status":"COMPLETED"' || return 1
  echo "$sim" | grep -q '"p95Liability"' || return 1
  api comp_admin p3.admin GET "/api/v1/calc/simulations/$sim_id" | grep -q '"meanLiability"' || return 1
  local rows
  rows=$(pg_docker -tAc "SELECT count(*) FROM simulation_payee_outcome WHERE simulation_id='$sim_id'")
  [ "$(echo "$rows" | tr -d '[:space:]')" -ge 1 ] || return 1
  note "simulation $sim_id completed with percentile bands"
}

migration_import() {
  note "Varicent importer: history lands idempotently"
  local csv
  csv="record_type,payeeId,employeeNumber,fullName,email,role
PAYEE,dddddddd-f000-0000-0000-000000000006,VAR-1,Imported Rep,var@example.test,AE
record_type,periodKey,employeeNumber,amount
HISTORY,2027-01,VAR-1,42000
HISTORY,2027-01,VAR-1,18000
record_type,planName,tierCsv
PLAN,Varicent Base,0-100000:5%,100000+:8%
record_type,territory,periodKey,amount
QUOTA,West,2027-01,500000
BOGUS,row"
  local r1 r2
  r1=$(api comp_admin p3.migrator POST /api/v1/migration/spm/varicent \
    -H 'Content-Type: text/csv' --data-binary "$csv")
  echo "$r1" | grep -q '"historyRows":2' || { note "import1: $r1"; return 1; }
  echo "$r1" | grep -q 'unknown record type: BOGUS' || return 1
  r2=$(api comp_admin p3.migrator POST /api/v1/migration/spm/varicent \
    -H 'Content-Type: text/csv' --data-binary "$csv")
  echo "$r2" | grep -q '"payees":1' || return 1
  local n
  n=$(pg_docker -tAc "SELECT count(*) FROM raw_transaction WHERE tenant_id='$TENANT' AND source_system='MIGRATION_VARICENT'" | tr -d '[:space:]')
  [ "$n" = "2" ] || { note "idempotence: $n rows"; return 1; }
  note "import idempotent (2 rows after 2 runs)"
}

audit_trail() {
  note "Phase-3 audit assertions"
  local q v
  q() { pg_docker -tAc "SELECT count(*) FROM $1" | tr -d '[:space:]'; }
  for pair in \
    "audit_log WHERE reason_code='TERRITORY_CREATED'|1" \
    "audit_log WHERE reason_code='TERRITORY_ASSIGN'|1" \
    "audit_log WHERE reason_code='QUOTA_ALLOCATED'|1" \
    "audit_log WHERE reason_code='ROUTING_RULE_CREATED'|1" \
    "audit_log WHERE reason_code='PARTNER_REGISTERED'|1" \
    "audit_log WHERE reason_code='PARTNER_DISPUTE'|1" \
    "audit_log WHERE reason_code='CHANNEL_CLAIM_SUBMITTED'|1" \
    "audit_log WHERE reason_code='CHANNEL_ACCRUAL'|1" \
    "audit_log WHERE reason_code='NL_PLAN_COMPILED'|1" \
    "audit_log WHERE reason_code='NL_PLAN_APPROVED'|1" \
    "audit_log WHERE reason_code='AI_PLAN_DIAGNOSTICS'|1" \
    "audit_log WHERE reason_code='SPM_IMPORT'|1"; do
    local where="${pair%|*}" min="${pair#*|}"
    v=$(q "$where")
    [ "$v" -ge "$min" ] || { note "audit gap: $where = $v (need >= $min)"; return 1; }
  done
  note "all Phase-3 audit trails present"
}

cleanup() {
  note "cleaning Phase-3 fixture"
  pg_docker >/dev/null 2>&1 <<SQL
DELETE FROM channel_claim WHERE tenant_id = '$TENANT';
DELETE FROM channel_program WHERE tenant_id = '$TENANT';
DELETE FROM partner_account WHERE tenant_id = '$TENANT';
DELETE FROM territory_assignment WHERE tenant_id = '$TENANT';
DELETE FROM quota_allocation WHERE tenant_id = '$TENANT';
DELETE FROM routing_rule WHERE tenant_id = '$TENANT';
DELETE FROM territory WHERE tenant_id = '$TENANT';
DELETE FROM nl_model_request WHERE tenant_id = '$TENANT';
DELETE FROM plan_diagnostic WHERE tenant_id = '$TENANT';
DELETE FROM simulation_payee_outcome;
DELETE FROM simulation_iteration;
DELETE FROM simulation_run WHERE tenant_id = '$TENANT';
DELETE FROM commission_ledger_entry WHERE tenant_id = '$TENANT';
DELETE FROM credit_allocation WHERE transaction_id IN (SELECT transaction_id FROM raw_transaction WHERE tenant_id = '$TENANT');
DELETE FROM raw_transaction WHERE tenant_id = '$TENANT';
SELECT eim_ops.detach_delete_payee(p.payee_id) FROM payee p WHERE p.tenant_id = '$TENANT';
DELETE FROM payee WHERE tenant_id = '$TENANT';
DELETE FROM payout_period WHERE tenant_id = '$TENANT';
DELETE FROM dispute_ticket WHERE tenant_id = '$TENANT';
ALTER TABLE plan_artifact DISABLE TRIGGER trg_plan_artifact_append_only;
DELETE FROM plan_artifact WHERE plan_version_hash IN ('$PLAN_HASH', 'wind000000000000000000000000000000000000000000000000000000000tg8');
ALTER TABLE plan_artifact ENABLE TRIGGER trg_plan_artifact_append_only;
SQL
}

echo "TG-8 Phase-3 probe — $(date -u +%Y-%m-%dT%H:%M:%SZ)"
step TG8-ENV "runtime secrets" load_runtime_secrets
step TG8-ENV "gateway token" client_token
step TG8-ENV "fixture seed" seed
step TG8-HIE "territories + routing + quota + realignment preview" territories_routing_and_quota
step TG8-SEC "partner identity + statement + dispute + claim accrual" partner_portal_and_claims
step TG8-RUL "NL modeling with SoD human approval" nl_modeling_with_human_gate
step TG8-RUL "plan diagnostics + trace-grounded explainer" diagnostics_and_explainer
step TG8-CAL "Monte Carlo liability bands + windfall" monte_carlo_simulation
step TG8-ONB "Varicent migration import (idempotent)" migration_import
step TG8-AUD "Phase-3 audit trails" audit_trail
step TG8-ENV "fixture cleanup" cleanup

printf '\nTG-8 Phase-3 probe: %d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then printf 'Failures:\n'; for f in "${FAILURES[@]}"; do printf '  - %s\n' "$f"; done; exit 1; fi
echo "TG8 PROBE GREEN"
