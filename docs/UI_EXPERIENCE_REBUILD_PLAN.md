# Saab TV: presentation-layer rebuild

Status: implementation in progress; verified checkpoints and remaining acceptance gates are tracked in `UI_FEATURE_PARITY_LEDGER.md`.
Date: 2026-10-14. Baseline: local 0.1.125-beta source.

Expanded customer-journey audit, source findings, work packages and acceptance plan: `CUSTOMER_DELIGHT_UI_AUDIT_AND_ROADMAP.md`. Use that companion for detailed experience requirements.

## 1. Scope and decision

Rebuild the UI from shared primitives upward, retaining the canonical information schema, repositories, account encryption/sync, addon/integration support, stream selection, playback backends, thumbnail generation and user data. Rebuild their presentation, not their functionality. No database migration is required merely to redesign screens.

Use Kotlin/Compose and the installed TV components. Do not introduce a web UI, another navigation framework, an animation dependency, or a second content cache. The goal is a coherent Saab TV identity with cinematic discovery and predictable remote control, not a literal reproduction of another service.

Replace every screen through complete journeys. Keep the old renderer available during development, using a local development switch, until each replacement passes parity tests. Never ship two independently evolving feature implementations. Delete the retired renderer after acceptance.

## 2. Findings from source inspection

These are structural findings and performance hypotheses, not measurements of a physical TV:

- `HomeScreen.kt` is 1,879 lines and `InfiniteLoopRow.kt` is 1,201 lines. The latter implements linear, infinite-grid and finite-grid paths. Their size is not itself a performance defect, but multiple interaction paths make consistent motion and focus harder to enforce.
- `DetailsScreen.kt` is 1,484 lines and coordinates content, resume, focus restoration, trailers and several related-content layouts. UI replacement must not change title/episode identity when invoking playback.
- Search obtains a `HomeViewModel` for enrichment, watched state and previews. Screen presentation is coupled to another screen's orchestration; expose the existing underlying data through Search's presentation state instead.
- MainActivity contains separate top/side navigation rendering branches and many screen-level focus requesters. Shared navigation behavior should not be implemented twice.
- Mobile and TV Material themes coexist. This is valid, but separate default colors, sizing and text roles permit inconsistent controls. Shared Saab controls need explicit semantic tokens for both.
- Poster, landscape, related-content and search layouts own separate sizing/focus decisions. One geometry contract must drive image, border, stack, hit area and trailer anchor.
- Recent fixes already reduce redraw cost and repeated metadata transitions. Keep these protections; a visual rebuild must not reintroduce image changes on focus or broad screen recomposition.
- Existing benchmarks cover startup and a short navigation sequence. They do not yet establish smoothness for sustained browsing, rail insertion, trailers, search or seeking on low-end hardware.

## 3. Visual system

Direction: cinematic, quiet and precise. Near-black neutral surfaces, white text and focus, restrained Saab blue for selected state/progress. Artwork supplies color; the entire interface must not change theme on every card movement.

Create semantic tokens for background, raised surface, scrim, primary/secondary/disabled text, focus, accent, success and destructive action. No arbitrary black text on bright/unknown surfaces. Verify normal, focused, selected, disabled and error states independently.

Starting layout values, to validate on real TVs rather than treat as final pixels:

| Element | Initial design rule |
| --- | --- |
| Spacing | 4dp base; 8/12dp within controls; 16/24dp between groups |
| Safe area | 32–48dp horizontal, adjusted for display and navigation |
| Poster | 2:3, starting 120 × 180dp |
| Landscape | 16:9, starting 208 × 117dp |
| Card corners | 10–12dp; image and border use the identical shape |
| Focus | 2dp white outline, no zoom, bounce or animated blur |
| Series stack | Two quiet rear layers inside a reserved decoration envelope |
| Typography | One existing font family with a small set of TV-specific roles |

Movie and series front cards have identical dimensions in each mode. Rear layers do not enlarge the focus rectangle or change row height. Poster/landscape preference applies everywhere; Continue Watching may retain its existing separate preference. Landscape cards use landscape artwork plus logo; missing artwork uses initials, and missing logo falls back to readable text. Do not duplicate titles already present as an overlay unless necessary for accessibility.

Use fixed logo bounds, title line limits and reserved metadata areas. Metadata updates cannot resize the card. Watched and watchlist badges share placement across all surfaces; Continue Watching retains its no-watched-tick rule. Descriptions belong in the preview/details region, not on every card.

## 4. Navigation and motion contract

One app shell owns current destination, navigation expansion and restoration. Keep both supported navigation placements, but render the same destination model and invoke the same actions. Side navigation expands as an overlay, not by repeatedly remeasuring the content page. Navigation selection and remote focus are distinct states.

