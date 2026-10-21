# Saab TV — customer-delight-first experience audit and rebuild roadmap

Audit baseline: 2026-10-14, local 0.1.125-beta. Deep-journey expansion: 2026-10-15.
Status: audit and implementation specification. Local implementation has started; see the checkpoint under §11. No production behavior is changed by this document itself.

This expands `UI_EXPERIENCE_REBUILD_PLAN.md`. Where this document is more specific, use this document for the customer journeys and acceptance gates. Values are initial design/measurement targets, not claims of achieved performance.

## 1. The product promise

**Find something you want to watch, start it confidently, and return exactly where you left off—with almost no effort.**

Premium is not the number of animations. It is the absence of hesitation, surprise, repetition and recovery work. Content should feel rich; interaction should feel simple. Fast browsing that launches the wrong episode is a failed experience. Beautiful trailers that hijack the remote are a failed experience. A sparse interface that hides important functionality is also a failed experience.

Correct title/profile/episode/action, safe Back behavior and user-data preservation are hard constraints; no visual treatment may weaken them. Within those constraints, use this presentation priority order when designs conflict:

1. Fluid, premium motion that makes the interface feel responsive and connected.
2. Obvious focus, predictable Back and immediate acknowledgement.
3. Useful content quickly, with honest loading and failure states.
4. Consistent layout and artwork quality, with expressive polish inside the TV's frame and memory budget.

Motion is the highest-priority polish requirement in this UI plan: customers should perceive the interface as fast because focus and content move continuously, predictably and without jitter. Treat it as a first-class experience requirement, not decoration to add after the screens are built. It must reinforce focus, continuity and cause-and-effect without delaying an action or hiding where the remote is aimed. Correctness and input acknowledgement remain hard constraints: a visually smooth wrong-title transition is still a failure.

The rebuild concerns the presentation layer. Playback/network/cache/sync defects discovered along a journey must be recorded and fixed in their proper subsystem, not concealed by animation. Backend algorithm changes, new cloud analytics and account-policy changes require separate scope/approval.

## 2. Evidence, confidence and limitations

The evidence is a source audit plus the user's reported experience across earlier releases. This is not a recorded usability study, a fresh physical-TV profiling session or proof that every historical bug still exists. Large files identify maintenance concentration, not automatically slow execution. Existing fixes and tests must be retained until replacements demonstrate equivalent behavior.

Evidence labels used below:

- **Observed:** directly visible in the inspected source.
- **Reported:** customer history; needs reproduction against the current build.
- **Hypothesis:** likely customer/performance consequence; validate before claiming cause.
- **Proposal:** desired behavior, not implemented behavior.

### Source audit register

| ID | Evidence | Customer consequence / risk | Response |
| --- | --- | --- | --- |
| A01 | Observed: `MainActivity.kt` contains top/side navigation branches, screen orchestration and focus requesters; 2,757 lines | Equivalent journeys can acquire different navigation/restoration behavior | One shell and one navigation contract; retain both placement preferences |
| A02 | Observed: `InfiniteLoopRow.kt` has separate linear/infinite-grid/finite-grid render paths | Focus geometry, card decoration and movement can diverge | Shared card contract and shelf interaction; grid shares commands/restoration, not an inappropriate horizontal animator |
| A03 | Observed: `HomeScreen.kt`/`DetailsScreen.kt` combine enrichment, previews, actions and restoration | Small feature changes have a broad interaction blast radius | Presentation state and explicit command boundaries; test journeys, not only isolated widgets |
| A04 | Observed: Search clears its result lists on each edit in `SearchViewModel.onQueryChange`; 250ms debounce, three-character minimum | Results can blink away while typing; short exact-title queries cannot search | Distinguish editing/loading/results; retain old visuals safely, handle short explicit-submit queries if providers support them |
| A05 | Observed: Search runs catalog and TMDB requests concurrently and publishes the first completed result | Already provides progressive results; a rewrite could accidentally restore all-provider waiting | Preserve concurrency, cancellation and partial publishing; add visible non-blocking partial status |
| A06 | Observed: Search and `TitleTrailerHost` depend on Home's ViewModel for shared behavior | Different screens are coupled through a screen-level coordinator | Reuse underlying repositories/commands; give each screen only the presentation state it needs |
| A07 | Historical observation, no longer current: `CatalogQuickActionsPopup` previously waited before focusing/arming. Current source arms actions immediately, requests initial focus after one frame, and keeps watched/watchlist mutations disabled until profile membership is known; the card owns long-press release handling. | Focus should feel immediate without letting the held-center release activate the newly opened popup. | Preserve immediate focus, authoritative state gating, and held-key safety; regressions cover action readiness, initial focus, and poster/landscape release behavior. |
| A08 | Observed: personalized Home rows use `personalizedRailOrder` to appear after catalogs, expressly to prevent index/focus disruption | Safeguard protects browsing but can bury the recommendations the customer expects to see | Identity-based restoration and a stable recommendation region, not inserting moving rows in front of active focus |
| A09 | Observed: recommendation seeds are the three most recent watched/in-progress titles plus a bounded watchlist contribution | Useful bounded implementation, but not evidence of whole-history personalization or a calibrated taste score | Describe suggestions honestly; distinguish presentation improvements from a future ranking project |
| A10 | Observed: onboarding presents first/second/third audio language on separate steps; `ProfileScreen` routes steps 6–8 to those screens | High setup effort before the first useful screen | One primary-language choice plus optional expanded priorities; preserve advanced preferences and copy-profile path |
| A11 | Observed: account entry performs prepare/sync before opening and has an account-error/offline path | Authentication, cloud restore and local readiness can look like one opaque wait | Distinguish stages and permitted local continuation; never bypass account isolation or unlock requirements |
| A12 | Observed: mobile/TV Material themes coexist; focused scales still exist in settings subtitle chips/slider buttons | Home's no-zoom motion is not a universal interaction contract | Semantic control states and shared motion, including settings; no blanket assumption that mixed Material itself is faulty |
| A13 | Observed: `SettingsSubScreens.kt` uses implementation-oriented thumbnail/credits copy; one description mentions OCR while the latest customer requirement is image heuristics | Settings may be confusing or inaccurate even when the underlying feature works | User-oriented copy audited against actual active policy, including fallback/manual-vs-auto behavior |
| A14 | Observed: Library derives observed state from history, watchlist and next-up and canonicalizes projections | Good foundation for immediate shared badge updates | Reuse it; do not create a parallel UI-owned membership database |
| A15 | Observed: trailer policy already excludes Search/Continue Watching hover and forces the details-title default fullscreen/unmuted | Established user requirements are easy to lose during redesign | Explicit context matrix; preserve policies rather than apply one blanket trailer behavior |
| A16 | Observed: diagnostics already uses a LazyColumn and distinguishes handled request failures from crashes | Existing improvements should survive replacement | Preserve virtualized rendering and severity semantics; improve discovery and reading without relabeling all errors as crashes |
| A17 | Observed: baseline-profile/startup benchmark navigates a short path, not the full discovery-to-watching flow | Passing it does not prove all screens are fluid | Expand release benchmarks to actual critical journeys; physical-device measurements required |
| A18 | Reported: previous oversized/misaligned rings, late logos, focus jumps, wrong-title launches, resume mismatch and repeated trailer regressions | Trust debt: customers compensate by pressing again or avoiding features | Dedicated regression fixtures and acceptance gates for each failure class, even if currently fixed |
| A19 | Observed: manual profile creation traverses name, avatar, theme, optional copy/manual choice, then three separate audio-language screens (`ProfileScreen` / `ProfileViewModel`) | A sequence of style/preferences decisions precedes the first useful Home; D-pad interaction multiplies the cost | Make only truly required profile inputs blocking; offer a coherent primary-language step with optional priorities and defer avatar/theme without deleting those features |
| A20 | Observed: `AccountEntryActivity.prepare()` awaits `AccountSyncManager.prepareBeforeOpeningApp()` before `openApp()`; the device-only 4K/HD prompt is shown from `openApp()` if not configured | Even a local capability choice can sit behind remote account preparation; whether this is perceptible is unmeasured | Separate device-local setup timing from account unlock/restore. Change ordering only if encrypted-account isolation and migration preconditions remain intact; measure both paths first |
| A21 | Observed: Home shelves use a fixed leading focus indicator and `FocusPivotSpec`, while profile carousels use a separate center-pivot spring | Different motion systems can feel like separate products; a different composition may still be intentional for profile selection | Inventory focusable regions; unify only where the interaction contract is the same, then validate remote traversal |
| A22 | Observed: focus-indicator visibility was read by the Home row while conditionally composing its overlay | A focus move can invalidate more of the shelf composition than the indicator needs; source inspection alone cannot quantify frame cost | Keep the indicator state in the smallest draw scope; verify with recomposition/frame traces rather than claim a measured speedup |

## 3. Customer modes, not imaginary demographics

These are design hypotheses to validate with users, not researched personas:

| Mode | Actual job | Friction to remove | Delight moment |
| --- | --- | --- | --- |
| Returning viewer | Continue last night's episode | Hunting for the right season/position; waiting through details | First visible resume tile leads to the correct episode and timestamp |
| Intentional searcher | Find a known title/person | Remote typing, stale results, duplicate titles | A few characters or phone paste surfaces the right title with year/type |
| Undecided viewer | Choose something suitable tonight | Repetitive rails, weak context, too much synopsis | One credible suggestion explains why it belongs without a fake percentage |
| Series follower | Pick a specific episode or continue next | Wrong default episode, ambiguous watched state | Season/episode/progress is unmistakable before pressing Play |
| Household member | Use their own profile and preferences | Other people's progress or preferences leaking into the session | Their Library and languages are immediately recognizable |
| Power user | Configure providers and playback precisely | Deep menus, typing secrets, technical terms without consequences | Phone-assisted setup, validation, clear settings ownership |
| Constrained-device viewer | Browse/watch on a low-memory TV | Long waits, jank, decoder contention | Same predictable interaction with reduced decoration, not reduced functionality |

## 4. Experience scorecard

Measure success as successful tasks, not longer browsing or more autoplay. The north-star measure is **successful viewing intent completion**: a user intentionally chooses a title/episode and it reaches first frame without wrong-title navigation, abandonment or error. Do not equate an automatic trailer with successful playback.

Supporting measures:

- Time to useful Home: first screen with correctly identified, actionable content—not merely splash dismissal.
- Time to confident decision: from browsing/search intent to intentional Play; compare with task success so faster accidental clicks do not look better.
- Time to first focus feedback, first useful result, usable details and feature first frame, separately.
- Correct-resume rate, Back-restoration success and wrong-title launch count.
- Search task completion, reformulation count and empty-vs-error clarity.
- Input presses and unexpected backtracking per scripted journey.
- Jank/frame overruns and retained-memory trend during the same journeys.
- Setup completion and points where users need help; one-question perceived-ease feedback in moderated tests.

Start with local benchmark/test exports and voluntary moderated observation. Any production telemetry must be explicitly designed for privacy and approved; do not silently upload queries, title history, profile identity, API keys, tokens, screenshot contents or provider URLs.

## 5. End-to-end service blueprint

Each journey must specify five things: customer intent, visible response, allowed input, data dependency and recovery. The UI owns presentation and input; existing domain services own truth.

### Customer-delight lens: the first session is one promise, not a screen tour

The customer does not experience “Home,” “Search,” and “Details” as separate features. They experience one question: *Can I find the right thing, start it, and trust Saab TV to remember?* Design and test the handoffs in order. Every handoff carries the same selected profile and canonical title identity; no page is allowed to reinterpret intent silently.

| Customer moment | What the person needs to feel | UI promise | Evidence that earns the promise |
| --- | --- | --- | --- |
| App tile → first frame | Recognized, not interrogated | Returning installations return to their useful destination; first launch has one clear next action | Cold launch and update-over-install traces; no repeated setup for returning accounts |
| First useful content | Welcomed, not abandoned | Show something usable as soon as its data is ready; explain what is unavailable and why | Delayed/offline provider fixture still shows cached content and truthful status |
| First choice | Oriented, not overwhelmed | Clear focus, readable title/year/type, sensible number of rails and one primary action | Remote-only browse task, press count and wrong-turn record |
| First commitment to Play | Confident, not tricked | Preview, source preparation and feature playback are visibly distinct; Play keeps the chosen title/episode | Typed identity assertion from every launch context; same-session trailer handoff test |
| First return/finish | Remembered, not reset | Resume, completion, watchlist and watched state agree across screens and later sessions | Process recreation plus account/device fixture; state parity matrix |
| First problem | In control, not blamed | Say what failed, what is safe, and the least destructive next action | Fault-injected recovery walkthrough; preserved query/progress/credentials assertions |

#### Time-ordered first-session blueprint

These are design checkpoints, not latency claims. Baseline each on the weakest supported TV before setting absolute timing goals. The clock starts when the customer launches the app and uses only one input device (remote, except explicit phone-paste entry).

