#!/usr/bin/env bash
# TG-7 verification harness — Phase 2: ASC 606/IFRS 15 accounting &
# enterprise: the accounting module (classification, amortization,
# lifecycle, multi-book, FX lock, rollforward, JEs, lineage, close
# process), multi-currency FX goldens, incremental recalculation, ERP
# reads + metering ingestion, dispute SLA, security ops (malware / GDPR /
# residency), the four UI surfaces, and the live accounting probe with the
# NFR-25 rollforward-scale and NFR-21 metering-scale gates.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PASS=0; FAIL=0
declare -a FAILURES=()
step() { local id="$1" desc="$2"; shift 2
  printf '\n=== [%s] %s\n' "$id" "$desc"
  if "$@"; then printf '>>> [%s] PASS\n' "$id"; PASS=$((PASS + 1))
  else printf '>>> [%s] FAIL\n' "$id"; FAIL=$((FAIL + 1)); FAILURES+=("$id: $desc"); fi
}
note() { printf '[tg7] %s\n' "$*"; }

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

echo "TG-7 verification — $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ---------------- unit / static gates ----------------
step TG7-DAT-x "migration policy lint (expand/contract)" bash scripts/check-migrations.sh
step TG7-CONTRACT "OpenAPI freshness + spectral" bash -c 'pnpm codegen:freshness >/dev/null 2>&1 && pnpm openapi:lint >/dev/null 2>&1'
step TG7-AUD-x "audit-coverage gate + self-test" pnpm audit:mappings --self-test
step TG7-WEB-x "frontend lint + typecheck" pnpm turbo lint typecheck

export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
step TG7-ENV "runtime secrets + port-forwards" load_runtime_secrets
export EIM_TEST_DB_URL="${EIM_TEST_DB_URL:-jdbc:postgresql://localhost:15433/eim}"
export EIM_TEST_KAFKA="${EIM_TEST_KAFKA:-localhost:19092}"
export DB_USERNAME="${DB_USERNAME:-eim}"
export DB_PASSWORD="${DB_PASSWORD:-$PG_TOKEN}"
step TG7-ACC-x "core-api full suite incl. accounting E2E + DMN matrix + SLA + security ops" \
  mvn -B -ntp -f apps/pom.xml -pl eim-core-api test
step TG7-ACC-x "process suite incl. accounting-close process" \
  mvn -B -ntp -f apps/pom.xml -pl eim-process test
step TG7-CAL-x "calc-engine suite incl. FX goldens (NFR-110)" \
  mvn -B -ntp -f apps/pom.xml -pl eim-calc-engine test
step TG7-ING-x "connectors suite incl. ERP reads + metering" \
  mvn -B -ntp -f apps/pom.xml -pl eim-connectors test
step TG7-UI-x "web-app tests incl. TG-7 surfaces (estimator, dashboards, CRM embed, console)" \
  bash -c 'pnpm --filter @eim/web-app test >/dev/null 2>&1'

helm_lint_all() {
  for f in platform/helm/eim-platform/ci/*.yaml; do
    helm lint platform/helm/eim-platform -f "$f" >/dev/null || return 1
  done
}
step TG7-OPS-x "helm lint (postures; portability)" helm_lint_all
step TG7-OPS-x "substitution matrix (NFR-80 portability)" bash scripts/substitution-matrix.sh

cluster_ready() {
  for d in eim-core-api eim-connectors eim-process eim-calc-engine eim-plan-compiler eim-apisix eim-keycloak eim-web eim-objectstore; do
    kubectl -n eim rollout status deploy/$d --timeout=120s >/dev/null || return 1
  done
  kubectl -n eim rollout status sts/eim-postgres --timeout=120s >/dev/null || return 1
  kubectl -n eim rollout status sts/eim-kafka --timeout=120s >/dev/null || return 1
}
step TG7-INF-x "cluster: all workloads ready" cluster_ready

# ---------------- live gates ----------------
step TG7-PROBE "accounting probe: books → classification → PO amortization → JEs → lifecycle → close → SLA → security → scale" \
  timeout 1500 bash scripts/tg7-accounting-probe.sh

soc2_typeii_evidence() {
  note "SOC 2 Type II operating evidence + Phase-2 action coverage"
  local seals bad control actions reopen pair blocked erased
  seals=$(psql_count "SELECT count(*) FROM eim_ops.verify_audit_seals() WHERE seal_day <> DATE '2000-01-01'")
  bad=$(psql_count "SELECT count(*) FROM eim_ops.verify_audit_seals() WHERE ok = false AND seal_day <> DATE '2000-01-01'")
  control=$(psql_count "SELECT count(*) FROM eim_ops.verify_audit_seals() WHERE seal_day = DATE '2000-01-01' AND ok = false")
  note "real sealed days=$seals failing=$bad; tamper control failing (expected)=1: $control"
  [ "$seals" -ge 1 ] && [ "$bad" = "0" ] && [ "$control" = "1" ] || return 1
  reopen=$(psql_count "SELECT count(*) FROM audit_log WHERE action_type = 'PERIOD_REOPEN'")
  pair=$(psql_count "SELECT count(*) FROM (
         SELECT target_record_id FROM audit_log
         WHERE action_type = 'APPROVAL' AND reason_code = 'MI18_REOPEN_APPROVAL'
         GROUP BY target_record_id HAVING count(DISTINCT actor) = 2)")
  [ "$reopen" -ge 1 ] && [ "$pair" -ge 1 ] || return 1
  blocked=$(psql_count "SELECT count(*) FROM audit_log WHERE reason_code = 'ATTACHMENT_SCAN_BLOCKED'")
  erased=$(psql_count "SELECT count(*) FROM audit_log WHERE reason_code = 'GDPR_ERASURE'")
  note "malware-gate blocks=$blocked; GDPR erasures=$erased"
  [ "$blocked" -ge 1 ] && [ "$erased" -ge 1 ] || return 1
  actions=$(psql_count "SELECT (SELECT count(DISTINCT reason_code) FROM audit_log WHERE reason_code IN
           ('ASC606_CLASSIFICATION','JE_GENERATION','ST_LT_RECLASS','ERP_PUSH','FR903_RETRO_TRUEUP',
            'FR903_CHURN','SLA_ESCALATION_BREACH','GDPR_ERASURE','ATTACHMENT_SCAN_BLOCKED'))
           + (SELECT count(DISTINCT action_type) FROM audit_log WHERE action_type = 'GL_PUSH')")
  note "Phase-2 evidence reason codes+actions=$actions (need 9)"
  [ "$actions" -ge 9 ] || return 1
}
step TG7-SEC-1 "SOC 2 Type II evidence: seals + dual-control + malware gate + GDPR + Phase-2 actions" soc2_typeii_evidence

tg7_docs() {
  [ -f docs/build-log/TG-7.md ] && grep -q "Honest deviations" docs/build-log/TG-7.md \
    && [ -f docs/security/soc2-typeii-and-residency-tg7.md ] \
    && grep -q "Type II" docs/security/soc2-typeii-and-residency-tg7.md \
    && grep -q "Data residency" docs/security/soc2-typeii-and-residency-tg7.md
}
step TG7-OPS-x "TG-7 build log + SOC 2 Type II / residency docs" tg7_docs

printf '\n========================================\n'
printf 'TG-7 verification: %d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf 'Failures:\n'
  for f in "${FAILURES[@]}"; do printf '  - %s\n' "$f"; done
  exit 1
fi
printf 'ALL TG-7 GATES GREEN\n'
