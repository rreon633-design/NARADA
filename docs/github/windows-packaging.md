# Windows packaging: exact payload provenance

## Ninth-campaign native validation

Run [41312970060](https://github.com/rreon633-design/NARADA/actions/runs/41312970060), source `18ff2ce6962ae600d050512249ae3448a04eb2b0`, passed the complete Windows gate. Its **7,270,718-byte (6.93 MiB)** installer exited zero; both installed payload hashes exactly matched their independently reproduced expected images, and the installed app/agent stayed alive for the required 15 seconds. The frontend was 382,485 bytes. This proves that campaign's installer/bootstrap check, not whole-app RAM/performance or the separate GUI/LAN/security gates. Android native storage failures prevented publication. Any subsequent source revision still needs its own same-run Windows pass.

## What the eighth campaign established

Run [41288851145](https://github.com/rreon633-design/NARADA/actions/runs/41288851145), source `ed98c9f43bece4c1493e2827f7ded0a61fd791ba`, passed native Rust tests (138 executed, including 52 filesystem cases and the 24-cycle native watcher regression), strict Clippy and the desktop build. Its NSIS installer measured **7,268,270 bytes (6.93 MiB)**, below the 30 MiB limit. The frontend measured 382,485 bytes.

Installation exited zero. The installed agent matched its compiler output exactly. The desktop executable had the expected 7,814,144-byte length but a different SHA-256. The strict check failed before desktop/agent startup was exercised. This remains a failed campaign, not retroactive installer qualification.

## Reviewed Tauri transformation

The build log explicitly recorded Tauri patching the desktop executable with NSIS bundle-type information. The pinned **Tauri CLI 2.12.4** implementation:

1. Saves the unmodified compiler executable.
2. Changes the first `__TAURI_BUNDLE_TYPE_VAR_UNK` marker to `__TAURI_BUNDLE_TYPE_VAR_NSS` before NSIS packaging.
3. Builds the installer with that patched image.
4. Restores the original compiler executable afterward.

Source: https://github.com/tauri-apps/tauri/blob/tauri-cli-v2.12.4/crates/tauri-bundler/src/bundle.rs

Comparing the restored compiler file directly with the installed NSIS payload therefore uses the wrong reference image. Equal lengths or an installer exit code alone must never be accepted as a substitute for full-byte verification.

## Corrected audit, first natively validated in the ninth campaign

The collector independently reproduces only the reviewed marker transformation into a separate audit file. It requires the pinned CLI version, exactly one original marker, unchanged executable length and an x64 PE image. It records both compiler-input and expected packaged SHA-256 values, the marker offset and the three changed bytes. The compiler output itself is not modified.

The smoke check binds those receipts to the current commit, rechecks the compiler/audit/agent files, and still requires exact installed application and agent hashes. Only the installed application is launched. The 15-second application/agent bootstrap check remains mandatory. Publication additionally binds the smoke receipt to the same compiler input and transformation as the build report.

No arbitrary binary difference, signing change, missing/duplicate application, path fallback, uninstalled executable or suppressed startup failure is accepted. The normal Tauri bundle metadata remains enabled. Python and synthetic PowerShell checks are not Windows installation/runtime evidence; a fresh same-run/source/attempt campaign must prove the reproduced image against the actual installer.

This is an unsigned development candidate, not production signing, a full tray/LAN/UI test or a performance qualification. The system WebView2 runtime is not included in the installer; only its small bootstrapper is embedded.
