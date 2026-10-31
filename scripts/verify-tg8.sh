#!/usr/bin/env bash
# TG-8 verification harness — Phase 3: full SPM & channel expansion: the
# Monte Carlo simulation engine + FX-hardened calc suite, the Phase-3 E2E
# (territories/routing/quota, partner portal + claims, NL modeling with the
# SoD human gate, diagnostics, explainer), the simulation UI, and the live
# Phase-3 probe through the gateway.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PASS=0; FAIL=0
declare -a FAILURES=()
step() { local id="$1" desc="$2"; shift 2
  printf '\n=== [%s] %s\n' "$id" "$desc"
  if "$@"; then printf '>>> [%s] PASS\n' "$id"; PASS=$((PASS + 1))
  else printf '>>> [%s] FAIL\n' "$id"; FAIL=$((FAIL + 1)); FAILURES+=("$id: $desc"); fi
}
note() { printf '[tg8] %s\n' "$*"; }

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

echo "TG-8 verification — $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ---------------- unit / static gates ----------------
step TG8-DAT-x "migration policy lint (expand/contract)" bash scripts/check-migrations.sh
step TG8-CONTRACT "OpenAPI freshness + spectral" bash -c 'pnpm codegen:freshness >/dev/null 2>&1 && pnpm openapi:lint >/dev/null 2>&1'
step TG8-AUD-x "audit-coverage gate + self-test" pnpm audit:mappings --self-test
step TG8-WEB-x "frontend lint + typecheck" pnpm turbo lint typecheck

export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
step TG8-ENV "runtime secrets + port-forwards" load_runtime_secrets
export EIM_TEST_DB_URL="${EIM_TEST_DB_URL:-jdbc:postgresql://localhost:15433/eim}"
export EIM_TEST_KAFKA="${EIM_TEST_KAFKA:-localhost:19092}"
export DB_USERNAME="${DB_USERNAME:-eim}"
export DB_PASSWORD="${DB_PASSWORD:-$PG_TOKEN}"
step TG8-ALL-x "core-api full suite incl. Phase-3 E2E (territories/partners/claims/NL/diagnostics/explainer/importer)" \
  mvn -B -ntp -f apps/pom.xml -pl eim-core-api test
step TG8-CAL-x "calc-engine suite incl. Monte Carlo goldens (FR-1001)" \
  mvn -B -ntp -f apps/pom.xml -pl eim-calc-engine test
step TG8-UI-x "web-app tests incl. the simulation explorer (FR-1001 UI)" \
  bash -c 'pnpm --filter @eim/web-app test >/dev/null 2>&1'

helm_lint_all() {
  for f in platform/helm/eim-platform/ci/*.yaml; do
    helm lint platform/helm/eim-platform -f "$f" >/dev/null || return 1
  done
}
step TG8-OPS-x "helm lint (postures; portability)" helm_lint_all
step TG8-OPS-x "substitution matrix (NFR-80 portability)" bash scripts/substitution-matrix.sh

cluster_ready() {
  for d in eim-core-api eim-connectors eim-process eim-calc-engine eim-plan-compiler eim-apisix eim-keycloak eim-web eim-objectstore; do
    kubectl -n eim rollout status deploy/$d --timeout=120s >/dev/null || return 1
  done
  kubectl -n eim rollout status sts/eim-postgres --timeout=120s >/dev/null || return 1
  kubectl -n eim rollout status sts/eim-kafka --timeout=120s >/dev/null || return 1
}
step TG8-INF-x "cluster: all workloads ready" cluster_ready

# ---------------- live gates ----------------
step TG8-PROBE "Phase-3 probe: territories/routing → partner portal/claims → NL gate → diagnostics/explainer → Monte Carlo → migration" \
  timeout 900 bash scripts/tg8-phase3-probe.sh

phase3_audit_evidence() {
  note "Phase-3 audit coverage"
  local q v
  q() { kubectl -n eim exec eim-postgres-0 -- psql -U eim -d eim -tAc "SELECT count(*) FROM $1" 2>/dev/null | tr -d '[:space:]'; }
  for pair in \
    "audit_log WHERE reason_code='TERRITORY_CREATED'|1" \
    "audit_log WHERE reason_code='ROUTING_RULE_CREATED'|1" \
    "audit_log WHERE reason_code='PARTNER_REGISTERED'|1" \
    "audit_log WHERE reason_code='CHANNEL_ACCRUAL'|1" \
    "audit_log WHERE reason_code='NL_PLAN_COMPILED'|1" \
    "audit_log WHERE reason_code='NL_PLAN_APPROVED'|1" \
    "audit_log WHERE reason_code='AI_PLAN_DIAGNOSTICS'|1" \
    "audit_log WHERE reason_code='SPM_IMPORT'|1"; do
    local where="${pair%|*}" min="${pair#*|}"
    v=$(q "$where")
    note "  $where = $v (need >= $min)"
    [ "$v" -ge "$min" ] || return 1
  done
}
step TG8-AUD "Phase-3 audit coverage across the new surfaces" phase3_audit_evidence

tg8_docs() {
  [ -f docs/build-log/TG-8.md ] && grep -q "Honest deviations" docs/build-log/TG-8.md
}
step TG8-OPS-x "TG-8 build log" tg8_docs

printf '\n========================================\n'
printf 'TG-8 verification: %d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf 'Failures:\n'
  for f in "${FAILURES[@]}"; do printf '  - %s\n' "$f"; done
  exit 1
fi
printf 'ALL TG-8 GATES GREEN\n'
