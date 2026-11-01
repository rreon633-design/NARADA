# NARADA — Problems, Risks and Solutions

> [Documentation index](../README.md) · Diagnosis and recovery playbook · 30 October 2026

## 1. Product and engineering risk register

| Problem / symptom | Likely cause or check | Safe solution / prevention |
|---|---|---|
| Same Wi-Fi, peer not found | Multicast blocked, wrong interface or guest isolation | Try paired manual endpoint; explain that isolation may still block direct reachability. |
| Endpoint reachable but untrusted | Wrong device, changed key or malicious advertisement | Stop; verify identity/re-pair explicitly. Never disable TLS validation. |
| Windows port blocked | Narrow firewall rule missing, wrong profile or listener not started | Inspect listener/profile and add only the needed application/interface rule. Never disable the firewall globally. |
| Local Wi-Fi has no internet | OS routes traffic through mobile data or treats Wi-Fi as unvalidated | Select/bind the local peer network where needed; do not require internet validation for LAN traffic. |
| Laptop sleeps or user logs out | Agent/network not available | Keep jobs durable and reconnect later; explain the per-user availability model. |
| Android stops background work | OS/OEM policy, quota, timeout or user force-stop | Use correct native API, checkpoint work and resume when eligible; user reopen may be necessary. |
| Pairing QR will not scan | Prototype pattern, missing bundled decoder, camera denied or expired invite | Use actual QR encoding/decoder, request camera contextually, regenerate a valid invitation. |
| Pairing succeeds twice | Token claim not atomic or retry creates new records | Single-use transaction, attempt binding and idempotent finalization. |
| Old session works after revoke | Cached grants or long-lived stream never rechecked | Epoch invalidation, close sessions, chunk/commit reauthorization. |
| File listing leaks another root | Query/event path lacks permission filtering | Apply scope inside domain repositories for listing, search, preview and events. |
| Root escape or wrong file access | String-prefix containment, junctions or path races | Reviewed handle-based resolver; strict unsupported-path policy and adversarial tests. |
| Large file exhausts RAM | Whole-file buffering, base64 blobs or unbounded chunk queue | Binary streaming, bounded buffers and paginated manifests. |
| Source changes during transfer | Resume relies on path/mtime rather than immutable bytes | Stable snapshot/version; reject changed/expired manifest and start a new transfer. |
| Completed file is corrupt | Completion announced before verification or flush | Chunk checks, final hash, durable publish and recovery intent. |
| Duplicate file after retry | Lost response and missing idempotency result | Stable operation key, request digest and queryable committed result. |
| File disappears during replacement | In-place write or unsafe overwrite policy | Stage separately, preserve old version and perform qualified safe publication. |
| Partial file appears in SAF destination | Provider lacks atomic writes/random access | Private verified staging, separate export status and explicit partial cleanup. |
| URI no longer readable | Grant revoked, provider disappeared or app failed to persist offered permission | Pause; ask for re-selection; do not request broad storage access. |
| Disk fills with snapshots/attachments | No quota, reference tracking or expiry | Reserve/check space; bounded retention; never evict unsent/conflict/pinned content silently. |
| Sync misses edits | Watcher overflow, crash, stale index or cursor expiry | Reconciliation scan, durable journal, stable snapshot and replay fence. |
| Sync destroys local edit | Timestamp-wins conflict policy or unconditional deletion mirror | Three-way/version comparison, preserve both, separate opt-in deletion policy. |
| Pinned content vanished | Stored in OS-evictable cache or sweeper ignored pin references | Persistent private pin store; explicit unpin/uninstall warning. |
| Message falsely says delivered | UI updated after socket write or a fake checkmark | Require authenticated durable recipient receipt. |
| Duplicate visible message | Retry treated as a new logical message | Stable ID, accepted-envelope digest and transactional deduplication. |
| Message cannot decrypt after restart | Ratchet state/outbox/inbox commits split | Single supported transaction boundary; tested adapter and key-loss recovery. |
| Attachment fails after resume | Re-encryption reused state/nonce or ciphertext changed | Transfer immutable ciphertext; restart encryption with fresh key when safe continuation is unavailable. |
| “Server cannot read” claim is misleading | Windows server is also a chat endpoint | Explain endpoint trust boundary; do not promise secrecy from the recipient process. |
| E2EE dependency integration fails | Platform binaries, unsupported APIs, license or maintenance mismatch | Resolve in Phase 0; select a reviewed alternative or explicitly ship transfer-only preview. |
| Database lock/corruption | Unbounded writers, power loss, disk errors or faulty migration | Bounded writer queues, durable transactions, backup/repair plan and no silent reset. |
| Old crypto state restored | Generic backup/rollback reactivates stale sessions | Separate content recovery from session identity; prohibit unsupported state rollback. |
| UI looks correct but actions do nothing | Source HTML uses toasts/static data | Replace fixtures with typed operations; test each P0 action end to end. |
| Mobile screen overlaps keyboard | Fixed-height HTML layout copied literally | Native Compose insets, IME handling and tested Back/navigation state. |
| “Local only” switch implies internet mode | Decorative settings copied without scope review | Display fixed policy; no public relay/port-forwarding capability in 1.0. |
| Phone claims to manage all devices | Screenshot catalogue confused with authorization | Limit to own pairing; Windows owner manages host grants and other peers. |
| UI freezes during scan/hash | Blocking work on UI/main event loop | Bounded background workers, lazy rendering and cancellation. |
| CPU/battery too high when idle | Continuous discovery, polling or keepalive loops | Lifecycle-scoped discovery, connectivity callbacks, budgeted work and backoff. |
| Windows/Android protocol drift | Independent schema edits | Versioned contracts, shared vectors and compatibility tests. |
| Update breaks DB or trust | Unsafely timed migration or binary downgrade | Quiesce jobs, verified migration, roll-forward preference and compatibility-aware rollback. |
| Name/signing/license problems at launch | Brand ownership or dependency rights assumed | Clear branding, distribution license and signing prerequisites before release. |

## 2. Prioritization

**Highest-impact release risks:** filesystem escape, key/session persistence failure, false delivery, silent overwrite, unsafe update and incorrect background/storage assumptions.

**High-likelihood usability risks:** discovery failure, denied notifications/camera, unavailable laptop, expired source snapshots, full storage and provider restrictions.

**Scope risks:** multi-peer UX, two-way sync, rich media, voice notes, dark mode, Wi-Fi Direct and remote access. Defer rather than dilute P0 reliability.

## 3. Troubleshooting order

1. Identify the intended peer and current operation ID.
2. Check whether both endpoint processes are actually available.
3. Separate discovery from direct network reachability.
4. Verify peer identity and protocol compatibility.
5. Check grants, root availability and source/destination version.
6. Check storage, URI grants and job state.
7. Inspect a redacted diagnostic bundle only after user consent.
8. Reproduce with a synthetic file and record a regression case.

Do not start troubleshooting by deleting app data, changing identity, disabling antivirus/firewall or clearing the only remaining copy of a file.

## 4. Risk ownership and escalation

Track each material risk with owner, probability/impact, mitigation, next experiment, target decision date and evidence. Escalate any confirmed authorization bypass, key exposure, silent data loss or false-delivery defect immediately to the release/security owner.

Related: [Prevention](20-prevention-precautions.md), [Decision gates](25-decisions-open-questions.md), [Release/support](24-release-support.md).
