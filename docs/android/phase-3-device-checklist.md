# Android Phase 3 — unexecuted native qualification checklist

[Index](README.md) · [Measured host evidence](phase-3-validation.md) · [Phase 2 native checklist](phase-2-device-checklist.md)

**All boxes below are open.** Source and host tests do not turn them into passes. Use disposable roots and a test phone; managed mutations have real effects. Record exact APK/source hashes, signer, build/API/OEM, desktop build/OS/filesystem, router/network, provider versions, steps and observed outcomes. Never include private keys, invitation tokens, raw trust databases or user file contents in reports.

## Platform and upgrade

- [ ] Install and upgrade the signed debug package from Phase 2 without clearing data; observe unchanged enrollment, certificate/pins, Room 3 anchor and trust tombstones.
- [ ] Exercise real sealed payload v1→v2 migration, interruption before/after rename/fsync, missing key/blob/anchor and damaged ciphertext; preserve evidence with no rekey/reset.
- [ ] Execute the five compiled instrumentation tests on a disposable emulator/device. Random test aliases/directories must not affect the enrolled application identity.
- [ ] API 29, 33, 35 and 36 phone matrix; supported OEMs, low-memory/lifecycle behavior, background/force-stop/process death and lock/unlock.
- [ ] Backup/restore and downgrade drills. No claim of coordinated whole-profile rollback resistance; an older app must not be treated as recovery tooling.
- [ ] Native 16-KiB page-size execution, not just ELF/ZIP inspection; measure application memory, startup, energy and response latency.

## Identity, network and authorization

- [ ] Carry forward all Phase 2 AndroidKeyStore, Conscrypt TLS 1.3, exact pin/SPKI, scanner/import/manual input, NSD and Windows LAN gates.
- [ ] Observe actual nonexportable keys and OS-reported hardware level; do not equate the label with attestation.
- [ ] Verify one owned foreground session while browsing; no health/file overlap, listener, Android owner API or background content connection.
- [ ] Wi-Fi/Ethernet loss/handoff, private-IP changes, public-only routes, captive portal, VPN/cellular exclusion, disabled connections, background and storage failure during every file-request stage.
- [ ] Grant withdrawal and local/desktop revoke before send, during server processing, after response and before receipt write. No late publication or false completed revocation/success.
- [ ] Target 36 LAN behavior and Android 16 restriction compatibility tests. Reassess local-network permission/store policy before targeting 37; do not add a blanket permission as a workaround.

## Native desktop filesystem

- [ ] Actual Windows filesystems/ACLs, junctions/reparse points/symlinks, root replacement, hidden/reserved files, exclusions, moved parents and TOCTOU races; Linux adapter evidence does not qualify these.
- [ ] Real Unicode/NFC/case-folded names, reserved device names, long paths, collision semantics, existing targets and version changes.
- [ ] Empty/nonempty directories; no recursive mutation or overwrite. Managed trash quotas/retention, changed/unavailable retained items and unresolved root recovery barriers.
- [ ] Reindex/watcher events versus phone refresh; expired cursors and cross-root/search/sort cursor misuse. No stale or cross-epoch merge.

## Mutation interruption and recovery

- [ ] Kill the phone before intent persistence: no effectful POST.
- [ ] Kill/lose the route after durable intent but before send: reopening shows unconfirmed; no automatic resend.
- [ ] Disconnect after desktop commit but before reply: exact saved operation retry returns the same receipt; no second effect or new UUID.
- [ ] Fail/kill the receipt write: no UI success unless committed state is durably reopened/confirmed.
- [ ] Restart desktop and phone; explicit endpoint change preserves pins and pairing scope. A replacement pairing cannot replay old intent.
- [ ] Withdraw permission after possible commit: denial does not falsely relabel the earlier outcome as failed.
- [ ] Explicit local dismissal stays `DISMISSED_UNCONFIRMED`, not success/undo/cancel. Preserve unconfirmed records when the local 32-record window is full.
- [ ] Native storage full/Keystore unavailable/atomic rename/directory-fsync failure. Failed local disable/revoke remains a repair/retry requirement, not an invented durable intent.

## Previews, providers and privacy

- [ ] Native JPEG decoder corpus: malformed segments, dimensions, allocations, EXIF/APP/comment payloads, truncation/trailing bytes and unsupported active formats; independent parser/codec review.
- [ ] Preview never opens an external viewer or exports originals; pixels/metadata are not written to Room, DataStore, saved state, notifications, logs or broad FileProvider paths.
- [ ] In-memory view expiry, tab exit/background/authority invalidation, bitmap release and recents/screenshot/secure-dialog behavior on OEMs.
- [ ] Keyboard and TalkBack privacy; immutable strings/IME learning/memory zeroization are not guaranteed by host tests.
- [ ] Local read-only SAF grant revocation/provider disappearance/refusal/slow IO remain separate under Storage. No remote root is coerced into a URI. Full transfer/export/provider publication is Phase 4, not this phase.

## UI/accessibility and release

- [ ] Physical TalkBack traversal, focus order, full selected name/object clarity and consent controls; empty/denied/offline/revoked/recovery states remain distinct.
- [ ] 200% text, landscape, smallest supported display, edge-to-edge/cutouts, gesture navigation and real IME. Confirmations must remain scrollable with reachable cancel/confirm controls.
- [ ] Native screenshot/recording restrictions are observed, not assumed from `FLAG_SECURE` source alone.
- [ ] Independent security/storage/crypto review, dependency advisory/license review, signing custody, artifact-bound install/update/support/recovery drills and approved pilot criteria.

**Do not mark Phase 8/native qualification or Phase 9/release complete because Phase 3’s development tests passed.**
