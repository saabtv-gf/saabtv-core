# Saab TV: centralized information schema and performance architecture

Status: implementation in progress in the working tree, based on commit `fc96e69`, app `0.1.122-beta`; the current Room target is schema 65. Neon migrations `005_sync_v3_state.sql` and `006_sync_v3_abuse_limits.sql` have been applied to the approved production branch; migration 006's postflight checks passed on 2026-10-12. Additive Room migrations 63→64 and 64→65 are local and still need normal app-release rollout. Live quota-boundary and high-transfer smoke tests remain unrun. This is **not** a completed app-wide migration or a claim of million-user production readiness. The client negotiates sync v3 only when supported and retains the encrypted v2 snapshot path during migration.

### Implementation checkpoint — 2026-10-12

Latest implementation state (verification timestamps matter):

- Latest full verification on 2026-10-12 passed the JVM suite (**1,100 tests, 0 failures, 2 skipped**) and Android TV API 36 emulator instrumentation (**31/31, 0 skipped/failed**). Debug lint and the strict core-policy, progress-boundary, central-information-policy and canonical-profile-preference gates passed. The refreshed merged JVM + emulator JaCoCo report covers **28,315/48,330 lines (58.59%)**. These are repository-wide figures, not proof that every scenario is covered. Debug APK packaging passes, including the `armeabi-v7a` split; this does not validate playback on a 32-bit TV. No physical TV is attached; fleet and performance acceptance gates remain unverified.
- App, health and isolated-player diagnostics now share an inter-process file lock and a **6 MiB persistent budget**, with **2 MiB export scratch** and **7-day retention**. Export labels and details are bounded; session IDs are path-safe; active sessions age from their newest file write. Focused regressions plus the full suite pass. These storage ceilings are explicit starting limits, not device-benchmarked targets.
- AndroidX Macrobenchmark is upgraded to stable **1.5.0**. Its cold-start/first-navigation journey and Baseline Profile collection both pass on the API 36 emulator. The benchmark run used the explicit `-PsaabAllowEmulatorBenchmark=true` smoke-test opt-in to suppress Macrobenchmark's intentional `EMULATOR` accuracy error; its timings are not valid performance measurements. Search traversal is conditional on Search being visible; representative Search/TMDB/detail/source/playback/seek measurements still require a provisioned profile/catalog and representative devices.
- The typed browse-state migration now covers Search (`TitleCardProjection`), My Library (`LibraryCardProjection`), Details display content (`TitleCardProjection` with a separate provider action/episode payload), and Home ordinary/hero/mixed catalog rows (`CategoryRow`). Raw provider `HomeRow` is converted at the repository-to-ViewModel boundary; catalog URL/skip pagination metadata is explicit. Compatibility `MetaItem` creation remains at exact provider/action/navigation boundaries, including opaque IDs that cannot be safely canonicalized.
- Verified-identity alias reads for remembered movie and episode sources are implemented and regression tested; unknown/addon identities remain scoped and exact. `CategoryRow` is now included in the 100%-line policy gate, including default-constructor and interface-order paths.
- Added edge-case regressions for malformed Cinemeta episode snapshots, cache-storage exception isolation/cancellation, rating source provenance, legacy metadata fallback, malformed projection fields, unsupported content/playback kinds and callback acknowledgement loss.
- Regression cases for audio/subtitle selections surviving a portable-profile/local-Room-ID remap, migration of legacy local track keys, and deleting/recreating the same portable profile are included in the passing current-worktree run.
- Account snapshot import compatibility is exercised for every supported historical schema from 51 through 60, including profile defaults, watchlist artwork/provider-origin additions, and canonical identity index repair.
- TMDB preview/discovery now returns canonical `TitleCardProjection`s for Search, personalized Home, Details recommendations/collections, cast filmography and studio discovery. `TmdbMetaPreview` is internal to the data adapter; a static source-boundary regression prevents TMDB preview/transport DTOs from leaking into UI files. Recommendation ties use canonical rating followed by a stable presentation key; tie behavior is deterministic and regression tested.
- Profile settings, library/watched/progress actions, bounded profile runtime configuration and profile deletion now have portable command/outbox paths; local profile deletion removes profile-owned rows transactionally and syncs a profile tombstone. New outbox payloads are AES-GCM encrypted at rest and again for transport; legacy plaintext pending rows are upgraded with compare-and-set before retry. Over-limit runtime snapshots remain on the encrypted v2 compatibility path.
- Sync v3 can apply `profile_state`, `profile_runtime`, `library`, `watched_state`, `series_next_up`, `playback_progress` and `source_selection` records and binds the runtime profile snapshot before the account UI opens. Integration credentials and selected audio/subtitle preferences still use the end-to-end encrypted v2 compatibility snapshot by design; device-only settings remain local.
- Playback requests consume typed `PlaybackIntent` identity/resume/session context. The code audit confirmed load-token checks around source resolution, retry/fallback, and subtitle work, plus request-generation ownership in the isolated thumbnail worker; deterministic regressions cover a stale magnet callback and ALASS cancellation. Exact `PlayerRequest`/provider action payloads remain compatibility adapters. Physical episode-transition, decoder, seek and long-session validation is still outstanding.
- Source discovery now emits independently completed addon batches for the foreground source picker. Each partial batch is TorBox-enriched when configured and uses the same profile filter/rank policy as the final result; only an eligible row with a playable URL/hash is selectable while slower addons continue. Manual selection cancels the remaining discovery request. Automatic source selection deliberately still waits for the complete ranked set. Catalog-row aggregation remains unchanged.
- The source-picker regression suite now covers fast-before-slow selection, exclusions on partial rows, cancellation after a user choice, parity of final and last-partial results, and D-pad focus landing on an actionable row. The current full run includes the regression and Compose interaction tests.
- Audited production user-state mutation call sites: UI and Trakt mutations invoke the command/outbox repository with their local DAO write in the same transaction; v3 DAO mutations are confined to sync-engine remote-apply transactions. Compatibility/fallback and legacy identity behavior remain separately tracked below.
- Verified alias promotion moves the latest complete episode-list snapshot to the canonical root without copying incomplete manifests. The approved Neon migration was subsequently applied; see the sync-v3 checkpoint below.
- Canonical scalar facts now carry per-field source and observation time (genres, date, rating, runtime, status, countries, original language and collection). Additive migration 60→61 preserves prior facts as `LEGACY`, below provider observations. Null/unsupported v3 pulls now report incomplete sync so the existing encrypted snapshot fallback can run; pending-work checks are scoped to the signed-in account.
- Room schema 65 and migration 64→65 add bounded durable TMDB Details-only cache fields for age-rating region and credits. Full Details entries are distinguishable from lightweight Home projections, have a 24-hour freshness policy, preserve provider provenance, and are restored from Room after service/process recreation. Credits are capped (40 cast, 20 directors, 20 writers, 64 KiB/entry, retained on at most 128 of the newest title-detail rows—8 MiB worst-case); eviction clears only credits, not canonical title facts or independently stored age ratings. Image bytes stay in the image cache. Regression coverage verifies restart reuse, Home/Details cache-key separation, merge preservation, malformed data, bounds/eviction, and the migration preserving existing canonical rows. Migration 64→65 is local and still requires release rollout.
- Additive migration 61→62 adds indexed canonical identity keys to history, library and series-next-up rows without changing legacy provider-ID primary keys. Backfill, account snapshot schema 60 import/export, v3 remote-apply normalization and canonical-key DAO lookups preserve exact playback IDs and addon installation scoping.
- Migration 62→63 repairs the retained seek-preview and outro-countdown mirror columns from their canonical profile fields. Room profile inserts/updates, remote profile state, and account snapshot capture/restore enforce the same precedence. Snapshot schema 62 exports an explicit fixed column contract for account-owned tables only; restore rejects an unversioned database-shape change, and canonical cache/outbox tables stay outside the snapshot.
- Account snapshot schema 62 now excludes device-local diagnostics settings and serializes profile spoiler/trailer preferences with portable profile IDs instead of sender-local Room IDs. Legacy schema ≤61 remains readable; any historical diagnostics values are validated but ignored, while the receiving TV's local diagnostics choice and active-profile marker are preserved. Focused regressions cover portable-ID remapping, legacy import, and device-local isolation.
- Account snapshot schema 63 now versions the portable Stremio/Trakt credential-key mapping explicitly. Sender-local Room IDs are translated to portable profile IDs on capture and to the receiving Room IDs on restore; the active-profile integer selector stays device-local. Legacy root Stremio credentials are attached to the active profile from the same captured Room rows, avoiding a profile-switch race. The schema-62 credential format remains importable. Regression coverage verifies two-profile credential remapping, active-profile restoration, and the legacy root-credential migration.
- Room schema 64 added portable `profileOwnerId` to addon, catalog, hub-row and hub-item records. Migration 63→64 assigns the previous global runtime set to the active portable profile and preserves every row. Hub rows and items now use composite profile-aware keys; hub/item projections join by `(profileOwnerId, rowId)`, so matching IDs in separate profiles cannot cross-attach. Runtime DAO reads, mutations, deletes and ordering carry or verify the expected owner; stale profile objects are rejected instead of being relabeled as the active profile. Per-profile v3 runtime capture/apply no longer depends on swapping global Room tables.
- The encrypted v2 compatibility snapshot is schema 63 and deliberately excludes the Room-only owner column. It exports the active profile's legacy runtime view and restores it under that profile's portable identity; schema-64 clients carry the full per-profile runtime state through bounded, encrypted v3 `profile_runtime` records. Regressions cover schema-62 runtime restore, portable credential IDs and ownership, plus duplicate hub IDs and stale cross-profile writes. Full JVM/API 36 emulator verification and lint passed for the current working tree.
- Profile runtime snapshots now use account-scoped Android Keystore-backed encrypted preferences instead of writing new credential-bearing add-on configuration as plaintext JSON. Legacy `AtomicFile` snapshots remain readable and are removed, including the backup sidecar, only after an encrypted durable copy has been written and read back; when Keystore persistence is unavailable the app keeps the legacy copy and records a redacted diagnostic rather than deleting the only durable copy. Profile deletion removes encrypted runtime state and all five profile-owned preference families. JVM and API 36 tests cover migration/fallback/deletion; the emulator Keystore test verifies persisted XML contains no secret.
- Verified alias promotion now transactionally rekeys already-persisted title/episode history, library and next-up identity indexes to the selected canonical root. Watched/watchlist badge flows include verified aliases, so an existing user-state row remains visible on cards presented through another verified provider identity. A Room regression verifies episode rows and next-up rekey together; an instrumented DAO regression verifies alias badge lookup. Opaque provider IDs and ownership keys remain intact.
- Details watched/resume/next-up reads now try the raw compatibility ID first and then the canonical index; series progress also queries episode-index prefixes, so verified title-ID aliases can reuse episode history. Watchlist status observes the same verified alias badge flow. Episode coordinate parsing is covered for IMDb/TMDB IDs and file-index suffixes; the canonical SQL prefix query and rekey behavior pass instrumented tests.
- TMDB preview results share one validated projection across search, personalized Home rows, Details recommendations/collections, cast filmography and studio discovery. The shared title-card renderer uses canonical title/artwork roles; Home enrichment, preview joins, artwork prefetch and lazy-list keys use typed canonical presentation keys so add-ons reusing opaque IDs cannot bleed metadata into one another.
- Search focus restoration, watched-alias single-flight, Details/cast enrichment lookups and inline-trailer anchors now use those same provider-installation-qualified presentation keys. `SaabTvCard` accepts a typed `TitleCardProjection`; the Details recommendation and filmography cards pass it while the compatibility `MetaItem` remains available for actions/navigation.
- Regenerable cache pools now have one device-tier policy: low/constrained/standard caps are respectively 20/32/48 MiB Coil memory, 60/80/100 MiB artwork disk, 15/20/30 MiB HTTP metadata and 150/200/250 MiB thumbnails. The thumbnail cap is installation-wide across account scopes and is enforced at startup, after writes, and when moving scopes; inactive artwork/HTTP scopes are pruned before opening a cache.
- A thumbnail generation's current content timeline is now a pinned working set: at most 25% of the device-tier thumbnail budget, capped at 64 MiB. LRU trimming evicts other timelines first; if existing or newly generated frames exceed the working-set ceiling, generation reports capacity unavailable without deleting partial/current frames. A cross-process OS file lock protects the active timeline because the decoder worker runs in an isolated process; stale reservations are reclaimed automatically when the owning process exits. Reservation release is non-cancellable and followed by normal trimming. Regressions cover independent cache instances, nested reservations, pin-versus-eviction, pre-existing over-limit timelines, no partial writes, cancellation cleanup and invalid identities. Physical worker-death behavior remains a device acceptance check.
- Startup now follows the same compatibility-snapshot gate as routine sync: v2 is uploaded only when v3 is unavailable, legacy snapshot data is dirty, or no initial snapshot baseline exists.
- Account sync crypto now has a short-lived key-use helper for synchronous call sites; the suspendable legacy-outbox compare-and-set path retains its explicit `try/finally` zeroization. Sync-v3 plaintext byte buffers and restored snapshot buffers are cleared after parsing/apply, and compressed component plaintext is cleared after use. Regression tests cover helper key-copy clearing after success and exceptions and pass in the current run.
- TMDB lookups coalesce identical in-flight reads behind bounded shared semaphores. A focused regression caught and fixed a nullable-miss fallthrough that could issue the same request twice; cancellation isolation, retry after failure, and semaphore behavior at capacity overflow are covered. The latest full JVM + API 36 runs pass.
- Canonical metadata merges now emit one redacted, detailed-only diagnostics event containing policy version, truncated canonical hash, and selected field/source/reason. No resolved text, URL, provider payload or credential is logged; focused tests verify the summary redaction.

