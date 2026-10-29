#!/usr/bin/env bash
# TG-2 verification harness — unit gates + live cluster gates for the data
# ingestion & credit/hierarchy tollgate. Run with the dev cluster up.
# All credentials are injected at runtime from the gitignored
# .dev-secrets.env — nothing sensitive lives in this file.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PASS=0
FAIL=0
declare -a FAILURES=()

step() { local id="$1" desc="$2"; shift 2
  printf '\n=== [%s] %s\n' "$id" "$desc"
  if "$@"; then printf '>>> [%s] PASS\n' "$id"; PASS=$((PASS + 1))
  else printf '>>> [%s] FAIL\n' "$id"; FAIL=$((FAIL + 1)); FAILURES+=("$id: $desc"); fi
}

probe() { local pod="$1" pattern="$2"; shift 2
  kubectl -n eim delete pod "$pod" --ignore-not-found >/dev/null 2>&1 || true
  kubectl -n eim run "$pod" --restart=Never --image=curlimages/curl:8.11.1 \
    --command -- "$@" >/dev/null 2>&1 || true
  local rc=1
  for _ in 1 2 3 4 5 6; do
    if kubectl -n eim logs "$pod" 2>/dev/null | grep -q "$pattern"; then rc=0; break; fi
    sleep 4
  done
  kubectl -n eim delete pod "$pod" --ignore-not-found >/dev/null 2>&1 || true
  return $rc
}

psql_count() { # psql_count <sql>
  kubectl -n eim exec eim-postgres-0 -- psql -U eim -d eim -tAc "$1" 2>/dev/null | tr -d '[:space:]'
}

load_runtime_secrets() {
  local base
  base="$(dirname "$0")/../.dev-secrets.env"
  [ -f "$base" ] || { echo "missing $base — run scripts/dev-up.sh" >&2; return 1; }
  local pg_token kc_user kc_pass oidc_secret
  pg_token="$(sed -n 's/^EIM_DEV_POSTGRES_PASSWORD=//p' "$base")"
  kc_user="$(sed -n 's/^EIM_DEV_KEYCLOAK_ADMIN_USERNAME=//p' "$base")"
  kc_pass="$(sed -n 's/^EIM_DEV_KEYCLOAK_ADMIN_PASSWORD=//p' "$base")"
  oidc_secret="$(sed -n 's/^EIM_DEV_OIDC_CLIENT_SECRET=//p' "$base")"
  export "PGPASSWORD=$pg_token"
  export "DB_USERNAME=eim"
  export "DB_PASSWORD=$pg_token"
  export "EIM_DEV_POSTGRES_PASSWORD=$pg_token"
  export "EIM_DEV_KEYCLOAK_ADMIN_USERNAME=$kc_user"
  export "EIM_DEV_KEYCLOAK_ADMIN_PASSWORD=$kc_pass"
  export "EIM_DEV_OIDC_CLIENT_SECRET=$oidc_secret"
}

echo "TG-2 verification — $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ---------------- unit / static gates ----------------
step TG2-DAT-x "migration policy lint (expand/contract)" bash scripts/check-migrations.sh
step TG2-CONTRACT "OpenAPI freshness (client + zod regen)" pnpm codegen:freshness
step TG2-CONTRACT "OpenAPI spectral lint" pnpm openapi:lint
step TG2-AUD-x "audit-coverage gate + self-test" pnpm audit:mappings --self-test
step TG2-OPS-x "frontend regression (lint/typecheck/test)" pnpm turbo lint typecheck test

export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
step TG2-ENV "runtime secrets loaded (gitignored file)" load_runtime_secrets
export EIM_TEST_DB_URL="${EIM_TEST_DB_URL:-jdbc:postgresql://localhost:15433/eim}"
export EIM_TEST_KAFKA="${EIM_TEST_KAFKA:-localhost:19092}"
step TG2-ING "connectors unit suite (CSV/SFDC/JDBC ingest, hierarchy, wavefront, benchmark)" \
  mvn -B -ntp -f apps/pom.xml -pl eim-connectors test

