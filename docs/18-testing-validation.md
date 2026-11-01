# NARADA — Testing and Validation Strategy

> [Documentation index](../README.md) · Required evidence before release · 30 October 2026

## 1. Test layers

| Layer | Main coverage | Tools / execution |
|---|---|---|
| Unit/property tests | Parsers, limits, authorization, state transitions, names and conflict rules | Rust/JVM tests; property-based tests where useful. |
| Repository/integration | Transactions, WAL, migrations, queues, leases and commit recovery | Temporary databases/filesystems and fault injection. |
| Protocol contract | Windows/Android request/event compatibility and negative cases | Shared schemas and language-independent fixtures. |
| Crypto integration | Official/library test vectors, session persistence, reorder/replay and key changes | Selected-library harness; not homemade crypto tests alone. |
| Native platform | Windows handle/IPC behavior; Android NSD, SAF, Keystore and jobs | Real Windows and Android hardware. |
| UI/accessibility | Routes, permissions, state copy, keyboard, TalkBack and sizing | DOM/Compose tests plus manual assistive-technology review. |
| End-to-end | Pair → browse → transfer → chat → pin → reconnect → revoke | Two-device automation and controlled manual scenarios. |
| Recovery/security | Process death, power loss, malformed input, resource abuse | Repeatable fault scripts, fuzzing and qualified review. |
| Performance | Latency, throughput, CPU/RAM, disk and battery | Declared baselines; raw environment recorded. |

## 2. Qualified environment matrix

- Supported Windows 11 x64 builds on at least two hardware classes, including an ordinary SSD laptop. Windows 10/ARM64 only when separately qualified.
- Android minimum supported API, a recent supported API, and the current target/latest available test API at qualification time; at least two OEM families and a constrained-memory device.
- Physical-device tests in addition to emulators; emulators do not prove real Wi-Fi discovery or OEM background behavior.
- Local NTFS roots, busy files, low-space volumes and unsupported filesystem rejection. Removable/network storage requires explicit qualification.
- Android private storage plus qualified SAF providers: seekable, non-seekable, permission revoked, unknown length and unavailable cloud-backed source.
- More than one router; 2.4/5 GHz where supported, congestion, weak signal, multicast blocked, client isolation and no-internet Wi-Fi.
- Network changes, sleep/wake, Windows logout, Android process death, user force-stop and permitted background-service timeout tests.
- UI at desktop scaling 100/150/200%, narrow windows, mobile 360–480 dp, large text, keyboard open and assistive technology.

Freeze actual model/OS/provider versions in the release report. Do not write “all Android phones” based on two successful devices.

## 3. Core acceptance cases

