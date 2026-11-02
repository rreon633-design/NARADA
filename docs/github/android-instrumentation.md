# Instrumentation against the exact minified APK

## Ninth-campaign outcome

Run [41312970060](https://github.com/rreon633-design/NARADA/actions/runs/41312970060) passed all minified APK boundary/definition audits. The runner then executed **13 cases on each API 29/35 emulator: 12 passed, 1 failed, 0 skipped**. JNI age/Olm, AndroidKeyStore and activity recreation cases ran; the earlier runner-linkage blocker was resolved. The remaining failure was a separate app-private storage capacity probe, not a reason to weaken shrinking or skip tests. See [the platform-specific storage correction](android-private-storage.md). Publication was skipped, and the revised source still needs a complete fresh campaign.

## Eighth-campaign result

Run [41288851145](https://github.com/rreon633-design/NARADA/actions/runs/41288851145) built, signed and statically audited all four per-ABI APKs. They measured 6,497,156–7,382,196 bytes (6.20–7.04 MiB), below the 20 MiB limit. Both API 29 and 35 emulators installed the exact x86_64 APK and reported a successful MainActivity launch command. Their instrumentation processes then crashed in `AndroidJUnitRunner.onCreate` because `androidx.tracing.Trace` was absent. **No test case ran successfully.** Neither attempt is a passing native test result.

The APK definition inventory confirmed that `Trace` was absent from both APKs. Inspection also found other cross-APK reference gaps, including test-consumed futures/Compose/Kotlin interfaces. A single tracing keep rule would not address the general boundary. Type-reference absence alone does not prove every referenced path is executed; the runner crash is the observed failure.

## Tenth-campaign follow-up

The revised capacity adapter passed host tests and native reservation, but the same fragment case then failed at writing on both APIs (12/13 each). Android prohibits hard links; [the subsequent publication adapter](android-atomic-publication.md) adds a dedicated native collision/symlink case, raising the fresh requirement to **14**. Earlier partial results remain failures.

## Generated, explicit class/member boundary

For the explicit `manualTest` test host, AGP's scoped pre-shrink class artifacts supply two inputs:

- **Target:** the application's classes and runtime dependencies.
- **Source:** instrumentation classes and test-only dependency classes. Shared definitions are checked for identical bytes and removed from the source side, so unrelated app APIs are not retained simply because they share a dependency.

The verified, bundled **R8 8.13.19** `TraceReferences` API infers the exact target classes and members referenced across that boundary. The resulting explicit rules are attached through `variant.proguardFiles`. Names/signatures are retained; access widening is permitted to avoid stranding Kotlin facades beside repackaged internals. No wildcard package/application keep, `-dontoptimize`, `-dontshrink`, `-dontwarn`, or `-ignorewarnings` is added to either APK shrinker. App R8 and resource shrinking remain enabled, as does instrumentation shrinking. Debug and the separate production `release` variant do not acquire these rules.

Inferring instrumented-test entry points with TraceReferences is also the purpose described by Keeper's task documentation [2](https://slackhq.github.io/keeper/0.x/keeper-gradle-plugin/com.slack.keeper/-infer-android-test-keep-rules/index.html). NARADA implements its own bounded task using the existing verified AGP tool; no new plugin/runtime dependency was added.

## Analysis-only platform metadata and diagnostics

The inputs are JVM classfiles **before Android desugaring and test dead-code removal**. Analysis therefore uses eight real SDK/JDK class definitions for the JVM string-concat bootstrap, desktop-only Hamcrest bean utility signatures, and legacy Android test APIs. Their original file hashes and the separate analysis JAR hash are recorded. This JAR is never placed on app/test runtime configurations or appended to their class artifacts.

Public SDK stubs omit five specific `execStartActivity` overloads inherited by AndroidX Test's hidden-framework bridge. Only those exact method signatures, with the pinned R8 version, are emitted as fully recorded analysis notices. Unknown methods, fields, app/library classes, mixed diagnostics and empty diagnostics remain errors; negative checks exercise this fence. This is **not** a warning relaxation for application/instrumentation R8, Android lint, APK auditing or emulator execution.

The collector checks actual DEX **definitions**, not just referenced strings. Every inferred named class must remain in each minified app APK. None of the analysis-only platform definitions may appear in either app or instrumentation APK. Receipts and generated rules are copied into fresh CI evidence.

## Local validation versus release qualification

Local classfile compilation and inference produced **401 explicit class/member headers** from 18,040 target and 1,624 test-only source classes. The dependency locks and verification metadata did not change. The previous failing APK is a negative control for the new named-boundary audit. Intermediate script/setup/analysis failures remain recorded.

This local task assembled no APK and executed no device tests. The two native-binary prebuild checks were excluded **only for this non-APK local classfile analysis**, where no JNI binaries were built; GitHub's native compilation, source/binary verification, lint, minification, signed APK collection and exact-APK emulator gates are unchanged and mandatory. Fresh Windows installer/bootstrap and both API-level instrumentation suites must pass in the same run/source/attempt before publication.
