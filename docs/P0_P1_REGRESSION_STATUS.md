# P0/P1 regression implementation status

This is an implementation ledger, not a claim that every P0/P1 scenario is complete.
The numbered groups correspond to `TEST_BACKLOG_90_PERCENT.md`. All new tests use
offline providers, isolated preferences, in-memory Room or real Compose key dispatch.
No production accounts, API keys or cloud rows are used.

| Group | Implemented regression coverage | Still required for complete group coverage |
| --- | --- | --- |
| P0.1 Progress/resume | Real PlayerViewModel: prepared/unprepared boundary saves, 0/1/4999/5000ms, unknown duration, future timestamps, 300-second/95% watched boundary, sticky completion, profile isolation, concurrent writes, cancellation-resistant boundary save, trailer exclusion, cache/watchlist cleanup | PlayerScreen lifecycle ON_STOP/disposal, urgent notification observation, multi-device player handoff |
| P0.2 Series | Real DetailsViewModel: explicit episode 10 source request, noncontiguous sidebar numbering, next episode after watched, latest episode at zero position, indexed playback IDs, series completion | DetailsScreen remote selection, malformed exact targets, full remembered source/subtitle transition across devices |
| P0.3 Navigation | Real NavDrawer D-pad entry, repeated Up/Down, destination activation, hidden menu, automatic focus rejection; existing focus-policy tests | MainActivity recreation/back stack, all main tabs and TopNav, deleted target and loading races |
| P0.4 Trailers | Real inline controls: Start Watching, Fullscreen callback, Back, watchlist state, mute, always-visible controls; ownership isolation; warmup single-flight, expiry, eviction, failure retry and caller cancellation | Actual decoder handoff/position, fullscreen surface, hover lifecycle, expired URL retries and hardware codec playback |
| P0.5 Cloud | Real AccountCloudStore with intercepted HTTP: unchanged/no-transfer, sparse component upload, newer local/cloud, default-device restore, failed upload retry, remote deletion and tampering; existing snapshot/partition/device tests | AccountSyncManager event timing/concurrency, in-flight logout, simultaneous-device server CAS, deployed RLS isolation |
| P0.6 Sources/subtitles | Real source/track stores: profile isolation, changed source, season pack file index, Off persistence, delay updates and clearing; real Details sorting/picker/autoplay; existing subtitle repository/backend/policy tests | PlayerScreen manual source replacement, ALASS cancellation when Off, full language-filter focus and timed recovery |
| P0.7 Updater | Real updater HTTP parsing/errors, untrusted URL/hash rejection, no-transfer permission denial/repeated resume/cancel; existing verifier/policy tests | Permission grant resume into valid APK, interrupted cleanup, installer/FileProvider failure, storage and operation mutex journeys |
| P0.8 Thumbnails | Real cache: complete first-five-minute grids at 10/20/30s, duplicates, profile/content cleanup, exact position lookup, color/dimensions; existing scheduling/carousel/memory policies | Worker IPC death, native frame extraction/HDR correctness, disabled-worker lifecycle, confirm-only seek UI and source switches |
| P1.9 Home/OTT | Real HomeViewModel toggle/clear scoped history, embedded trailer shortcut, no-profile prefetch, empty tab reset; existing addon/JustWatch/browse tests | OTT repository response/cache/partial failure, pagination cancellation, all quick-action screens and background transition races |
| P1.10 Search | Existing real SearchViewModel tests: debounce cancellation, query reset, result types/dedup, session and keyboard edits | SearchScreen keyboard/result D-pad layout, delayed provider responses/errors, QR routing and bottom focus |
| P1.11 Watchlist | Real ViewModel subscribed flows across profiles/types, null profile, idempotent remove, focus reset, poster lookup suppression/single-flight, deletion during resolution | WatchlistScreen remote popup/focus restore, failed provider retry, profile change during poster lookup |
| P1.12 Profiles | Real wizard: steps/cancel, unique language ordering with Malayalam/Kannada, persisted onboarding, identity edits, PIN validation/lockout/removal and deletion; existing runtime-copy/device tests | ProfileScreen keypad focus/theme, persistence failure and process recreation, account/device onboarding UI |
| P1.13 Settings | Existing real Settings/Playback/Dashboard/Theme ViewModels and PlaybackSettingsScreen tests; real diagnostics toggle/control clicks and remote route | Every remaining settings section UI, account tab entry, modal focus, final-item scrolling, write-failure presentation |
| P1.14 Account/phone | Real auth: input rejection, availability normalization/token reuse, JWT/bearer routing, cross-account session rejection, 401 vs transient failure, offline logout, failed login; AES-GCM Unicode/tampering/AAD/size/IV tests | AccountScreen activation/password focus, availability response races, complete relay expiration/replay/upload, Pages DOM mode routing and real keystore |
| P1.15 Services | Real TorBox batched authenticated cache evidence, result reuse/invalidation, no-key skip, successful miss vs failure/unknown, provider seeder preservation; existing addon/subtitle/IntroDB/Retrofit tests | OTT/TMDB/Trakt live-contract fixtures, native ALASS formats/cancellation and complete endpoint failure matrix |
| P1.16 Diagnostics | Default-off filtering, fatal exceptions retained, cancellation suppression, redacted export/limits/clear, real diagnostic buttons and toggles | Crash-row remote OK regression, lazy-list focus restoration, concurrent/disk-failure/uncaught-handler recovery |

## Limits and next execution work

Real Room and HTTP orchestration tests do not replace native/hardware qualification.
Reflection is localized to test fixtures for private HTTP/storage seams; test preferences
simulate successful secure storage and must not be treated as a Keystore security test.
The thumbnail color test validates bitmap cache round-trip, not native HDR conversion.
The inline Fullscreen test validates the callback and session ownership, not video decode.

The remaining column includes both JVM-testable work and device-only work. None of
those entries are silently skipped or marked complete. Keep the whole-app coverage
denominator unchanged. The 90% gate remains unmet.
