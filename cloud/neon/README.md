# Saab TV accounts

The Android app talks directly to Neon Managed Auth and the Neon Data API over HTTPS. No database-owner password or custom API server is included in the app.

## Database setup

Run `001_accounts.sql` once as the database owner, after enabling Managed Auth and the Data API. Refresh the Data API schema cache afterwards. This migration has already been applied to the configured production branch. Do not run it again without checking the existing policy.

The availability RPC returns only a boolean. Account snapshots are protected by forced row-level security using the authenticated Neon user ID. The caller-only identity helper reads the `sub` claim from the JWT validated by the Data API (`request.jwt.claims`); it does not grant access to Neon-owned auth tables. Writes use revision-based compare-and-swap to prevent silent overwrites between TVs. `002_identity_helper.sql` corrects the originally deployed policy; both scripts are reflected in the configured production deployment.

## Login and storage

- Usernames are case-insensitive, 3–32 ASCII letters, digits or underscores. The UI uses an internal `username@accounts.saabtv.invalid` email alias for managed email/password authentication.
- Signup validates at least eight characters with uppercase, lowercase, digit and special character. Live testing confirmed that Neon rejects six-character passwords; the UI follows the provider's eight-character minimum. Email verification must not be required for the internal aliases.
- Password recovery is intentionally unavailable for now, per product decision. The username uses a non-deliverable internal email alias, so a recovery key by itself cannot restore a Neon Auth session or authorize a server-side password reset. Do not reset passwords through the console; the encrypted backup may become unrecoverable. Revisit only with a verified recovery email or an explicitly approved recovery service plus a tested snapshot-key rewrapping migration.
- The stable managed-auth user ID selects a separate Room database, encrypted integration preferences, profile files and thumbnail cache on each TV.
- Portable snapshots include profiles, themes, PIN data, settings, addons, integrations, watchlists, progress, source choices, subtitle choices and custom profile/hub images. Videos, thumbnails, diagnostic logs and login sessions are not uploaded.
- Backups are AES-GCM encrypted on the TV. The encryption key is derived using PBKDF2-HMAC-SHA256 (210,000 iterations) from the password and stable account ID. Session credentials and the derived key are kept in Android Keystore-backed encrypted preferences.
- Local PIN/runtime state remains subject to Android app sandbox protection. Cloud encryption does not replace a device lock or protect a rooted TV.
- Existing local profiles can be explicitly imported once. Original data is retained. Sign-out keeps the isolated local account cache; it does not delete the account or cloud backup.
- Sync is event-driven: progress is batched for up to 120 seconds, settings changes are debounced, and explicit playback/background boundaries can flush promptly. Unchanged data is not uploaded. Offline playback continues with cached account data. Sync v3 uses per-entity revisions, idempotent operations and tombstones; causal edits win after observing a remote revision, while simultaneous offline edits use a deterministic tie-break. The encrypted whole-account snapshot remains only for migration/compatibility and its legacy conflict policy is timestamp-directed. See the v2 transfer and v3 protocol sections below.
- A cloud restore is journaled as ciphertext so an interrupted restore can safely replay at next launch. Account changes restart the app process to clear account-owned singleton state.

## Validation

`smoke-test.rb` uses only the public service URLs and refuses to run unless
`SAAB_QA_CREATE_USERS=1` is explicitly set. It creates two disposable QA accounts
and checks username availability, sign-in, JWT access, v2 revision conflicts,
v2/v3 account isolation, and v3 push/pull. After testing, it attempts account
deletion, but the configured managed service currently returns 404 for
`/delete-user`; complete cleanup in the Neon Console using only the exact generated
QA usernames printed by the script, and verify that none remain. It must never be
used with a real account. All six accounts from the implementation's three
approved QA rounds were removed and the zero-remaining count was verified.

With migration 006 installed, add
`SAAB_QA_VALIDATE_006=1` to verify direct-write denial and the actual PostgREST
429/PT429 mapping at the 31st push and 13th pull. This adds at most 30 small v3 QA
rows to the second temporary account before the expected throttle. Add
`SAAB_QA_VALIDATE_413=1` as a separate, explicit opt-in to send one 20 MiB+ test
request and verify the 413/PT413 mapping; this incurs deliberate network transfer
and is not part of the default smoke test. `SAAB_QA_VALIDATE_PUSH_BYTES=1` opts in
to two ~13.6 MiB v3 pushes, verifying that the aggregate 25 MiB/minute byte cap is
independent of the single-request cap; the first request creates ~13.6 MiB of
temporary encrypted-state test rows on the disposable account. It also incurs
deliberate transfer and storage churn. Example:

