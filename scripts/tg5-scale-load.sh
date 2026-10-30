#!/usr/bin/env bash
# TG5-TST-1/2/3 (NFR-21/22/25): enterprise-scale fixture + synthetic load.
#
#  - TST-1 seeds 5,000 payees / 1,000,000 transaction rows on a dedicated
#    tenant-month and runs the full-cohort calculation through the engine,
#    asserting the ≤ 10-minute close-cycle budget (NFR-21).
#  - TST-2 drives synthetic read/write traffic through the gateway and
#    computes p95/p99 latencies (NFR-22).
#  - TST-3 executes the heavy reporting query in a read-only session and
#    proves aggregate parity with the writer session (NFR-25 prep).
#
# Credentials are read at runtime from the gitignored .dev-secrets.env and
# passed to child processes as a connection URI built inside the container
# from the environment — no secret literals in this source file.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

TENANT="dddddddd-d000-0000-0000-000000000001"
PERIOD="dddddddd-d000-0000-0000-000000000010"
HASH="scale00000000000000000000000000000000000000000000000000000000tg5"

PASS=0; FAIL=0
declare -a FAILURES=()
step() { local id="$1" desc="$2"; shift 2
  printf '\n=== [%s] %s\n' "$id" "$desc"
  if "$@"; then printf '>>> [%s] PASS\n' "$id"; PASS=$((PASS + 1))
  else printf '>>> [%s] FAIL\n' "$id"; FAIL=$((FAIL + 1)); FAILURES+=("$id: $desc"); fi
}
note() { printf '[tg5] %s\n' "$*"; }

# runtime-only secret plumbing: env key names in the gitignored file
PG_KEY=EIM_DEV_POSTGRES_PASSWORD
OIDC_KEY=EIM_DEV_OIDC_CLIENT_SECRET

load_runtime_secrets() {
  local base; base=".dev-secrets.env"
  [ -f "$base" ] || { echo "missing $base" >&2; return 1; }
  PG_TOKEN="$(sed -n "s/^${PG_KEY}=//p" "$base")"
  OIDC_TOKEN="$(sed -n "s/^${OIDC_KEY}=//p" "$base")"
  [ -n "$PG_TOKEN" ] && [ -n "$OIDC_TOKEN" ]
}

pg_docker() { # pg_docker [psql flags...]; SQL on stdin when applicable
  local pass_env
  pass_env="$(printf '%s=%s' "$PG_KEY" "$PG_TOKEN")"
  docker run --rm -i --network host --env "$pass_env" \
    apache/age:release_PG16_1.6.0 \
    sh -c 'exec psql "postgresql://eim:${EIM_DEV_POSTGRES_PASSWORD}@127.0.0.1:15433/eim?sslmode=disable" -v ON_ERROR_STOP=1 "$@"' psql "$@"
}

gw() { echo "http://localhost:31234$1"; }
kc() { echo "http://localhost:31235$1"; }

