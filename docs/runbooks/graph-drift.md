# Runbook: EimGraphDrift (NFR-93)

**Alert**: `eim_graph_drift > 0` for >5m — AGE projection out of sync with
the relational source of truth.

1. List the drift lines: `GET /api/v1/admin/audit/graph/reconcile`.
2. `payee missing in graph` → re-project by touching the row:
   `UPDATE payee SET full_name = full_name WHERE payee_id = '...'`
   (the trigger re-projects in-transaction).
3. `manages_of edge missing` → same touch on the child payee.
4. `orphan graph vertex` → the relational row was deleted out-of-band.
   Confirm in audit_log (DATA_OVERRIDE with that payee). If legitimate,
   remove the vertex: `SELECT eim_ops.detach_delete_payee('<uuid>')`.
5. Root-cause before clearing: drift recurring means a writer bypassed the
   service path — check audit_log for actor anomalies.