The earlier implementation slices below describe the original identity and cache foundation; their schema-56 references are historical. The current DB version is 65.

Implemented locally in this working tree (historical automated test results are recorded above; current-tree verification and device-tier benchmarking remain separate gates):

- Typed content kind/namespace identities with media-kind-safe TMDB keys, IMDb validation and addon-installation scoping; TMDB ID conversions now use these keys.
- Deterministic canonical text/artwork field selection for focused Home metadata, rejecting unverified, wrong-field, future-dated and stale-identity candidates.
- Persistent Room title-fact and verified-alias tables added by additive `MIGRATION_54_55`; verified identity promotion is transactional, root-stable, and merges complementary localized fields.
- TMDB Home enrichment now checks the persistent canonical cache before issuing network requests, stores valid results after the response, separates movie/series and locale keys, and treats logo lookup as a separately observed field. Full details/credits remain on the details path.
- Cinemeta movie facts now map to the same canonical title row. A repeat movie Details lookup can use a fresh verified IMDb-keyed Cinemeta cache entry; series continue to fetch Cinemeta so their episode list is complete, and a preferred addon still takes precedence.
- Complete verified Cinemeta series episode lists now have an additive, bounded local cache (`canonical_episode_lists` + `canonical_episodes`, schema 56). The manifest distinguishes a cached empty list from a cache miss; stale, incomplete, malformed, wrong-source and future-dated data falls through to Cinemeta. Exact opaque episode IDs/order are preserved; age and capacity eviction remove complete snapshots and prune orphan episode rows. These tables remain outside account sync.
- Search now publishes TMDB candidates using their native `tmdb:<id>` plus normalized movie/series kind instead of waiting for one TMDB→IMDb request per candidate. Detail navigation retains the existing alias-resolution path, so a missing mapping no longer erases an otherwise valid search result.
- A full TMDB details fetch now persists its common title facts through the same canonical title row used by Home. Credits/certifications remain details-only; unsupported identities and mismatched TMDB response IDs cannot enter the cache.
- Provider-specific projections now preserve rating provenance: the TMDB Home mapper exposes only fields whose source is TMDB (and whose rating scale is `0-10`), while the Cinemeta mapper exposes an IMDb rating only when the canonical row identifies it as IMDb on the same scale. This prevents cross-provider ratings and attributed title/art fields from leaking through the wrong adapter.
- New metadata tables are excluded from account snapshots, keeping rebuildable cache facts out of cross-device user-state payloads.
- Explicit TTL/capacity eviction for IntroDB, TorBox availability observations and subtitle capability observations.
- Deterministic unit tests and a dedicated 100% JaCoCo line gate for the handwritten identity, merge, cache and Home metadata policy classes.

Scalar provenance and canonical identity migrations (schemas 61–63), explicit v2 snapshot schemas 62–63, Room schema 64 profile ownership, and current Room schema 65 Details cache fields are represented separately. Account snapshots preserve device-local diagnostics/profile activation. Canonical projections are used by the shared card renderer, canonical cache adapters and selected joins; `MetaItem` remains the provider/action compatibility model in ViewModels and Compose callbacks. No real-device/fleet performance baseline or improvement has been measured.

The working-tree Sync v3 implementation is negotiated with the server and falls back to v2 when either required RPC is unavailable. The deployed push RPC selects a deterministic `(entity_revision, device_id)` winner and republishes the winner after a losing write so connected clients can converge, but this is a deterministic tie policy—not proof of real-world event order. Production migration postflight verified version 3, its three tables and two RPCs, forced RLS, no direct authenticated table reads, authenticated RPC execution, and preservation of v2. Functional two-device conflict, deletion/recreation and older-client coexistence checks remain release gates. Integration credentials and explicit audio/subtitle preferences sync through the end-to-end encrypted compatibility snapshot; bounded add-on/runtime configuration can also use the encrypted v3 outbox. Neither path writes decrypted secrets to the cloud. Track-selection keys use portable profile IDs with a legacy local-key migration. Per-device 4K capability and hardware overrides stay local. Resource budgets, real-device benchmarks, provider contracts and fleet-scale SLOs remain unmeasured.

## 1. Recommendation

Create one provider-independent information contract, backed by a local canonical store and small purpose-specific repositories. Cinemeta, TMDB, Stremio addons, TorBox, IntroDB, Trakt and other integrations become adapters at its boundary. Screens consume small projections of that store, not provider responses.

Centralize **identity, ownership, merge rules, freshness, commands and resource budgets**. Do not centralize every object into one giant JSON document, singleton, database table, ViewModel, or blocking server call.

Three distinct data domains must remain separate:

1. **Content:** titles, episodes, localized metadata, artwork references, credits and catalog membership. Reusable and cacheable; never authoritative for user progress.
2. **User state:** account/profile configuration, library, watched state, resume position and selections. Durable, isolated and synchronized.
3. **Runtime:** playback sessions, resolved URLs, availability observations, decoder state and worker jobs. Short-lived, device-sensitive and usually not synchronized.

The first migration can use the existing Room, Retrofit/OkHttp, Coil, Hilt and coroutine stack. It does not require a new hosted backend. Supporting millions of active users subsequently requires provider agreements, fleet-wide traffic controls and operational infrastructure; a local schema cannot supply those by itself.

## 2. What the current code actually does

These are code observations, not measured device-performance results. The implementation checkpoint above lists the code changed since the initial review. The Neon migration/postflight status is recorded separately; this section does not claim a new database inspection or production-device performance measurement.