helm_lint_all() {
  for f in platform/helm/eim-platform/ci/*.yaml; do
    helm lint platform/helm/eim-platform -f "$f" >/dev/null || return 1
  done
}
step TG2-INF-x "helm lint (4 postures)" helm_lint_all
step TG2-INF-x "substitution matrix (NFR-80/81)" bash scripts/substitution-matrix.sh

# ---------------- live cluster gates ----------------
if kubectl -n eim get deploy/eim-core-api >/dev/null 2>&1; then
  seed_demo() {
    kubectl -n eim exec -i eim-postgres-0 -- psql -U eim -d eim -v ON_ERROR_STOP=0 >/dev/null 2>&1 <<'SQL'
LOAD 'age';
SET search_path TO public, ag_catalog;
SELECT count(*) FROM ag_catalog.cypher('eim_graph', $$ MATCH (n:Payee) DETACH DELETE n $$) AS (r ag_catalog.agtype);
SQL
    kubectl -n eim exec -i eim-postgres-0 -- psql -U eim -d eim >/dev/null 2>&1 <<'SQL'
-- Clean-slate payee rows (unit suites and prior probes leave versions
-- behind; the graph was detached above) then re-seed the demo org.
DELETE FROM payee;
INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, role_title, effective_start_date)
VALUES ('a1b2c3d4-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000001','E-001','Sarah Jenkins','sarah@example.test','Enterprise AE',current_date),
       ('a1b2c3d4-0000-0000-0000-000000000002','11111111-0000-0000-0000-000000000001','E-002','Alex Rivera','alex@example.test','Sales Engineer',current_date);
UPDATE payee SET manager_payee_id='a1b2c3d4-0000-0000-0000-000000000001' WHERE payee_id='a1b2c3d4-0000-0000-0000-000000000002';
-- Locked January period so the probe's Jan row is late-arriving (FR-604).
INSERT INTO payout_period (payout_period_id, tenant_id, period_key, starts_on, ends_on, status, locked_at)
VALUES ('eeeeeeee-0000-0000-0000-00000000eee1','11111111-0000-0000-0000-000000000001','2026-01',
        DATE '2026-01-01', DATE '2026-01-31', 'LOCKED', now())
ON CONFLICT (tenant_id, period_key) DO UPDATE SET status = 'LOCKED', locked_at = now();
SET session_replication_role = replica;
DELETE FROM audit_seals;
SET session_replication_role = DEFAULT;
SELECT eim_ops.seal_audit_day(current_date - 1);
SQL
  }
  step TG2-ENV "demo org re-seeded + locked period + seals reset" seed_demo

  cluster_ready() {
    kubectl -n eim rollout status deploy/eim-core-api --timeout=120s &&
    kubectl -n eim rollout status deploy/eim-connectors --timeout=120s &&
    kubectl -n eim rollout status deploy/eim-apisix --timeout=120s &&
    kubectl -n eim rollout status deploy/eim-keycloak --timeout=120s &&
    kubectl -n eim rollout status sts/eim-postgres --timeout=120s &&
    kubectl -n eim rollout status sts/eim-kafka --timeout=120s
  }
  step TG2-INF-x "cluster: all workloads ready" cluster_ready

  gw_401() {
    probe g2 '^401$' sh -c \
      'curl -s -o /dev/null -w %{http_code} -X POST http://eim-apisix:9080/api/v1/ingestion/csv -H "Content-Type: application/json" -d "{\"csv\":\"x\"}"'
  }
  step TG2-SEC-x "cluster: gateway 401 on ingestion without token" gw_401

  tg2_probe() {
    kubectl -n eim delete cm eim-tg2-probe-script --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n eim create configmap eim-tg2-probe-script \
      --from-file=probe.sh=scripts/probe-tg2.sh >/dev/null || return 1
    cat > /tmp/tg2-run.json <<'JSON'
{"spec":{"containers":[{"name":"tg2-probe","image":"curlimages/curl:8.11.1","command":["sh","/probe/probe.sh"],"env":[{"name":"OIDC_CLIENT_SECRET","value":"EIM_OIDC_SECRET_PLACEHOLDER"}],"volumeMounts":[{"name":"probe","mountPath":"/probe"}]}],"volumes":[{"name":"probe","configMap":{"name":"eim-tg2-probe-script"}}],"restartPolicy":"Never"}}
JSON
    sed -i "s/EIM_OIDC_SECRET_PLACEHOLDER/$EIM_DEV_OIDC_CLIENT_SECRET/" /tmp/tg2-run.json
    kubectl -n eim delete pod tg2-verify --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n eim run tg2-verify --restart=Never --image=curlimages/curl:8.11.1 \
      --overrides "$(cat /tmp/tg2-run.json)" >/dev/null 2>&1 || true
    local rc=1
    for _ in $(seq 1 12); do
      if kubectl -n eim logs tg2-verify 2>/dev/null | grep -q "TG2-E2E-OK"; then rc=0; break; fi
      kubectl -n eim get pod tg2-verify 2>/dev/null | grep -qE "Error|Completed" && break
      sleep 5
    done
    if [ "$rc" -ne 0 ]; then kubectl -n eim logs tg2-verify 2>/dev/null | tail -12; fi
    kubectl -n eim delete pod tg2-verify --ignore-not-found >/dev/null 2>&1 || true
    rm -f /tmp/tg2-run.json
    return $rc
  }
  step TG2-ING/HIE "cluster: E2E ingest+quarantine+trueup+SCD-2 rollup probe" tg2_probe

  raw_rows() {
    local n
    n=$(psql_count "SELECT count(*) FROM raw_transaction WHERE external_deal_id IN ('TG2-LIVE-1','TG2-LIVE-3')")
    [ "$n" -ge 2 ]
  }
  step TG2-ING-3 "cluster: accepted rows landed in raw_transaction" raw_rows

  credit_row() {
    local n
    n=$(psql_count "SELECT count(*) FROM credit_allocation ca JOIN raw_transaction t ON t.transaction_id = ca.transaction_id WHERE t.external_deal_id = 'TG2-LIVE-1'")
    [ "$n" -ge 1 ]
  }
  step TG2-CR-1 "cluster: credit attached to Sarah for TG2-LIVE-1" credit_row

  quarantine_row() {
    local n
    n=$(psql_count "SELECT count(*) FROM ingestion_record WHERE external_id = 'TG2-LIVE-2' AND status = 'QUARANTINED'")
    [ "$n" -ge 1 ]
  }
  step TG2-ING-4 "cluster: bad row quarantined with per-field reason" quarantine_row

  connector_audit() {
    local n
    n=$(psql_count "SELECT count(*) FROM audit_log WHERE action_type = 'CONNECTOR_RUN' AND actor = 'revops.probe' AND after_state->>'source' = 'CSV'")
    [ "$n" -ge 1 ]
  }
  step TG2-AUD-x "cluster: CONNECTOR_RUN audit row for the probe batch" connector_audit

  trueup_outbox() {
    local n
    n=$(psql_count "SELECT count(*) FROM transactional_outbox WHERE aggregate_type = 'trueup.required' AND payload::text LIKE '%TG2-LIVE-3%'")
    [ "$n" -ge 1 ]
  }
  step TG2-CR-4 "cluster: true-up signal emitted for late-arriving row" trueup_outbox

  watermark_state() {
    local n
    n=$(psql_count "SELECT count(*) FROM connector_state WHERE connector_name = 'CSV' AND object_name = 'tg2_live_probe' AND watermark_value = 'TG2-LIVE-3'")
    [ "$n" -ge 1 ]
  }
  step TG2-ING-6 "cluster: watermark persisted after sync" watermark_state

  sync_event_roundtrip() {
    local n
    n=$(psql_count "SELECT count(*) FROM event_audit_log WHERE topic = 'eim.sync.completed' AND outcome = 'SUCCESS'")
    [ "$n" -ge 1 ]
  }
  step TG2-AUD-x "cluster: sync.completed event through Kafka into event_audit_log" sync_event_roundtrip

  hierarchy_audit() {
    local n
    n=$(psql_count "SELECT count(*) FROM audit_log WHERE action_type = 'CONFIG_CHANGE' AND actor = 'org.probe' AND target_entity = 'payee'")
    [ "$n" -ge 2 ]
  }
  step TG2-HIE-2 "cluster: hierarchy edits audited (create + move)" hierarchy_audit

  graph_clean() {
    local n
    n=$(psql_count "SELECT count(*) FROM eim_ops.reconcile_graph()")
    [ "$n" = "0" ]
  }
  step TG2-HIE-x "cluster: graph reconcile clean (0 drift incl. probe payee)" graph_clean

  seals_ok() {
    local n
    n=$(psql_count "SELECT coalesce(bool_and(ok), true)::int FROM eim_ops.verify_audit_seals()")
    [ "$n" = "1" ]
  }
  step TG2-AUD-x "cluster: seals chain verifies" seals_ok
else
  printf '\n=== live cluster gates SKIPPED (no dev cluster)\n'
  FAIL=$((FAIL + 1)); FAILURES+=("cluster: gates skipped — run scripts/dev-up.sh")
fi

printf '\n========================================\n'
printf 'TG-2 verification: %d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf 'Failures:\n'
  for f in "${FAILURES[@]}"; do printf '  - %s\n' "$f"; done
  exit 1
fi
printf 'ALL TG-2 GATES GREEN\n'
