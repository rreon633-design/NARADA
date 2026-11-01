# Phase 5 — native qualification and review tasks

[Phase 5 status](phase-5.md) · [Independent crypto gate](chat-crypto.md) · [Measured boundary](phase-5-validation.md)

**All tasks below remain open.** Ten instrumentation cases compile; none has run. A checked host test is not a checked native task. No independent sign-off or remote CI result is implied.

## Library and platform matrix

- [ ] Load and call the packaged JNI on supported API29/33/36 hardware/emulators, including arm64 and the declared32-bit/x86 variants where applicable.
- [ ] Run both new Olm instrumentation cases plus the eight retained platform cases; bind outputs to APK/source hashes.
- [ ] Execute on16 KiB-page devices/emulators; static ELF/ZIP alignment alone is insufficient.
- [ ] Exercise native invalid inputs, OOM/exception/ABI/config mismatch, crypto unavailability and no fallback.
- [ ] Review AndroidKeyStore AES/P256 behavior, key invalidation, hardware/security-level reporting and cryptographic state validation. Hardware reports are not attestation.
- [ ] Repeat actual protocol interoperability with the qualified Windows desktop/DPAPI store over real allowed LANs/routers, not Linux JSSE loopback alone.

## Persistence and recovery

- [ ] Kill/force-stop/reboot at enrollment, ratchet+envelope commit, send admission, remote commit/lost response, inbox commit and ACK windows.
- [ ] Fault-inject ENOSPC/fsync/rename/read failures and damaged/missing/partial account/session/anchor evidence on real storage; no rekey or false delivery.
- [ ] Restore older valid whole aggregates and matching metadata; document rollback limitations or supply a reviewed mitigation. Do not claim monotonicity from continuity checks alone.
- [ ] Validate1→4/2→4/3→4 protected migrations without key/owner/AAD changes, plus retained mutation/download/export journals.
- [ ] Verify backup/restore exclusions, uninstall behavior and **same-signer** upgrade/downgrade with controlled signing custody. Supplied APKs do not establish that path.
- [ ] Test capacity/large UTF-8/drafts/sessions across the shared2 MiB aggregate; no silent history/pin/unknown-outcome eviction.

## Authority and real lifecycle

- [ ] Background/tab/route/pairing/permission changes at every client and presentation boundary; no stale plaintext view or new-scope replay.
- [ ] Cancel/join health and content leases under native scheduling; measure shared 300 ms admission and contention with other clients under the unchanged desktop limit.
- [ ] Lost/duplicate/reordered push, pull and ACK responses; verify exact digest/receipt replay and conflict refusal.
- [ ] Re-pair, remote revoke, storage lockout and session/key change without destroying offline history/copies or silently accepting replacement keys.
- [ ] Confirm no content listener, permanent presence, boot receiver message send, WorkManager content task or automatic retry after force-stop.

## Privacy and usability

- [ ] Real IME, font scaling200%, insets/edge-to-edge/navigation modes, rotation, foldables and TalkBack;48dp reachability and scroll/focus behavior.
- [ ] Verify FLAG_SECURE before private composition against screenshots/recents/casting on the supported OEM matrix; state external-window/IME/accessibility limits.
- [ ] Check heap/serialization/logcat/tombstones/support reports/backup artifacts for accidental message/pickle/key leakage, with appropriately scoped secure test handling.
- [ ] Notification permission/channel/lock-screen/local-only/immutable intent on supported OEMs; no sender/text/IDs/actions/extras. Verify default-off and no false background-delivery claim.
- [ ] Measure real memory/CPU/storage/thermal/battery/network behavior; do not extrapolate desktop test duration into phone performance.

## Independent and release work

- [ ] Obtain independent crypto/storage/JNI review of the actual artifact, including Olm v1/64-bit MAC compatibility/threat budget, memory limits and rollback assumptions.
- [ ] Complete current dependency/advisory/license review and artifact-bound SBOM/provenance; broad notice collection is not legal approval.
- [ ] Freshly exercise the adjusted tool-cache/native restoration recipe on a clean Linux builder; other build hosts need their own qualification.
- [ ] Run the reviewed CI definition; emulator smoke, if later run, does not replace hardware/Windows coverage.
- [ ] Revisit target SDK/local-network permission/store policy against release-time requirements; API36 is the current development target, not a future policy waiver.
- [ ] Establish production identity/signing/update custody, accountable review owners, recovery/support process and pilot/release go/no-go in Phase 9.

Retain the [connection](phase-2-device-checklist.md), [files](phase-3-device-checklist.md), [transfer/export](phase-4-device-checklist.md) and [foundation](device-checklist.md) native gates. Phase 6 attachments and Phase 7 pins/sync are feature work, not evidence that these tasks passed.
