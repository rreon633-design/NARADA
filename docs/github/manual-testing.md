# NARADA — manual-test installation

These downloads are **development candidates**, not a production/security-qualified release. Use a disposable Windows account/VM and Android test profile, with synthetic files and messages. Never expose valued folders, credentials or confidential content.

## Pick the right download

| Platform | Download | Notes |
|---|---|---|
| Windows 10/11, x64 | `NARADA-…-windows-x64-setup.exe` | Native Tauri application and agent. No Electron or bundled Chromium. |
| Most modern Android phones | `NARADA-…-android-arm64-v8a.apk` | Android 10 / API 29 or newer. Confirm your device ABI. |
| Older 32-bit ARM Android | `NARADA-…-android-armeabi-v7a.apk` | Only if this is your device's supported ABI. |
| x86_64 emulator/device | `NARADA-…-android-x86_64.apk` | This exact candidate is used by the required hosted emulator checks. |
| Older x86 device | `NARADA-…-android-x86.apk` | Build/static checks; no native x86 execution claimed. |

Install **one** APK, not all four. With Android platform tools, `adb shell getprop ro.product.cpu.abi` reports the primary ABI. Do not choose solely from a phone's marketing name.

## Check the files

Download `SHA256SUMS.txt` from the release (or the platform-specific checksum file from a workflow artifact). Compare the SHA-256 for the exact filename before installation:

- PowerShell: `Get-FileHash .\NARADA-…-windows-x64-setup.exe -Algorithm SHA256`
- Linux: `sha256sum NARADA-…-android-arm64-v8a.apk`
- macOS: `shasum -a 256 NARADA-…-android-arm64-v8a.apk`

The accompanying build reports record the source commit, workflow run, byte sizes, native provenance and Android certificate fingerprint. Hashes detect changes relative to the report; they are **not** production publisher authentication or an independent security review.

## Windows

1. Use a clean test account or VM. Run the x64 NSIS installer from the trusted repository's release.
2. The installer is **unsigned**. Windows may warn or block it. Do not disable global security controls; follow your organization's policy and review the source/reports before proceeding.
3. The small installer embeds only Microsoft's bootstrapper, not Chromium or the WebView runtime. It uses the system **Evergreen WebView2** runtime. If that runtime is missing, the embedded bootstrapper needs Internet access to obtain it. An offline installation requires WebView2 already installed. The runtime's download/disk cost is separate from the NARADA installer size.
4. Launch NARADA and first test navigation, settings, diagnostics, window scaling, close-to-tray and clean exit. The hosted bootstrap smoke does not prove rendered pixels or tray behavior.
5. Grant access only to a new, disposable test folder. Do not broadly relax the firewall or expose the listener on public networks to make pairing work.

## Android and signing

- The manual-test APK is **R8-minified, resource-shrunk and non-debuggable**. It is signed using a temporary CI runner debug certificate solely to make installation possible. This is **not production signing**.
- App ID: **`com.narada.mobile.dev.ci`**. Launcher: **NARADA (Test)**. It is separate from the earlier `com.narada.mobile.dev` app and does not replace its data.
- A new CI run can create a different signing certificate. These candidates do **not** promise in-place upgrades, even for identical version numbers. Keep each run's fingerprint/report. Never delete valued app state or bypass a signer/storage-continuity failure to force an update.
- Sideload only the matching APK into the disposable test profile. If Android requires an installation-source permission, grant it only for the trusted installer flow and revoke it afterward. Do not globally disable scanning/protection.
- An **unsigned** APK cannot be installed normally. The ordinary unsigned `release` variant remains available for owner-controlled signing/qualification; the SSH GitHub deploy key is never an application signing key.

## Version policy

`v0.0.1` is the **first GitHub development release tag**. Existing internal versions remain Android/desktop shell `0.7.0`, Android versionCode `7`, and Rust workspace `0.7.1`. Protocol, trust-store and database schema versions are not reset. The separate `.ci` identity avoids claiming a downgrade or qualified upgrade of the earlier app.

## Suggested end-to-end test

1. Put the disposable Windows/Android test devices on the same trusted private IPv4 Wi-Fi/Ethernet network. Android remains outbound-only; do not use public, VPN, cellular or loopback endpoints to circumvent policy.
2. Explicitly start pairing, inspect the identity/pin and approve on the desktop. Verify that health/ACK completion is needed before Connected.
3. Test grant/revoke and rejected actions before granting file permissions. Try a harmless browse, rename, collision and reversible trash operation in the disposable root.
4. Transfer a small synthetic file. Pause, restart, and verify integrity; explicitly export to a new SAF destination. Do not select an existing valuable file.
5. Test a synthetic chat and an attachment below the 8 MiB plaintext limit. Disconnect/reconnect and observe retry/unknown-outcome handling.
6. Test offline freshness separately from pinned verified bytes, and verify that changes/deletions do not silently destroy retained copies.
7. Check dark/light mode, large text, screen insets, keyboard, accessibility, background/foreground transitions and screenshot protection on real devices.

## Report a failure safely

Include the release tag, source commit, artifact hash, Android ABI/API or Windows version, and exact reproduction steps using synthetic data. Do **not** attach private keys, app databases, unredacted invitations, decrypted messages, personal paths, or full private device bugreports. Redact diagnostic logs before sharing.

## Remaining gates

Hosted emulators are not physical-device/hardware-backed-key proof. Whole-state rollback, hostile provider stoppability, real power-loss/ENOSPC behavior, Android 16 KiB-page **runtime**, Windows LAN/firewall/tray behavior, independent security/license review, production signing, upgrade continuity and real-hardware performance remain separate gates. Passing this workflow does not mark all roadmap phases production-complete.
