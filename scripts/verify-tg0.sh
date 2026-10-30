#!/usr/bin/env bash
# TG-0 verification harness — runs EVERY tollgate-0 gate locally, the same
# way CI does (.github/workflows/ci.yml). Produces pass/fail per item;
# results are recorded in docs/build-log/TG-0.md.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PASS=0
FAIL=0
declare -a FAILURES=()

step() { # step <id> <description> <cmd...>
  local id="$1"; local desc="$2"; shift 2
  printf '\n=== [%s] %s\n' "$id" "$desc"
  if "$@"; then
    printf '>>> [%s] PASS\n' "$id"
    PASS=$((PASS + 1))
  else
    printf '>>> [%s] FAIL\n' "$id"
    FAIL=$((FAIL + 1))
    FAILURES+=("$id: $desc")
  fi
}

quiet() { "$@" >/dev/null 2>&1; }

helm_lint_all() {
  for f in platform/helm/eim-platform/ci/*.yaml; do
    helm lint platform/helm/eim-platform -f "$f" || return 1
  done
}

kustomize_all() {
  for e in dev staging prod; do
    kustomize build "platform/argocd/apps/overlays/$e" >/dev/null || return 1
  done
}

echo "TG-0 verification — $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ---------------- frontend & guardrails ----------------
step TG0-INF-4 "OpenAPI lint (spectral)" pnpm exec spectral lint openapi/core-api.yaml openapi/connectors.yaml openapi/calc-engine.yaml openapi/process.yaml openapi/plan-compiler.yaml
step TG0-INF-5 "codegen freshness (types + zod vs specs)" pnpm codegen:freshness
step TG0-TST-1 "lint (typed ESLint, all packages)" pnpm turbo lint
step TG0-TST-1 "typecheck (maximal strictness)" pnpm turbo typecheck
step TG0-TST-2 "tests + coverage floors" pnpm turbo test
step TG0-TST-2 "web production build" pnpm --filter @eim/web-app build
step TG0-TST-4 "golden calculation fixtures" pnpm golden
step TG0-TST-6 "audit-coverage gate + self-test" pnpm audit:mappings --self-test
step TG0-TST-3 "float prohibition negative control (must fail lint)" bash -c \
  'if (cd packages/shared-domain && pnpm exec eslint --no-ignore fixtures/float-violation.ts); then echo "fixture unexpectedly passed"; exit 1; fi'

# ---------------- backend ----------------
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
step TG0-INF-2 "Quarkus services build + tests (mvn verify)" \
  mvn -B -ntp -f apps/pom.xml verify

# ---------------- charts, delivery, gitops ----------------
step TG0-INF-6 "helm dependency build" helm dependency build platform/helm/eim-platform
step TG0-INF-6 "helm lint (all 4 postures)" helm_lint_all
step TG0-INF-6 "substitution matrix (NFR-80/81)" bash scripts/substitution-matrix.sh
step TG0-INF-8 "kustomize overlays (dev/staging/prod)" kustomize_all
step TG0-INF-7 "workflow lint (actionlint)" actionlint
step TG0-OPS-x "shellcheck scripts" bash -c 'shellcheck scripts/*.sh'

# ---------------- live cluster smoke (TG0-INF-3) ----------------
if kind get clusters 2>/dev/null | grep -q '^eim-dev$' && \
   kubectl config get-contexts -o name 2>/dev/null | grep -q 'kind-eim-dev'; then
  NS=eim
  step TG0-INF-3 "cluster: core-api rollout ready" \
    kubectl -n "$NS" rollout status deploy/eim-core-api --timeout=60s
  step TG0-INF-3 "cluster: AGE extension loaded" bash -c \
    'kubectl -n eim exec eim-postgres-0 -- psql -U eim -d eim -tAc "SELECT extname FROM pg_extension WHERE extname='"'"'age'"'"'" | grep -qx age'
  step TG0-INF-3 "cluster: AGE graph eim_graph exists" bash -c \
    'kubectl -n eim exec eim-postgres-0 -- psql -U eim -d eim -tAc "SELECT name FROM ag_catalog.ag_graph WHERE name='"'"'eim_graph'"'"'" | grep -qx eim_graph'
  step TG0-INF-3 "cluster: kafka broker responsive" bash -c \
    'kubectl -n eim exec eim-kafka-0 -- /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1'
  step TG0-INF-3 "cluster: objectstore (S3) rollout ready" \
    kubectl -n eim rollout status deploy/eim-objectstore --timeout=60s
  step TG0-INF-3 "cluster: apisix routes core-api hello" bash -c \
    'kubectl -n eim run curl-probe --rm -i --restart=Never --image=curlimages/curl:8.11.1 --command -- curl -sf http://eim-apisix:9080/api/v1/hello | grep -q "\"status\":\"ok\""'
  step TG0-INF-3 "cluster: flyway baseline applied" bash -c \
    'kubectl -n eim exec eim-postgres-0 -- psql -U eim -d eim -tAc "SELECT count(*) FROM schema_baseline" | grep -qv 0'
else
  printf '\n=== [TG0-INF-3] live cluster smoke SKIPPED (no eim-dev cluster running)\n'
  FAILURES+=("TG0-INF-3: cluster smoke skipped — run scripts/dev-up.sh first")
  FAIL=$((FAIL + 1))
fi

# ---------------- summary ----------------
printf '\n========================================\n'
printf 'TG-0 verification: %d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf 'Failures:\n'
  for f in "${FAILURES[@]}"; do printf '  - %s\n' "$f"; done
  exit 1
fi
printf 'ALL TG-0 GATES GREEN\n'