| Window / stage | Customer-facing sequence | Backstage handoff to preserve | Delight test / exit condition |
| --- | --- | --- | --- |
| 0–10s: arrive | Show a coherent launch state and the first actionable focus. Returning viewer is not sent through an intro. | Process restore → account/profile classification → local UI state | Focus is visible and actionable; no blank screen waiting on optional TMDB/provider work |
| 10–30s: orient | Identify active profile and destinations without a modal tour. If setup blocks playback, name only that blocker and offer setup; otherwise let browsing begin. | Account unlock/local readiness/cloud freshness are separate states | Customer can explain where they are and what pressing Center does; no implicit sign-out/data reset |
| 30–90s: discover | Continue Watching and useful catalog choices arrive progressively; recommendation emptiness is not disguised as a loading rail. Search is obvious. | Cached catalog → provider result → personalization/enrichment, each independently cancelable | A delayed request never clears already useful content or relocates focus |
| 1–3m: decide | Browse, search or use a suggestion; distinguish remakes and movie/series; use phone paste when remote typing is painful. | Query/session token → concurrent provider/TMDB responses → canonical projection | No stale result can be activated under a newer query; no unsupported “match” or availability claim |
| 2–4m: inspect | Details shows sufficient known facts immediately; related sections fill reserved space. Explicit episode choice stays selected. | Canonical details → progressive enrichment → typed action payload | Play remains reachable during enrichment; More Like This/Collection/Filmography retain their own identity |
| 3–5m: start | User sees one honest transition to the player; can still cancel/retry if source resolution fails. | Preview teardown/handoff → source resolution → prepare → first rendered frame | Instrument intent-to-first-frame as separate intervals; no wrong-title play or second resolve from trailer promotion |
| 5m onward: watch | Controls appear on request, seek thumbnails are truthful, subtitles and skip controls obey preferences. | Playback clock → progress/episode state → local store/outbox | Pause/seek/episode boundary saves correct position; playback ticks do not invalidate browse UI |
| Leave / return | Back restores the exact context; later Home/Library shows the same progress and membership. | Local transaction → sync queue/acknowledgement → other device projection | User can distinguish “saved here” from “synced”; no success copy before the owning store confirms it |

#### First-session branch map — design for the account/device state the customer actually has

“First experience” is not synonymous with a first-ever installation. The same person may be updating an existing TV, signing in on a second TV, recovering after a failed cloud read, or selecting another household profile. The entry UI should classify the path silently from trustworthy state and explain a choice only when the customer must make one. Do not collapse these paths into one onboarding wizard.

| Entry condition | Current customer-facing steps observed in the app | Delight-first target sequence | Non-negotiable safety / likely friction | End-to-end acceptance evidence |
| --- | --- | --- | --- | --- |
| Fresh install, not authenticated | Sign in / Create Account entry; remote and phone-assisted forms exist. Successful auth reaches device-display setup if not configured. | Explain the one required account decision; make sign-in/create-account, password visibility and phone input obvious; then ask this TV’s display capability once and continue to profile setup. | Keep existing account requirement/policy; no credential in logs; avoid making users configure optional providers before they can understand the app. | New-install test starts with no preferences, completes account flow with D-pad only, reaches a named profile and an understandable next action; secrets absent from diagnostics/export. |
| Fresh install, authenticated session restored | Session validation and account preparation happen before Main; display setup is offered when device-local capability is unset. | Show a short, truthful “preparing account” state; ask only this device’s display question; restore profile choices without re-running account signup. | Authenticated, encrypted data unlocked, local-ready and cloud-fresh are different states. Never treat a valid session as proof ciphertext was decrypted. | Seed valid session + encrypted snapshot + no device capability; assert one capability prompt, same account/profile state, and no duplicate onboarding. |
| Returning install after app update | Session validation, account preparation and threshold upgrade precede Main; failed preparation may permit local use only under existing policy. | Keep the known identity and local state; do required safe migrations; distinguish “opening local data” from remote freshness without a blocking technical dump. | Update must not silently sign out, erase/reinitialize local data, or repeatedly return to a generic unlock screen. Do not weaken encryption to improve perceived speed. | Upgrade-over-install fixture with watched/progress/settings/addons/integrations; inject transient network, 429/5xx and decrypt failure separately; assert preserved rows and correct recovery actions. |
| Existing account on a new TV | Sign in/prepare; prompt for that device’s 4K/HD choice; then profile selection and possibly profile initialization. | Make it obvious that display capability belongs to this TV; restore account-owned preferences/state; let the customer select an existing profile before asking for optional tuning. | Do not copy the other TV’s device capability as a profile preference. Sync and restored profile choice must not leak another household profile. | Two-device fixture with different display capabilities; compare library, progress, portable preferences and local-only settings; switch profiles without cross-profile cards flashing. |
| Multiple household profiles on an established TV | “Who Is Watching?” profile choice; locked profile PIN; profile options and add flow. First-time profile may offer copying settings or manual setup. | Default focus to the most likely valid profile only if identity policy supports it; otherwise make selection unmistakable. On add, explain what copying includes and keep the existing source profile unchanged. | No profile data briefly shown before selection; no accidental edit/delete from Center hold; PIN errors preserve selection. | Profile isolation test alternates profiles during late catalog/TMDB results and preview callbacks; every visible row and pending action remains scoped to the selected profile. |
| Network, restore or unlock failure | Error path can show Try Again, Continue Offline when permitted, and Sign Out; friendly errors vary by failure class. | Name the failing stage, state what is safe, preserve the current session/context and focus Retry only when retry is plausible. Offer local continuation only when the account policy permits it. | Socket timeout, server rate limit, authentication failure, migration failure and AEAD authentication failure are not interchangeable. Sign Out is never an accidental/default action. | Fault-injection matrix asserts exact user-facing category, allowed buttons, local data preservation, session preservation/clearance, retry idempotence and focus order. |

#### Deep audit: customer intent, backstage work and the “earned delight” proof

“First experience” means the first successful *job*, not just first installation. For a returning viewer the job is “continue the right episode”; for a new viewer it may be “find one thing I can confidently watch.” The first-session design is complete only when the customer-facing promise is backed by the actual subsystem that owns the truth. Avoid making the UI feel faster by hiding a wait, showing guessed data, or prematurely claiming a save.

| Journey moment | What the customer is trying to accomplish / likely uncertainty | Frontstage response that earns trust | Backstage work and owner to inspect | Failure that creates disproportionate effort | Delight proof / acceptance signal |
| --- | --- | --- | --- | --- | --- |
| Open / update | “Did it remember me? Is my library still safe?” | Return directly to the last useful account/profile context; one clear first focus; explain a real blocking step in ordinary language | Entry routing, encrypted-data unlock, schema migration, profile restoration and local/cloud readiness are separate facts | Update loops to sign-in/unlock, blank screen, repeated onboarding, or an error that suggests data was erased | Upgrade-over-existing-install reaches a useful state; compare local rows before/after; cold and warm launch traces; no secrets or unrelated profile visible |
| Choose a profile / device | “Am I in my space, and which settings follow me?” | Make selected profile unmistakable; distinguish profile-owned settings from this-TV settings without asking the same question twice | Account/profile scope, device-local display capability, track preference and preference hydration | Another profile’s cards briefly render; device choice leaks to another TV; setup asks many optional questions before browse | Alternate two profiles during delayed responses and process recreation; no cross-scope flash; setup can defer optional choices |
| Reach useful Home | “Is there anything here for me, or is the app stuck?” | Show the first valid cached/provider content progressively; keep Search and navigation available; say when a shelf is genuinely empty or unavailable | Home state, provider catalog requests, canonical identity/art projection, TMDB enrichment, image loader/cache | Home waits for all sources, a blank/repeated skeleton persists, or late rails move the selected item | Delayed/failing provider fixture still exposes a useful path; measure first actionable card and active-focus stability separately from full enrichment |
| Scan and move | “Did the remote move where I intended?” | Immediate, spatially predictable focus feedback; selected card moves into a stable visual anchor; Back has one owner | Remote repeat cadence, focus address, lazy-list attachment, scroll owner, animation retargeting and row mutations | Focus jumps, ring detaches, menu opens unexpectedly, queued motion continues after input stops | Script single/repeat/held-key/rapid vertical+horizontal input; assert focused canonical item, bounded settling, no duplicate activation on release |
| Decide what to watch | “Why this title, and is it the right film/series?” | Show title/logo, type/year, a concise reason/context and only verified availability; let user compare recommendations without opening many dead ends | Suggestion seed/ranking, deduplication across aliases, title-kind resolution and availability truth | One content kind disappears, same-name remake is mistaken, fake match score or unplayable state is implied | Mixed movie/series and duplicate/remake fixtures; moderated task: customer can explain why suggested and identify correct title without coaching |
| Search | “Can I say what I mean with this remote?” | Persistent query; visible progress/results distinction; preserve useful prior results as non-activatable while updating; show filters/interpretation only when supported | Input latency, request cancellation/token, catalog+TMDB partial responses, natural-language interpretation and result merge | Empty flash on every key, stale result opens under new query, short title cannot be submitted, partial outage looks like no match | Out-of-order response and paste fixtures; test exact, short, multilingual, interpreted and ordinary title queries; record first-useful-result time and reformulations |
| Inspect details | “Do I know enough to press Play?” | Useful provider/Cinemeta facts first, TMDB additions later; primary action remains available; episode context and progress are explicit | Canonical title projection, metadata precedence, enrichment scheduling, episode selection and identity-preserving action model | Page waits on optional metadata; play for a related card becomes parent title; selected S2E5 resets to S1E1 | Delayed/failed enrichment screenshots plus command-payload assertions for hero, More Like This, Collection, Cast and Filmography |
| Preview / commit | “Can I sample it and then watch this exact thing?” | Preview is optional and dismissible; fullscreen is explicit; one clear Start Watching action; returning Back restores the origin | Trailer session, preview policy by context, player-surface promotion and captured play intent | Fullscreen restarts/ends preview, wrong title starts, trailer steals focus or resurrects over feature playback | Assert one session ID/media timeline across promotion; test every origin, cancellation timing and exact child-card identity |
| Start / watch | “Is it starting, or should I do something?” | Enter player once; stage-specific, cancellable progress; controls and subtitle state are predictable; do not show fake percentage | Source resolution → URL validity → backend prepare → buffering → first decoded/rendered frame; thumbnail generation is separate | “Source found” is mistaken for playing; spinner has no action; buffering repeats; seek image/time disagree | Report each latency interval and failure class; fake-backend UI tests plus release-device traces; first frame (not resolved source) ends startup measure |
| Pause / leave / finish | “Will it keep the right place and know what I finished?” | Acknowledge local save accurately; watched, watchlist, resume and next-episode actions agree everywhere; preserve current 95% rule | Playback progress writer, episode boundary policy, canonical state commands and sync outbox | Position moves to wrong episode, completion removes the wrong item, or UI says synced before acknowledgement | Pause/seek/episode end/process-death fixtures; assert one state projection in all surfaces and explicit local-vs-cloud acknowledgement |
| Return / second TV | “Is this the same household state?” | Continue locally where allowed; quietly reconcile; present a choice only for a genuine unresolved conflict | Sync v3 merge, encrypted snapshot, profile-owned state, portable preferences, device-only settings and track-ID fallback | Ordinary stale state blocks app use; local-newer work is discarded; copied setting is invalid on new device | Two-device fixture for local-newer/local-older/concurrent/offline states; compare library, settings, episode/time and device-local fields |
| Configure / recover | “Can I fix this without becoming support staff?” | Group related settings; labels describe effect; input/paste, validation, diagnostics and recovery are reachable by remote | Settings ownership, credential vault, command outcome, diagnostics taxonomy and export redaction | Failed replacement deletes working key; network error called a crash; footer clipped; retry repeats unchanged failure | Full D-pad path, long/localized copy and font-size tests; verify old credentials survive failed replacement and exported logs contain no secrets |

##### Customer effort budget and delight signals

The experience should minimize *unnecessary* customer decisions before the first successful intentional play—not eliminate security prompts or advanced controls. During formative tests, classify each decision as required, optional-but-safe-to-defer, or avoidable. The expected default journey has no feature tour, no forced provider configuration if browsing is already possible, no repeated profile/device question, and no modal confirmation for ordinary reversible actions. Count remote presses and detours as diagnostics, not as the sole success metric.

For each journey report four distinct clocks: (1) input-to-visible acknowledgement, (2) time to useful content, (3) time to confident decision, and (4) time to successful first frame. Report p50/p95 plus task success and wrong-action rates by entry path and device tier. A faster median that increases wrong-title starts, repeated presses, or abandonment is a regression. Passive trailer time does not count as customer progress.

The strongest delight signals are concrete and testable: the right profile is already selected; the desired title is recognized; a card is immediately actionable; Play retains the selected episode; a cached preview never flashes away; Back returns to the exact place; and a later device has the same user-owned library/progress without copying hardware-specific choices. Use small formative user sessions to discover which promise matters most before tuning decorative motion.

