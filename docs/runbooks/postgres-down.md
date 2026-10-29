# Runbook: EimPostgresDown

1. `kubectl -n eim get pod eim-postgres-0` — describe + logs.
2. Dev cluster: single StatefulSet pod; PVC `data-eim-postgres-0` holds data.
   Do NOT delete the PVC. `kubectl -n eim rollout restart sts eim-postgres`.
3. Restore path (encrypted backups): fetch latest `eim-snapshots/pg-*.sql.gz.enc`
   from the objectstore, decrypt with the backup key from the secret store,
   gunzip, psql into a fresh instance (NFR-42 drill covers the AGE catalog).
4. Production posture: CloudNativePG failover handles pod loss; this alert
   then indicates a cluster-level event.
