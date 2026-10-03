# Regression coverage: measured, not assumed

## Current measured coverage (2026-10-03)

The complete suite reports **685 tests: 683 passed, 2 existing external-provider
smoke tests skipped, zero failures/errors**. The whole-app report includes UI,
services, repositories and generated application code; the test denominator has not
been narrowed.

| Metric | Covered / total | Coverage |
| --- | --- | --- |
| Lines | 17,266 / 39,412 | 43.81% |
| Branches | 10,871 / 37,545 | 28.95% |
| Methods | 3,357 / 7,801 | 43.03% |
| Classes | 861 / 2,012 | 42.79% |
| Instructions | 173,142 / 433,926 | 39.90% |

The unchanged 90% whole-app target needs **18,205 additional covered lines**. Recent
tests exercise encrypted phone-pairing, OTT orchestration, thumbnail worker command
gating, TV keyboard and account-form interactions, episode selection, profile PIN
focus/confirmation, quick actions, crash-row activation, lifecycle progress snapshots,
post-stop sync suppression, interrupted-update cleanup, fake-playback controls and
hub/category editor journeys. UI testing also exposed and fixed focus-requester wiring
that could crash hub editing. No production accounts, cloud rows, or external services
were contacted.

**Not every P0/P1 scenario is complete.** Remaining scenarios are retained in the
group-by-group ledger, separated into testable gaps and device/live-service work, in
[P0_P1_REGRESSION_STATUS.md](/Users/sanju/Documents/Codex/2026-08-24/bu/work/Lumerio/docs/P0_P1_REGRESSION_STATUS.md).
The detailed source-range inventory below remains the earlier baseline, not a freshly
regenerated per-file inventory for this batch.

## Previous baseline (0.1.95-beta)

492 unit tests reported (51 additional tests in this functional/UI expansion); 490 passed; zero failures/errors;
two external-provider smoke tests skipped. The lifecycle-defect regression is now enabled and passing.
Across all compiled application packages (including UI, services, repositories and generated
DI/database classes), JaCoCo reports:

| Metric | Covered / total | Coverage |
| --- | --- | --- |
| Lines | 7,342 / 39,394 | 18.64% |
| Branches | 3,863 / 37,579 | 10.28% |
| Methods | 1,553 / 7,794 | 19.93% |
| Classes | 371 / 2,011 | 18.45% |
| Instructions | 59,062 / 433,825 | 13.61% |

Only generated R/BuildConfig identifiers are omitted. No application feature package
is excluded to inflate these figures. No runtime coverage libraries are added to the release APK.

Separately, matching report source files to actual handwritten Kotlin package declarations
gives **4,842 / 35,989 lines (13.45%)**, up from 10.02%. This avoids counting generated
Room/DI and compiler-inlined external sources as functional test progress. Neither measure
has reached the requested 90% target. Global coverage uses the report-level XML counter,
not summed class CSV counters, which double-count shared source lines.

## Functional/UI-focused expansion

51 tests were added across eight suites, with one shared offline fixture. All 51 now pass.
The initially ignored lifecycle-defect regression was enabled after the user authorized the fix:
`selectSubtitleTrack` now ignores late calls after `release`, like other player controls.

- Search ViewModel: debounce cancellation, immediate removal of stale selectable results,
  type separation/deduplication, short queries, session reset and keyboard editing.
- Dashboard ViewModel: mixed catalog/hub ordering, normalized moves, tab visibility,
  out-of-bounds moves, rename, layout clamps and hub item artwork/removal.
- Theme ViewModel: seeding, profile isolation, custom theme persistence, active updates,
  color derivation, deletion and built-in protection.
- Profile orchestration: runtime snapshot switching, profile activation, settings copy while
  preserving identity/theme, trailer preferences, pending setup, splash flags and deletion.
- Real ExoPlayer adapter: speed/subtitle bounds, ARGB preservation, manual delay persistence,
  pre-load transport controls and most late controls after disposal. No codec/network decode
  is claimed by these tests.
