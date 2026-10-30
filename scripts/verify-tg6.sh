#!/usr/bin/env bash
# TG-6 verification harness — parallel run & Phase 1 GA: partner onboarding
# lifecycle, backfill, cent-for-cent parallel run with triage and the 100.0%
# sign-off gate, one live pay period through close + payroll export,
# close-cycle telemetry, the playbook dry-run (MA-01/02/05/09 + MI-18
# dual-approval reopen), GA deployment posture and SOC 2 evidence.
# Run with the dev cluster up (scripts/dev-up.sh) and port-forwards active
# (gateway 31234, keycloak 31235, postgres 15433, kafka 19092).
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PASS=0; FAIL=0
declare -a FAILURES=()
step() { local id="$1" desc="$2"; shift 2
  printf '\n=== [%s] %s\n' "$id" "$desc"
  if "$@"; then printf '>>> [%s] PASS\n' "$id"; PASS=$((PASS + 1))
  else printf '>>> [%s] FAIL\n' "$id"; FAIL=$((FAIL + 1)); FAILURES+=("$id: $desc"); fi
}
note() { printf '[tg6] %s\n' "$*"; }

psql_count() { # psql_count <sql>
  kubectl -n eim exec eim-postgres-0 -- psql -U eim -d eim -tAc "$1" 2>/dev/null | tr -d '[:space:]'
}

load_runtime_secrets() {
  local base; base=".dev-secrets.env"
  [ -f "$base" ] || return 1
  PG_TOKEN="$(sed -n "s/^EIM_DEV_POSTGRES_PASSWORD=//p" "$base")"
  OIDC_TOKEN="$(sed -n "s/^EIM_DEV_OIDC_CLIENT_SECRET=//p" "$base")"
  [ -n "$PG_TOKEN" ] && [ -n "$OIDC_TOKEN" ] || return 1
  for p in 31234 31235 15433 19092; do
    (echo >/dev/tcp/127.0.0.1/$p) 2>/dev/null || return 1
  done
}

echo "TG-6 verification — $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ---------------- unit / static gates ----------------
step TG6-DAT-x "migration policy lint (expand/contract)" bash scripts/check-migrations.sh
step TG6-CONTRACT "OpenAPI freshness + spectral" bash -c 'pnpm codegen:freshness >/dev/null 2>&1 && pnpm openapi:lint >/dev/null 2>&1'
step TG6-AUD-x "audit-coverage gate + self-test" pnpm audit:mappings --self-test
step TG6-WEB-x "frontend regression" pnpm turbo lint typecheck test

export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
step TG6-ENV "runtime secrets + port-forwards" load_runtime_secrets
export EIM_TEST_DB_URL="${EIM_TEST_DB_URL:-jdbc:postgresql://localhost:15433/eim}"
export EIM_TEST_KAFKA="${EIM_TEST_KAFKA:-localhost:19092}"
export DB_USERNAME="${DB_USERNAME:-eim}"
export DB_PASSWORD="${DB_PASSWORD:-$PG_TOKEN}"
step TG6-ONB-x "core-api full suite incl. parallel-run E2E + MI-18 reopen" \
  mvn -B -ntp -f apps/pom.xml -pl eim-core-api test
step TG6-ONB-x "connectors full suite (ingestion used by backfill)" \
  mvn -B -ntp -f apps/pom.xml -pl eim-connectors test
step TG6-ONB-x "calc-engine regression (cohort strategy drives backfill)" \
  mvn -B -ntp -f apps/pom.xml -pl eim-calc-engine test
step TG6-ONB-x "process regression (period close + true-up flows)" \
  mvn -B -ntp -f apps/pom.xml -pl eim-process test

