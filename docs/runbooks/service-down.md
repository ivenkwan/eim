# Runbook: EimCoreApiDown

1. `kubectl -n eim get pods -l app=eim-core-api` — CrashLoop? `kubectl logs --previous`.
2. Common causes: DB unreachable (check eim-postgres), Flyway validation
   failure (a shipped migration was edited — see scripts/check-migrations.sh),
   Keycloak unreachable at startup (OIDC tenant init retries).
3. Locked-period trigger firing at boot means a calculation run targeted a
   LOCKED period — check payout_period.status before re-running.
