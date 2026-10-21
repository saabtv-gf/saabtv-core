# U02 — TV performance baseline

U02 is the repeatable release-mode measurement harness for browsing/focus motion. It must be run on physical TVs; emulator results are smoke-only and cannot establish the reference baseline.

## Fixed fixture

`app/src/benchmark/assets/u02-tv-fixture-v1.json` pins the fixture identifier, four mixed movie/series shelves, 24 cards per shelf, poster shape and one local Android resource artwork URI. The activity that renders it is under `app/src/benchmark`, so it is included only in the non-debuggable `benchmark` build type and cannot appear in release APKs. It renders the production `SaabTvTheme`, `FocusShelf`, and `SaabTvCard` components without account data, TMDB/add-on calls, random ordering, or remote artwork.

The single local art resource intentionally makes all runs network-independent and gives the card image loader a stable cache key. It does not represent network latency, unique-image decode pressure, recommendations, actual account data or playback decoding; those require their own fixed fixtures and benchmarks.

## Measurement protocol

Run one device at a time. Capture a baseline on a low-end Fire TV and a modern Android TV, then run the exact same commit/fixture/script after a change. Use the same power/thermal state and allow the device to cool between captures. The scripted sequence contains 28 rapid non-edge D-pad moves with horizontal reversals and vertical shelf travel; the harness asserts every expected card receives focus.

```sh
SAAB_U02_DEVICE_SERIAL='<adb-serial>' \
SAAB_U02_DEVICE_CLASS=low-end-fire-tv \
  ./scripts/performance/run-u02-tv-baseline.sh

SAAB_U02_DEVICE_SERIAL='<adb-serial>' \
SAAB_U02_DEVICE_CLASS=modern-android-tv \
  ./scripts/performance/run-u02-tv-baseline.sh
```

The script records device/model/OS/resolution/density, source commit and benchmark APK SHA-256 in `metadata.txt`. It runs `:benchmark:connectedBenchmarkAndroidTest`, exports the U02 per-key focus/acknowledgement JSON records from logcat into `remote-input-report.log`, saves a post-run memory snapshot, and copies fresh Macrobenchmark JSON plus Perfetto trace artifacts from the benchmark output tree. Results are written under `benchmark/build/u02-reports/` by default; set `SAAB_U02_OUTPUT_DIR` to choose another location.

Macrobenchmark reports frame duration/overrun distributions and peak Java heap/RSS submetrics. U02 JSON reports per-input time until the expected focus identity is accessible, time until UI idle, p50/p95 for each, no-movement inputs and multi-step inputs. Treat input-to-idle as an interaction-settle proxy, not hardware-remote latency. `Perfetto` traces are the source for frame deadlines, UI-thread work, recomposition/layout, image decode and system contention analysis. Keep fixture ID, apk hash, commit, device identity and the unedited raw traces together for every comparison.

## Interpretation and acceptance

- Do not compare emulator numbers with TV numbers or combine the two reference TV classes into one score.
- A run is invalid if fixture ID, commit, APK hash, content shape, cache state or resolution differs from its paired run.
- Any no-movement, multi-step or wrong-focus event fails the scripted journey even if average frame time improves.
- Performance changes are accepted only after reviewing per-frame overrun and input p50/p95 alongside memory and trace evidence; a mean FPS alone is insufficient.
- Numerical UI latency/frame/memory targets remain unset until multiple clean reference captures establish device-specific baselines.
- No baseline artifacts are committed by this source change: no build/device run was requested or performed.