##### First-session invariant carried across every surface

Treat the user's intent as a small, immutable handoff record assembled from existing domain models (do not add a competing persistence or event framework): account/profile scope; canonical title identity and kind; optional season/episode/resume target; originating destination plus shelf/filter/item restoration address; requested action; and a request/session generation that invalidates stale async callbacks. Every screen may enrich presentation, but only the appropriate domain owner may change identity or durable state. On Back, restore the origin if it remains valid; otherwise fall back to the nearest meaningful item in the same profile/destination. Tests must assert this payload at the boundary, not infer correctness from what the screenshot happens to show.

#### The first ten minutes: customer effort budget and observability

The goal is not “fewest screens at any cost.” It is **fewest unnecessary decisions before the first successful, intentional viewing task**, with security and profile correctness preserved. Count required decisions separately from optional decisions; every optional decision must have a safe default and be deferrable.

| Moment | Customer effort budget | What the UI must acknowledge | What to measure in a test (not hidden telemetry) |
| --- | --- | --- | --- |
| Launch and first focus | No introductory tour on a return; one obvious first focus | “Your account/profile is being prepared” only while a real blocking step is active | Launch→first input-ready frame; first focus visibility; blank-frame duration; focus target identity |
| Account and device readiness | Ask only for an account when required, then this device’s display capability if absent | Separate authentication, local decrypt/unlock, local readiness and cloud freshness | Screen/press count per branch; account-stage duration; retry count; exact failure class; preservation checks |
| Profile and preferences | Choose/create profile; collapse audio preferences into one primary choice with optional priorities; defer styling | “These settings belong to your profile” vs “this TV” | Required-vs-optional choices; time to skip optional setup; accidental profile edit/delete; selected profile continuity |
| First useful browse | Search and one or more honest content paths remain available even if personalization is empty | “Not set up,” “offline,” “no matches” and “still loading” have distinct visible meanings | Time to actionable result (not first skeleton); populated-card ratio; useful rows before slow network settles; focus changes during arrival |
| First decision | Title, year/type, episode context and actual known availability are enough to decide | TMDB enrichment is supplemental; a missing logo is not a missing title | Decision task success; detail open/back press count; wrong-title/episode action count; stale result activation |
| First Play | One explicit action produces one playback intent | Preview, source resolution, prepare/buffer and first frame are separate stages | Intent→resolver, resolver→prepared, prepared→first-frame; cancel/retry behavior; wrong-title rate; p50/p95 by device class |
| First return | Correct progress and Library state persist | “Saved on this TV” vs “Synced” only reflects actual storage acknowledgements | Resume identity/position after process death, account switch and second device; Back restoration; pending-sync age where testable |

Use fixed fixtures for empty-provider, one-provider, mixed movie/series, exact-title search, short title, remake, missing-art, slow TMDB, no playable source, one playable source, active trailer, episode boundary and two-TV account cases. Seed screenshots are supplementary: assertions must also cover action identity, focus address, profile scope and persisted state. The scorecard in §4 should be reported by path (new install, returning update, second device, recovery), device tier and network condition; a single blended average hides the failure a returning customer actually experiences.

#### Handoff contract (frontstage and backstage)

| Handoff | Frontstage owner | Backstage truth owner | Must never happen | Recovery that keeps customer effort low |
| --- | --- | --- | --- | --- |
| Update → account entry | App shell / account entry | Auth, local encryption and migration | Turn an upgrade into data loss or a sign-in loop | Preserve the local copy; identify unlock vs network vs migration failure; retry without repeating prior steps |
| Account → Home | Home shell | Profile scope, catalog repositories, sync freshness | Leak another profile or block Home on optional enrichment | Show local usable state under current security policy; communicate sync separately |
| Shelf → title action | Card/shelf | Canonical identity and command handler | Route a related title through its parent details item | Include canonical title/kind/episode in the action; preserve selection on Back |
| Search → result → details | Search session | Query token, provider cancellation, canonical merge | Let an older query replace or launch under the latest query | Keep query visible, show partial/error state, allow retry/edit without losing intent |
| Details → trailer → Play | One preview host | Trailer session/player and play-intent coordinator | Recreate the trailer, restart it on promotion or hand off the parent title | Back returns to the origin; Start Watching submits the captured card identity once |
| Title/episode → feature player | Player destination | Source resolver, backend prepare and first-frame renderer | Conflate “source found” with “playback started” | Keep a cancellable player surface with truthful stage; retry source without erasing chosen episode |
| Player → user state | Player progress owner | History/library repository and sync outbox | Save the wrong episode at season/series boundary | Commit one canonical progress event; show local save first and cloud acknowledgement only when confirmed |
| Settings → integration/account | Settings shell | Credential vault, validation, account/sync services | Lose a known-good credential on failed replacement or log a secret | Keep old credential until replacement validates; masked fields, phone paste and specific non-secret errors |
| Failure → diagnostics/support | Error surface | Error taxonomy and diagnostic privacy policy | Treat every I/O error as a crash or export secrets | Explain handled failure vs crash; preview/redact export; retain data while clearing only chosen logs |

### Recovery is a first-class journey

An ideal path is not customer delight if its first failure strands the person. Apply a uniform recovery shape: **what happened** (plain words, no stack trace) → **what remains safe** (local progress, query, selected title, saved credential) → **one primary next action** → **secondary escape/diagnostics**. Retry is only primary when retry is likely to change the outcome; otherwise provide a concrete alternative.

| Failure / ambiguity | Required distinction | Keep intact | Customer-facing next step |
| --- | --- | --- | --- |
| Sign-in succeeds, account data will not unlock | Authenticated vs encrypted state unlocked | Local encrypted data; selected account context | Retry unlock, continue locally only if policy allows, or sign out explicitly; never say connectivity caused a cryptographic failure without evidence |
| Home has no titles | Truly empty vs addon not configured vs provider unavailable vs offline | Profile, settings, other available catalogs | Explain which case is known; offer relevant setup/retry and keep Search/navigation reachable |
| TMDB/Cinemeta enrichment fails | Required content absent vs optional enrichment absent | Cached title facts, chosen action, focus | Render baseline facts and keep Play usable; disclose partial details only if decision-relevant |
| Search returns nothing / one provider fails | True empty vs partial failure vs stale query | Query and filters | Keep successful provider results; label partial failure; offer edit/retry without flashing to empty |
| Trailer cannot resolve or promote | Preview failure vs feature-source failure | Originating title and normal layout | Dismiss/retry preview; do not claim the title itself cannot play |
| Source found but player has no first frame | Resolver success vs prepare/buffer/decode failure | Title, episode, resume target | State the current stage accurately; allow cancel/source choice/retry without sending user back through unrelated screens |
| Sync is stale, limited or rejected | Local save vs server acknowledgement vs rate limiting | Local commands and device settings | Show pending/last-confirmed status only when known; preserve outbox and avoid repeated blocking conflict dialogs |
| Image/logo/thumbnail is absent | No asset vs loading vs decode/network error | Card geometry, target timestamp and navigation | Use stable initials/title/time fallback; never substitute unrelated artwork or move focus |
| Action removes the selected tile | Local state updated vs remote sync pending | Nearest useful focus and undo only if truly supported | Move focus to nearest remaining item and announce what changed without jumping to the menu |

For every recovery path, tests must assert both the message/action and *preservation*: the query, profile, episode, selected title, locally saved data and existing credential that should survive. A screenshot alone is not enough.

### J01 — Install/update and first opening

Desired feeling: “This is ready, and my existing account is safe.”

- Returning update: recognize the account/profile; do not repeat onboarding or explain features before reaching useful content.
- New installation: one clear sign-in/create-account route with phone input, legible validation and no credential exposure.
- Separate signing in, unlocking account data and restoring state. Each error identifies what is still safe and the next useful action.
- Continue with locally usable account data only when current account/security policy permits it. A cryptographic failure is not merely “offline.”
- Never put Sign Out/Start Fresh in the default focus position of a recoverable error. Do not imply a password reset can decrypt data when recovery is unsupported.
- Success gate: upgrade preserves state; temporary provider/cloud failure does not become an unexplained blank screen; no unsafe cross-account cached content.

### J02 — Setup and profile choice

Desired feeling: “It understands enough to start; I can customize later.”

- Default route asks only what is necessary to create a usable profile. Offer copy-existing-profile with a plain summary of what is copied.
- Combine primary language and optional extra priorities into one screen. Keep display capability device-specific; do not infer device performance from 4K support.
- Display the difference between “profile preference” and “this TV” where it matters, without a tutorial wall.
- Existing advanced theme/avatar/PIN/provider options remain accessible; defer optional decoration, not security or required configuration.
- Do not promise playable content if an addon/integration still needs setup. Present “Browse available titles” vs “Finish playback setup” honestly under existing access policy.
- Success gate: a new user can reach useful content without configuring every advanced preference; an existing user can select a profile without accidental editing/deletion.

### J03 — Arrive on Home

Desired feeling: “There is something for me, and I know where to begin.”

- First-time profile: curated/provider content and a clear setup explanation; no empty personalized shelf presented as a broken feature.
- Returning profile: Continue Watching is prominent and its episode/remaining-time labels are correct. Do not start trailer autoplay from these tiles.
- Reserve predictable regions for personalized content. When data arrives late, preserve active focus and viewport. Use placeholders only briefly; do not show indefinite shimmer for a missing provider.
- Home density has three levels: contextual hero, active shelf, enough next shelf to suggest depth. Avoid a giant hero that makes actual choices scarce.
- Primary actions are obvious; browsing does not require repeatedly entering/exiting a modal menu.
- Success gate: useful cached content appears without waiting for all recommendations, logos or provider responses.

### J04 — Browse across and between shelves

Desired feeling: “The remote is connected directly to the interface.”

- Acknowledgement starts on the next available frame; artwork readiness does not delay focus.
- Horizontal movement uses one fixed leading anchor except clamped list edges. Vertical movement aligns the shelf to a common position; maintain the nearest meaningful horizontal choice when changing rows.
- Exactly one scroll owner per axis. Stop/resume/retarget gracefully on rapid input; the app must not keep drifting after the user releases the key.
- A white ring hugs the actual card image bounds. Series layers never move the focus/hit/trailer rectangle. Card headings and adjacent shelves cannot overlap.
- View All behaves as a peer card with the selected global aspect ratio. Back returns to the source shelf and identity, not the top of Home.
- Success gate: rapid mixed-direction sequences, empty shelves and late inserts produce no invisible focus, navigation theft, duplicate activation or unbounded scrolling.

### J05 — Evaluate a suggestion

Desired feeling: “This recommendation has a reason, not just another random title.”

- One Top Suggestions rail with movies and series, respecting current content/sort/preferences. A short reason may be shown from existing evidence; do not expose personal history to another profile.
- “Popular” and “new” must not be mislabeled as deep personal matches. Hide unsupported match percentages rather than manufacture precision.
- Deduplicate verified canonical titles across provider aliases; preserve similarly named remakes as distinct year/type entries.
- Provide enough metadata to decide: title/logo, type, year, concise genre/context and availability status if actually known. Discovery does not prove a playable source exists.
- Existing watchlist/watched actions update shared UI state. “Not interested” and adjustable recommendation weights would be new preference/domain work: optional later scope, not silently part of this UI rewrite.
- Success gate: cold-history, movie-only history, series-only history and mixed-history fixtures all produce honest, non-empty behavior or an intentional fallback.

### J06 — Search with an exact intent or a vague request

Desired feeling: “I can get to the right title without fighting the keyboard.”

- Query remains visible. Keyboard/results/filters have clear D-pad boundaries; phone paste is readily discoverable. Voice is offered only where supported, never as a dead control.
- Preserve current progressive provider results and cancellation. During edits, old results may remain as a visibly updating, non-activatable snapshot until new-query results arrive; never let stale cards play under a new query label.
- Support an explicit-submit route for short queries if the provider supports it, rather than assuming all useful names are at least three characters.
- Differentiate exact-title search from interpreted filters such as language/genre/type. Show the interpretation as removable chips; if interpretation confidence is absent, keep an ordinary title-query fallback.
- Filters apply within the same search session. Result arrival must not change the selected item or move focus back to the keyboard.
- Partial provider failure yields available results and a restrained notice, not “No results.” Truly empty results suggest a useful edit; no fake spell correction without supporting logic.
- No hover autoplay here. Make year/type clear for remakes and similarly named series.
- Success gate: rapid typing/deletion, paste, short names, multilingual titles, returning from details and out-of-order responses remain coherent.

### J07 — Open details and decide

Desired feeling: “I have everything needed to decide, without waiting for everything.”

