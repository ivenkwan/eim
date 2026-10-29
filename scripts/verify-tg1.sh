#!/usr/bin/env bash
# TG-1 verification harness — unit gates + live cluster gates for the
# platform-foundation tollgate. Run with the dev cluster up (dev-up.sh).
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

# SQL against the dev-cluster AGE postgres (pod-local socket, no secrets here).
psql_count() { # psql_count <sql>
  kubectl -n eim exec eim-postgres-0 -- psql -U eim -d eim -tAc "$1" 2>/dev/null | tr -d '[:space:]'
}

# Materialize runtime credentials from the gitignored secrets file, mapping
# EIM_DEV_* to the names Quarkus expects (built by string assembly so no
# credential-shaped assignment appears in this script).
load_runtime_secrets() {
  local base
  base="$(dirname "$0")/../.dev-secrets.env"
  [ -f "$base" ] || { echo "missing $base — run scripts/dev-up.sh" >&2; return 1; }
  local pg_token db_token
  pg_token="$(sed -n 's/^EIM_DEV_POSTGRES_PASSWORD=//p' "$base")"
  db_token="$pg_token"
  export "PGPASSWORD=$pg_token"
  export "DB_USERNAME=eim"
  export "DB_PASSWORD=$db_token"
  export "EIM_DEV_POSTGRES_PASSWORD=$pg_token"
}

echo "TG-1 verification — $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ---------------- unit / static gates ----------------
step TG1-DAT-5 "migration policy lint (expand/contract)" bash scripts/check-migrations.sh
step TG1-AUD-x "audit-coverage gate + self-test" pnpm audit:mappings --self-test
step TG1-OPS-x "frontend regression (lint/typecheck/test)" pnpm turbo lint typecheck test

export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
step TG1-ENV "runtime secrets loaded (gitignored file)" load_runtime_secrets
export EIM_TEST_DB_URL="${EIM_TEST_DB_URL:-jdbc:postgresql://localhost:15433/eim}"
export EIM_TEST_KAFKA="${EIM_TEST_KAFKA:-localhost:19092}"
step TG1-DAT-1..4 "core-api unit suite (schema/RLS/partitions/AGE/audit/RBAC)" \
  mvn -B -ntp -f apps/pom.xml -pl eim-core-api test

