#!/bin/sh
# TG-2 E2E cluster probe: ingestion + hierarchy through the gateway with the
# OIDC edge check, per-row quarantine, late-arriving true-up signal, SCD-2
# manager move with as-of rollups, and wavefront layering (TG-2 exit
# criteria). Runs inside the cluster as a ConfigMap-mounted script; the
# client secret arrives as an env var from the gitignored secrets file.
set -e
GW=http://eim-apisix:9080
KC=http://eim-keycloak:8080
TENANT=11111111-0000-0000-0000-000000000001
SARAH=a1b2c3d4-0000-0000-0000-000000000001
ALEX=a1b2c3d4-0000-0000-0000-000000000002
LIVE_REP=d1e2f3a4-0000-0000-0000-00000000aa01

echo "== client-credentials token"
TOK=$(curl -s -X POST $KC/realms/eim/protocol/openid-connect/token \
  -d "grant_type=client_credentials&client_id=eim-gateway&client_secret=$OIDC_CLIENT_SECRET" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
test -n "$TOK" || { echo "FAIL: no token"; exit 1; }
echo "ok"

echo "== gateway: ingestion without token -> 401"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/api/v1/ingestion/csv \
  -H 'Content-Type: application/json' -d '{"csv":"x"}')
echo "ingest-no-token=$CODE"; test "$CODE" = "401"

echo "== CSV batch through the gateway: 2 accepted, 1 quarantined, 1 late-arriving"
CSV='deal_id,close_date,amount,currency,payee_email
TG2-LIVE-1,2026-04-10,25000.50,USD,sarah@example.test
TG2-LIVE-2,not-a-date,9000,USD,sarah@example.test
TG2-LIVE-3,2026-01-15,5000,USD,sarah@example.test'
RESP=$(curl -s -X POST $GW/api/v1/ingestion/csv \
  -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
  -H "X-Tenant-Id: $TENANT" -H 'X-Actor: revops.probe' -H 'X-Roles: comp_admin' \
  -d "{\"objectName\":\"tg2_live_probe\",\"csv\":\"$(echo "$CSV" | sed 's/"/\\"/g' | sed ':a;N;$!ba;s/\n/\\n/g')\"}")
echo "$RESP"
echo "$RESP" | grep -q '"ingested":2' || { echo "FAIL: expected 2 ingested"; exit 1; }
echo "$RESP" | grep -q '"quarantined":1' || { echo "FAIL: expected 1 quarantined"; exit 1; }
echo "$RESP" | grep -q '"lateArriving":1' || { echo "FAIL: expected 1 late-arriving"; exit 1; }

echo "== create payee under Sarah (effective 2026-01-01)"
RESP=$(curl -s -X POST $GW/api/v1/org/payee \
  -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
  -H "X-Tenant-Id: $TENANT" -H 'X-Actor: org.probe' -H 'X-Roles: comp_admin' \
  -d "{\"payeeId\":\"$LIVE_REP\",\"employeeNumber\":\"LIVE-1\",\"fullName\":\"Live Rep\",\"email\":\"live.rep@example.test\",\"roleTitle\":\"AE\",\"managerPayeeId\":\"$SARAH\",\"effectiveStart\":\"2026-01-01\"}")
echo "$RESP"
echo "$RESP" | grep -q "$LIVE_REP" || { echo "FAIL: payee create"; exit 1; }

echo "== mid-period move to Alex effective 2026-05-15 (SCD-2)"
RESP=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$GW/api/v1/org/payee/$LIVE_REP/manager" \
  -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
  -H "X-Tenant-Id: $TENANT" -H 'X-Actor: org.probe' -H 'X-Roles: comp_admin' \
  -d "{\"manager_payee_id\":\"$ALEX\",\"effective_start\":\"2026-05-15\"}")
echo "move=$RESP"; test "$RESP" = "202"

echo "== as-of rollups: May 1 under Sarah, June 1 under Alex"
R1=$(curl -s -H "Authorization: Bearer $TOK" \
  -H "X-Tenant-Id: $TENANT" -H "X-Actor: org.probe" -H "X-Roles: comp_admin" \
  "$GW/api/v1/org/rollup/$SARAH?asOf=2026-05-01")
echo "$R1"
echo "$R1" | grep -q "$LIVE_REP" || { echo "FAIL: rep not under Sarah on May 1"; exit 1; }
R2=$(curl -s -H "Authorization: Bearer $TOK" \
  -H "X-Tenant-Id: $TENANT" -H "X-Actor: org.probe" -H 'X-Roles: comp_admin' \
  "$GW/api/v1/org/rollup/$ALEX?asOf=2026-06-01")
echo "$R2"
echo "$R2" | grep -q "$LIVE_REP" || { echo "FAIL: rep not under Alex on June 1"; exit 1; }
R3=$(curl -s -H "Authorization: Bearer $TOK" \
  -H "X-Tenant-Id: $TENANT" -H "X-Actor: org.probe" -H 'X-Roles: comp_admin' \
  "$GW/api/v1/org/rollup/$SARAH?asOf=2026-06-01")
echo "$R3"
echo "$R3" | grep -qv "$LIVE_REP" || { echo "FAIL: rep still under Sarah on June 1"; exit 1; }

echo "== wavefronts layered (Sarah level 1 over leaves)"
W=$(curl -s -H "Authorization: Bearer $TOK" \
  -H "X-Tenant-Id: $TENANT" -H "X-Actor: org.probe" -H 'X-Roles: comp_admin' \
  "$GW/api/v1/org/wavefronts")
echo "$W" | head -c 300; echo
echo "$W" | grep -q '"maxLevel":[1-9]' || { echo "FAIL: no layered wavefronts"; exit 1; }

echo "TG2-E2E-OK"