client_token() {
  # Host header pinned to the in-cluster name: Keycloak derives the issuer
  # from the request host, and APISIX introspection requires the internal
  # issuer (a localhost-issued token introspects inactive)
  curl -s -X POST "$(kc /realms/eim/protocol/openid-connect/token)" \
    -H "Host: eim-keycloak:8080" \
    -d "grant_type=client_credentials&client_id=eim-gateway&client_secret=$OIDC_TOKEN" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

now_ms() { date +%s%N | cut -c1-13; }

seed_scale() {
  note "seeding 5,000 payees / 1,000,000 rows (a few minutes)"
  pg_docker <<'SQL'
\set QUIET on
\timing off
BEGIN;
DELETE FROM commission_ledger_entry WHERE tenant_id = 'dddddddd-d000-0000-0000-000000000001';
DELETE FROM calc_run WHERE tenant_id = 'dddddddd-d000-0000-0000-000000000001';
DELETE FROM credit_allocation WHERE transaction_id IN (SELECT transaction_id FROM raw_transaction WHERE tenant_id = 'dddddddd-d000-0000-0000-000000000001');
DELETE FROM raw_transaction WHERE tenant_id = 'dddddddd-d000-0000-0000-000000000001';
SELECT eim_ops.detach_delete_payee(p.payee_id) FROM payee p WHERE p.tenant_id = 'dddddddd-d000-0000-0000-000000000001';
DELETE FROM payee WHERE tenant_id = 'dddddddd-d000-0000-0000-000000000001';
DELETE FROM payout_period WHERE tenant_id = 'dddddddd-d000-0000-0000-000000000001';
ALTER TABLE plan_artifact DISABLE TRIGGER trg_plan_artifact_append_only;
DELETE FROM plan_artifact WHERE plan_version_hash = 'scale00000000000000000000000000000000000000000000000000000000tg5';
ALTER TABLE plan_artifact ENABLE TRIGGER trg_plan_artifact_append_only;
COMMIT;

INSERT INTO payout_period (payout_period_id, tenant_id, period_key, starts_on, ends_on, status)
VALUES ('dddddddd-d000-0000-0000-000000000010','dddddddd-d000-0000-0000-000000000001','2026-scl',DATE '2026-04-01',DATE '2026-04-30','OPEN');

INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, role_title, effective_start_date)
VALUES ('dddddddd-d000-0000-0000-000000f00001','dddddddd-d000-0000-0000-000000000001','S-MGR','Scale Manager','scale.mgr@example.test','Mgr',DATE '2026-01-01');

INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, role_title, manager_payee_id, effective_start_date)
SELECT (regexp_replace(md5('tg5-payee-' || g::text), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5'))::uuid,
       'dddddddd-d000-0000-0000-000000000001',
       'S-' || g, 'Scale Rep ' || g, 'scale' || g || '@example.test', 'AE',
       'dddddddd-d000-0000-0000-000000f00001', DATE '2026-01-01'
FROM generate_series(1, 5000) g;

INSERT INTO plan_artifact (plan_version_hash, tenant_id, plan_name, plan_json, model_json, drl, helper_java, dmn)
VALUES ('scale00000000000000000000000000000000000000000000000000000000tg5',
        'dddddddd-d000-0000-0000-000000000001','scale','{}',
        CAST('{"planName":"scale","components":[{"name":"commission","kind":"TIER_TABLE","outputType":"CURRENCY","tiers":{"mode":"MARGINAL","basis":"AMOUNT","brackets":[{"upTo":"100000","rate":"0.10"},{"upTo":"150000","rate":"0.15"},{"rate":"0.20"}]}},{"name":"team_commission","kind":"ROLLUP","outputType":"CURRENCY","dependsOn":["commission"]}]}' AS jsonb),
        'stub','stub','stub');

INSERT INTO raw_transaction (transaction_id, tenant_id, source_system, external_deal_id, transaction_date, booking_amount, currency_code, ingestion_batch_id)
SELECT (regexp_replace(md5('tg5-txn-' || g || '-' || d), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5'))::uuid,
       'dddddddd-d000-0000-0000-000000000001', 'CSV', 'S-' || g || '-' || d,
       DATE '2026-04-01' + (d % 28), 500 + (d % 50) * 10, 'USD',
       'dddddddd-d000-0000-0000-0000000000b1'
FROM generate_series(1, 5000) g, generate_series(0, 199) d;

INSERT INTO credit_allocation (credit_allocation_id, tenant_id, transaction_id, payee_id, split_pct, effective_date)
SELECT gen_random_uuid(), 'dddddddd-d000-0000-0000-000000000001', t.transaction_id,
       (regexp_replace(md5('tg5-payee-' || split_part(t.external_deal_id, '-', 2)), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5'))::uuid,
       1, t.transaction_date
FROM raw_transaction t WHERE t.tenant_id = 'dddddddd-d000-0000-0000-000000000001';

SELECT 'seeded payees=' || count(*) FROM payee WHERE tenant_id = 'dddddddd-d000-0000-0000-000000000001';
SELECT 'seeded txns=' || count(*) FROM raw_transaction WHERE tenant_id = 'dddddddd-d000-0000-0000-000000000001';
SQL
}

enterprise_run() {
  note "full-cohort calc run (budget 600,000 ms; in-cluster call — the"
  note "gateway's 60s upstream timeout is the API-edge budget, batch runs"
  note "are triggered in-cluster by the orchestrator in production)"
  local t0 t1 ms body
  t0=$(now_ms)
  body=$(kubectl -n eim run tg5-run-$RANDOM --restart=Never --image=curlimages/curl:8.11.1 \
    --env="T=$TENANT" --env="P=$PERIOD" --env="H=$HASH" --env="TOK=$TOKEN" \
    --command -- sh -c 'curl -s -m 850 -X POST http://eim-calc-engine:8082/api/v1/calc/runs \
      -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" \
      -H "X-Tenant-Id: $T" -H "X-Actor: scale.probe" -H "X-Roles: comp_admin" \
      -d "{\"tenantId\":\"$T\",\"payoutPeriodId\":\"$P\",\"planVersionHash\":\"$H\"}"; sleep 2' 2>/dev/null \
    | true)
  local runpod
  runpod=$(kubectl -n eim get pods -o name | grep "pod/tg5-run-" | tail -1 | cut -d/ -f2)
  for _ in $(seq 1 120); do
    body=$(kubectl -n eim logs "$runpod" 2>/dev/null | head -c 2000)
    [ -n "$body" ] && kubectl -n eim get pod "$runpod" 2>/dev/null | grep -qE "Completed|Error" && break
    sleep 5
  done
  kubectl -n eim delete pod "$runpod" --ignore-not-found >/dev/null 2>&1
  t1=$(now_ms); ms=$((t1 - t0))
  note "run took ${ms}ms: $(echo "$body" | head -c 300)"
  echo "$body" | grep -q '"status":"COMPLETED"' || return 1
  [ "$ms" -le 600000 ] || { note "BUDGET EXCEEDED"; return 1; }
  local rows
  rows=$(pg_docker -tAc "SELECT count(*) FROM commission_ledger_entry WHERE tenant_id = '$TENANT'" | tr -d '[:space:]')
  note "ledger rows: $rows (5,000 commission + 1 rollup)"
  [ "$rows" -ge 5001 ]
}

latency_slo() {
  note "synthetic load: 60 reads + 60 writes through the gateway"
  local reads=() writes=() i code t0 t1 rep
  rep="$(pg_docker -tAc "SELECT (regexp_replace(md5('tg5-payee-1'), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\\1-\\2-\\3-\\4-\\5'))::uuid" | tr -d '[:space:]')"
  for i in $(seq 1 60); do
    t0=$(now_ms)
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$(gw /api/v1/me/statement)" \
      -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT" -H "X-Payee-Id: $rep" \
      -H "X-Actor: load" -H "X-Roles: payee")
    t1=$(now_ms)
    [ "$code" = "200" ] || { note "read $i -> $code"; return 1; }
    reads+=($((t1 - t0)))
    t0=$(now_ms)
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 10 -X POST "$(gw /api/v1/disputes)" \
      -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT" -H "X-Payee-Id: $rep" \
      -H "X-Actor: load" -H "X-Roles: payee" -H 'Content-Type: application/json' \
      -d '{"category":"PLAN_LOGIC","notes":"slo probe","symptom":"payout_wrong"}')
    t1=$(now_ms)
    [ "$code" = "202" ] || { note "write $i -> $code"; return 1; }
    writes+=($((t1 - t0)))
  done
  local r_p95 r_p99 w_p95
  r_p95=$(printf '%s\n' "${reads[@]}" | sort -n | sed -n '57p')
  r_p99=$(printf '%s\n' "${reads[@]}" | sort -n | sed -n '60p')
  w_p95=$(printf '%s\n' "${writes[@]}" | sort -n | sed -n '57p')
  note "reads p95=${r_p95}ms p99=${r_p99}ms (budgets 300/800); writes p95=${w_p95}ms (budget 500)"
  [ "$r_p95" -le 300 ] && [ "$r_p99" -le 800 ] && [ "$w_p95" -le 500 ]
}

replica_read() {
  note "heavy query parity: writer vs read-only session (NFR-25 prep)"
  local w_sum r_sum
  w_sum=$(pg_docker -tAc "SELECT coalesce(sum(payout_amount),0)::text FROM commission_ledger_entry WHERE tenant_id = '$TENANT'" | tr -d '[:space:]')
  r_sum=$(pg_docker -c "SET default_transaction_read_only = on; SELECT coalesce(sum(payout_amount),0)::text FROM commission_ledger_entry WHERE tenant_id = '$TENANT';" | grep -oE '[0-9]+\.[0-9]+' | head -1)
  note "writer=$w_sum read-session=$r_sum"
  [ -n "$w_sum" ] && [ "$w_sum" = "$r_sum" ]
}

cleanup_scale() {
  note "cleaning scale fixture"
  pg_docker <<'SQL' >/dev/null 2>&1
DELETE FROM commission_ledger_entry WHERE tenant_id = 'dddddddd-d000-0000-0000-000000000001';
DELETE FROM calc_run WHERE tenant_id = 'dddddddd-d000-0000-0000-000000000001';
DELETE FROM credit_allocation WHERE transaction_id IN (SELECT transaction_id FROM raw_transaction WHERE tenant_id = 'dddddddd-d000-0000-0000-000000000001');
DELETE FROM raw_transaction WHERE tenant_id = 'dddddddd-d000-0000-0000-000000000001';
SELECT eim_ops.detach_delete_payee(p.payee_id) FROM payee p WHERE p.tenant_id = 'dddddddd-d000-0000-0000-000000000001';
DELETE FROM payee WHERE tenant_id = 'dddddddd-d000-0000-0000-000000000001';
DELETE FROM payout_period WHERE tenant_id = 'dddddddd-d000-0000-0000-000000000001';
ALTER TABLE plan_artifact DISABLE TRIGGER trg_plan_artifact_append_only;
DELETE FROM plan_artifact WHERE plan_version_hash = 'scale00000000000000000000000000000000000000000000000000000000tg5';
ALTER TABLE plan_artifact ENABLE TRIGGER trg_plan_artifact_append_only;
SQL
}

echo "TG-5 scale/load — $(date -u +%Y-%m-%dT%H:%M:%SZ)"
step TG5-ENV "runtime secrets (gitignored file)" load_runtime_secrets
step TG5-TST-1 "seed 5,000 payees / 1M rows" seed_scale
TOKEN="$(client_token)"
[ -n "$TOKEN" ] && note "gateway token acquired"
step TG5-TST-1 "enterprise full-cohort run ≤ 10 min (NFR-21)" enterprise_run
step TG5-TST-2 "API latency SLOs under synthetic load (NFR-22)" latency_slo
step TG5-TST-3 "read-only session parity for heavy queries (NFR-25 prep)" replica_read
step TG5-ENV "scale fixture cleanup" cleanup_scale

printf '\nTG-5 scale/load: %d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then printf 'Failures:\n'; for f in "${FAILURES[@]}"; do printf '  - %s\n' "$f"; done; exit 1; fi
echo "SCALE/LOAD GREEN"
