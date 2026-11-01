# NARADA Android — Phase 5 handoff

**Development implementation delivered: durable, private, foreground text messaging.** Version **0.5.0 (5)**, `com.narada.mobile.dev`, API29–36. The independent crypto/storage review and Android execution gates are **not complete**; this is not a qualified release.

[Project / build](apps/android/README.md) · [Phase-wise plan](android-plan.md) · [Split documentation](docs/android/README.md)

## What changed

- A small, stateless Rust/JNI adapter to locked **vodozemac 0.11.1**, using the desktop's exact **Olm v1 / 64-bit MAC** mode. No custom ratchet, experimental-v2 substitution, plaintext/TLS-only fallback or downloaded crypto library.
- Explicit messaging enrollment, signed transport-bound peer keys, retained key pins and protected account/session state. Software Olm keys are encrypted at rest using the existing AndroidKeyStore-backed store; they are **not hardware-held ratchet keys**.
- Sealed transactional outbox/inbox: ratchet plus exact ciphertext saved before push; ratchet plus plaintext history and matching receipt saved before ACK. Retained duplicate receipts can be re-ACKed without another decryption.
- Saved encrypted drafts/history, explicit bounded sync/retry/cancel, honest unknown outcomes and no automatic resend after restart. Text only; attachments remain Phase 6.
- Private Chat UI following the supplied monochrome mobile references, lifecycle/authority clearing, non-saveable plaintext and generic opt-in notifications. Production contains no synthetic messages.
- Shared300 ms content admission across Files, Chat and Transfers. Prior file/download/export safeguards remain.

## Measured result

| Campaign | Final record | Passed |
|---|---|---:|
| Rust adapter, Linux host | `preparation/olm-host-05` | 12 |
| Domain | `domain-02` | 22 |
| Connection/file/transfer/chat core | `connection-03` | 299 |
| Actual unchanged Rust desktop peer | `interop-02` | 43 |
| Android host/Robolectric | `host-02` | 205 |
| **Total** | Final campaigns only | **581** |

**102 additional checks versus Phase 4.** Final campaigns have zero failures/errors/skips. Lint: **0 errors, 0 warnings, 36 informational Hints**. Debug APK and instrumentation APK builds passed; **10 instrumentation cases compiled, zero executed**. The final audit matched **177 executable/runtime/test/build inputs** across all seven final Gradle stages and native source receipts. It verified signatures, manifest, JNI provenance and static16 KiB alignment. This is not Android loading/execution proof.

The signed APK bytes are packaged in `NARADA_Android_Phase5_APKs.tar.xz`. The separate source handoff includes all177 measured Android inputs, actual peer source, split docs and selected evidence. Its three unchanged design-reference PNGs are intentionally not duplicated: retain `references/` from the full workspace or the unchanged Phase 4 source archive. HTML prototypes and a five-asset SHA256 manifest are included. These PNGs are design inputs, not build dependencies.

## Deliberate limits

32 retained messages total; four chat bindings; two sessions/peer;4096 UTF-8 bytes/text;2 MiB sealed aggregate, shared with earlier journals. Capacity failures preserve existing data, rather than silently evicting history, unknown outcomes, pins or sessions. A sync batch handles at most three old ACKs, three eligible sends and three pulls. Retry budget is three admitted attempts with at least four-second spacing; retry never means new ciphertext or a new message ID.

No phone listener/owner API, cloud relay, permanent presence, background messaging, upload, encrypted attachments or automatic pins/sync. A delivery receipt means durable protocol storage, not a human read receipt. Local read marking is not transmitted as a read receipt.

## Open gates and signing warning

- Independent review of the JNI/application/storage integration, protocol compatibility, truncated MAC choice, dependency advisories and licenses.
- AndroidKeyStore/Conscrypt, real JNI on devices/emulators,16 KiB runtime, Windows LAN, backup/restore/upgrade, kill/power-loss/ENOSPC/rollback, IME/TalkBack/insets/OEM notification testing.
- Whole-state rollback resistance and guaranteed plaintext-memory erasure are **not established**. Native byte-array clearing does not prove Java strings or library temporaries are erased.
- No remote CI execution or release approval. The refreshed CI definition is source only.
- **The supplied debug signer differs from Phase 4 and Phase 1–3. No in-place upgrade is qualified. Do not uninstall or clear valued app data to resolve a signature conflict.** Use a fresh disposable test device/profile. Uninstall/clear-data destroys identity/trust and is not lossless repair.

The desktop remains0.7.0/schema6. Its historical single-pass **342-pass/6-failure** record and later unvalidated corrections remain unchanged. Focused Android interoperability is not a rerun or approval of that entire desktop campaign. All1,922 frozen pre-Phase 5 inputs remain byte-identical; failed Phase 5 attempts are separately preserved.

## Read by topic

[Scope / tasks / metrics](docs/android/phase-5.md) · [Crypto / tech stack / review gate](docs/android/chat-crypto.md) · [Wire protocol / mechanisms](docs/android/chat-protocol.md) · [Recovery / precautions](docs/android/chat-recovery.md) · [End-to-end workflow / tips](docs/android/phase-5-workflow.md) · [Validation](docs/android/phase-5-validation.md) · [Problems / solutions](docs/android/phase-5-problems-solutions.md) · [Native checklist](docs/android/phase-5-device-checklist.md)

Next feature phase: **6 — encrypted attachments**, beginning with the separate Android-compatible age-format and cross-platform-vector gate. This does not waive any Phase 5 review/native blocker.
