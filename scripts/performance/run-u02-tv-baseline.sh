#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
serial="${SAAB_U02_DEVICE_SERIAL:?Set SAAB_U02_DEVICE_SERIAL to the target TV adb serial}"
device_class="${SAAB_U02_DEVICE_CLASS:?Set SAAB_U02_DEVICE_CLASS to low-end-fire-tv or modern-android-tv}"
case "$device_class" in
  low-end-fire-tv|modern-android-tv) ;;
  *) echo "Unsupported U02 device class: $device_class" >&2; exit 2 ;;
esac

command -v adb >/dev/null || { echo "adb is required" >&2; exit 2; }
command -v java >/dev/null || { echo "Java is required" >&2; exit 2; }
adb -s "$serial" wait-for-device
state="$(adb -s "$serial" get-state)"
[[ "$state" == "device" ]] || { echo "Device is not online: $serial" >&2; exit 2; }
emulator="$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')"
[[ "$emulator" != "1" ]] || { echo "U02 reference runs must use physical TVs" >&2; exit 2; }

export ANDROID_SERIAL="$serial"
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$device_class"
output_dir="${SAAB_U02_OUTPUT_DIR:-$repo_root/benchmark/build/u02-reports/$run_id}"
mkdir -p "$output_dir"
marker="$(mktemp)"
trap 'rm -f "$marker"' EXIT
touch "$marker"

{
  printf 'run_id=%s\n' "$run_id"
  printf 'fixture_id=u02-tv-browse-v1\n'
  printf 'device_class=%s\n' "$device_class"
  printf 'device_serial=%s\n' "$serial"
  printf 'model=%s\n' "$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"
  printf 'manufacturer=%s\n' "$(adb -s "$serial" shell getprop ro.product.manufacturer | tr -d '\r')"
  printf 'android_release=%s\n' "$(adb -s "$serial" shell getprop ro.build.version.release | tr -d '\r')"
  printf 'api_level=%s\n' "$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
  printf 'fingerprint=%s\n' "$(adb -s "$serial" shell getprop ro.build.fingerprint | tr -d '\r')"
  printf 'resolution=%s\n' "$(adb -s "$serial" shell wm size | tr -d '\r')"
  printf 'density=%s\n' "$(adb -s "$serial" shell wm density | tr -d '\r')"
  printf 'commit=%s\n' "$(git -C "$repo_root" rev-parse HEAD)"
  printf 'worktree_dirty=%s\n' "$(if [[ -n "$(git -C "$repo_root" status --porcelain)" ]]; then echo true; else echo false; fi)"
} > "$output_dir/metadata.txt"

adb -s "$serial" logcat -c
(cd "$repo_root" && ./gradlew -Pkotlin.compiler.execution.strategy=in-process :benchmark:connectedBenchmarkAndroidTest)

apk_abi="$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
apk_path="$(find "$repo_root/app/build/outputs/apk/benchmark" -type f -name "*${apk_abi}*.apk" -print -quit 2>/dev/null || true)"
if [[ -z "$apk_path" ]]; then
  apk_path="$(find "$repo_root/app/build/outputs/apk/benchmark" -type f -name '*.apk' -print -quit 2>/dev/null || true)"
fi
if [[ -n "$apk_path" ]]; then
  printf 'benchmark_apk=%s\n' "${apk_path#"$repo_root"/}" >> "$output_dir/metadata.txt"
  printf 'benchmark_apk_sha256=%s\n' "$(shasum -a 256 "$apk_path" | awk '{print $1}')" >> "$output_dir/metadata.txt"
else
  echo "Could not locate the benchmark APK for post-run identification" >&2
  exit 1
fi

adb -s "$serial" logcat -d -s U02_BASELINE:I U02_INPUT_SAMPLE:I > "$output_dir/remote-input-report.log"
adb -s "$serial" shell dumpsys meminfo com.saab.tv > "$output_dir/post-run-meminfo.txt"

artifact_root="$repo_root/benchmark/build/outputs"
if [[ -d "$artifact_root" ]]; then
  while IFS= read -r -d '' artifact; do
    relative="${artifact#"$artifact_root"/}"
    destination="$output_dir/macrobenchmark/$relative"
    mkdir -p "$(dirname "$destination")"
    cp "$artifact" "$destination"
  done < <(find "$artifact_root" -type f \
    \( -name '*.trace' -o -name '*.perfetto-trace' -o -name '*benchmarkData*.json' \) \
    -newer "$marker" -print0)
fi

printf 'U02 report: %s\n' "$output_dir"
