#!/usr/bin/env bash
# Build all service container images locally (TG0-INF-3 / TG0-INF-7).
# Java services: jars must exist (mvn -f apps/pom.xml package); images are
# runtime-only and reuse the local jar to avoid a second Maven pass in Docker.
# Web: pnpm --filter @eim/web-app build must have produced packages/web-app/dist.
set -euo pipefail
cd "$(dirname "$0")/.."

TAG="${TAG:-0.1.0-dev}"

for svc in eim-core-api eim-connectors eim-calc-engine eim-process eim-plan-compiler; do
  if [ ! -d "apps/$svc/target/quarkus-app" ]; then
    echo "[build-apps] missing apps/$svc/target/quarkus-app — run: mvn -f apps/pom.xml -DskipTests package" >&2
    exit 1
  fi
  echo "[build-apps] docker build $svc:$TAG"
  docker build -q -t "$svc:$TAG" "apps/$svc" >/dev/null
done

if [ ! -f packages/web-app/dist/index.html ]; then
  echo "[build-apps] missing packages/web-app/dist — run: pnpm --filter @eim/web-app build" >&2
  exit 1
fi
echo "[build-apps] docker build eim-web:$TAG"
docker build -q -t "eim-web:$TAG" packages/web-app >/dev/null

echo "[build-apps] built 6 images tagged :$TAG"
docker image ls --format '{{.Repository}}:{{.Tag}}' | grep ":$TAG" | sort
