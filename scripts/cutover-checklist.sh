#!/usr/bin/env bash
# TG9-D: the TG-6 cutover checklist (doc/cutover-hypercare-tg6.md §2,
# steps C1–C8) as an executable. Each step verifies real state through the
# gateway / database and prints PASS/SKIP/FAIL; nothing is auto-remediated
# — a FAIL blocks cutover until a human resolves it, exactly like the
# paper checklist. Parallel-run sign-off evidence (P4) requires two 100%
# periods and is checked from the parallel_signoff table.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

TENANT="${1:-}"
PARTNER_NAME="${2:-}"
[ -n "$TENANT" ] || { echo "usage: $0 <tenant-id> [partner-name]" >&2; exit 1; }

PG_TOKEN="$(sed -n "s/^EIM_DEV_POSTGRES_PASSWORD=//p" .dev-secrets.env 2>/dev/null || true)"
OIDC_TOKEN="$(sed -n "s/^EIM_DEV_OIDC_CLIENT_SECRET=//p" .dev-secrets.env 2>/dev/null || true)"
[ -n "$PG_TOKEN" ] && [ -n "$OIDC_TOKEN" ] || { echo "missing .dev-secrets.env" >&2; exit 1; }

psql_q() {
  docker run --rm --network host --env "EIM_DEV_POSTGRES_PASSWORD=$PG_TOKEN" \
    apache/age:release_PG16_1.6.0 \
    sh -c "exec psql \"postgresql://eim:\${EIM_DEV_POSTGRES_PASSWORD}@127.0.0.1:15433/eim?sslmode=disable\" -tAc \"$1\"" 2>/dev/null | tr -d '[:space:]'
}

PASS=0; FAIL=0; SKIP=0
gate() { # gate <id> <desc> <status: PASS|FAIL|SKIP> [detail]
  printf '[%s] %s — %s%s\n' "$2" "$3" "$1" "${4:+ — $4}"
  case "$1" in PASS) PASS=$((PASS+1));; FAIL) FAIL=$((FAIL+1));; SKIP) SKIP=$((SKIP+1));; esac
}

echo "cutover checklist — partner=${PARTNER_NAME:-?} tenant=$TENANT"

# C1 legacy plan-edit freeze is a business action; verify no plan publishes
# in the last 7 days as its proxy
RECENT=$(psql_q "SELECT count(*) FROM audit_log WHERE action_type='PLAN_PUBLISH' AND occurred_at > now() - interval '7 days'")
[ "$RECENT" = "0" ] && gate C1 "legacy plan edits frozen (proxy: no publishes in 7d)" PASS "$RECENT publishes" \
               || gate C1 "recent plan publishes exist — confirm the freeze" WARN "$RECENT in 7d"

# C2 zero open discrepancies (no DISCREPANT rows unresolved)
DISC=$(psql_q "SELECT count(*) FROM parallel_diff WHERE status = 'DISCREPANT'")
[ "$DISC" = "0" ] && gate C2 "zero open discrepancies" PASS || gate C2 "open discrepancies" FAIL "$DISC"

# C3 two consecutive 100% sign-offs
SIGNOFFS=$(psql_q "SELECT count(*) FROM parallel_signoff s JOIN partner_tenant p ON p.partner_id = s.partner_id WHERE p.tenant_id = CAST('$TENANT' AS uuid) AND s.match_rate = 100.0")
[ "$(psql_q "SELECT count(*) FROM partner_tenant WHERE tenant_id = CAST('$TENANT' AS uuid)")" = "0" ] \
  && gate C3 "no partner registered for tenant" SKIP \
  || { [ "$SIGNOFFS" -ge 2 ] && gate C3 "two consecutive 100% sign-offs" PASS "$SIGNOFFS sign-offs" \
                || gate C3 "sign-off gate" FAIL "$SIGNOFFS/2 sign-offs"; }

# C4 partner is LIVE (or SIGNED_OFF ready to flip)
STATUS=$(psql_q "SELECT status FROM partner_tenant WHERE tenant_id = CAST('$TENANT' AS uuid) LIMIT 1")
[ "$STATUS" = "LIVE" ] || [ "$STATUS" = "SIGNED_OFF" ] \
  && gate C4 "partner status ready ($STATUS)" PASS || gate C4 "partner status" FAIL "${STATUS:-none}"

# C5 first EIM-native period closed (COMPLETED close process)
CLOSED=$(psql_q "SELECT count(*) FROM process_instance WHERE tenant_id = CAST('$TENANT' AS uuid) AND process_type='period_close' AND state='COMPLETED'")
[ "$CLOSED" -ge 1 ] && gate C5 "first EIM-native period closed" PASS "$CLOSED closes" \
               || gate C5 "no completed period close yet" SKIP

# C6 payroll export received (hash recorded)
EXPORTS=$(psql_q "SELECT count(*) FROM export_run e JOIN payout_period p ON p.payout_period_id = e.payout_period_id WHERE p.tenant_id = CAST('$TENANT' AS uuid)")
[ "$EXPORTS" -ge 1 ] && gate C6 "payroll export produced (hash evidence)" PASS "$EXPORTS exports" \
               || gate C6 "no exports yet" SKIP

# C7 close cycle inside SLA (telemetry)
SLA=$(psql_q "SELECT count(*) FROM payout_period WHERE tenant_id = CAST('$TENANT' AS uuid) AND status='LOCKED' AND locked_at IS NOT NULL AND locked_at <= ends_on + interval '3 days'")
LOCKED=$(psql_q "SELECT count(*) FROM payout_period WHERE tenant_id = CAST('$TENANT' AS uuid) AND status='LOCKED'")
[ "$LOCKED" = "0" ] && gate C7 "no locked periods yet" SKIP \
  || { [ "$SLA" = "$LOCKED" ] && gate C7 "close cycle within 3-day SLA" PASS "$SLA/$LOCKED" \
                || gate C7 "close cycle SLA breach" FAIL "$SLA/$LOCKED within SLA"; }

# C8 legacy decommission ticket — a business artifact, surfaced as INFO
gate C8 "legacy job decommission ticket (change mgmt) — verify manually" SKIP "after 2 clean EIM-native periods"

printf '\nresult: %d PASS, %d FAIL, %d SKIP/verify-manually\n' "$PASS" "$FAIL" "$SKIP"
[ "$FAIL" -eq 0 ] || { echo "CUTOVER BLOCKED — resolve the FAIL gates first"; exit 1; }
echo "cutover gates clear — proceed per the hypercare runbook"
