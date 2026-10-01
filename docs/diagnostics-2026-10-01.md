# Diagnostics review — 1 October 2026

The supplied 0.1.79-beta export contains 80 network request failures and 35
trailer preview playback failures. There are no recorded uncaught exceptions,
ANRs or out-of-memory events in this export. This does not prove that no crash
occurred outside the retained history.

## Findings and changes

- Network failures include IOExceptions, socket errors and HTTP/2 stream resets.
  The old export omits exception messages and request context, so it cannot
  distinguish cancellation from a genuine connectivity problem retrospectively.
  Cancelled OkHttp calls now use a non-error event. Genuine failures retain their
  stack; optional logging adds method, host and elapsed time, never URL paths,
  query parameters or credentials.
- All trailer failures are caused by Media3 InvalidResponseCodeException in
  the custom YouTube chunk data source. The previous export omitted the response
  status, so an exact server-side reason cannot be established from that report.
  Structured HTTP status and playback codes are now retained without recording
  response bodies, signed URLs or arbitrary exception messages.
- Prefer HTTP Range on the untouched signed URL. Query-range compatibility
  fallback retains encoded query values and removes an existing range parameter
  instead of adding a duplicate. Terminal HTTP responses advance to the next
  extracted variant without retrying the same rejected URL multiple times;
  transient server/network errors retain Media3's normal retry policy.
- Inline-to-fullscreen expansion previously recreated the media-source effect
  and restarted variant selection at index zero. Expansion now changes track
  constraints/presentation only, preserving the successful variant and position.
  Progressive streams retain their playing resolution; adaptive manifests can
  select a higher rendition when expanded.
- Sidebar focus is observed on its actual focus group, with explicit vertical
  neighbours independent of animated layout geometry. The browse root attempts
  to restore its current navigation item on a directional key if all focus has
  been lost. Trailer overlays retain control of their own remote input.
- Recoverable exception persistence now uses the bounded background writer;
  fatal uncaught exception persistence remains synchronous before process exit.

## Verification limits

Unit tests cover cancellation classification/privacy, terminal HTTP status
classification and every sidebar vertical neighbour. Release lint and a signed
armeabi-v7a build are part of local verification. Physical TV focus/input,
inline-to-fullscreen playback and live YouTube CDN availability need device QA.
These changes cannot guarantee playback of every remotely rejected YouTube URL.

Media3 reference:
https://developer.android.com/reference/androidx/media3/datasource/HttpDataSource.InvalidResponseCodeException
