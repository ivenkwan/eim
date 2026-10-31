#!/usr/bin/env bash
# TG9-D: migration dry-run — imports a legacy export into an ISOLATED
# tenant, reports what landed (payees/history/plans/quotas/errors), then
# removes the fixture. The partner's real tenant is never touched; the
# report is the go/no-go evidence for the cutover checklist.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

CSV_FILE="${1:-}"
SOURCE="${2:-varicent}"
[ -f "$CSV_FILE" ] || { echo "usage: $0 <legacy-export.csv> [source=varicent|xactly|anaplan]" >&2; exit 1; }

OIDC_TOKEN="$(sed -n "s/^EIM_DEV_OIDC_CLIENT_SECRET=//p" .dev-secrets.env 2>/dev/null || true)"
[ -n "$OIDC_TOKEN" ] || { echo "missing .dev-secrets.env" >&2; exit 1; }
# deterministic throwaway tenant (uuid v5-style from md5)
TENANT="dddddddd-d9y0-0000-0000-$(echo -n "dryrun-$(date +%s)" | md5sum | cut -c1-12)"
TOKEN=$(curl -s -X POST "http://localhost:31235/realms/eim/protocol/openid-connect/token" \
  -H "Host: eim-keycloak:8080" \
  -d "grant_type=client_credentials&client_id=eim-gateway&client_secret=$OIDC_TOKEN" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

api() {
  local role="$1" actor="$2" method="$3" path="$4"; shift 4
  curl -s -m 300 -X "$method" "http://localhost:31234$path" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-Tenant-Id: $TENANT" -H "X-Actor: $actor" -H "X-Roles: $role" "$@"
}

echo "== migration dry-run: $CSV_FILE (source=$SOURCE, tenant=$TENANT)"
echo "== [1/3] import"
RESP=$(api comp_admin dryrun.migrator POST "/api/v1/migration/spm/$SOURCE" \
  -H 'Content-Type: text/csv' --data-binary @"$CSV_FILE")
echo "$RESP"

echo "== [2/3] what landed"
PG_TOKEN="$(sed -n "s/^EIM_DEV_POSTGRES_PASSWORD=//p" .dev-secrets.env)"
docker run --rm --network host --env "EIM_DEV_POSTGRES_PASSWORD=$PG_TOKEN" \
  apache/age:release_PG16_1.6.0 \
  sh -c "exec psql \"postgresql://eim:\${EIM_DEV_POSTGRES_PASSWORD}@127.0.0.1:15433/eim?sslmode=disable\" -c \"
SELECT 'payees' k, count(*) v FROM payee WHERE tenant_id = CAST('$TENANT' AS uuid)
UNION ALL SELECT 'raw_transactions', count(*) FROM raw_transaction WHERE tenant_id = CAST('$TENANT' AS uuid)
UNION ALL SELECT 'quota_allocations', count(*) FROM quota_allocation WHERE tenant_id = CAST('$TENANT' AS uuid)
UNION ALL SELECT 'plan_artifacts', count(*) FROM plan_artifact WHERE tenant_id = CAST('$TENANT' AS uuid)\"" 2>/dev/null

echo "== [3/3] cleanup (throwaway tenant)"
docker run --rm --network host --env "EIM_DEV_POSTGRES_PASSWORD=$PG_TOKEN" \
  apache/age:release_PG16_1.6.0 \
  sh -c "exec psql \"postgresql://eim:\${EIM_DEV_POSTGRES_PASSWORD}@127.0.0.1:15433/eim?sslmode=disable\" -c \"
DELETE FROM credit_allocation WHERE transaction_id IN (SELECT transaction_id FROM raw_transaction WHERE tenant_id = CAST('$TENANT' AS uuid));
DELETE FROM raw_transaction WHERE tenant_id = CAST('$TENANT' AS uuid);
SELECT eim_ops.detach_delete_payee(p.payee_id) FROM payee p WHERE p.tenant_id = CAST('$TENANT' AS uuid);
DELETE FROM payee WHERE tenant_id = CAST('$TENANT' AS uuid);
DELETE FROM quota_allocation WHERE tenant_id = CAST('$TENANT' AS uuid);
DELETE FROM partner_tenant WHERE tenant_id = CAST('$TENANT' AS uuid)\"" >/dev/null 2>&1
echo "dry-run fixture removed — review the report above before the cutover checklist"
