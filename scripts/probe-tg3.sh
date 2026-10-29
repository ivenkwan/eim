#!/bin/sh
# TG-3 E2E cluster probe (phase from $1: run | replay):
#   run    — publish the canonical plan, execute a calculation run for the
#            seeded fixture org, verify stats + sandbox preview total.
#   replay — shadow-replay the (now locked) April period after the harness
#            injected a late-arriving deal; diffs must be 1.
# Runs inside the cluster as a ConfigMap-mounted script; the client secret
# arrives as an env var from the gitignored secrets file.
set -e
PHASE="${1:-run}"
GW=http://eim-apisix:9080
KC=http://eim-keycloak:8080
TENANT=99999999-0000-0000-0000-000000000001
P_APR=a11ce000-0000-0000-0000-000000000101
P_MAY=a11ce000-0000-0000-0000-000000000102

echo "== client-credentials token"
TOK=$(curl -s -X POST $KC/realms/eim/protocol/openid-connect/token \
  -d "grant_type=client_credentials&client_id=eim-gateway&client_secret=$OIDC_CLIENT_SECRET" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
test -n "$TOK" || { echo "FAIL: no token"; exit 1; }
echo ok

AUTH="Authorization: Bearer $TOK"
HDR="$AUTH
X-Tenant-Id: $TENANT
X-Actor: comp.probe
X-Roles: comp_admin"

if [ "$PHASE" = "run" ]; then
  echo "== publish the canonical plan (forward+backward DRL, DMN, registry)"
  RESP=$(curl -s -X POST $GW/api/v1/plans/publish -H "$HDR" -H 'Content-Type: application/json' -d @/probe/plan.json)
  echo "$RESP"
  HASH=$(echo "$RESP" | sed -n 's/.*"planVersionHash":"\([^"]*\)".*/\1/p')
  test -n "$HASH" || { echo "FAIL: no plan version hash"; exit 1; }

  echo "== execute a calculation run through the gateway"
  RESP=$(curl -s -X POST $GW/api/v1/calc/runs -H "$HDR" -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"$TENANT\",\"payoutPeriodId\":\"$P_APR\",\"planVersionHash\":\"$HASH\"}")
  echo "$RESP"
  echo "$RESP" | grep -q '"status":"COMPLETED"' || { echo "FAIL: run not completed"; exit 1; }
  echo "$RESP" | grep -q '"ledgerEntries":2' || { echo "FAIL: expected rep commission + mgr rollup"; exit 1; }

  echo "== sandbox preview (builder dock path, nothing persisted)"
  RESP=$(curl -s -X POST $GW/api/v1/calc/preview -H "$HDR" -H 'Content-Type: application/json' \
    -d '{"plan":{"planName":"draft","components":[{"name":"commission","kind":"TIER_TABLE","outputType":"CURRENCY","tiers":{"mode":"MARGINAL","basis":"AMOUNT","brackets":[{"upTo":"100000","rate":"0.10"},{"upTo":"150000","rate":"0.15"},{"rate":"0.20"}]}}]},"transactions":[{"date":"2026-04-10","amount":"70000"}],"ctx":{"window_from":"90000","period_start":"2026-04-01","period_end":"2026-04-30","period_days":"30","active_days":"30"}}')
  echo "$RESP" | head -c 300; echo
  echo "$RESP" | grep -q '10500.00' || { echo "FAIL: canonical preview total"; exit 1; }
  echo "TG3-E2E-RUN-OK"
  exit 0
fi

if [ "$PHASE" = "replay" ]; then
  HASH=$(cat /probe-hash/hash)
  echo "== shadow replay (locked April, open May, pinned $HASH)"
  RESP=$(curl -s -X POST $GW/api/v1/calc/shadow-replay -H "$HDR" -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"$TENANT\",\"lockedPeriodId\":\"$P_APR\",\"openPeriodId\":\"$P_MAY\",\"planVersionHash\":\"$HASH\"}")
  echo "$RESP" | head -c 400; echo
  echo "$RESP" | grep -q '"diffs":1' || { echo "FAIL: expected one drifted payee"; exit 1; }
  echo "TG3-E2E-OK"
  exit 0
fi

echo "unknown phase: $PHASE"; exit 1
