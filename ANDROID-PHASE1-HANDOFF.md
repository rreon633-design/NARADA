# NARADA — Android Phase 1 handoff

**31 October 2026 · v0.1.0 · Development foundation complete**  
**82 host tests passed · lint passed · debug APK built · device qualification open**

## Open these first

- [Android phase-wise development plan, phases 1–9](android-plan.md)
- [Native Android project and build instructions](apps/android/README.md)
- [Phase 1 scope and success criteria](docs/android/phase-1.md)
- [Executed validation, previous failures and limits](docs/android/validation.md)
- [Architecture and end-to-end ownership](docs/android/architecture.md)
- [Security, storage and recovery precautions](docs/android/security-storage.md)
- [Unexecuted native/device checklist](docs/android/device-checklist.md)

## What is implemented

A real **Kotlin + Jetpack Compose + Material 3** application, following your supplied mobile screenshot and HTML—not a WebView or phone mockup.

- Three-step onboarding and **Home · Files · Chat · Transfers · More** navigation.
- Nested More destinations, coherent Back, saved tab/scroll state and protected unsaved edits.
- Hilt/ViewModel/Flow ownership; **Room schema 2** with explicit migration and corruption/loss preservation; DataStore for non-secret preferences.
- App-private files and actual **read-only SAF consent**; no broad phone-storage access or folder indexing.
- Unique, bounded WorkManager local checks with durable IDs, cancellation and recovery handling.
- Contextual notification opt-in and generic private content.
- In-app review and narrow content-URI sharing of an allowlisted diagnostic report.
- Pinned wrapper/dependencies, locks/checksums, test source, CI definition and separate Markdown documentation.

Later screens explain that pairing, remote files, transfers, E2EE and sync are unavailable. No fabricated peers, QR codes, messages, percentages or delivery states are supplied. Sensitive-content storage fails closed instead of using plaintext Room/DataStore.

## Deliverables

| File | Contents |
|---|---|
| `NARADA_Android_Phase1_Source.zip` | Native source, phase plan, focused docs, source-hash/test/lint/APK-audit evidence, CI source and unchanged supplied references |
| `NARADA_Android_Phase1_Debug.apk` | Development app, Android 10/API 29+, approximately 35.4 MB |
| `NARADA_Android_Phase1_Instrumentation.apk` | Matching device smoke-test APK; compiled, not executed |
| `NARADA_Android_Phase1_SHA256SUMS.txt` | Artifact integrity receipts |

The source archive is Android-focused; it does not replace the earlier desktop source archives. It excludes private signing keys, local machine paths/configuration, SDKs, caches and generated build trees. Referenced build logs do retain the nonsecret development-host tool paths for provenance. SHA-256 receipts are integrity checks, not signed publisher attestations.

## Validation and remaining gates

**22 domain + 60 Android host tests passed**, zero failures/skips. Final lint has zero errors/warnings and **30 informational dependency-update advisories** under the documented compatible-pin policy. App/test APK packaging, debug-signature verification, manifest/permission inspection, ZIP CRC/alignment and native-library alignment checks passed.

**No phone, emulator, connected instrumentation or remote CI ran.** Host-rendered Compose PNGs are not phone screenshots. APK installation, real providers/permissions, OS process lifecycle, accessibility, OEM backup, independent security review and release signing remain open gates. Light theme is supported; dark/system theme is not claimed implemented.

Desktop evidence is unchanged: its recorded 342 passes / 6 failures and unverified later corrections are neither rerun nor certified by Android tests.

## Next phase

Start **Phase 2: protected transport identity, real QR pairing, explicit desktop approval, TLS 1.3 mutual authentication, discovery/reconnect and revocation**. First run the device checklist on a dedicated test installation and settle signing-key custody for ongoing development updates. Do not enable a network permission or advertise a secure connection before those implementations and negative interoperability checks exist.
