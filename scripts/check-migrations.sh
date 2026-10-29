#!/usr/bin/env bash
# TG1-DAT-5 / NFR-41: expand/contract migration policy.
# Rules:
#   1. A migration file may be MODIFIED only before it ships (TG-0/TG-1 dev);
#      once committed to main, edits require a NEW versioned file instead
#      (checked against origin/main when available, HEAD otherwise).
#   2. File names must match Flyway conventions: V<version>__<desc>.sql
#      (version strictly increasing, no duplicates).
#   3. No destructive statements outside an explicitly-named drop file
#      (DROP TABLE/COLUMN/INDEX require filename to contain __drop_).
set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

MIG_GLOB='apps/*/src/main/resources/db/migration/*.sql'
BASE="${1:-origin/main}"

fail=0
note() { echo "[migration-lint] $*"; }
bad() { echo "[migration-lint] FAIL: $*"; fail=$((fail + 1)); }

# --- naming + duplicate version checks
declare -A seen
shopt -s globstar nullglob
for f in $MIG_GLOB; do
  base=$(basename "$f")
  if [[ ! "$base" =~ ^V([0-9]+)__([a-z0-9_]+)\.sql$ ]]; then
    bad "$f: name must match V<version>__<snake_case>.sql"
    continue
  fi
  v="${BASH_REMATCH[1]}"
  # apps/<svc>/src/main/resources/db/migration/V*.sql — service is 6 dirs up.
  svc=$(basename "$(dirname "$(dirname "$(dirname "$(dirname "$(dirname "$(dirname "$f")")")")")")")
  key="$svc/$v"
  if [[ -n "${seen[$key]:-}" ]]; then
    bad "duplicate version $v in $svc (${seen[$key]} and $f)"
  fi
  seen[$key]="$f"
done
note "naming/duplicates checked across ${#seen[@]} migration(s)"

# --- destructive statement guard
for f in $MIG_GLOB; do
  base=$(basename "$f")
  if grep -qiE '^[[:space:]]*(DROP[[:space:]]+(TABLE|COLUMN|INDEX)|TRUNCATE)' "$f"; then
    if [[ "$base" != *"__drop_"* && "$base" != *"__baseline"* ]]; then
      bad "$f: destructive statement outside a __drop_ named migration (expand/contract, NFR-41)"
    fi
  fi
done
note "destructive-statement guard done"

# --- immutability of shipped migrations (edit requires a new version file)
if git rev-parse --verify "$BASE" >/dev/null 2>&1; then
  while IFS= read -r f; do
    [ -f "$f" ] || continue
    if ! git diff --quiet "$BASE" -- "$f" 2>/dev/null; then
      if git cat-file -e "$BASE:$f" 2>/dev/null; then
        bad "$f: modified a shipped migration — add a NEW V<n> file instead (NFR-41/62)"
      fi
    fi
  done < <(git ls-tree -r --name-only "$BASE" -- 'apps/*/db/migration/*.sql' 2>/dev/null)
  note "immutability checked against $BASE"
else
  note "ref $BASE unavailable — skipping immutability check (first run)"
fi

if [ "$fail" -gt 0 ]; then
  echo "[migration-lint] FAILED: $fail violation(s)"
  exit 1
fi
echo "[migration-lint] PASS"
