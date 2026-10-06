#!/usr/bin/env bash
set -euo pipefail

mkdir -p app/build/ci-diagnostics
adb logcat -c || true

if ./gradlew --no-daemon \
  :app:unitRegressionCoverage \
  :app:verifyCorePolicyRegressionCoverage \
  :app:lintRelease \
  -PsaabEmulatorTest=true \
  -Dorg.gradle.jvmargs=-Xmx4g; then
  ./gradlew --no-daemon \
    :app:assembleRelease \
    -Psaab32BitOnly=true \
    -Dorg.gradle.jvmargs=-Xmx4g
else
  test_status=$?
  adb logcat -d -b all -v threadtime > app/build/ci-diagnostics/emulator-logcat.txt 2>&1 || true
  adb shell dumpsys meminfo > app/build/ci-diagnostics/emulator-meminfo.txt 2>&1 || true
  exit "$test_status"
fi
