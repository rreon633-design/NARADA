# Phase 5 — measured validation and evidence boundaries

[Handoff](../../ANDROID-PHASE5-HANDOFF.md) · [Evidence index](../../artifacts/android-phase-5/README.md) · [Open native gates](phase-5-device-checklist.md)

## Final campaigns — no additive superseded totals

| Campaign | Preserved final record | Passes | Seconds |
|---|---|---:|---:|
| Linux Rust adapter | `preparation/olm-host-05.log/.json` |12|2.861|
| Domain | `runs/domain-02` |22|15.360|
| Connection/file/transfer/chat core | `runs/connection-03` |299|27.704|
| Actual Rust peer interoperability | `runs/interop-02` |43|61.006|
| Android host/Robolectric | `runs/host-02` |205|299.112|
| **Total** | **Final campaigns only** |**581**|—|

All final test campaigns:0 failures/errors/skips. **102 new checks versus Phase 4's479:**12 Rust +48 connection (12 protocol,24 engine,12 client) +9 actual-chat interop +33 app host (11 protected-store,8 coordinator,12 UI,2 notification).

| Build/analysis | Record | Result | Seconds |
|---|---|---|---:|
| Lint | `lint-01` |exit0;0 errors/warnings,36 Hints |121.506|
| Debug APK | `apk-01` |exit0 |122.597|
| Instrumentation APK | `instrumentation-01` |exit0;10 cases compiled,0 run |82.226|
| Artifact audit | `apk-audit-04` |manifest, signatures, provenance, static alignment, DEX and input/history checks passed |Not a runtime campaign |

The Hints are informational pinned-version update-advisory categories, not an application lint baseline. The first artifact audit found a pre-/post-AGP native hash comparison mistake; the second found AGP's transparent `.gz` asset expansion. Both failed transcripts remain. Audit03 resolved those transformations; audit04 additionally verified merge/strip intermediates and derived the old signer from frozen signature/summary evidence. No application/runtime source was altered to bypass these checks.

## What is bound to the artifact

The audit compares the complete **177 executable/runtime/test/build inputs** across all seven final Gradle stages and current source. The Rust test's before/after three-source hashes agree with the generated native build receipt. Generated native binaries are source-receipted; all four packaged Olm libraries match a separately reproduced pinned-NDK strip and AGP output. This is stronger than asserting that a raw Cargo library hash must equal stripped APK bytes.

Debug and test APK signatures verify and match each other. The manifest is `com.narada.mobile.dev`,0.5.0/code 5,min29/target36,debuggable,backup off,cleartext off. No new Android permission, exported content server or test software-identity generator appears. Test Bouncy Castle classes/fixtures are absent from the app APK; production software Olm crypto is intentionally present, protected at rest.

Static ELF machine/JNI export/RELRO/BIND_NOW/non-executable-stack and16 KiB alignment checks passed for the four Olm libraries. All bundled64-bit ELF LOAD alignment and APK ZIP16 KiB alignment passed. **No16 KiB Android loading test ran.** Notices match the expanded APK asset; the102-package inventory is not a reviewed binary SBOM.

Machine-readable hashes, signer digests, manifest and source maps are in `validation-summary.json`, `validated-input-hashes.json` and `native-binary-audit.json` under the evidence index. The APK bundle is SHA256-checked and round-tripped without changing signed APK bytes.

## Actual interoperability, not a fake protocol

The reference executable is the unchanged desktop Rust agent, built on Linux with the explicit development-vault feature for disposable fixture profiles. SHA256:

`b410279635fa7e3bc711f94bc8dfae555d8bb099272061bb54c18d237e277de1`

43 cases include34 retained connection/file/transfer tests and nine chat tests. The new cases exercise Unicode/two-way replies, lost push/ACK, exact retry, restarts, withdrawn grant, failed inbox persistence,4096-byte text and unsupported attachments. The expiry fixture elsewhere uses a deliberately clock-aged disposable SQLite snapshot, not24-hour operating evidence.

## Preservation and UI evidence

All **1,922 frozen pre-Phase 5 inputs** are verified byte-identical, including supplied references and historical desktop/Android evidence. Historical and preliminary logs/XML/renders are compacted/deduplicated with SHA256 receipts; absence of a loose file is not deletion of its bytes. Use `scripts/android_phase5_history.py` and the [evidence index](../../artifacts/android-phase-5/README.md) to recover an exact original path to a new destination. Failed attempts and passing final records remain distinct even if a screenshot's bytes coincide.

Final host renders19–22 show closed/authorized chat, retained receipt history, reviewed unknown outcome and200% text. These are synthetic Robolectric captures, not Android-device screenshots. Visual inspection confirmed the reference-aligned monochrome controls and flowing large text in the captured scrolled regions; no whole-device accessibility claim follows.

The selected source archive includes the measured inputs, actual peer source, split docs, selected logs/XML/renders and source manifest. It omits the three unchanged design-reference PNGs to avoid duplicating large original assets; their exact hashes and preserved workspace/Phase 4-source locations are documented. They are not build dependencies. Full historical preservation containers/verbose DEX inventory remain separately in the full workspace, not nested in source.

## Not executed or approved

**Zero Android devices/emulators; zero instrumentation executions; no Windows-native/LAN campaign; no remote CI; no independent integration/security/license approval; no release signing or update qualification.** Linux JNI/JSSE/software fixture keys and Robolectric do not substitute for AndroidKeyStore/Conscrypt/Binder/OEM behavior. Fresh full-cache restoration of the adjusted native provisioner has not been exercised; successful builds used the already repaired NDK installation.

Whole-state rollback resistance, real power-loss durability, hostile-provider stoppability, guaranteed plaintext erasure and sustained performance remain unproven. The supplied signer differs from all prior Android phase signers; clear-data/uninstall is not a lossless migration. Historical desktop342-pass/6-failure evidence and Android Phase 1=82,Phase 2=186,Phase 3=297,Phase 4=479 remain unchanged, not summed into581.
