# Saab TV — screen-by-screen workflows

These are flowcharts of the current app implementation, traced through the Compose screens, view models, repositories, and shell routing. They describe the existing route and data handoffs, not a proposed redesign. “Back to browse” means the selected Home/Movies/Series/OTT/Search/My Library destination remains selected; the shell restores that screen’s entry/focus target.

## Screen index

| Screen | Flowchart |
|---|---|
| App entry and profile selection | [1](#1-app-entry-and-profile-selection) |
| Shared navigation shell | [2](#2-shared-navigation-shell) |
| Home | [3](#3-home) |
| Movies | [4](#4-movies) |
| Series | [5](#5-series) |
| OTT | [6](#6-ott) |
| Search | [7](#7-search) |
| My Library | [8](#8-my-library) |
| View More / full catalog grid | [9](#9-view-more--full-catalog-grid) |
| Title details | [10](#10-title-details) |
| Cast / crew filmography | [11](#11-cast--crew-filmography) |
| Studio / company titles | [12](#12-studio--company-titles) |
| Resume and playback | [13](#13-resume-and-playback) |
| Settings overview and subsections | [14](#14-settings-overview-and-subsections) |
| Account and cloud sync | [15](#15-account-and-cloud-sync) |
| Title-card actions and user-state updates | [16](#16-title-card-actions-and-user-state-updates) |

## 1. App entry and profile selection

```mermaid
flowchart TD
    A[Launch app] --> B{Active profile restored?}
    B -- Yes --> C[Restore profile and theme]
    B -- No --> D[Show profile selection]
    D --> E{Choose existing profile or create/edit?}
    E -- Existing --> F[Load profile]
    E -- Create/edit --> G[Profile setup wizard]
    G --> H[Save profile and initialize/copy preferences]
    H --> F
    C --> I[Open app shell at Home]
    F --> I
    I --> J[Load selected screen for active profile]
```

The current profile scopes preferences, watched state, library, and playback history. Profile selection and onboarding are in [`ProfileScreen.kt`](../app/src/main/java/com/saab/tv/ui/profiles/ProfileScreen.kt) and [`ProfileViewModel.kt`](../app/src/main/java/com/saab/tv/ui/profiles/ProfileViewModel.kt); profile restoration and the shell entry are coordinated in [`MainActivity.kt`](../app/src/main/java/com/saab/tv/MainActivity.kt#L860).

## 2. Shared navigation shell

```mermaid
flowchart TD
    A[App shell] --> B{Navigation placement}
    B -- Left --> C[Navigation drawer]
    B -- Top --> D[Top navigation bar]
    C --> E{Selected destination}
    D --> E
    E --> H[Home catalog]
    E --> M[Movies catalog]
    E --> S[Series catalog]
    E --> O[OTT catalog]
    E --> Q[Search]
    E --> L[My Library]
    E --> P[Profile selection / logout]
    E --> T[Settings]
    E --> X[Exit confirmation]
    H --> F[Focus enters screen content]
    M --> F
    S --> F
    O --> F
    Q --> F
    L --> F
    T --> F
    F -->|Back from content| C
    F -->|Back from nested route| R[Pop nested route or close overlay]
    R --> F
    H -->|Select title| DT[Details]
    M -->|Select title| DT
    S -->|Select title| DT
    O -->|Select title| DT
    Q -->|Select result| DT
    L -->|Select title| DT
    DT -->|Back| F
```

The shell has typed `Menu`, `Grid`, `Details`, `Resume`, and `Player` states. The selected tab is tracked separately, so returning from a title does not inherently select Profile. The left/top destinations and focus targets are defined in [`NavigationDestinations.kt`](../app/src/main/java/com/saab/tv/ui/navigation/NavigationDestinations.kt#L6); shell state and callbacks are in [`MainActivity.kt`](../app/src/main/java/com/saab/tv/MainActivity.kt#L874). Back actions use the prioritized dispatcher in [`BackNavigation.kt`](../app/src/main/java/com/saab/tv/ui/navigation/BackNavigation.kt#L15).

## 3. Home

```mermaid
flowchart TD
    A[Enter Home] --> B[HomeViewModel.loadScreen home profile]
    B --> C[Observe watch history, next-up, watched IDs]
    B --> D[Fetch first catalog batch and hub rows]
    D --> E[Resolve configured hero row]
    E --> F[Publish initial rows so Home becomes usable]
    F --> G[Warm metadata for likely visible titles]
    F --> H[Fetch remaining catalog rows in background]
    F --> I{Personalized TMDB rails enabled and key available?}
    I -- Yes --> J[Load enabled personalized rails asynchronously]
    I -- No --> K[Keep standard Cinemeta/add-on catalogs]
    H --> L[Append remaining rows]
    J --> L
    F --> M{User action}
    M -- Select title --> N[Details]
    M -- Continue Watching title --> O[Resume or Player]
    M -- View More --> P[Full catalog grid]
    M -- Quick action --> Q[Watchlist / watched state mutation]
    M -- Trailer --> R[Inline or fullscreen trailer flow]
```

Home, Movies, and Series use the same [`HomeScreen.kt`](../app/src/main/java/com/saab/tv/ui/home/HomeScreen.kt#L160) surface and [`HomeViewModel.kt`](../app/src/main/java/com/saab/tv/ui/home/HomeViewModel.kt#L1297) loader. Home additionally observes continue-watching/next-up and can request enabled personalized TMDB rails; those are published after the initial catalog batch. “View More” switches the shell into `Grid` in [`MainActivity.kt`](../app/src/main/java/com/saab/tv/MainActivity.kt#L1193).

## 4. Movies

```mermaid
flowchart TD
    A[Choose Movies] --> B[Load movies dashboard configuration]
    B --> C[Fetch initial movie rows and hubs]
    C --> D[Publish rows and warm visible metadata]
    D --> E[Fetch remaining rows and pages as needed]
    E --> F{Title-card action}
    F -- Select --> G[Movie details]
    F -- View More --> H[Movie hub grid]
    F -- Quick action --> I[Update watched or watchlist]
    G -->|Back| D
    H -->|Back| D
```

The tab is `DashboardTab.MOVIES`, rendered through the shared catalog surface. Movie card identity and metadata are canonicalized before presentation. Movie details resolve Cinemeta first for standard IMDb IDs, then use add-on fallback when needed; TMDB is an enrichment source rather than a prerequisite for opening the screen. See [`DashboardTab.kt`](../app/src/main/java/com/saab/tv/domain/DashboardTab.kt#L6), [`HomeViewModel.kt`](../app/src/main/java/com/saab/tv/ui/home/HomeViewModel.kt#L1297), and [`AddonRepository.kt`](../app/src/main/java/com/saab/tv/data/repository/AddonRepository.kt#L758).

## 5. Series

```mermaid
flowchart TD
    A[Choose Series] --> B[Load series dashboard configuration]
    B --> C[Fetch initial series rows and hubs]
    C --> D[Publish rows and warm visible metadata]
    D --> E[Fetch remaining rows and pages as needed]
    E --> F{Title-card action}
    F -- Select --> G[Series details]
    F -- View More --> H[Series hub grid]
    F -- Quick action --> I[Update watched or watchlist]
    G --> J[Resolve series details and canonical episode list]
    J -->|Back| D
    H -->|Back| D
```

Series shares the catalog screen but its details/episode identity is series-aware. The repository treats Cinemeta as the canonical source for series details and episode numbering; alternate add-on episode numbering is not allowed to replace that list. See [`AddonRepository.kt`](../app/src/main/java/com/saab/tv/data/repository/AddonRepository.kt#L758) and [`DetailsViewModel.kt`](../app/src/main/java/com/saab/tv/ui/details/DetailsViewModel.kt#L218).

## 6. OTT

```mermaid
flowchart TD
    A[Choose OTT] --> B[Load OTT catalog rows]
    B --> C{Fresh memory/disk cache available?}
    C -- Yes --> D[Show cached rows]
    C -- No --> E[Query JustWatch catalog groups]
    E --> F[Resolve candidates and canonical title metadata]
    F --> G[Publish provider/type rows and persist last-good cache]
    E -->|Failure with last-good rows| D
    E -->|Failure without usable cache| H[Show retryable load error]
    D --> I{User action}
    I -- Select title --> J[Details]
    I -- View More --> K[Full grid]
    I -- Refresh --> E
    H -->|Retry| E
```

OTT rows come from [`OttCatalogRepository.kt`](../app/src/main/java/com/saab/tv/data/ott/OttCatalogRepository.kt#L109), with JustWatch groups for provider top titles, newly added titles, and overall trending across movies and series. Cinemeta resolves the underlying title; individual provider failures are isolated so one bad group need not discard all other rows. OTT loading is a separate path and does not run Home’s personalized TMDB rails.

## 7. Search

```mermaid
flowchart TD
    A[Enter Search] --> B[Default filter: Movies]
    B --> C[Type query or choose recent query]
    C --> D{Query length}
    D -- Under 2 --> E[Keep entry UI; do not search]
    D -- 2 chars and explicit submit --> F[Submit immediately]
    D -- At least 3 chars --> G[Debounce typing 250 ms]
    F --> H[Fetch Cinemeta movie and series catalogs]
    G --> H
    H --> I{TMDB natural-language search enabled?}
    I -- Yes --> J[Discover TMDB results in parallel]
    I -- No --> K[Use Cinemeta results]
    J --> L[Canonicalize and deduplicate provider batches]
    K --> L
    L --> M[Show Movies and Series result rails]
    M --> N{User action}
    N -- Select title --> O[Details]
    N -- View More --> P[Search full grid for selected media type]
    N -- Change filter --> Q[Show Movies or Series results]
    N -- Back --> R[Return focus to Search entry or navigation]
```

The search screen’s default filter is Movies, with Series as the other media filter. Cinemeta movie/series catalog searches run together; if TMDB search is enabled for the profile and a key is configured, natural-language discovery is run in parallel and results are canonicalized/deduplicated. Search state preserves recent searches and the last focused result for the active session. See [`SearchScreen.kt`](../app/src/main/java/com/saab/tv/ui/search/SearchScreen.kt#L48) and [`SearchViewModel.kt`](../app/src/main/java/com/saab/tv/ui/search/SearchViewModel.kt#L35).

## 8. My Library

```mermaid
flowchart TD
    A[Enter My Library] --> B[Attach profile-scoped Room flows]
    B --> C[Combine history, watchlist and series next-up]
    C --> D[Project Continue Watching, Watchlist and Watched]
    D --> E[Canonicalize title cards]
    E --> F[Apply status, Movies/Series and sort filters]
    F --> G[Render non-empty sections]
    G --> H{User action}
    H -- Continue Watching --> I[Resume selected playback ID]
    H -- Select title --> J[Details]
    H -- Quick action/remove --> K[Commit user-state command]
    K --> L[Room emits updated library]
    L --> F
    J -->|Back| Restore[Restore previous card, else first available]
    I -->|Playback Back| J
```

Room-backed watch history, watchlist, and series next-up are the Library inputs. The screen preserves a focused item/section and has a first-card fallback when the old item is gone. See [`WatchlistViewModel.kt`](../app/src/main/java/com/saab/tv/ui/watchlist/WatchlistViewModel.kt#L31), [`MyLibraryItems.kt`](../app/src/main/java/com/saab/tv/ui/watchlist/MyLibraryItems.kt#L48), and [`WatchlistScreen.kt`](../app/src/main/java/com/saab/tv/ui/watchlist/WatchlistScreen.kt#L90).

## 9. View More / full catalog grid

```mermaid
flowchart TD
    A[Choose View More on a rail] --> B[Capture source key and focused item]
    B --> C[Open shared GridViewScreen]
    C --> D{Grid source}
    D -- Home/Movies/Series catalog --> E[Load additional catalog pages]
    D -- Search Movies/Series --> F[Load more TMDB search pages when available]
    E --> G[Update grid while preserving scroll/focus]
    F --> G
    G --> H{User action}
    H -- Select title --> I[Details; return target is Grid]
    H -- Load more --> D
    H -- Back --> J[Return to originating browse screen]
    I -->|Details Back| C
```

Home rails and Search View More callbacks both set the shell to `Grid`; the grid retains source, scroll, and focused-item state. See [`GridViewScreen.kt`](../app/src/main/java/com/saab/tv/ui/home/GridViewScreen.kt) and the route callbacks in [`MainActivity.kt`](../app/src/main/java/com/saab/tv/MainActivity.kt#L1372).

## 10. Title details

```mermaid
flowchart TD
    A[Select a title from any browse surface] --> B[Capture title identity, media type, artwork and return destination]
    B --> C[Open Details route]
    C --> D[Resolve canonical Cinemeta/add-on metadata]
    D --> E[Publish usable title details]
    E --> F{Series?}
    F -- Yes --> G[Resolve episodes, progress and next-up]
    F -- No --> H[Prepare movie actions]
    E --> I{TMDB enabled and not playback-only?}
    I -- Yes --> J[Enrich missing details, recommendations, collection/cast as available]
    I -- No --> K[Keep canonical provider payload]
    E --> L[Warm available trailer metadata]
    G --> M[Render details, artwork and actions]
    H --> M
    J --> M
    K --> M
    L --> M
    M --> N{User action}
    N -- Play / Start Watching --> O[Load/select source]
    N -- Trailer --> P[Trailer playback]
    N -- Episodes --> Q[Episode sidebar; choose episode]
    N -- Cast/crew --> R[Cast filmography route]
    N -- Studio/company --> S[Studio titles route]
    N -- Related/collection title --> T[Push another Details route]
    N -- Watchlist/watched --> U[Commit library/history command]
    O --> V[Resume / Player]
    Q --> O
    P -->|Return| M
    R -->|Back| M
    S -->|Back| M
    T -->|Back| M
    M -->|Back at root| W[Restore originating Grid or browse tab]
```

Details first resolves the selected title and publishes its canonical provider data; TMDB enrichment and trailer lookup proceed independently. For standard IMDb movie IDs Cinemeta is tried first; series details/episode numbering use Cinemeta, with add-on fallback for other metadata cases. TMDB may add missing enrichment and recommendations but does not need to block first usable details. See [`DetailsScreen.kt`](../app/src/main/java/com/saab/tv/ui/details/DetailsScreen.kt#L163), [`DetailsViewModel.kt`](../app/src/main/java/com/saab/tv/ui/details/DetailsViewModel.kt#L218), and [`AddonRepository.kt`](../app/src/main/java/com/saab/tv/data/repository/AddonRepository.kt#L758).

## 11. Cast / crew filmography

```mermaid
flowchart TD
    A[Choose cast/crew member in Details] --> B[Push cast_detail route]
    B --> C[Load person data and filmography]
    C --> D[Render profile and title cards]
    D --> E{Select filmography title}
    E -- Yes --> F[Push Details route]
    E -- No; Back --> G[Pop to previous Details route]
    F -->|Back| D
```

The cast route is nested under the Details `NavHost`; it does not replace the outer browse destination. See [`CastDetailScreen.kt`](../app/src/main/java/com/saab/tv/ui/cast/CastDetailScreen.kt#L88) and route registration in [`MainActivity.kt`](../app/src/main/java/com/saab/tv/MainActivity.kt#L1697).

## 12. Studio / company titles

```mermaid
flowchart TD
    A[Choose studio/company in Details] --> B[Push studio_detail route]
    B --> C[Load entity title catalog]
    C --> D[Render entity information and titles]
    D --> E{Select title}
    E -- Yes --> F[Push Details route]
    E -- No; Back --> G[Pop to previous Details route]
    F -->|Back| D
```

This is another nested Details route and uses its own entity kind/source type. See [`StudioDetailScreen.kt`](../app/src/main/java/com/saab/tv/ui/studio/StudioDetailScreen.kt#L94) and [`MainActivity.kt`](../app/src/main/java/com/saab/tv/MainActivity.kt#L1718).

## 13. Resume and playback

```mermaid
flowchart TD
    A[Play from Details or Continue Watching] --> B[Resolve selected playback identity]
    B --> C[Load candidate streams and subtitles]
    C --> D{Selected source}
    D -- HTTP/other URL --> E{Player preference}
    E -- Internal --> G[Open Player]
    E -- Ask --> F[Player choice]
    F --> G
    E -- External --> X[Launch external player]
    D -- Magnet --> H[Start torrent service and try ranked fallback sources]
    H --> I{Local stream ready?}
    I -- Yes --> G
    I -- No --> J[Show source error/retry path]
    G --> K[First rendered frame: playback established]
    K --> L[Playback controls and right-side panels]
    L --> M[Audio, subtitles, sources, cast, episodes, seek]
    L --> N[Periodically persist progress]
    N --> O[Room history + account sync outbox]
    L --> P{End, next episode, or Back}
    P -- Next episode --> C
    P -- End/Back --> Q[Persist final progress and track/source choices]
    Q --> R[Return to Details]
    R --> S[Details Back restores originating Grid or browse tab]
```

The details play callback constructs the exact selected title/source context before entering `Resume` or `Player`. Playback persists progress locally and requests account sync; seeking, stream selection, cast and track panels are owned by [`PlayerScreen.kt`](../app/src/main/java/com/saab/tv/ui/player/PlayerScreen.kt#L67) and [`PlayerViewModel.kt`](../app/src/main/java/com/saab/tv/ui/player/PlayerViewModel.kt#L40). The shell callback returns to Details after playback session state is committed in [`MainActivity.kt`](../app/src/main/java/com/saab/tv/MainActivity.kt#L2543).

## 14. Settings overview and subsections

```mermaid
flowchart TD
    A[Choose Settings from app navigation] --> B[Settings screen]
    B --> C[Sidebar starts on Personalization]
    C --> D{Choose a section}
    D --> AC[Account]
    D --> PE[Personalization]
    D --> TH[Theme]
    D --> HO[Home Screen]
    D --> PL[Playback]
    D --> SF[Sort & Filter]
    D --> AD[Add-ons]
    D --> IN[Integrations]
    D --> DG[App Diagnostics]
    D --> AB[About]
    AC -->|Back| C
    PE -->|Back| C
    TH -->|Back| C
    HO -->|Back; invalidate Home cache| C
    PL -->|Back| C
    SF -->|Back| C
    AD -->|Back| C
    IN -->|Back| C
    DG -->|Back| C
    AB -->|Back| C
    C -->|Back| N[Return focus to app navigation]
```

The settings sidebar and right-hand pane are a single screen with section-specific content. D-pad selection changes the displayed subsection; moving focus right enters its first available control. Back from a control returns to the selected section; Back from the sidebar returns focus to app navigation. Implemented sections are enumerated in [`SettingsScreen.kt`](../app/src/main/java/com/saab/tv/ui/settings/SettingsScreen.kt#L59).

### Account settings

```mermaid
flowchart LR
    A[Settings: Account] --> B[View account/session and sync actions]
    B --> C{Choose account action}
    C --> D[Sign in / sign out / sync / restore]
    D --> E[Account operation result or recoverable error]
    E -->|Back| A
```

Screen: [`AccountSettingsScreen.kt`](../app/src/main/java/com/saab/tv/ui/account/AccountSettingsScreen.kt#L65).

### Personalization settings

```mermaid
flowchart LR
    A[Settings: Personalization] --> B[Read current profile preferences]
    B --> C[Change a preference]
    C --> D[Persist profile setting]
    D --> B
    B -->|Back| A0[Settings sidebar]
```

Includes profile-level viewing and title-card choices; screen: [`SettingsSubScreens.kt`](../app/src/main/java/com/saab/tv/ui/settings/SettingsSubScreens.kt#L147).

### Theme settings

```mermaid
flowchart LR
    A[Settings: Theme] --> B[Choose existing theme or create/edit]
    B --> C[Apply selected theme to profile]
    C --> A
    B -->|Cancel/Back| A
```

Screen implementation is in [`SettingsSubScreens.kt`](../app/src/main/java/com/saab/tv/ui/settings/SettingsSubScreens.kt) and theme management is provided by `ThemeManager`.

### Home Screen editor

```mermaid
flowchart LR
    A[Settings: Home Screen] --> B[Edit dashboard categories, ordering or hero]
    B --> C[Save dashboard configuration]
    C --> D[Invalidate Home catalog cache]
    D --> A
    B -->|Back| A
```

The shell invalidates the Home view model after editor back/save so configuration changes are reflected on re-entry. Screen: [`DashboardEditorScreen.kt`](../app/src/main/java/com/saab/tv/ui/settings/DashboardEditorScreen.kt).

### Playback settings

```mermaid
flowchart LR
    A[Settings: Playback] --> B[Edit profile playback preferences]
    B --> C[Persist setting]
    C --> B
    B -->|Back| A0[Settings sidebar]
```

Screen: [`SettingsSubScreens.kt`](../app/src/main/java/com/saab/tv/ui/settings/SettingsSubScreens.kt#L270).

### Sort & Filter settings

```mermaid
flowchart LR
    A[Settings: Sort & Filter] --> B[Edit source ranking/filter preferences]
    B --> C[Persist preference]
    C --> B
    B -->|Back| A0[Settings sidebar]
```

Screen: [`SettingsSubScreens.kt`](../app/src/main/java/com/saab/tv/ui/settings/SettingsSubScreens.kt#L1844).

### Add-ons

```mermaid
flowchart LR
    A[Settings: Add-ons] --> B[Browse installed add-ons]
    B --> C{Install, configure, update or remove}
    C --> D[Apply add-on change]
    D --> B
    B -->|Back| A0[Settings sidebar]
```

Screen: [`AddonsScreen.kt`](../app/src/main/java/com/saab/tv/ui/addons/AddonsScreen.kt#L64).

### Integrations

```mermaid
flowchart LR
    A[Settings: Integrations] --> B[Choose integration]
    B --> C[Configure credentials/options]
    C --> D[Validate or save integration]
    D --> B
    B -->|Back| A0[Settings sidebar]
```

Screen: [`IntegrationsScreen.kt`](../app/src/main/java/com/saab/tv/ui/settings/IntegrationsScreen.kt#L67).

### App Diagnostics

```mermaid
flowchart LR
    A[Settings: App Diagnostics] --> B[Inspect application and playback events]
    B --> C[Scroll, filter or export/share diagnostics]
    C --> B
    B -->|Back| A0[Settings sidebar]
```

Screen: [`PlaybackDiagnosticsSettings.kt`](../app/src/main/java/com/saab/tv/ui/settings/PlaybackDiagnosticsSettings.kt#L73).

### About / update

```mermaid
flowchart LR
    A[Settings: About] --> B[View app/build information]
    B --> C[Check for update or open available app action]
    C --> D[Show update state/result]
    D --> B
    B -->|Back| A0[Settings sidebar]
```

Screen: [`SettingsSubScreens.kt`](../app/src/main/java/com/saab/tv/ui/settings/SettingsSubScreens.kt#L2166).

## 15. Account and cloud sync

```mermaid
flowchart TD
    A[Account state change or user-state command] --> B[Commit local Room transaction]
    B --> C[Append/update sync outbox and revision]
    C --> D{Signed in, unlocked and network available?}
    D -- No --> E[Keep local state; retry when eligible]
    D -- Yes --> F[AccountSyncManager schedules sync]
    F --> G[Push pending commands/snapshot]
    G --> H[Pull remote account state]
    H --> I[Reconcile remote state into local Room]
    I --> J[Room flows refresh screens]
    E -->|Eligibility changes / retry| F
```

User-state changes are written locally first; cloud sync is an asynchronous follow-up and does not own the visible screen state. The command boundary is [`UserStateCommandRepository.kt`](../app/src/main/java/com/saab/tv/data/account/UserStateCommandRepository.kt#L23); sync orchestration is [`AccountSyncManager.kt`](../app/src/main/java/com/saab/tv/data/account/AccountSyncManager.kt#L230). Trakt synchronization is a separate integration path.

## 16. Title-card actions and user-state updates

```mermaid
flowchart TD
    A[Focus title card] --> B[Show canonical artwork and available badges]
    B --> C{Select, long-press or preview action}
    C -- Select --> D[Open title Details]
    C -- Long-press --> E[Quick-action popup]
    E --> F{Choose action}
    F -- Add/remove watchlist --> G[Toggle library command]
    F -- Mark/unmark watched --> H[Update history/series next-up]
    G --> I[Commit Room + sync outbox]
    H --> I
    I --> J[Room flow updates badge/Library immediately]
    C -- Trailer preview --> K[Inline trailer]
    K -->|Start Watching| L[Use the focused card's playback identity]
    L --> M[Resume / source selection / Player]
```

The card UI is shared across browse, Search, Details-related rows, filmography, and Library. Mutations use the shared user-state command boundary; Room updates are local-first and the outbox is synchronized afterward. See [`HomeViewModel.kt`](../app/src/main/java/com/saab/tv/ui/home/HomeViewModel.kt#L249), [`DetailsViewModel.kt`](../app/src/main/java/com/saab/tv/ui/details/DetailsViewModel.kt#L760), and [`UserStateCommandRepository.kt`](../app/src/main/java/com/saab/tv/data/account/UserStateCommandRepository.kt#L23).

## Metadata and ownership quick map

| Concern | Current owner / behavior |
|---|---|
| Shell routes and browse return | `MainActivity` + `ShellView` / `BrowseReturnPolicy` |
| Home, Movies, Series | Shared `HomeScreen` + `HomeViewModel`, configured per tab/profile |
| OTT | `OttCatalogRepository` + JustWatch client; Cinemeta resolves title metadata |
| Search | `SearchScreen` + `SearchViewModel`; Cinemeta catalog search and optional parallel TMDB discovery |
| Details | `DetailsScreen` + `DetailsViewModel`; canonical provider data first, optional asynchronous TMDB enrichment |
| My Library | `WatchlistScreen` + `WatchlistViewModel`; profile-scoped Room history/watchlist/next-up projection |
| Playback | `PlayerScreen` + `PlayerViewModel`; streams, controls, progress, seek thumbnails and playback panels |
| Canonical metadata | `CanonicalFieldResolver`: identity-verified source priority; localized language is a tie-break within source priority, not a way for TMDB to replace populated higher-priority Cinemeta fields |
| Cloud user state | Local Room transaction/outbox first, then `AccountSyncManager` push/pull/reconciliation |

The canonical field authority is implemented in [`CanonicalFieldResolver.kt`](../app/src/main/java/com/saab/tv/domain/schema/CanonicalFieldResolver.kt#L10). The current priority for localized title/synopsis fields is user override (where supported), Cinemeta, TMDB, add-on, then legacy; for an already-present Cinemeta value, a language preference does not promote TMDB above it.

## Browse return and focus rules

| Situation | Current route/focus behavior |
|---|---|
| Enter a browse destination | Shell selects a destination-specific entry requester; Home/Movies/Series/OTT share the catalog entry target |
| Open title from a browse tab | Save title selection and `previousView`; open Details |
| Open title from View More grid | Save `Grid` as return target; Details Back returns to Grid |
| Back from nested cast/studio/related details | Pop the nested Details `NavHost` route |
| Back from root Details | Return to Grid if origin was Grid; otherwise return to the selected browse screen |
| Back from Player | Persist session state and return to Details; the next Back returns to originating browse/grid |
| Search Back | If a result is focused, first return to Search entry; otherwise hand focus to app navigation |
| My Library Details return | Restore the remembered section/card; if unavailable, use the first available title |
| Settings Back | Content controls return focus to selected subsection; subsection sidebar returns focus to app navigation |

The concrete root-details return decision is [`BrowseReturnPolicy.kt`](../app/src/main/java/com/saab/tv/ui/navigation/BrowseReturnPolicy.kt#L6), while the caller and focus token are in [`MainActivity.kt`](../app/src/main/java/com/saab/tv/MainActivity.kt#L1495). Search and Library have screen-specific Back/focus handlers.
