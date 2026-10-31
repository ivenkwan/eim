#!/usr/bin/env bash
# eim-ctl — per-component lifecycle CLI for the dev cluster (start / stop /
# restart / scale / status / logs / port-forwards). Complements dev-up.sh
# (which boots the whole platform); this tool manages one component at a
# time. "stop" scales to 0 (kind-friendly: preserves PVCs and lets a
# dependency come back cleanly); "start" scales back to its desired count.
#
# Usage:
#   scripts/eim-ctl.sh list
#   scripts/eim-ctl.sh status [component]
#   scripts/eim-ctl.sh start|stop|restart|logs <component> [-f]
#   scripts/eim-ctl.sh scale <component> <replicas>
#   scripts/eim-ctl.sh forwards up|down
#   scripts/eim-ctl.sh health
set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

NS="${EIM_NAMESPACE:-eim}"

# component -> kind/name (kind: deploy | sts | svc-only)
COMPONENTS=(
  "core-api:deploy/eim-core-api"
  "connectors:deploy/eim-connectors"
  "process:deploy/eim-process"
  "calc-engine:deploy/eim-calc-engine"
  "plan-compiler:deploy/eim-plan-compiler"
  "apisix:deploy/eim-apisix"
  "web:deploy/eim-web"
  "keycloak:deploy/eim-keycloak"
  "objectstore:deploy/eim-objectstore"
  "postgres:sts/eim-postgres"
  "kafka:sts/eim-kafka"
)

resolve() { # resolve <name> -> kind/name or error
  local want="$1"
  for c in "${COMPONENTS[@]}"; do
    if [ "${c%%:*}" = "$want" ]; then
      echo "${c#*:}"
      return 0
    fi
  done
  echo "unknown component '$want' (known: $(list_names | tr '\n' ' '))" >&2
  return 1
}

list_names() { for c in "${COMPONENTS[@]}"; do echo "${c%%:*}"; done; }

kubectl_c() { kubectl -n "$NS" "$@"; }

cmd_status() {
  printf '%-14s %-8s %-8s %s\n' COMPONENT KIND READY AGE
  for c in "${COMPONENTS[@]}"; do
    local name="${c%%:*}" target="${c#*:}" kind="${c#*:}"; kind="${kind%%/*}"
    if kubectl_c get "$target" >/dev/null 2>&1; then
      local ready total age
      total=$(kubectl_c get "$target" -o jsonpath='{.status.replicas}' 2>/dev/null)
      ready=$(kubectl_c get "$target" -o jsonpath='{.status.readyReplicas}' 2>/dev/null)
      total="${total:-$(kubectl_c get "$target" -o jsonpath='{.spec.replicas}')}"
      ready="${ready:-0}"
      age=$(kubectl_c get "$target" -o jsonpath='{.metadata.creationTimestamp}' 2>/dev/null | cut -dT -f1)
      printf '%-14s %-8s %s/%s    %s\n' "$name" "$kind" "$ready" "$total" "$age"
    else
      printf '%-14s %-8s %s\n' "$name" "$kind" "MISSING"
    fi
  done
}

cmd_one_status() {
  local target; target=$(resolve "$1")
  kubectl_c get "$target" -o wide
  kubectl_c describe "$target" | sed -n '/Conditions:/,/Events:/p' | head -20
}

replicas_of() { kubectl_c get "$1" -o jsonpath='{.spec.replicas}'; }

