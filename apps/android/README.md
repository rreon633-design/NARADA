# NARADA for Android — Phases 6 & 7

Native Kotlin / Compose / Material 3, Hilt, Coroutines/Flow, Room, DataStore and WorkManager. Small stateless Rust/JNI Olm and age adapters; no WebView, embedded phone server or wholesale desktop backend port.

[Current development handoff](../../ANDROID-PHASE6-7-HANDOFF.md) · [Phase-wise plan](../../android-plan.md) · [Split documentation](../../docs/android/README.md) · [Workflow](../../docs/android/phase-6-7-workflow.md) · [Measured validation](../../docs/android/phase-6-7-validation.md)

Two-way encrypted attachments, an authorized **versioned desktop upload extension**, scoped snapshot/change metadata, persistent pins and explicit one-way refresh are implemented alongside the earlier pairing/files/download/chat work. Native-device execution, independent reviews and release approval are separate gates, never implied by Linux/JNI/JSSE/Robolectric results. Final counts and the failures/corrections preceding them are in the validation report. Historical desktop and Phase 5 evidence remains preserved, not rerun or relabeled.

## Pins and storage boundary

| Component | Pin / boundary |
|---|---|
| Minimum Android / compile-target | API 29 / 36–36 |
| Build Tools / NDK | 35.0.0 / 30.0.16248370 (r30); native API 29 |
| JDK / Gradle / AGP | 17 / 8.13 / 8.13.2 |
| Kotlin / KSP / Compose BOM | 2.2.21 / 2.2.21-2.0.5 / 2025.12.00 |
| CameraX / offline ZXing | 1.5.1 / 3.5.3 |
| Olm | vodozemac 0.11.1, defaults off; Olm v1, 64-bit MAC; adapter bridge 2 |
| age | age 0.12.1, defaults off; fresh X25519 identity, age v1 / STREAM; bridge 1 |
| JNI / Rust | jni 0.21.1 / Rust 1.99.1; Linux host and four Android ABIs |
| Development application / version | `com.narada.mobile.dev` / 0.7.0 (7) |
| Room / protected aggregate | Room schema 3; payload 6 reads 1–6, retaining enrollment keys/owner/AAD |
| Host UI fixtures | Robolectric 4.16.1, API 35 (not AndroidKeyStore/Conscrypt/device evidence) |

Maven verification remains enabled. Locked native dependency inventories and provided/upstream notices are **not** independently reviewed license conclusions, advisory clearance or a binary-reachability SBOM. The application has no production software identity/storage fallback. Whole-state rollback resistance is unproven.

## Build from the source bundle

Linux x86_64 is the implemented native **build-host** recipe; Windows/macOS build hosts are not qualified. Install JDK 17, Python 3.11+, Rust 1.99.1, SDK 36, Build Tools 35.0.0 and NDK 30.0.16248370 after reviewing their licenses. Set `JAVA_HOME`, `ANDROID_HOME` and `ANDROID_NDK_HOME`. Provisioning/restoration helpers that use `tarfile`'s safe extraction filter require Python 3.12+.

```bash
rustup toolchain install 1.99.1 --profile minimal
rustup target add --toolchain 1.99.1 aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
export RUSTUP_TOOLCHAIN=1.99.1
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
python3 scripts/build_android_age.py --host --android
python3 scripts/build_android_olm67.py --host --android
```

Build host and Android together: missing/stale source-receipted native outputs fail the Gradle verifier. Outputs under `app/build/generated/{age,olm}` are excluded from the source bundle; rebuild them. The `native-*-notices.txt.gz` source assets become expanded `assets/native-*-notices.txt` files through AGP.

Use the **fresh-record**, sequential commands in [workflow](../../docs/android/phase-6-7-workflow.md) for Rust tests, domain/core/app/actual-peer regression, lint, APK and instrumentation compilation. On a small host the runner separates 384 MiB Kotlin compilation from the 160 MiB test daemon and 320 MiB per-class app worker. Both invocations must use `--info`; changing the KSP log-level input forces recompilation. Never run multiple memory-heavy stages concurrently or mistake an interrupted/missing-XML run for passing evidence.

```bash
cd apps/android
# Build only; use the evidence runner for the validation campaign.
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
# Requires a disposable connected Android device/emulator; not executed in this handoff:
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

Debug output: `app/build/outputs/apk/debug/app-debug.apk`. Debug signing identities can differ after excluded caches are restored; do not claim an in-place upgrade from earlier APKs. Release signing, backup/upgrade, native providers, accessibility/IME/insets, 16 KiB runtime and Windows LAN qualification remain explicit gates.

## Working behavior

- Pair on the allowed private IPv4 Wi-Fi/Ethernet network with pinned bootstrap, desktop approval/fingerprint comparison and durable ACK/health. Phone remains outbound-only.
- Chat: explicitly enable protected chat, select the approved peer, import one read-only SAF document (≤8 MiB), synchronize its protected manifest, then explicitly send the retained ciphertext. A message receipt is not proof that bytes were stored. Restart never auto-sends.
- Receiving: explicitly retain/fetch attachment ciphertext; all age authentication and full ciphertext/plaintext hashes precede availability. Explicit create-new inert SAF export is separate and may remain uncertain.
- More → Offline copies: select a peer/root and explicitly refresh scoped metadata. Pin an exact file version, resume the candidate in Transfers, then explicitly verify/adopt it. Metadata freshness never means bytes are available.
- Refresh keeps current/candidate/one previous references. Failed refresh, tombstones and source deletion do not erase verified copies. Unpin/release retains the bytes; separate reviewed removal is reference-aware. Quota exhaustion never silently evicts data.

No owner reindex API, background presence, automatic destructive replacement, bidirectional filesystem sync, phone listener, auth retry/downgrade or crypto reset/rekey fallback. External provider windows/copies and hostile-provider stoppability/atomic publication are outside the guarantees.
