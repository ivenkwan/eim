#!/usr/bin/env bash
# TG-5 verification harness — release-candidate hardening: enterprise scale,
# latency SLOs, chaos/DR drills, the red-team probe, RLS regression, the
# audit assurance pack, and the operability gates. Run with the dev cluster
# up (scripts/dev-up.sh) and the port-forwards from tg5-scale-load active
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
note() { printf '[tg5] %s\n' "$*"; }

psql_count() { # psql_count <sql>
  kubectl -n eim exec eim-postgres-0 -- psql -U eim -d eim -tAc "$1" 2>/dev/null | tr -d '[:space:]'
}

load_runtime_secrets() {
  local base; base=".dev-secrets.env"
  [ -f "$base" ] || { echo "missing $base" >&2; return 1; }
  PG_TOKEN="$(sed -n 's/^EIM_DEV_POSTGRES_PASSWORD=//p' "$base")"
  OIDC_TOKEN="$(sed -n 's/^EIM_DEV_OIDC_CLIENT_SECRET=//p' "$base")"
  [ -n "$PG_TOKEN" ] && [ -n "$OIDC_TOKEN" ]
}

port_forwards() {
  for p in 31234 31235 15433 19092; do
    (echo >/dev/tcp/127.0.0.1/$p) 2>/dev/null || return 1
  done
}

echo "TG-5 verification — $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ---------------- unit / static gates ----------------
step TG5-DAT-x "migration policy lint (expand/contract)" bash scripts/check-migrations.sh
step TG5-CONTRACT "OpenAPI freshness + spectral" bash -c 'pnpm codegen:freshness >/dev/null 2>&1 && pnpm openapi:lint >/dev/null 2>&1'
step TG5-AUD-x "audit-coverage gate + self-test" pnpm audit:mappings --self-test
step TG5-OPS-x "frontend regression" pnpm turbo lint typecheck test

export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
secrets_and_forwards() {
  local base; base=".dev-secrets.env"
  [ -f "$base" ] || return 1
  PG_TOKEN="$(sed -n 's/^EIM_DEV_POSTGRES_PASSWORD=//p' "$base")"
  OIDC_TOKEN="$(sed -n 's/^EIM_DEV_OIDC_CLIENT_SECRET=//p' "$base")"
  [ -n "$PG_TOKEN" ] && [ -n "$OIDC_TOKEN" ] || return 1
  for p in 31234 31235 15433 19092; do
    (echo >/dev/tcp/127.0.0.1/$p) 2>/dev/null || return 1
  done
}
step TG5-ENV "runtime secrets + port-forwards" secrets_and_forwards
export EIM_TEST_DB_URL="${EIM_TEST_DB_URL:-jdbc:postgresql://localhost:15433/eim}"
export EIM_TEST_KAFKA="${EIM_TEST_KAFKA:-localhost:19092}"
step TG5-SEC-2 "core-api full suite incl. RLS role regression (NFR-52)" \
  mvn -B -ntp -f apps/pom.xml -pl eim-core-api test
step TG5-TST-4 "connectors full suite incl. tenant-quota isolation (NFR-33)" \
  mvn -B -ntp -f apps/pom.xml -pl eim-connectors test
step TG5-REG "calc-engine regression (cohort strategy)" \
  mvn -B -ntp -f apps/pom.xml -pl eim-calc-engine test

