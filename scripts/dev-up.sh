#!/usr/bin/env bash
# TG0-INF-3: boot the FULL EIM platform on a local kind cluster from the one
# umbrella chart (cloud-agnostic packaging, NFR-80). Exit criteria evidence
# for tollgate TG-0.
#
# Usage:
#   scripts/dev-up.sh                 # build images + create cluster + deploy + smoke
#   scripts/dev-up.sh --skip-builds   # reuse existing images / cluster
#   scripts/dev-up.sh --destroy       # delete the cluster
#   scripts/dev-up.sh --dry-run       # print the plan, execute nothing
#
# Generated dev credentials are written to .dev-secrets.env (gitignored) and
# printed once. No credentials are ever committed (NFR-53).
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER="eim-dev"
NAMESPACE="eim"
KIND_NODE_IMAGE="kindest/node:v1.37.0"
SECRETS_FILE=".dev-secrets.env"
UMBRELLA="platform/helm/eim-platform"

DRY_RUN=0
SKIP_BUILDS=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --skip-builds) SKIP_BUILDS=1 ;;
    --destroy)
      kind delete cluster --name "$CLUSTER" && echo "[dev-up] cluster '$CLUSTER' deleted"
      exit 0 ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

run() { # run <cmd...>  — respects --dry-run
  echo "[dev-up] $*"
  if [ "$DRY_RUN" -eq 0 ]; then "$@"; fi
}

# ---------------------------------------------------------------- credentials
if [ "$DRY_RUN" -eq 1 ]; then
  echo "[dev-up] (dry-run) would generate credentials into $SECRETS_FILE"
else
  PG_PASS="$(openssl rand -hex 16)"
  KC_USER="admin"
  KC_PASS="$(openssl rand -hex 16)"
  OIDC_SECRET="$(openssl rand -hex 24)"
  BACKUP_KEY="$(openssl rand -hex 32)"
  {
    echo "EIM_DEV_POSTGRES_PASSWORD=$PG_PASS"
    echo "EIM_DEV_KEYCLOAK_ADMIN_USERNAME=$KC_USER"
    echo "EIM_DEV_KEYCLOAK_ADMIN_PASSWORD=$KC_PASS"
    echo "EIM_DEV_OIDC_CLIENT_SECRET=$OIDC_SECRET"
    echo "EIM_DEV_BACKUP_ENCRYPTION_KEY=$BACKUP_KEY"
  } > "$SECRETS_FILE"
  chmod 600 "$SECRETS_FILE"
  echo "[dev-up] credentials written to $SECRETS_FILE (gitignored)"
fi

# ---------------------------------------------------------------- build images
if [ "$SKIP_BUILDS" -eq 0 ] && [ "$DRY_RUN" -eq 0 ]; then
  if ! docker image ls --format '{{.Repository}}:{{.Tag}}' | grep -q '^eim-core-api:0.1.0-dev$'; then
    run bash scripts/build-apps.sh
  fi
fi

# ---------------------------------------------------------------- cluster
if ! kind get clusters 2>/dev/null | grep -q "^$CLUSTER$"; then
  run kind create cluster --name "$CLUSTER" --image "$KIND_NODE_IMAGE"
else
  echo "[dev-up] reusing existing cluster '$CLUSTER'"
fi
run kubectl config use-context "kind-$CLUSTER"

# ---------------------------------------------------------------- load images
if [ "$DRY_RUN" -eq 0 ]; then
  for image in eim-web eim-core-api eim-connectors eim-calc-engine eim-process eim-plan-compiler; do
    if ! docker exec "eim-dev-control-plane" ctr -n k8s.io images ls "name==$image:0.1.0-dev" -q | grep -q .; then
      run kind load docker-image "$image:0.1.0-dev" --name "$CLUSTER"
    fi
  done
fi

# ---------------------------------------------------------------- deploy
run helm dependency build "$UMBRELLA"
if [ "$DRY_RUN" -eq 0 ]; then
  helm upgrade --install eim "$UMBRELLA" \
    --namespace "$NAMESPACE" --create-namespace \
    --set "global.databasePassword=$PG_PASS" \
    --set "eim-postgres.auth.password=$PG_PASS" \
    --set "eim-keycloak.adminUsername=$KC_USER" \
    --set "eim-keycloak.adminPassword=$KC_PASS" \
    --set "eim-keycloak.oidcClientSecret=$OIDC_SECRET" \
    --set "eim-apisix.oidcClientSecret=$OIDC_SECRET" \
    --set "eim-core-api.oidcClientSecret=$OIDC_SECRET" \
    --set "eim-core-api.authMode=header" \
    --set "eim-postgres.backup.encryptionKey=$BACKUP_KEY" \
    --wait --timeout 10m
fi

# ---------------------------------------------------------------- smoke
smoke() { # smoke <label> <cmd...>
  if [ "$DRY_RUN" -eq 1 ]; then echo "[dev-up] (dry-run) smoke: $1"; return; fi
  if "$@" >/dev/null 2>&1; then
    echo "[dev-up] smoke ok: $1"
  else
    echo "[dev-up] smoke FAILED: $1" >&2
    exit 1
  fi
}

if [ "$DRY_RUN" -eq 0 ]; then
  echo "[dev-up] waiting for rollouts..."
  kubectl -n "$NAMESPACE" rollout status deploy/eim-core-api --timeout=300s
  kubectl -n "$NAMESPACE" rollout status deploy/eim-apisix --timeout=120s
  kubectl -n "$NAMESPACE" rollout status statefulset/eim-postgres --timeout=300s

  smoke "postgres+AGE extension" kubectl -n "$NAMESPACE" exec eim-postgres-0 -- psql -U eim -d eim -tAc "SELECT extname FROM pg_extension WHERE extname='age'"
  smoke "AGE graph eim_graph exists" kubectl -n "$NAMESPACE" exec eim-postgres-0 -- psql -U eim -d eim -tAc "SELECT name FROM ag_catalog.ag_graph WHERE name='eim_graph'"
  smoke "kafka broker responsive" kubectl -n "$NAMESPACE" exec eim-kafka-0 -- /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092
  smoke "objectstore (S3) serving" kubectl -n "$NAMESPACE" rollout status deploy/eim-objectstore --timeout=120s
  smoke "core-api health/ready" kubectl -n "$NAMESPACE" exec deploy/eim-core-api -- wget -qO- http://localhost:8080/q/health/ready
fi

echo "[dev-up] done. Try: kubectl -n $NAMESPACE port-forward svc/eim-apisix 9080:9080 && curl localhost:9080/api/v1/hello"
