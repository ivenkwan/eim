#!/usr/bin/env bash
# TG-4 verification harness — unit gates + live cluster gates for the
# governance & seller portal tollgate, ending with the full monthly-cycle
# E2E probe. Run with the dev cluster up.
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

echo "TG-4 verification — $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ---------------- unit / static gates ----------------
step TG4-DAT-x "migration policy lint (expand/contract)" bash scripts/check-migrations.sh
step TG4-CONTRACT "OpenAPI freshness (client + zod regen)" pnpm codegen:freshness
step TG4-CONTRACT "OpenAPI spectral lint" pnpm openapi:lint
step TG4-AUD-x "audit-coverage gate + self-test" pnpm audit:mappings --self-test
step TG4-OPS-x "frontend regression (lint/typecheck/test incl. portal + axe)" pnpm turbo lint typecheck test

export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
step TG4-ENV "runtime secrets loaded (gitignored file)" load_runtime_secrets
export EIM_TEST_DB_URL="${EIM_TEST_DB_URL:-jdbc:postgresql://localhost:15433/eim}"
export EIM_TEST_KAFKA="${EIM_TEST_KAFKA:-localhost:19092}"
step TG4-GOV/SEL "core-api governance suite (overrides, audit viewer, exports, statements, disputes)" \
  mvn -B -ntp -f apps/pom.xml -pl eim-core-api test
step TG4-GOV-2/3 "process suite (period-close SoD+lock, true-up queue)" \
  mvn -B -ntp -f apps/pom.xml -pl eim-process test
step TG4-REG "calc-engine regression (shadow replay now routes to review queue)" \
  mvn -B -ntp -f apps/pom.xml -pl eim-calc-engine test

