#!/usr/bin/env bash
set -euo pipefail

mkdir -p app/build/ci-diagnostics
bash .github/scripts/verify-release-no-device-tests.sh
./gradlew --no-daemon \
  :app:unitRegressionCoverage \
  :app:verifyCorePolicyRegressionCoverage \
  :app:compileDebugAndroidTestKotlin \
  :app:lintRelease \
  :app:assembleRelease \
  -Psaab32BitOnly=true \
  -Dorg.gradle.jvmargs=-Xmx4g
