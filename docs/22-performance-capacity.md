# NARADA — Performance, Capacity and Resource Planning

> [Documentation index](../README.md) · Starting budgets to benchmark · 30 October 2026

## 1. Resource model

The objective is predictable, bounded work—not maximum throughput at any cost. Separate the background agent, desktop WebView, Android app, active transfer buffers, OS file cache and database/thumbnail caches when reporting memory.

Initial transfer settings: two active transfers globally, two in-flight chunks per transfer and 4 MiB chunks. The raw chunk window is approximately:

```text
2 transfers × 2 chunks × 4 MiB = 16 MiB
```

Additional copies, TLS, hashing, queues and preview buffers increase real use. Start with a 64 MiB active streaming-buffer budget per endpoint, measure it, and limit large previews separately. Do not claim total process memory is 16 MiB.

## 2. Capacity boundaries

| Dimension | Planning policy |
|---|---|
| File size | Provisional qualification through 20 GiB where storage/provider limits allow; publish only tested guarantees. |
| Protocol counters | Bounded wide integers; potentially unsafe JSON integers encoded as decimal strings for JavaScript. |
| Manifest | Paged digests/bitmaps; no full unbounded file list in one response. |
| Directory | Paginate, index and virtualize; benchmark 50k indexed entries plus stress datasets. |
| Transfer concurrency | Global/per-peer/per-root limits; fair scheduler and cancellation capacity. |
| Pins | User-visible quota based on actual available storage, not the mockup's fixed example numbers. |
| Outbox/attachments | Byte and count quotas, age limits and reference-aware cleanup. |
| Logs | Initial 20 MiB cap and 7-day retention; adjust from real diagnostics needs. |
| Journal | Bounded age/size with explicit cursor expiry and snapshot fallback. |

Filesystem/provider limits still apply. For example, FAT32 cannot hold a single file of 4 GiB or more; fail early rather than promising “unlimited size.” Other filesystem/provider behavior must be qualified separately.

## 3. Disk-space accounting

Account per device for the objects it actually holds:

```text
required free space >=
    new source snapshot, if needed
  + encrypted attachment blob, if needed
  + receiver staging / replacement version
  + retained old version or conflict copy
  + provider export copy, if on the same storage volume
  + database/log growth and a safety reserve
```

Not every operation uses every term, and sender/receiver storage must not be conflated. A large encrypted attachment may temporarily require multiple file-sized objects. Show a preparation/space estimate and permit an explicit quota increase only when safe.

- Check free space before preparation and during writes.
- A reservation estimate cannot prevent another app from consuming space; handle actual disk-full failures.
- Unpin removes the managed local copy only when no protected reference needs it.
- Do not automatically clear unsent attachments or unresolved conflicts to satisfy a new transfer.

## 4. Windows optimization

- Keep one main user agent with bounded tasks and a separate interactive shell.
- Isolate blocking hashing/file enumeration from the async reactor using a bounded worker pool.
- Coalesce filesystem hints and budget reconciliation rather than rescanning all roots continuously.
- Use indexed queries, stable pagination and small event payloads.
- Avoid expensive UI rerenders/progress updates when the main window is hidden.
- Lazy-generate previews under independent decode/size limits.
- Keep idle polling minimal; use native lifecycle/network events where appropriate.
- Measure installer/runtime footprint separately from working-set memory.

## 5. Android optimization

- Use lifecycle-scoped NSD; avoid continuous discovery when not needed.
- Select the correct transfer execution API and stop it promptly when no active work remains.
- Use WorkManager constraints for deferrable refresh; do not turn it into a polling timer.
- Limit simultaneous decodes, connections and open URI/file handles.
- Use private persistent storage for pins and an evictable cache only for truly reconstructible data.
- Avoid unnecessary wake locks; where required, bound their lifetime and release on cancellation/error.
- Measure battery/thermal behavior on real devices during a long transfer and idle connected period.
- React to low storage and background restrictions by pausing safely, not repeatedly restarting expensive work.

## 6. Network scheduling

Prioritize pairing/session control, cancel/revoke commands, chat receipts and small metadata responses over bulk file bytes. Bounded queues and fair scheduling prevent one large file from freezing the interface.

- Batch small metadata updates without hiding meaningful state transitions.
- Use full-jitter reconnect backoff; OS background scheduling is a separate constraint.
- Do not compress JPEG/MP4/ZIP and similar already-compressed content by default.
- Evaluate optional compression/QUIC only against the same qualified TCP/TLS baseline.
- Measure no-internet LAN behavior separately from optional update-service access.

## 7. Performance test report

Record build, OS, device CPU/RAM/storage, filesystem/provider, router/band/signal, payload type and size, concurrency, preparation time, payload throughput, verification/export time, CPU/memory/battery and failures.

Compare p50/p95 where appropriate and include cold/warm results. Investigate a dependency/UI change that exceeds an agreed regression budget even when the absolute average still appears acceptable.

Related: [Success metrics](17-success-metrics.md), [Transfer mechanism](08-transfer-mechanism.md), [Testing](18-testing-validation.md).