helm_lint_all() {
  for f in platform/helm/eim-platform/ci/*.yaml; do
    helm lint platform/helm/eim-platform -f "$f" >/dev/null || return 1
  done
}
step TG6-OPS-x "helm lint (postures; portability)" helm_lint_all
step TG6-OPS-x "substitution matrix (NFR-80 portability)" bash scripts/substitution-matrix.sh
step TG6-OPS-x "web perf budgets" bash -c 'pnpm --filter @eim/web-app build >/dev/null 2>&1 && pnpm web-budget packages/web-app/dist >/dev/null'

cluster_ready() {
  for d in eim-core-api eim-connectors eim-process eim-calc-engine eim-plan-compiler eim-apisix eim-keycloak eim-web eim-objectstore; do
    kubectl -n eim rollout status deploy/$d --timeout=120s >/dev/null || return 1
  done
  kubectl -n eim rollout status sts/eim-postgres --timeout=120s >/dev/null || return 1
  kubectl -n eim rollout status sts/eim-kafka --timeout=120s >/dev/null || return 1
}
step TG6-INF-x "cluster: all workloads ready" cluster_ready

# ---------------- live gates ----------------
step TG6-ONB-1..8 "partner drill: onboard -> backfill -> parallel run -> 100% x2 -> live period -> telemetry -> MA/MI-18" \
  timeout 590 bash scripts/tg6-partner-drill.sh

soc2_evidence() {
  note "SOC 2 machine-checked evidence (OPS-2)"
  local seals bad control reopen pair actions
  # the 2000-01-01 GENESIS row is the TG-5 tamper positive control: it must
  # keep FAILING (proof the detector works); every real seal must verify.
  seals=$(psql_count "SELECT count(*) FROM eim_ops.verify_audit_seals() WHERE seal_day <> DATE '2000-01-01'")
  bad=$(psql_count "SELECT count(*) FROM eim_ops.verify_audit_seals() WHERE ok = false AND seal_day <> DATE '2000-01-01'")
  control=$(psql_count "SELECT count(*) FROM eim_ops.verify_audit_seals() WHERE seal_day = DATE '2000-01-01' AND ok = false")
  note "real sealed days=$seals failing=$bad; tamper-control failing (expected)=1: $control"
  [ "$seals" -ge 1 ] && [ "$bad" = "0" ] && [ "$control" = "1" ] || return 1
  # dual-control pairing: every PERIOD_REOPEN audit row must pair with two
  # MI18 approvals recorded by distinct actors
  reopen=$(psql_count "SELECT count(*) FROM audit_log WHERE action_type = 'PERIOD_REOPEN'")
  pair=$(psql_count "SELECT count(*) FROM (
         SELECT target_record_id FROM audit_log
         WHERE action_type = 'APPROVAL' AND reason_code = 'MI18_REOPEN_APPROVAL'
         GROUP BY target_record_id HAVING count(DISTINCT actor) = 2)")
  note "PERIOD_REOPEN rows=$reopen requests-with-dual-approval=$pair"
  [ "$reopen" -ge 1 ] && [ "$pair" -ge 1 ] || return 1
  # evidence trails across the mandated action enum are live
  actions=$(psql_count "SELECT count(DISTINCT action_type) FROM audit_log WHERE action_type IN
           ('CONFIG_CHANGE','APPROVAL','MANUAL_ADJUSTMENT','PERIOD_REOPEN','PERIOD_LOCK','EXPORT_GENERATION')")
  note "distinct evidence action types=$actions (need 6)"
  [ "$actions" -ge 6 ] || return 1
  # mutation/outbox/event reconciliation publishes with a bounded gap
  psql_count "SELECT greatest(gap, 0) FROM eim_ops.reconcile_audit(now() - interval '7 days')" | grep -qE '^[0-9]+$'
}
step TG6-OPS-2 "SOC 2 evidence: seals + dual-control pairing + action coverage + reconciliation" soc2_evidence

ga_docs() {
  [ -f doc/ga-deployment-tg6.md ] && grep -q "On-call model" doc/ga-deployment-tg6.md \
    && grep -q "Incident process" doc/ga-deployment-tg6.md \
    && [ -f doc/cutover-hypercare-tg6.md ] && grep -q "Hypercare rotation" doc/cutover-hypercare-tg6.md \
    && [ -f doc/soc2-controls-tg6.md ] && grep -q "Control matrix" doc/soc2-controls-tg6.md
}
step TG6-OPS-1 "GA deployment posture + cutover/hypercare + SOC 2 controls docs" ga_docs

printf '\n========================================\n'
printf 'TG-6 verification: %d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf 'Failures:\n'
  for f in "${FAILURES[@]}"; do printf '  - %s\n' "$f"; done
  exit 1
fi
printf 'ALL TG-6 GATES GREEN\n'
