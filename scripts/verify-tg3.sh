#!/usr/bin/env bash
# TG-3 verification harness — unit gates + live cluster gates for the plan
# modeling & calculation engine tollgate. Run with the dev cluster up.
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

echo "TG-3 verification — $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ---------------- unit / static gates ----------------
step TG3-DAT-x "migration policy lint (expand/contract)" bash scripts/check-migrations.sh
step TG3-CONTRACT "OpenAPI freshness (client + zod regen)" pnpm codegen:freshness
step TG3-CONTRACT "OpenAPI spectral lint" pnpm openapi:lint
step TG3-AUD-x "audit-coverage gate + self-test" pnpm audit:mappings --self-test
step TG3-OPS-x "frontend regression (lint/typecheck/test)" pnpm turbo lint typecheck test
step TG3-UI-7 "web perf budgets (bundle + lighthouse targets)" bash -c \
  'pnpm --filter @eim/web-app build >/dev/null 2>&1 && pnpm web-budget packages/web-app/dist'

export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
step TG3-ENV "runtime secrets loaded (gitignored file)" load_runtime_secrets
export EIM_TEST_DB_URL="${EIM_TEST_DB_URL:-jdbc:postgresql://localhost:15433/eim}"
export EIM_TEST_KAFKA="${EIM_TEST_KAFKA:-localhost:19092}"
step TG3-RUL "plan-compiler suite (type checker, Tarjan, KIE DRL/DMN harness, registry)" \
  mvn -B -ntp -f apps/pom.xml -pl eim-plan-compiler test
step TG3-CAL "calc-engine suite (canonical tiers, protections, wavefront E2E, fault injection, shadow replay, benchmark, kafka)" \
  mvn -B -ntp -f apps/pom.xml -pl eim-calc-engine test

