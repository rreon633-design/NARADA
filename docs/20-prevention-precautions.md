# NARADA — Prevention and Precautions

> [Documentation index](../README.md) · Non-negotiable safeguards · 30 October 2026

## 1. Security before convenience

- [ ] Treat LAN discovery and device names as untrusted information.
- [ ] Use paired cryptographic identity on every normal session; never accept all certificates.
- [ ] Keep the bootstrap listener temporary, narrow and rate-limited.
- [ ] Use high-entropy single-use invitations; decorative six-digit prototype codes are not authentication.
- [ ] Restrict root/device administration to the local owner.
- [ ] Recheck authorization for previews, search, events, chunks and final commits.
- [ ] Apply explicit request, frame, nesting, queue, concurrency, disk and timeout limits.
- [ ] Disable unsupported TLS early-data behavior and unsafe protocol downgrade paths.
- [ ] Reject path traversal, unsupported reparse points, device paths, unsafe aliases and race-prone mutations.
- [ ] Keep source data, temporary data and executable/update locations separate.
- [ ] Use maintained crypto implementations; no handwritten ratchet, key exchange or streaming encryption format.
- [ ] Review dependency licenses, support status, advisories and signing prerequisites.

## 2. Prevent data loss and false success

- [ ] Bind resumable work to an immutable manifest/version.
- [ ] Persist durable chunk state only after the corresponding data is safely checkpointed.
- [ ] Keep partial files separate from user-visible completed destinations.
- [ ] Verify final length and digest before publication.
- [ ] Recover filesystem/database commit gaps through an intent journal.
- [ ] Use idempotency keys and result records for every retryable mutation.
- [ ] Default to keep-both or ask; replacement needs current-version validation.
- [ ] Distinguish transfer completion from external provider export completion.
- [ ] Preserve the old pin until a new version is fully verified.
- [ ] Never silently delete the only conflict candidate or pinned copy on source deletion.
- [ ] Explain that sync and app-private copies are not independent backups.

## 3. Protect messages and keys

- [ ] Store outbox/inbox/session transitions atomically using the library's approved persistence model.
- [ ] Transmit only after outgoing ciphertext and session state are durably committed.
- [ ] Retry the persisted envelope; do not re-encrypt the same logical message unpredictably.
- [ ] Acknowledge delivery only after recipient authentication and durable commit.
- [ ] Keep attachment keys inside E2EE content, not URLs, API metadata or logs.
- [ ] Use fresh attachment keys and reviewed streaming authentication.
- [ ] Bound skipped-key, prekey, inbox, outbox and replay-state storage.
- [ ] Fail visibly on key invalidation or crypto-store errors; do not silently reset identity.
- [ ] Do not restore old ratchet state through a generic database rollback.
- [ ] State that revocation and “delete for everyone” cannot guarantee erasure of received copies.

## 4. Desktop precautions

- [ ] Run the user agent without routine administrator/LocalSystem privileges.
- [ ] Restrict Named Pipe access and verify the peer/server context; defend against remote pipe use and wrong-instance binding.
- [ ] Use explicit Tauri capabilities and validate custom commands inside Rust.
- [ ] Bundle resources locally; use a restrictive CSP and no remote script execution.
- [ ] Render untrusted names/messages as text, not interpolated HTML.
- [ ] Do not execute attachments or automatically extract archives.
- [ ] Explain window-close versus exit-agent behavior and laptop sleep/logoff limits.
- [ ] Use a narrow firewall rule; do not encourage disabling the whole firewall.

## 5. Android precautions

- [ ] Use native Compose, not the prototype's fixed phone-frame layout.
- [ ] Obtain storage access through user-selected SAF content, not unnecessary all-files permission.
- [ ] Persist only URI permissions actually granted/offered; handle revocation.
- [ ] Assume a provider may be remote, non-seekable, mutable or unable to replace atomically.
- [ ] Use appropriate user-visible transfer jobs/foreground services and implement applicable timeout/stop behavior.
- [ ] Use WorkManager for deferrable work, not guaranteed permanent sockets.
- [ ] Do not promise force-stop recovery without user/system eligibility.
- [ ] Avoid continuous discovery, tight polling and unbounded wake locks.
- [ ] Test IME, system insets, large text, rotation, process death and denied notifications.

## 6. Privacy and diagnostics

- [ ] No mandatory cloud account or content analytics.
- [ ] Keep notification body previews off by default.
- [ ] Never log QR payloads, tokens, private keys, message text, attachment keys or full private paths.
- [ ] Inspect exported diagnostics for sensitive database/WAL/crash-dump content.
- [ ] Separate optional update checks from core offline functionality.
- [ ] Explain that user-selected cloud-backed providers may use their own internet services.
- [ ] Do not promise forensic secure erase on SSDs, third-party providers or backups.

## 7. UI/product honesty

- [ ] Replace “Always connected” with accurate local-first messaging.
- [ ] Remove fake QR patterns, static completion, pretend filters and nonfunctional P0 buttons.
- [ ] A green connection dot comes from an authenticated session, not Wi-Fi association.
- [ ] A 100% byte counter still needs verification/commit state.
- [ ] Distinguish remote, downloaded, pinned, exported and stale files.
- [ ] Distinguish queued, transport accepted, delivered and read messages.
- [ ] Make unsupported features unavailable rather than misleadingly interactive.
- [ ] Maintain accessibility and destructive-action confirmation in every theme.

## 8. Release prevention gates

Before release, verify no unresolved critical/high security finding, known silent corruption, incorrect receipt, unsafe migration or unsupported cryptographic restore path. Every exception to a non-security performance target needs an owner and published rationale. Security/data-loss blockers cannot be waived merely to preserve a schedule.
