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
- Sync is debounced and retried periodically while the app runs. Offline playback continues with cached account data. A conflicting cloud copy requires choosing Restore Cloud or Keep This TV in Account settings; it is not merged automatically.
- A cloud restore is journaled as ciphertext so an interrupted restore can safely replay at next launch. Account changes restart the app process to clear account-owned singleton state.

## Validation

`smoke-test.rb` uses only the public service URLs. It creates two disposable QA accounts and checks username availability, sign-in, JWT access, revision conflicts and cross-account/anonymous isolation. It attempts account deletion, but the configured managed service currently returns 404 for `/delete-user`; complete cleanup in the Neon Console using only the exact generated QA usernames printed by the script, and verify that none remain. Running it requires explicit authorization to create and permanently clean up test data. It must never be used with a real account. All six accounts from the implementation's three approved QA rounds were removed and the zero-remaining count was verified.

Local validation: 134 unit tests passed; release lint reported no errors; the 32-bit release APK signature and package metadata verified. The Android snapshot round-trip/isolation instrumentation test compiles, but was not executed because no Android device/emulator was connected. Account UI, legacy import, multi-TV restore and process restart still need physical-TV validation before calling the release production-proven.

Rotate any database-owner credential previously shared in chat. Never put it into source, Android resources, Gradle properties or an APK.
