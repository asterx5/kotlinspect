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
  # The most useful lines: compiler and test errors, then the tail of the log.
  grep -E "^e: |error:|error -|FAILED|Failing tests|XCTAssert|failed \(|Exception|What went wrong|Caused by|\*\* .* FAILED \*\*" "$log" \
    | grep -v "^warning" | head -40 | while IFS= read -r line; do
      echo "::error title=$name::${line:0:900}"
    done
  tail -15 "$log" | while IFS= read -r line; do
    echo "::warning title=$name (tail)::${line:0:900}"
  done
fi
exit "$status"