- Show current cached/Cinemeta facts immediately. Fill TMDB facts and related sections in reserved spaces; maintain the information-source precedence already established by the canonical schema.
- Header priority: logo/title, type/year/runtime or episode context, brief synopsis, primary Play/Resume, Library action. Extra facts use progressive disclosure, not dense paragraphs.
- For series, make the active season, selected episode and resumable episode separate, legible concepts. Selecting S2E5 must not mutate into the first episode during metadata refresh.
- Preserve the customer's removal choices: do not reintroduce TMDB rating, Network or Production blocks merely because a redesigned template has room.
- More Like This, Collection and Filmography retain identical card actions/geometry and normal preview policy. Their Play actions always carry their own title identity.
- Details-title autoplay remains fullscreen/unmuted after inactivity, per existing requirement, not while the user is selecting episodes or after feature playback starts. A future consent-policy change is separate, not an unannounced redesign decision.
- Success gate: late enrichment, missing logo, long descriptions and empty cast sections never block Play or reposition focused actions.

### J08 — Preview, promote and leave a trailer

Desired feeling: “A preview adds confidence; it never takes control away.”

- Use one active player/session and one media owner. Inline/fullscreen is a presentation change, not a fresh resolve/restart.
- Inline remains a landscape overlay with always-visible Mute, Fullscreen, Play and Library controls where applicable. Controls have labels/semantics even when visually icon-first.
- Explicit fullscreen unmutes. Back restores inline first when promoted from inline; Back from an automatically fullscreen preview restores its originating normal layout. Manual launch restoration follows its origin.
- Failed trailer playback gives an unobtrusive retry/dismiss path; the title remains available to inspect/watch. Do not show feature playback errors as trailer errors or vice versa.
- Start Watching snapshots the originating title/episode intent, stops the preview and hands over to feature playback. A late preview callback cannot revive the trailer over the feature.
- Success gate: repeated promotion/demotion, Back during resolution, navigation away and Play during fullscreen do not recreate sessions, detach the wrong surface or play the wrong title.

### J09 — Choose a source and start watching

Desired feeling: “The app is doing the right work; I can intervene if needed.”

- Preserve auto-select and manual selection. The UI must not create a source-picker detour for a valid prepared/resumable source.
- Player surface is the destination immediately for direct-resume intent. Avoid a visible intermediate details screen. Keep one clear loading indicator rather than a succession of unrelated spinners.
- If source preparation takes noticeable time, use factual states: preparing source, connecting, buffering. Do not display fake progress or a timer promised as guaranteed completion.
- Source cards use concise quality/audio/size/cache tags. Unknown cache status is “unknown,” not uncached. Do not restore the removed TorBox seeder lookup.
- Filters explain “No sources match your filters” separately from provider error or no returned sources. Preserve the query/title while the user changes filters or retries.
- Success gate: prepared-source hit, expired URL, empty source set, partial provider results and rapid double-Play requests retain correct intent and offer a useful exit.

### J10 — Watch without interface interference

Desired feeling: “Only the story matters.”

- Controls are quiet, consistent and fully navigable. First input reveals controls without inadvertently activating a hidden action; subsequent activation is intentional.
- Maintain clear subtitle/audio state. Subtitles Off cannot leave autosync running or be reversed by a cosmetic state update.
- Loading/buffering feedback should not cover the whole image unnecessarily or create false repeated restart behavior. Network policy is outside the renderer.
- Skip Intro, Skip Recap and Next Episode share compact geometry and continuous, frame-driven progress; no once-per-second stepped fill or mismatched rounded fill ends.
- Respect pause, seeking and cancellation. A manual fallback next-episode prompt never silently becomes auto-advance. Keep the current 95% watched policy and configured IntroDB behavior.
- Success gate: Back, pause, seek, language selection, skip countdown cancellation and next-episode transitions work identically with supported playback backends.

### J11 — Seek past the opening or find a scene

Desired feeling: “I can see where I am going, immediately.”

- Retain the last valid cached thumbnail while the next one loads; no blank flashes, loading dots or synthetic empty cards at the ends.
- Show target seek time separately from the current playback position. On release, commit the latest valid target; cancelled seek returns to the previous position.
- Missing/unready thumbnails present a stable time-only state, not a misleading unrelated image. Aspect ratio remains stable.
- First-ten-thumbnail generation and general generation speed have their own decoder/network instrumentation. The UI must use ready frames efficiently, but cannot manufacture them by animation.
- Success gate: fast bidirectional seeking over cache boundaries, end-of-video edges, cache eviction and missing frames never show the wrong timestamp as verified content.

### J12 — Finish, continue, save and undo

Desired feeling: “The app remembers what I did.”

- Completed episodes lead to a next-episode primary action; partial episodes retain correct Resume. Distinguish series watched episodes from a manually watched whole title according to existing domain semantics.
- Watchlist, watched badge and progress update from the same canonical user state in Home/Search/Details/Collection/Filmography/Library.
- Mark watched removes watchlist membership; mark unwatched is available consistently. A tile removed by the current filter restores focus to the nearest remaining valid item.
- Clear progress explains its effect and offers confirmation where irreversible. Use Undo only when the existing command can reliably reverse all relevant state; never present a fake reversible destructive action.
- Success gate: actions performed through any entry point reflect elsewhere without a screen reload or re-login.

### J13 — Return later or move to another TV

Desired feeling: “This is still my account, wherever I sit down.”

- Surface local readiness and sync freshness separately: “Saved on this TV,” “Sync pending,” “Last synced…” only when supported by real state.
- Do not claim remote durability before cloud acknowledgement. Sync in progress should not look like lost progress or force a blocking technical conflict modal for ordinary browsing.
- On a new device, use the existing account restoration rules; profile preferences/integrations are distinct from device-local display/decoder settings.
- If local and cloud state disagree, the UI exposes understandable recovery using existing domain policy; it does not invent timestamp conflict resolution or overwrite encrypted state.
- Success gate: test two-device resume, interrupted uploads, offline changes, profile switches and an update installed over existing data. UI work cannot be declared complete if the journey still fails due to an unresolved domain defect.

### J14 — Configure, recover and get help

Desired feeling: “I can change what matters and recover without being an engineer.”

- Settings hierarchy: Appearance & Home; Playback & Episodes; Audio & Subtitles; Sources & Quality; Addons & Integrations; Account & Sync; Help & Diagnostics. Preserve every current setting through a parity inventory, including expert options.
- Use plain-language primary labels and short consequences. Keep technical implementation details in secondary help; settings copy must match active implementation.
- Integration flow: choose provider → phone paste/manual entry → validate → show configured status. Mask secrets, do not log them or copy them into support summaries.
- Validation does not wipe an existing working credential on a failed replacement attempt. Saving is clearly distinguishable from validation success.
- Diagnostics: human-readable summary, handled errors vs crash distinction, time range/component filters where feasible, expandable event details, export and clear. Clearing requires clear scope and preserves app data.
- Updates: readable version/action/error state; no invisible button labels; focus remains in the dialog after an action disappears. Do not imply restart is needed before installation actually completes.
- Success gate: all dialogs scroll correctly at increased font size and on small logical layouts; no button is clipped, unreachable or ambiguously focused.

### Customer-delight traceability: the moments that decide trust

The fourteen journeys above are not independent screens. The release-critical path is one connected loop: arrive → orient → find → decide → start → watch → resume/share state → recover. A defect at any handoff invalidates the apparent quality of every prior screen. The table is the audit-to-test spine; each row must map to a named automated assertion or an explicit physical-device acceptance record before its package can be called done.

| Moment / customer question | Visible promise and pass condition | Trust-destroying failure to catch | Truth owner / handoff | Evidence required |
| --- | --- | --- | --- | --- |
| First open after install/update — “Is my account safe?” | Existing profile and local data survive; user can distinguish authentication, unlock, local readiness and cloud freshness | Signed-in TV loops forever at unlock; upgrade strands valid ciphertext; error copy implies local data is lost | Account state → unlock/snapshot protocol → entry screen | Upgrade-over-existing-install fixture, preserved local data assertion, recoverable retry/offline/signed-out outcomes |
| First useful Home — “Where do I start?” | Cached actionable rows appear without waiting for every provider/TMDB request; first focus is visible and stable | Blank screen, late rows take focus, home loads all network data before becoming useful | Catalog/home presentation state with repository partial results | Cold/warm trace, delayed/failing provider fixture, focused-row assertion through late insertion |
| First remote press — “Did it hear me?” | Focus/pressed feedback is immediate and belongs to the input target; held Center opens actions only after release | Focus jumps rows, popup auto-activates, menu expands unexpectedly, rapid repeats leave drift | One shell input owner → current surface command | Remote repeat/held-key matrix on device; Compose event tests for press, repeat, release, cancel |
| Find and distinguish — “Is this the right title?” | Search preserves query and partial results; title/year/type make remakes clear; spoken/filter intent is not faked | Results blink away, stale result can play under a new query, short name is impossible to submit | Search session identity → provider/TMDB result merge | Out-of-order response, short query, multilingual, same-name movie/series/remake fixtures |
| Decide — “Can I trust the details?” | Usable metadata is immediate; enrichment fills reserved areas; Play is never blocked by cast/logo/TMDB | Details waits for remote enrichment; metadata changes selection; wrong related card launches parent title | Canonical title identity → detail projection → explicit action payload | Cached/late/failed enrichment screenshots and assertions for every related-title action identity |
| Promote trailer — “Can I see more without losing my place?” | Preview belongs to hovered/selected title; promote keeps the same session; Back restores the exact origin | Trailer hijacks remote, fullscreen restarts, old trailer appears after moving focus, Start Watching uses parent details item | Preview session owner → player surface handoff → originating address | Session/player identity + media position continuity; Back restoration across each launch context |
| Start playback — “Will this play the exact thing I chose?” | Correct movie or chosen season/episode starts; source failures are actionable; first frame is measured separately from source search | Wrong title/episode, long opaque loading, selected cached source is not actually playable, accidental double launch | Typed play command → source resolution → playback coordinator | Command identity assertion from every entry point; deterministic resolver failure/success fixtures; device first-frame trace |
| Watch and seek — “Can I control it confidently?” | Stable controls, seek target/time/image agree, cached preview appears without a flash, subtitle Off remains Off | Buffer loops, stale thumbnail, mismatched timestamp, skip countdown steps, subtitles resurface | Player timeline/source of truth; thumbnail cache is presentation support | Fake playback seeking tests; cached/miss/error thumbnail matrix; real-device decoder/network benchmark |
| Finish or leave — “Will it remember what happened?” | Resume records correct season/episode/timestamp; watched threshold remains 95%; next episode only appears under its configured evidence rule | Episode boundary overwrites prior progress; wrong season; stale watched/watchlist badge; smart fallback fires early | Playback progress → user-state command/outbox → shared Library projection | Boundary/95%/undo tests, thumbnail-cache-ready gate tests, card parity in every catalog surface |
| Switch TV/profile — “Is this still my state?” | Account-owned library/settings and selected profile reconcile; TV-specific capability stays on that device; track IDs restore where valid | Snapshot conflict blocks a TV, another profile leaks, local-newer state is discarded, unsupported track IDs break playback | Account/profile/device scopes → sync merge → local preferences | Two-device conflict fixtures, profile isolation tests, unavailable-track fallback and migration upgrade tests |
| Recover/configure — “What can I safely do now?” | Copy identifies affected scope and offers the safest next action; phone paste, export and retry are findable | Generic retry loop, unreadable focus/text, destructive action gets default focus, secret appears in diagnostics | Error taxonomy + settings command result + diagnostics privacy policy | Failure-state screenshots, focus order assertions, redaction/export tests, partial-outage recovery journey |

#### First-session timeline, as the customer experiences it

1. **Open:** distinguish a returning install from a genuinely new setup; never replay onboarding by default to a returning account.
2. **Orient:** expose profile and destinations with one obvious focus target; explain unresolved setup only when it prevents a real action.
3. **Offer something useful:** render locally cached/catalog data immediately and enrich asynchronously without moving focus.
4. **Help choose:** show enough context and honest availability to avoid opening several dead ends; keep Search reachable in one deliberate action.
5. **Confirm intent:** the selected title, profile and episode remain visible in the action contract until playback begins.
6. **Reward quickly:** source resolution and first frame have separate progress/error states; no empty spinner while background work is unobservable.
7. **Remember accurately:** progress and Library state update through one owner and remain correct after process death or another-device merge.

Targets should be baselined on reference TVs before adopting numerical service-level objectives. For the first-session usability study, record time and press count per step plus whether help was needed; do not count passive trailer time as discovery success.

## 6. Information architecture and interaction rules

Keep current high-level destinations recognizable: Home, Movies, Series, Search, My Library, Settings/Profile. More destinations are not more discovery. Settings and profile controls should not consume the primary browsing area.

Use one information hierarchy everywhere:

1. What is this? Title/logo and type/year where ambiguity exists.
2. Why should I care? Concise relevant context or progress.
3. What can I do? One clear primary action and contextually useful secondary actions.
4. What happens next? Immediate acknowledgement and stable next focus.

No essential action is discoverable only by long press. Long press is an accelerator; equivalent actions exist in Details. Icon-only quick actions need an on-focus label/tooltip and accessibility semantics. Support click, long press and held-key release without an accidental first-option click.

