# Phase 4 — unexecuted native qualification gates

**All boxes below are open.** Eight instrumentation cases were compiled, not run. [Measured host evidence](phase-4-validation.md) is not a substitute.

## Install, signer and identity

- [ ] Establish an owned development/release signer and custody/recovery procedure. The supplied Phase 4 signer differs from Phase 1–3; no same-signer update is claimed.
- [ ] Fresh disposable install on API29/33/35/36 and representative OEMs; permission flows and locked/unlocked keystore behavior. Test applicable Android16 LAN compatibility changes and target37/policy changes before adoption.
- [ ] With a controlled compatible signer, test actual Room/payload/key ownership upgrade paths, interrupted migration and downgrade refusal. Never clear valued app data to make a test pass.
- [ ] AndroidKeyStore P-256/AES nonexportability, invalidation, no fallback/reset, unavailable/locked key paths and backup/device-transfer exclusions. Hardware reporting is not attestation.

## Actual desktop transport

- [ ] Pair/approve/compare fingerprints and reconnect using Android Conscrypt + AndroidKeyStore keys against the Windows agent.
- [ ] Wi-Fi/Ethernet network binding, NSD/manual endpoint handling, route changes, cellular/VPN/public/loopback rejection, revoked grants/pins and healthy connection ownership.
- [ ] Sustained multi-chunk transfers plus native health restarts exercise 300 ms shared admission. Test contention with other clients; no auth retry/downgrade if peer admission is exhausted.
- [ ] File changes, permission withdrawal, peer restart/expiry and lost prepare/finish responses never replace the selected immutable source or invent a receipt.

## Storage and process/power loss

- [ ] Kill/background/force-stop at intent, prepare, each manifest page, chunk write, file fsync, hard-link, directory fsync, checkpoint, full verification and receipt boundaries.
- [ ] Restart pauses unfinished work; explicit resume revalidates saved encrypted bytes. No old run updates a new owner; cancel/pause joins before reuse.
- [ ] Corruption, missing fragment, wrong AAD/context, unexpected files/symlinks, real ENOSPC, quota pressure and unsupported fsync/link behavior never become verified progress.
- [ ] Fully staged offline verification and independent finish ACK behave correctly. Revocation cannot remotely erase a received copy.
- [ ] Four-record/128 MiB limits, explicit removal, no silent eviction and incomplete removal failures on real filesystems.

## SAF picker/provider matrix

- [ ] Local storage, supported cloud provider, removable storage and nonseekable/pipe descriptors; identify unsupported provider behaviors rather than bypass checks.
- [ ] True temporary OS read/write flags, revoked grants, create-capable directory, complete bounded snapshot, Unicode/case collisions, racing create, auto-renamed children and dishonest/virtual metadata.
- [ ] Activity recreation, result registry restoration, cancelled/duplicate/stale callback, actual STARTED delivery timing and process loss around picker/create.
- [ ] Create reply loss, write/close/readback failure, short/extra/changed bytes, ENOSPC and external observer seeing partial content produce truthful uncertainty.
- [ ] Noncooperative Binder calls, cancellation/descriptor closure, absolute deadlines and quarantined worker/helper. No thread explosion, automatic rewrite/delete or late verified result. Record remaining OS/provider uninterruptibility.
- [ ] Two-record limit; dismissal/forgetting is not cleanup or recall. New export requires new consent and a fresh document.

## UX, privacy and resources

- [ ] Physical 48 dp targets, TalkBack/focus order, 200% text, system insets/IME/landscape, large filenames and action visibility on real small screens.
- [ ] Main-window `FLAG_SECURE`, recents and screenshot/recording behavior on OEMs; acknowledge external provider windows/documents are outside it.
- [ ] No sensitive logs/support reports/notifications/backup data; review destination upload/retention/partial-file consent with users.
- [ ] Measure RAM/disk headroom/battery/thermal/throughput and background stop latency. Host timings are not phone benchmarks.
- [ ] Native 16 KiB-page loading/runtime and real API coverage; static APK/ELF alignment is only a prerequisite.

## Release gates

- [ ] Execute recorded native tests and actual CI against the intended artifact; review dependencies/licenses/advisories, perform fuzz/recovery and independent crypto/storage review.
- [ ] Complete Windows LAN/OEM/router/provider matrix, support ownership, data-loss recovery drills and accountable go/no-go approval.
- [ ] Keep Olm, age, automatic pins/sync and release claims separate (phases5–9).

On an authorized disposable connected device/emulator only:

```bash
cd apps/android
./gradlew :app:connectedDebugAndroidTest
```

That command was **not executed** for this handoff. The optional CI emulator job is source, not evidence of a run.
