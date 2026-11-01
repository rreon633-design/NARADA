# NARADA — Desktop release and support tooling

> Source 0.7.0 · **Development only, unsigned, not release-qualified**

## Current state

Release is blocked: the one host pass has **342 passed / 6 failed**, ten source files subsequently changed without rebuild/retest, and all eight external approval rows remain pending. The existing gate deliberately does not treat corrections, negative-gate tests or package checksums as successful validation. [Actual results](desktop-5-9-validation.md).

## Evidence gate

`release/qualification.json` intentionally contains pending approvals. Run:

```sh
python3 scripts/release/check_release.py
```

A blocked gate exits nonzero. `--expect-blocked` is only a test that missing evidence is refused; a successful negative check does **not** mean release readiness. Required approvals include native Windows/MSVC installer, DPAPI/IPC/ACL, filesystem/power-loss, physical LAN/lifecycle, accessibility, independent crypto/security review, dependency/license review and pilot/product go/no-go.

Approved evidence must identify a real reviewer and an existing, hashed file under `qualification-evidence/`. Host validation must be passing and still bound to the exact tested source. Approval packets are separate from executable-source binding; changing code, tests or signing scripts is not an approval update. A future authorized validation must preserve this failed record and explicitly wire the gate to a new evidence record, not edit old results. The tool verifies structure and hashes, not reviewer authenticity or the substantive quality of an audit. Genuine accountable approval remains mandatory.

## Build and signing, only on the qualified Windows host

1. Review current dependencies, source hashes, storage/protocol compatibility and license policy.
2. Complete the native matrix on the exact source and candidate artifacts.
3. Supply the approved certificate from the current user's Windows certificate store and an approved RFC3161 timestamp URL.
4. Run `scripts/release/build-signed-windows.ps1` with those public identifiers. It refuses pending gates or development vault/preview variables; it builds the default-feature sidecar, signs it, configures native Tauri signing and checks resulting Authenticode signatures.
5. Bind final artifact hashes and install/upgrade/uninstall results to the release evidence. No signing key, signature, owner, installer execution or pilot is fabricated by this handoff.

The existing NSIS configuration is current-user. Automatic startup, physical lifecycle/ACL behavior and native browser-download behavior need execution evidence. No automatic public distribution/upload is performed.

## Optional offline update verification

`scripts/release/verify_update.py` verifies an explicitly provided Ed25519-signed manifest against an independently pinned public-key digest, rejects version replay/downgrade, checks platform/channel/schema, filename, length and full artifact SHA-256. It **does not download or execute** an installer.

The trusted key/hash cannot come from the untrusted manifest being verified. Windows Authenticode publisher verification and reviewed migration/backup procedure remain required. The application has no silently enabled network updater or telemetry service.

## Inventory and provenance

`python3 scripts/export_inventory.py` generates CycloneDX source-lockfile inventory, paginated declared-license notes, available upstream license text copies and lockfile digests under `artifacts/desktop-5-9/`. It includes development/target dependencies and is not automatically an exact shipped-installer SBOM. Unknown declarations and upstream pre-1.0 warnings are not legal/security approvals.

The generated inventory contains 810 lockfile components, 1,072 license-text copies and four unresolved declarations. [Observed resource/supply-chain results](desktop-5-9-resources.md).

A final source archive has its own manifest/checksum, excluding profiles, wrapping keys, caches, toolchains, binaries and dependency directories. `scripts/package_desktop_source.py` checks this handoff against the exact historical Phase 4 archive; reproducing that packaging check requires the earlier archive, not just this new source tree. Package integrity is separate from runtime testing, native qualification, signing and review.

## Support/pilot checklist

- Assign support and incident owners and a real approved contact channel.
- Define pilot consent, supported desktop matrix, stop conditions and rollback/roll-forward limits.
- Rehearse private-content/key-loss recovery without resetting ratchets.
- Use the allowlisted support summary; do not collect private stores or crash dumps by default.
- Keep a release-stopping process for false delivery, silent replacement/deletion, auth bypass, key loss and unknown publication outcomes.
- Obtain a signed go/no-go decision after observing the actual pilot. The source package itself is not a completed pilot.