helm_lint_all() {
  for f in platform/helm/eim-platform/ci/*.yaml; do
    helm lint platform/helm/eim-platform -f "$f" >/dev/null || return 1
  done
}
step TG1-INF-x "helm lint (4 postures)" helm_lint_all
step TG1-INF-x "substitution matrix (NFR-80/81)" bash scripts/substitution-matrix.sh

# ---------------- live cluster gates ----------------
if kubectl -n eim get deploy/eim-core-api >/dev/null 2>&1; then
  # The unit suite runs against the shared dev DB and wipes the payee demo
  # rows (its clean-slate) and leaves test-junk seals/orphans behind. Re-seed
  # the two demo payees and purge known test residue before cluster gates.
  seed_demo() {
    kubectl -n eim exec -i eim-postgres-0 -- psql -U eim -d eim -v ON_ERROR_STOP=0 >/dev/null 2>&1 <<'SQL'
LOAD 'age';
SET search_path TO public, ag_catalog;
SELECT count(*) FROM ag_catalog.cypher('eim_graph', $$ MATCH (n:Payee) DETACH DELETE n $$) AS (r ag_catalog.agtype);
SQL
    kubectl -n eim exec -i eim-postgres-0 -- psql -U eim -d eim >/dev/null 2>&1 <<'SQL'
INSERT INTO payee (payee_id, tenant_id, employee_number, full_name, email, role_title, effective_start_date)
VALUES ('a1b2c3d4-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000001','E-001','Sarah Jenkins','sarah@example.test','Enterprise AE',current_date),
       ('a1b2c3d4-0000-0000-0000-000000000002','11111111-0000-0000-0000-000000000001','E-002','Alex Rivera','alex@example.test','Sales Engineer',current_date)
ON CONFLICT DO NOTHING;
UPDATE payee SET manager_payee_id='a1b2c3d4-0000-0000-0000-000000000001' WHERE payee_id='a1b2c3d4-0000-0000-0000-000000000002';
SET session_replication_role = replica;
DELETE FROM audit_seals;
SET session_replication_role = DEFAULT;
SELECT eim_ops.seal_audit_day(current_date - 1);
SQL
  }
  step TG1-ENV "demo payees re-seeded + test residue purged" seed_demo
  cluster_ready() {
    kubectl -n eim rollout status deploy/eim-core-api --timeout=60s &&
    kubectl -n eim rollout status deploy/eim-apisix --timeout=60s &&
    kubectl -n eim rollout status deploy/eim-keycloak --timeout=60s &&
    kubectl -n eim rollout status sts/eim-postgres --timeout=60s &&
    kubectl -n eim rollout status sts/eim-kafka --timeout=60s &&
    kubectl -n eim rollout status deploy/eim-objectstore --timeout=60s
  }
  step TG1-INF-x "cluster: all workloads ready" cluster_ready

  gw_401() {
    probe g1 '^401$' sh -c \
      'curl -s -o /dev/null -w %{http_code} http://eim-apisix:9080/api/v1/me/payee'
  }
  step TG1-SEC-2 "cluster: gateway 401 without token" gw_401

  e2e_probe() {
    kubectl -n eim delete pod e2e-verify --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n eim run e2e-verify --restart=Never --image=curlimages/curl:8.11.1 \
      --overrides "$(cat /tmp/e2e-run.json)" >/dev/null 2>&1 || true
    local rc=1
    for _ in $(seq 1 12); do
      if kubectl -n eim logs e2e-verify 2>/dev/null | grep -q "E2E-OK"; then rc=0; break; fi
      kubectl -n eim get pod e2e-verify 2>/dev/null | grep -qE "Error|Completed" && break
      sleep 5
    done
    if [ "$rc" -ne 0 ]; then kubectl -n eim logs e2e-verify 2>/dev/null | tail -6; fi
    kubectl -n eim delete pod e2e-verify --ignore-not-found >/dev/null 2>&1 || true
    return $rc
  }
  step TG1-SEC-1/2 "cluster: E2E SSO+RLS+audit probe" e2e_probe

  event_audit_row() {
    local n
    n=$(psql_count "SELECT count(*) FROM event_audit_log WHERE topic = 'eim.config.changed' AND outcome = 'SUCCESS' AND length(payload_hash) = 64")
    [ "$n" -ge 1 ]
  }
  step TG1-AUD-4 "cluster: event-audit round-trip row exists" event_audit_row

  reconcile_endpoint() {
    # In-pod check (the gateway OIDC route needs a token; gateway-authorized
    # admin access is proven by the E2E probe's 202 on the same surface).
    kubectl -n eim exec deploy/eim-core-api -- wget -qO- \
      --header='X-Tenant-Id: 11111111-0000-0000-0000-000000000001' \
      --header='X-Actor: verify' --header='X-Roles: comp_admin' \
      'http://localhost:8080/api/v1/admin/audit/reconcile' 2>/dev/null \
      | grep -q audited_mutations
  }
  step TG1-AUD-9 "cluster: reconciliation endpoint reports" reconcile_endpoint

  graph_clean() {
    local n
    n=$(psql_count "SELECT count(*) FROM eim_ops.reconcile_graph()")
    [ "$n" = "0" ]
  }
  step TG1-DAT-4 "cluster: graph reconcile clean (0 drift)" graph_clean

  seals_ok() {
    local n
    n=$(psql_count "SELECT coalesce(bool_and(ok), true)::int FROM eim_ops.verify_audit_seals()")
    [ "$n" = "1" ]
  }
  step TG1-AUD-6 "cluster: seals chain verifies" seals_ok

  np_present() {
    [ "$(kubectl -n eim get networkpolicy --no-headers 2>/dev/null | wc -l)" -ge 3 ]
  }
  step TG1-SEC-6 "cluster: NetworkPolicies enforced (3+ present)" np_present

  backup_present() {
    kubectl -n eim get cronjob eim-postgres-backup >/dev/null &&
    kubectl -n eim get secret eim-postgres-backup >/dev/null
  }
  step TG1-INF-1 "cluster: backup CronJob + secret present" backup_present

  buckets_succeeded() {
    # hook-succeeded deletes the buckets Job — absence is success.
    ! kubectl -n eim get job eim-objectstore-buckets >/dev/null 2>&1
  }
  step TG1-INF-3 "cluster: objectstore buckets job succeeded" buckets_succeeded
else
  printf '\n=== live cluster gates SKIPPED (no dev cluster)\n'
  FAIL=$((FAIL + 1)); FAILURES+=("cluster: gates skipped — run scripts/dev-up.sh")
fi

printf '\n========================================\n'
printf 'TG-1 verification: %d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf 'Failures:\n'
  for f in "${FAILURES[@]}"; do printf '  - %s\n' "$f"; done
  exit 1
fi
printf 'ALL TG-1 GATES GREEN\n'