cmd_scale() {
  local target; target=$(resolve "$1")
  local n="$2"
  case "$target" in
    deploy/*) kubectl_c scale "$target" --replicas="$n" ;;
    sts/*)    kubectl_c scale "$target" --replicas="$n" ;;
  esac
  echo "[eim-ctl] $1 scaled to $n"
}

cmd_start() {
  local target; target=$(resolve "$1")
  # only bump the replica count if it is 0 (stopped); otherwise idempotent
  if [ "$(replicas_of "$target")" = "0" ]; then
    cmd_scale "$1" 1
  else
    echo "[eim-ctl] $1 already running (replicas=$(replicas_of "$target"))"
  fi
  kubectl_c rollout status "$target" --timeout=300s
}

cmd_stop() {
  local target; target=$(resolve "$1")
  cmd_scale "$1" 0
  echo "[eim-ctl] $1 stopped (scaled to 0; PVCs/config preserved)"
}

cmd_restart() {
  local target; target=$(resolve "$1")
  local desired; desired="$(replicas_of "$target")"
  if [ "$desired" = "0" ]; then
    cmd_scale "$1" 1
    kubectl_c rollout status "$target" --timeout=300s
    echo "[eim-ctl] $1 restarted"
    return 0
  fi
  # Recreate-style restart (scale to 0, wait for termination, scale back):
  # singleton components with PVCs/locks (objectstore, keycloak) crashloop
  # under a rolling restart — old and new pods contend for the same
  # volume/lock. Found while exercising this tool against the dev cluster.
  cmd_scale "$1" 0
  for _ in $(seq 1 60); do
    kubectl_c get pods -l app="${target#*/}" --no-headers 2>/dev/null       | grep -qvE "Terminating|Running" || break
    sleep 2
  done
  kubectl_c delete pods -l app="${target#*/}" --ignore-not-found >/dev/null 2>&1 || true
  sleep 2
  cmd_scale "$1" "$desired"
  kubectl_c rollout status "$target" --timeout=300s
  echo "[eim-ctl] $1 restarted (recreate)"
}

cmd_logs() {
  local target; target=$(resolve "$1")
  shift || true
  kubectl_c logs "$target" --tail=100 "$@"
}

FORWARDS=(31234:deploy/eim-apisix:9080 31235:deploy/eim-keycloak:8080 15433:sts/eim-postgres:5432 19092:sts/eim-kafka:9092)

cmd_forwards() {
  case "${1:-}" in
    up)
      for f in "${FORWARDS[@]}"; do
        local port="${f%%:*}" rest="${f#*:}" target="${rest%%:*}" tport="${rest#*:}"
        if (echo >/dev/tcp/127.0.0.1/"$port") 2>/dev/null; then
          echo "[eim-ctl] forward :$port already up"
        else
          nohup kubectl -n "$NS" port-forward "$target" "$port:$tport" \
            > "/tmp/eim-ctl-pf-$port.log" 2>&1 &
          echo "[eim-ctl] forward :$port -> $target:$tport started"
        fi
      done
      sleep 2
      ;;
    down)
      for f in "${FORWARDS[@]}"; do
        local port="${f%%:*}"
        pkill -f "port-forward.*$port" 2>/dev/null || true
      done
      echo "[eim-ctl] port-forwards down"
      ;;
    *)
      echo "usage: $0 forwards up|down" >&2
      return 1
      ;;
  esac
}

cmd_health() {
  local fails=0
  for c in "${COMPONENTS[@]}"; do
    local target="${c#*:}"
    if kubectl_c rollout status "$target" --timeout=10s >/dev/null 2>&1; then
      echo "ok   ${c%%:*}"
    else
      echo "FAIL ${c%%:*}"
      fails=$((fails + 1))
    fi
  done
  [ "$fails" -eq 0 ] || return 1
}

case "${1:-help}" in
  list)    list_names ;;
  status)  [ "${2:-}" ] && cmd_one_status "$2" || cmd_status ;;
  start)   [ "${2:-}" ] || { echo "usage: $0 start <component>" >&2; exit 1; }
           cmd_start "$2" ;;
  stop)    [ "${2:-}" ] || { echo "usage: $0 stop <component>" >&2; exit 1; }
           cmd_stop "$2" ;;
  restart) [ "${2:-}" ] || { echo "usage: $0 restart <component>" >&2; exit 1; }
           cmd_restart "$2" ;;
  scale)   [ "${3:-}" ] || { echo "usage: $0 scale <component> <replicas>" >&2; exit 1; }
           cmd_scale "$2" "$3" ;;
  logs)    [ "${2:-}" ] || { echo "usage: $0 logs <component> [-f]" >&2; exit 1; }
           target=$(resolve "$2"); shift; cmd_logs "${target#*/}" "$@" ;;
  forwards) cmd_forwards "${2:-}" ;;
  health)  cmd_health ;;
  help|*)
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    ;;
esac
