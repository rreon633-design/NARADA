# Phases 6 & 7 — tips, tricks and practical checks

[Workflow](phase-6-7-workflow.md) · [Attachments](attachments-recovery.md) · [Offline recovery](offline-recovery.md)

- Start with a small ordinary file and a new disposable profile. The development signer differs from Phase5; never erase valued trust/history just to install it.
- **Manifest first, bytes second:** explicitly sync Chat before sending attachment ciphertext. The disabled send button is intentional until manifest delivery is known.
- An attachment name is a display label, not authority to execute/open a file. Exports are inert octet-stream documents and require separate create-new consent.
- A pause/restart retains immutable ciphertext. Resume that job; do not generate a replacement message or reconstruct encryption state to hide an uncertain reply.
- Import ≤8 MiB. Ordinary offline files have a separate 64 MiB per-file bound and share four download slots/128 MiB with retained attachment reservations.
- **Metadata first, candidate second, adoption third:** refresh a root, pin/refresh an exact version, complete its download, then explicitly verify/adopt. Never infer availability from a fresh timestamp.
- Keep the current version while testing an update. Adoption retains one previous reference; release only after review. Unpin/release does not delete downloaded bytes.
- A tombstone is information about the source, not proof of lost private data. The old object is not automatically rebound to another object at the same path.
- Inspect an uncertain provider target outside NARADA before deciding what to do. Dismissal/forgetting is not external deletion, nonexistence or verification.
- Changed grants, endpoint or foreground can invalidate work. Reopen the protected records and explicitly review; do not bypass a scope/storage gate or disable dependency/TLS checks.
- For reproducibility, give every validation run a new name. Missing XML and killed JVMs are not a zero-failure success. On a small build host, keep stages sequential and use matching `--info` for precompile/test.
- Preserve the current APK tar.xz if you keep historical recovery deltas. Verify hashes and use the standard-library helper to reconstruct older containers; they do not depend on a private signing key.

Host screenshots and fixture values are labeled synthetic and are never injected into production activity. Native platform/security qualification remains [open](phase-6-7-device-checklist.md).
