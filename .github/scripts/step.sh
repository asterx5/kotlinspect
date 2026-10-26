#!/usr/bin/env bash
# Runs one CI step, keeping its full output in build/logs/<name>.log. On failure it re-emits the
# lines that explain it as GitHub annotations, which (unlike logs) anyone can read.
# Usage: step.sh <name> <command...>
name="$1"
shift
mkdir -p build/logs
log="build/logs/$name.log"
set -o pipefail
"$@" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
if [ "$status" -ne 0 ]; then
  echo "::error title=$name failed::exit code $status"
  # GitHub shows at most 10 error annotations per step: assertion and compiler errors first, then
  # other failure lines, without Xcode's RegisterExecutionPolicyException noise.
  {
    grep -E "XCTAssert|XCTFail|: error:|^e: |^ld: |Undefined symbols|referenced from|framework '|library '" "$log"
    grep -E "error -|FAILED|What went wrong|Caused by|Exception" "$log"
  } | grep -v -e "RegisterExecutionPolicyException" -e "^warning" | awk '!seen[$0]++' | head -9 | while IFS= read -r line; do
    echo "::error title=$name::${line:0:900}"
  done
  tail -15 "$log" | while IFS= read -r line; do
    echo "::warning title=$name (tail)::${line:0:900}"
  done
fi
exit "$status"
