# Android ↔ desktop file protocol

[Index](README.md) · [Phase 3 scope](phase-3.md) · [Recovery](files-recovery.md)

## Boundary

The only added peer route is **POST `/api/v1/files`**, using the existing exact certificate/SPKI pins, fresh TLS 1.3 context, approved ACTIVE client identity and network-bound foreground IO. It is not a desktop owner IPC endpoint, Android listener, generic HTTP client or arbitrary URL proxy. Owner root registration, reindex/policy changes, grant updates and pairing approval remain on the desktop.

`ConnectionsCoordinator.withFiles` serializes file work with connection commands, cancels/joins the health lease, and owns a fresh IO-scoped session. The socket never escapes to UI. Health resumes afterwards only if the foreground/network/storage/epoch policy still permits it. Cancellation closes owned sockets but cannot undo desktop requests already processed.

## Request envelope

```json
{
  "hello": {
    "version": 1,
    "capabilities": ["connection.health", "connection.permissions"]
  },
  "version": 1,
  "action": {
    "type": "query",
    "payload": {"kind": "roots"}
  }
}
```

`action.type` is `query` or `mutate`. The file protocol is advertised independently as `files.v1` in authenticated health; other advertised desktop operations do not enable later Android features.

| Query kind | Payload fields in addition to `kind` | Required current capabilities |
|---|---|---|
| `roots` | none | Desktop filters to browsable roots |
| `browse` | `root_id`, nullable `directory_id`, nullable `cursor`, `sort` | `browse` |
| `search` | `root_id`, `query`, nullable `cursor`, `sort` | `browse` |
| `details` | `root_id`, `object_id` | `browse` |
| `preview` | `root_id`, `object_id`, `expected_version` | `browse`, `read` |
| `trash` | `root_id`, nullable `cursor` | `browse`, `trash` |

Sort strings: `name`, `name_desc`, `modified`, `size`. IDs/cursors are canonical UUID strings; versions are 64 lowercase hexadecimal characters. Peer identity IDs remain 64-character hashes, **not** file UUIDs. Search is nonblank, control-free, at most 128 UTF-8 bytes; Android also rejects bidi overrides/isolates.

Mutation payload: `root_id`, **durably generated `operation_id`**, `object_id`, `expected_version`, `action`. The nested action is tagged by `kind`:

- `rename` + `name`: `browse` + `modify`.
- `trash`: no `name` field; `browse` + `trash`.
- `restore` + nullable `name`: `browse` + `trash` + `create`; `object_id` is the retained trash ID. The Android confirmation supplies an explicit safe restore name.

Names follow the desktop’s NFC/180-UTF-8-byte component policy, including reserved Windows device names/superscript digits, no separators/controls/bidi overrides, no trailing dot/space, and hidden/reserved destination exclusions. The phone does not synthesize desktop paths or rename collision suffixes.

## Response validation

Envelope remains `{ok, data, error}`. HTTP status must agree with it. Unknown/duplicate JSON fields, escaped duplicate keys, quoted numeric/boolean coercions, malformed UTF-8, redirects, compression and ambiguous framing are refused. Only allowlisted protocol error codes reach UI; **remote prose is never displayed or logged**. Remote file `STORAGE_UNAVAILABLE` maps to `FILE_REMOTE_STORAGE`, not the phone’s protected-storage failure latch.

- **Root:** `root_id`, label, revision, index epoch, state/error code, indexing time/counts and capabilities. Owner-only path/exclusions/watcher fields are not accepted. Capabilities must equal the corresponding freshly authenticated grant.
- **Page:** root, ≤16 unique entries, bounded breadcrumbs, nullable next cursor, matching root/index epoch, indexed-match count and bounded notice. Browse parents and last breadcrumb must match the requested directory. Search is root-wide.
- **Entry:** UUID, safe name, file/directory kind, version, unsigned-decimal size, timestamp, validated display-only relative label, nullable parent UUID, preview candidate flag. IDs—not labels—drive all future operations.
- **Trash page:** root, ≤16 retained entries, unavailable count, cursor, notice. Entries bind safe original display labels, current version and retention dates. Missing/changed retained objects are owner recovery, not successful deletion.
- **Preview:** exact requested object/version, JPEG MIME, 1–256 dimensions, canonical Base64, ≤32 KiB. JPEG parser permits one bounded baseline RGB frame, fixed JFIF header only, no EXIF/APP1+ or comments, and no trailing payload. Dimensions must match before Android decoder allocation. The native decoder separately checks MIME/bounds/result allocation (≤256 × 256 × 4 bytes).
- **Receipt:** operation ID, `committed`, action, object ID/version, nullable trash ID. Rename/trash bind the selected object; trash ID equals the operation ID. Restore returns the **original object ID**, not the trash-entry ID, so only its canonical form is checked; the UI refreshes rather than patching a guessed row.

## Authorization sequence

1. Validate request identifiers/name/version locally before file traffic.
2. Verify current local trust/epoch/foreground scope; issue authenticated health and persist current grants/operations/counters.
3. Require `files.v1` and the current operation’s capabilities.
4. Issue the bounded file request. The desktop rechecks active peer/session, root policy, operation capability, exclusions, live object/version and native filesystem boundaries.
5. Validate the response, recheck local fences and authenticate health again. A changed grant/session or failed post-check discards the result. The desktop may close an old session when policy changes; no post-check on a closed lease is invented.
6. For mutation success, persist the matching receipt before returning a committed UI result. Otherwise preserve the pre-send unconfirmed intent.

Health polling is not instantaneous remote erasure. Already delivered metadata can be visible until an observed invalidation, a request failure or the 60-second view expiry. Root indexing/exclusion changes are also detected through the file protocol; health grants alone are not a root-content subscription. Neither screenshots already taken elsewhere nor already admitted desktop effects can be revoked retroactively.

## Limits

| Boundary | Limit |
|---|---:|
| All request bodies | 16,384 bytes |
| File response body only | **61,024 bytes** (desktop 60,000-byte data budget + envelope headroom) |
| Non-file response bodies | Still 16,384 bytes |
| HTTP header/chunk-line budget | 16 KiB total; existing strict framing/deadlines retained |
| HTTP request absolute deadline | 8 seconds per request; one logical file action also performs health checks |
| Root grants / retained peers | 8 / 16 |
| Desktop page / phone current rows | 16 / 16 |
| Phone previous-page history | 32 previous positions + current; no accumulated row cache |
| Desktop cursor / phone metadata view | 60-second cursor / 60-second idle in-memory view |
| Breadcrumb depth / display-relative label | 32 components + root / 2,048 UTF-8 bytes |
| Desktop source preview | PNG/JPEG, ≤5 MiB, ≤4,096 per axis, bounded decoder allocation |
| Returned / decoded phone preview | ≤32 KiB JPEG, ≤256 per axis / ≤256 KiB pixels |
| Phone sealed file-operation window | 32 records; unresolved outcomes never auto-evicted |
| Desktop managed trash | 200 items / 100 MiB per root; retained at least 14 days, no automatic purge |
| Desktop mutation history | 5,000 per root; quota does not silently discard receipts |

These are development limits, not resource measurements or security certification. The original desktop source is unchanged; [native Windows and Android gates](phase-3-device-checklist.md) remain open.