| Current implementation | Evidence in repository | Implication |
|---|---|---|
| `MetaItem` is both the Stremio response model and a UI object containing progress, resume playback ID and addon origin. | `data/model/stremio/MetaItem.kt:15` | Provider metadata, navigation identity and personal state are coupled. |
| TMDB enrichment and preview models exist alongside `MetaItem`; Home converts TMDB previews into `tmdb:` IDs. | `data/tmdb/TmdbDomainModels.kt:6`; `ui/home/HomeViewModel.kt:1215` | The same work can be represented by different IDs and data shapes. |
| TMDB mapping uses typed, media-kind-qualified keys and a persistent verified-alias store; Home/Search/Library card enrichment, focus and trailer-anchor joins now use installation-qualified presentation keys. | `data/tmdb/TmdbService.kt`; `domain/schema/ContentIdentity.kt`; `domain/schema/CanonicalTitleProjection.kt`; Room schema 65 | The known movie/series numeric collision and reused-addon-ID presentation bleed are covered. Provider/action compatibility APIs and durable history primary keys still retain legacy string IDs. |
| Home enrichment scans/copies category rows, mixed rows, hero items and its enrichment map. | `ui/home/HomeViewModel.kt:895` | Multiple representations require coordinated updates; allocation/invalidation cost grows with loaded items. |
| Room schema 65 adds canonical title/verified-alias facts, complete Cinemeta episode-list cache tables, scalar-field provenance, indexed canonical identity keys, profile-owned runtime addon/catalog/hub rows, and bounded durable Details-only TMDB credits/age-rating cache. | `data/local/SaabTvDatabase.kt`; `data/local/CanonicalMetadataDao.kt`; `data/repository/AddonRepository.kt`; `data/tmdb/TmdbMetadataService.kt`; `data/local/AddonDao.kt` | Search, My Library, Details display, and Home catalog/hero state now expose typed projections. Exact provider payloads remain only at compatibility/action boundaries. Verified aliases are used by watched/library/progress/next-up and remembered-source reads; opaque IDs remain unmerged. Runtime config rows are addressed by portable profile owner; stale UI writes are fenced. Details cache is bounded and separate from Home card cache. |
| History and watchlist rows duplicate title/artwork strings and use provider-style string IDs. Series lookup parses prefixes/suffixes. | `data/model/WatchHistoryEntity.kt:9`; `WatchlistEntity.kt:9`; `domain/EpisodeUtils.kt` | Resume, badges and quick actions depend on compatible string formats and synchronized copies. |
| Foreground source selection emits per-addon partial batches, TorBox-enriches when configured, and runs the same profile filter/rank policy for partial and final results. Eligible playable rows are selectable before slower addons finish; auto-select waits for the complete ranked set. Catalog rows still aggregate within each configured initial/background batch. | `data/repository/AddonRepository.kt` (`getStreamsProgressively`, `getDashboardRows`); `ui/details/DetailsViewModel.kt`; `ui/details/GlassSidebar.kt` | Manual selection has no slow-addon barrier after an eligible source arrives. Catalog aggregation may still delay the batch awaiting it; auto-selection intentionally avoids choosing from an incomplete ranking. |
| Details publishes Cinemeta metadata before scheduling supplemental TMDB work; a new warm movie cache can also satisfy repeat Cinemeta lookups. Series still fetch their full Cinemeta episode list. | `ui/details/DetailsViewModel.kt:182` onward; `data/repository/CanonicalMovieMetadataCache.kt`; `data/repository/AddonRepository.kt` | Preserve and generalize the early Details fast path; cache facts are rebuildable and never replace series episode authority. |
| TMDB already has bounded caches and a separate network dispatcher. Preview warmup already implements bounded single-flight work. | `TmdbMetadataService.kt:45`; `di/NetworkModule.kt`; `data/cache/PreviewWarmupCache.kt` | Reuse these mechanisms; do not add another competing cache framework. |
| IntroDB, TorBox and subtitle observations now use a bounded TTL/LRU cache; other app caches and memory consumers remain independently budgeted. | `data/cache/BoundedExpiringCache.kt`; corresponding repositories/services | These three maps have explicit bounds. This does not establish an installation-wide memory budget or prove a measured memory reduction. |
| Cloud transfer already uses revision probes, compressed encrypted components and changed-object uploads. | `AccountComponentCloud.kt:17`; `cloud/neon/004_incremental_account_sync.sql` | The earlier whole-upload network problem has already been partly addressed. |
| Normal sync-v3 mutations are captured as durable per-entity outbox operations, encrypted and reconciled by entity revision; the complete encrypted v2 snapshot remains for old-client negotiation, initial baseline and compatibility fallback. | `AccountSyncV3Engine.kt`; `UserStateCommandRepository.kt`; `SyncV3Protocol.kt`; `AccountSyncManager.kt`; `AccountSnapshotStore.kt` | The fast path is entity-incremental. Snapshot capture/whole-copy conflict policy still exists by design on the v2 compatibility path and must not be mistaken for v3's conflict semantics; retire it only after supported-client adoption is measured. |
| Profiles own runtime addon/configuration snapshots in explicit Room rows keyed by portable profile owner; switching a profile changes the active query, not the stored rows. Runtime snapshots are account-scoped, Keystore-encrypted locally; device display overrides are separately maintained and excluded from account snapshots. | `ProfileConfigurationManager.kt`; `AddonDao.kt`; `MIGRATION_63_64`; `DeviceDisplayPreferences.kt` | Local encrypted snapshots and profile-owned rows are independently durable. The schema-64 migration maps the former global set to the active/first portable profile. V2 keeps its schema-62 compatibility shape; v3 transports bounded per-profile runtime. |
| Thumbnail cache already has source-scoped timeline identity and an isolated worker; alternate lightweight streams remain allowed. | `ThumbnailTimelinePolicy.kt:12`; `SeekThumbnailWorkerService.kt`; `docs/thumbnail-timeline-and-inline-preview.md` | Retain these safeguards and the requested fast-source policy. A different release may still have a different timeline. |
| Regenerable image/HTTP/thumbnail caches share device-tier caps, while account directories remain isolated; the active account's Coil/HTTP caches have per-scope caps and thumbnail eviction counts every scope under the installation root. | `data/cache/InstallationCacheBudget.kt`; `di/ImageLoaderModule.kt`; `di/NetworkModule.kt`; `data/cache/SeekThumbnailCache.kt` | Initial managed disk caps are 225/300/380 MiB for low/constrained/standard tiers (artwork + HTTP + thumbnails). These exclude Room, diagnostics and other app files, and are unbenchmarked starting limits—not measured safe maxima. |
| Macrobenchmark and Baseline Profile infrastructure use stable AndroidX Benchmark 1.5.0. Both emulator smoke tests pass with the explicit `-PsaabAllowEmulatorBenchmark=true` opt-in; this suppresses the library's `EMULATOR` accuracy error and is not a performance result. Profile generation traverses Search focus when the Search destination is present. | `benchmark/build.gradle.kts`; `benchmark/src/main/java/com/saab/tv/benchmark/BaselineProfileGenerator.kt`; `gradle/libs.versions.toml` | Real Search/TMDB/detail/source/playback/seek and soak measurements still need a provisioned profile/catalog and representative hardware. Emulator timings do not establish Fire TV decoder, memory or fleet performance. |

The most valuable change is therefore not a cosmetic model rename. It is eliminating screen-specific identity and synchronization logic while reducing repeated work.

## 3. Target data flow

```text
Cinemeta / TMDB / addons / IntroDB / TorBox / Trakt
                    |
           validated provider adapters
                    |
       identity resolver + deterministic merge
                    |
       repositories -> local canonical store
                    |
       card / detail / episode / library projections
                    |
            ViewModels -> Compose UI

User action -> command -> local transaction + sync outbox
                               |
                       encrypted cloud sync

Playback command -> session coordinator -> existing player backend
                           |
                 transient source/worker state
```

The shared contract is not the only implementation type. Keep transport DTOs, database entities and UI projections separate. Android's offline-first guidance recommends local persistent reads and repository-managed network updates; that is the basis for this direction. [Android offline-first architecture](https://developer.android.com/topic/architecture/data-layer/offline-first)

Exceptions are explicit: high-frequency decoder telemetry, frame buffers and native handles stay in the runtime pipeline, not Room. Their domain-facing snapshots still follow the common typed contract.

## 4. Identity: the foundation of the schema

### Content and provider IDs

Use a typed `TitleKey`, never an unqualified numeric ID. Examples:

```text
imdb/movie/tt1234567
imdb/series/tt7654321
tmdb/movie/12345
tmdb/series/12345
addon/<installation-id>/<provider-type>/<opaque-provider-id>
```

These are illustrative key formats, not real title mappings. Movie and series keys remain different even when the numeric TMDB ID is identical. Do not infer provider namespaces from arbitrary digit strings.

Maintain verified aliases and redirects. Prefer a verified IMDb anchor for Cinemeta/addon interoperability when available, but allow a TMDB-native or addon-native title to render without blocking for an IMDb lookup.

When an alias later proves equivalence, promote/merge the records in a transaction, retain an old-key redirect, migrate related user references, and notify affected projections. Use deterministic root selection on all devices. Incoming sync records carry their original identity and known verified aliases so provisional records converge after resolution. Maintain a stable local presentation ID during promotion to avoid replacing a focused card's Compose key.

Rules:

- Never merge works solely because the title text/year resembles another work.
- A trusted external-ID match can establish equivalence; fuzzy matching is only a candidate requiring stronger evidence.
- An addon instance owns its opaque IDs. Two addons using `123` must not collide.
- Provider identity and installation identity are different: two configured installs of one addon may have different credentials/capabilities.
- Unknown mappings and verified absence are different. Negative mapping results expire; network errors do not become permanent negative mappings.
- Keep a compact portable identity stub with user state so bookmarks/resume remain intelligible if the metadata cache is evicted or the provider disappears.

### Episodes, editions and files

Separate four concepts:

1. `TitleKey`: the series/movie work.
2. `EpisodeKey`: parent series plus stable episode identity, with provider alias and explicit season/episode fields.
3. `MediaEditionKey`: cut/release/timeline when verified; unknown is permitted.
4. `MediaFileKey`: torrent hash plus file index or other stable file identity.

Do not use an expiring HTTP URL as the preferred durable file identity. If no stable file ID exists, use a session-scoped identity instead of pretending it is permanent.

Preserve opaque episode IDs and represent specials/order explicitly. Do not assume every provider follows `tt:season:episode`, or that special episode numbering is invalid. Cinemeta remains the authoritative episode listing for the existing series workflow; TMDB may enrich known episodes, not silently replace that listing or invent episode numbers.

An exact `EpisodeKey` must flow through card -> resume intent -> source lookup -> player -> saved progress. It must never be reconstructed as S1E1 because a title record is incomplete.

## 5. Proposed logical schema

These are logical records, not a requirement to introduce every SQL table immediately. Normalize relationships that are queried/shared; keep small leaf structures as bounded typed columns or JSON when that is simpler. Avoid a generic entity-attribute-value database.

| Record | Principal fields | Scope and retention |
|---|---|---|
| `Title` | key, kind, original title/language, year, lifecycle status | Content cache; no user progress |
| `ExternalIdentity` | namespace, media kind, provider/install ID, external ID, title key, verification source | Indexed aliases; referenced identity stubs survive cache eviction |
| `IdentityRedirect` | old key, canonical key, verification/version | Durable for referenced user records; acyclic |
| `LocalizedTitle` | title key, language tag, name, synopsis | Localized content cache |
| `TitleFacts` | runtime estimate, genres, country/date facts and source | Content cache; runtime is not decoder duration |
| `Rating` | title key, source, value, scale, vote count, observed time | Preserve source; IMDb and TMDB never mislabeled |
| `Certification` | title key, country, system, rating | Region-specific, not one global age-rating string |
| `Artwork` | asset key, title/person key, role, language, source, dimensions, locator, version | References only; binary images in file cache |
| `Season` / `Episode` | series key, episode key, aliases, season/episode/order, release time, title/still reference | Lazy by needed season; provider-authoritative listing |
| `Person` / `TitleCredit` | person key, title key, role/job/character/order, portrait reference | Credits separately paged/cached |
| `CollectionMembership` | collection key, title key, rank | Content relationship |
| `Catalog` / `CatalogPage` / `CatalogEntry` | provider/query/context key, cursor, title reference, rank, generation | Cached membership, not copies of entire titles |
| `RecommendationEntry` | profile/taste revision, title key, score, reasons, rank, generation | Private derived cache; rebuildable, not canonical watch history |
| `Trailer` | title key, provider/video ID, official/type/language | Cacheable descriptor, not signed playable URL |
| `StreamCandidate` | playable key, provider/install ID, file key, technical facts, opaque locator reference | Short TTL, profile/configuration-sensitive |
| `AvailabilityObservation` | candidate/hash, provider, state, observed/expiry times, evidence | Transient; cached/not cached/unknown/error distinct |
| `SubtitleCandidate` | playable/file key, embedded/external origin, language, format, flags, release-match evidence | Session/transient cache |
| `SegmentMarker` | playable/edition key, intro/recap/outro kind, start/end, source/confidence | Provider cache or local derived result |
| `Account` / `Profile` | stable account ID, portable profile UUID, order/name/theme/avatar/PIN reference | Durable user state; never use local row IDs as cross-device identity |
| `ProfilePreferences` | versioned appearance, playback, language and sort/filter sections | Profile-synced; typed validation/defaults; shared semantic settings have one owner |
| `DevicePreferences` | installation ID, 4K answer, codec/tunnel overrides, local diagnostics options | Device-local; not overwritten by another TV |
| `ProviderInstallation` | profile/account scope, addon/provider ID, public config, capability version, credential reference | Durable config; secrets in separate encrypted storage |
| `LibraryMembership` | profile, title key, membership state/revision | Durable, explicit add/remove operations |
| `WatchedState` | profile, playable/title key, manual/automatic origin, state/revision | Durable; can be explicitly unmarked |
| `PlaybackProgress` | profile, playable key, position/duration, session ID/sequence, edition/file reference, revision | Durable; includes zero-position established sessions |
| `SeriesResume` | profile, series key, exact next/resume episode reference, position reference, revision | Derived from validated progress/episode state; not a second uncontrolled truth |
| `SourcePreference` / `TrackPreference` | profile/playable scope, stable source fingerprint, language/track fingerprint or Off | Durable; never retain expired URLs/native track indices |
| `ThumbnailManifest` | playable/file/timeline key, interval, targets/captured PTS, dimensions/color/version, completion | Local cache metadata; images on disk, not cloud |
| `DerivedCreditsMarker` | playback timeline key, trigger time, evidence version, generation | Local session result; survives thumbnail eviction |
| `SyncOutbox` / `SyncCheckpoint` | operation ID, entity key/version, encrypted-payload reference, cursor/acknowledgement | Durable until acknowledged, then compacted |

