# Phase 3 — implemented file contract

[Implementation](phase-3-implementation.md) · [Connection contract](phase-2-protocol.md) · [Recovery](files-and-recovery.md)

This is the **actual v0.3.0 contract**, not the broader proposed REST API in the planning pack. Owner IPC remains protocol 1; the file envelope has its own version 1. Objects, roots and cursors are opaque UUIDs. An object ID is not a path and is not authorization by itself.

## Owner commands

| Command | Payload / result |
|---|---|
| `list_roots` | Owner root metadata; includes local paths and exclusions. |
| `register_root` | `{path, label, exclusions}`; explicit local absolute folder, label up to 48 characters. |
| `update_root_policy` | `{root_id, expected_revision, exclusions}`. |
| `remove_root` | `{root_id, expected_revision}`; withdraw grants, retain disk content/history. |
| `reindex_root` | `{root_id}`. |
| `file_query` | A query below, using local-owner authority. |
| `file_mutation` | A versioned mutation below. |
| `remote_file_query` | `{peer_id, query}`; agent resolves only the saved, pinned endpoint. |
| `remote_file_mutation` | `{peer_id, mutation}`. |
| `choose_root` | **Native shell only**, not an agent/peer command. Returns a Unicode path or cancellation; Windows runtime validation pending. |

These are in addition to the existing 16 Phase 2 owner commands. Root input paths are never accepted on the peer route. The native UI has no general filesystem capability.

## Peer route and authentication

Only **POST `/api/v1/files`** is added. The JSON body is:

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

`action.type` is `query` or `mutate`. The existing strict transport response envelope contains either data or a sanitized error. `hello` compatibility and file version are checked. Unknown fields/actions, including extra fields on an empty `roots` or `trash` variant, are refused.

TLS 1.3, exact saved certificate pin, private-key proof and activated trust are prerequisites. A current session lease is checked against database revision/epoch for every request. Queries/mutations retain a policy read lease through their filesystem work. Owner grant/revoke/root-policy changes serialize against that work. A request admitted before a change can finish before that change commits; this is not instantaneous cancellation or remote erasure.

Health advertises `health`, `permissions`, `files.v1`. The client also recognizes the historical two-operation health list for connection compatibility. A Phase 2 peer does not gain file support merely because the connection works. Unsupported file routes/versions fail; there is no plaintext or path-based fallback.

## Queries

All non-root queries include `root_id`.

| `kind` | Other fields | Required current capabilities |
|---|---|---|
| `roots` | None | Returns only roots with `browse`. |
| `browse` | `directory_id` or null, `cursor` or null, `sort` | `browse` |
| `search` | `query`, `cursor` or null, `sort` | `browse` |
| `details` | `object_id` | `browse` |
| `preview` | `object_id`, `expected_version` | `browse` + `read` |
| `trash` | `cursor` or null | `browse` + `trash` |

Sort is `name`, `name_desc`, `modified` or `size`. Search is a literal case-normalized filename search across the approved root's catalog, not arbitrary filesystem traversal; 1–128 UTF-8 bytes, no controls. Root policy exclusions still apply.

Pages contain `root`, `entries`, `next_cursor`, and appropriate breadcrumbs/notice. Entry size and index epoch are decimal strings to avoid JavaScript integer truncation. Displayed versions are 64-character hexadecimal **metadata version tokens, not content-integrity hashes**. Do not use them as transfer checksums.

Cursors expire after 60 seconds and are bound to actor, root, query/directory/sort and index epoch. Up to 64 cursors are retained; eviction or a changed epoch returns `CURSOR_STALE`. Restart loses cursors, not the persistent index. Current grants are required even for a previously issued cursor.

Owner root metadata includes its path and exclusions. Peer root responses do not. A stale/moved/hidden/unsafe object is omitted or refused rather than read through its old name. Live revalidation can produce short pages; an empty page is not a promise that a blocked or changed root contains no files.

## Preview representation

Successful data includes `object_id`, `version`, `mime: image/jpeg`, `width`, `height`, `data` (base64), and a notice.

- Source: regular PNG/JPEG, at most 5 MiB and 4096 × 4096 pixels.
- Decoder allocation setting: 96 MiB. This is **not** a measured cap on total process working memory.
- Output: metadata-stripped, single-frame baseline JPEG, no upscaling of smaller images, at most 256 × 256 and 32 KiB.
- UI checks selected object/version, envelope size, actual frame dimensions, one baseline frame, and no trailing embedded image before creating a data-URI image.
- No original file download, original metadata, SVG/HTML/PDF renderer or executable handler exists.

## Versioned mutation

```json
{
  "root_id": "<opaque root UUID>",
  "operation_id": "<new UUID for this user-confirmed action>",
  "object_id": "<opaque object or retained-item UUID>",
  "expected_version": "<64-hex metadata version from the latest listing>",
  "action": {"kind": "rename", "name": "new-name.txt"}
}
```

| Action | Parameters | Required capabilities |
|---|---|---|
| `rename` | `name` | `browse` + `modify` |
| `trash` | None | `browse` + `trash` |
| `restore` | `name` or null; original parent only | `browse` + `trash` + `create` |

Restore uses the retained-item `object_id` and its current version from the trash listing. Destinations never overwrite. The original parent must exist; a different filename may resolve a collision. Only files and empty folders are supported. A nonempty folder, even one whose children are all excluded, is refused.

Names must be NFC, at most 180 UTF-8 bytes, not hidden/reserved/device names, not separators/streams, not trailing dots/spaces or bidi overrides. Relative paths are internally bounded to 2,048 bytes / 32 components. Peers cannot supply a destination directory, absolute path, UNC/device namespace, ACL or temporary path.

A successful receipt contains `operation_id`, `state: committed`, action, object ID, resulting version and a trash ID when relevant. A same-actor/same-ID/same-body replay returns the saved receipt if current root access/exclusions permit it. A different body gives `IDEMPOTENCY_CONFLICT`. Cancellation/timeout is not proof of non-application: retry exactly the same action identifier, not a replacement mutation.

## Resource budgets and error handling

- File data response: 60,000 bytes; transport envelope limit 60,000 + 1,024. Control requests/responses remain at their existing smaller limits; owner IPC remains bounded to 65,536 bytes.
- File jobs: eight admitted jobs, serialized filesystem/catalog work. Watchers and reconciliation are bounded but not real-time guarantees.
- Trash: 200 retained entries / 100 MiB per root, minimum 14-day retention, **no automatic purge or permanent-delete API**.
- Operation history: 5,000 per root. Unresolved operations block further root mutation. Startup examines up to 64 prepared intents.

Common codes: `ACCESS_DENIED`, `ROOT_UNAVAILABLE`, `ROOT_CHANGED`, `INDEX_LIMIT`, `INDEX_UNAVAILABLE`, `CURSOR_STALE`, `STALE_VERSION`, `NAME_COLLISION`, `DIRECTORY_NOT_EMPTY`, `PREVIEW_UNSUPPORTED`, `PREVIEW_LIMIT`, `TRASH_QUOTA`, `TRASH_CHANGED`, `TRASH_UNTRUSTED`, `OPERATION_INDETERMINATE`, `IDEMPOTENCY_CONFLICT`, `FILE_BUSY`. Transport and identity failures retain their Phase 2 meanings. No error resets identity, files or metadata.
