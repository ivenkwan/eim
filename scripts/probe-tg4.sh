#!/bin/sh
# TG-4 monthly-cycle E2E probe (in-cluster): the full loop — ingest →
# calculate → dispute → override → period-close (review + SoD sign-off +
# lock) → payroll export → statement → true-up decision. Runs as a
# ConfigMap-mounted script; the client secret arrives as an env var from
# the gitignored secrets file.
set -e
GW=http://eim-apisix:9080
KC=http://eim-keycloak:8080
TENANT=cccccccc-c000-0000-0000-000000000001
MGR=cccccccc-c000-0000-0000-000000000002
REP=cccccccc-c000-0000-0000-000000000003
P_APR=cccccccc-c000-0000-0000-000000000004
P_MAY=cccccccc-c000-0000-0000-000000000005

echo "== client-credentials token"
TOK=$(curl -s -X POST $KC/realms/eim/protocol/openid-connect/token \
  -d "grant_type=client_credentials&client_id=eim-gateway&client_secret=$OIDC_CLIENT_SECRET" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
test -n "$TOK" || { echo "FAIL: no token"; exit 1; }
echo ok

ADMIN="Authorization: Bearer $TOK
X-Tenant-Id: $TENANT
X-Roles: comp_admin"
PAYEE="Authorization: Bearer $TOK
X-Tenant-Id: $TENANT
X-Payee-Id: $REP
X-Roles: payee"
FIN="Authorization: Bearer $TOK
X-Tenant-Id: $TENANT
X-Roles: finance_approver"

echo "== 1. ingest (CSV through the gateway)"
RESP=$(curl -s -X POST $GW/api/v1/ingestion/csv \
  -H "$ADMIN" -H 'Content-Type: application/json' -H 'X-Actor: cycle.probe' \
  -d '{"objectName":"tg4_cycle","csv":"deal_id,close_date,amount,currency,payee_email\nC-1,2026-04-10,160000,USD,cycle.rep@example.test"}')
echo "$RESP" | grep -q '"ingested":1' || { echo "FAIL: ingest: $RESP"; exit 1; }

echo "== 2. publish plan + calculate"
HASH=$(curl -s -X POST $GW/api/v1/plans/publish \
  -H "$ADMIN" -H 'Content-Type: application/json' -H 'X-Actor: cycle.probe' \
  -d @/probe/plan.json | sed -n 's/.*"planVersionHash":"\([^"]*\)".*/\1/p')
test -n "$HASH" || { echo "FAIL: publish"; exit 1; }
RESP=$(curl -s -X POST $GW/api/v1/calc/runs \
  -H "$ADMIN" -H 'Content-Type: application/json' -H 'X-Actor: cycle.probe' \
  -d "{\"tenantId\":\"$TENANT\",\"payoutPeriodId\":\"$P_APR\",\"planVersionHash\":\"$HASH\"}")
echo "$RESP" | grep -q '"status":"COMPLETED"' || { echo "FAIL: calc: $RESP"; exit 1; }

echo "== 3. seller dispute (pre-tagged) then admin resolve"
DISPUTE=$(curl -s -X POST $GW/api/v1/disputes \
  -H "$PAYEE" -H 'Content-Type: application/json' -H 'X-Actor: cycle.rep' \
  -d '{"category":"PLAN_LOGIC","notes":"payout looks short","symptom":"late_arrival"}')
echo "$DISPUTE" | grep -q 'SOURCE_DATA' || { echo "FAIL: pre-tag: $DISPUTE"; exit 1; }
DID=$(echo "$DISPUTE" | sed -n 's/.*"disputeId":"\([^"]*\)".*/\1/p')
curl -s -X POST "$GW/api/v1/disputes/$DID/resolve" \
  -H "$ADMIN" -H 'Content-Type: application/json' -H 'X-Actor: cycle.admin' \
  -d '{"resolution":"APPROVE","comment":"source fixed"}' | grep -q APPROVED \
  || { echo "FAIL: resolve"; exit 1; }

echo "== 4. manual override linked to the dispute"
RESP=$(curl -s -X POST $GW/api/v1/admin/overrides \
  -H "$ADMIN" -H 'Content-Type: application/json' -H 'X-Actor: cycle.admin' \
  -d "{\"payoutPeriodId\":\"$P_APR\",\"payeeId\":\"$REP\",\"componentName\":\"commission\",\"adjustedAmount\":\"20000\",\"reasonCode\":\"GOODWILL\",\"reasonComment\":\"dispute approved\",\"disputeId\":\"$DID\"}")
echo "$RESP" | grep -q '"originalAmount":"19500.0000"' \
  || { echo "FAIL: override original preserved: $RESP"; exit 1; }

echo "== 5. period close: review -> SoD sign-off -> lock"
PID=$(curl -s -X POST $GW/api/v1/process/period-close/start \
  -H "$ADMIN" -H 'Content-Type: application/json' -H 'X-Actor: cycle.admin' \
  -d "{\"tenantId\":\"$TENANT\",\"payoutPeriodId\":\"$P_APR\"}" \
  | sed -n 's/.*"processId":"\([^"]*\)".*/\1/p')
test -n "$PID" || { echo "FAIL: close start"; exit 1; }
curl -s -X POST "$GW/api/v1/process/period-close/$PID/tasks/comp_admin_review" \
  -H "$ADMIN" -H 'Content-Type: application/json' -H 'X-Actor: cycle.admin' \
  -d '{"comment":"reviewed"}' | grep -q finance_signoff || { echo "FAIL: review"; exit 1; }
curl -s -X POST "$GW/api/v1/process/period-close/$PID/tasks/finance_signoff" \
  -H "$FIN" -H 'Content-Type: application/json' -H 'X-Actor: cycle.admin' \
  -d '{"comment":"sign"}' | grep -q 403 && echo "  (SoD enforced: same actor rejected)"
curl -s -X POST "$GW/api/v1/process/period-close/$PID/tasks/finance_signoff" \
  -H "$FIN" -H 'Content-Type: application/json' -H 'X-Actor: fin.chief' \
  -d '{"comment":"signed"}' | grep -q period_lock || { echo "FAIL: signoff"; exit 1; }
curl -s -X POST "$GW/api/v1/process/period-close/$PID/tasks/period_lock" \
  -H "$FIN" -H 'Content-Type: application/json' -H 'X-Actor: fin.chief' -d '{}' \
  | grep -q export_handoff || { echo "FAIL: lock"; exit 1; }
curl -s -X POST "$GW/api/v1/process/period-close/$PID/tasks/export_handoff" \
  -H "$ADMIN" -H 'Content-Type: application/json' -H 'X-Actor: cycle.admin' \
  -d '{"comment":"handed off"}' | grep -q COMPLETED || { echo "FAIL: handoff"; exit 1; }

echo "== 6. payroll export from the locked period (byte-identical hash)"
H1=$(curl -s -X POST $GW/api/v1/admin/exports \
  -H "$FIN" -H 'Content-Type: application/json' -H 'X-Actor: fin.chief' \
  -d "{\"periodId\":\"$P_APR\",\"template\":\"ADP\"}" | sed -n 's/.*"fileHash":"\([^"]*\)".*/\1/p')
H2=$(curl -s -X POST $GW/api/v1/admin/exports \
  -H "$FIN" -H 'Content-Type: application/json' -H 'X-Actor: fin.chief' \
  -d "{\"periodId\":\"$P_APR\",\"template\":\"ADP\"}" | sed -n 's/.*"fileHash":"\([^"]*\)".*/\1/p')
test -n "$H1" && test "$H1" = "$H2" || { echo "FAIL: hash regen: $H1 vs $H2"; exit 1; }

echo "== 7. statement (seller view, numbers match the ledger)"
RESP=$(curl -s -H "$PAYEE" -H 'X-Actor: cycle.rep' "$GW/api/v1/me/statement")
echo "$RESP" | grep -q '"periodStatus":"LOCKED"' || { echo "FAIL: locked state: $RESP"; exit 1; }
echo "$RESP" | grep -q '20000.0000' || { echo "FAIL: statement total (post-override): $RESP"; exit 1; }

echo "== 8. retro CRM change after lock (late April deal) -> true-up routing"
RESP=$(curl -s -X POST $GW/api/v1/ingestion/csv \
  -H "$ADMIN" -H 'Content-Type: application/json' -H 'X-Actor: cycle.probe' \
  -d '{"objectName":"tg4_cycle","csv":"deal_id,close_date,amount,currency,payee_email\nC-2,2026-04-25,40000,USD,cycle.rep@example.test"}')
echo "$RESP" | grep -q '"lateArriving":1' || { echo "FAIL: late-arriving: $RESP"; exit 1; }

echo "== 8b. shadow replay -> true-up queue -> Finance approval"
curl -s -X POST $GW/api/v1/calc/shadow-replay \
  -H "$FIN" -H 'Content-Type: application/json' -H 'X-Actor: fin.chief' \
  -d "{\"tenantId\":\"$TENANT\",\"lockedPeriodId\":\"$P_APR\",\"openPeriodId\":\"$P_MAY\",\"planVersionHash\":\"$HASH\"}" \
  | grep -q '"diffs":' || { echo "FAIL: replay"; exit 1; }
TID=$(curl -s -H "$FIN" -H 'X-Actor: fin.chief' "$GW/api/v1/process/trueup" \
  | sed -n 's/.*"trueupId":"\([^"]*\)".*/\1/p' | head -1)
test -n "$TID" || { echo "FAIL: empty queue"; exit 1; }
curl -s -X POST "$GW/api/v1/process/trueup/$TID/decision" \
  -H "$FIN" -H 'Content-Type: application/json' -H 'X-Actor: fin.chief' \
  -d '{"decision":"APPROVE","note":"verified"}' | grep -q POSTED \
  || { echo "FAIL: trueup decision"; exit 1; }

echo "TG4-E2E-OK"