Additional control-plane schemas cover auth/pairing, updates, themes and diagnostics. Those are not title metadata: an update descriptor carries version/ABI/signature/digest, a pairing envelope carries mode/capability/expiry, and diagnostics carry redacted operation IDs. Do not force them into `Title`.

### Types and invariants

- Time positions/durations: signed 64-bit milliseconds with validated nonnegative values. Dates: date-only where applicable; timestamps: UTC instants. Monotonic clocks measure timeouts/TTL within a process.
- Size: 64-bit bytes, not formatted `GB` text. Runtime estimates and actual media duration have separate fields.
- Language: normalized language tags plus original provider values where needed; ordered preferences are distinct from a title's original language.
- Availability: `Unknown`, `Available`, `Unavailable`, `Expired`, `Error` plus observation provenance. Seeder count `null` is unknown, not zero.
- Subtitles: explicit `Off`, `Automatic`, `Selected(fingerprint)`; Off cancels selection, downloads and ALASS work.
- Decode/transport/color/HDR capability: structured facts with `Unknown`, not a regex claim treated as hardware proof.
- List limits, string lengths and payload limits validated at the adapter boundary. IDs/URLs are encoded safely, not concatenated from unvalidated input.
- Cache freshness and user-write revision are different concepts; `lastFetchedAt` is not `lastWatchedAt`.

Illustrative projection, deliberately small:

```kotlin
data class TitleCardModel(
    val stablePresentationId: String,
    val titleKey: TitleKey,
    val kind: MediaKind,
    val displayName: String,
    val artwork: CardArtwork,
    val libraryBadge: Boolean,
    val watchedBadge: Boolean,
    val resume: ResumeSummary?
)
```

The card does not carry all episodes, cast, source lists, credentials or provider JSON. A Continue Watching projection suppresses the watched tick without modifying canonical watched state.

## 6. Provider adapter contract

Use capability-sized interfaces, not one enormous integration interface or a runtime plugin loader. An adapter implements only what its provider supports: catalog/search, metadata, episode listing, stream discovery, availability, subtitles, segments or user-state export/import.

Every result is a normalized batch with source identity, request context/generation, freshness, explicit partial-failure state and bounded data. Preserve transport DTOs only inside their adapter. Preserve opaque source locators behind typed references where converting them would lose addon functionality.

| Integration | Supplies | Must not own |
|---|---|---|
| Cinemeta | Base metadata, IMDb-oriented identities, canonical current series episode listing/trailer descriptors | Profile state or TMDB-specific UI logic |
| TMDB | Verified identity links, localized facts, artwork/logo candidates, recommendations, collections, people and search intent results | User watched status; replacement of Cinemeta episode lists |
| Stremio catalog/metadata addons | Catalog membership, native IDs, metadata according to declared capability | Other addons' namespaces or account secrets |
| Torrentio/MediaFusion/stream addons | Stream candidates and provider observations | Globally authoritative availability; displayed seeder text treated as live truth |
| TorBox | Batched cached availability with a configured integration | Extra seeder probing on the playback startup path, which was explicitly removed |
| IntroDB | Intro/recap/outro markers for the requested episode | Playback lifecycle or unconditional next-episode decisions |
| OpenSubtitles/subtitle addons | External subtitle candidates, languages and matching evidence | Relabeling embedded tracks as external subtitles |
| Player backend | Actual duration, tracks, decode capability and playback/session events | Metadata provider resolution or cloud reconciliation |
| ALASS | Versioned alignment result keyed to exact media/subtitle input | Running while subtitles are Off or owning subtitle selection |
| Trakt | Remote history/library observations and outgoing acknowledgements | Blind overwriting of newer local progress |
| JustWatch OTT | Country/platform catalog membership and IDs | Critical playback dependency; its undocumented endpoint is not a production SLA |
| Stremio account import | Profile addon/configuration input | Direct replacement of live tables while screens/jobs retain the previous profile |
| Neon Auth/Data API | Authenticated encrypted user-state transport/revisions | Public artwork/video storage or plaintext integration credentials |
| Trailer resolver | Short-lived playable trailer variants and expiry | Permanent title identity or guarantees about YouTube formats |
| TorrServer/external players | Device/session playback capability | Portable account state containing native handles or local paths |

