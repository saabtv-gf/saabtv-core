# U19 — Release sign-off

U19 is the evidence gate for a release candidate, not a release/publish command. It
must not be called complete merely because the source compiles or the unit suite is
green.

## Run

From the repo root, run:

```bash
./scripts/release/run-u19-release-signoff.sh
```

The runner performs the full JVM suite, compiles Android instrumentation tests,
assembles the release and macrobenchmark variants, assembles both release APK ABIs
plus a signed 32-bit debug APK for local testing, and records an APK SHA-256 manifest plus command logs under
`app/build/reports/u19-signoff/<UTC-run-id>/`. If a device is connected it also runs
the connected Android TV regression suite. An exit code of zero means automated
commands did not fail; it is **not** release approval if the report contains
`PENDING` gates.

For the required physical-device performance matrix, connect one low-end Fire TV
and one modern Android TV, then run:

```bash
SAAB_U19_LOW_END_TV_SERIAL=<serial> \
SAAB_U19_MODERN_TV_SERIAL=<serial> \
./scripts/release/run-u19-release-signoff.sh
```

The script uses the U02 fixed fixture and input sequence. Each physical run records
device model/OS/resolution, source commit and dirty-tree state, benchmark APK hash,
per-frame timing, focus/input acknowledgement and settle samples, memory, and the
Perfetto/Macrobenchmark artifacts. Emulator output is smoke evidence only.

The debug test APK uses the `.test` application ID, so it installs alongside the
production package rather than upgrading it. The release APK requires the configured
release keystore to be installable over a production install.

## Acceptance gates

| Gate | Required evidence | Current source-tree assessment |
| --- | --- | --- |
| Reproducible candidate | Clean source commit, version, ABI APK hashes, verified signer | The current checkout is dirty; any build from it is a local candidate only. |
| Regression | Full JVM regression, instrumentation source compile, connected TV journeys | Runner automates these; results belong to each run report, not historical ledger totals. |
| Performance | Comparable U02 release traces on a low-end Fire TV and modern Android TV; no lost/misrouted remote inputs; frame-time and memory reviewed against an agreed budget | Open until both physical tiers are measured and reviewed. |
| Motion/parity | Current-source G01–G14 captures, exact remote scripts, and reviewer acceptance for J01–J14 | Open; the parity ledger currently leaves golden captures/evidence and journey acceptance open. |
| Renderer retirement | Prove all customer routes use the migrated production surfaces before deleting compatibility paths | No separate legacy UI renderer/switch was found in the source audit. Do not remove compatibility code without route-level proof. |
| Rollout/rollback | Signed artifact, release notes, staged rollout owner/monitoring, and a tested rollback artifact/procedure | Open until release owner records these items. The runner never publishes. |

Any failed automated gate blocks sign-off. Missing physical-device, parity, signing,
or rollout evidence leaves the report pending. A successful benchmark must retain
its raw traces and metadata; averages alone are insufficient to detect focus/input
regressions or long-tail frame stalls.