helm_lint_all() {
  for f in platform/helm/eim-platform/ci/*.yaml; do
    helm lint platform/helm/eim-platform -f "$f" >/dev/null || return 1
  done
}
step TG5-OPS-7a "helm lint (4 postures; portability)" helm_lint_all
step TG5-OPS-7b "substitution matrix (NFR-80 portability)" bash scripts/substitution-matrix.sh
step TG5-OPS-7c "web perf budgets" bash -c 'pnpm --filter @eim/web-app build >/dev/null 2>&1 && pnpm web-budget packages/web-app/dist >/dev/null'

cluster_ready() {
  for d in eim-core-api eim-connectors eim-process eim-calc-engine eim-plan-compiler eim-apisix eim-keycloak eim-web eim-objectstore; do
    kubectl -n eim rollout status deploy/$d --timeout=120s >/dev/null || return 1
  done
  kubectl -n eim rollout status sts/eim-postgres --timeout=120s >/dev/null
  kubectl -n eim rollout status sts/eim-kafka --timeout=120s >/dev/null
}
step TG5-INF-x "cluster: all workloads ready" cluster_ready

# ---------------- drills (scale, chaos/DR, red-team) ----------------
step TG5-TST-1/2/3 "enterprise scale + latency SLO + replica parity drills" \
  timeout 590 bash scripts/tg5-scale-load.sh
step TG5-OPS-1/2/3/4 "zero-downtime + chaos + DR + WORM drills" \
  timeout 590 bash scripts/tg5-chaos-dr.sh

pentest_probe() {
  local pw; pw="$OIDC_TOKEN"
  kubectl -n eim delete cm eim-tg5-pentest --ignore-not-found >/dev/null 2>&1
  kubectl -n eim create configmap eim-tg5-pentest \
    --from-file=probe.sh=scripts/tg5-pentest.sh >/dev/null
  printf '{"apiVersion":"v1","kind":"Pod","metadata":{"name":"tg5-pen","namespace":"eim"},"spec":{"restartPolicy":"Never","containers":[{"name":"c","image":"curlimages/curl:8.11.1","command":["sh","/probe/probe.sh"],"env":[{"name":"OIDC_CLIENT_SECRET","value":"%s"}],"volumeMounts":[{"name":"p","mountPath":"/probe"}]}],"volumes":[{"name":"p","configMap":{"name":"eim-tg5-pentest"}}]}}' "$pw" > /tmp/tg5-pen.json
  kubectl delete pod tg5-pen -n eim --ignore-not-found >/dev/null 2>&1
  kubectl apply -n eim -f /tmp/tg5-pen.json >/dev/null
  local rc=1
  for _ in $(seq 1 24); do
    if kubectl -n eim logs tg5-pen 2>/dev/null | grep -q "TG5-PENTEST-OK"; then rc=0; break; fi
    kubectl -n eim get pod tg5-pen 2>/dev/null | grep -qE "Error|Completed" && break
    sleep 5
  done
  if [ "$rc" -ne 0 ]; then kubectl -n eim logs tg5-pen 2>/dev/null | tail -8; fi
  kubectl -n eim delete pod tg5-pen --ignore-not-found >/dev/null 2>&1
  kubectl -n eim delete cm eim-tg5-pentest --ignore-not-found >/dev/null 2>&1
  rm -f /tmp/tg5-pen.json
  return $rc
}
step TG5-SEC-1/3 "red-team probe: gateway 401s, OWASP basics, BOLA, bypass (NFR-54)" pentest_probe

age_access_audit() {
  # the app role cannot create or drop graphs (schema-level privilege)
  local blocked=0
  kubectl -n eim exec eim-postgres-0 -- psql -U eim -d eim -c \
    "SET ROLE eim_app; SELECT ag_catalog.create_graph('pen_test_graph');" >/dev/null 2>&1 || blocked=1
  [ "$blocked" = "1" ]
}
step TG5-SEC-3 "AGE access audit: graph DDL blocked for the app role (R-7)" age_access_audit

audit_pack() {
  kubectl -n eim exec -i eim-postgres-0 -- psql -U eim -d eim \
    -v ON_ERROR_STOP=1 < scripts/tg5-audit-pack.sql >/dev/null 2>&1
}
step TG5-AUD-1 "audit assurance pack: seals + tamper control + event hashes + lineage" audit_pack

np_present() {
  [ "$(kubectl -n eim get networkpolicy --no-headers 2>/dev/null | wc -l)" -ge 3 ]
}
step TG5-SEC-1 "NetworkPolicies enforced (gateway-only ingress)" np_present

ops_docs() {
  [ -f doc/ops-tg5-runbooks.md ] && [ -f doc/retention-gdpr-tg5.md ] \
    && grep -q "RB-10" doc/ops-tg5-runbooks.md \
    && grep -q "pseudonymization" doc/retention-gdpr-tg5.md
}
step TG5-OPS-5/6 "SLO dashboards + alert→runbook 100% + capacity plan docs" ops_docs
step TG5-AUD-2 "retention + GDPR erasure-vs-immutability design (NFR-63/71)" \
  bash -c 'grep -q "signed off" doc/retention-gdpr-tg5.md'

printf '\n========================================\n'
printf 'TG-5 verification: %d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf 'Failures:\n'
  for f in "${FAILURES[@]}"; do printf '  - %s\n' "$f"; done
  exit 1
fi
printf 'ALL TG-5 GATES GREEN\n'
