# Native Trailer Playback

Trailer buttons use Cinemeta's `trailerStreams.ytId` / legacy `trailers.source`
metadata, then resolve public YouTube video/audio URLs on the TV. Media3 merges
separate adaptive video and audio, so playback is not limited to low-resolution
combined files. No iframe, WebView, remote resolver, or YouTube login is used.

Variants are ordered by resolution across clients; decoding/network errors move
to the next variant. HLS uses the highest supported track. The quality badge is
updated from the decoded video size. 2160p requires a 4K upload, a reachable
source and a compatible TV decoder; Cinemeta cannot create a missing 4K rendition.
Full-screen trailers use Saab TV's existing PlayerScreen and backend. Backdrop
previews release their decoder on focus change, disable, background, Back or exit.
Trailer playback does not save movie progress or start thumbnail
generation. Retry and Back are available if resolution fails.

## Attribution

The VISIONOS client configuration and adaptive/native playback approach are
adapted from [NuvioTV](https://github.com/NuvioMedia/NuvioTV), GPL-3.0,
revision `7f32b3c548a5d7e7771f654ac963bc530497708f`, specifically
`data/trailer/InAppYouTubeExtractor.kt` and `core/player/TrailerPlayerPool.kt`.
Saab TV retains its existing GPL-3.0 license.

Card sizing and motion are adapted from NuvioDesktop (GPL-3.0), revision
`e166b226d6adda156c0bceda3387e5c6236bb75f`: `ShelfComponents.kt`,
`PosterCardDimensions.kt` and `PosterCardStyleRepository.kt`. Shelf posters
retain compact TV dimensions (120dp in shelves, 140dp standalone, 190dp
landscape), 2:3 ratio and 12dp corners. Focus uses a border, with no card scaling.
Shelf navigation uses a single cancellable 280ms slide;
backdrops preload then slide/crossfade over 420ms without a second image fade.

## Device QA

Playback settings include profile-synced backdrop preview enable/mute controls
and delays of 3, 5, 10 or 15 seconds (default enabled, muted, 5 seconds).
Catalog, watchlist, grid, recommendation, cast and studio title focus
starts a native backdrop trailer. Search and Continue Watching never autoplay
on hover. Mute/Unmute, Start Watching and Add/Remove
Watchlist replace the catalog strip; menus are hidden. Controls hide after five
seconds of actual playback or remote inactivity. The first remote key reveals
hidden controls without activating an invisible button. Back restores the exact
poster and suppresses re-autoplay until another normal-layout poster is focused.

Playback settings choose Inline Card (default) or Fullscreen. Inline keeps
browsing visible; arrows navigate shelves, OK enters its compact controls and
the fullscreen icon expands the same player at its current timestamp. Inline
prefers available 720p variants and caps adaptive resolution to 720p; fullscreen
restores highest-supported selection. The TextureView stays inside the card.
Details automatically preview the main title fullscreen after the same delay,
only while sources/episodes are closed and auto-start playback is not underway.

Startup no longer waits for the per-stream subtitle lookup (previously up to
2.5 seconds), and source lookup gives optional subtitle catalogs only a 150ms
grace period. Profile snapshot saving is parallel to entering the player.
Embedded/off/manual choices prevent a deferred external-subtitle replacement.
Missing or differently matched external subtitles may require one post-first-
frame reprepare; startup is never held for that lookup. Decode buffers and RAM
limits are unchanged. First Frame Rendered diagnostics report source-to-frame
time; no two-second end-to-end result has been measured on a physical TV.
Next-episode Percentage/Time settings apply only when no valid IntroDB outro
exists; Off waits for IntroDB or natural playback end.

Enable basic App Diagnostics to inspect Trailer Resolved Client / Selected
Quality / Decoded Quality events. They contain video IDs, codec and resolution,
never signed media URLs. Undocumented clients may supply a lower ladder or
signature-cipher-only 4K formats; unsupported ciphers are not decrypted by this
resolver. Signed DASH manifests are supported alongside direct and HLS sources.

Test a Cinemeta movie and series with 4K, 1080p and unavailable trailers; confirm
audio, reported resolution, remote seek/pause, retry, Back, background/return,
and decoder fallback on a 1080p Fire TV. YouTube's undocumented player endpoint
can change or reject requests; native extraction cannot guarantee availability.
