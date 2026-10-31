#!/usr/bin/env bash
# TG-9 verification harness — sustainment & production readiness: the
# warning fixes (outbox-exporter gate, api_gap reconciliation), the CI
# tiers, the closed deviations (snapshot offload, dirty-payee cache,
# importer hardening), the onboarding automation, and the staging posture.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PASS=0; FAIL=0
declare -a FAILURES=()
step() { local id="$1" desc="$2"; shift 2
  printf '\n=== [%s] %s\n' "$id" "$desc"
  if "$@"; then printf '>>> [%s] PASS\n' "$id"; PASS=$((PASS + 1))
  else printf '>>> [%s] FAIL\n' "$id"; FAIL=$((FAIL + 1)); FAILURES+=("$id: $desc"); fi
}
note() { printf '[tg9] %s\n' "$*"; }

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

echo "TG-9 verification — $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ---------------- unit / static gates ----------------
step TG9-DAT-x "migration policy lint (expand/contract)" bash scripts/check-migrations.sh
step TG9-CONTRACT "OpenAPI freshness + spectral" bash -c 'pnpm codegen:freshness >/dev/null 2>&1 && pnpm openapi:lint >/dev/null 2>&1'
step TG9-AUD-x "audit-coverage gate + self-test" pnpm audit:mappings --self-test
step TG9-WEB-x "frontend lint + typecheck" pnpm turbo lint typecheck

export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
step TG9-ENV "runtime secrets + port-forwards" load_runtime_secrets
export EIM_TEST_DB_URL="${EIM_TEST_DB_URL:-jdbc:postgresql://localhost:15433/eim}"
export EIM_TEST_KAFKA="${EIM_TEST_KAFKA:-localhost:19092}"
export DB_USERNAME="${DB_USERNAME:-eim}"
export DB_PASSWORD="${DB_PASSWORD:-$PG_TOKEN}"
step TG9-A1-x "core-api suite — outbox gate silent, api_gap semantics, importer staging" \
  mvn -B -ntp -f apps/pom.xml -pl eim-core-api test
step TG9-C2-x "calc-engine suite — dirty-cache drain + fallback + FX goldens" \
  mvn -B -ntp -f apps/pom.xml -pl eim-calc-engine test
step TG9-C1-x "connectors suite — snapshot offload never blocks ingest" \
  mvn -B -ntp -f apps/pom.xml -pl eim-connectors test

ci_workflows() {
  python3 -c "
import yaml
for f in ['.github/workflows/nightly.yml', '.github/workflows/quarterly-dr.yml']:
    yaml.safe_load(open(f))
print('workflows parse')"
}
step TG9-A4-x "nightly + quarterly CI workflows parse" ci_workflows

helm_all() {
  for f in platform/helm/eim-platform/ci/*.yaml; do
    helm lint platform/helm/eim-platform -f "$f" >/dev/null || return 1
  done
}
step TG9-B-x "helm lint incl. the staging posture" helm_all

scripts_gate() {
  for s in scripts/onboard-partner.sh scripts/migration-dryrun.sh scripts/cutover-checklist.sh; do
    bash -n "$s" || return 1
  done
}
step TG9-D-x "onboarding + migration + cutover scripts parse" scripts_gate

api_gap_live() {
  # The SHARED dev database accumulates unpaired API audit rows by design:
  # test suites DELETE their transactional_outbox rows in cleanup while
  # audit_log is append-only (NFR-60) — those rows can never re-pair. So a
  # global api_gap=0 assertion would fail forever on this database no
  # matter how correct the code is. Instead this gate proves the ALERT
  # SEMANTICS live: seed one paired audit+outbox row (delta must be 0)
  # and one unpaired API row (delta must be 1), then reconcile.
  note "live api_gap alert semantics on the dev cluster (paired=0 / orphan=+1)"
  local before paired orphan
  before=$(kubectl -n eim exec eim-postgres-0 -- psql -U eim -d eim -tAc \
    "SELECT api_gap FROM eim_ops.reconcile_audit_full(now() - interval '10 minutes')" 2>/dev/null | tr -d '[:space:]')
  kubectl -n eim exec eim-postgres-0 -- psql -U eim -d eim -c \
    "BEGIN; INSERT INTO audit_log (tenant_id, actor, action_type, target_entity, target_record_id, source_channel, occurred_at) VALUES ('aaaaaaaa-9990-0000-0000-000000000001','tg9.gate','CONFIG_CHANGE','tg9gate','tg9-paired-1','API', now()); INSERT INTO transactional_outbox (aggregate_type, aggregate_id, payload, tenant_id, occurred_at) VALUES ('tg9gate','tg9-paired-1','{}','aaaaaaaa-9990-0000-0000-000000000001', now()); COMMIT" >/dev/null 2>&1
  paired=$(kubectl -n eim exec eim-postgres-0 -- psql -U eim -d eim -tAc \
    "SELECT api_gap FROM eim_ops.reconcile_audit_full(now() - interval '10 minutes')" 2>/dev/null | tr -d '[:space:]')
  kubectl -n eim exec eim-postgres-0 -- psql -U eim -d eim -c \
    "INSERT INTO audit_log (tenant_id, actor, action_type, target_entity, target_record_id, source_channel, occurred_at) VALUES ('aaaaaaaa-9990-0000-0000-000000000001','tg9.gate','CONFIG_CHANGE','tg9gate','tg9-orphan-1','API', now())" >/dev/null 2>&1
  orphan=$(kubectl -n eim exec eim-postgres-0 -- psql -U eim -d eim -tAc \
    "SELECT api_gap FROM eim_ops.reconcile_audit_full(now() - interval '10 minutes')" 2>/dev/null | tr -d '[:space:]')
  note "baseline=$before paired=$paired orphan=$orphan"
  [ "$paired" = "$before" ] && [ "$orphan" = "$((paired + 1))" ]
}
step TG9-A2-x "live api_gap alert semantics (paired=0 delta / orphan=+1)" api_gap_live

otel_fixed() {
  for c in eim-core-api eim-connectors; do
    local v
    v=$(kubectl -n eim get deploy "$c" -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="QUARKUS_OTEL_EXPORTER_OTLP_TRACES_EXPORT")].value}' 2>/dev/null)
    note "  $c otel export=$v"
    [ "$v" = "none" ] || return 1
  done
}
step TG9-A3-x "OTel export=none on dev (values respected post-ternary-fix)" otel_fixed

docs_gate() {
  [ -f docs/build-log/TG-9.md ] && grep -q "Honest deviations" docs/build-log/TG-9.md \
    && [ -f docs/release-pipeline.md ] && grep -q "staging gate" docs/release-pipeline.md \
    && [ -f docs/partner-parallel-run.template.md ]
}
step TG9-DOC "build log + release pipeline + parallel-run template docs" docs_gate

printf '\n========================================\n'
printf 'TG-9 verification: %d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf 'Failures:\n'
  for f in "${FAILURES[@]}"; do printf '  - %s\n' "$f"; done
  exit 1
fi
printf 'ALL TG-9 GATES GREEN\n'
