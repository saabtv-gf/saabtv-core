#!/usr/bin/env bash
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
run_id="$(date -u +%Y%m%dT%H%M%SZ)"
output_dir="${SAAB_U19_OUTPUT_DIR:-$repo_root/app/build/reports/u19-signoff/$run_id}"
mkdir -p "$output_dir/logs"
report="$output_dir/signoff.md"

commit="$(git -C "$repo_root" rev-parse HEAD)"
dirty="false"
[[ -z "$(git -C "$repo_root" status --porcelain)" ]] || dirty="true"
version="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"/\1/p' "$repo_root/app/build.gradle.kts" | head -1)"

unit_status="PENDING"
instrumentation_compile_status="PENDING"
instrumentation_status="PENDING"
benchmark_build_status="PENDING"
release_build_status="PENDING"
local_test_build_status="PENDING"
signature_status="PENDING"
low_end_status="PENDING"
modern_tv_status="PENDING"
static_status="PENDING"
unit_test_summary="not available"

run_step() {
  local key="$1" label="$2" log_file="$3"
  shift 3
  printf '\n== %s ==\n' "$label"
  if (cd "$repo_root" && "$@") >"$log_file" 2>&1; then
    printf -v "$key" '%s' "PASS"
  else
    local code=$?
    printf -v "$key" '%s' "FAIL (exit $code)"
  fi
  tail -n 24 "$log_file" 2>/dev/null || true
  printf '%s: %s\n' "$label" "${!key}"
}