### Back and focus precedence

One owner routes each Back event. Priority: top dialog/action popup → fullscreen trailer promotion → inline/manual preview → player overlay/subpanel as defined → screen back stack → navigation/root. Do not assign separate competing handlers that each observe and act on one event.

Restore a logical address: destination + profile + shelf/filter + canonical title + episode intent if relevant + scroll offset. A FocusRequester is a temporary attached UI object, not a persistence key. When the target no longer exists, choose the nearest valid sibling; when the row is gone, choose the nearest remaining row; use navigation only as an intentional final fallback.

Queue structural updates while they would invalidate active focus. Do not freeze all content indefinitely: apply safe changes in place and reconcile pending changes at a navigation boundary. Profile switch discards old-profile pending results and preview work immediately.

## 7. Visual and motion design specification

The visual direction is **cinematic clarity**, not a glass/blur showcase. Quiet near-black backgrounds, white high-contrast text/focus, restrained blue for active state/progress and a limited semantic status palette. Backdrops carry atmosphere; controls remain stable and legible over bright and dark content.

Use the existing font family initially; typography refinement is more valuable than another dependency. Define explicit hero, section, card-label, body, control and helper roles. Validate from actual sofa distance. Avoid both tiny dense helper text and oversized paragraphs. Text must not push action footers offscreen when localized.

| Component | Contract |
| --- | --- |
| Title card | 2:3 poster or 16:9 landscape; identical movie/series front dimensions; stable image/logo bounds |
| Series decoration | Two subtle solid rear layers; optional precomputed artwork tint, no runtime animated blur; outside front focus bounds but inside row reservation |
| Focus ring | Same geometry as front card, 2dp starting value; contrast-preserving dark underlay where white artwork obscures it; no zoom |
| Badges | Shared watched/watchlist state; distinguish by shape/icon, not color only; no watched tick in Continue Watching |
| Artwork fallback | Stable initials on actual absence/failure; readable title text when logo absent; do not initially flash initials on every request |
| Primary button | Visually strongest, plain verb; focused/pressed/loading/disabled states defined separately |
| Secondary/destructive action | Same spacing/height; destructive treatment only for meaningful destructive actions, not ordinary reversible navigation |
| Dialog | Bounded readable width; scrollable body, visible non-clipped actions; focus trapped/restored intentionally |
| Loading | Preserve existing useful content; reserved geometry; one honest indicator; no full-page shimmer after every small refresh |
| Notification | Brief, non-stealing acknowledgement; no toast as the only explanation of a persistent failure |

Card starting sizes remain 120×180dp poster and 208×117dp landscape from the prior plan, subject to real-TV validation. Maintain a consistent 4dp spacing scale and explicit row decoration clearance. Do not mix raw output pixels with Compose dp; image requests are sized for actual rendered pixel dimensions.

### Motion contract

Treat animation as the visible explanation of an interaction: acknowledge first, then move continuously toward the latest intent. A polished transition must not be implemented as a chain of timed callbacks or an input queue that makes the screen keep moving after the customer stops.

**Priority gate:** establish the shared focus/shelf motion contract and collect a baseline before expanding the visual rewrite. Every destination should inherit the same retargetable motion where its interaction is equivalent; do not add per-screen decorative animation until focus ownership, image identity and frame pacing are stable. A motion change is not accepted from screenshots alone: identity/focus tests plus release-mode traces on TV hardware are required.

- Input/focus acknowledgement: immediate visual state; no wait for artwork, sound, membership lookup or trailer resolution.
- Shelf travel: one interruptible no-bounce spring per axis; approximate typical settling 180–260ms as a tuning starting point, not a fixed timer.
- Backdrop: retain old image until the new image is drawable, then one short fade/translation; avoid multiple overlapping queued transitions. Do not animate metadata enrichment for the same title as a new title selection.
- Controls/overlays: 80–160ms state feedback and 120–180ms appearance as initial tuning. No layout remeasurement solely for a focus glow/scale.
- Navigation expansion: overlay and scrim; content viewport stays stable. Support the existing top-navigation option through the same state/actions.
- Reduced motion: no travel/parallax, minimal fades, unchanged visible focus and functionality. Honor relevant system animation settings where available and provide an app preference only if needed.
- Do not autoplay moving hero carousels while a person is navigating. Trailers are the only intentionally moving media preview; preserve their configured delays and explicit context policies.

#### Motion-first execution plan and end-to-end acceptance

| Stage | Customer-facing behavior | Implementation boundary | Acceptance; no “feels smooth” sign-off by inspection alone |
| --- | --- | --- | --- |
| M0 — Capture the baseline | The same deterministic remote sequence can be compared before/after | Existing Macrobenchmark/Perfetto path, release build, fixed content/art/cache state; no analytics SDK | Record per-frame deadlines, input acknowledgement, scroll travel/settle, dropped/repeated remote events and memory on a low-end Fire TV plus a modern TV |
| M1 — Focus and shelf travel | Focus is visible immediately; content glides under a fixed indicator and follows the latest key press without accumulating lag | One owner per axis; stable keys; one interruptible no-bounce spring; draw focus indication in the smallest invalidation scope; no zoom on title cards | Script left/right, held repeats, reversal mid-flight, row change, edge clamp, empty/late row and return-from-details; assert final identity/address, indicator bounds, no focus theft, no drift after key-up and frame timing |
| M2 — Card/content transition | Artwork, logo, progress and backdrop belong to the same title at every frame | Canonical identity owns request keys; reserve geometry; retain prior drawable until replacement is drawable; animate only on title identity change, not metadata enrichment | Delay/reorder art responses and move focus rapidly; no unrelated image flash, initials flash for merely-pending art, layout jump or stale logo; report image-ready separately from focus-ready |
| M3 — Route and overlay transition | Details, Library, source selection, menus and previews enter/leave with clear spatial continuity | Existing navigation/back owner and player/trailer session ownership; motion must not change command identity | Back at every stage returns to exact origin/address; preview promotion preserves session/time; cancellation stops future transitions; repeated input cannot queue hidden work |
| M4 — Small state feedback | Watchlist/watched/selected/action-pending feedback feels immediate and restrained | Shared user-state projection and shared badge/control component | Action acknowledgement starts within one rendered frame; pending/success/error remain distinct; other visible copies update without resetting focus |
| M5 — Accessibility and hardware | Motion remains understandable on a remote, at sofa distance and on low-memory TVs | Existing theme/settings and system animator scale; avoid simultaneous blur, scale, shadow and full-size image decode | Reduced-motion mode preserves focus/action meaning; no OOM/thermal degradation; frame metrics meet §13 on reference tiers after cold and warmed runs |

The goal is a consistent Saab motion language, not a literal clone of a streaming service. For shelves, motion is primarily *translation of content under a stable focus frame*; card scaling is deliberately avoided because it has been reported as jittery. At boundaries, clamp to real content and keep the same frame/card geometry rather than exposing a blank gap. Motion must be retargetable: a new D-pad target supersedes the old target and carries no hidden backlog. Image fade, title logo, hero backdrop, ring and row travel must not all start separate animations for the same focus event.

Use two acceptance levels. Deterministic tests prove identity, focus address, geometry and state transitions. Release-mode device traces prove frame pacing and input-to-frame timing. Emulator/Compose tests are necessary for behavior but cannot establish physical-remote repeat cadence, thermal stability, decode budget or perceived smoothness. Android TV guidance confirms that the D-pad/Back path and visible focus are the primary interaction model; Compose guidance recommends real-journey benchmarks, stable lazy keys and limiting unnecessary recomposition. These are validation principles, not evidence that any target has already been met.

## 8. State and artwork contracts

Canonical schema is necessary but not sufficient for a fast UI: it unifies meaning; it does not make network artwork immediately available or eliminate image decode cost.

Each screen exposes narrow immutable render state plus explicit actions. Use existing repositories/commands; do not build a generic second event framework or a second content store. Cards receive only identity, artwork, label and badge/progress values. High-frequency playback progress cannot invalidate the browse screen.

Represent the states that matter instead of one `isLoading` for everything:

- data: absent, cached/usable, refreshing, partial, ready, empty, failed;
- action: idle, accepted/pending, succeeded, failed;
- artwork: no URL, loading with previous image, drawable, failed;
- focus: logical target pending attachment, attached/focused, fallback required;
- preview: pending delay, resolving, playing inline, playing fullscreen, dismissed/failed;
- account: authenticated/unlocked/local-ready/cloud-fresh are distinct facts.

These are conceptual distinctions; use the smallest existing models that express them. Do not create a sprawling abstraction with every Cartesian combination. Explicitly define valid transitions and tests for each screen.

Artwork precedence is shared across TMDB/Cinemeta/provider contexts. Resolve URLs and prefetch for visible cards before focus, with a bounded neighboring lookahead. Display and prefetch must share request dimensions/cache identity. Never request every title in the catalog eagerly.

Memory example, not a current-app measurement: an uncompressed 3840×2160 ARGB bitmap is about 31.6MiB; two such layers are about 63.3MiB before logos/player surfaces/caches. Size decoded assets to their displayed role. A normal title tile does not need a full-size 4K backdrop. Cap concurrent decodes/preview sessions and release outgoing surfaces; do not retain every visited screen.

## 9. Complete state checklist for every surface

Before implementation approval, each component/screen needs designs and tests for:

1. First load with nothing cached; warm load; return from another screen.
2. Partial data and late metadata/artwork; refresh with useful content retained.
3. True empty state vs provider/network/auth failure.
4. Missing image/logo/title facts; long titles; duplicate display names/remakes.
5. Normal, focused, selected, pressed, long-pressed, disabled and action-pending.
6. Small screen/logical density, font scaling, reduced motion and screen reader labels.
7. Rapid D-pad repeat, Back at every asynchronous stage, overlay open/close.
8. Profile change, process recreation and list mutation/removal while focused.
9. Offline operation, partial provider outage and recovery without forced refresh.
10. Correct identity for actions from Home, Movies, Series, Search, Library, More Like This, Collection and Filmography.

## 10. Feature-parity ledger: do not lose capability during cleanup

Phase 0 must enumerate actual preference keys, callbacks and destinations. This table defines categories, not a claim of a finished exhaustive inventory:

| Area | Must survive |
| --- | --- |
| Account/profile | Sign-in/signup, secure remote input, import, PIN/avatar/theme, account isolation, safe offline paths, sync/backup UI |
| Catalogs | Provider/addon rails, user ordering/hiding/layout, hubs/paging, poster/landscape, series stack, badges |
| Discovery | TMDB enrichment, mixed suggestions, genres/new/popular, search parsing, related titles, collections, people |
| User state | Watchlist, watched/unwatched, 95% threshold, clear progress, current episode/timestamp, new-episode indications |
| Trailers | Hover delay/style/mute, manual launch, context exclusions, fullscreen promotion/unmute, detail inactivity policy |
| Sources | Manual/automatic source choice, remembered selection, filters/ranking, cache tags and unknown states |
| Playback | Supported backends, audio/subtitles, Off/autosync, seeking/thumbnails, skip intro/recap, next episode/fallback policies |
| Device | Display choice, frame-rate/decoder/tunneling preferences, update/install flow, TV remote behavior |
| Help | Basic/detailed logging policy, always-captured crashes/exceptions, export/clear, readable diagnostics |

Keep current optional power-user behaviors available under appropriate settings. The rewrite may recommend simpler defaults, but it must not silently delete advanced configuration or replace established media/sync policies.

### Customer evidence → implementation trace

This matrix prevents the audit from becoming a generic “make it smoother” redesign. Historical reports are regression evidence and must be reproduced against the current build before asserting that a defect remains. A test of the proposed contract is still required even when the old symptom cannot be reproduced.

| Reported experience | Journey/package | Root-cause boundary to inspect | Acceptance evidence (not visual opinion alone) |
| --- | --- | --- | --- |
| Focus jumps, focus ring drifts, or menu steals focus | J04 / U03, U05, U06, U08 | Logical address vs transient FocusRequester; scroll owner; shell/sidebar ownership; item-size/layout bounds | Stable selected title through rapid D-pad movement and late row mutation; physical remote confirms no focus theft |
| Details, More Like This, Filmography or trailer action starts a different title | J07–J09 / U13–U14 | Lost typed content identity while action is routed through shared screen ViewModel | Every entry point emits the selected card’s canonical identity, kind and episode intent in a fake playback assertion |
| Continue Watching resumes wrong episode/time | J12–J13 / U12–U15 | Episode progress owner, completion threshold, last-position update order, cross-device merge | S2E5 boundary fixture survives pause/finish/process death/two-device merge without changing episode |
| Artwork/logo flashes, changes after focus, or delays card reveal | J03–J07 / U05, U07, U09–U13 | Request/cache identity mismatch, late canonical enrichment, wrong aspect role, oversized decode | Same cache key/request dimensions for prefetch+display; hit has no placeholder flash; profile of bounded prefetch memory |
| Playback/trailer appears slow or buffers despite a source being known | J08–J11 / U14–U15 | Resolve time vs prepare time vs first frame; media-session duplication; network/decoder constraints | Separate p50/p95 timings and session IDs; cache/source success does not mask first-frame failure |
| Cross-device account looks empty or asks to resolve conflict repeatedly | J01/J13 / U03, U12, U16–U17 | Encryption unlock vs server freshness; merge key/scope; old local DB migration | Same account snapshot on two device fixtures; local newer/older policy resolves without deleting unrelated local/device state |
| Settings, dialogs or diagnostics are hard to read/control | J02/J14 / U04, U16–U18 | Control semantics, focus boundary, scroll ownership, clipped content, contrast | All primary actions visible/reachable at TV dimensions; long/localized text; remote traversal and export-path test |

