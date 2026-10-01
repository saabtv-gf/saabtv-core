# Saab TV accounts

The Android app talks directly to Neon Managed Auth and the Neon Data API over HTTPS. No database-owner password or custom API server is included in the app.

## Database setup

Run `001_accounts.sql` once as the database owner, after enabling Managed Auth and the Data API. Refresh the Data API schema cache afterwards. This migration has already been applied to the configured production branch. Do not run it again without checking the existing policy.

The availability RPC returns only a boolean. Account snapshots are protected by forced row-level security using the authenticated Neon user ID. The caller-only identity helper reads the `sub` claim from the JWT validated by the Data API (`request.jwt.claims`); it does not grant access to Neon-owned auth tables. Writes use revision-based compare-and-swap to prevent silent overwrites between TVs. `002_identity_helper.sql` corrects the originally deployed policy; both scripts are reflected in the configured production deployment.

## Login and storage

- Usernames are case-insensitive, 3–32 ASCII letters, digits or underscores. The UI uses an internal `username@accounts.saabtv.invalid` email alias for managed email/password authentication.
- Signup validates at least eight characters with uppercase, lowercase, digit and special character. Live testing confirmed that Neon rejects six-character passwords; the UI follows the provider's eight-character minimum. Email verification must not be required for the internal aliases.
- There is no email recovery or password-change flow yet. Losing the password can make the encrypted backup unrecoverable. Do not reset passwords through the console without a snapshot re-encryption migration.
- The stable managed-auth user ID selects a separate Room database, encrypted integration preferences, profile files and thumbnail cache on each TV.
- Portable snapshots include profiles, themes, PIN data, settings, addons, integrations, watchlists, progress, source choices, subtitle choices and custom profile/hub images. Videos, thumbnails, diagnostic logs and login sessions are not uploaded.
- Backups are AES-GCM encrypted on the TV. The encryption key is derived using PBKDF2-HMAC-SHA256 (210,000 iterations) from the password and stable account ID. Session credentials and the derived key are kept in Android Keystore-backed encrypted preferences.
- Local PIN/runtime state remains subject to Android app sandbox protection. Cloud encryption does not replace a device lock or protect a rooted TV.
- Existing local profiles can be explicitly imported once. Original data is retained. Sign-out keeps the isolated local account cache; it does not delete the account or cloud backup.
- Sync is event-driven: progress is batched for up to 120 seconds, settings changes are debounced, and explicit playback/background boundaries can flush promptly. Unchanged data is not uploaded. Offline playback continues with cached account data. Revision-based compare-and-swap protects writes; newer local/cloud timestamps decide which whole-account copy wins. See incremental sync below for the new transfer protocol.
- A cloud restore is journaled as ciphertext so an interrupted restore can safely replay at next launch. Account changes restart the app process to clear account-owned singleton state.

## Validation

`smoke-test.rb` uses only the public service URLs. It creates two disposable QA accounts and checks username availability, sign-in, JWT access, revision conflicts and cross-account/anonymous isolation. It attempts account deletion, but the configured managed service currently returns 404 for `/delete-user`; complete cleanup in the Neon Console using only the exact generated QA usernames printed by the script, and verify that none remain. Running it requires explicit authorization to create and permanently clean up test data. It must never be used with a real account. All six accounts from the implementation's three approved QA rounds were removed and the zero-remaining count was verified.

Local validation: 134 unit tests passed; release lint reported no errors; the 32-bit release APK signature and package metadata verified. The Android snapshot round-trip/isolation instrumentation test compiles, but was not executed because no Android device/emulator was connected. Account UI, legacy import, multi-TV restore and process restart still need physical-TV validation before calling the release production-proven.

Rotate any database-owner credential previously shared in chat. Never put it into source, Android resources, Gradle properties or an APK.

## Phone pairing

`003_pairing_relay.sql` adds separate private, encrypted five-minute relay storage
and capability-restricted RPCs for the GitHub Pages phone tools. It does not change
account tables, identity helpers or existing account policies. The approved
migration is applied to production. See `web/remote/README.md` for protocol,
limits, expiry/cleanup semantics and relay-only QA instructions. The exact Pages
origin `https://saabtv-gf.github.io` is configured in Neon Auth.

## Incremental sync (migration applied)

`004_incremental_account_sync.sql` was applied to the production `neondb` branch
on 2026-10-02 with the owner's approval; the Data API schema cache was refreshed.
It preserves existing encrypted backups
until a successful version-2 save. No database credential goes into the app.
Post-migration checks verified protocol version 2, forced row-level security,
no anonymous object/read/save access, and unchanged existing snapshots (two,
1,266,108 encrypted bytes). An authenticated role without a JWT identity saw no
objects and could not save; the validation transaction was rolled back.

- Foreground/startup/resume checks select only `revision,updated_at`. The encrypted
  manifest is fetched only if that revision is not already cached on this TV.
- Progress is partitioned by profile and title/episode. Preferences are partitioned
  by key; other settings tables are separate components; custom images are separate
  immutable components. Changed content receives a new random object ID. Unchanged
  components retain their existing IDs and are not uploaded again.
- A single revision-checked transaction commits the encrypted manifest and changed
  objects together, verifies every retained object, and removes unreferenced objects
  for that account only. Clears/deletions remove manifest references. No plaintext
  titles, paths, preference names or content hashes are stored on the server.
- Components and manifest are gzip-compressed before AES-GCM encryption when this
  reduces size. Decompression is bounded. Object ciphertext is authenticated against
  the account AND object ID. Cached files are ciphertext, never plaintext snapshots.
- Resume refresh reads only the manifest and missing progress components, not custom
  images or unrelated settings. Images still download once when restoring a new TV.
- Pre-migration servers fall back to the original compatible snapshot save protocol;
  revision-only read checks and the encrypted local cache still reduce downloads.
  Full incremental/compressed writes require migration 004 and a process restart.
- After an account adopts v2, older apps cannot restore that account's manifest.
  The legacy save RPC refuses to overwrite its v2 state. Upgrade **all TVs** to the
  new app before the first v2 write; do not roll back to an older APK afterward.
- App Diagnostics and exported reports show seven UTC days of local Data API payload
  counts/bytes, plus the latest snapshot size and changed-component count. These are
  application-body measurements, NOT Neon's billed network measurement; auth, phone
  pairing, headers, transport compression, failed-response bytes and other TVs are
  not included. No credentials, paths, object IDs or payloads are logged.

Before production rollout, validate two-device restore, concurrent CAS writes,
cleared progress, source/subtitle choices, and RLS isolation against migration 004.
Existing unit tests cover partition round-trip, component isolation, progress
deletion, compression, account isolation and decompression limits. They are not a
substitute for live Data API/database or physical-TV testing.

Do not run `VACUUM FULL` or delete auth data to force the storage dashboard lower.
Live-table/TOAST/index sizes and obsolete row versions need measurement first;
removing unused objects does not promise an immediate drop in allocated storage.
