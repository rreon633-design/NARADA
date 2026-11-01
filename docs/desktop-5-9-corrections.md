# NARADA — Post-pass corrections and recovery finding

> 31 October 2026 · **Source changes only: not rebuilt or retested**

The original pass remains **342 passed / 6 failed**. Ten source files differ from that pass. Their original bytes, before/after SHA-256 values and reasons are preserved in [the correction record](../artifacts/desktop-5-9/post-pass-corrections.json) and [unified diff](../artifacts/desktop-5-9/post-pass-corrections.patch). Nothing in this page is a replacement test result.

## Corrections for recorded failures

- Lifecycle test: expect metadata schema 6, not 4. No migration file was changed.
- Content tests: expect the existing `PERMISSION_DENIED` authorization contract where applicable. Pin-specific `ACCESS_DENIED` expectations are retained.
- Malformed-request test: inspect the actual parse-rejection envelope, including its empty correlation ID and `INVALID_REQUEST`. Normal valid-request correlation checks remain strict.
- Browser test: derive the direct RPC port from the active test-server configuration rather than the historical hard-coded port.
- Chat CSS: explicitly inherit the light/dark bubble text color instead of the global muted paragraph color. No post-correction accessibility pass is claimed.

## Additional recovery finding from source review

The tested version refused several **partial** private-store-loss combinations, but deleting the key, database and guard together could be mistaken for first-time content initialization. That gap was found by reading the source, **not** by a completed new test.

The correction now:

1. Records the local **public content-identity fingerprint** in the existing `connection_config` JSON document in metadata SQLite. No message, private key, session, draft or private attachment manifest is stored there.
2. Preserves that internal fingerprint atomically when network configuration changes. It is not accepted as an owner-writable `ConnectionConfig` field.
3. Opens a marked store in existing-only mode and checks its identity before startup mutations. Existing-only mode cannot create replacement key, database or account state.
4. Binds a valid unmarked private store before exposing content service.
5. Refuses ambiguous **existing** profiles that lack both an enrollment marker and a complete private store. It does not guess that encryption never existed. A genuinely new profile can initialize; an old ambiguous profile needs preservation and an explicit new-profile decision if recovery is impossible.

This is conservative: a pre-chat legacy profile may need a separately created, explicitly enrolled profile to use messaging. Existing files and trust metadata are not automatically deleted or migrated into a replacement identity. See [recovery guidance](content-recovery.md).

The fingerprint is **not** a monotonic anti-rollback counter. Matching rollback of all protected content state, including its guard and the same identity, remains outside the guarantee. Independent review and crash/native testing are still mandatory.

## New regression source, not executed

Two Rust tests cover sticky marker/config behavior and bound-store total loss. Two process tests cover loss after network-config changes and ambiguous unmarked legacy enrollment. They are **not counted** among the 348 tests executed in the recorded pass.

The final packaging script also distinguishes the 31 October handoff date from the 30 October validation date.

## Verification boundary

The corrected Rust, CSS and test source has not been rebuilt or tested. The recorded Windows GNU executable and frontend build were produced **before** these corrections and are not distributed as validated current binaries. No preview is left running. Release remains blocked both by the failed pass and by the changed source, in addition to external qualification gates.
