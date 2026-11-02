# Android private-storage capacity probe

## Native evidence, not a host-only assumption

Fresh run [41312970060](https://github.com/rreon633-design/NARADA/actions/runs/41312970060), source `18ff2ce6962ae600d050512249ae3448a04eb2b0`, passed tooling, Linux, the Windows installer/bootstrap gate and Android build/audit. The exact minified x86_64 APK executed all **13 instrumentation cases on both API 29 and 35: 12 passed, 1 failed, 0 skipped** on each. The failure was `nativeNoBackupFragmentsReopenAndNeverOverwriteCorruption`, at transfer reservation, reporting `LOCAL_STORAGE_UNAVAILABLE`. Publication remained blocked.

Android 10 and Android 15 libcore source both explicitly make `Files.getFileStore(Path)` throw `SecurityException("getFileStore")`; global filesystem/mount information is not exposed through that API. The shared JVM chunk store's default capacity probe used it, and the Android factories had not supplied their platform-specific probe.

Sources:
- https://android.googlesource.com/platform/libcore/+/refs/tags/android-10.0.0_r1/ojluni/src/main/java/sun/nio/fs/UnixFileSystemProvider.java
- https://android.googlesource.com/platform/libcore/+/refs/tags/android-15.0.0_r1/ojluni/src/main/java/sun/nio/fs/UnixFileSystemProvider.java

## Correction and unchanged safety boundaries

Both Android factories—ordinary received files and attachment fragments—now explicitly use `StatFs(context.noBackupFilesDir.absolutePath).availableBytes` through the existing `freeBytes` injection point. Each admission probe obtains current statistics from the existing app-private volume. This is not a retry/fallback after a denied mount-table query, and it needs no broader storage permission.

The shared engine is unchanged: **16 MiB headroom plus fragment overhead**, immutable encrypted fragments, no-follow checks, no-replace hard-link publication, file/directory synchronization, full authentication/hash verification and corruption refusal all remain enforced. A denied/failed probe does not invent capacity; it still fails closed. Available blocks, rather than filesystem-reserved free blocks, are used.

Four host regressions cover both factories: reserved-block exclusion, unchanged headroom/overhead, re-probing after construction and a denied probe with no filesystem mutation. The existing native fragment case is expanded to perform real write/reopen/corrupt/refuse-overwrite checks in both private stores. No native case is skipped or weakened; cleanup remains limited to UUID-owned test fixtures.

All four targeted Robolectric SDK 35 regressions passed locally (zero failures/skips). A test-setup compilation error and a sandbox memory-limit failure are retained separately; neither is a pass. The successful host results are adapter tests—not native storage or device proof. GitHub must freshly execute all mandatory checks and both complete 13-case exact-APK suites before `v0.0.1` can be released. Power-loss, whole-state rollback, physical-device/provider and independent-security qualification remain open.
