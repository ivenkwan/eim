# eim-platform (umbrella chart)

Deploys the full EIM platform. Cloud-agnostic: CNCF-conformant Kubernetes only (NFR-80).

```bash
helm dependency build platform/helm/eim-platform
helm upgrade --install eim platform/helm/eim-platform \
  --namespace eim --create-namespace \
  --set eim-postgres.auth.password=... \
  --set eim-keycloak.adminUsername=... --set eim-keycloak.adminPassword=...
```

Substitution posture is values-only (NFR-81): embedded PostgreSQL/Kafka can be
swapped for external endpoints via `global.databaseHost` /
`global.kafkaBootstrapServers` plus `eim-postgres.enabled=false` /
`eim-kafka.enabled=false` — verified by `scripts/substitution-matrix.sh`.
