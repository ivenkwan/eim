# Release Pipeline — RC staging gate (TG9-B)

Status: **declared**. The staging posture (`platform/helm/eim-platform/ci/staging.yaml`)
is the release-candidate gate the [GA deployment posture](ga-deployment-tg6.md)
already references: full OIDC (no dev header trust), OTel on, replicas ×2,
snapshot offload enabled. A deploy to production is gated on the same suite
passing here first.

## Pipeline

```
merge → ci.yml (types, coverage floors, golden suites, contract tests,
        audit-mapping gate, chart lint/substitution, RLS role tests)

nightly → nightly.yml (kind cluster boots the umbrella chart; the latency
        SLO drill + audit assurance pack + api_gap=0 reconciliation run
        against it; teardown after)

quarterly → quarterly-dr.yml (kind cluster; the full chaos + DR drill
        incl. the AGE-catalog restore to a scratch DB, integrity parity,
        WORM proofs; manual dispatch carries the audit-evidence reason)

RC → staging cluster (below) → full verify harness → production
```

## The staging gate

1. **Render check** — `helm lint` + `helm template` against the staging
   posture (already in `ci.yml`'s helm job):
   ```bash
   helm lint platform/helm/eim-platform -f platform/helm/eim-platform/ci/staging.yaml
   helm template platform/helm/eim-platform -f platform/helm/eim-platform/ci/staging.yaml \
     --set global.databasePassword=... --set eim-postgres.auth.password=... \
     --set eim-keycloak.adminUsername=... --set eim-keycloak.adminPassword=... \
     --set eim-keycloak.oidcClientSecret=... --set eim-apisix.oidcClientSecret=... \
     --set eim-core-api.oidcClientSecret=... > /dev/null
   ```
   NOTE: `eim-core-api.authMode` is **omitted** in staging — the default is
   `oidc`, which is the point of the posture. Setting it to `header` here
   would defeat the gate.

2. **Deploy** — a second kind cluster (or any staging CNCF cluster) with
   the staging values plus secrets:
   ```bash
   kind create cluster --name eim-staging
   bash scripts/build-apps.sh          # or pull the CI-built images
   kind load docker-image eim-core-api:... --name eim-staging   # per service
   helm upgrade --install eim platform/helm/eim-platform \
     -n eim --create-namespace \
     -f platform/helm/eim-platform/ci/staging.yaml \
     --set global.databasePassword="$PG" --set eim-postgres.auth.password="$PG" \
     --set eim-keycloak.adminUsername="$KCU" --set eim-keycloak.adminPassword="$KCP" \
     --set eim-keycloak.oidcClientSecret="$OIDC" \
     --set eim-apisix.oidcClientSecret="$OIDC" \
     --set eim-core-api.oidcClientSecret="$OIDC" \
     --wait --timeout 10m
   ```

3. **Verify** — the full interactive suite against staging (the same
   harnesses, pointed at the staging port-forwards):
   `verify-tg6.sh`, `verify-tg7.sh`, `verify-tg8.sh` plus the SOC 2
   evidence gate. In staging the OIDC posture is the real one — the dev
   header-trust shortcuts are absent by design, so passing here exercises
   the same auth path production uses.

4. **Production** — only after staging passes: same helm release shape,
   prod values, then the rollout-verification steps in the GA posture doc.

## Honest boundary

The dev kind cluster (single node, shared with interactive drills) is the
**development** environment. The staging posture file, the CI tiers, and
this pipeline make the gate reproducible; actually standing up the
long-lived staging cluster is an ops action (cluster provisioning +
secrets) that this repo prepares but does not perform.
