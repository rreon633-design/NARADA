# Phase 4 — measured validation and honest boundaries

**31 October 2026.** [Handoff](../../ANDROID-PHASE4-HANDOFF.md) · [Artifact report](../../artifacts/android-phase-4/validation-summary.json) · [Native checklist](phase-4-device-checklist.md)

## Final accepted cohort

| Record | Scope | Result |
|---|---|---|
| `domain-02` | Domain/foundation policy | 22 passed |
| `connection-03` | 14 protocol/client/engine/chunk/export/pacing suites | 251 passed |
| `interop-03` | Actual unchanged Rust peer; connection 10 + files 12 + transfers 12 | 34 passed |
| `host-03` | 23 Android host/Compose/repository/provider/ownership suites | 172 passed |
| `lint-03` | Full lint, no application baseline | 0 errors, 0 warnings, 36 informational hints |
| `apk-01` | Debug APK build | Passed |
| `instrumentation-01` | Instrumentation APK build | Passed; **not executed** |

**479 passed, zero failed/errors/skips.** Eight instrumentation cases compiled; zero device/emulator executions. No Windows native/LAN campaign, remote CI, independent review or release approval ran.

The final audit compares **157 runtime/test/build-input hashes across all seven stages**, validates nonzero JUnit suites and exact summary counts, rejects `--tests` filters, audits actual APKs and verifies 1,774 frozen historical files. Raw stage receipts captured 160 paths; Markdown, `.gitignore` and optional `dev-env.sh` are excluded from the executable input comparison. Equal file counts do not establish source equivalence.

## Current detailed coverage

- Core transfer additions: DownloadEngine 22, TransferTransport 10, TransferChunks 15, TransfersClient 22, TransfersProtocol 20, TransferExport 19 and production pacing 4; retained browser tests now include actual download selection fences.
- Native host transfer suites: protected journal 7, bounded provider I/O 6, picker binding 4, provider adapter 11, Transfers coordinator 9, Files download UI 5, Transfers UI 5 and review UI 4. The connection lease suite now has 10 checks including owner isolation. These are included in 172, not additional tests.
- Actual transfer interop: exact bytes/receipt/finish, empty and Unicode input, 34 chunks with paginated hashes/partial tail, fresh engine/restart, stale selection, grant withdrawal, revoke, corrupt checkpoint, lost prepare/finish replies and clock-aged source expiry. The expiry case stops its **disposable** desktop agent, ages its owned SQLite snapshot and restarts; no original user profile is touched.
- Eight compiled native cases include the five earlier platform/connection cases plus nonexportable/context-bound transfer AES, no-backup chunk tamper/no-overwrite and reopened receipt/uncertain-export journal checks.

## Earlier runs and corrections — preserved, not counted again

| Evidence | Outcome / correction |
|---|---|
| `connection-01` | 237 passed on earlier inputs; `connection-02` later 251 passed before the final native KTX edit |
| `interop-01` | 32 passed / 1 failed: 34-chunk case received IDENTITY_REJECTED; unchanged peer limits include 12 TLS starts/second |
| Pacing correction | Shared production 300 ms admission, not test-only sleeps/server weakening/auth retries; `interop-02` and final `interop-03` both passed all 34. Rate admission was the consistent explanation, not a captured server admission trace |
| `host-01` | 168 passed / 3 failed of 171: two bounded-worker cases plus an obsolete shell headline assertion |
| Worker/test corrections | Future carries `Result<Unit>` to preserve typed failures; thread-role check permits coroutine-debug suffix; current shell copy asserted. Added independent monotonic deadline regression and mutation-internal guards |
| `provider-host-02` | Filtered 6 passed; never eligible as a complete host campaign |
| `host-02` | Full 172 passed before the final KTX edit; final `host-03` reran all 172 after it |
| `lint-01` | Two `UseKtx` errors; changed the two production URI conversions to `toUri()`, without a baseline or suppression |
| `lint-02` | Not successful: 384 MiB heap thrashed; tool wait timed out, owned JVM investigated then terminated. 1,131 full GCs were observed. Exit 1 preserved; any copied lint report is not fresh acceptance evidence |
| `lint-03` | 640 MiB heap, fresh output reports, passed; recorder now clears previous build lint reports before running |
| Final cohort | Domain/core/interop/host and both APK stages reran after the final native edit; all source-matched |

Initial compilation/provisioning corrections and their exact logs are retained. Earlier log/XML/render files were losslessly archived with per-member SHA-256/length checks; summaries and source hashes stay at their original paths. See [preservation/recovery](../../artifacts/android-phase-4/README.md). No old desktop result or Phase 1–3 test result was rewritten.

## Artifact checks

`com.narada.mobile.dev` 0.4.0 (4), min29/target36, debuggable development build; Room3 and protected payload3/read1–3. Manifest permission set is unchanged. Backup and cleartext are disabled; app-owned providers/scan activity remain private. Export uses an external SAF provider, not a new app content surface.

Both APK signatures verify and match each other, but **not the Phase 1–3 signer**. Current certificate SHA-256:

```text
099784b9d61d25bb9d9e9da4b0d9ee9aa9e3e4a41b9d1ac15ed38aa802a2ef32
```

DEX inspection finds the Phase 4 implementation and no Bouncy Castle/test identity generators in the app APK. ZIP alignment and all bundled 64-bit ELF load alignments were statically checked for 16 KiB compatibility; no 16 KiB device loading/execution claim follows. All five reference hashes are unchanged. Dependency verification records 710 TOFU components, not independent publisher/security approval.

The verbose 28,092,104-byte DEX inventory is losslessly XZ-compressed with raw/compressed hashes. APKs are preserved as exact signed bytes in an outer `tar.xz`, with an internal checksum file and adjacent archive checksum. The source bundle has its own manifest/checksum and link/secret/build-output checks.

## Boundary of proof

Linux JSSE/software certificates, filesystem fault fixtures, Robolectric/API35 native graphics and a custom provider do not establish AndroidKeyStore/Conscrypt, Binder/OS grants, OEM durability, Android process-death, Windows sockets/firewall/discovery, real camera/IME/TalkBack, performance or release behavior. The 18 renders are host captures; transfer fixtures are explicitly synthetic. Entire-main-window `FLAG_SECURE` is implemented but needs real OEM validation.

CI workflow source was updated, not remotely executed. Prior desktop single-pass failures remain unchanged. Green Android interop is narrower evidence, not requalification of the whole corrected desktop product.
