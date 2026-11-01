# Android native/device qualification checklist

[Current validation](phase-3-validation.md) · [Phase 3 files matrix](phase-3-device-checklist.md) · [Phase 2 connection matrix](phase-2-device-checklist.md) · [Security/recovery](security-storage.md)

**Status: not executed in the implementation workspace.** There is no connected phone/emulator and no KVM. Compilation of instrumentation sources, Robolectric screenshots, lint and APK signature checks do not satisfy this checklist.

Use a disposable test installation/profile. Do not clear personal app data as a troubleshooting shortcut. Record API, OEM/model, build fingerprint in a controlled test record—not in the ordinary support export.

## Run the compiled smoke test (not yet executed)

Either build both APKs locally with the same controlled debug key and run `./gradlew :app:connectedDebugAndroidTest`, or install the supplied matching pair on a dedicated test device:

```bash
adb install -r NARADA_Android_Phase3_Debug.apk
adb install -t -r NARADA_Android_Phase3_Instrumentation.apk
adb shell am instrument -w com.narada.mobile.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Save the output, device/API details and both APK hashes in a **new** evidence record. The smoke source launches/recreates the real Activity, checks the explicit permission boundary, platform keys, bounded native JPEG and sealed atomic file-intent reopening; it does not replace this manual matrix. A locally rebuilt test APK with a different signing certificate is not the supplied matching pair. Do not uninstall/clear data as a supposedly lossless signer-mismatch repair.

## Install, setup and navigation

- [ ] Install the actual debug APK; record its SHA-256 and certificate fingerprint.
- [ ] Cold and warm launch on the declared API/device matrix; no fake loading delay, connected device or message.
- [ ] Complete onboarding with a Unicode label; close/reopen and rotate; committed setup survives.
- [ ] Five tabs and every More destination work. General tab scroll/back stacks survive supported restoration; sensitive file names/search/cursors/pixels intentionally do not.
- [ ] Back returns to the caller. An unsaved name guards Back/tab navigation; discard does not commit it.
- [ ] Real IME, edge-to-edge system bars, gesture/predictive Back, landscape and small screens remain usable.

## Lifecycle and storage

- [ ] Launching/recreating a screen never creates a new background job.
- [ ] Request a check, leave the Activity, kill the process, reopen and reboot the phone. Verify stable operation ID and no duplicate accepted work.
- [ ] Exercise queued/running cancellation and a late result; no cancelled/older generation overwrites the current record.
- [ ] Test scheduler/storage-low/background restriction, Doze and battery-saver behavior; publish observed limits without promising always-on execution.
- [ ] Verify explicit force-stop stops eligibility until user launch; do not claim work survives force-stop continuously.
- [ ] On a disposable profile, validate corrupt preferences/database, missing enrolled database and unsupported schema. Original bytes remain; no silent reset occurs.
- [ ] Exercise fsync interruption, disk-full and provider/service failures. Host SQLite tests do not prove physical power-loss behavior.

## SAF and exports

- [ ] Test the system document provider, removable storage where available, a cloud/virtual provider and a provider that refuses persistable access.
- [ ] Confirm Phase 1 retains read only, not offered write permission.
- [ ] Kill between pending metadata/OS permission/receipt steps; reconcile without an orphan or a fabricated granted state.
- [ ] Revoke access in Android/provider settings, remove the storage volume, go offline and choose the folder again.
- [ ] Retained consent is not presented as proof of actual document availability.
- [ ] Removing consent never deletes a source file.
- [ ] Review/share a support report with a separate test recipient app. Only the selected content URI is readable; no unrelated app file, raw database or writable grant is exposed.
- [ ] Exercise chooser cancellation, missing recipients and expired/cache-pruned reports.

## Notifications and accessibility

- [ ] No permission prompt on startup/onboarding. Enable notifications explicitly on API 33+.
- [ ] Deny, permanently deny, disable a channel and disable app notifications; app usage remains available.
- [ ] Check actual lock-screen/system tray behavior. Generic notification content must not reveal a name, path, message or key.
- [ ] Verify the immutable notification action opens local activity without executing a privileged command.
- [ ] TalkBack traversal, focus/order, all control labels and 48 dp targets on real hardware.
- [ ] 200% font and large display scaling on small screens; no inaccessible action or clipped critical text.
- [ ] Reduced motion and Android animation-scale settings actually affect transitions.

## Platform/release boundary

- [ ] Confirm cloud backup and device-to-device transfer exclusions on supported OEM tools.
- [ ] Install/upgrade/uninstall using the intended signing strategy; a different debug certificate is not a valid update path.
- [ ] Check both APK ZIP and native ELF/page-size compatibility, then execute on 16 KB devices/emulators where applicable.
- [ ] Measure cold start, memory, background CPU/wakeups, APK size and thermal/battery behavior; no invented benchmark values.
- [ ] Execute native instrumentation and preserve reports against the exact APK/source revision.

Phase 2 LAN discovery, QR pairing, mTLS and Windows interoperability have a separate unexecuted native matrix linked above. Transfers, E2EE and sync remain later phases. None is qualified by foundation host tests.
