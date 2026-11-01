# Android APK audit: SDK fields and retained evidence

## Seventh fresh campaign

Run [41286741644](https://github.com/rreon633-design/NARADA/actions/runs/41286741644), source `706bc986d7a550f0555c088e25cfacc5864c89cd`, passed the Android host/JNI/real-peer/Robolectric/lint stages and the minified manual-test app/instrumentation build. Artifact collection then failed at its SDK-bound assertion. Emulator execution and publication were skipped. A successful build is not a qualified downloadable release.

## Reproduced tool-format mismatch

The original collector looked for the legacy AAPT label `sdkVersion:'29'`. The official Build Tools **35.0.0** Linux `aapt2`, inspected locally against an immutable historical APK, emits:

```text
minSdkVersion:'29'
targetSdkVersion:'36'
```

The tool archive was checked against the Android SDK repository's published SHA-1 checksum; a SHA-256 receipt and the full observed badging output were also retained locally. This historical APK was only a tool-format sample, not a new candidate or a rerun of historical tests.

The parser now accepts either explicitly named minimum-SDK field, requires exactly one minimum and target, and still requires **minimum 29 / target 36**. Missing, duplicate, conflicting, nonnumeric and wrong values fail. Independently decoded manifest `uses-sdk` attributes must match those exact values as well. No SDK target, Android version code, shrinker, signing or validation gate was lowered.

## Evidence and remaining gates

- Raw badging is saved before its assertions; the decoded manifest is saved before manifest assertions.
- Successful complete collection copies the build report into the always-uploaded evidence directory.
- A short public CI notice contains candidate filenames, measured bytes and SHA-256 values only. It explicitly does not claim native runtime qualification.
- Per-ABI isolation, signature, ZIP/ELF alignment, native-source/strip provenance, JNI exports, hardening, notices, R8 mapping, and **20 MiB per APK** checks remain mandatory.
- The exact collected x86_64 APK must execute the required instrumentation on both API 29 and 35. Windows installation/bootstrap and all other same-run/source/attempt gates must also succeed before `v0.0.1` can be published.

SDK repository: https://dl.google.com/android/repository/repository2-3.xml

Inspected tool archive: https://dl.google.com/android/repository/build-tools_r35_linux.zip

The eighth campaign subsequently passed all four APK audits. Its exact-APK emulator attempts then exposed a separate cross-APK R8 entry-point issue; see [instrumentation boundary and qualification](android-instrumentation.md). Artifact-audit success remains distinct from native test success.
