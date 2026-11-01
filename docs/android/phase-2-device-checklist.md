# Phase 2 native Android ↔ Windows qualification

[Current host evidence](phase-2-validation.md) · [Workflow](pairing-workflow.md) · [Foundation checklist](device-checklist.md)

**All boxes below remain unexecuted here.** A real Rust process behind JSSE on Linux is not AndroidKeyStore/Conscrypt, camera, NSD, Windows or physical-device evidence. Use a disposable installation/profile and record exact source/APK hashes, API/OEM/router/Windows version and test outcome in new records.

## Compiled native smoke source

The matching instrumentation APK includes Activity launch/recreation/permission checks and two platform-key tests: nonexportable stable signing/sealing with tamper rejection, and absent identity without software fallback. These **three tests were compiled, not run**.

```bash
# Extract the delivered APK bundle and verify SHA256SUMS.txt first.
adb install -r NARADA_Android_Phase2_Debug.apk
adb install -t -r NARADA_Android_Phase2_Instrumentation.apk
adb shell am instrument -w com.narada.mobile.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Only test-created random aliases are cleaned up by key tests. Run on disposable data; interrupted instrumentation can leave evidence that correctly blocks enrollment. Never use cleanup to erase a user's real enrolled identity.

## Platform keys, transport and owner approval

- [ ] Actual AndroidKeyStore-generated self-signed certificate/Signature works on API 29 and current supported OEM/API versions; record OS-reported hardware level without claiming attestation.
- [ ] Actual nonexportable key completes Conscrypt TLS 1.3 client authentication against the current Windows private-interface agent.
- [ ] Correct certificate + SPKI pins and `narada.local` SAN succeed; wrong certificate/key/curve/SAN/expired cert and TLS 1.2 fail before secret disclosure.
- [ ] Owner approval is required after private-key proof; normal API rejects no cert, another phone key and a revoked key.
- [ ] Consumed bootstrap cannot replay; expired invitations and clock skew fail without automatic re-pair.
- [ ] Lose final ACK reply, kill/restart each side, recover same ACK_PENDING attempt without another token or duplicate active record.
- [ ] Review/change desktop grants, verify monotonic counters and no unimplemented content endpoints/UI activation.

## Network and lifecycle

- [ ] Wi-Fi/Ethernet private IPv4 succeeds. Cellular, VPN, public address, DNS, phone loopback, malformed endpoint and route-policy changes fail closed.
- [ ] Switch network/IP, lose Wi-Fi, change default to cellular/VPN, lock/background/rotate/kill/relaunch. Old socket/health/approval callbacks never restore a false connection.
- [ ] Observe selected-Network binding, ≤one health socket, 5-second checks/~50-second renewal, absolute request limits and six persisted transient failures.
- [ ] Test Windows firewall/private-profile changes and guest/client-isolation networks without broadening bindings or rules.
- [ ] Test NSD on API 29–32, 33, 34+ and current Android, including cancellation, late resolution, repeated/lost advertisements and route change. Document the older public-API scoping/cancel limitations.
- [ ] A discovered endpoint is never trusted or auto-selected; saved identity pins survive manual endpoint edits.
- [ ] Exercise applicable Android local-network restriction/permission modes. Revisit target 37 and Play policy before release, not by bypassing the restriction.

## Scanner, input and privacy

- [ ] Real camera with clear/dim/rotated/distant codes; contextual grant/deny/permanent deny; optional camera absent; scanner timeout/background/recreation.
- [ ] Local/cloud/virtual image providers: denial, very large/malformed/truncated images, blocking/cancellation and unsupported MIME; bounded memory, no saved/uploaded frames.
- [ ] Inspect activity-result/saved-instance state, backups, logcat/support exports and app-private files: no invitation token or private key serialization.
- [ ] Secure-window behavior during scanner/pending pairing; manual code/IME/autofill and process restoration do not retain secret input. Clipboard/third-party keyboards remain OS/user trust boundaries.
- [ ] TalkBack labels/focus, 48 dp actions, 200% font/display scaling, IME/insets, landscape and small devices.

## Storage, revocation and upgrade

- [ ] Install-over the actual Phase 1 artifact with matching signer: preserve setup/events/job/SAF consent; migrate 2→3 with no automatic key enrollment.
- [ ] Restart/kill at PREPARING, key generation, sealed-file commit, anchor bind, pending ACK, final activation and tombstone write; no silent replacement or invented success.
- [ ] Missing/corrupt key, ciphertext, anchor or metadata; fsync/rename/disk-full; preserve evidence and block networking rather than recreate state.
- [ ] Revoke while loading keys, binding/handshaking, waiting on approval/ACK, or receiving health. Local epochs prevent reactivation; also verify desktop-side revoke.
- [ ] If a disable/revoke write fails, verify the UI reports failure and does not claim durable revocation; review saved state after restart and repair explicitly on both devices.
- [ ] Cloud/OEM device-transfer restore, uninstall/clear-data and signing mismatch: no lossless-reset claim; no fallback identity. Whole-state rollback resistance is not claimed.
- [ ] Actual 16 KB page-size execution, resources/battery/thermal behavior and long-run boundedness—not just static ELF/ZIP checks.

Security/advisory/license review, release package/signing custody, support ownership and release approval remain separate phase 8–9 gates.
