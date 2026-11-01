# Phases 6 & 7 — workflow, process and end-to-end use

[Features: attachments](phase-6.md) · [Features: offline](phase-7.md) · [Tips](phase-6-7-tips.md) · [Precautions](attachments-recovery.md) · [Evidence](phase-6-7-validation.md)

## Evaluate on a new disposable profile

The supplied debug APK is not a qualified upgrade from prior APKs and uses a different signer from Phase 5. Extract the APK tar.xz first; verify its checksum and the two APK member hashes. Do not uninstall/clear valued data to bypass signature or storage errors. Device/emulator execution has not been performed in this handoff.

1. Enable the explicit outbound connection; use the allowed private IPv4 Wi-Fi/Ethernet network.
2. Scan/enter the pinned invitation, approve on the desktop and compare fingerprints. Wait for durable ACK and authenticated health, not merely a socket.
3. Grant only the required Chat/Attachment or root Read + SyncRead capabilities. Pending pairing blocks content.
4. Treat AndroidKeyStore/Conscrypt, actual SAF providers, Windows LAN and accessibility behavior as qualification work, not pre-approved behavior.

## Send an encrypted attachment

1. Chat → select an approved peer → explicitly enable protected private chat.
2. Optionally enter a caption (attachment captions ≤1,024 UTF-8 bytes).
3. Choose an attachment, read the consent and select one ordinary document ≤8 MiB. Only temporary read permission is used.
4. Wait for the local encrypted staging/receipt. An interrupted import is not resumable encryption.
5. Explicitly sync Chat so the protected typed manifest is committed on the desktop.
6. Review/send encrypted bytes. If paused or a reply is lost, explicitly resume the same immutable job and let status reconciliation determine missing chunks.
7. Distinguish local verification, chat delivery and remote byte-storage confirmation.

## Receive and export

Sync the protected chat first, explicitly retain/fetch the described ciphertext, and wait for full verification. For an external copy, review **Export an inert new document**, enter a valid fresh name, confirm and choose a provider folder. A private receipt is not an external export receipt. An uncertain target must be inspected/reviewed; never blindly repeat or delete it.

## Keep an offline file version

More → Offline copies → select the approved peer/root → Refresh metadata only. Confirm a pin for the exact listed file/version. Resume the candidate in Transfers, then return and explicitly verify/adopt it. A successful refresh/download observer never auto-adopts.

When a new source version appears, confirm refresh into a new candidate; current stays intact. Adopt only after verification. One previous version remains referenced. Release a reference/unpin only after review; the bytes remain in Transfers until separate explicit removal. A tombstone does not erase an offline copy.

## Reproduce a new development campaign

Prepare the pinned tools and both host/four-ABI libraries from [the project build guide](../../apps/android/README.md). Do not disable dependency verification. From repository root, use **fresh record names**, sequentially:

```bash
python3 scripts/validate_android_native67.py --record local-host-01
python3 scripts/build_android_phase67_peer.py --record local-peer-01
python3 scripts/validate_android_phase67.py domain --record local-domain-01
python3 scripts/validate_android_phase67.py connection --record local-core-01
python3 scripts/validate_android_phase67.py interop --record local-peer-tests-01 \
  --peer-receipt artifacts/android-phase-6-7/peer-builds/local-peer-01/receipt.json
python3 scripts/validate_android_phase67.py host --record local-app-01
python3 scripts/validate_android_phase67.py lint --record local-lint-01
python3 scripts/validate_android_phase67.py instrumentation --record local-instrumentation-01
python3 scripts/validate_android_phase67.py apk --record local-apk-01
```

The peer builder records the current Rust/Desktop-UI/config/release inputs and binary; it builds a disposable Linux **dev-vault** peer, never runs the historical desktop validator. Use the matching explicit receipt. Overrides `NARADA_AGENT_TARGET`/`NARADA_AGENT_BINARY` must agree if you change the default cache location.

On an approximately 2 GiB host, run compilation/tests/lint serially. The app runner precompiles at 384 MiB heap/384 MiB metaspace, then tests with a 160/224 MiB daemon and per-class 320/256 MiB workers. Both use `--info` to avoid a KSP log-level input change. Lint uses 512/256 MiB **after** the app has been compiled. Keep the memory guard; never count missing XML or an interrupted JVM as passing.

`audit_android_phase67.py` intentionally audits this handoff's named final records and full workspace preservation containers. A newly named campaign requires an explicitly reviewed record mapping, not overwriting old results. Ordinary source-bundle builds/tests do not require the full historical archive set; rerunning the complete historical-byte audit does.

The included CI definition covers both JNI adapters, current host/interoperability stages and an optional emulator job. **Remote CI was not executed.** Do not run/relabel the historical desktop single-pass campaign as part of Android validation.
