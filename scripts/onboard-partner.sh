#!/usr/bin/env bash
# TG9-D: onboarding automation for the FIRST real design partner. Drives
# the TG-6 + TG-8 surfaces end-to-end through the gateway so onboarding is
# a repeatable script, not tribal knowledge:
#   partner registry (IdP posture) → payees + hierarchy → plan publish →
#   connector ingest smoke → legacy backfill (migration importer) →
#   parallel-run first diff (reports, does NOT judge) → partner identity.
# Every step prints its evidence; the runbook doc stays the narrative.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

# ---- required args ----
PARTNER_NAME="${1:-}"
TENANT_ID="${2:-}"
IDP_POSTURE="${3:-GATEWAY_OIDC}"     # GATEWAY_OIDC | SAML_FEDERATED
IDP_ENTITY="${4:-}"
CERT_CN="${5:-}"                     # channel partners only (mTLS)
[ -n "$PARTNER_NAME" ] && [ -n "$TENANT_ID" ] || {
  echo "usage: $0 <partner-name> <tenant-id> [idp-posture] [idp-entity] [cert-cn]" >&2
  exit 1
}

PG_KEY=EIM_DEV_POSTGRES_PASSWORD
OIDC_KEY=EIM_DEV_OIDC_CLIENT_SECRET
PG_TOKEN="$(sed -n "s/^${PG_KEY}=//p" .dev-secrets.env 2>/dev/null || true)"
OIDC_TOKEN="$(sed -n "s/^${OIDC_KEY}=//p" .dev-secrets.env 2>/dev/null || true)"
[ -n "$OIDC_TOKEN" ] || { echo "missing .dev-secrets.env (${OIDC_KEY})" >&2; exit 1; }

TOKEN=$(curl -s -X POST "http://localhost:31235/realms/eim/protocol/openid-connect/token" \
  -H "Host: eim-keycloak:8080" \
  -d "grant_type=client_credentials&client_id=eim-gateway&client_secret=$OIDC_TOKEN" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "no gateway token" >&2; exit 1; }

api() {
  local role="$1" actor="$2" method="$3" path="$4"; shift 4
  curl -s -m 120 -X "$method" "http://localhost:31234$path" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-Actor: $actor" -H "X-Roles: $role" "$@"
}
jq_get() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -1; }

echo "== [1/6] partner registry (IdP posture: $IDP_POSTURE)"
IDP_FIELD=""
[ -n "$IDP_ENTITY" ] && IDP_FIELD=",\"idpEntityId\":\"$IDP_ENTITY\""
P=$(api comp_admin onboard.admin POST /api/v1/partners \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"$PARTNER_NAME\",\"idpPosture\":\"$IDP_POSTURE\"$IDP_FIELD}")
PARTNER_ID=$(echo "$P" | jq_get partnerId)
echo "   partnerId=$PARTNER_ID"

if [ -n "$CERT_CN" ]; then
  echo "== [1b] channel partner identity (cert CN: $CERT_CN)"
  api comp_admin onboard.admin POST /api/v1/channel/partners \
    -H 'Content-Type: application/json' \
    -d "{\"orgName\":\"$PARTNER_NAME\",\"certCn\":\"$CERT_CN\"}" | head -c 200; echo
fi

echo "== [2/6] payees + hierarchy (supply: payees.csv with PAYEE rows or skip)"
[ -f payees.csv ] && api comp_admin onboard.admin POST /api/v1/migration/spm/manual \
  -H 'Content-Type: text/csv' --data-binary @payees.csv | head -c 200 && echo || echo "   (no payees.csv — skipping)"

echo "== [3/6] plan publish (supply: plan.json, or skip to reuse an existing hash)"
PLAN_HASH="${PLAN_HASH:-}"
if [ -z "$PLAN_HASH" ] && [ -f plan.json ]; then
  PLAN_HASH=$(api comp_admin onboard.admin POST /api/v1/plans/publish \
    -H 'Content-Type: application/json' --data-binary @plan.json | jq_get planVersionHash)
fi
echo "   planVersionHash=$PLAN_HASH"

echo "== [4/6] connector ingest smoke (one CSV deal through the gateway)"
api comp_admin onboard.admin POST /api/v1/ingestion/csv \
  -H 'Content-Type: application/json' \
  -d "{\"objectName\":\"$PARTNER_NAME-smoke\",\"csv\":\"deal_id,close_date,amount,currency,payee_email\nONBOARD-1,2026-10-15,50000,USD,onboard@$PARTNER_NAME.example.test\"}" \
  | head -c 200; echo

echo "== [5/6] legacy backfill (supply: legacy.csv; optional)"
[ -f legacy.csv ] && api comp_admin onboard.admin POST /api/v1/migration/spm/varicent \
  -H 'Content-Type: text/csv' --data-binary @legacy.csv | head -c 300 && echo || echo "   (no legacy.csv — skipping)"

echo "== [6/6] parallel-run first diff (report only — sign-off is a human step)"
if [ -n "$PLAN_HASH" ] && [ -n "$PARTNER_ID" ]; then
  PERIOD="${PERIOD_KEY:-$(date +%Y-%m)}"
  api comp_admin onboard.admin POST "/api/v1/calc/runs" \
    -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"$TENANT_ID\",\"payoutPeriodId\":\"$PERIOD\",\"planVersionHash\":\"$PLAN_HASH\"}" | head -c 200; echo
  echo "   next: upload the legacy statement, generate the diff, and drive the"
  echo "   TG-6 sign-off flow — scripts/tg6-partner-drill.sh documents the full walk"
else
  echo "   (no plan hash — run the calc step manually once the plan publishes)"
fi
echo "onboarding automation complete for $PARTNER_NAME (tenant $TENANT_ID)"
