#!/usr/bin/env bash
# Rebuilds spin and runs a full clean build across configured sibling projects.
#
# Usage:
#   ./test-all.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Projects to build after spin is installed. Paths are relative to this script
# or absolute. Edit this list to add/remove projects.
PROJECTS=(
    ../base.build
    ../codemodel.build
    ../spawn.build
)

LOG_DIR="$SCRIPT_DIR/.test-all-logs"
mkdir -p "$LOG_DIR"

pass() { echo "  [ok] $*"; }
fail() { echo "  [FAIL] $*"; echo "        log: $1"; exit 1; }

run() {
    local label="$1"; shift
    local log
    log="$LOG_DIR/$(echo "$label" | tr ' /' '_').log"
    echo -n "  $label ... "
    if "$@" >"$log" 2>&1; then
        echo "ok"
    else
        echo "FAILED"
        echo "  log: $log"
        tail -30 "$log" >&2
        exit 1
    fi
}

echo
echo "spin full build test  $(date '+%Y-%m-%d %H:%M')"
echo "──────────────────────────────────────────────"

run "mvnw clean install"  bash -c "cd '$SCRIPT_DIR' && ./mvnw clean install"
run "install.sh --local"  bash -c "cd '$SCRIPT_DIR' && ./install.sh --local"

for project in "${PROJECTS[@]}"; do
    [[ "$project" = /* ]] || project="$SCRIPT_DIR/$project"
    if [ ! -d "$project" ]; then
        echo "  skipping $project (not found)"
        continue
    fi
    run "spin $(basename "$project") clean build"  spin -w "$project" clean build
done

echo "──────────────────────────────────────────────"
echo "all passed  $(date '+%H:%M')"
echo
