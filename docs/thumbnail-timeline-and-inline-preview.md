# Seek preview correctness and inline trailer layout

## Thumbnail mismatch paths

- The old mpv seek used nearest keyframes and accepted up to 20 seconds of error.
  A seek timeout did not prevent the old frame from being cached.
- Cache lookup could substitute a nearby cached frame while the carousel labeled
  it with a different requested time.
- Cache identity was only profile/title scoped, allowing different releases or
  mid-playback source changes to reuse incompatible timelines.
- Alternate low-resolution releases were chosen by metadata, not evidence that
  their cuts, lead-ins and timestamps matched the playing file.
- `initDetailed` does not start the wrapper event thread. The native diagnostic
  bridge must dispatch queued lifecycle events while polling; otherwise a strict
  playback-restart gate would never acknowledge a new frame.

The corrected path uses the fast source selected by ThumbnailSourceSelector,
including eligible cached 720p/1080p files, with file-scoped cache identity,
exact mpv seeks, restart acknowledgement and a 100 ms timestamp tolerance. Failed
or unverified captures are not stored. Platform fallback asks for the closest
decoded frame instead of the closest keyframe (Android does not expose its PTS).
The carousel and confirmation seek share the same interval-grid timestamp, clear
stale images immediately, and never substitute a different cached timestamp.
Media3 confirmation seeks use exact seeking. Version-4 derived thumbnail caches
replace older caches; watch progress and preferences are untouched.

The same-playback-file constraint was removed at the user's request; other timing
and cache safeguards remain. Alternate releases may have different cuts or lead-in
timings, so accurate timestamps in that file cannot guarantee scene equivalence
with the main playback file.

## Inline trailer layout

The in-layout transformation was reverted at the user's request. Catalogue cards,
row heights, and grid spans remain fixed. A screen-bounded 360 dp landscape overlay
uses a 16:9 video area and a 72 dp footer, with a 240 ms eased expansion over the
focused card. If an anchor is unavailable, a centered bounded overlay is used;
an absent anchor no longer silently switches the preview into fullscreen.
Details pages always use fullscreen previews; catalogue previews honor the saved
presentation setting. Manual trailer actions also always open fullscreen.
The host owns one player across inline/fullscreen switches. Controls are rendered
outside the poster/row key handlers, remain visible inline, and the fullscreen
button explicitly changes presentation while retaining the current position.
Only fullscreen controls hide after five seconds. Navigation rendering is below
the active overlay. Focus restoration and directional navigation use the persistent
host scope so disposing the overlay cannot cancel navigation.
Back restores the original browsing focus and directional navigation dismisses
the player before moving to another card. Remote or pointer activity on the
details page resets its configured trailer-autoplay delay until the user is idle.

## Episode advancement

Only a valid IntroDB outro marker can initiate an automatic next-episode countdown.
Percentage/time fallback and natural completion without that marker can show a
manual next-episode prompt, but never automatically advance.

## Verification limits

Unit tests cover seek timestamp rejection, restart readiness, grid alignment,
source identity and IntroDB-only automatic advancement. Release compilation and
signing are checked. Physical-TV decoder/frame comparisons, remote focus and
animation smoothness still require device validation. No universal cache-speed
or pixel-perfect equivalence guarantee is made.
