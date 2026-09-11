#!/usr/bin/env bash
# Runs each fixture project through Maven and then spin, reporting results.
#
# Prerequisites:
#   ./mvnw clean install
#   ./install-dev.sh
#
# Usage:
#   ./test-fixtures.sh [fixture-filter]
#
# Examples:
#   ./test-fixtures.sh                     # run all fixtures
#   ./test-fixtures.sh feature-preview     # run one fixture by name
#
# Post-build verification:
#   A fixture may drop an executable `verify.sh` at its root. When present, it runs after
#   each successful build (Maven, then spin) with cwd set to the fixture dir and BUILD_TOOL
#   set to "mvn" or "spin", and must exit non-zero to fail. Use it for anything the build's
#   own exit code can't confirm — e.g. that a provided-scope dependency's classes are absent
#   from the packaged jar. A failure after the Maven phase counts as FIXTURE-BROKEN (the
#   fixture's own assumption is wrong); a failure after the spin phase counts as SPIN-FAIL.
set -uo pipefail

trap 'echo; echo "Interrupted."; kill 0; exit 130' INT

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FIXTURES_DIR="$SCRIPT_DIR/fixtures/maven"
FILTER="${1:-}"

LOG_DIR="$SCRIPT_DIR/.fixture-logs"
mkdir -p "$LOG_DIR"

pass=0
spin_fail=0
fixture_broken=0
skipped=0

RESULTS=()
NAMES=()
MVN_TIMES=()
SPIN_TIMES=()

elapsed_s() {
    local start="$1" end="$2"
    awk "BEGIN{printf \"%.3f\", ($end - $start) / 1000000000}"
}

run_verify() {
    local dir="$1"
    local tool="$2"
    local verify_log="$3"

    local verify_script="$dir/verify.sh"
    if [ ! -x "$verify_script" ]; then
        return 0
    fi
    (cd "$dir" && BUILD_TOOL="$tool" ./verify.sh) >"$verify_log" 2>&1
}

run_fixture() {
    local name="$1"
    local dir="$2"
    local mvn_log="$LOG_DIR/${name}.mvn.log"
    local spin_log="$LOG_DIR/${name}.spin.log"
    local verify_log="$LOG_DIR/${name}.verify.log"
    local t0 t1

    echo -n "  $name ... "

    # Maven ground-truth check
    t0=$(date +%s%N)
    (cd "$dir" && ./mvnw -B clean install) >"$mvn_log" 2>&1
    local mvn_exit=$?
    t1=$(date +%s%N)
    local mvn_time
    mvn_time=$(elapsed_s "$t0" "$t1")

    if (( mvn_exit != 0 )); then
        echo "FIXTURE-BROKEN"
        echo "    mvn log: $mvn_log"
        tail -10 "$mvn_log" >&2
        NAMES+=("$name")
        RESULTS+=("FIXTURE-BROKEN")
        MVN_TIMES+=("$mvn_time")
        SPIN_TIMES+=("-")
        (( fixture_broken++ )) || true
        return
    fi

    if ! run_verify "$dir" mvn "$verify_log"; then
        echo "FIXTURE-BROKEN"
        echo "    verify log (mvn): $verify_log"
        tail -10 "$verify_log" >&2
        NAMES+=("$name")
        RESULTS+=("FIXTURE-BROKEN")
        MVN_TIMES+=("$mvn_time")
        SPIN_TIMES+=("-")
        (( fixture_broken++ )) || true
        return
    fi

    # spin check
    t0=$(date +%s%N)
    spin -w "$dir" clean build >"$spin_log" 2>&1
    local spin_exit=$?
    t1=$(date +%s%N)
    local spin_time
    spin_time=$(elapsed_s "$t0" "$t1")

    if (( spin_exit != 0 )); then
        echo "SPIN-FAIL"
        echo "    spin log: $spin_log"
        tail -10 "$spin_log" >&2
        NAMES+=("$name")
        RESULTS+=("SPIN-FAIL")
        MVN_TIMES+=("$mvn_time")
        SPIN_TIMES+=("$spin_time")
        (( spin_fail++ )) || true
        return
    fi

    if ! run_verify "$dir" spin "$verify_log"; then
        echo "SPIN-FAIL"
        echo "    verify log (spin): $verify_log"
        tail -10 "$verify_log" >&2
        NAMES+=("$name")
        RESULTS+=("SPIN-FAIL")
        MVN_TIMES+=("$mvn_time")
        SPIN_TIMES+=("$spin_time")
        (( spin_fail++ )) || true
        return
    fi

    echo "ok"
    NAMES+=("$name")
    RESULTS+=("OK")
    MVN_TIMES+=("$mvn_time")
    SPIN_TIMES+=("$spin_time")
    (( pass++ )) || true
}

echo
echo "spin fixture tests  $(date '+%Y-%m-%d %H:%M')"
echo "──────────────────────────────────────────────"

if [ ! -d "$FIXTURES_DIR" ]; then
    echo "No fixtures found at $FIXTURES_DIR"
    exit 1
fi

for fixture_dir in "$FIXTURES_DIR"/*/; do
    name="$(basename "$fixture_dir")"
    if [ -n "$FILTER" ] && [[ "$name" != *"$FILTER"* ]]; then
        (( skipped++ )) || true
        continue
    fi
    if [ ! -f "$fixture_dir/pom.xml" ]; then
        continue
    fi
    run_fixture "$name" "$fixture_dir"
done

echo "──────────────────────────────────────────────"
echo

# Compute max name width for alignment
max_name=0
for n in "${NAMES[@]+"${NAMES[@]}"}"; do
    (( ${#n} > max_name )) && max_name=${#n}
done

# Results with timing
for i in "${!RESULTS[@]}"; do
    status="${RESULTS[$i]}"
    name="${NAMES[$i]}"
    mvn_t="${MVN_TIMES[$i]}"
    spin_t="${SPIN_TIMES[$i]}"

    case "$status" in
        OK)             label="OK             " ;;
        SPIN-FAIL)      label="SPIN-FAIL      " ;;
        FIXTURE-BROKEN) label="FIXTURE-BROKEN " ;;
    esac

    if [ "$spin_t" = "-" ]; then
        timing="mvn ${mvn_t}s"
    else
        timing="mvn ${mvn_t}s  spin ${spin_t}s"
    fi

    printf "  %s  %-*s  %s\n" "$label" "$max_name" "$name" "$timing"
done

echo

# Aggregate totals (only fixtures that ran both)
total_mvn=0
total_spin=0
both_count=0
for i in "${!RESULTS[@]}"; do
    total_mvn=$(awk "BEGIN{printf \"%.3f\", $total_mvn + ${MVN_TIMES[$i]}}")
    if [ "${SPIN_TIMES[$i]}" != "-" ]; then
        total_spin=$(awk "BEGIN{printf \"%.3f\", $total_spin + ${SPIN_TIMES[$i]}}")
        (( both_count++ )) || true
    fi
done

total=$(( pass + spin_fail + fixture_broken ))
echo "  $total run — $pass ok, $spin_fail spin-fail, $fixture_broken fixture-broken, $skipped skipped"
if (( ${#NAMES[@]} > 0 )); then
    echo "  Total time — mvn ${total_mvn}s  spin ${total_spin}s  (${both_count} fixtures ran both)"
fi
echo

if (( spin_fail > 0 || fixture_broken > 0 )); then
    exit 1
fi
