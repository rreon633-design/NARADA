# Lightweight desktop and Android policy

**Targets are enforced build-time guardrails, not measured results until CI runs.** Runtime memory, startup time and battery performance still need measurement on representative user hardware.

| Output | Guardrail | How |
|---|---:|---|
| Each Android APK | 20 MiB maximum | R8, resource shrinking, one ABI per APK, no universal APK |
| Windows x64 NSIS installer | 30 MiB maximum | Tauri/system WebView2, size-optimized Rust, stripped symbols, thin LTO, LZMA installer |
| Built desktop frontend | 1.5 MiB maximum | Vite production minification/tree-shaking, no release source maps |

Thresholds live in `release/ci-policy.json`. Exceeding one fails candidate collection and prevents release publication. Build reports contain actual byte counts and SHA-256 values; the release notes are generated from those actual reports, not estimates.

## Windows

The application remains native Rust + Tauri. It does not switch to Electron or bundle Chromium. Existing WebView2 is reused; if missing, the installer downloads Microsoft's bootstrapper. This avoids a fixed browser runtime in every NARADA download but does **not** eliminate WebView2's separate disk/RAM/download cost.

The workspace release profile uses `opt-level = "s"`, thin LTO, one codegen unit and stripped symbols. Unwinding is retained to avoid changing existing failure boundaries. The packaged agent is explicitly built without default/development features, and its help surface is checked for accidental development-HTTP exposure.

## Android

The `manualTest` build type inherits release minification/shrinking. It is non-debuggable, uses a separate `.ci` identity and a temporary runner debug signing certificate. `-PnaradaAbiSplits=true` creates four separate APKs; the user installs only the appropriate one. JNI libraries remain source-bound and statically checked for architecture and 64-bit 16 KiB alignment.

The age/Olm adapters already use size-oriented Rust release profiles. Their exact JNI keep rules and bundled license notices are retained. Encryption, integrity checks, storage continuity, revocation fences, quotas, replay handling and explicit export consent are **not removed** to achieve a smaller APK.

## Measurements still needed

- Cold/warm startup and responsiveness on low/mid-range Android devices and Windows PCs.
- Whole-process-tree Windows memory, not just one application's working-set sample.
- Idle/background CPU, Android battery consumption, large-text/accessibility behavior and transfer/attachment memory peaks.
- Installed size including OS/runtime dependencies, rather than download size alone.

The 15-second hosted Windows bootstrap emits a limited application working-set sample for diagnosis. It explicitly excludes WebView/agent processes and must not be presented as total RAM use or proof of a performance budget.
