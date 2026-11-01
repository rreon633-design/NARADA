# NARADA — Android mobile development plan

> Started 31 October 2026 at the user's request. Android is no longer deferred as a whole. This plan is separate from the completed desktop development batches and their preserved evidence.

## Baseline and delivery rules

- **Native Kotlin + Jetpack Compose + Material 3**, with Hilt, Coroutines/Flow, Room, DataStore and WorkManager. The supplied HTML is a visual reference, never a WebView substitute.
- Initial development baseline: **Android 10 / API 29 minimum**, compile/target **API 36**. This is not a claim of qualification on every supported API/OEM.
- Five primary tabs: **Home · Files · Chat · Transfers · More**. More contains Sync, Offline files, Devices, Notifications, Settings and Help.
- Match the supplied [mobile screenshot](references/android-screens-reference.png) and [mobile HTML](references/original-android-prototype.html): monochrome surfaces, near-black actions, generous touch targets and rounded cards. Do not copy fake peers, QR codes, progress or delivery states.
- Work in the requested phase batches; Phases6 and7 were explicitly authorized together. Source implementation, JVM/host tests, emulator execution, physical-device qualification, independent review and release signing are separate evidence levels.
- Preserve the desktop source archives and validation history. Android tests use their own evidence directory.

## Phase-wise roadmap

| Phase | Android deliverables | Exit criteria | Current status |
|---|---|---|---|
| **1 — Native foundations** | Reproducible Gradle project, Compose navigation/onboarding, durable settings and metadata, migration/recovery safeguards, private-file/SAF-grant adapters, owned background health work, private notifications, redacted diagnostics, tests and CI source | Build/installable development APK; available host checks pass; recreation does not own or duplicate durable jobs; no destructive storage fallback; native-only checks explicitly recorded | **Development foundation complete** — 82/82 host tests, lint and both APK builds passed; device qualification open. See [Phase 1 scope](docs/android/phase-1.md) and [validation](docs/android/validation.md) |
| **2 — Identity and pairing** | Keystore-backed transport identity, real offline QR scanner, one-time pinned bootstrap, explicit Windows approval, TLS 1.3 mTLS, grants, NSD/manual reconnect/revoke | Android ↔ desktop protocol/negative tests; no insecure verifier, silent identity reset, unauthenticated file endpoint or fake connected state | **Development implementation delivered** — 186 current host/interop checks pass; lint/APK audit pass; native Android↔Windows gates remain open. See [Phase 2](docs/android/phase-2.md) |
| **3 — Authorized files** | Granted remote roots, folder stack, search/sort/pagination, bounded preview, selected-object actions and URI/provider errors | Actual remote entries, permission rechecks, hostile identifiers and denied/offline/revoked cases; no raw-path assumptions | **Development implementation delivered** — 297 final checks pass, lint/artifact audit and both APK builds pass; native qualification open. See [Phase 3](docs/android/phase-3.md) |
| **4 — Verified transfers** | Immutable manifests, bounded chunks, checkpoints, process-death recovery, queue/control, verified private receipt and separate SAF export | Real byte integrity/resume/disk-full/revocation/provider tests; saved copy before publication; no destructive replacement | **Development implementation delivered** — foreground ordinary-file downloads and separate create-new SAF export; 479 final checks, lint, both APKs and audit passed. 8 instrumentation cases compiled/0 run; native/provider/Windows gates open; original signer unavailable. See [Phase 4](docs/android/phase-4.md) |
| **5 — Durable private messaging** | Reviewed Android-compatible Olm integration, transport-bound keys, protected aggregate/session state, transactional outbox/inbox, replay rules, receipts, history/drafts and notification privacy | Interoperate with desktop's actual protocol; persist before send/ACK; no plaintext in metadata/logs/backups; independent crypto/storage review | **Development implementation delivered; review gate open** — pinned Olm v1/JNI compilation/host feasibility,581 final checks,177 matched inputs, both APKs/lint/audit passed.10 instrumentation cases compiled/0 run. Independent crypto/storage review and Android execution are not complete. See [Phase 5](docs/android/phase-5.md) |
| **6 — Encrypted attachments** | Compatible age adapter, fresh key/immutable ciphertext, protected manifests, authorized versioned desktop upload extension, bounded two-way upload/download/resume, full verification and explicit inert export | Actual-peer format vectors, tamper/truncation/reorder rejection, no ordinary-metadata key leakage or nonce reconstruction; independent review remains a gate | **Development implementation complete; review/native gates open** — combined Phases6–7 campaign:796 passes,223 Android inputs, both APKs/lint/audit passed;13 instrumentation cases compiled/0 run. See [Phase6](docs/android/phase-6.md) |
| **7 — Offline pins and refresh** | Scoped cursor/snapshot client, persistent quota-bound pins, one-way refresh, conflicts/tombstones and reference-aware cleanup | Offline usable copies survive restart, failed refresh and source deletion; metadata freshness never implies content availability | **Development implementation complete; native gates open** — persistent scoped caches/pins, exact candidates, verified adoption, retained versions/tombstones and reference-aware cleanup. Shares the796-check Phases6–7 campaign; not a second796. See [Phase7](docs/android/phase-7.md) |
| **8 — Native qualification** | Parser/fuzz/recovery campaigns, TalkBack/200% text/IME/insets, device/router/SAF matrix, measured resources, advisory/license review, migration drills and independent security review | Declared matrix passes; no known critical/high security or silent-loss/false-delivery defect; limits published | Not started |
| **9 — Release and pilot** | Approved package identity and signing custody, signed APK/AAB, update verification, SBOM/provenance, support/recovery ownership, consent-based pilot and go/no-go | Artifact-bound install/upgrade/uninstall/backup tests and real accountable approvals | Not started |

