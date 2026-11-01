# NARADA — Android Phase 3 handoff

**31 October 2026 · 0.3.0 (3) · Development implementation delivered**

[Android roadmap](android-plan.md) · [Native project / build instructions](apps/android/README.md) · [Split documentation](docs/android/README.md)

## What is implemented

A native **Kotlin / Compose / Material 3** client for the desktop’s real authorized file protocol, extending the delivered Phase 2 trust and connection layer. The supplied mobile image/HTML remains the visual reference; no WebView, fake remote entries or simulated mutations are used.

- Granted roots; opaque-ID folder navigation and breadcrumbs; root-scoped name search; all four desktop sort orders; explicit, bounded page navigation.
- Live details and permission-checked, metadata-stripped **JPEG pixel previews**, at most **256 × 256 pixels / 32 KiB**. No original download, active document, external viewer, content provider or persistent preview cache.
- Explicitly confirmed **rename, managed trash and restore** for the desktop protocol’s supported files/empty directories. No overwrite, recursive mutation, permanent deletion or automatic purge.
- A protected operation journal, persisted **before** mutation transmission. Lost replies, cancellation, policy changes or failed receipt writes remain **unconfirmed**. Recovery resends the same saved identifier and exact arguments; it never invents a replacement action.
- Current grant/session checks before and after file responses; local trust/lifecycle/route fences; strict identifiers, names, JSON, response sizes and JPEG structure; visible denied/offline/stale/recovery errors.
- One foreground connection owner. File requests pause/close the health lease and use an IO-scoped, pinned mutual-TLS session. No Android listener, owner API, background content service or added permission.

## Evidence, not a device-release claim

| Final record | Actual result |
|---|---|
| `domain-01` | 22 passed |
| `connection-02` | 135 passed: retained connection boundaries + file protocol/client/controller/TLS tests |
| `interop-02` | 22 passed against the real unchanged Linux Rust agent: 10 connection + 12 file cases |
| `host-03` | 118 passed: Robolectric/API 35, protected repository, connection ownership and real Compose controls |
| `lint-01` | 0 errors, 0 warnings; 36 informational dependency/version hints |
| `apk-01` | Development debug APK built |
| `instrumentation-01` | Instrumentation APK built; **5 tests compiled, not device-executed** |

**297 passed, 0 failed/errors/skipped in the final campaign.** This retains the 186-check Phase 2 baseline and adds 111 Phase 3 checks. **127 runtime/test/build inputs** match all seven final stages and the packaged source. APK signature/manifest/alignment/DEX audit passed; 710 dependency components have recorded checksums. **1,594 frozen historical files** were verified unchanged. Earlier preparation failures are preserved, not counted as passes.

[Detailed results, corrections and limits](docs/android/phase-3-validation.md) · [Artifact summary](artifacts/android-phase-3/validation-summary.json)

## Deliverables

- `NARADA_Android_Phase3_Source.zip` — Android source, split Markdown docs, selected measured evidence, five original visual references, unchanged Rust peer/workspace source for interop, and a per-file source manifest.
- `NARADA_Android_Phase3_APKs.zip` — lossless container for the signed debug APK and instrumentation APK. **Extract before installation.**
- `NARADA_Android_Phase3_SHA256SUMS.txt` and `NARADA_Android_Phase3_Package_Check.json` — final hashes, inclusion and round-trip checks.

The debug application is **`com.narada.mobile.dev`**, min API **29**, compile/target **36**, JDK **17**, Room **3**. It retains the earlier development signer. This is not production signing or release approval. Use disposable desktop roots and a test phone first; confirmed file actions really affect the desktop.

```bash
# After extraction; install/upgrade on your own disposable test device:
adb install -r NARADA_Android_Phase3_Debug.apk
```

Do not uninstall/clear data to “repair” protected state. App-private encrypted trust payloads lazily migrate **v1 → v2 on a successful write**, preserving enrollment, keys, pins and Room anchors. The encryption envelope stays compatible; older app code will not understand the new payload and must not be used as a downgrade/repair path. [Recovery details](docs/android/files-recovery.md)

## How to exercise it

1. Register a disposable root on the desktop and explicitly grant `browse`; add `read` for previews, `modify` for rename, `trash` for managed trash, and **both `trash` + `create`** for restore.
2. Complete Phase 2 pairing, full fingerprint comparison and desktop approval; select/reconnect the intended saved desktop. Keep the phone foreground on private IPv4 Wi-Fi/Ethernet.
3. Open **Files** and refresh roots. Search is explicit, within the root; page navigation never merges different epochs.
4. Open a file’s details, preview a suitable image, or confirm a managed action. A successful UI result requires a saved matching receipt, not just a submitted request.
5. For an unconfirmed outcome, inspect the desktop. **Retry the same operation** may apply a request that never arrived; it is not a read-only status check. Dismissing local retry does not undo or confirm anything remotely.

[Step-by-step workflow](docs/android/phase-3-workflow.md) · [Wire contract and limits](docs/android/files-protocol.md)

## Boundaries and remaining gates

No upload, full download, SAF export, sharing originals, offline copy/pin, transfer queue, E2EE chat, attachment engine or synchronization is added. Phone SAF grants/provider errors remain the separate read-only foundation under Storage; desktop roots are never phone URIs. **Phase 4 — verified transfers — is next.**

No emulator, physical Android device, Windows LAN/native filesystem, remote CI or independent review ran here. The real-agent suite uses **Linux + JSSE/software fixture keys**, not AndroidKeyStore/Conscrypt or Windows. Native key/fsync/process-death/upgrade/backup/codec, provider/NSD/camera/routing/OEM, TalkBack/IME/insets and resource qualification remain open. The host UI layout correction and 200% type checks are not physical-device qualification. [Device checklist](docs/android/phase-3-device-checklist.md)

The desktop source and its historical **342-pass/6-failure** single-pass record were not rewritten or rerun. This campaign’s focused interop is new, narrower evidence. Android Phase 1/2 handoffs and exact delivered archive bytes are retained. Eight older ZIPs were losslessly consolidated inside the persisted `NARADA_Pre_Android_Phase2_Deliveries.tar.xz` to fit workspace limits; their member hashes and extraction verification are in the [relocation receipt](artifacts/android-phase-3/preparation/historical-archive-relocation.json). Android Phase 2 source/APK ZIPs remain at their original names. No private runtime profiles or signing keys are in this source delivery.