- Updater service: release response parsing, version decisions, URL/checksum requirements,
  HTTP/malformed-response failures, invalid-download rejection before transfer and cancellation.
- Compose interactions: option values and recomposition, D-pad enter/up/left, no duplicate
  action on key release, language segments, filter chips, onboarding back/continue and
  playback render-surface modifier forwarding.
- Full production Playback Settings screen connected to its real ViewModel and Room:
  section collapse/expand, seek interval updates to both stored columns and toggling settings.

Selected source-file line coverage (not independent feature-completeness claims):

| Source | Coverage |
| --- | ---: |
| ThemeManager | 97.44% |
| SearchViewModel | 84.62% |
| DashboardViewModel | 67.07% |
| ProfileConfigurationManager | 65.28% |
| AppUpdateManager | 43.08% |
| SettingsSubScreens | 29.96% |
| ExoPlayerBackend | 8.66% |

Fixed defect: `ExoPlayerBackend.selectSubtitleTrack` previously changed
`subtitleSelectionWasManual` after `release`. The added `released` guard prevents it.
`subtitleSelectionAfterReleaseMustNotMutateDisposedPlayerState` is enabled and asserts
unchanged disposed state after Off, embedded and external selections. No live accounts
or external data were modified.

22 core policy groups (25 classes including nested subtitle comparators) have 100%
line, branch, method, class and instruction coverage. CI enforces each class individually;
it also checks that required policy classes still exist, so deleting or renaming one cannot
silently pass an empty coverage rule.

Covered groups: watched progress, progress snapshots, Continue Watching target selection,
playback retry/fallback state, buffer and jitter recovery, adaptive buffering, autoplay,
subtitle preference/selection, thumbnail timing/work/memory/carousel, title metadata priority,
hover-trailer permission, watchlist focus, list-index safety, episode action labels,
profile PINs/onboarding preferences and diagnostic recording.

These claims are for the named policy groups, not PlayerScreen, PlayerViewModel or
end-to-end functionality. The full report still includes all other application packages.
The only subsequent production change for this request is the subtitle-release guard above.

## Latest expansion and 90% target

The requested **90% whole-application line coverage has not been achieved**.
It requires at least 35,471 covered lines: 18,205 more than the current report.
The figures above come from the report-level XML counters, not a sum of CSV class
rows (classes sharing a source file can count the same line more than once).

The complete scenario checklist and remaining 178-file coverage inventory are in
[TEST_BACKLOG_90_PERCENT.md](/Users/sanju/Documents/Codex/2026-08-24/bu/work/Lumerio/docs/TEST_BACKLOG_90_PERCENT.md).

Added Robolectric 4.17 and Compose interaction testing as test-only dependencies;
they do not enter the release APK. Tests run with an isolated base Application,
API 28 resources and native SQLite, without initializing the production Hilt graph
or contacting live providers/databases. Native SQLite is required on this Apple
Silicon host; the old sqlite4java/legacy engine cannot load on this architecture.

New test suites cover:

- Offline Retrofit/Stremio HTTP contracts: manifests, ratings, metadata, canonical
  episode numbering, trailer fields, embedded subtitles, malformed JSON and HTTP failures.
- IntroDB cache identity, cache hits, empty responses, provider failures and retries.
- Stream resolution/format/language/size/seeder parsing and provider priority.
- Recommendation ordering and subtitle release matching.
- Account partitions, compression bounds, duplicate identities and missing components.
- Real Room profile/history/watchlist/hub/addon/catalog/theme/next-episode persistence.
  Includes zero-position saves, large timestamp precision, profile isolation, latest
  episode ordering and newer-only remote history merges.
- Account snapshot round trips, all preference types, cross-account/schema rejection,
  unapproved asset paths, portable avatar paths and cache exclusion.
