# Phases 6 & 7 — problems, corrections and preserved attempts

[Final validation](phase-6-7-validation.md) · [Workflow](phase-6-7-workflow.md) · [Native gates](phase-6-7-device-checklist.md)

Earlier attempts remain recorded; passing final runs do not erase their outcomes. Large old logs/XML/renders may be exactly relocated or aliased. The full-workspace `scripts/android_phase67_history.py PATH --output NEW_PATH` resolves retained member bytes; selected evidence is included in the compact source bundle.

| Attempt / problem | Outcome and correction |
|---|---|
| Excluded tool caches absent after restore | Pinned JDK/Gradle/Rust/SDK/NDK dependencies were provisioned again with download hashes. No previous private signing key was restored |
| Core/app early compilation | Wrapper permission, nullable/smart-cast and fixture lambda/shadowing/Unit-return issues were corrected; failed logs retained |
| Olm host-02 | 19 pass / 1 fixture failure; corrected fixture key length. Later 20-test runs passed; final native receipt is source-bound |
| connection-01 / -03 / -04 | 299 / 378 / 402 passing checks at those earlier input sets. They are not the final current 418-case evidence |
| interop-01 (attachment filter) | 7 pass / 5 fail. Owner fixture used `parent_id` instead of actual `directory_id`; corrected without weakening the protocol |
| interop-02 | 62 pass / 3 fail. Stable snapshot retained 22 rows while changes exposed the 23rd; endpoint change advanced local epoch; revoked attachment grant legitimately withheld the envelope with no ratchet advance/ACK. Corrected expectations; fresh interop-03/-04/-05 passed all 65 |
| host-01 / -02 | Incomplete compiler/daemon failures, no complete XML; **not passing campaigns**. The memory guard terminated an oversized JVM and an orphan worker was stopped |
| host-03 | Worker killed after log output of 255 completed / 12 failed / 1 skipped; no complete XML. Stale migration/future-version/UI assertions corrected; this partial run is not a measured all-suite result |
| host-04 / -05 | Compiler heap failure; then mismatched KSP `--info` input caused recompilation in the tiny test daemon. Both precompile/test now use the same log level |
| host-06 (offline filter) | 6 pass / 2 fail exposed a real race: desktop finish ACK changed `remoteFinished` while the candidate was reverified. Adoption now ignores only that independent flag; changed local receipts still fail. Both have core regressions |
| host-07 | Complete 259-test run, 257 pass / 2 fail. Attachment export consent did not become idle in two Robolectric cases. Reused the bounded, secure full-screen consent component with scrollable body and fixed reachable actions; tests include explicit checkbox consent and 200% type |
| host-08 | All 259 passed on the pre-final-KTX source. Final host-09 separately passed all 259 on the audited input set |
| Late cursor-expiry review | Added exact-work ownership to automatic snapshot restart; an old expiry cannot replace newer work or recreate forgotten metadata. Added late-success/error/forget, 16-page, combined quota and metadata-byte regressions |
| Attachment TLS boundary review | Added dedicated PUT/blob-GET tests for exact opaque bytes/headers, bounds, hashes, truncation, duplicate headers, redirects, compression and sanitized error codes |
| Native notice aggregation | First inventory omitted COPYRIGHT-named files; next included them. Final inventory also includes immutable-commit age/age-core root license supplements. Earlier inventories are retained, not represented as final legal review |
| lint-01 | One `UseKtx` error, 36 Hints; changed `Uri.parse` to the existing `toUri` extension |
| lint-02 | Memory guard stopped JVM at approximately 1.18 GiB RSS while compilation/lint shared a large process. No fresh completed lint report; not clean evidence |
| lint-03 | After compilation, a 512 MiB heap/256 MiB metaspace run passed: zero errors/warnings, 36 Hints |
| apk-audit-01 | Auditor incorrectly expected age to share Olm's generic JNI `call` symbol. Corrected audit to require age's four actual exports; no application change. Fresh apk-audit-02 passed |
| AGP native stripping | Packaged `.so` bytes legitimately differ from Cargo-stripped input. Reproduced NDK `llvm-strip --strip-unneeded` matches every packaged age/Olm ABI byte-for-byte |
| Snapshot space | Exact old run files are compressed/aliased; historical APK containers use verified standard-library-restorable deltas. APK signatures/bytes and frozen source/evidence are not rewritten |

## Final record discipline

Final domain/core/actual-peer/app tests were rerun after the KTX correction: **22 + 418 + 65 + 259**, plus **12 + 20** native Rust tests, totaling **796**. Lint, instrumentation/APK build and APK audit are separate stages, not extra passing test cases. 13 instrumentation cases are compiled, **zero run**.

The source fingerprints include both main and tests, assets, native locks and build inputs; tool versions/copies are recorded. Missing output, filtered preparation, interrupted processes and static binary checks never stand in for executed native tests. Historical desktop Phase 4 and desktop 5–9 campaigns remain unrerun; their original failures and unvalidated corrections retain their original meaning.