- Left at the shelf boundary explicitly enters navigation. Up/down within a shelf region never opens it accidentally.
- Dismissing an action/dialog restores its originating card by canonical identity, not a stale index or detached requester.
- Back closes the topmost surface first: dialog/quick actions, trailer fullscreen, inline preview, screen, then navigation/root behavior. Each event has one owner and one transition.
- Inline-to-fullscreen changes the existing trailer session's presentation and unmutes it; it does not resolve/recreate playback.
- Focus remains in the current destination after any action removes its button. Choose the nearest valid sibling before considering the app navigation.
- A late rail must not steal focus. Preserve the logical item and viewport. Queue insertion above the active shelf until navigation settles or the user returns toward that location; make newly available content reachable normally.

Horizontal browsing: one fixed leading focus anchor and one interruptible, critically damped scroll. No simultaneous manual scroll and bring-into-view animator. At the first/last item, clamp to valid content bounds and allow an edge focus position rather than creating blank space to force the anchor. Avoid fake infinite duplication as the default browsing model.

Vertical browsing: align the selected shelf to a shared vertical anchor while the page moves. Match horizontal and vertical motion character, not necessarily identical duration. Keep row headings and previews in reserved geometry so vertical navigation does not repeatedly expand/collapse the page.

Initial motion tuning: 80–120ms feedback for control state, approximately 180–260ms typical shelf travel, 180–240ms page/backdrop transition, 120–160ms small overlay transition. Spring travel is distance/input-dependent, not a fixed-duration promise. Rapid D-pad input retargets current motion; never queue animations. Coalesce backdrop changes during key repeat and retain the last image until the replacement is ready. Reduced-motion mode removes travel/parallax but retains visible focus.

No marquee during navigation, autoplay carousel movement while browsing, card zoom, heavy glow, real-time blurred card stacks or large overlapping animated surfaces. Use at most two full-screen artwork layers during a backdrop transition and release the outgoing layer afterward.

## 5. Screen and journey design

### Home, Movies and Series

Use one browse shell: stable preview above a vertically scrolling set of shared shelves. Continue Watching is easy to reach. Top Suggestions is one mixed movie/series shelf; favorite genres, New for You and Popular in Your Taste use the same components and existing recommendation data. Show a concise reason where already supported, not invented match percentages. Preserve user rail ordering/hiding and provider catalogs.

Late recommendations fill reserved stable sections or wait for safe insertion. Their absence must not block browsing. View All uses the same aspect ratio as its shelf and opens a fast filterable hub with restoration to the originating item. No change to recommendation algorithms is implied by this UI-only plan.

### Search

A dedicated search workspace: compact keyboard, persistent query, clear results region, remote-paste access, recent searches and existing supported query filters. Preserve natural-language search and provider/addon results. Use a short debounced query; cancel stale requests and never let an older response replace a newer query. Keep existing results visible while updating and distinguish empty results from provider failure.

Results use the same card/shelf motion as Home. Preserve movie/series grouping and expose filters without burying the keyboard. Back returns from results to query editing before leaving search. No autoplay trailers in Search; this preserves the current explicit requirement. Do not invent voice search availability where platform support is absent.

### Details, episodes, people and collections

Display cached/Cinemeta basics immediately; TMDB enrichment fills fixed slots afterward without blocking primary actions. Do not require every logo/biography/related shelf before displaying the title. Hero: logo/title, concise metadata, short synopsis, primary Play/Resume, library state and secondary actions. Clear episode selection with seasons, watched/progress state and correct next episode.

All related/collection/filmography cards use the same actions and trailer settings as catalog cards. Only the details title's own trailer follows the details fullscreen/unmuted policy. Selected related-title actions must retain that related title's canonical identity through preparation and playback.

### My Library

Clear tabs/filters for All, Watchlist, In Progress and Watched; movie/series filters and existing sort choices. Uniform card geometry and badges. Mark watched removes watchlist membership; mark unwatched is reversible. State changes update visible cards immediately from the existing canonical user state. Preserve focused identity when a card disappears from the current filter.

### Trailers and watching

One preview host, one active trailer session. Inline overlay is landscape, anchored to its title, with always-visible accessible controls. Fullscreen presents consistent Play, Watchlist and Mute controls and restores focus on Back. No autoplay on Continue Watching or Search. Respect existing delay/mode settings; details autoplay still requires inactivity and must stop when feature playback begins.

Continue Watching enters playback directly using the existing preparation path; do not route through a visible details page. Present honest loading state on the player surface if source/network work remains. Player controls stay quiet and legible, with stable seek geometry, retained cached thumbnails, compact skip/next buttons and frame-driven progress. UI changes must not alter buffering, track selection, resume, credits detection or the 95% watched threshold.

### Settings, account and diagnostics

One control family for buttons, credential fields, dropdowns, toggles, sliders and dialogs. Explicit minimum control height; scrollable dialog bodies with non-clipped action footers. Use short section summaries and existing meaningful grouping. Field focus surrounds the input, not helper text. Keep remote paste accessible for every supported integration credential. Diagnostics uses a bounded virtualized event list that remains fully navigable and scrollable.

## 6. Implementation structure

Keep this small rather than introduce a generic UI engine:

