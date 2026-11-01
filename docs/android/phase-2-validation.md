# Android Phase 2 — measured validation

[Handoff](../../ANDROID-PHASE2-HANDOFF.md) · [Native checklist](phase-2-device-checklist.md) · [Historical Phase 1](validation.md)

**31 October 2026: 186 current host/interop checks passed**, zero failures/errors/skips in final test stages. This includes the retained 82 foundation checks and 104 new connection/persistence/UI/interop checks. No physical device, Android emulator, native Windows or remote CI run is claimed.

## Final stage records

| Record | Observed result | Elapsed |
|---|---|---|
| `domain-03` | 22 passed; 0 failures/errors/skips | 12.636 s |
| `connection-03` | 59 passed; 0 failures/errors/skips | 23.238 s |
| `interop-03` | 10 passed; 0 failures/errors/skips | 15.721 s |
| `host-03` | 95 passed; 0 failures/errors/skips | 110.735 s |
| `lint-03` | 0 errors/warnings; 36 hints | 125.862 s |
| `apk-02` | Build passed; not device-executed | 20.608 s |
| `instrumentation-02` | Build passed; not device-executed | 17.028 s |

[Machine-readable summary](../../artifacts/android-phase-2/validation-summary.json) · [Frozen input hashes](../../artifacts/android-phase-2/validated-input-hashes.json) · [Raw final/earlier records](../../artifacts/android-phase-2/runs/)

All seven final stages match **108 runtime/test/build inputs**. The validator creates new record directories and forces actual test execution; absent/zero test output is not accepted. Final commands do not write locks/checksums or disable verification.

## What actually ran

- **22 domain tests:** retained foundation policies/validation/redaction.
- **59 connection tests:** 26 strict protocol/certificate/cipher tests, 18 state/epoch/ACK/capacity tests, 15 real JSSE TLS boundary tests. TLS checks include wrong pin/SAN, TLS 1.2 rejection, redirects, malformed HTTP framing, excessive length, duplicate JSON and an absolute slow-response deadline.
- **95 Android host tests:** 60 retained tests plus 16 protected-repository, 4 QR, 9 coordinator, 4 connection UI and 2 additional Room migration/anchor tests. Room/DataStore/Compose run under Robolectric API 35 on JDK 17, not Android hardware. API 36 Robolectric requires JDK 21 and was not claimed.
- **10 separate real-agent cases:** production Kotlin protocol/TLS against the current corrected Rust agent: owner/proof/phone confirmation, wrong certificate pin, wrong SPKI, consumed bootstrap, premature ACK, no client certificate, wrong phone identity, lost final reply plus desktop restart/idempotent recovery, changed grants plus desktop revocation, persisted local tombstone/reload.
- APK and instrumentation compilation, manifest/signature/ZIP/ELF checks. Instrumentation includes three native tests but was **not executed**.

The interop process uses Linux loopback, a fresh development vault/profile, JSSE and test-only software client keys. It is not phone/Conscrypt/Windows/DPAPI/firewall qualification. Fake-peer coordinator/state tests are distinct from these real TLS/agent tests.

## APK and supply-chain audit

- `com.narada.mobile.dev`, 0.2.0 (2), min 29 / target 36, Room 3; development/debuggable, not release-signed.
- Main APK 38,569,644 bytes; instrumentation 2,217,410 bytes. Signed APK bytes are delivered unchanged inside a smaller lossless outer ZIP.
- Matching v2 signatures; original Phase 1 debug certificate preserved: `4867bd2ab95831d130e9d23c21942cca04ad60863f698c9a953f041fc6952c72`.
- Permissions: INTERNET, ACCESS_NETWORK_STATE, CAMERA, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, WAKE_LOCK and the app's non-exported dynamic-receiver permission. No foreground-service or broad-storage permission. Camera is optional/contextual.
- Only intended launcher/system-permission-guarded components exported; scanner/FileProvider private; no runtime PreviewActivity; FileProvider only `cacheDir/support`.
- Backup and cleartext disabled. 16 native ELF files inspected; 64-bit LOAD alignment and APK ZIP alignment satisfy the static 16 KB checks. This is not 16 KB device execution.
- APK DEX inspection excludes Bouncy Castle and software test identity generators.
- 710 checksum-recorded dependency components; exact catalog/locks. TOFU is not an independent signature/advisory/license review. Lint's 36 update advisories remain visible; no baseline suppresses app findings.

[APK audit outputs](../../artifacts/android-phase-2/apk-audit-02/) contain the actual manifest, signer output, DEX package inventory and alignment output. [Host renders](../../artifacts/android-phase-2/runs/host-03/host-renders/) are real View/Canvas captures; pairing fixtures are visibly labeled, not live devices.

## Failures and corrections retained

- First preparation compile: unavailable Android `OsConstants.O_DIRECTORY`; corrected to public `O_RDONLY`. An earlier relative-path ViewModel patch failed before its corrected absolute-path write. Original log retained.
- Preparation resolves used explicit lock/checksum-writing flags; later normal builds verified those inputs without those flags.
- Security review added storage-error latching, normalized local-store parse failures, post-key/handshake/health epoch checks, socket-close generation synchronization, scoped socket ownership, capture expiry and NSD stop/route fences; these changes were compiled and included in final checks.
- `lint-01`: **2 errors, 0 warnings, 36 hints** (stale removed PreviewActivity manifest entry and lowercase test Composable). Source was fixed without a baseline; `lint-02` passed. Earlier records remain unchanged.
- A final review found that a failed revoke could briefly display a completed-revocation status while closing its socket. It now displays a neutral closing status until persistence/refresh succeed. A ninth coordinator regression test proves that a failed write never claims durable revocation; all seven stages were rerun as the final records above. The earlier audit/receipts remain separate (`validation-summary-01.json`, `apk-audit/`); its unshipped APK bundle was retained in excluded working cache, not passed off as the final artifact.
- Preparation transcripts and verbose DEX inventory were losslessly gzip-compressed after execution; original-byte hashes and compression receipts are preserved. Final stage logs and XML remain directly readable.
- Earlier Phase 2 host/core/interop passes are preparation/earlier-source evidence, not double-counted in the 186 final total.

## History is not rewritten

The corrected Rust agent was newly built with verified Rust 1.99.1 (`--locked -p narada-agent --features dev-preview`) for this Android campaign. This proves the build and these connection cases only. The historical desktop 342-pass/6-failure single-pass suite was **not rerun** or relabeled. Phase 1's separate 82-pass artifact record and handoff remain unchanged.

Historical APK bytes were losslessly wrapped into `NARADA_Android_Phase1_APKs.zip` to stay within workspace snapshot limits; both old SHA-256 values were checked. Synthetic old desktop runtime files were privately archived, not shipped in the source bundle. [Preservation receipt](../../artifacts/android-phase-2/preparation/snapshot-budget.json) records the reversible relocation. All original desktop/source archives and five supplied reference assets remain unchanged.

## Open gates

Actual AndroidKeyStore/Conscrypt mTLS, camera/NSD/selected-network behavior, Windows private-interface/firewall interoperability, real install-over/kill/reboot/Doze/fsync/backup/OEM/provider/TalkBack/16 KB/resource measurements, independent security/dependency review and release signing remain [explicitly unchecked](phase-2-device-checklist.md). Build/test-source presence is not execution evidence.
