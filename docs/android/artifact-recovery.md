# Artifact delivery, preservation and recovery

[Handoff](../../ANDROID-PHASE6-7-HANDOFF.md) · [Integrity/evidence](phase-6-7-validation.md)

## Current deliverables

- `NARADA_Android_Phase6-7_Source.tar.xz`: current source, split docs, actual peer source, build/test/audit/CI definitions and selected measured evidence. Extract first; open `apps/android` after preparing the pinned tools/JNI libraries.
- `NARADA_Android_Phase6-7_APKs.tar.xz`: **standalone current APK container**, not a delta. Extract the exact development and instrumentation APKs plus their README/member checksums. No private keys are included.
- `NARADA_Android_Phase6-7_SHA256SUMS.txt` and `NARADA_Android_Phase6-7_Package_Check.json`: integrity/package checks, not security/device/release approval.

Full historical preservation archives and the three original design-reference PNGs are retained separately to avoid duplicating them in every source bundle. The source bundle includes both unchanged HTML references, an original-asset hash/location manifest, all measured Android inputs and selected historical summaries. Original screenshots are optional design inputs, not missing runtime/build assets.

## Exact old APK recovery

To keep the workspace within its snapshot budget, the **older Phase4/Phase5 APK containers** are stored as byte-preserving deltas against the current standalone Phase6–7 container. Both the original signed APK bytes and their original tar.xz-container hashes were reconstructed and compared exactly; no APK was resigned or internally rewritten.

Keep these sibling files together:

```text
NARADA_Android_Phase6-7_APKs.tar.xz
NARADA_Android_Phase4_APKs_Recovery.json
NARADA_Android_Phase4_APKs_Recovery.bsdiff
NARADA_Android_Phase5_APKs_Recovery.json
NARADA_Android_Phase5_APKs_Recovery.bsdiff
NARADA_Recover_APKs.py
```

Run normal Python 3.11+ (not optimized `-O` mode), or use the same helper at `scripts/apk_bundle_delta.py`:

```bash
python NARADA_Recover_APKs.py restore \
  --base NARADA_Android_Phase6-7_APKs.tar.xz \
  --metadata NARADA_Android_Phase5_APKs_Recovery.json
# Use the Phase4 metadata filename to recover Phase4.
# Add --verify-only to check without writing.
```

Restoration uses the standard library only: it checks base/delta SHA-256 and sizes, applies bounded BSDIFF40 to exact tar bytes, verifies the restored tar and recreates/verifies the original XZ wrapper. It refuses to overwrite an existing output. If a platform's XZ encoder cannot reproduce the outer wrapper, `--uncompressed-tar` emits the separately hash-verified exact tar containing the original signed APK bytes instead. Linux Python 3.13.16 roundtrips were executed; Windows/macOS restoration was not executed.

Original Phase4 container SHA-256: `cd8015e52230da8d68d4e6bebe3ad4b183ce9cdf2608a266db8596756bee2c86`.

Original Phase5 container SHA-256: `4d5fbe13820293a3c9db03da55625b7a3d60965825b331b8bcb65705ec06a6fa`.

Delta generation used optional development-only `bsdiff4==1.2.6`; it is not bundled into the Android application or required for restoration. Hashes establish equality to the recorded bytes, not independent publisher/security approval.

## Historical evidence readers

In the **full workspace**, `scripts/android_phase67_history.py` resolves old log/XML/render members from exact compressed relocations/aliases and the separately retained original containers. It also resolves the pre-extension versions of seven live desktop paths. Example:

```bash
python3 scripts/android_phase67_history.py \
  artifacts/android-phase-6-7/runs/host-07/gradle.log \
  --output .cache/inspection/host-07-original.log
```

Some final render/log bytes may be aliased to the published source bundle in the workspace; the extracted source bundle itself contains the selected files directly. A partial source download is not the full historical archive set. The complete historical-byte audit requires the preserved workspace containers; ordinary current builds/tests do not. Historical results and source manifests are never relabeled as new successful campaigns.