The listed customer themes span UI and underlying services. Keep the UI plan bounded to presentation, focus, truthful status and command identity; route any data durability, provider reliability, decoding or sync algorithm changes to their owning plan and do not claim a visual prototype solved them.

## 11. Implementation work packages and dependencies

Severity expresses customer impact, not an implementation estimate. P0 = trust/navigation/correctness; P1 = task completion/speed/consistency; P2 = additional polish. Items remain incomplete until their stated acceptance gate is met.

| Package | Priority | Deliverable | Depends on / acceptance |
| --- | --- | --- | --- |
| U01 | P0 | Feature/preference parity ledger and current golden journey recordings | Clean source + APK hash pinned; every destination/action and persisted preference owner mapped; G01–G14 replayed/captured with sanitized fixtures and linked reports; no journey accepted from source presence alone |
| U02 | P0 | Real-device profiling harness and fixed fixture dataset | Scripted input plus traces and frame metrics export |
| U03 | P0 | Navigation/Back/focus address and motion ownership contract | No screen-specific competing ownership; restoration after mutation; motion retargets instead of queueing |
| U04 | P1 | Tokens, typography, control states, geometry and shared motion specification | Long text/contrast/font-size/reduced-motion matrix; motion response verified on TV tiers |
| U05 | P1 | Unified title card and action strip | All contexts, both shapes, badge parity, held-key safety |
| U06 | P0 | Shared shelf and grid restoration behavior | U03/U05; no ring drift or queued motion |
| U07 | P1 | Artwork readiness and bounded lookahead integration | Reuse existing caches; visible-first; missing/error behavior |
| U08 | P0 | New app shell plus Home vertical slice | U03–U07; browse → action → details → Back |
| U09 | P1 | Recommendation layout/progressive arrival | U08; no focus theft; cold/mixed history fixtures |
| U10 | P1 | Movies/Series hubs and View All | U06/U08; return to source card; media filters/sort; paging/empty/error/retry states |
| U11 | P1 | Dedicated Search presentation | U05–U07; no stale selectable results, safe partial failure |
| U12 | P0 | Shared state actions and Library presentation | U05; immediate cross-surface membership and safe focus removal |
| U13 | P0 | Details/episodes/related/person/collection presentation | Correct title/episode commands; progressive metadata |
| U14 | P0 | Trailer presentation ownership and handover | U03/U13; promotion without restart; no late preview over feature |
| U15 | P1 | Player controls and seek presentation | Existing backend policies retained; no flicker/stepped progress |
| U16 | P1 | Settings/integrations/account/profile presentation | All keys preserved; remote input, legibility, scroll/focus tests |
| U17 | P1 | Error/recovery/diagnostics/update presentation | Failure taxonomy, safe actions, actual state-derived copy |
| U18 | P1 | Accessibility/localization/reduced-motion pass | All replacement journeys, not Home alone |
| U19 | P0 | Release benchmarking, motion/parity sign-off, old-renderer retirement | Full regression; physical low-end TV gate; rollout/rollback ready |

Do not start U08–U17 as independent screen rewrites before U03–U07 stabilize. Shared primitives prevent repeating the current fragmentation. Do not spend a phase extracting hundreds of files without a customer-visible working journey.

U01's current destination/action map, preference-owner inventory, evidence rules, and G01–G14 capture manifest live in [`UI_FEATURE_PARITY_LEDGER.md`](UI_FEATURE_PARITY_LEDGER.md). The manifest is a capture contract, not evidence that recordings exist; the ledger records which U01 acceptance gates are still open.

### Implementation checkpoint — 2026-10-15

- **U05, in progress (held-key safety, action readiness and card-state parity):** poster and landscape cards share a held-center state machine that opens quick actions only on release; readiness follows saved-state reads rather than a fixed delay. Both card shapes now render watched and watchlist markers through one shared status overlay. Compose regressions exercise all four watched/watchlisted combinations across both shapes and verify Continue Watching can suppress only the watched tick. The full JVM suite passes 1,224 tests (0 failures/errors, 2 skipped). Remaining U05 work: audit action-strip behavior in every destination, physical-remote validation, and confirm badge geometry/contrast on device; this is not package completion.
- **U03/U06, in progress (Home/grid restoration slice):** Home card addresses now carry stable shelf identity and title identity alongside the temporary row/index address. Restoration follows a title through shelf insertion/reordering, and falls back to a nearby valid card when the title/shelf disappears. Hub addresses preserve group/item identity; grid restoration now follows canonical item identity across reorder and chooses the nearest valid item if the saved title is removed. The linear, finite-grid and infinite-grid horizontal renderers use shelf/content lazy keys (with duplicate disambiguation and loop generations), independent of mutable position. Legacy focus addresses remain supported. Regression tests cover shelf/history/hub/grid insertion, reordering/removal, View More modes and opaque/malformed IDs. This is not yet a shared navigation/Back contract, nor has physical-remote behavior been verified.
- **U07, in progress (shared artwork readiness):** Search result rows, Details recommendations (More Like This/Collection), Cast Filmography, Studio Discover, Home shelves and My Library shelves now use one bounded visible-first prefetch policy. The standalone catalog Grid has also been routed through that policy instead of issuing its previous broad five-card focus burst; it still schedules TMDB enrichment only for visible landscape cards. The policy selects poster versus landscape+logo by card shape, prefers nonblank TMDB-enriched assets, falls back to provider art, and rejects blank/missing URLs. A per-row 64-request insertion-ordered window avoids repeatedly enqueueing the same image during scroll/focus movement while bounding bookkeeping memory. The dispatcher routes through the same Coil prefetch methods/cache identities used by the card renderer. Tests cover range edges, missing focus/art, enrichment fallback, role routing, duplicate suppression/window eviction, and Compose wiring for Search, Details, Cast, Studio, Home/Library shelves and Grid. Device profiling of decode/cache pressure and a physical-screen journey remain; these component tests do not establish smoothness on TV hardware.
- **Motion is the highest-priority P0 acceptance track (not complete):** Home, Search, Details recommendation rails (More Like This/Collection), Studio Discover and Cast Filmography now share the fixed-anchor focus indicator sized to the active poster/landscape card; per-card borders are removed from these migrating rows. The indicator observes focus in its draw phase, avoiding shelf recomposition for the frame. Compose tests cover poster/landscape geometry, focus enter/exit, View All transfer, row focus restoration and rounded/square outline painting (the paint helper’s focus/shape branches are covered). Home hero backdrop fades are now 240/200ms (in/out, previously 500/300ms), while info fades are 160/120ms (previously 400/200ms); a rapid right-then-left Compose journey verifies content retargets to the final title and settles within 300ms. Shelf, hub, grid, Search, Details, Cast, Studio and centered profile carousels now share the same no-bounce, 600-stiffness retargetable scroll spring while retaining their own leading/centered anchor geometry. `FocusPivotSpec` now has direct coverage for center/leading anchors, restoration suppression, micro-scroll cutoff and spring creation; its JaCoCo class line, branch, method and instruction counters are all 100%. No physical-device benchmark has demonstrated smoother travel, lower jank or better input-to-frame latency; row retargeting and remaining destinations still need M0–M5 measurements and migration.
- **Motion continuation (2026-10-15):** Hub and View More cards now use the same 2dp focus ring without a focused size change; series rear-layer alpha is static while focus moves. Setup buttons, the Settings sidebar, Details cast cards and studio chips likewise keep fixed bounds on focus and communicate state through their existing border/color treatments. Geometry assertions cover HubCard, SetupButton, the Settings sidebar, cast cards and studio chips; the series stack test verifies the decorative layer contract. Targeted JVM/Compose verification passed 16 tests across `ComponentInteractionRegressionTest`, `HubCardFocusMotionTest`, `SeriesCardStackTest` and `DetailsRowFocusGeometryTest` (0 failures/errors). The full JVM suite then passed 1,192 tests with 0 failures/errors and 2 skipped; `git diff --check` is clean. Focus scaling/transition behavior elsewhere is still under migration, and these emulator-style Compose checks are not a substitute for M0 release traces on real TVs.
- **Navigation motion continuation (2026-10-16):** Removed focus-only zoom from drawer labels, drawer/top-bar profile avatars and profile labels; their existing fixed focus surfaces, border/color feedback and fade/slide affordances remain. New bounds-stability tests cover both navigation modes, and the existing menu directional-entry/selection suite was rerun: 15 tests passed, 0 skipped/failures/errors. `git diff --check` is clean. This does not verify physical-remote cadence or complete the remaining focus-motion migration.
- **Artwork pending-state continuation (2026-10-16):** Poster cards no longer paint title initials over valid artwork while the image request is still pending; initials remain the fallback for a missing URL or a failed request. Added branch tests for missing, pending, successful and failed artwork policy. The outgoing drawable is not explicitly retained while a different title's image loads, and visible-first device decode/cache profiling is still outstanding; this is a targeted correction, not completion of U07/M2.
- **Retained-artwork continuation (2026-10-16):** Poster, landscape backdrop and landscape logo renderers now keep the last successful drawable while a replacement request is pending, then switch to the new drawable when it is ready; absent or failed art clears stale content and uses the existing title fallback. The artwork semantics wrapper keeps its label available while pending/fallback is shown, preserving D-pad focus semantics. Pure policy tests cover retain/replace/clear and fallback decisions; deterministic Compose frame tests cover rendered, pending and failed/fallback states. The first full rerun exposed the lost-label regression; focused Home, Grid, Search, quick-action and Watchlist journeys reproduced it before the fix and passed afterward. The latest full JVM suite passes 1,234 tests (0 failures/errors, 2 skipped). The unit-only JaCoCo report gives `RetainedArtwork.kt` 100% line coverage (49/49); its generated Compose branches/instructions are 64.8%/80.8%, so this is not a claim of 100% branch coverage. `git diff --check` is clean. A delayed-fetch device render test, bounded decode/memory profiling and physical-TV measurements remain; this is an in-progress U07/M2 slice, not motion sign-off.
- **Fixed-focus motion continuation (2026-10-16):** Removed focus-only scale transforms throughout Settings: toggles, option/segmented/color controls, subtitle chips and sliders, language rows, integrations/management buttons, theme cards/editor controls, dashboard tabs/cards/actions/sliders and hub-shape choices. Also removed geometric zoom from Details action buttons and Add-on/import rows/buttons; their existing border/color feedback, Details label reveal and addon dialog fades remain. Compose assertions cover fixed focused bounds across settings, theme editing, dashboard, details actions and addon lists. The broader settings/editor slice passed 21 tests; the details/addon slice passed 17 tests, all with 0 failures/errors/skips. The complete JVM suite passed 1,207 tests with 0 failures/errors and 2 skipped; `git diff --check` is clean. Focus/back ownership, remaining non-settings destinations and physical-remote behavior remain open under U03/U16.
- **Focus-geometry continuation (2026-10-16):** Removed remaining focus-only zoom from profile PIN/avatar/theme selection controls, player action/error/track/subtitle panels and the catalog Grid Back control. Their selected/focused colors, borders and progress feedback remain; dialog entry and non-focus pulse animation were left intact. Added fixed-bounds focus assertions for profile creation/avatar choice, Skip Intro and Grid Back. Targeted player/profile checks and the Grid journey pass; the full JVM suite passed 1,210 tests with 0 failures/errors and 2 skipped. `git diff --check` is clean. This consolidates fixed geometry but still needs TV frame-time and remote-cadence measurement before any claim of smoother real-device motion.
- **Focus recomposition continuation (2026-10-16):** Removed focus-only `zIndex` writes from shared poster/landscape cards and from Hub/View More cards. The shared title-card surfaces already have fixed geometry and the shelf indicator is drawn above them; there is no scaling/overlap left that requires per-focus z-order changes. Hub/View More retain their existing focus tint state. Added focus-bounds checks for poster, landscape and View More alongside the HubCard check. The complete JVM suite passed 1,199 tests with 0 failures/errors and 2 skipped; `git diff --check` is clean. This removes avoidable state/layout invalidation but does not demonstrate frame-time improvement on hardware.
- **U03, in progress (prioritized Back ownership, 2026-10-16):** The main activity now installs a single shell Back dispatcher with explicit Navigation → Screen → Route → Player → Preview → Modal precedence (same-priority ties go to the newest active layer). Main-tree screens, player, trailer, settings/dialog and navigation surfaces register through it; D-pad Back key previews use the same route and suppress the platform callback that follows the same press. The duplicate guard expires after the immediate dispatch window so a later Back cannot be swallowed if Android did not emit a paired callback. Standalone Account Entry and YouTube Trailer activities retain local AndroidX handlers. Eleven dispatcher/Compose tests cover empty/disabled registrations, ordering, callback updates/removal, route disposal, key/system duplicate handling and guard expiry, shell fallback, nested modal→screen routing and standalone fallbacks. The dispatcher core has 100% JaCoCo line/branch/method/instruction coverage, and the BackNavigation composable source has 100% line coverage; Compose compiler-generated branches remain uncovered. The latest full JVM suite passed 1,224 tests with 0 failures/errors and 2 skipped; `git diff --check` is clean. This is not U03 completion: real Fire TV/Android TV remote repeat, platform-dialog precedence, accessibility Back and focus restoration after route changes still need device acceptance; no `adb`/TV is available in this environment.
- **U04, in progress (shared focus-motion specification, 2026-10-16):** Added explicit Saab motion tokens for 140ms focus feedback, 180ms label reveal, 240ms navigation reveal, 600-stiffness no-bounce retargeting, and the existing hero backdrop/info fade timings. Migrated the shared focus spring, Home hero transitions, drawer/top-navigation affordances and Details action strip onto those tokens; focus geometry stays fixed. A direct unit test exercises every SaabMotion spec and timing, and JaCoCo reports the new SaabMotion class at 100% line, instruction, method and class coverage. Targeted focus/navigation/hero/action tests and the complete JVM suite pass (1,224 tests, 0 failures/errors, 2 skipped); `git diff --check` is clean. Still open: migrate remaining destinations consistently, test long/localized text, contrast, font scaling and reduced motion, and measure input-to-frame/settle response on supported TV tiers. This is a token/adoption slice, not U04 completion or measured proof of smoother hardware motion.
- **U04 visual-system continuation (2026-10-18):** The mobile and TV Material schemes now derive their surface-variant and foreground roles from the selected theme; requested theme text colors are preserved when readable and safely fall back to the higher-contrast neutral when they are not. Theme typography follows the active theme for title and secondary roles. Shared 2dp focus geometry/color and common control corner/minimum sizes are now tokens used by the shelf focus frame and setup buttons. Added a matrix across all built-in themes plus hostile custom colors, both Material schemes, long setup copy and 1.35× font scale. Targeted visual, focus, and reduced-motion tests pass; the full JVM suite passes. Physical low-end/modern TV frame-response sign-off remains unavailable here, so U04's software acceptance is implemented while its hardware motion measurement is still open.
- **Motion-token adoption continuation (2026-10-16):** Settings layout-mode padding now uses the shared 240ms navigation reveal; Settings sidebar focus colors/borders, Playback preference controls and Integrations management toggles now use the 140ms focus-feedback easing. The Details watched toggle now uses the no-bounce shared retarget spring instead of a medium spring. `FocusPivotSpecTest`, Settings journey, Playback settings and Integrations interaction tests pass, followed by the full 1,224-test JVM suite (0 failures/errors, 2 skipped). JaCoCo still reports SaabMotion itself at 100%. Other destinations retain deliberate per-control timings and remain to be audited; no device frame-time result yet.
- **Fixed-pivot rail geometry continuation (2026-10-16):** Search now gives its first card the same start offset as the fixed focus frame and enough trailing space for the last card to reach that pivot. Details recommendations, Cast Filmography and Studio Discover calculate end padding from their active card width (including landscape cards) rather than assuming 120dp. A pure geometry helper has portrait, landscape and narrow-viewport tests; JaCoCo reports its only method at 100% instruction/line/method coverage. Search Compose tests now verify both first- and last-card alignment in poster and landscape modes. Kotlin compilation and the complete JVM suite pass (1,226 tests, 0 failures/errors, 2 skipped). The full aggregate coverage task could not refresh emulator data because two Android desugaring artifacts are unavailable offline; a JVM-only JaCoCo report was generated, and no physical TV/remote validation has been run.
- **Finite-shelf edge continuation (2026-10-16):** Removed synthetic tail space from bounded Home, Hub, Search, Details, Cast and Studio shelves. The shared focus frame now follows the selected lazy item’s actual viewport offset when a real content boundary prevents reaching the normal pivot; repeated Home shelves select the visible copy nearest that pivot. Search Compose tests verify first-card pivot alignment and last-card/ring alignment without blank trailing scroll area in poster and landscape modes; geometry unit cases cover finite edges, repeated copies and missing-target fallback. The remaining Profile and Settings section transitions now use the shared 240ms navigation reveal token instead of 500/300ms and 300/300ms fades. Full JVM suite passes (1,227 tests, 0 failures/errors, 2 skipped); `git diff --check` is clean. JaCoCo could not be regenerated offline because `org.jacoco.ant:0.8.11` is not cached. The focus/frame behavior still needs physical remote/frame-timing validation; this is an in-progress U03/U04/U06 slice, not completion.
- **Combined regression and benchmark checkpoint (2026-10-16):** `:app:unitRegressionCoverage` now completes successfully, running all 1,234 JVM tests and 32 Android emulator tests (0 failures/errors; 2 JVM tests skipped). The merged JaCoCo report currently covers 29,528 of 48,779 lines (60.5%) and 19,504 of 45,377 branches (43.0%); these are whole-app totals, not UI-only coverage. The new retained-artwork renderer remains at 49/49 covered lines. The opt-in emulator macrobenchmark generated its Baseline Profile and completed cold-start timing, but the rapid shelf-focus journey skipped: this test install opens `AccountEntryActivity` without an accessible Home/Search or Continue Offline action. The benchmark now attempts the local-recovery route when offered, but still needs an explicit authenticated/fixture-backed Home setup. Emulator timing is smoke-only, and no physical Fire TV motion result is available. This checkpoint does not close M0–M5 or U03/U04/U07/U19.
- **Focus-indicator render-path follow-up (2026-10-16):** Edge-clamped outline movement now applies its pixel correction in a `graphicsLayer` instead of a layout-placement modifier, keeping the focus frame size stable while avoiding shelf placement work for indicator-only scroll updates. Existing draw, Search first/last-card geometry, Home-shelf, Details, Cast and Studio Compose tests pass. A fresh full `:app:unitRegressionCoverage` run passed all 1,234 JVM tests and all 32 emulator tests (0 failures/errors; 2 JVM skips); aggregate coverage is 29,530/48,781 lines (60.5%) and 19,506/45,377 branches (43.0%). `git diff --check` passes. This is a targeted render-path optimization; frame-time benefit still requires release traces on physical low-end and modern TVs.
- **Late Home-rail focus continuation (2026-10-16):** Added a Compose regression journey using the real `InfiniteLoopRow` focus nodes inside a keyed vertical lazy list. It focuses a title, inserts a delayed recommendation shelf above it, and verifies the focused title remains focused while its logical address resolves to the new shelf index. Existing resolver tests also cover row insertion/reordering, content movement, duplicate-title shelf identity and removal fallback. The focused Compose test passes. This confirms the current stable row keys plus canonical focus address work for this insertion case; it does not certify every asynchronous refresh/removal path or physical-TV focus behavior.
- **Shared route/dialog reveal continuation (2026-10-16):** Profile PIN, Add-on dialogs and Details content reveal now use the shared 240ms no-surprise navigation reveal token, replacing three slightly different screen/dialog timings. Profile, Add-on and Details JVM/Compose test packages pass. This narrows timing drift but does not establish hardware frame pacing or complete the all-screen motion audit.
- **Parity ledger and motion-token adoption (2026-10-16):** Added `UI_FEATURE_PARITY_LEDGER.md`, mapping J01–J14 to current owners, regression evidence and still-open end-to-end proof. Home preview information now uses the shared hero-info timings; navigation placement and trailer promotion use the shared navigation-reveal spec. `git diff --check` passes. A fresh Gradle run could not start because this environment has no installed Java runtime/JDK; previous test checkpoints are historical, not verification of this change. These are foundation slices only; no screen migration or hardware acceptance gate is closed.
- **Motion benchmark, in progress:** `TvStartupBenchmark.homeShelfFocusTravelFrameTiming` now records `FrameTimingMetric` during rapid horizontal movement, a mid-flight reversal, and vertical row changes. The benchmark source compiles; it has not been executed because this environment has no `adb`/connected TV, so it provides a measurement path, not performance evidence yet.