## Phase 1 work packages

**AND-101–110 are delivered for the defined development-foundation scope.** Native/emulator/CI execution, physical accessibility, provider/OEM behavior and release/security approvals remain separate open gates, not completed checkboxes. See the [handoff](ANDROID-PHASE1-HANDOFF.md).

| ID | Deliverable | Evidence boundary |
|---|---|---|
| AND-101 / NAR-009 | Pinned Gradle wrapper, JDK/SDK baseline, domain and Android app modules | Actual local build, not a claim that remote CI ran |
| AND-102 / NAR-010 | Dependency locks/checksums and CI definition | Resolution integrity, not independent supply-chain approval |
| AND-103 / NAR-013 | Room metadata, explicit schema migration, bounded events/jobs/grants and corruption preservation | Host tests plus native interruption tests still required |
| AND-104 / NAR-015 | Native reference-aligned shell, onboarding, five tabs, More destinations, Back and state restoration | Compose/host checks and physical TalkBack/IME qualification distinguished |
| AND-105 / NAR-016 | App-private files and read-only persisted SAF grants | No broad storage permission; real provider qualification remains explicit |
| AND-106 / NAR-016 | Unique WorkManager local-check ownership, cancellation and recovery | Diagnostic work only; not a transfer engine or always-on service |
| AND-107 / NAR-016 | Contextual notification permission/channel and generic private notification | No message/filename previews; device delivery must be verified |
| AND-108 / NAR-062 foundation | User-reviewable allowlisted support export | No device label, URI, message, key, path, endpoint or raw database dump |
| AND-109 | JVM/domain, Room/DataStore/repository, Compose and instrumentation test source | Reports identify precisely which checks actually ran |
| AND-110 | Separate Markdown handoff, architecture, setup, recovery and validation docs | No giant all-in-one document |

## Explicit Phase 1 non-goals

No pairing, LAN transport, actual file transfers, E2EE messages, encrypted attachments, pins, cloud service, permanent socket host, broad phone-storage access, automatic startup service or production release. Their tabs explain their phase honestly. Sensitive-content storage is unavailable rather than falling back to Room/DataStore plaintext. Room and DataStore in Phase 1 contain non-secret local preferences and operational metadata only.

## Phase gates and dependencies

Phase 2 must validate the desktop's **current, corrected** source/protocol; the desktop's historical 342-pass/6-failure record is not approval of later corrections. Android must not assume that a Rust library automatically has a supported Android binding. E2EE/age licensing, platform compatibility, transaction semantics and recovery need explicit decisions before phases 5–6.

Before a release, revisit target SDK/store policy and the supported device matrix against then-current requirements. API 29 is a development floor, not a performance, background-execution or OEM guarantee. Never promise that work continues through force-stop.

## Phase 2 handoff

AND-201–208 source, current host/real-Rust interop evidence and development artifacts are described in [the Phase 2 handoff](ANDROID-PHASE2-HANDOFF.md). This does not mark physical-device, Windows LAN, independent security or release gates complete. The Phase 3 continuation has separate evidence below; qualification blockers are not hidden by later features.

## Phase 3 handoff

**AND-301–309** implement authorized roots/navigation/search/pages/details, bounded JPEG previews, selected managed actions, protected intent/receipt recovery and explicit failure/privacy boundaries. The [Phase 3 handoff](ANDROID-PHASE3-HANDOFF.md) and [measured validation](docs/android/phase-3-validation.md) record **297 final passes, 127 matched inputs, clean error/warning lint and both APK builds**. Five compiled instrumentation tests were not executed. No Android device/emulator, Windows native/LAN, remote CI, independent review or release claim is made.

Room stays at schema 3; sealed trust payloads lazily migrate v1→v2 without rekeying. File metadata/pixels remain foreground-memory-only, not a transfer/offline engine. Local SAF provider handling remains separate; phone refresh is not an owner reindex command. This was the Phase 3 boundary; the separate Phase 4 continuation is recorded below.

