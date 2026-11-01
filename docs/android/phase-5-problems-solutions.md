# Phase 5 — problems, corrections and practical lessons

[Validation](phase-5-validation.md) · [Build](../../apps/android/README.md) · [Recovery](chat-recovery.md)

Every attempt has its own evidence. Corrections below are not retroactive rewrites of earlier results.

| Problem / preserved attempt | Correction and later evidence | Boundary |
|---|---|---|
| Rust adapter compile E0308, then strict unknown/duplicate-field test failure (`olm-host-01/02`) | Corrected types/empty request variants; final05 has12 passes | No weakened parser or invented Android run |
| NDK ZIP symbolic links extracted as ordinary files |39 links repaired before native builds; provisioner now restores/validates links and SDK NDK link | Fresh complete restoration after those script changes not executed |
| Gradle import/name shadowing (`core-compile-01`) | Explicit imports; later compilation succeeded | Source failure preserved |
| Chat fixture capabilities had invalid shape (`chat-core-01`:7 pass/25 fail) | Fixture-only correction; health validation retained | Later full connection03 has299 passes |
| Interop filter selected nothing; then peer lacked Linux protected-store development feature | Fixed task include; rebuilt unchanged actual agent with explicit `narada-connection/dev-vault` | Disposable Linux fixture only; no Android fallback or production Windows claim |
| App compile RSS exceeded small-host guard |384 MiB heap/384 MiB metaspace,64 MiB code/direct caps and sequential warm compilation | Resource workaround, not mobile memory/performance evidence |
| Initial unit-test compilation hit Kotlin smart-cast/ambiguous `fail`; filtered host run lost daemon | Corrected test code and separate warm precompilation | Preserved attempts; no added pass count from incomplete/no-JUnit runs |
| Full `host-01`:203 pass/2 fail | Stale expected protected payload3 and planned-chat label changed to4 and closed private view/no composer | Full host02:205 passes; no production logic bypass |
| First migration test edit targeted a nonexistent variable name | Corrected the actual `upgraded.version` assertion; precompile07 and host02 succeeded | The unsuccessful edit is not called a passing run |
| Direct generated-library hash did not match APK (`artifact-audit-01`) | AGP runs NDK `llvm-strip --strip-unneeded`; independently reproduced exact transformation and checked merge/strip/APK chain | Do not delete provenance assertions or pretend stripped bytes are raw build bytes |
| Expected `.gz` notice filename absent (`artifact-audit-02`) | AGP expands gzipped source assets; compared expanded APK bytes with verified decompression | Notices are packaged, not silently omitted |
| Snapshot/storage budget and repeated evidence | Lossless archives plus exact-byte aliases, verified before removing loose duplicates | Frozen inputs/failed campaigns remain recoverable; no private key/profile in delivery |

## Tips

- Build native libraries first. Missing/stale JNI input is a hard failure, not a reason to disable native verification.
- Run memory-heavy compiler, native Rust, graphics tests and lint sequentially on a2 GiB host. Lint uses640 MiB;384 MiB previously thrashed.
- Set up the real reference peer with the explicit Linux test vault when using host fixtures. Do not ship that fixture feature as an Android key/storage fallback.
- Keep extraction paths short for local Unix socket limits in the actual-peer tests.
- Keep new stage/audit directories rather than overwriting failures. Final total581 is not the sum of every recorded rerun.
- Preserve old signer/data continuity; signature conflicts are not solved by silently deleting the identity/store.
- Review the truncation-MAC/rollback/memory limitations before expanding the feature to attachments or release.
