# Android immutable-fragment publication

## Tenth-campaign result

[Run 41378161996](https://github.com/rreon633-design/NARADA/actions/runs/41378161996), commit `abea4191606ea14f1c56665fc7915a9b29646158`, passed tooling, Linux, Windows installation/bootstrap, and Android host/build/lint/audit. Both emulators again executed **13 cases: 12 passed, 1 failed, 0 skipped**. The private-fragment case passed reservation and then failed during `write`; publication was skipped. The capacity adapter and all four new host tests passed, but this was not complete native storage qualification.

The shared JVM writer publishes with `Files.createLink`. Android 10 and 15 explicitly prohibit **all untrusted apps from hard-linking any file**, including their own data. The official rule is `neverallow all_untrusted_apps file_type:file link;`. Ordinary file creation/rename permissions do not grant hard-link permission. The mapped instrumentation error does not expose a raw path/errno; the prohibited primitive cannot be used as an Android publication strategy.

Sources:
- https://android.googlesource.com/platform/system/sepolicy/+/refs/tags/android-10.0.0_r1/private/app_neverallows.te
- https://android.googlesource.com/platform/system/sepolicy/+/refs/tags/android-15.0.0_r1/private/app_neverallows.te
- https://android.googlesource.com/platform/bionic/+/refs/tags/android-10.0.0_r1/libc/SECCOMP_WHITELIST_COMMON.TXT (explicitly allows `renameat2` on all architectures)

## Separate, minimal platform primitive

The Android factories now inject a filesystem-only JNI primitive implemented by `native-storage/storage.c`. It is **separate from the unchanged age and Olm cryptographic libraries** and adds no third-party native dependency or persistent native handle.

It uses `renameat2(RENAME_NOREPLACE)` relative to a pinned, app-owned private directory descriptor. Staging is opened no-follow/nonblocking and must be a private, single-link regular file owned by the current UID. Canonical sibling `chunk-N.pending` → `chunk-N.enc` names are bounded to the existing 0–255 indices. Source inode identity is checked, the file is synced, the no-replace rename is performed, the installed inode is checked, the directory is synced and its current path identity is rechecked. All descriptors are closed.

- A destination file, symlink or other collision is never replaced.
- Unsupported kernels/filesystems, denied operations and unknown outcomes fail closed. There is **no** plain-rename, copy, hard-link, permission-bypass or delete-and-retry fallback.
- If a post-publication check/sync/close fails, the uncertain installed object remains untouched. No success/receipt is returned; a later explicit retry still authenticates and hashes the existing immutable fragment.
- No path, content, key or raw OS message is logged. The narrow native result can expose only a bounded numeric status/errno for synthetic test diagnostics; application errors retain the existing redacted codes.

The shared engine accepts this optional consuming primitive; its default JVM hard-link/fsync/delete/fsync path is unchanged. Chunk bytes, encryption/AAD, filenames, 16 MiB headroom and overhead, corruption refusal, resume/checkpoint policy, app/schema/protocol versions and keystore identity are unchanged. No migration, reset or rekey is involved.

## Build and validation gates

A portable Linux build helper compiles the small bridge for the host and all four Android ABIs. Android uses pinned NDK `30.0.16248370` and minimum API 29. The build records source/compiler/binary hashes. A new mandatory Gradle prebuild check rejects missing/stale binaries. APK collection checks the JNI export, reproduced NDK stripping, ELF/ZIP alignment, RELRO/NOW/non-executable stack and original notices just as it does for the crypto libraries.

**27 focused Linux tests passed**, including eight actual host JNI/syscall cases and four additional shared-engine publication/error/collision regressions. These are not Android/SELinux execution or power-loss evidence. The connection-suite minimum rises from 418 to **430**.

The exact-APK native suite grows to **14 cases**, adding direct publication/collision/symlink checks under the enforcing Android sandbox while retaining the existing write/reopen/corruption case for both stores. Emulator parsing and publication both require at least 14 complete passes with no failed/skipped cases. A complete new same-source/run/attempt campaign is mandatory. Physical-device, 16 KiB runtime, hostile-provider/power-loss, whole-state rollback and independent-review gates remain open.

The four capacity-adapter regressions also passed again after the publication wiring changed. Local compilation/inference successfully covered the expanded instrumentation and produced **405** precise boundary headers; it assembled no APK and does not replace either Android native execution gate.
