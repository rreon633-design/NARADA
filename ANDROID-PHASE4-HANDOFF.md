# NARADA Android — Phase 4 handoff

**31 October 2026 · Development implementation delivered, not a qualified release.**

[Documentation index](docs/android/README.md) · [Phase-wise plan](android-plan.md) · [Build / tech stack](apps/android/README.md)

## Delivered

Foreground-only **desktop → phone ordinary-file downloads**: immutable manifests, bounded chunks, encrypted private staging, revalidated checkpoints, pause/cancel/explicit resume, restart-to-paused recovery, full-hash private receipts and independent finish acknowledgement.

A **separate, explicit create-new SAF export** follows private verification. It uses fresh picker consent, no destructive replacement, close/readback/full-hash verification, a protected uncertainty journal and bounded provider execution. Receiving a private copy is not publishing it. Provider upload/retention and possible partial documents are explained before consent.

Native Files/Transfers screens use actual authorized selections and protected records. No fake production transfers/progress, background content service, phone listener, uploads, Olm messages, age attachments or automatic pins/sync were added. The supplied mobile references remain the visual direction; all five original visual assets are unchanged.

## Measured result

| Gate | Executed result |
|---|---|
| Domain / connection core | **22 + 251 passed** |
| Real Rust peer interoperability | **34 passed** — connection 10, files 12, transfers 12 |
| Android host / Compose / adapter checks | **172 passed** |
| Final total | **479 passed; 0 failed, errors or skips** |
| Lint | **0 errors, 0 warnings, 36 informational update hints**; no application baseline |
| Build / artifact audit | Debug and instrumentation APKs built; manifest, signatures, DEX and static alignment checks passed |
| Evidence binding | **157 runtime/test/build inputs** matched across all seven final stages; **1,774 historical files** verified byte-identical |
| Instrumentation | **8 cases compiled; 0 device/emulator executions** |

See [measured validation](docs/android/phase-4-validation.md) for exact records, earlier failures/corrections and host-only boundaries. Intermediate/filtered runs are not added to the final total. No historical desktop campaign was rerun.

## Development artifacts and signing warning

- `NARADA_Android_Phase4_Source.tar.xz` — source, split Markdown documentation, current host evidence, unchanged actual Rust peer source and original references; no private keys/caches/profiles.
- `NARADA_Android_Phase4_APKs.tar.xz` — both signed development APKs, internal SHA256SUMS and installation warnings. Extract with tar/7-Zip before installation. XZ keeps the signed APK bytes unchanged while preserving workspace history within its storage budget.
- Adjacent `.sha256` files and the [artifact receipt](artifacts/android-phase-4/validation-summary.json) bind the files. This is integrity evidence, not release signing or independent approval.

**Application:** `com.narada.mobile.dev`, **0.4.0 (4)**, min API 29 / target 36; Room 3; protected payload v3 reads v1–v3 without rekeying.

**The Phase 1–3 private debug signing key is unavailable. This APK uses a different development certificate and is NOT an in-place update for those APKs.** Use a fresh disposable test device/profile or build with your own controlled compatible signing key. Do not uninstall/clear-data as “repair”: it loses the existing local identity/trust and private state. No signer-continuity or lossless APK-upgrade claim is made.

## Read next

- [Scope / work packages / success metrics](docs/android/phase-4.md)
- [Transfer protocol and mechanics](docs/android/transfers-protocol.md)
- [Recovery and storage precautions](docs/android/transfers-recovery.md)
- [SAF authority and publication boundary](docs/android/saf-export.md)
- [End-to-end workflow / tips / troubleshooting](docs/android/phase-4-workflow.md)
- [Unexecuted native checklist](docs/android/phase-4-device-checklist.md)

AndroidKeyStore/Conscrypt, real provider/Binder behavior, Android process/power loss, Windows LAN, OEM filesystems, accessibility, resource measurements, remote CI, independent review and release qualification remain open. Phase 5 (reviewed Android-compatible Olm messaging) is next; phases 6–9 remain separate gates.