```sh
SAAB_QA_CREATE_USERS=1 SAAB_QA_VALIDATE_006=1 ruby cloud/neon/smoke-test.rb
SAAB_QA_CREATE_USERS=1 SAAB_QA_VALIDATE_006=1 SAAB_QA_VALIDATE_413=1 ruby cloud/neon/smoke-test.rb
SAAB_QA_CREATE_USERS=1 SAAB_QA_VALIDATE_006=1 SAAB_QA_VALIDATE_PUSH_BYTES=1 ruby cloud/neon/smoke-test.rb
```

Run these only with approval for disposable test accounts and cleanup. Do not run
them against production accounts; the current Managed Auth deletion route requires
manual cleanup in the Neon Console.

Local validation is recorded in `docs/CENTRAL_INFORMATION_SCHEMA_PLAN.md`; its latest checkpoint is 1,100 JVM tests (0 failures, 2 skipped), 31/31 API 36 emulator instrumentation tests, and passing lint and policy coverage gates. These are not live Neon or physical-TV acceptance results. Account UI, legacy import, multi-TV restore, process restart, 32-bit Fire TV playback and release/ABI install/upgrade still need representative-device validation before calling the release production-proven.

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

## Sync v3 (migration applied)

`005_sync_v3_state.sql` is applied to the production branch. Postflight verified
protocol version 3, all three v3 tables and both RPCs, forced row-level security,
no direct authenticated table reads, authenticated RPC execution and preservation
of the v2 snapshot table. The app negotiates v3 and retains v2 as a compatibility
path. V3 stores encrypted per-entity operations with idempotent operation IDs,
portable identities, causal entity revisions and deletion tombstones. A local edit
after observing remote revision N is emitted at a higher revision; truly concurrent
offline edits use the deterministic `(entity_revision, device_id)` tie-break, not
reconstructed wall-clock order. Detailed client/schema behavior and tests are in
`docs/CENTRAL_INFORMATION_SCHEMA_PLAN.md`.

The client limits one v3 pass to four batches of at most 4 MiB each. Remaining
operations continue on the batched cadence. This limits one TV to 16 MiB per pass
and leaves some quota headroom; it cannot guarantee the account aggregate when
multiple TVs sync concurrently, so the server remains the enforcement point.

The postflight did not validate a live two-TV user journey, conflict/deletion
recreation against production, or old-client coexistence. Those remain release
acceptance gates and require controlled test accounts/devices; deterministic
multi-database tests do not replace them.

## Sync v3 abuse limits (migration 006 applied)

`006_sync_v3_abuse_limits.sql` wraps the encrypted snapshot and v3 sync mutation
RPCs with authenticated per-account fixed-window limits: at most 30 pushes and
25 MiB of aggregate push request bodies per minute, 12 v3 pulls per minute, and a
20 MiB cap on one v3 push request. Direct authenticated inserts/updates to account
state and direct inserts/deletes to encrypted component objects are revoked;
reads remain account-scoped and write RPCs keep their compare-and-swap checks. The
previous RPC bodies become owner-only internals. Quota state contains only the
account ID and counters; it is not directly readable by app roles and is removed
with the auth account. The Data API maps throttles to HTTP 429; the app waits one
minute before retrying that quota window. An HTTP 413 payload-size response is
deferred until an explicit retry or new local mutation instead of being uploaded
repeatedly.

Applied to the approved Neon production primary endpoint on 2026-10-12. Postflight
verified the private quota schema/table, wrapper execution grants, owner-only
internal RPCs, revoked direct writes, retained account-scoped reads, forced v3 RLS,
and preservation of the v2 snapshot table. Existing snapshots were not deleted.
These checks verify installed permissions and objects; they do not exercise the
live HTTP 429/413 boundaries or prove that real multi-device burst sizes fit the
limits. Those behavioral checks remain opt-in in `smoke-test.rb` and must use
disposable accounts with approved cleanup. No production account was used for a
quota stress test.

Do not run `VACUUM FULL` or delete auth data to force the storage dashboard lower.
Live-table/TOAST/index sizes and obsolete row versions need measurement first;
removing unused objects does not promise an immediate drop in allocated storage.
