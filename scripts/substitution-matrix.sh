#!/usr/bin/env bash
# TG0-INF-6 / NFR-81: values-only substitution matrix.
# Renders the umbrella chart under each infrastructure posture and asserts
# the expected resources appear/disappear. Portability must be values-only.
set -euo pipefail

UMBRELLA="platform/helm/eim-platform"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

render() { # render <values-file> <output-file>
  helm template eim "$UMBRELLA" -f "$UMBRELLA/ci/$1" > "$OUT/$2"
}

has() { # has <file> <pattern> <description>
  if grep -q "$2" "$OUT/$1"; then
    echo "  ok: $3"
  else
    echo "  FAIL: $3 (pattern '$2' not found in $1)"
    exit 1
  fi
}

lacks() { # lacks <file> <pattern> <description>
  if grep -q "$2" "$OUT/$1"; then
    echo "  FAIL: $3 (pattern '$2' unexpectedly found in $1)"
    exit 1
  else
    echo "  ok: $3"
  fi
}

echo "[matrix] posture: dev-embedded (everything in-cluster)"
render dev-embedded.yaml embedded.yaml
has embedded.yaml 'name: eim-postgres' 'embedded PostgreSQL+AGE present'
has embedded.yaml 'name: eim-kafka' 'embedded Kafka present'
has embedded.yaml 'name: eim-apisix' 'APISIX gateway present'
has embedded.yaml 'name: eim-keycloak' 'Keycloak present'
has embedded.yaml 'image: apache/age' 'AGE-enabled PostgreSQL image'
has embedded.yaml 'shared_preload_libraries=age' 'AGE preloaded'

echo "[matrix] posture: external PostgreSQL"
render external-db.yaml extdb.yaml
lacks extdb.yaml 'name: eim-postgres' 'embedded PostgreSQL removed'
has extdb.yaml 'corporate-pg.example.internal' 'apps point at external host'
has extdb.yaml 'name: eim-kafka' 'embedded Kafka still present'

echo "[matrix] posture: external Kafka"
render external-kafka.yaml extkafka.yaml
lacks extkafka.yaml 'name: eim-kafka' 'embedded Kafka removed'
has extkafka.yaml 'corporate-kafka.example.internal:9092' 'external bootstrap servers wired'

echo "[matrix] posture: fully external data plane"
render external-all.yaml extall.yaml
lacks extall.yaml 'name: eim-postgres' 'no embedded PostgreSQL'
lacks extall.yaml 'name: eim-kafka' 'no embedded Kafka'
lacks extall.yaml 'name: eim-objectstore' 'no embedded objectstore'
has extall.yaml 'name: eim-core-api' 'apps still deploy'
has extall.yaml 'name: eim-apisix' 'gateway still deploys'

echo "[matrix] required-secret guard: rendering without postgres password must FAIL"
if helm template eim "$UMBRELLA" -f "$UMBRELLA/ci/external-kafka.yaml" \
     --set eim-postgres.auth.password= > /dev/null 2>&1; then
  echo "  FAIL: chart rendered without required password"
  exit 1
fi
echo "  ok: required-password enforcement works (NFR-53)"

echo "[matrix] PASS — all postures render via values only (NFR-80/81)"
