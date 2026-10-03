# P0/P1 regression implementation status

This is an implementation ledger, not a claim that every P0/P1 scenario is complete.
The numbered groups correspond to `TEST_BACKLOG_90_PERCENT.md`. All new tests use
offline providers, isolated preferences, in-memory Room or real Compose key dispatch.
No production accounts, API keys or cloud rows are used.

The latest regression tests cover stale cloud revision conflicts, source/subtitle
restoration from an account snapshot, drawer close/re-entry, lifecycle-stop progress
snapshots, suppression of sync requests after manager shutdown, and partial-download
cancellation cleanup. Earlier additions cover Compose focus and selection, service
command gating, OTT orchestration, encrypted phone pairing, and crash-row activation.
Hardware-only decoder and live Neon/RLS checks remain explicitly outstanding rather
than being counted as verified by fakes.

Latest validation: **685 JVM tests reported; 683 passed, 2 external-provider smoke
tests skipped, 0 failed**. Whole-app JaCoCo coverage is **17,266/39,412 lines
(43.81%)**, so the requested 90% target is not met. Each numbered P0/P1 family below
has an explicit implemented/remaining entry; “Still required” means that scenario is
not claimed as covered. Hardware/native and deployed-service checks remain separate.

| Group | Implemented regression coverage | Still required for complete group coverage |
| --- | --- | --- |
| P0.1 Progress/resume | Real PlayerViewModel: prepared/unprepared boundary saves, 0/1/4999/5000ms, unknown duration, future timestamps, 300-second/95% watched boundary, sticky completion, profile isolation, concurrent writes, cancellation-resistant boundary save, trailer exclusion, cache/watchlist cleanup; lifecycle-stop snapshot projection covers prepared-zero, rendered-frame, ended and unstarted states | PlayerScreen lifecycle observer/disposal wiring, urgent notification observation, multi-device player handoff |
| P0.2 Series | Real DetailsViewModel: explicit episode 10 source request, malformed exact target never falling back to episode one, noncontiguous sidebar numbering, next episode after watched, latest episode at zero position, indexed playback IDs, series completion; real EpisodesContent Compose: current episode overrides stale saved season/index, current card is focused, selecting a card emits the exact season/index; selected season-pack source and explicit subtitle survive account snapshot restore | Full on-player source/subtitle transition across episodes; device-to-device process handoff |
| P0.3 Navigation | Real NavDrawer D-pad entry, repeated Up/Down, destination activation, hidden menu, automatic focus rejection, and Right-to-close/Left-to-re-enter without navigation; existing focus-policy tests | MainActivity recreation/back stack, all main tabs and TopNav, deleted target and loading races |
| P0.4 Trailers | Real inline controls: Start Watching, Fullscreen callback, Back, watchlist state, mute, always-visible controls; ownership isolation; warmup single-flight, expiry, eviction, failure retry and caller cancellation | Actual decoder handoff/position, fullscreen surface, hover lifecycle, expired URL retries and hardware codec playback |
| P0.5 Cloud | Real AccountCloudStore with intercepted HTTP: unchanged/no-transfer, sparse component upload, newer local/cloud, default-device restore, stale revision conflict preserves local then applies newer cloud, failed upload retry, remote deletion and tampering; stopped AccountSyncManager ignores later history/flush requests; existing snapshot/partition/device tests | AccountSyncManager in-flight logout/concurrency, live concurrent-device server CAS, deployed RLS isolation |
| P0.6 Sources/subtitles | Real source/track stores: profile isolation, changed source, season pack file index, Off persistence, delay updates and clearing; real Details sorting/picker/autoplay; existing subtitle repository/backend/policy tests | PlayerScreen manual source replacement, ALASS cancellation when Off, full language-filter focus and timed recovery |
| P0.7 Updater | Real updater HTTP parsing/errors, untrusted URL/hash rejection, no-transfer permission denial/repeated resume/cancel, permission grant resumes exactly one download and malformed APK bytes end in a contained error before installer handoff, cancellation after partial bytes deletes the temporary APK; existing verifier/policy tests | Permission grant through a valid signed APK, installer/FileProvider failure, storage and operation mutex journeys |
| P0.8 Thumbnails | Real cache: complete first-five-minute grids at 10/20/30s, duplicates, profile/content cleanup, exact position lookup, color/dimensions; scheduling/carousel/memory policies; Service dispatch proves disabled profiles, local-file URLs, malformed/unknown commands and mismatched cancellation cannot start/cancel native decoding | Worker-process death/restart, native frame extraction/HDR correctness, confirm-only seek UI and playback-source switches (native/device acceptance) |
| P1.9 Home/OTT | Real HomeViewModel toggle/clear scoped history, embedded trailer shortcut, no-profile prefetch, empty tab reset; real OTT repository success/cache/dedup, unresolved-title hiding, partial-provider failure, force refresh and last-good fallback; real quick-action popup add/remove-only state, profile isolation and dismissal | Pagination cancellation, every quick-action entry surface and background transition races |
| P1.10 Search | Real SearchViewModel tests: debounce cancellation, query reset, result types/dedup, session and keyboard edits; TV keyboard Q/space/delete/clear/QR callbacks and D-pad result/menu routing; Pages per-mode DOM tests cover signin/signup/paste/search/avatar/hub | Delayed provider response/error UI and bottom-of-screen focus behavior under real TV layout/device |
| P1.11 Watchlist | Real ViewModel subscribed flows across profiles/types, null profile, idempotent remove, focus reset, poster lookup suppression/single-flight, deletion during resolution; shared title quick-action popup tested for watchlist remove-only/add behavior | WatchlistScreen-specific remote popup/focus restore, failed poster-provider retry, profile change during poster lookup |
| P1.12 Profiles | Real wizard: steps/cancel, unique language ordering with Malayalam/Kannada, persisted onboarding, identity edits, PIN validation/lockout/removal and deletion; device display tests; keypad set/confirm/mismatch/retry and four-digit focus handoff | PIN theme-color assertions, persistence failure/process recreation, account/device onboarding UI |
| P1.13 Settings | Existing real Settings/Playback/Dashboard/Theme ViewModels and PlaybackSettingsScreen tests; real diagnostics toggle/control clicks and remote route | Every remaining settings section UI, account tab entry, modal focus, final-item scrolling, write-failure presentation |
| P1.14 Account/phone | Real auth: input rejection, availability normalization/token reuse, JWT/bearer routing, cross-account session rejection, 401 vs transient failure, offline logout, failed login; AES-GCM Unicode/tampering/AAD/size/IV tests; Compose signin/signup activation, password visibility and mode fields; CloudPairingSession creation, encrypted message read/ack, wrong-mode rejection and connect failure; Pages mode DOM tests | Relay expiry/replay/capability/upload route, real Android keystore, username-availability response races |
| P1.15 Services | Real TorBox batched authenticated cache evidence, result reuse/invalidation, no-key skip, successful miss vs failure/unknown, provider seeder preservation; OTT repository/HTTP orchestration plus existing addon/subtitle/IntroDB/Retrofit tests | TMDB/Trakt endpoint fixture matrix, native ALASS formats/cancellation and remaining endpoint failures |
| P1.16 Diagnostics | Default-off filtering, fatal exceptions retained, cancellation suppression, redacted export/limits/clear, real diagnostic buttons/toggles and D-pad route; activating a read-only uncaught-crash row with Enter/Center leaves the panel stable | Lazy-list focus restoration/long-log scrolling, concurrent/disk-failure/uncaught-handler recovery |

## Limits and next execution work

Real Room and HTTP orchestration tests do not replace native/hardware qualification.
Reflection is localized to test fixtures for private HTTP/storage seams; test preferences
simulate successful secure storage and must not be treated as a Keystore security test.
The thumbnail color test validates bitmap cache round-trip, not native HDR conversion.
The inline Fullscreen test validates the callback and session ownership, not video decode.

The remaining column includes both JVM-testable work and device-only work. None of
those entries are silently skipped or marked complete. Keep the whole-app coverage
denominator unchanged. The 90% gate remains unmet.