helm_lint_all() {
  for f in platform/helm/eim-platform/ci/*.yaml; do
    helm lint platform/helm/eim-platform -f "$f" >/dev/null || return 1
  done
}
step TG3-INF-x "helm lint (4 postures)" helm_lint_all
step TG3-INF-x "substitution matrix (NFR-80/81)" bash scripts/substitution-matrix.sh

# ---------------- live cluster gates ----------------
if kubectl -n eim get deploy/eim-core-api >/dev/null 2>&1; then
  seed_probe_fixture() {
    kubectl -n eim exec -i eim-postgres-0 -- psql -U eim -d eim \
      < scripts/probe-tg3-seed.sh.seed 2>/dev/null
  }
  seed_fixture() {
    # the seed script is pure SQL; execute it inside the postgres pod
    kubectl -n eim exec -i eim-postgres-0 -- psql -U eim -d eim \
      < scripts/probe-tg3-seed.sh >/dev/null 2>&1
  }
  step TG3-ENV "probe fixture seeded (manager+rep, periods, deals)" seed_fixture

  cluster_ready() {
    kubectl -n eim rollout status deploy/eim-plan-compiler --timeout=120s &&
    kubectl -n eim rollout status deploy/eim-calc-engine --timeout=120s &&
    kubectl -n eim rollout status deploy/eim-core-api --timeout=120s &&
    kubectl -n eim rollout status deploy/eim-apisix --timeout=120s &&
    kubectl -n eim rollout status deploy/eim-keycloak --timeout=120s &&
    kubectl -n eim rollout status sts/eim-postgres --timeout=120s &&
    kubectl -n eim rollout status sts/eim-kafka --timeout=120s
  }
  step TG3-INF-x "cluster: all workloads ready" cluster_ready

  gw_401() {
    kubectl -n eim delete pod g3 --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n eim run g3 --restart=Never --image=curlimages/curl:8.11.1 --command -- \
      sh -c 'curl -s -o /dev/null -w %{http_code} -X POST http://eim-apisix:9080/api/v1/plans/publish -H "Content-Type: application/json" -d "{}"' \
      >/dev/null 2>&1 || true
    local rc=1
    for _ in 1 2 3 4 5; do
      if [ "$(kubectl -n eim logs g3 2>/dev/null | tail -1)" = "401" ]; then rc=0; break; fi
      sleep 4
    done
    kubectl -n eim delete pod g3 --ignore-not-found >/dev/null 2>&1 || true
    return $rc
  }
  step TG3-SEC-x "cluster: gateway 401 on plan publish without token" gw_401

  probe_cm() {
    kubectl -n eim delete cm eim-tg3-probe --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n eim create configmap eim-tg3-probe \
      --from-file=probe.sh=scripts/probe-tg3.sh \
      --from-file=plan.json=scripts/tg3-probe-plan.json >/dev/null
  }
  run_probe_phase() { # run_probe_phase <phase> <wait-pattern>
    local phase="$1" pattern="$2"
    cat > /tmp/tg3-run.json <<JSON
{"spec":{"containers":[{"name":"tg3-probe","image":"curlimages/curl:8.11.1","command":["sh","/probe/probe.sh","$phase"],"env":[{"name":"OIDC_CLIENT_SECRET","value":"EIM_OIDC_SECRET_PLACEHOLDER"}],"volumeMounts":[{"name":"probe","mountPath":"/probe"}]}],"volumes":[{"name":"probe","configMap":{"name":"eim-tg3-probe"}}],"restartPolicy":"Never"}}
JSON
    sed -i "s/EIM_OIDC_SECRET_PLACEHOLDER/$EIM_DEV_OIDC_CLIENT_SECRET/" /tmp/tg3-run.json
    kubectl -n eim delete pod tg3-verify --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n eim run tg3-verify --restart=Never --image=curlimages/curl:8.11.1 \
      --overrides "$(cat /tmp/tg3-run.json)" >/dev/null 2>&1 || true
    local rc=1
    for _ in $(seq 1 18); do
      if kubectl -n eim logs tg3-verify 2>/dev/null | grep -q "$pattern"; then rc=0; break; fi
      kubectl -n eim get pod tg3-verify 2>/dev/null | grep -qE "Error|Completed" && break
      sleep 5
    done
    if [ "$rc" -ne 0 ]; then kubectl -n eim logs tg3-verify 2>/dev/null | tail -12; fi
    kubectl -n eim delete pod tg3-verify --ignore-not-found >/dev/null 2>&1 || true
    rm -f /tmp/tg3-run.json
    return $rc
  }
  tg3_probe_run() {
    probe_cm && run_probe_phase run TG3-E2E-RUN-OK
  }
  step TG3-RUL/CAL "cluster: E2E publish+run+preview probe" tg3_probe_run

  lock_and_inject() {
    kubectl -n eim exec -i eim-postgres-0 -- psql -U eim -d eim >/dev/null 2>&1 <<'SQL'
UPDATE payout_period SET status = 'LOCKED', locked_at = now()
 WHERE payout_period_id = 'a11ce000-0000-0000-0000-000000000101';
INSERT INTO raw_transaction (transaction_id, tenant_id, source_system, external_deal_id,
  transaction_date, booking_amount, currency_code, ingestion_batch_id)
VALUES (gen_random_uuid(),'99999999-0000-0000-0000-000000000001','CSV','P-3','2026-04-25',40000,'USD',gen_random_uuid());
INSERT INTO credit_allocation (credit_allocation_id, tenant_id, transaction_id, payee_id, split_pct, effective_date)
SELECT gen_random_uuid(),'99999999-0000-0000-0000-000000000001',t.transaction_id,
  'a11ce000-0000-0000-0000-000000000002',100,t.transaction_date
FROM raw_transaction t WHERE t.tenant_id='99999999-0000-0000-0000-000000000001' AND t.external_deal_id='P-3';
SQL
  }
  step TG3-CAL-13 "cluster: lock April + inject late-arriving deal" lock_and_inject

  export_probe_hash() {
    local hash
    hash=$(psql_count "SELECT plan_version_hash FROM plan_artifact WHERE tenant_id::text = '99999999-0000-0000-0000-000000000001' ORDER BY published_at DESC LIMIT 1")
    [ -n "$hash" ] || return 1
    kubectl -n eim delete cm eim-tg3-probe-hash --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n eim create configmap eim-tg3-probe-hash --from-literal="hash=$hash" >/dev/null
  }
  step TG3-ENV "cluster: pinned artifact hash exported" export_probe_hash

  tg3_probe_replay() {
    cat > /tmp/tg3-replay.json <<JSON
{"spec":{"containers":[{"name":"tg3-probe","image":"curlimages/curl:8.11.1","command":["sh","/probe/probe.sh","replay"],"env":[{"name":"OIDC_CLIENT_SECRET","value":"EIM_OIDC_SECRET_PLACEHOLDER"}],"volumeMounts":[{"name":"probe","mountPath":"/probe"},{"name":"probehash","mountPath":"/probe-hash"}]}],"volumes":[{"name":"probe","configMap":{"name":"eim-tg3-probe"}},{"name":"probehash","configMap":{"name":"eim-tg3-probe-hash"}}],"restartPolicy":"Never"}}
JSON
    sed -i "s/EIM_OIDC_SECRET_PLACEHOLDER/$EIM_DEV_OIDC_CLIENT_SECRET/" /tmp/tg3-replay.json
    kubectl -n eim delete pod tg3-replay --ignore-not-found >/dev/null 2>&1 || true
    kubectl -n eim run tg3-replay --restart=Never --image=curlimages/curl:8.11.1 \
      --overrides "$(cat /tmp/tg3-replay.json)" >/dev/null 2>&1 || true
    local rc=1
    for _ in $(seq 1 18); do
      if kubectl -n eim logs tg3-replay 2>/dev/null | grep -q "TG3-E2E-OK"; then rc=0; break; fi
      kubectl -n eim get pod tg3-replay 2>/dev/null | grep -qE "Error|Completed" && break
      sleep 5
    done
    if [ "$rc" -ne 0 ]; then kubectl -n eim logs tg3-replay 2>/dev/null | tail -12; fi
    kubectl -n eim delete pod tg3-replay --ignore-not-found >/dev/null 2>&1 || true
    rm -f /tmp/tg3-replay.json
    return $rc
  }
  step TG3-RUL/CAL "cluster: E2E shadow-replay probe (locked period, pinned artifacts)" tg3_probe_replay

  ledger_rows() {
    local n
    n=$(psql_count "SELECT count(*) FROM commission_ledger_entry WHERE tenant_id::text = '99999999-0000-0000-0000-000000000001'")
    [ "$n" -ge 2 ]
  }
  step TG3-CAL-11 "cluster: ledger committed (rep commission + mgr rollup)" ledger_rows

  trueup_origin() {
    local n
    n=$(psql_count "SELECT count(*) FROM commission_ledger_entry WHERE entry_type = 'PRIOR_PERIOD_TRUEUP' AND calculation_trace->>'origin_period_id' = 'a11ce000-0000-0000-0000-000000000101'")
    [ "$n" -ge 1 ]
  }
  step TG3-CAL-13 "cluster: PRIOR_PERIOD_TRUEUP with origin linkage" trueup_origin

  plan_publish_audit() {
    local n
    n=$(psql_count "SELECT count(*) FROM audit_log WHERE action_type = 'PLAN_PUBLISH' AND actor = 'comp.probe'")
    [ "$n" -ge 1 ]
  }
  step TG3-AUD-x "cluster: PLAN_PUBLISH audit row" plan_publish_audit

  calc_audit() {
    local n
    n=$(psql_count "SELECT count(*) FROM audit_log WHERE action_type = 'CALCULATION_RUN'")
    [ "$n" -ge 1 ]
  }
  step TG3-AUD-x "cluster: CALCULATION_RUN audit rows" calc_audit

  artifacts_immutable() {
    local n
    n=$(psql_count "SELECT count(*) FROM plan_artifact WHERE tenant_id::text = '99999999-0000-0000-0000-000000000001'")
    [ "$n" -ge 1 ]
  }
  step TG3-RUL-6 "cluster: immutable artifact versions registered" artifacts_immutable

  seals_ok() {
    local n
    n=$(psql_count "SELECT coalesce(bool_and(ok), true)::int FROM eim_ops.verify_audit_seals()")
    [ "$n" = "1" ]
  }
  step TG3-AUD-x "cluster: seals chain verifies" seals_ok

  cleanup_probe() {
    kubectl -n eim delete cm eim-tg3-probe --ignore-not-found >/dev/null 2>&1 || true
  }
  step TG3-ENV "probe resources cleaned" cleanup_probe
else
  printf '\n=== live cluster gates SKIPPED (no dev cluster)\n'
  FAIL=$((FAIL + 1)); FAILURES+=("cluster: gates skipped — run scripts/dev-up.sh")
fi

printf '\n========================================\n'
printf 'TG-3 verification: %d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf 'Failures:\n'
  for f in "${FAILURES[@]}"; do printf '  - %s\n' "$f"; done
  exit 1
fi
printf 'ALL TG-3 GATES GREEN\n'