- Subtitle repository manifest capabilities, request encoding, URL/language normalization,
  unsafe-scheme rejection, deduplication and release ranking.
- Addon repository installation/configuration, discovery, dashboard filtering, category
  limits, canonical Cinemeta series resolution and movie metadata fallback.
- Device-local display preferences and account/profile preference isolation.
- Real Compose button clicks, disabled actions, D-pad center/left/up events, detail
  action expansion, setup headers and toggle state changes.
- Settings ViewModel persistence for appearance, playback, seek intervals, language
  priority, shared countdown, subtitle styles, source filters and local hardware overrides.

The expanded suite, existing core-policy 100% gate and release lint all pass.
These tests are meaningful progress, not a substitute for testing the remaining screens
and player lifecycle paths.

## Commands

```sh
./gradlew :app:unitRegressionCoverage :app:verifyCorePolicyRegressionCoverage
./gradlew :app:verify90PercentUnitLineCoverage
./gradlew :app:verifyFullUnitRegressionCoverage
```

The core-policy command passes. The 90% command uses the same whole-app denominator;
it intentionally FAILS until the target is achieved. It is not enabled as a release
gate yet, because that would prevent releases without adding coverage.
The full-coverage command requires 100% lines, branches, methods, classes and instructions across
the measured application. It intentionally FAILS today. It must not be reported as passing
or enabled as a release requirement until the missing tests exist.

HTML/XML/CSV: `app/build/reports/jacoco/unitRegressionCoverage/`.

## Remaining regression work

Largest uncovered source files in the latest XML report:

| File | Uncovered lines |
| --- | ---: |
| BasePlayerScaffold.kt | 3,240 |
| MainActivity.kt | 1,924 |
| ExoPlayerBackend.kt | 1,519 |
| SettingsSubScreens.kt | 1,087 |
| DashboardEditorScreen.kt | 1,309 |
| ProfileScreen.kt | 1,144 |
| IntegrationsScreen.kt | 1,066 |
| HomeScreen.kt | 1,043 |
| DetailsScreen.kt | 989 |

Next test expansion must exercise these real components with controlled dependencies,
including remote focus/lifecycle and media-backend behavior. Calling only constructors,
excluding these packages or lowering the denominator would not fulfill the target.

1. Persistence integration tests: Room writes, ordering, profile isolation, concurrent boundary saves,
   write failures, process recreation, cloud upload/download and cross-device resume conflicts.
2. Player integration tests with a controllable backend: pause before preparation; pause at 0–5 seconds;
   natural end; final episode; manual/automatic episode switch; source replacement; buffering/errors;
   verify the ID, season, episode, timestamp, watched flag and cloud-sync event together.
3. Compose/device tests: the actual remote focus tree, menu entry, every tab, modal focus restoration,
   inline/fullscreen trailer handoff, subtitles, updates, onboarding and login.
4. Feature service tests: deterministic HTTP fixtures for Cinemeta, TorBox, trailers, IntroDB,
   subtitles and accounts, including malformed responses, timeouts, cancellation and retry paths.
5. Native/device qualification: libmpv/ALASS, codec compatibility, thumbnail workers, memory pressure,
   long playback and installation on both Fire TV and the 4K TV.

100% JVM coverage is not 100% functionality coverage. Android lifecycle/focus, native code,
network provider behavior and hardware decoders require integration/device tests. A passing
unit suite alone must not be presented as proof those journeys work.

## Manual acceptance for the progress fix

- Pause a selected episode at 0 seconds, 1 second and 4 seconds; exit and resume it.
- Resume an existing episode, then exit while its new source is still preparing: preserve the old timestamp.
- Change episode before five seconds: persist the outgoing episode before resolving the next one.
- Allow the final episode to finish: persist completion and do not offer resume for that finished episode.
- Repeat on another signed-in device after sync; confirm profile, episode and timestamp.
- Recheck menu entry/tab movement against the requested 0.1.89 navigation baseline.