- **U03/U06 navigation and shelves continuation (2026-10-18):** Grid/View All focus and viewport state is now saveable and scoped to its originating shelf/query. Re-entering the same source retains its canonical item identity and position; opening a different source resets stale selection/scroll state. Search's remembered focus target/item identity is saveable as well. Three new saver/source-isolation regressions pass. The full JVM suite passes 1,474 tests (2 skipped, 0 failures/errors), Android-test Kotlin compilation and both debug APK ABIs succeed, and `git diff --check` is clean. Existing shared Back dispatch, focus-entry, shelf/frame, and route contracts remain the single owners. No connected emulator/Fire TV is available in this run, so physical D-pad repeat/reversal, process-death navigation restoration on hardware, and release-device frame-time acceptance remain open; U03/U06 are software-covered for these paths but not hardware-signed-off.
- **Most recent JaCoCo checkpoint before the motion continuation (2026-10-15):** `:app:unitRegressionCoverage` passed with 1,186 tests, 0 failures/errors and 2 skipped. `HomeFocusRestorationKt` and the shared `FocusPivotSpec` each showed 100% line, branch, method, instruction and class coverage. Aggregate coverage was 59.7% line (29,087/48,708), 42.0% branch (18,986/45,184) and 55.8% instruction (289,586/518,737). That report combines JVM results with previously collected emulator coverage; connected Android instrumentation was not rerun, so it is not fresh emulator verification. A temporary JDK/test home under `/private/tmp` was used because the machine's system Java and normal user-library writes are unavailable.
- **Still a release gate:** run on a physical low-memory Fire TV and at least one modern TV. Emulator success does not validate actual remote repeat cadence, focus rendering, frame pacing or device-specific performance.
- **Not complete:** no other work package is marked complete. The app-shell prototype and broad screen replacement have not started; this is a measured first slice, not a claim that the roadmap is implemented.
- **Earlier device evidence:** 32 Android-TV emulator tests were reported passing before this checkpoint. They were not rerun now, and `adb` is unavailable in the current shell; no physical-TV acceptance has been performed.
- **Current status:** focus restoration and shared Search/Details recommendation artwork-preload slices compile and pass in the full JVM suite. §5 includes a timed first-session blueprint, frontstage/backstage handoff contracts and explicit recovery-preservation rules; the traceability tables link trust moments to tests and system owners. These are planning artifacts; they do not mean the broad UI migration or physical-device gate is complete.

## 12. Delivery gates

### Gate A — Design proof

Produce coherent screen/state designs for first launch, Home, Search, Details/episodes, Library, preview/fullscreen, player/seek and settings/recovery. Include poster and landscape, not only attractive landscape hero shots. Review the D-pad path and text hierarchy using real remote input where possible. A static image cannot validate motion or Back behavior.

### Gate B — Executable experience prototype

Build an in-app development screen/journey using fixture content and the real new components. Home, Search and Details demonstrate one shared card/focus/motion system. Include late images, slow provider replies and held-key release. This is a prototype, not a replacement APK presented as a completed redesign.

### Gate C — Production-connected vertical slice

Connect Home → Details → Play → Back through existing commands. Preserve source/title identity, local state and return focus. Measure on the weakest supported reference TV. If this slice is not demonstrably better, fix the system before spreading it to other screens.

### Gate D — All-screen parity

Migrate remaining screens, run the preference/action matrix and tests, and remove duplicate renderer paths. A development-only switch may help compare old/new during migration; it is not permission to ship two permanent implementations.

### Gate E — Release confidence

Record release-build performance and scripted tests, preserve rollback artifacts/mappings and run upgrade-over-existing-data checks. Start with local testing and a controlled beta. Publishing/releases remain separate explicit user actions. A visual redesign does not authorize database migrations or cloud changes.

## 13. Measurement plan and provisional budgets

Measure independently: cold process launch, warm return, first actionable Home, input acknowledgement, scroll settling, visible artwork readiness, cached/network details, provider search completion, feature first frame, trailer first frame and seek-preview response. Report cache hit/miss and provider/network conditions; do not average away slow tails.

