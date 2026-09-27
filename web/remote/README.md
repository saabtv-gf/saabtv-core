# Phone Companion

Static HTTPS interface for sign-in, signup, links, search, profile photos and
home-screen artwork. Hosted at `https://saabtv-gf.github.io/saabtv-core/`.
Open a phone tool on the TV and scan its QR. Without a valid pairing link, entry
and submission remain disabled. Both devices need internet, not the same Wi-Fi.
No local HTTP/TLS server, certificate exception, analytics or browser storage.

## Protocol and deployment

- GitHub Pages hosts only HTML, CSS and JavaScript. Neon Data API relays ciphertext.
- AES-256-GCM with fresh 96-bit IVs and session/direction/message-ID authenticated
  data. Fresh key and separate 256-bit read/write capabilities per pairing.
- QR fragments carry only the phone write capability, session ID and encryption
  key. Fragments are removed from browser history immediately. No DB-owner secret
  is included. Login/signup still requires confirmation on the TV.
- Server-side five-minute expiry, role/capability isolation, idempotent message
  IDs, acknowledgements, bounded storage (64 sessions; 1 MB pending ciphertext
  each), one pending message per session, one text/account/photo submission or
  at most 40 artwork submissions. Closing erases manifest and pending ciphertext.
- Photo files up to 5 MB are cropped/resized on the phone, then sent as JPEG up to
  512 KB. Original images are never uploaded. Artwork uses the TV's permitted item
  list and aspect ratio. Remove-artwork requires phone confirmation.
- Apply `cloud/neon/003_pairing_relay.sql` once as owner after approval. This was
  applied to the configured production branch. Do not rerun blindly. Its private
  tables have no anonymous/authenticated direct access; only narrow RPCs execute
  with owner privileges. Existing account RLS/policies are unchanged.
- `https://saabtv-gf.github.io` is the approved Neon Auth origin. Data API uses
  bearer tokens; anonymous tokens cannot read account snapshots.
- `.github/workflows/pages.yml` tests crypto and publishes only four public files,
  not this README, tests, Android code, SQL or configuration files.

## Validation and remaining risks

Run `node --test test/*.test.mjs` locally. Live relay-only tests require explicit
authorization and `SAAB_PAIRING_LIVE=1`; they never create or authenticate accounts.
`live-relay.mjs` tests all tools, encrypted payloads, roles, cross-session access,
limits, idempotency, acknowledgement cleanup and close. `expiry-relay.mjs` tests
actual five-minute expiry. `browser-relay.mjs` simulates the TV receiver.

Expiry immediately denies access; expired rows are physically purged on the next
session creation, not by a scheduled job. PostgreSQL history/backups may retain
ciphertext. Encryption keys are not stored in Neon. Keep QR codes private.
An attacker with a QR can submit to that pairing; TV confirmation protects account
authentication, but paste/search/artwork accepts the paired phone. Internet abuse
can exhaust the bounded pairing capacity; provider-level edge rate limiting is
recommended before wider distribution. Pairing traffic polls only while its TV
dialog is open. Physical Fire TV and Safari validation remains necessary.

Do not fetch a local HTTP endpoint from an HTTPS page, send passwords over HTTP,
or suppress certificate validation as a workaround.