Stremio distinguishes metadata IDs from playable video IDs and declares resource capabilities. The adapter must preserve that distinction. [Stremio addon protocol](https://stremio.github.io/stremio-addon-sdk/protocol.html)

Start by wrapping the existing services, not rewriting every provider. A provider registry selects existing Hilt-bound adapters from installed configuration; there is no downloaded executable addon code.

## 7. Merge and freshness policy

Merge facts centrally, not inside Home/Details/Search independently. Store provenance at useful granularity—metadata section/artwork/rating—not a heavyweight wrapper around every scalar.

### 7.1 One canonical field, not one field per provider

All normalized records use one field dictionary. For example, Cinemeta `name`, TMDB movie `title`, TMDB TV `name`, and another addon's title label become the same canonical localized title field. The domain/UI must not grow `cinemetaName`, `tmdbName`, `addonName`, or separate overwrite logic for each provider.

Provider-specific names remain inside transport DTOs because external APIs cannot be renamed. They end at the adapter boundary; there is only one canonical field for each semantic meaning in the app.

The selected value and its source are exposed together where diagnostics need them. Provider observations may be retained as bounded alternatives, but never as competing authoritative UI fields. A candidate carries the same canonical field identifier, a source identifier, a typed value, its context and freshness; it does not create another public property.

| Canonical field | Provider variants mapped into it | Selection rule |
|---|---|---|
| `localizedTitle.name` | Cinemeta `name`; TMDB movie `title`/TV `name`; addon title label | Requested-language match, then usable fallback; verified identity required. Preserve original title separately because it is a different fact. |
| `localizedTitle.synopsis` | Cinemeta `description`; TMDB `overview`; addon synopsis | Profile metadata override if explicitly chosen; otherwise suitable localized TMDB enrichment, then usable Cinemeta/declared metadata-owner fallback. Empty responses do not erase it. |
| `artwork[POSTER]` | Cinemeta/addon `poster`; TMDB `poster_path` | Correct portrait role, valid asset, language/quality policy; do not select a backdrop for this role. |
| `artwork[BACKDROP]` | Cinemeta/addon `background`; TMDB `backdrop_path` | Genuine landscape asset, locale/quality policy and consistent visible-generation choice. Never treat a portrait as equivalent. |
| `artwork[LOGO]` | Cinemeta/addon `logo`; TMDB image `logos` candidates | Requested-language logo, language-neutral/English fallback as configured; correct transparent artwork role and dimensions. |
| `titleFacts.genreIds` | Cinemeta genre names; TMDB genre IDs; addon tags | Map known labels to a common vocabulary with media context; deduplicate and prefer trusted structured classifications. Preserve unmapped provider labels as bounded evidence, not invented equivalent IDs. |
| `titleFacts.runtimeEstimateMs` | Cinemeta formatted `runtime`; TMDB runtime minutes; addon estimates | Parse to milliseconds; prefer the matching edition/title's usable structured estimate, then fallback. It never overwrites `playbackSession.durationMs`. |
| `releaseDate` | TMDB movie/TV dates; Cinemeta/addon release info | Scope by title/episode, date kind and region. Year-only data remains year precision; never invent January 1. Display year is derived where possible. |
| `rating.value` | Cinemeta IMDb rating; TMDB vote average; addon ratings | One rating-record schema with `source` and `scale`. When the UI asks for IMDb, select IMDb evidence only; TMDB is not an IMDb fallback. |
| `episode.name` / `episode.synopsis` / `episode.still` | Cinemeta `title`/`name`/`overview`/`thumbnail`; TMDB episode enrichment | Match the exact Cinemeta episode identity, then apply locale-specific enrichment/fallback; do not replace listing or numbering. |
| `mediaFile.sizeBytes` | Addon video-size hint; parsed display size; verified file metadata | Verified matching-file metadata > structured provider hint > parsed display claim. Unknown stays null; contradictory files are not merged. |
| `mediaFile.videoFacts` | Backend tracks/probe; structured addon codec/HDR/resolution; parsed filename | Actual matching-file decoder/probe evidence > structured provider facts > parsed inference. Keep evidence confidence/unknown states. |
| `availability.cachedState` | TorBox cached response; addon cached marker | Fresh configured TorBox observation > provider-reported claim. A failed request is unknown, not verified false. Claims retain source/confidence. |
| `availability.seederCount` | Structured addon seeders; behavior hints; text-parsed count | Best available provider observation for the same torrent and time context. No new TorBox seeder requests; zero and unknown remain distinct. |
| `subtitle.language` / `subtitle.origin` | Player embedded track language; subtitle addon/OpenSubtitles language | Normalize language once; preserve embedded/external origin. Remembered Off/selection governs choice, then preferred embedded language, then best matching external candidate. |
| `segment.startMs` / `segment.endMs` | IntroDB intro/recap/outro; local heuristic trigger evidence | Verified IntroDB marker for the exact episode/timeline where applicable; local smart-credit result is fallback with separate evidence and manual-prompt policy. |

This dictionary is versioned and includes aliases, type/unit, null semantics, owner, context dimensions, selection policy and tests. Domain property names are concise; the paths above describe ownership, not a requirement for nested objects on every card.

Do not merge semantically different data simply because its display label is similar: stream release filename is not the work title; original language is not selected audio language; provider popularity is not a review rating; account sync time is not watch time. Different facts retain different canonical fields, while the *same* fact has only one.

### 7.2 Central field resolver

Keep a small deterministic resolver in the existing repository layer, backed by an explicit policy table. It chooses a value for a canonical field and read context—not one global winning provider for the whole title.

Decision order:

1. **Identity/context validation:** reject candidates for the wrong work, episode, file, language/region scope or account/provider generation.
2. **Validity and semantics:** reject wrong type/unit, malformed values, out-of-range values and artwork with the wrong role. A timestamp-only candidate cannot stand in for a date-only fact without a rule.
3. **Explicit user choice:** honor a relevant provider/metadata preference without letting it override actual decoder facts or account ownership.
4. **Field-specific authority:** use the policy for this field, not a blanket “TMDB always wins” rule.
5. **Context fit and freshness:** prefer language/region/edition fit; distinguish fresh, usable stale, unknown and explicit deletion. Define per-field whether fresh fallback outranks stale primary data.
6. **Quality/evidence:** evaluate completeness, image dimensions, verified/structured/inferred evidence and precision, as appropriate to the field.
7. **Deterministic tie-break:** use stable provider/candidate ordering or provider revision; network arrival order never decides.

The exact priority tuple is field-specific: language match has stronger importance for synopsis/logo than for a decoder-reported file size. Implement named policies, not one undocumented weighted score for every data type.

Example: Cinemeta returns a usable synopsis, a late TMDB response contains `overview = ""`, and another addon supplies a different series' text. Keep the Cinemeta synopsis: the empty TMDB value is not an update, and the wrong-identity addon value is rejected. If matching localized TMDB text arrives later, the selected synopsis can update without altering progress/artwork/focus.

Expose an opt-in diagnostic event such as `field=synopsis selectedSource=tmdb reason=language_match policyVersion=1`, with redacted identity correlation. Tests must assert both selected value and reason. Provider failures do not become crash diagnostics merely because a fallback was needed.

### 7.3 Efficient candidate storage and ownership

- There is one authoritative resolved value per semantic field/context. An artwork collection or multi-source ratings collection still uses one typed record schema, not provider-specific duplicate properties.
- Retain at most a small measured number of alternatives per field/section when they are needed for fallback; deduplicate equal values and evict unused raw payloads. Do not retain every response indefinitely.
- Start with typed section records and compact provenance maps. A normalized sparse candidate table is optional for fields that truly need independently persisted conflict/fallback evidence; do not introduce a universal SQL EAV engine.
- Cache a selected result by entity/context/policy revision so every Compose render does not re-run resolution across all providers.
- Re-resolve only affected fields when new evidence arrives, a value expires, a provider is disabled or the policy/context changes.
- Mutable user fields have one **local command-owned value** and an explicit sync merge policy. Providers supply remote observations; they do not participate in metadata-provider voting for watched status or resume position.
- Locale-specific resolution is not one global scalar overwritten whenever a different profile selects another language. It is one canonical localized field indexed by locale, with profile projections selecting their context.

### 7.4 Remove existing application-level duplicates too

The rule also applies to settings and locally derived data, not just external metadata:

- `seekTimeIntervalSeconds` and `seekThumbnailIntervalSeconds` become one `seekIntervalSeconds`; both seeking and thumbnail grids read it. Old columns are migration inputs, not independently mutable values.
- Where intro/outro countdown is intentionally shared, use one canonical `autoSkipCountdown` setting. Keep separate enable/mode fields only where behavior genuinely differs; manual skip availability and automatic skipping are not inherently the same fact.
- The shared ordered audio-language preference supplies both source-language ranking and audio-track selection. Subtitle-language preferences remain separate because they express a different choice. Legacy duplicated settings map through one migration rule with defined precedence when values disagree.
- Badges derive from canonical library/watched records. Do not synchronize a second `isWatchlisted`/`isWatched` boolean in each catalog DTO.
- Progress percentage derives from canonical position/duration. Display it in projections; do not save independently writable percentages on every card.
- Current/next episode derives from exact episode progress plus the authoritative episode list; cached projections have a derivation version, not competing editable truth.
- Artwork URLs formerly duplicated in library/history become references or explicitly bounded offline fallback stubs. A fallback stub is not a second metadata master.

Before deleting a duplicate, classify it as authoritative, computed, transport-only, compatibility input or recovery stub. That avoids deleting fields that look alike but represent different scopes or units. Add a static architecture check that prevents provider DTO imports outside adapters/temporary migration facades, plus regression tests for every legacy-to-canonical field mapping.

### 7.5 Common merge guarantees

1. Cinemeta/base metadata can render immediately. TMDB enrichment is supplemental and asynchronous.
2. Missing fields cannot erase usable known values unless a provider explicitly reports a authoritative deletion.
3. Partial network failures update request status, not known good title data.
4. A localized synopsis from the selected language can supersede a fallback synopsis; an English response must not erase a better matching localization.
5. A rating remains bound to its source/scale. Removing TMDB ratings from the UI does not require mislabeling or deleting the underlying fact.
6. Poster, backdrop and logo are separate asset roles. Landscape cards never substitute a portrait merely because `poster` is non-null.
7. Choose a stable card artwork pair per visibility generation. An offscreen refresh may prepare a better pair; focus alone must not swap it or resize the card.
8. Credentials/configuration revisions invalidate only their associated private results. They must not indiscriminately purge all reusable content.
9. User state always wins over provider display flags. Metadata refresh cannot add/remove watched/library membership.

Starting TTL proposals, to tune with traces and provider contracts:

| Data | Initial freshness policy |
|---|---|
| Verified aliases | Long-lived; revalidate on contradiction, version change or explicit repair |
| Title text/credits/artwork locators | 1–7 days, stale-while-revalidate |
| Ongoing series episode lists | 1–6 hours; stronger refresh near release dates |
| Catalog/recommendation membership | 15–60 minutes, country/language/taste revision in key |
| Identity not-found | 5–30 minutes, bounded negative cache |
| TorBox cached observations | Approximately existing 60-second TTL; bounded and credential-scoped |
| Stream candidates | Short profile/addon-config TTL; revalidate playable URLs on consumption |
| Resolved trailer URL | Provider expiry minus safety margin; no permanent URL persistence |
| Playback tracks/native handles | Session only |
| User state | No cache TTL; persists until explicit update/removal/account deletion |

The key includes every result-affecting dimension: namespace/media kind, language/region, field set, provider installation/configuration revision, and credential/account scope where applicable. Do not include the actual secret in keys, paths or logs.

## 8. Faster Home, cards, details and search

### Home and title cards

- Catalog pages store ordered title references. The same title in five rails has one reusable metadata record, not five authoritative copies.
- Build a windowed card query/projection; avoid loading every catalog page, full history and episode list into one screen state.
- Observe the relevant library/watched keys with batched queries. Avoid one network lookup or one database subscription per card.
- Prefetch metadata locators and sized image/logo requests for the visible viewport plus a small neighbor window. All visible cards initiate loading when visible, independently of focus.
- Reuse exactly the same image request keys/sizes/transforms in prefetch and render paths. Persistent encoded image bytes are not duplicated solely because display size differs unless a transformation requires it.
- Decode to rendered size; avoid retaining a 4K backdrop for a small card. Keep only nearest hero/backdrop assets in the hot working set.
- Batch metadata merges. A progress write should not rebuild all public metadata rows; one logo response should not recreate every rail list.
- Generate recommendations from a compact profile taste summary with a revision. Recompute on meaningful history/library changes, not every progress tick or D-pad move.
- Preserve mixed movie/series recommendations and centralized ranking. Do not collapse an entire mixed rail into one media type.

### Details

- Publish the cached/base card and Cinemeta details first; start supplemental enrichment in parallel.
- Request only needed TMDB sections; cast, filmography, collections and More Like This have separate loading/error states.
- Coalesce compatible TMDB subrequests where appropriate. TMDB supports `append_to_response`, but do not replace multiple small on-demand requests with one huge payload on every card. [TMDB append-to-response](https://developer.themoviedb.org/docs/append-to-response)
- A metadata failure cannot prevent Start Watching, episode selection, Back or resume.
- Do not eagerly fetch/enrich all seasons. Enrich the current/visible season, then neighbors if budget permits.
- Do not block the details screen on resolving every recommendation to IMDb. Resolve on background demand and reuse verified aliases.

### Search

- Keep the existing debounce/cancellation behavior. Query results are generation-scoped; old responses cannot alter a new query.
- Publish each useful provider batch as it arrives, with independent loading/error state and centralized identity deduplication.
- Cache bounded recent query membership, keyed by language/region/provider configuration. Do not persist the whole search corpus on a TV.
- TMDB-only results can render immediately using native IDs. They are not discarded merely because an IMDb mapping request is slow/missing; playback availability is a separate state.
- Use a consistent rail component and stable presentation keys. Paging may use the existing mechanism or Android Paging where it replaces custom plumbing without breaking fixed-anchor focus. [Android Paging overview](https://developer.android.com/topic/libraries/architecture/paging/v3-overview)

### Focus and animations

A schema helps remove data churn, but it does not itself fix focus geometry or animation rendering. Retain explicit focus anchors, separate selection from committed focus, and keep navigation state outside individual cards. Newly inserted rails must preserve a stable anchor or intentionally hand focus over according to a tested policy—not use raw row indices after insertion.

Measure frame timings rather than infer smoothness from animation durations. Android documents a roughly 16 ms frame budget at 60 Hz. [Android rendering performance](https://developer.android.com/topic/performance/issues/render)

## 9. Playback startup and runtime isolation

Create a small `PlaybackIntent`: account/profile context, exact playable key, resume reference, selected-source preference and configuration generation. It contains no giant details-page state.

Critical path:

```text
tap -> exact resume/source context -> usable source -> player prepare -> first frame
```

Not on that path: cast/crew, recommendations, artwork completion, whole-account snapshot generation, diagnostics export, thumbnail completion or ALASS alignment.

- Reuse valid trailer/source warmup for the exact movie/episode/profile/configuration. A series title source is not automatically the requested episode source.
- Return stream discovery incrementally. Wait only for the bounded evidence actually required by the user's ranking/filter policy; do not always wait for every addon timeout.
- Preserve resolution/language/size ranking and cached-source preference. Choosing the first arrival unconditionally could violate those policies. Define a small measured selection deadline and allow immediate start only when required evidence and remembered-source policy are satisfied.
- TorBox checks remain batched and enabled only when configured. A timeout is unknown, not uncached/zero seeders. Do not reinstate the removed seeder API calls.
- Resolve signed URLs immediately before consumption; expiry/failure can trigger one bounded refresh/fallback. Never replay a stale URL from cloud history.
- Embedded subtitle detection waits for actual track discovery. External lookup can run asynchronously, but embedded preferred tracks and remembered Off/selection have priority.
- Give playback transport/decoder work priority over artwork, enrichment, thumbnails and alignment; reserve capacity rather than allow all IO work to compete equally.
- Model the lifecycle explicitly: idle -> resolving -> preparing -> playing/paused -> ending -> released/error. Assign a session generation to every callback/job so results from the previous title cannot take control.
- Inline/fullscreen trailer is one session with a presentation-mode change, not a second extraction/player instance. Requested fullscreen promotion explicitly unmutes according to the existing behavior.

Under-two-second first frame is a target for a warm valid cached source on defined devices/network conditions, not a universal guarantee for DNS, slow addons, uncached torrents, codec initialization or external players.

### Thumbnails and smart next episode

Keep image decoding out of the canonical metadata database. Maintain a manifest with exact targets, capture timestamps, source/timeline identity, interval, dimensions/color pipeline and pipeline version.

- Preserve cached 720p/smallest-file then cached 1080p selection where TorBox is configured, without changing the selected playback stream.
- Preserve alternate lightweight sources; this proposal does not reinstate the removed same-file/verified-only restriction. Mark timeline compatibility as unknown when it is not demonstrated; do not promise scene equivalence across different cuts.
- Source/configuration change cancels obsolete workers and changes the manifest generation. No stale frame can satisfy a new generation's completion count.
- Completion is `validCapturedTargets == expectedTargets`, not rounded display percentage. A UI percentage can round to 100 before every frame exists; it is not a readiness gate.
- Smart fallback analyzes the final five minutes only after the entire current manifest is genuinely complete, retains the requested heuristic policy, and records a derived trigger one interval before the detected credit frame.
- Store the derived trigger independently of thumbnail files so 95%-completion cleanup cannot erase it. Keep it local and bound to the correct timeline/policy version.
- The final-five-minute analysis is a bounded low-priority job, not repeated every playback tick. IntroDB markers retain their priority and automatic-advance policy; fallback remains a manual prompt unless explicitly changed.
- Cached seek frames use stable image keys and a bounded decoded hot set; no repeated placeholder clearing/decoding merely because focus changed.

## 10. User-state commands and cross-device sync

Define business commands once and call them from every surface:

```text
AddToLibrary / RemoveFromLibrary
MarkWatched / UnmarkWatched
SavePlaybackCheckpoint / ClearProgress
SelectSource / SelectSubtitle / DisableSubtitles
ChangeProfilePreference / ChangeDevicePreference
InstallProvider / UpdateProviderConfiguration
```

Each command validates its context, changes local user state and appends the sync operation in one Room transaction. UI projections observe the commit immediately; cloud completion is not needed for a tick/bookmark to appear.

Watched/library/resume rules must be explicit:

- Watched state and library membership are independent records, with the requested rule that marking watched removes watchlist membership in the same transaction.
- Unmarking watched is an explicit operation, not a missing field that a later merge reintroduces.
- Manual mark/unmark and automatic progress completion have separate provenance. A later stale checkpoint must not undo an explicit decision.
- Continue Watching remembers the exact last episode/session and includes established zero-position playback; an unstarted auto-next transition must not overwrite it.
- Do not use maximum position as universal resume merge logic: intentional restart/replay can legitimately move backwards.
- Keep actual existing watch-policy behavior under regression tests before changing it. The current policy uses a 300,000 ms position floor and threshold/last-30-seconds/forced completion paths; this is not the same as measuring accumulated viewing time. A strict 95%-only policy or true watch-time requirement is a separate product decision.
- Series aggregate/manual watched state, episode completion and next-up are not interchangeable. Define series completion against the authoritative episode-list generation so future episodes do not erase manual intent accidentally.

### Improve the existing encrypted protocol, do not start over

The current v2 encrypted-component transfer is valuable. Evolve it in two steps:

1. Add a local durable outbox and dirty-entity capture. Serialize/hash changed entities only; keep full snapshots for explicit backup/migration, not every normal upload.
2. Version the transport for per-entity revisions, bounded delta fetches, deletion tombstones and checkpointed reconciliation. Keep authenticated encryption and account isolation.

Use stable portable profile IDs, not independently assigned integer IDs from different TVs. Keep local row IDs as implementation details.

Conflict handling:

- Compare base revisions/causal operations. Wall-clock timestamps alone are unsafe when TVs have incorrect clocks or delayed offline writes.
- Different entities merge independently: a watchlist change on one TV must not overwrite another TV's subtitle setting.
- Same-entity mutations use documented deterministic policies. Preserve concurrent progress/session records when necessary; do not silently discard offline activity by choosing a whole snapshot.
- Explicit clear/remove operations need tombstones until acknowledgement/retention rules make compaction safe. Old TVs must not resurrect deleted state.
- Retried commands are idempotent by operation ID. A lost response after server commit must not duplicate actions.
- Track ordering within each playback session; session sequence prevents an older pause/save callback from winning over a newer checkpoint.
- There is no algorithm that can recover the actual real-world order of two disconnected, clock-skewed user intentions without extra evidence. Keep the losing version for limited recovery and specify the tie policy rather than claiming perfect chronological merging.

Encrypted server payloads cannot be semantically merged by SQL without decryption. For the present no-custom-backend architecture, authenticated RPCs can enforce revisions/size/ownership; clients decrypt, merge and retry. Design the wire format accordingly. Do not pretend RLS performs business conflict resolution.

Sync on meaningful events: established playback pause/exit/end/source/track changes, watched/library/configuration edits, profile changes, foreground catch-up and explicit sync. Batch routine position checkpoints using the existing cadence as a starting point; do not send each frame or idle fixed-frequency whole snapshots. Persist the outbox before UI acknowledgement and resume it after process death. Cross-device accuracy still has an offline/network freshness limit.

Device configuration remains device-local: 4K answer, hardware decoder/tunneling overrides, local active-navigation state, download paths and local diagnostic toggles. Profile language/theme/library/progress/integration configuration sync according to their defined scope. Integration/add-on credentials sync only inside the end-to-end encrypted account snapshot; they are never sent as plaintext or in the v3 operation envelope. Explicitly selected audio/subtitle preferences also sync through that encrypted snapshot; a target device should fall back safely if a saved track identifier is unavailable or differs. Ephemeral access sessions and signed media URLs do not sync.

## 11. Memory, storage and scheduling budgets

Use one installation-level budget policy plus account-scope isolation. A cap on each account's cache is not a cap on total installation storage.

Initial tuning targets—not measured safe limits for every Fire TV:

| Resource | Starting proposal |
|---|---|
| Hot card/domain projections | Visible rows plus neighbors; roughly 100–200 lightweight records, not thousands of full titles |
| Metadata RAM caches | Byte-aware budget around 4–12 MiB on constrained devices; bound large credits/episode collections separately |
| Decoded artwork RAM | Implemented initial caps: 20/32/48 MiB for low/constrained/standard devices; playback-time trim behavior and percentage tuning still require device measurements |
| Persisted content metadata | Initial soft target 20–40 MiB, indexed and evictable except referenced identity stubs |
| Encoded artwork | Implemented initial caps: 60/80/100 MiB by device tier |
| HTTP metadata | Implemented initial caps: 15/20/30 MiB by device tier |
| Thumbnail files | Implemented initial caps: 150/200/250 MiB by device tier; trim is installation-wide and startup migration removes legacy cache trees |
| Diagnostics/export scratch | Implemented separately: 6 MiB persistent total across app/health/playback diagnostics, 2 MiB export scratch, 7-day retention |
| Combined managed artwork/HTTP/thumbnail disk | Implemented initial caps: 225/300/380 MiB by tier. Diagnostics has its own explicit cap and remains outside these regenerable cache totals. |

User databases and unsent outbox operations are not evictable caches. Under storage pressure, sacrifice regenerable artwork/old thumbnails first. A long active thumbnail job can exceed the proposed pool; detect that condition rather than endlessly evicting frames it still needs to reach completion. Either provide an explicitly budgeted pinned working set or report unavailable capacity. Never delete durable progress to meet a cache target.

Decoded memory arithmetic explains the need for sizing: a 3840×2160 four-byte bitmap is approximately 31.6 MiB; a 640×360 bitmap is approximately 0.88 MiB, before object/GPU/decoder overhead. Retaining many full-resolution assets overwhelms a TV even when their compressed files are small.

Implement bounded queues/concurrency, not just bounded result caches. Use per-priority/provider limits and cancel obsolete request generations. One network request per identical key should serve multiple consumers; cancelling one consumer should not accidentally cancel work still needed by another.

The client now applies this rule to TMDB Home/details enrichment, episodes, recommendations, collections, person details, trailer/video reads, entity catalogs, natural-query discovery and IMDb/TMDB alias resolution. Identical in-flight keys share the same request; screen cancellation only cancels that screen's await, while the process-owned metadata load can finish for remaining consumers. The metadata routes share a four-flight semaphore; alias resolution has a separate two-flight limit. Pending-key registries are bounded (96 keys per typed route), and focused tests cover request sharing, cancellation isolation, shared concurrency limits, and retry after failure. Over-capacity callers wait on the shared semaphore but are not retained in the single-flight registry; tune this behavior with real request traces before increasing caps.

Observe Java heap, native decoder/worker memory, GPU allocations and both process RSS/PSS. Main-process heap alone misses the cost of the isolated thumbnail worker. Keep native frame queues bounded, use existing resource ownership/release boundaries, and shed optional work on memory pressure.

Room index priorities: external alias uniqueness; catalog key/generation/rank; profile/playable progress; profile/title library; series/episode ordering; outbox status/sequence. Batch writes, query narrow columns, inspect query plans, and avoid whole-history reads for a single-title badge. Do not perform blanket database vacuuming or destructive cache clearing on every launch.

## 12. What millions of users change

Local Room is per device; it does not become a million-user server database. The fleet-scale problem is provider traffic, authentication/sync abuse, metadata rights, supportability and rollout reliability.

### Traffic example, not a forecast

Assume **1 million daily active users**, two Home opens/day and 20 metadata requests/open:

| Scenario | Upstream requests/day | Average requests/second | Illustrative 10× peak |
|---|---:|---:|---:|
| No reusable fleet cache | 40,000,000 | 463 | 4,630 |
| 98% shared cache hit | 800,000 | 9.3 | 93 |
| 99.5% shared cache hit | 200,000 | 2.3 | 23 |

These percentages are assumptions, not promised outcomes. Localization, long-tail titles, miss bursts and concurrent refreshes affect them. Local cache hits across devices are not automatically shared cache hits.

TMDB currently describes protective upper limits around 40 requests/second, explicitly subject to change, and requires handling 429 responses. Do not interpret that as a contractual allowance for this app, or assume its exact key/IP scope. Agree an operating model with the provider. [TMDB rate limits](https://developer.themoviedb.org/docs/rate-limiting)

TMDB also distinguishes commercial/noncommercial use, requires attribution and offers no general API SLA. Confirm commercial/data-image redistribution terms before a shared cache/CDN rollout. [TMDB API FAQ](https://developer.themoviedb.org/docs/faq)

### Recommended scale stage

- A small managed metadata gateway/cache for approved public metadata, not a video proxy. Start as one deployable service/function plus durable cache, not a microservice platform.
- Coalesce popular content fetches across devices; cache versioned metadata/card bundles by language/region. Bound negative caching and use stale data during upstream incidents.
- CDN/object storage for owned/authorized assets and custom avatars/hub images. Do not mirror third-party artwork without permission; direct provider CDN delivery may remain the correct choice.
- Keep private addon URLs, user TorBox keys and account-specific availability out of a public shared cache. Debrid/source calls remain account-scoped.
- Keep encrypted mutable account state separate from public metadata. Neon can continue as its store after load/cost/RLS testing. A Data API with JWT/RLS supplies access control, not fleet-level product SLOs or semantic synchronization by itself. [Neon Data API overview announcement](https://neon.com/blog/a-postgrest-compatible-data-api-now-on-neon)
- Add abuse controls: per-account/device write quotas, bounded RPC arguments, retention/TTL cleanup, pairing capabilities/replay protection, rate limits and operational alarms.
- Do not proxy video, trailers or thumbnail binaries through Postgres. Their bandwidth dwarfs metadata; playback should remain direct to its authorized source.
- Replace critical reliance on undocumented endpoints with a licensed/stable integration or a feature that can degrade gracefully. JustWatch's present endpoint belongs behind a capability/kill switch.

Keeping the earlier no-custom-backend requirement is feasible for the on-device migration. If it remains permanent, shared metadata traffic control and app-owned API-key protection are limited; provider-supported distribution and quotas become a prerequisite. This plan does not authorize deploying a gateway or changing the production database.

### User-state capacity example

At 1 million active accounts, 20 changed entities/day and an average 0.75 KiB encrypted envelope, uploads alone are approximately 14.3 GiB/day before headers/retries/manifests. Two receiving devices could add approximately 28.6 GiB/day if each consumes every change. Real progress checkpoints can make the event count substantially higher.

At 200 KiB retained user state/account, 1 million accounts need approximately 191 GiB of logical payload before indexes, encrypted-object overhead, replication/backups and custom images. Small per-user savings matter; a 35 MB total today is not a useful forecast without row/asset/TOAST/index measurement.

Measure operation sizes, changes per viewing hour, manifest growth and fan-out. Set cost budgets per active account/device and test peak concurrency, not just database storage. No precise Neon cost or transfer reduction is claimed without current billed metrics and a load test.

## 13. Security and reliability requirements

- Credentials use references and Keystore-backed protection; preserve encrypted cloud transport. Never log tokens, passwords, configured addon URLs with secrets, signed media URLs or decrypted user payloads.
- Password recovery is intentionally deferred by product choice. The current loss-of-password limitation must remain explicit before consumer-scale rollout; a later recovery path needs a recoverable Neon Auth session and a tested encrypted-data-key rewrap, not just a client-side code.
- Enforce account ownership at every server function/table, payload limits and schema versions. Test RLS against two accounts and anonymous callers in an approved isolated environment.
- User-added addon endpoints are untrusted. Validate manifests/responses, limit redirects/payloads, require secure transport by default, and make LAN/self-hosted HTTP exceptions explicit—not a blanket trust switch.
- Any future backend adapter must protect against SSRF/private-address access. Do not blindly fetch user-supplied URLs from a server.
- Quarantine malformed provider fields/records instead of crashing the whole response. Cancellation is not a failure; propagate it consistently, including existing broad-catch paths.
- Keep last good content while a provider is down. Circuit-break repeated failure per provider/credential context with bounded backoff/jitter; do not cache transient failure as empty success.
- Pin/version schema and adapter contracts. Additive unknown fields are tolerated; required-field incompatibilities are explicit.
- Distinguish UI loading, provider unavailability, handled network exception and uncaught crash. Basic/detailed logs remain opt-in with crash/exception capture as already requested.
- Use redacted correlation/session IDs to connect intent, provider request, merge, prepare, first frame, checkpoint and sync acknowledgement.
- Feature-specific kill switches need signed/trusted configuration or release flags. They cannot silently disable core user-data durability.
- Sign/verify update APKs and preserve ABI/signing-key compatibility. Architecture rollout must not repeat update/install regressions.

## 14. Migration sequence and acceptance gates

No big-bang rewrite. Keep old UI/features functional behind a compatibility mapper while each vertical slice moves.

| Phase | Work | Exit criterion |
|---|---|---|
| 0 — Baseline | Capture current latency/jank/memory/request/sync measurements; record feature invariants and production schema compatibility. | Repeatable release-build traces on low-end 32-bit TV and other supported tiers. |
| 1 — Identity and commands | Typed keys, alias store, movie/series-safe mapping keys, portable profile IDs, shared watched/library/progress commands and outbox seam. | Same-title aliases show identical badges/actions; exact episode resume survives process/profile/device changes. |
| 2 — Content/artwork vertical slice | Persist base metadata/artwork roles; wrap Cinemeta/TMDB; move Home card projection and Details fast path. | No focus-triggered logo lookup; no portrait/backdrop swapping; offline/warm metadata usable; unchanged features. |
| 3 — Catalog/search adoption | Catalog membership/cursors, windowed projections, recommendation generations, shared Search/Library/More Like This/filmography cards. | Stable focus with inserted rails/pagination; mixed media; all quick actions reflect immediate canonical state. |
| 4 — Playback/runtime | Incremental sources, exact PlaybackIntent, source fingerprints, subtitle policy and worker manifests. | Warm-start improvement demonstrated; no slow-addon barrier; Off disables ALASS; source changes reject stale callbacks. |
| 5 — Sync v3 | Dirty-entity capture, per-entity revisions/deletions, bounded delta/checkpoint transport, approved server migration. | Concurrent two-TV edits converge without whole-account overwrite; process/offline recovery and isolation validated. |
| 6 — Resource and fleet readiness | Global budgets, expanded performance profiles, provider agreements/optional gateway, abuse/cost/load tests. | Device soak and staged fleet tests meet agreed budgets/SLOs; rollback rehearsed. |

Phases can overlap only where dependencies are clear. Identity precedes migrating library/progress; sync wire-format migration follows stable entity semantics. The scale gateway is not required to prove the first on-device gains.

Implementation rules:

- Add new Room tables/migrations without deleting the existing account. Backfill lazily and idempotently; keep explicit migration checkpoints.
- Account snapshots use a versioned, explicit column contract for account-owned tables; canonical caches and the sync outbox are excluded. A Room schema change to an exported table must bump the snapshot schema and add an import mapping. Restore checks the current table shape and fails closed if the mapping was not updated; never fall back to `SELECT *` as the wire contract.
- Old-client compatibility is a product gate. Existing cloud v2 already has rollback limitations; do not assume an older APK can read a new manifest. Use version negotiation, refusal of incompatible writes and a tested recovery build.
- Shadow-compare old/new identity/projection results before switching reads. During transition there is exactly one authoritative writer per entity; unmanaged dual writes are not a migration strategy.
- Do not destructively downgrade Room or restore production snapshots from a newer schema into an older binary. Keep an encrypted compatible export/recovery path and roll forward when rollback is unsafe.
- Only remove old caches/mappers after the new vertical slice is validated; measure APK/dependency impact separately.

## 15. Required regression and performance tests

### Schema/adapter/identity

- Same numeric TMDB ID in movie and series namespaces; multiple addons with identical opaque IDs.
- IMDb/TMDB alias promotion while focused, watchlisted, watched, playing and synchronizing; redirects never loop or duplicate user state.
- Missing/null/wrong-type fields, unknown content types, oversized JSON, pagination duplicates, malformed language/URL, expired negative cache.
- Out-of-order partial Cinemeta/TMDB responses, localized fallback, missing logos/posters, rating source identity and explicit deletion.
- Cancellation and timeout do not overwrite last-good content or clear usable sources.
- Context changes while requests are in flight: account, profile, provider credentials, language, device and source.

### User actions/sync

- Mark/unmark watched and add/remove library from every title surface with immediate matching badges; Continue Watching tick suppression.
- S2E5 and other exact episode identities; start/pause at zero, end of season, unstarted next episode, unknown duration and replay backwards.
- Atomic local write/outbox; process termination before/after commit/ack; duplicated delivery; checkpoint recovery; bounded retry.
- Concurrent two-TV edits to different fields/entities; same-entity conflicts; clock skew; offline stale replay; deletion tombstones; old-client schema refusal.
- Hardware overrides remain local while language/theme/addons/source/subtitles/progress synchronize.
- Tampered ciphertext, wrong account/object binding, oversized/decompression payloads and two-account RLS isolation.

### UI/playback/device

- Home/Search/Library/Details use equivalent card projections and focus geometry in poster/landscape modes.
- TMDB rail insertion cannot destroy focus; artwork completion cannot resize/move the focus anchor.
- Details is usable while TMDB is delayed/offline; Start Watching does not await supplemental data.
- One slow stream addon cannot block a known eligible source indefinitely; ranking rules still apply.
- Inline -> fullscreen preserves session/position and unmutes; Back restores focus; feature jobs cannot reopen the menu unexpectedly.
- Subtitle Off cancels ALASS and remains Off after source/device changes.
- Thumbnail intervals/edition scope/captured PTS, true full-cache gate, eviction after trigger, source switch, worker death and retry.
- Real hardware decoder/native memory behavior, HDR colors, tunneled/non-tunneled modes and Fire TV remote/launcher behavior.

### Benchmarks and rollout

Extend the existing Macrobenchmark/Baseline Profile journeys instead of duplicating infrastructure. [Android Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)

Benchmark cold/warm Home, visible artwork readiness, repeated horizontal/vertical D-pad navigation, delayed TMDB rails, details first usable state, search first useful results, warm/cold source selection, first frame, seek and multi-hour playback. Include real low-end 32-bit Fire TV, HD/non-4K and 4K devices; emulator timings do not prove TV decoder/memory performance.

Use offline fixtures and an injected clock for deterministic CI. Add provider contract checks outside the core deterministic suite. Keep existing coverage gates; new critical identity/merge/sync policies should have full feasible line/branch coverage plus scenario assertions. Coverage percentage alone cannot guarantee correctness or smoothness.

## 16. Proposed success measures

Targets must be confirmed after Phase 0; these are not current measurements or promises of a 10× improvement.

| Metric | Initial acceptance target |
|---|---|
| Local card/progress/library query | p95 under 50 ms on the defined low-end tier |
| Cached details first usable state | p95 under 250 ms, excluding cold app startup |
| Metadata calls on simple focus movement | Zero when the visible window is already prepared |
| Warm valid cached-source first frame | p95 under 2 s on specified device/network/codec cases; report cold separately |
| Repeated navigation | Under 1% missed-deadline frames in benchmark journey; no frame/focus mismatch introduced by data updates |
| Stability | Target at least 99.9% crash-free sessions, separately track ANRs, native worker crashes and failed play attempts |
| Memory | No upward steady-state trend in 30-minute browse/2-hour playback soak; remain below measured tier-specific process limits |
| Durable user data | No lost acknowledged local command in process/network failure tests; explicit offline/unacknowledged status |
| Cross-device state | Small urgent mutations applied promptly on a connected second TV; measure p95 and document foreground/offline limitations |
| Transfer/storage | Changed-entity bytes only on normal sync; bounded manifests and per-account cost budget from measured viewing-hour events |
| Cache benefit | Publish warm/cold hit rates, duplicate-request count and device/fleet scope separately |

Release gates compare against the baseline: no regression in existing playback/resume/menu/update journeys, verified schema compatibility, and demonstrated improvements before wider rollout. Start with internal devices, then a small opted-in cohort; a GitHub APK release by itself does not provide a controlled fleet rollout or telemetry.

## 17. Implementation batches and current position

Implemented locally in the current working tree: portable profile IDs and profile-state commands; command paths for library/watched/progress and profile deletion; encrypted Room outbox plus sync-v3 negotiation/protocol/engine for those entity types and bounded profile runtime snapshots; typed canonical content identity, verified alias/cache tables, Cinemeta episode-list cache, TMDB/Cinemeta canonical metadata adapters/caches, and the PlaybackIntent handoff. A source-selection v3 command synchronizes only a validated torrent info-hash for movie/series identity; it excludes signed URLs, filenames and file indexes. Add-on runtime configuration can sync through the encrypted v3 outbox when it fits bounds; integration credentials and selected audio/subtitle preferences remain in the end-to-end encrypted v2 compatibility snapshot; device 4K capability remains local.

Implementation status and remaining completion gates:

1. **Identity/content — client code migration implemented locally:** Typed canonical projections and identity-qualified join/cache keys cover metadata adapters, Search (`TitleCardProjection`), My Library (`LibraryCardProjection`), Details display content, Home catalog/hero rows (`CategoryRow`), TMDB preview mapping, shared card display, Home enrichment, visible-row keys, Search focus, Details/cast enrichment, watched-alias work, remembered-source reads and inline-trailer anchors. `SaabTvCard` accepts `TitleCardProjection`, used by TMDB recommendations and filmography. Room schema 65 persists indexed canonical identity keys beside legacy history/library/next-up IDs, portable profile-owner keys on runtime addon/catalog/hub rows, and bounded Details-only TMDB credits/age ratings. Snapshot restore recomputes identity indexes and remaps schema-62 runtime rows to a portable profile. Verified alias promotion rekeys indexes transactionally. Watched/watchlist badges and Details resume/progress/next-up reads resolve verified aliases. Source-selection alias reads have dedicated movie/episode regressions. Remaining `MetaItem` payloads are intentional provider/action/episode compatibility boundaries; opaque IDs stay exact. Provider-ID primary keys are retained to preserve exact episode resume and addon ownership. Do not merge addon identities without verified provider origin. Room 63→64 and 64→65 release migrations remain rollout gates.
2. **User-state commands:** Profile settings, library/watched/progress/next-up, Trakt mutations and portable torrent-source selection use the command/outbox path. The app-side call-site audit found UI/Trakt writes wrapped in command transactions and v3 DAO writes inside the remote-apply transaction. New v3 outbox rows are encrypted locally and for transport; legacy rows are upgraded before retry. Integration credentials and explicit audio/subtitle preferences sync through the end-to-end encrypted v2 compatibility snapshot (wire schema 63); integration keys and profile settings are mapped through portable profile identities. Bounded add-on/runtime configuration can use v3. Ephemeral sessions and signed URLs remain device/session-local. The 4K capability stays on each device. The confirmed watched threshold remains 95%, preserving existing minimum-position and end-of-content completion behavior. Old-client compatibility/fallback transitions still need live validation.
3. **Playback/runtime — core guards implemented:** `PlaybackIntent` carries exact title/playable identity, portable profile, resume position, source fingerprint, settings generation and session ID into playback startup. Backend load tokens reject stale source resolution, fallback, retry, and subtitle callbacks; screen-owned work is keyed/cancelled, and the isolated thumbnail worker has request-keyed generation ownership and cancellation. Deterministic adapter tests cover late magnet callbacks and ALASS Off/manual cancellation. Remaining validation is on real decoders/devices: episode transition/resume, thumbnail worker lifecycle under buffering/seeking, memory pressure, and long playback; emulator success is not hardware proof.
4. **Sync v3:** Local protocol, encrypted outbox and deployed SQL use idempotent operation IDs, account isolation, tombstones and a Lamport-style per-entity revision. A client advances its local revision beyond the largest remote revision it has applied, so a causally later edit wins; concurrent/offline edits that have not observed one another use deterministic `(entity_revision, device_id)` arbitration. A regression now verifies a local edit after remote revision 7 is published as revision 8 and wins. Migrations `005_sync_v3_state.sql` and `006_sync_v3_abuse_limits.sql` are applied to the production branch. Postflight confirmed version 3, the private quota table, wrapper grants, owner-only internal RPCs, revoked direct writes, read access, forced RLS, and preservation of v2 snapshot data. No snapshots were deleted. Deterministic multi-database/fake-transport tests cover account scoping, legacy outbox encryption upgrade, acknowledgement loss, concurrent edits, profile deletion/recreation and portable identity; live two-TV testing and older-client coexistence remain release gates. This is causal ordering only after observing a revision, not recovery of true wall-clock order between concurrent offline edits. Startup avoids unnecessary compatibility snapshots, but fallback snapshots remain during migration.
5. **Server/device validation:** Neon migrations 005 and 006 are applied to production. Migration 006 enforces 30 pushes and 25 MiB of push bodies per account/minute, 12 v3 pulls/minute, and a 20 MiB per-request v3 push cap. Postflight verified wrapper/internal grants, direct-write denial, read access, forced v3 RLS, and preserved v2 snapshot state. `cloud/neon/smoke-test.rb` has a default-safe disposable-account guard plus opt-in checks for account isolation, direct-write denial, HTTP 429/PT429, aggregate byte limits and HTTP 413/PT413. Only its Ruby syntax and no-network safety guards have been checked locally; actual quota boundaries and high-transfer probes were not run. A client pass now stops after four 4 MiB batches and continues backlog on the batched cadence, limiting one TV to 16 MiB/pass. This leaves some room under the per-account cap, but cannot guarantee aggregate use when multiple TVs sync concurrently; the server remains the enforcement point and 429 handling is tested. HTTP 413 payloads are deferred until an explicit retry or new local mutation; orchestration and bounded-drain regressions cover these paths. Current JVM and API 36 emulator suites pass, but functional live push/pull, account-isolation and two-physical-TV conflict tests remain open. Physical Fire TV/32-bit device validation and release/ABI install/upgrade tests remain unverified. These require authenticated devices and a controlled test account; they cannot be proven by local deterministic tests.
6. **Production readiness:** Initial device-tier cache caps and installation-wide thumbnail eviction are implemented (low/constrained/standard managed pools: 225/300/380 MiB), but 32-bit/4K playback soak, memory/storage/network benchmarks, fleet SLOs, provider quotas and operational monitoring are not established. JustWatch now uses one serialized refresh without holding its cache lock over network I/O, preserves last-good rows, stops retrying permanent/schema errors, honors bounded numeric `Retry-After` for 429s, and opens a five-minute provider circuit after repeat transport failure; deterministic tests cover concurrent refresh and fail-closed recovery. This is an automatic circuit breaker, not a remote operator kill switch. Measure and tune these limits before claiming million-user readiness.
7. **Request coalescing:** All principal TMDB metadata entry points now use cancellation-isolated single-flight loads under shared concurrency limits. Nullable not-found results do not trigger a duplicate fallback request, failed flights are removed so retries work, and over-capacity callers still obey the shared semaphore. Profile deletion also removes that profile's unsent child mutations before appending the higher-revision deletion tombstone, preventing the same device from uploading already-superseded mutations afterward. Focused and full tests pass; cross-device stale-writer behavior and capacity tuning remain sync/device acceptance work.
8. **Diagnostics resource bound:** App, health and isolated playback/thumbnail-worker diagnostics now share an inter-process lock, enforce 6 MiB of persistent history and 2 MiB of export scratch, prune after seven days, bound export volume, and sanitize session path components. Active playback sessions use newest child-file timestamps for expiry. Focused tests cover aggregate caps, retention, active-session retention and path traversal; the full JVM/emulator suite passes. Hardware tuning remains open.

This tackles both correctness and speed while preserving the app's established features. Entity-level sync and fleet infrastructure follow once identities and ownership are dependable. Adding more thumbnail workers, more prefetch, a generic event bus or a giant shared JSON schema before this groundwork would increase resource usage without solving the consistency problem.

## 18. Decisions and remaining operating policy

Confirmed for this implementation: account-owned profiles, settings, library/watched/progress state, integrations/add-ons including credentials, and explicit audio/subtitle preferences sync across devices; credentials and track preferences stay end-to-end encrypted while using the v2 compatibility snapshot (schema 63). The per-device 4K answer and hardware overrides stay local. Preserve the current 95% watched threshold and existing completion exceptions. Keep the v2 compatibility path during migration; set a minimum supported client and retirement criteria only after rollout/adoption can be measured. Password recovery is explicitly deferred: the current synthetic `.invalid` email identity cannot receive Neon Auth reset mail, and a client-only recovery key cannot restore an Auth session or change the Neon-managed password. Do not expose a recovery-key UI until a verified email or approved recovery service and a tested data-key rewrap flow are in place.

Still open: tune constrained-device resource budgets after real baselines; decide whether a managed metadata gateway is acceptable later (none is included now); confirm commercial/provider usage; validate the deployed Room migrations, Neon quota behavior, two-device sync and physical-TV playback; and revisit account recovery only if its necessary recovery channel is approved. These are explicit rollout/policy gates, not work that can be safely closed by emulator-only evidence.

**Bottom line:** a shared canonical contract is feasible and worthwhile, but the payoff comes from typed identity, one local truth per entity, lightweight projections, durable commands, incremental synchronization and bounded work—not simply making all integrations return the same oversized object.
