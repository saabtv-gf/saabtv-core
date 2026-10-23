#!/usr/bin/env bash
set -euo pipefail

release_tasks=".github/scripts/release-build.sh"
coverage_task="app/build.gradle.kts"

if grep -Eiq 'connected[A-Za-z]*AndroidTest|createDebugAndroidTestCoverageReport|(^|[[:space:]/])device-tests\.sh' "$release_tasks"; then
  echo "Release build script must not invoke device or emulator tests." >&2
  exit 1
fi

coverage_definition="$(sed -n '/tasks.register<JacocoReport>("unitRegressionCoverage")/,/^}/p' "$coverage_task")"
if grep -Eiq 'dependsOn.*(connected[A-Za-z]*AndroidTest|createDebugAndroidTestCoverageReport)' <<<"$coverage_definition"; then
  echo "unitRegressionCoverage must not depend on connected Android coverage tasks." >&2
  exit 1
fi

echo "Release verification is device-free."