| ID | Test | Required outcome |
|---|---|---|
| AT-01 | Valid no-internet pairing | Both endpoints authenticate and persist only approved grants. |
| AT-02 | Expired/reused invitation; concurrent claims | No second unauthorized activation; same-attempt retry is recoverable. |
| AT-03 | Discovery/IP spoof; changed key | Connection rejected without trust downgrade or token disclosure. |
| AT-04 | Unknown/revoked peer probes APIs and WSS | No metadata/content/diagnostic access. |
| AT-05 | Read-only peer attempts upload/rename/trash | Explicit permission denial on every path. |
| AT-06 | Traversal, UNC/device names, ADS, junction and rename race | No escape from approved root; unsupported paths fail closed. |
| AT-07 | Zero-byte/small/large file and many small files | Verified complete output, bounded memory and correct progress. |
| AT-08 | Network loss after a durable chunk checkpoint | Reconnect resumes the same manifest without trusting uncertain bytes. |
| AT-09 | Process killed before/after file flush and DB commit | Recovery reconciles disk and checkpoint state correctly. |
| AT-10 | Kill during final rename/commit; lose success reply | One committed result or explicit recoverable state; no silent overwrite. |
| AT-11 | Source/destination changes mid-transfer | Version conflict or new transfer required; mixed content never completes. |
| AT-12 | Tampered chunk/manifest and oversized frame | Rejected safely with bounded CPU/RAM/disk; no final publish. |
| AT-13 | Disk full/DB busy/read-only volume | Existing good content remains safe; work pauses/fails clearly. |
| AT-14 | SAF non-seekable export or lost URI grant | Private verified content preserved; export status and next action honest. |
| AT-15 | Same chat envelope repeated; receipt lost | One visible message; repeat durable receipt. |
| AT-16 | Same message ID, different envelope | Reject replay/conflict without corrupting session state. |
| AT-17 | Crash during crypto/inbox/outbox transaction | No send-before-persist, false receipt or silently reset session. |
| AT-18 | Attachment truncation/reorder/tamper | Authentication/integrity failure; no unsafe plaintext publication. |
| AT-19 | Pin offline, failed refresh and source deletion | Prior verified pin usable; no silent loss. |
| AT-20 | Watcher overflow and compacted cursor | Stable snapshot + subsequent journal replay converge safely. |
| AT-21 | Concurrent edit/delete and case collision | Conflict candidates retained and clearly labeled. |
| AT-22 | Device revoked during transfer/chat | Subsequent authorized work stops; commit rechecks scope. |
| AT-23 | UI window close, mobile rotation/force-stop | No duplicated job, false online state or promise of impossible background delivery. |
| AT-24 | Logs/notifications/diagnostic export inspection | No prohibited keys, tokens, message bodies or private paths. |
| AT-25 | Upgrade interrupted; incompatible old client | Safe recovery or explicit incompatibility; no silent data reset. |
| AT-26 | Unsigned/tampered update | Rejected before installation. |
| AT-27 | Desktop/mobile prototype replacement audit | No decorative QR, fake delivery, static connected status or placeholder P0 action. |
| AT-28 | Fresh offline installation and first use | Runtime/decoder prerequisites satisfied without hidden cloud dependency. |

## 4. Fault-injection method

Instrument durable state boundaries with deterministic hooks: before write, after write, before flush, after flush, before DB commit, after DB commit, before publish, after publish, before receipt and after receipt.

For each hook:

1. Run a known synthetic operation and terminate the relevant process.
2. Restart under the supported lifecycle path.
3. Inspect job/message state, file hashes and stored protocol state.
4. Verify no success was reported beyond the durable boundary.
5. Repeat the same request/receipt/commit to check idempotency.
6. Record the seed, build and exact fault point for reproduction.

Process-kill results do not establish storage power-loss durability. Run controlled power-loss qualification where practical and document hardware/write-cache assumptions.

## 5. Security and fuzzing scope

Include JSON/binary framing, chunk arithmetic, manifest sizes, relative paths, Unicode/case normalization, URI/QR payload parsing, certificate pin checks, malformed E2EE envelopes and permission-filtered events.

Test quota exhaustion by an authenticated peer, handshake floods within controlled lab limits, slow requests and large directory/message histories. Verify cancellation/backpressure keeps the agent responsive.

Keep public test fixtures synthetic. Security tests must not require real user private keys or production signing credentials.

## 6. UI verification against supplied designs

- Compare the Windows file tree/table/inspector and overall monochrome shell with the supplied detailed image.
- Compare mobile bottom navigation, cards, file rows, chat and transfer layout with the mobile overview.
- Verify actual behaviors separately from screenshot similarity.
- Confirm folder selection, tray and notifications are native, while the Android app has no desktop demo selector or drawn fake status bar.
- Assert that disabled/hidden controls match release scope and current permissions.
- Test missing images, unavailable previews, long filenames, empty histories, one-handed mobile interaction and large text.

## 7. Release evidence bundle

Keep a test report, failure list, compatibility matrix, performance report, dependency/security findings, migration results and signed go/no-go checklist. Known critical/high security or silent-data-loss defects block release even when most tests pass.
