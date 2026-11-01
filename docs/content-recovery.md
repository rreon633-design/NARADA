# NARADA — Private content and offline recovery

> For development profiles and support staff · [Release/support](24-release-support.md)

## Before changing anything

Stop new work, retain the entire affected profile and original wrapping environment, and collect only the allowlisted support summary. Do not upload databases, ciphertext, guards, key wrappers, transport identities, private filenames or crash dumps to a support ticket. A process dump can contain plaintext and secrets.

Never delete `content.key`, `content.db` or `content.guard` to make a lock warning disappear. Never restore an old ratchet database while continuing with newer messages/session state. Do not blindly rename or delete a staging/commit-intent file.

## Decision table

| Symptom | Safe action | Do not do this |
|---|---|---|
| Agent offline | Start the same original profile; review IPC/startup error. | Invent delivery or regenerate identities. |
| Message pending / retry stopped | Check current trust/consent and peer reachability; explicitly retry the saved ID/ciphertext. | Treat send success as recipient persistence. |
| Encryption identity changed | Stop sending and verify the actual devices and original identities. | Silently reset sessions or accept the new key. |
| Protected store locked | Preserve evidence and matching original state; obtain reviewed recovery guidance. | Mix database, WAL, key and guard from different points in time. |
| Attachment paused after restart | Resume explicitly; acknowledged ciphertext chunks are rehashed. | Reconstruct an encryption nonce counter. |
| Unknown ciphertext orphan | Preserve it; it consumes quota and needs manual review. | Adopt it based on its filename or delete unknown data automatically. |
| Attachment authentication failed | Keep the failure state; ask for a freshly sent attachment if necessary. | Export unverified plaintext or open an archive. |
| Export response lost | Retry the persisted operation and destination shown by the owner history. | Guess a new target while publication is uncertain. |
| Filename collision | Choose a new confirmed name where the no-publication outcome is proven. | Replace the existing file. |
| Pin source deleted | Keep the retained local version; review whether to unpin. | Mirror source deletion to the last offline copy. |
| Pin refresh failed | Retain the old copy; review error and use explicit safe retry/check. | Reuse frozen old bytes as the new version. |
| Pin version/history quota | Archive/unpin deliberately; published files remain owner-owned. | Automatically prune edited/conflicted copies. |

## Whole-store loss and explicit enrollment — post-pass source correction

The corrected source records a **public local content-identity fingerprint** inside the existing metadata `connection_config` JSON, independent of `content.key`, `content.db` and `content.guard`. Network-setting writes preserve it. No private key, message, session or conversation binding is moved into metadata.

A marked profile must reopen the existing private store with the same identity. Missing files cannot cause a new key/account to be created. A valid older unmarked private store can be bound on startup; an existing profile with no complete private store and no enrollment marker is ambiguous and is deliberately blocked.

That conservative policy also affects pre-chat legacy profiles. Preserve the old profile and original wrapping environment. If no recoverable content exists, the owner may explicitly choose a **separate, genuinely empty profile directory**, pair again and register folders again. Do not delete the old profile, remove its marker, reuse another profile's key or call an automatic reset a recovery. There is no automatic trust/history migration into the new identity.

This recovery correction and its four new regression tests have **not been built or run**. See [the correction record](desktop-5-9-corrections.md).

A matching rollback of the complete protected content state, including its guard and the same identity, is still not detected. The public fingerprint is not a hardware/OS monotonic counter. Never resume an old ratchet snapshot as if it were current.

## Native storage boundary

Windows DPAPI ties key recovery to the correct user/protection context; copying an encrypted wrapper is not a portable backup key. Linux development uses an explicit test vault supplied outside the browser. Its test key must be retained separately; it is not included in source archives or support summaries. There is no production Linux key-store fallback.

Full-profile rollback resistance and hardware power-loss durability are not qualified. A safe new identity/profile, when recovery is impossible, requires an explicit owner decision, re-pairing and honest history-loss/duplicate-delivery warnings—not an automatic repair button.
