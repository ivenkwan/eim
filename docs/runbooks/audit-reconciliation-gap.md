# Runbook: EimAuditReconciliationGap (NFR-64)

**Alert**: `eim_audit_reconcile_gap != 0` for >2m — audited mutations exceed
published event-audit rows.

1. `kubectl -n eim exec deploy/eim-core-api -- wget -qO- 'http://localhost:8080/api/v1/admin/audit/reconcile?hours=2'`
   — note `audited_mutations`, `outbox_rows`, `published_events`, `gap`.
2. If `outbox_rows > published_events`: the exporter is stalled.
   Check `kubectl -n eim logs deploy/eim-core-api | grep 'outbox exported'`.
   Most common cause: Kafka unreachable — `kubectl -n eim get sts eim-kafka`.
3. If Kafka is down: restart it (`kubectl -n eim rollout restart sts/eim-kafka`);
   the exporter re-delivers unpublished rows (idempotent consumers).
4. If `outbox_rows == published_events < audited_mutations`: an audit write
   path bypassed the outbox — this is a code regression (AD-9). Capture the
   `/api/v1/admin/audit/reconcile` output and open an incident; do NOT
   "fix" by deleting audit rows (append-only, FR-601.3).
5. Verify seals still hold: `GET /api/v1/admin/audit/seals/verify` → all ok=true.