| Measure | Initial target / gate | Method |
| --- | --- | --- |
| Focus acknowledgement | p95 under 50ms in normal browse | Input trace to first visible state change; not scroll completion |
| Rendering | <1% deadline misses on reference TV, <3% on lowest supported reference TV in defined journeys | FrameTimingMetric/Perfetto in release; actual refresh deadline, e.g. 16.67ms at 60Hz |
| Long stalls | No unexplained UI stalls >100ms in fixture browse/overlay tests | Main-thread and rendering traces; investigate rather than hide in an average |
| Cached usable details/return | p95 under 300ms on reference device | Content actionable, not entire TMDB enrichment finished |
| Warm actionable Home | Establish baseline, then set a device-specific absolute target and required improvement | Cold/warm separately; account preparation measured separately |
| Search | Immediate query feedback; first-result/provider latency reported separately | Local debounce and provider time must be distinguishable |
| Artwork | No avoidable placeholder flash on memory-cache hits; visible roles requested before focus | Request/cache tracing and recorded journeys |
| Trailer promotion | Same session, no re-resolve/restart; transition time measured | Player identity/time-position continuity assertions |
| Seek thumbnail | Ready cache hit appears by next practical render; no blank transition | Fixture/memory/disk cases separate; record target and rendered timestamp |
| Memory | No upward retained-memory trend across 30 repeat journeys; absolute ceiling set from weakest device baseline | Heap/native/graphics/PSS samples after comparable settled states |
| Correctness | Zero wrong-title launch, lost focus, unintended navigation expansion or profile leakage in critical regression matrix | Reproducible scripted assertions; not a statistical promise of zero real-world bugs |

Run fixture benchmarks repeatedly on the same device/build/thermal/network conditions; compare distributions, not one best run. Include a long session after caches are warm and the TV is thermally constrained. Emulator instrumentation verifies behavior but cannot establish physical-TV performance.

Baseline Profiles must cover actual discovery/search/details/player journeys. Do not force full eager composition as a speed trick. Keep lazy layouts, stable identity keys, bounded prefetch and state-read isolation as recommended by Compose performance guidance.

## 14. Regression and fixture specification

Use existing tests as a starting point, not proof that all the new contracts are covered. A test-file inventory was inspected; each acceptance contract still needs mapping to an actual assertion.

Fixture pack:

- A fixed mixed catalog with 1,000 identities and multiple pages, plus an empty catalog and a ten-title small catalog. Use known artwork dimensions and controlled availability.
- Movies/series with the same names, remakes, provider aliases, missing IMDb/TMDB IDs and verified/nonverified aliases.
- Long/localized titles, Malayalam/Kannada/Telugu examples, missing logos, bright artwork, entirely missing artwork and failed image requests.
- A series with at least three seasons, specials, watched/partial/unstarted episodes and a last position of S2E5; separate season-final and series-final fixtures.
- Cold, movie-only, series-only and mixed taste histories, all-watched suggestions, sparse provider results and duplicate recommendation candidates.
- Delayed metadata/rail insertion, reversed search responses, credential validation failure, expiring stream URL, trailer resolution failure and process recreation.
- Both card shapes and navigation placements; low-memory mode, font scaling and reduced motion.

Test layers:

1. Unit: action identity, restoration fallback, state transitions, selection/held-key policy, badge semantics and geometry calculations.
2. Compose: every card context, action label/state, dialog/footer visibility, keyboard/results traversal, partial data and profile switch.
3. Device: remote repeat, Back ownership, trailer surface promotion, player controls, focus after removed actions and process recreation.
4. Screenshot: all visual states at fixed fixture content/fonts/densities, both shapes, bright/dark backdrop and missing artwork.
5. Macrobenchmark: scripted release journeys and frame timing; no internet dependency in UI regression benchmarks.
6. Real-network smoke: provider/integration/stream behavior and two-device continuity separately, without making nondeterministic services the only regression oracle.

Do not promise 100% visual/functionality coverage based on line coverage. Require every identified critical journey and error state to have a named test or explicit manual acceptance record, and report untested branches honestly.

## 15. Customer validation protocol

Run moderated sessions spanning returning viewers, series followers, undecided browsing and a person who has not configured the app. Include the actual low-end TV/remote, sofa distance and ordinary room lighting. Start with a small formative group; findings guide iteration, not population-wide statistical claims.

Ask participants to perform tasks without telling them which button to use:

1. Continue the last episode and tell us what episode/position you expect.
2. Find a known short-name title and distinguish its remake.
3. Find a language/genre series using ordinary words.
4. Choose something new and explain why you think it was suggested.
5. Save a title, find it in Library, mark watched, then undo that watched state.
6. Play S2E5 from a details episode list.
7. Open a trailer, unmute/promote it, return, then start that same title.
8. Skip past an opening and locate a scene using thumbnails.
9. Change subtitle language and turn subtitles off.
10. Recover from an unavailable provider without signing out or losing the query.
11. Move to another profile and explain which settings apply to that TV vs profile.
12. Find/export a diagnostic report without interpreting every network error as a crash.

Record completion, wrong turns, repeated presses, moments of uncertainty and perceived ease. Do not explain away a confusing design during the test. Review recordings/traces for the same moments; what feels slow may be delayed feedback rather than raw provider latency.

## 16. Risks, exclusions and open decisions

- A full simultaneous rewrite risks repeating historical regressions. Sequential vertical slices are the delivery method, not a reduction in final scope.
- Real physical-TV access is needed for final motion/memory acceptance. Current emulator tests cannot certify customer-perceived smoothness.
- New search history, negative-feedback recommendations, voice search, undo commands or recommendation weighting may need domain/privacy work. Keep them clearly separate from UI parity and do not imply they already exist.
- Preserve the current details autoplay sound behavior requested by the customer; evaluate surprise in research, but seek agreement before changing that policy.
- Preserve both navigation placements and user card-shape settings. A premium default must not erase existing customization.
- Do not claim UI work fixes cloud durability, backend capacity, source availability, subtitle alignment or thumbnail decoder speed. It can expose the right state and remove presentation overhead.
- No new external analytics, paid design service, dependencies, cloud mutations, credential access or GitHub publication is required for the audit.

Immediate next deliverables: (1) actual preference/action parity ledger, (2) complete Home/Search/Details state designs, (3) one executable fixed-focus browse prototype, (4) recorded baseline and comparison on a physical reference TV. Approve that foundation before migrating every screen.

## 17. Primary references and interpretation

- Android TV navigation: https://developer.android.com/training/tv/get-started/navigation — remote reachability and visible focus.
- Android TV focus system: https://developer.android.com/design/ui/tv/guides/styles/focus-system — consistent focused state and hierarchy.
- Android TV immersive lists: https://developer.android.com/design/ui/tv/guides/components/immersive-list — contextual selected-item preview.
- Android TV navigation drawer: https://developer.android.com/design/ui/tv/guides/components/navigation-drawer — standard vs modal behaviors.
- Compose performance: https://developer.android.com/develop/ui/compose/performance/bestpractices — avoid needless recomposition/work; stable lazy-list identity.
- Compose Baseline Profiles: https://developer.android.com/develop/ui/compose/performance/baseline-profiles — optimize actual important journeys.
- Android slow rendering: https://developer.android.com/topic/performance/vitals/render — understand rendering deadlines and measurement limitations; do not assume a Views-only vitals dashboard covers every Compose issue.
- WCAG 2.2: https://www.w3.org/TR/WCAG22/ — use text contrast, non-text contrast, visible focus and controllable motion as design checks. WCAG is web guidance; this document does not claim formal Android-app conformance. Start at 4.5:1 normal text, 3:1 large text and 3:1 relevant non-text controls, then verify over actual artwork; a focus outline needs suitable contrast, not merely a white color.

All prioritization, timing targets, layouts, user tasks and migration packages above are Saab TV proposals. No undocumented Netflix/Prime implementation details or invented benchmark results are used.

## 18. Continuation — shared focus feedback

2026-10-16: Continued the motion-contract migration by routing focus-driven color transitions in add-on import/list, profile selection, integration rows, settings, dashboard/hub editors and theme selection through `SaabMotion.focusFeedbackSpec()`. The shared focus feedback duration is now 120 ms, the upper end of the proposed 80–120 ms feedback range. A unit assertion pins that contract so future local defaults do not silently diverge.

This is a bounded consistency slice, not completion of the UI roadmap. Discovery/detail/player parity, additional screen-local transitions, reduced-motion behavior, full journey coverage and physical-TV motion acceptance remain open. Emulator/unit tests do not establish perceived smoothness on Fire TV hardware.

## 19. Foundations and Home continuation

2026-10-16: The Home/catalog card-to-details transition is now shared by both top-navigation and side-navigation placements, keeping the selected canonical ID/type, artwork metadata, playback identity, resume hint and browse return destination in sync. Added a Compose journey using the real `HomeScreen` and fixture-backed `HomeViewModel`: focus a catalog card, send a remote-style long-press, mark the title watched, verify the state change, open that same title, dispatch shell Back through the route priority/policy, and verify the same title regains focus. Added a selection projection regression for series identity, artwork, add-on origin and playback identity. The first run exposed that the test asserted before Home's bounded asynchronous focus restoration completed; it now waits on the visible focus contract rather than an arbitrary sleep. The full JVM suite passes 1,236 tests (0 failures/errors, 2 skipped), including Home late-rail, canonical-address, Back precedence and focus-pivot coverage. The prior broad worktree contains unrelated user changes; no cleanup/reset was done.

This closes neither U08 nor the Home acceptance gate. The new journey exercises the production Home composable/ViewModel and production Back dispatcher/policy in a fixture host, but not the full `MainActivity` against a seeded release database. The macrobenchmark still lacks a deterministic authenticated Home fixture, and no connected device/physical Fire TV is available here to validate remote-repeat cadence, frame timing, artwork cache/memory pressure or the full navigation-placement matrix. J03/J04/J05 and U01–U08 therefore remain in progress pending those gates.

## 20. Navigation focus-entry safety continuation

2026-10-17: The shared `NavigationFocusGuard` now treats only the configured D-pad entry direction (left for the drawer, up for the top bar) as an explicit request to enter navigation. Back and Escape still flow through Back handling but no longer authorize a later restored focus to activate the menu. Added a top-navigation Compose regression that presses Back and Escape from content, simulates restored navigation focus, and verifies focus returns to content. The full JVM suite passes 1,255 tests (0 failures/errors, 2 skipped), and `git diff --check` is clean. This narrows one focus-theft path; it does not close the cross-surface focus/Back journey or physical-remote acceptance gates, and no connected Android TV device is available in this environment.

## 21. Inline trailer promotion continuation

2026-10-17: Fullscreen promotion now animates the existing inline trailer overlay from its current centered bounds to the viewport while retaining one ExoPlayer and one `PlayerView`; the inline footer contracts during the same transition. Added geometry regressions for start, midpoint, completion and out-of-range animation progress. The focused trailer suite and full JVM suite pass (1,256 tests, 0 failures/errors, 2 skipped), and `git diff --check` is clean. J08 remains partial: actual decoder continuity, audible fullscreen playback, Back/focus restoration and frame pacing still require supported physical-TV validation; this environment has no connected Android TV device.

## 22. U05 — Cards/actions

2026-10-18: Unified the compact horizontal title-card action strip across Home, Continue Watching and the catalog quick-action popup, with watched/watchlist state mutations unavailable until their initial profile state has resolved. The shared poster/landscape card now uses the visual-system focus tokens, and held-center actions are release-triggered using elapsed key duration so remotes that omit repeat events still open quick actions without turning short presses into long presses. Expanded the fixture-backed live badge journey across Home, Movies, Series, More Like This, Collection, Filmography, My Library and Search in both poster and landscape shapes. The focused U05 suite passes 32 tests (0 failures/errors/skips). Remaining acceptance is physical-remote cadence and Fire TV geometry/contrast; no hardware device is attached here.

## 23. U07 — Artwork

2026-10-18: Completed the software artwork path across title-card surfaces: one request builder defines role, decode dimensions, scaling and cache keys for both visible renderers and prefetch; shelf/grid warming is visible-first with only a bounded two-card focus neighborhood; and the existing Coil memory/disk budgets bound retained and prefetched images. Same-title metadata enrichment retains the last successful drawable. A first valid request no longer flashes initials/title fallback while loading; fallback appears only for missing or failed artwork. Added policy and Compose regressions for pending, success, missing and failed states. Full JVM tests pass (1,468; 2 skipped, no failures/errors) and both debug APK ABIs assemble. TV memory/decode-pressure profiling and physical-screen acceptance remain unverified because no `adb`/TV target is available; U07 is software-complete but still awaits hardware acceptance.

## 24. U08 — Shell/Home

2026-10-18: Persisted the selected navigation destination through Activity recreation using a stable key saver that tolerates unknown values and the string keys for every current destination. Added complete route save/restore tests and expanded the production Home journey to include the real drawer/focus-handoff host, a card quick action and watched mutation, Details entry, production Back dispatch, and focus restoration to the same title. Full JVM suite: 1,471 tests, 2 skipped, 0 failures/errors; armv7a and arm64 debug APKs assemble; `git diff --check` passes. Remaining U08 acceptance is explicitly device-bound: validate physical D-pad traversal/cadence, process-death Home focus restoration, seeded release-database startup/navigation, and frame time/artwork pressure on Fire TV. No connected TV/emulator is available in this environment, so the software result is not a claim of hardware sign-off.
