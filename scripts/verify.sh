#!/usr/bin/env bash
#
# Local verification, one Gradle invocation per stage.
#
# Why not one invocation: on Windows, `:mobile:lintDebug` in the same invocation as the assemble
# tasks has stalled twice after lint wrote its SARIF report — no further output, no file writes,
# the client waiting on a daemon that has no work left. A single `./gradlew a b c d` then looks
# exactly like a slow build while it is in fact wedged, and the whole run has to be thrown away.
# Running each stage separately makes that visible per stage and bounded by a timeout, and keeps a
# finished stage's result instead of losing it with the wedge.
#
# Usage:
#   scripts/verify.sh          # everything below
#   scripts/verify.sh --quick  # unit tests and the debug APK only
#
# Environment:
#   JAVA_HOME       must point at JDK 25 (see gradle/gradle-daemon-jvm.properties)
#   STAGE_TIMEOUT   seconds per stage, default 900
#
set -u

cd "$(dirname "$0")/.." || exit 1

STAGE_TIMEOUT="${STAGE_TIMEOUT:-900}"
LOGS="build/verify"
mkdir -p "$LOGS"

QUICK=0
if [ "${1:-}" = "--quick" ]; then QUICK=1; fi

if command -v timeout >/dev/null 2>&1; then
    TIMEOUT_BIN=timeout
else
    echo "warning: no 'timeout' binary; a stalled stage will not be detected" >&2
    TIMEOUT_BIN=
fi

GRADLE="./gradlew --console=plain"

# Runs one stage, streams nothing, and reports either ok, a real failure, or a timeout.
# A timeout is reported differently from a failure on purpose: they need different reactions.
stage() {
    local name="$1"
    shift
    local log="$LOGS/$name.log"
    local started=$SECONDS
    printf '=== %-16s ' "$name"
    if [ -n "$TIMEOUT_BIN" ]; then
        $TIMEOUT_BIN "$STAGE_TIMEOUT" "$@" >"$log" 2>&1
    else
        "$@" >"$log" 2>&1
    fi
    local code=$?
    local elapsed=$((SECONDS - started))
    if [ "$code" -eq 0 ]; then
        echo "ok        ${elapsed}s  $log"
        return 0
    fi
    if [ "$code" -eq 124 ]; then
        echo "STALLED   ${elapsed}s"
        echo "  no result within ${STAGE_TIMEOUT}s. This is the Wedged-daemon case, not a compile error."
        echo "  stopping the Gradle daemon so the next stage starts clean"
        $GRADLE --stop >/dev/null 2>&1 || true
        echo "  log: $log"
        exit 1
    fi
    echo "FAILED    ${elapsed}s  exit=$code"
    echo "--- last lines of $log ---"
    tail -n 25 "$log" | sed 's/^/  | /'
    if [ -f scripts/build_stage_report.py ]; then
        python3 scripts/build_stage_report.py "$log" 2>/dev/null | sed 's/^/  /' || true
    fi
    exit 1
}

# The engine sources are fetched, not committed; a verify run starts by proving the pins match.
stage hichain-sources python3 scripts/fetch-hichain-sources.py --verify
stage public-tree    python3 scripts/check_public_tree.py
stage unit-tests     $GRADLE :common:testDebugUnitTest
stage debug-apk      $GRADLE :mobile:assembleDebug

if [ "$QUICK" -eq 0 ]; then
    # Separate invocations, deliberately: see the header.
    stage lint           $GRADLE :mobile:lintDebug
    stage release-apk    $GRADLE :mobile:assembleRelease
fi

echo
echo "all stages passed"