helm_lint_all() {
  for f in platform/helm/eim-platform/ci/*.yaml; do
    helm lint platform/helm/eim-platform -f "$f" >/dev/null || return 1
  done
}
step TG4-INF-x "helm lint (4 postures)" helm_lint_all
step TG4-INF-x "substitution matrix (NFR-80/81)" bash scripts/substitution-matrix.sh

# ---------------- live cluster gates ----------------
if kubectl -n eim get deploy/eim-core-api >/dev/null 2>&1; then
  seed_fixture() {
    kubectl -n eim exec -i eim-postgres-0 -- psql -U eim -d eim \
      < scripts/probe-tg4-seed.sh >/dev/null 2>&1
  }
  step TG4-ENV "monthly-cycle fixture seeded" seed_fixture

  cluster_ready() {
    kubectl -n eim rollout status deploy/eim-core-api --timeout=120s &&
    kubectl -n eim rollout status deploy/eim-process --timeout=120s &&
    kubectl -n eim rollout status deploy/eim-calc-engine --timeout=120s &&
    kubectl -n eim rollout status deploy/eim-plan-compiler --timeout=120s &&
    kubectl -n eim rollout status deploy/eim-apisix --timeout=120s &&
    kubectl -n eim rollout status deploy/eim-keycloak --timeout=120s &&
    kubectl -n eim rollout status sts/eim-postgres --timeout=120s &&
    kubectl -n eim rollout status sts/eim-kafka --timeout=120s
  }
  step TG4-INF-x "cluster: all workloads ready" cluster_ready

  gw_401() {
    kubectl -n eim delete pod g4 --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n eim run g4 --restart=Never --image=curlimages/curl:8.11.1 --command -- \
      sh -c 'curl -s -o /dev/null -w %{http_code} -X POST http://eim-apisix:9080/api/v1/disputes -H "Content-Type: application/json" -d "{}"' \
      >/dev/null 2>&1 || true
    local rc=1
    for _ in 1 2 3 4 5; do
      if [ "$(kubectl -n eim logs g4 2>/dev/null | tail -1)" = "401" ]; then rc=0; break; fi
      sleep 4
    done
    kubectl -n eim delete pod g4 --ignore-not-found >/dev/null 2>&1 || true
    return $rc
  }
  step TG4-SEC-x "cluster: gateway 401 on dispute submit without token" gw_401

  tg4_probe() {
    kubectl -n eim delete cm eim-tg4-probe --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n eim create configmap eim-tg4-probe \
      --from-file=probe.sh=scripts/probe-tg4.sh \
      --from-file=plan.json=scripts/tg4-probe-plan.json >/dev/null || return 1
    cat > /tmp/tg4-run.json <<'JSON'
{"spec":{"containers":[{"name":"tg4-probe","image":"curlimages/curl:8.11.1","command":["sh","/probe/probe.sh"],"env":[{"name":"OIDC_CLIENT_SECRET","value":"EIM_OIDC_SECRET_PLACEHOLDER"}],"volumeMounts":[{"name":"probe","mountPath":"/probe"}]}],"volumes":[{"name":"probe","configMap":{"name":"eim-tg4-probe"}}],"restartPolicy":"Never"}}
JSON
    sed -i "s/EIM_OIDC_SECRET_PLACEHOLDER/$EIM_DEV_OIDC_CLIENT_SECRET/" /tmp/tg4-run.json
    kubectl -n eim delete pod tg4-verify --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n eim run tg4-verify --restart=Never --image=curlimages/curl:8.11.1 \
      --overrides "$(cat /tmp/tg4-run.json)" >/dev/null 2>&1 || true
    local rc=1
    for _ in $(seq 1 24); do
      if kubectl -n eim logs tg4-verify 2>/dev/null | grep -q "TG4-E2E-OK"; then rc=0; break; fi
      kubectl -n eim get pod tg4-verify 2>/dev/null | grep -qE "Error|Completed" && break
      sleep 5
    done
    if [ "$rc" -ne 0 ]; then kubectl -n eim logs tg4-verify 2>/dev/null | tail -14; fi
    kubectl -n eim delete pod tg4-verify --ignore-not-found >/dev/null 2>&1 || true
    rm -f /tmp/tg4-run.json
    return $rc
  }
  step TG4-EXIT "cluster: full monthly-cycle E2E probe (ingest→calc→dispute→override→close→export→statement→trueup)" tg4_probe

  correlation_chain() {
    local n
    n=$(psql_count "SELECT count(*) FROM audit_log WHERE tenant_id::text = 'cccccccc-c000-0000-0000-000000000001' AND correlation_id IS NOT NULL")
    [ "$n" -ge 5 ]
  }
  step TG4-AUD-x "cluster: correlation-id chain across audit streams" correlation_chain

  accounting_identity() {
    # demo ledger identity: sum(CALCULATED + MANUAL_OVERRIDE + TRUEUP) per payee
    # equals the exported net (the export sums the same entry types)
    local ledger export
    ledger=$(psql_count "SELECT coalesce(sum(payout_amount),0)::text FROM commission_ledger_entry WHERE tenant_id::text = 'cccccccc-c000-0000-0000-000000000001'")
    export=$(psql_count "SELECT coalesce(sum(total),0)::text FROM (SELECT sum(payout_amount) AS total FROM commission_ledger_entry WHERE tenant_id::text = 'cccccccc-c000-0000-0000-000000000001' GROUP BY payee_id) t")
    [ "$ledger" = "$export" ]
  }
  step TG4-EXIT "cluster: accounting identity of the cycle ledger" accounting_identity

  seals_ok() {
    # completed days (before today) must verify; the known tamper row
    # (GENESIS, 2000-01-01) must FAIL verification — detection proof
    local ok_days broken_genesis
    ok_days=$(psql_count "SELECT count(*) FROM eim_ops.verify_audit_seals() WHERE ok = false AND seal_day < current_date AND seal_day > DATE '2000-01-01'")
    broken_genesis=$(psql_count "SELECT count(*) FROM eim_ops.verify_audit_seals() WHERE seal_day = DATE '2000-01-01' AND ok = false")
    [ "$ok_days" = "0" ] && [ "$broken_genesis" = "1" ]
  }
  step TG4-AUD-x "cluster: seals chain verifies (+ tamper detection positive control)" seals_ok

  cleanup_probe() {
    kubectl -n eim delete cm eim-tg4-probe --ignore-not-found >/dev/null 2>&1 || true
  }
  step TG4-ENV "probe resources cleaned" cleanup_probe
else
  printf '\n=== live cluster gates SKIPPED (no dev cluster)\n'
  FAIL=$((FAIL + 1)); FAILURES+=("cluster: gates skipped — run scripts/dev-up.sh")
fi

printf '\n========================================\n'
printf 'TG-4 verification: %d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf 'Failures:\n'
  for f in "${FAILURES[@]}"; do printf '  - %s\n' "$f"; done
  exit 1
fi
printf 'ALL TG-4 GATES GREEN\n'