1. Design tokens and Saab control wrappers using installed Compose/TV primitives.
2. One title-card component with explicit poster/landscape geometry and optional progress/series decorations.
3. One shelf interaction implementation for all card shelves; a grid variant reuses cards/actions/restoration, not horizontal scrolling internals.
4. One app shell for navigation/back ownership and one trailer host for preview presentation.
5. Screen-specific immutable presentation state assembled by existing ViewModels/repositories. Cards receive compact artwork, badge and identity values, not entire mutable provider objects.

Use canonical IDs for list keys, commands and restoration; keep provider IDs at existing adapter boundaries. UI event handlers emit explicit actions such as openDetails(id), resume(id), playEpisode(id), toggleWatchlist(id), markWatched(id). Reuse existing commands; do not create another persistence layer or recommendation implementation.

## 7. Perceived speed and actual rendering

- Keep lazy composition: composing the entire library eagerly would worsen RAM/startup. Eagerly prepare visible artwork requests and a bounded next-screen lookahead instead.
- Resolve image/logo URLs before focus; prefetch and displayed requests use identical role, size and cache identity. Do not change the source image just because focus moved.
- Retain displayed artwork during refresh; use initials only for genuinely absent/failed artwork, not as a mandatory flash before each cached image.
- Publish narrow immutable state. Playback ticks, sync progress and thumbnail updates must not invalidate the browse page.
- Do not sort catalogs, resolve artwork, perform database work, decode images or build large lists in composables or on a D-pad callback.
- Bound preview decoding and image lookahead on low-memory devices. Pause offscreen work; do not keep visited pages or decoded full-size artwork alive indefinitely.
- Adapt decoration based on measured device limits, not whether the panel is 4K. Preserve the same interactions when expensive visual effects are disabled.
- Generate release Baseline Profiles for real browse/search/details/player journeys. Measure optimized release builds rather than infer performance from debug/emulator animations.

## 8. Migration and acceptance gates

| Phase | Deliverable | Gate before moving on |
| --- | --- | --- |
| 0 | [Feature/setting parity ledger](UI_FEATURE_PARITY_LEDGER.md); recorded current journeys and physical-device performance baseline | Every existing destination, preference and action mapped to replacement |
| 1 | Tokens, controls, cards, geometry and motion test screen | Poster/landscape parity, readable controls, matching focus bounds, repeat-key stress tests |
| 2 | New shell plus Home vertical slice | Browse → quick action → details → Back restores identity; no focus theft during rail insertion |
| 3 | Movies/Series, hubs, Search and Library | Same motion everywhere; stale queries and live watched/watchlist updates tested |
| 4 | Details, episodes, collections/people and trailer presentation | Correct title/episode launch; inline/fullscreen/Back/audio tests pass |
| 5 | Player controls, settings, onboarding/account and diagnostics | Feature parity, remote navigation, overflow, long text and accessibility pass |
| 6 | Full migration, release profiling and old UI deletion | Regression suite green, release-device metrics accepted, no duplicate render paths |

Targets below are acceptance goals, not current measurements or promises:

- On 60Hz hardware, use the 16.67ms frame deadline; report frame-overrun percentiles and jank, not average FPS alone. Aim for <1% missed deadlines on the reference TV and <3% on the lowest supported reference device during scripted browse journeys.
- Input-to-visible focus acknowledgement p95 <50ms under normal browsing. Motion may continue afterward; do not delay input feedback until artwork arrives.
- Cached title details/return navigation shows useful content within 300ms p95 on reference hardware. Measure cold/network-dependent journeys separately.
- Repeated browsing, opening/closing trailers and returning from playback must show no unbounded retained-memory growth. Establish numeric memory budgets after phase-0 measurement on the weakest supported TV.
- No missing focus, detached focus exceptions, wrong-title playback, unintended menu expansion or loss of user state in the regression journeys.

Automated checks: geometry and command unit tests; Compose interaction tests for every card context; screenshots for all component states and long/localized text; device D-pad repeat/Back/overlay tests; release Macrobenchmarks for horizontal/vertical shelves, delayed rail insertion, search, details, trailer promotion and player seek. Include missing images, delayed enrichment, offline/partial provider failure and repeated profile switches. Use synthetic fixture catalogs so network variability does not hide UI regressions, plus separate real-network smoke tests.

Line coverage is not proof of smoothness. Measure frame timing on physical low-end Fire TV/Android TV and at least one 4K device. Do not claim 10× speed or sub-two-second stream startup from a UI rewrite; network, source resolution and decoding remain outside this scope.

## 9. References

- Android TV immersive-list guidance: https://developer.android.com/design/ui/tv/guides/components/immersive-list
- Android TV navigation drawers: https://developer.android.com/design/ui/tv/guides/components/navigation-drawer
- Compose performance best practices: https://developer.android.com/develop/ui/compose/performance/bestpractices
- Baseline Profiles for Compose: https://developer.android.com/develop/ui/compose/performance/baseline-profiles

The motion values, geometry, migration choices and acceptance targets above are Saab TV proposals, not specifications copied from these references.
