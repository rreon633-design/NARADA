# Phase 4 — verified transfers: scope and tasks

[Handoff](../../ANDROID-PHASE4-HANDOFF.md) · [Plan](../../android-plan.md) · [Validation](phase-4-validation.md)

## Feature boundary

One foreground-only desktop-to-phone ordinary-file slice. Desktop-granted Read authority, current capability/version and the selected source version are required. Files larger than 64 MiB and unsupported object types cannot be queued. A display path is never a phone path or URI.

The engine retains **four records / 128 MiB aggregate**, **64 MiB per file**, **256 KiB per chunk / 256 chunks**. Retained records consume capacity; the user explicitly removes unwanted copies/records. There is no silent eviction of a verified copy or unresolved outcome. Two export records are retained; a third is rejected until a terminal record is explicitly reviewed and forgotten.

## Delivered work packages

| ID | Implemented work | Evidence / boundary |
|---|---|---|
| AND-401 | Exact wire parsing, tuple manifest digest, bounded binary transport and capabilities | Core protocol/transport tests and actual Rust peer |
| AND-402 | Intent-before-prepare, immutable UUID/source/version, durable run ownership | Core/client/engine tests; no automatic replacement snapshot |
| AND-403 | AES-GCM encrypted immutable chunks, no-replace install and checkpoint ordering | Linux file/fault tests; Android implementation compiled, device fsync/power-loss unqualified |
| AND-404 | Queue, pause, cancel, explicit resume, restart recovery, local staged verification | Engine/coordinator tests and host UI; no WorkManager transfer/service |
| AND-405 | Independent private receipt and finish ACK | Exact-byte actual-peer tests; lost ACK cannot erase local success |
| AND-406 | Explicit create-new SAF consent, verified source, target identity, no-truncate write, independent readback | Core export and native-adapter host tests; real provider grants/cloud/Binder still gated |
| AND-407 | Single-use picker ownership, durable uncertainty, bounded provider worker/deadline | Host registry/worker/coordinator tests; no hostile-provider stoppability promise |
| AND-408 | Native Transfers and file download reviews, secure main window, 48 dp controls and 200% text fixtures | Compose/host renders, not device/OEM security/accessibility qualification |
| AND-409 | Final campaigns, APK audit, preserved history, CI source and split handoff | 479 passes; 8 instrumentation cases compiled, 0 executed; CI source not run remotely |

## Success metrics at this handoff

- 479/479 final checks passed, with zero failures/errors/skips; 182 more cases than the Phase 3 campaign.
- All seven final stages match 157 runtime/test/build inputs. A filtered run or matching file count alone is insufficient.
- Exact immutable bytes, empty/Unicode files, 34-chunk pagination/partial tail, resume, stale/expired sources, revoked access, corrupt saved chunks and lost replies have executable evidence.
- No production progress is derived from desktop `verified_bytes`; only rechecked, durably stored phone chunks count.
- No source-to-target publication occurs before full private verification; no overwrite/delete API is used by export.
- 1,774 frozen historical files remain byte-identical, including 49 losslessly relocated log/HTML files. Prior failures are not rewritten.

These are **development correctness metrics**, not throughput/battery benchmarks, device durability guarantees, release/security approval or a claim of physical cancellation of a provider.

## UI evidence

The supplied [mobile screenshot](../../references/android-screens-reference.png) and [HTML](../../references/original-android-prototype.html) guide the native UI. Production rows are actual records; demonstrations below are explicitly **synthetic Robolectric host fixtures**, not phone screenshots or live transfers.

- [Empty Transfers](../../artifacts/android-phase-4/runs/host-03/host-renders/15-transfers-empty.png)
- [Restart-paused download](../../artifacts/android-phase-4/runs/host-03/host-renders/16-transfers-restart-paused.png)
- [Uncertain export review](../../artifacts/android-phase-4/runs/host-03/host-renders/17-transfers-uncertain.png)
- [Transfers at 200% text](../../artifacts/android-phase-4/runs/host-03/host-renders/18-transfers-font-200.png)

## Not included

No uploads, permanent phone presence, owner API, background transfer listener/service, attachment protocol, automatic pins/refresh, Olm/age Android integration, E2EE chat, recursive deletion, destructive replacement, atomic provider publication or remote recall of received copies. Those claims are not implied by a green test count.