write_report() {
  local overall="PENDING"
  if [[ "$unit_status" == FAIL* || "$instrumentation_compile_status" == FAIL* || \
        "$instrumentation_status" == FAIL* || "$benchmark_build_status" == FAIL* || \
        "$release_build_status" == FAIL* || "$local_test_build_status" == FAIL* || "$static_status" == FAIL* ]]; then
    overall="FAILED"
  elif [[ "$unit_status" == PASS && "$release_build_status" == PASS && \
          "$instrumentation_compile_status" == PASS && "$instrumentation_status" == PASS && \
          "$benchmark_build_status" == PASS && "$local_test_build_status" == PASS && \
          "$signature_status" == PASS && \
          "$low_end_status" == PASS && "$modern_tv_status" == PASS ]]; then
    overall="READY FOR HUMAN PARITY/ROLLOUT APPROVAL"
  fi

  cat >"$report" <<EOF
# U19 release sign-off — $run_id

- Overall: **$overall**
- App version: \`${version:-unknown}\`
- Source commit: \`$commit\`
- Worktree dirty: **$dirty** (a dirty source tree is not a reproducible release source)
- This runner builds and records evidence only; it does not publish or create a GitHub release.

## Automated gates

| Gate | Result | Evidence |
| --- | --- | --- |
| Static diff/fixture checks | $static_status | \`logs/static.log\` |
| Full JVM regression suite | $unit_status | \`logs/unit.log\` and Gradle XML reports |
| Android instrumentation source compile | $instrumentation_compile_status | \`logs/instrumentation-compile.log\` |
| Connected Android TV regression | $instrumentation_status | \`logs/instrumentation.log\` |
| Release/macrobenchmark variants | $benchmark_build_status | \`logs/benchmark-build.log\` |
| Release APK assembly | $release_build_status | \`logs/release-build.log\` |
| 32-bit signed local test APK | $local_test_build_status | \`logs/local-test-build.log\` |
| APK signature verification | $signature_status | APKs and SHA-256 listed below |

- JVM test counts: $unit_test_summary

## Device gates

| Device tier | Result | Evidence |
| --- | --- | --- |
| Low-end physical Fire TV | $low_end_status | U02 report directory, if run |
| Modern physical Android TV | $modern_tv_status | U02 report directory, if run |

Physical benchmark runs require both \`SAAB_U19_LOW_END_TV_SERIAL\` and
\`SAAB_U19_MODERN_TV_SERIAL\`. Emulator measurements are smoke tests only and do
not satisfy either performance gate.

## APKs

$(if [[ "$release_build_status" == PASS ]]; then
  find "$repo_root/app/build/outputs/apk/release" -maxdepth 1 -type f -name '*.apk' -print 2>/dev/null | while IFS= read -r apk; do
    printf -- '- \`%s\` — ' "${apk#"$repo_root"/}"
    shasum -a 256 "$apk" | awk '{ print "SHA-256 `" $1 "`" }'
  done
else
  printf '%s\n' 'No APK was built in this run.'
fi)
$(if [[ "$local_test_build_status" == PASS ]]; then
  apk="$repo_root/app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk"
  if [[ -f "$apk" ]]; then
    printf -- '- \`%s\` — ' "${apk#"$repo_root"/}"
    shasum -a 256 "$apk" | awk '{ print "SHA-256 `" $1 "`" }'
  fi
fi)

## Remaining human acceptance

- Motion and J01–J14 parity: **PENDING** until current-source golden journeys are captured and reviewed on TV hardware; historical test totals are not sign-off evidence.
- Old-renderer retirement: **PENDING** until parity evidence confirms no customer path depends on a compatibility implementation. No separate legacy UI renderer/switch was found in the current source audit; do not delete compatibility code based only on naming.
- Rollout/rollback: **PENDING** until a clean source commit, verified production signing, approved release notes, staged rollout and tested rollback artifact are recorded.

Exit status indicates automated command failure only. **PENDING is not release approval.**
EOF
}

if ! command -v java >/dev/null 2>&1 || ! java -version >/dev/null 2>&1; then
  static_status="PENDING"
  unit_status="BLOCKED (Java 17 runtime unavailable)"
  instrumentation_compile_status="$unit_status"
  benchmark_build_status="$unit_status"
  release_build_status="$unit_status"
  local_test_build_status="$unit_status"
  instrumentation_status="PENDING (no runnable device/toolchain)"
  signature_status="PENDING (no new APK)"
  low_end_status="PENDING (physical TV/toolchain unavailable)"
  modern_tv_status="$low_end_status"
  printf 'Java 17 runtime unavailable; Gradle tasks cannot run in this environment.\n' >"$output_dir/logs/prerequisites.log"
  write_report
  printf 'Java 17 is required. Sign-off report: %s\n' "$report" >&2
  exit 2
fi

run_step static_status "Static diff and U02 fixture checks" "$output_dir/logs/static.log" bash -c \
  'git diff --check && python3 -m json.tool app/src/benchmark/assets/u02-tv-fixture-v1.json >/dev/null && bash -n scripts/performance/run-u02-tv-baseline.sh scripts/release/run-u19-release-signoff.sh'
original_user_home="$(python3 -c 'import os; print(os.path.expanduser("~"))')"
gradle_user_home="${GRADLE_USER_HOME:-$original_user_home/.gradle}"
robolectric_home="$output_dir/robolectric-home"
mkdir -p "$robolectric_home"
robolectric_repo="${MAVEN_REPO_LOCAL:-$original_user_home/.m2/repository}"
robolectric_java_options="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Duser.home=$robolectric_home -Dmaven.repo.local=$robolectric_repo"
run_step unit_status "Full JVM regression suite" "$output_dir/logs/unit.log" \
  env GRADLE_USER_HOME="$gradle_user_home" JAVA_TOOL_OPTIONS="$robolectric_java_options" \
  ./gradlew --no-parallel -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --rerun-tasks
unit_test_summary="$(python3 - "$repo_root/app/build/test-results/testDebugUnitTest" <<'PY'
import glob
import sys
import xml.etree.ElementTree as ET

tests = failures = errors = skipped = 0
for path in glob.glob(sys.argv[1] + "/TEST-*.xml"):
    root = ET.parse(path).getroot()
    tests += int(root.attrib.get("tests", 0))
    failures += int(root.attrib.get("failures", 0))
    errors += int(root.attrib.get("errors", 0))
    skipped += int(root.attrib.get("skipped", 0))
passed = tests - failures - errors - skipped
print(f"{passed} passed / {tests} total; {failures} failures, {errors} errors, {skipped} skipped")
PY
)"
run_step instrumentation_compile_status "Android instrumentation source compilation" "$output_dir/logs/instrumentation-compile.log" \
  ./gradlew --no-parallel -Pkotlin.compiler.execution.strategy=in-process :app:compileDebugAndroidTestKotlin
run_step benchmark_build_status "Release and benchmark variant assembly" "$output_dir/logs/benchmark-build.log" \
  ./gradlew --no-parallel -Pkotlin.compiler.execution.strategy=in-process :app:assembleBenchmark :benchmark:assembleBenchmark
run_step release_build_status "Local release APK assembly" "$output_dir/logs/release-build.log" \
  ./gradlew --no-parallel -Pkotlin.compiler.execution.strategy=in-process :app:assembleRelease
run_step local_test_build_status "32-bit signed local test APK assembly" "$output_dir/logs/local-test-build.log" \
  ./gradlew --no-parallel -Pkotlin.compiler.execution.strategy=in-process :app:assembleDebug -Psaab32BitOnly=true

if [[ "$release_build_status" == PASS ]]; then
  apksigner="$(command -v apksigner || true)"
  apk32="$repo_root/app/build/outputs/apk/release/app-armeabi-v7a-release.apk"
  apk64="$repo_root/app/build/outputs/apk/release/app-arm64-v8a-release.apk"
  apk_debug32="$repo_root/app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk"
  if [[ -n "$apksigner" && -f "$apk32" && -f "$apk64" && -f "$apk_debug32" ]] && \
      "$apksigner" verify "$apk32" && "$apksigner" verify "$apk64" && "$apksigner" verify "$apk_debug32"; then
    signature_status="PASS"
  elif [[ ! -f "$apk32" || ! -f "$apk64" ]]; then
    signature_status="FAIL (expected both ABI APKs)"
  else
    signature_status="PENDING (apksigner unavailable or release key not configured)"
  fi
fi

if command -v adb >/dev/null 2>&1 && adb devices | awk 'NR > 1 && $2 == "device" { found=1 } END { exit !found }'; then
  run_step instrumentation_status "Connected Android TV regression suite" "$output_dir/logs/instrumentation.log" \
    ./gradlew --no-parallel -Pkotlin.compiler.execution.strategy=in-process :app:connectedDebugAndroidTest
else
  instrumentation_status="PENDING (no attached Android device)"
fi

if [[ "$unit_status" == PASS && "$release_build_status" == PASS && "$benchmark_build_status" == PASS ]]; then
  u02_runner="$repo_root/scripts/performance/run-u02-tv-baseline.sh"
  if [[ -n "${SAAB_U19_LOW_END_TV_SERIAL:-}" ]]; then
    if (cd "$repo_root" && SAAB_U02_DEVICE_SERIAL="$SAAB_U19_LOW_END_TV_SERIAL" \
        SAAB_U02_DEVICE_CLASS=low-end-fire-tv SAAB_U02_OUTPUT_DIR="$output_dir/low-end-fire-tv" \
        "$u02_runner") >"$output_dir/logs/low-end-fire-tv.log" 2>&1; then
      low_end_status="PASS"
    else
      low_end_status="FAIL (see logs/low-end-fire-tv.log)"
    fi
  fi
  if [[ -n "${SAAB_U19_MODERN_TV_SERIAL:-}" ]]; then
    if (cd "$repo_root" && SAAB_U02_DEVICE_SERIAL="$SAAB_U19_MODERN_TV_SERIAL" \
        SAAB_U02_DEVICE_CLASS=modern-android-tv SAAB_U02_OUTPUT_DIR="$output_dir/modern-android-tv" \
        "$u02_runner") >"$output_dir/logs/modern-android-tv.log" 2>&1; then
      modern_tv_status="PASS"
    else
      modern_tv_status="FAIL (see logs/modern-android-tv.log)"
    fi
  fi
fi

write_report
printf 'U19 sign-off report: %s\n' "$report"
[[ "$unit_status" != FAIL* && "$instrumentation_compile_status" != FAIL* && \
   "$instrumentation_status" != FAIL* && "$benchmark_build_status" != FAIL* && \
   "$release_build_status" != FAIL* && "$local_test_build_status" != FAIL* && "$static_status" != FAIL* ]]