## Phase 4 handoff

**AND-401–409** deliver foreground desktop-to-phone ordinary-file downloads: immutable manifests, bounded encrypted chunks/checkpoints, pause/cancel/explicit resume, restart-paused recovery, full private receipts and independent finish ACK. A separate explicit create-new SAF export verifies private data before creation and close/readback before its own receipt; uncertain targets are never automatically rewritten/deleted.

The [Phase 4 handoff](ANDROID-PHASE4-HANDOFF.md) and [measured validation](docs/android/phase-4-validation.md) record **479 final passes, 157 matched executable inputs, clean error/warning lint, both APK builds/audit and 1,774 byte-identical historical files**. Eight native cases compiled; zero device/emulator executions. Host process/fault/provider fixtures are not Android power-loss/Binder/OS-grant/Windows LAN proof. No remote CI, independent review or release approval.

Room remains3; protected payload3 reads1–3, preserving key ownership/AAD. Four retained download records/128 MiB,64 MiB/file,256 KiB/chunk; two retained export records. No upload/listener/background content service, Olm/age or automatic pins/sync. The Phase1–3 private debug signer is missing; the supplied APK uses a different signer, so **no in-place upgrade** is claimed. Uninstall/clear-data is not lossless repair.

**At the Phase4 handoff**, the next feature was Phase5 messaging. That continuation is recorded below; this does not rewrite the earlier Phase4 evidence or retained gates.

## Phase 5 handoff

**AND-501–508 development scope** adds a locked vodozemac0.11.1 /Olm v1/64-bit-MAC adapter, explicit transport-bound messaging enrollment, protected account/session continuity, transactional outbox/inbox, exact replay/receipts, encrypted drafts/history and private foreground UI/notifications. Compiled Android binding feasibility and actual-desktop host interoperability are demonstrated; **independent crypto/storage review remains an uncompleted Phase5 gate**.

The [handoff](ANDROID-PHASE5-HANDOFF.md) and [validation](docs/android/phase-5-validation.md) record **581 final passes,177 matched executable inputs, both APKs/lint/audit passed and1,922 byte-identical historical inputs**. Ten instrumentation cases compiled; no Android/emulator/Windows-LAN execution, remote CI or independent approval. Generated→merged→reproduced NDK strip→packaged JNI provenance and static16 KiB alignment were checked, not native execution.

Room stays3; protected payload4 reads1–4 without rekeying.32 retained messages/four bindings/two sessions per peer,4096 UTF-8 bytes/text and2 MiB aggregate; no silent history/pin/unknown-outcome eviction. Ratchet+envelope precede push; ratchet+inbox+receipt precede ACK. Restart does not send or re-encrypt. Shared300 ms content admission retains desktop limits. Software Olm secrets are protected at rest, not hardware-held ratchet keys. Whole-state rollback resistance remains unproven.

The supplied signer differs from Phase4 and Phase1–3; no same-signer in-place migration is qualified. **At that Phase5 handoff**, the next feature was Phase6, with a separate age-format/library/vector gate. The authorized Phases6–7 continuation is recorded below; the earlier independent/native gates are not waived.


## Phases 6 & 7 handoff — current

The user authorized two-way encrypted attachments and a versioned desktop upload extension, with the phone outbound-only and original desktop source/evidence preserved. **AND-601–607 / AND-701–707 development implementations are complete**: fresh-key age staging, protected typed Olm manifests, explicit two-way byte transfer and inert export; scoped metadata, durable quota-bound pins, exact candidates, full verification/adoption, tombstones and retained references.

The [current handoff](ANDROID-PHASE6-7-HANDOFF.md) and [measured validation](docs/android/phase-6-7-validation.md) record **796 final checks**,223 exact Android input files and101 current desktop input files/binary fingerprinted. Both APKs, clean error/warning lint and static/native-provenance audit passed.13 instrumentation cases compiled; zero Android/device/emulator/Windows executions or remote CI.28 host renders are labeled synthetic.2,370 original historical inputs are preserved exactly; the historical desktop campaign was never rerun or relabeled.

Room stays3; sealed payload6 reads1–6 without rekeying. age0.12.1/defaults off and Olm0.11.1/v1/64-bit-MAC use stateless JNI adapters; content secrets remain software-protected at rest. Whole-state rollback resistance and independent library/JNI/application/advisory/legal review remain open (AND-608/AND-708). The signer differs from Phase5; no in-place upgrade is qualified.

**Two roadmap phases remain:8 (native qualification) and9 (release/pilot), plus retained earlier gates.** Neither native approval nor release is implied by completed feature development. See [the detailed checklist](docs/android/phase-6-7-device-checklist.md).
