# Windows native filesystem correction

The fresh Windows campaign at `d4ee9828ef65215dcf1c4251e853ad365463f8a0` exposed OS error **87 (`ERROR_INVALID_PARAMETER`)** at the Win32 handle-relative rename call. The files library reported 34 passes/12 failures, in addition to the two connection attachment-export failures. These results remain failures.

The correction uses `NtSetInformationFile(FileRenameInformation)` with `FILE_RENAME_INFORMATION.RootDirectory` set to the already-pinned destination handle. Directory handles also request traversal access. No absolute-path, replace-existing, symlink-following, destructive retry, broader file-sharing or software-key fallback is introduced. `ReplaceIfExists` remains false. The existing identity/version/volume/authority checks and unknown-outcome recovery remain enabled.

A new Windows-only regression calls the native rename primitive directly: an existing destination must survive unchanged, then a cross-directory rename to a fresh name must preserve source identity and bytes. This complements the existing higher-level collision/transfer/export tests. Execution and success must be established by subsequent GitHub runs; adding a test is not a passing result.

References:
- https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/ntifs/nf-ntifs-ntsetinformationfile
- https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/ntifs/ns-ntifs-_file_rename_information
- https://docs.rs/windows-sys/0.59.0/windows_sys/Wdk/Storage/FileSystem/fn.NtSetInformationFile.html

This is development native-regression work, not an independent filesystem/security/power-loss qualification.

The next native run (`41281460193`) advanced past the rename failures, including the direct collision regression. It then failed one fixture teardown with Windows sharing error 32. Inspection found that `Files::shutdown` aborted an async indexing waiter, which cannot cancel an already-running `spawn_blocking` filesystem scan. Shutdown now wakes the idle worker and joins an active scan cooperatively before closing storage. A deterministic pinned-handle regression asserts that shutdown cannot return before that blocking work finishes. No cleanup failure is ignored and file-sharing protections remain unchanged. This additional correction still requires a fresh successful native campaign.
