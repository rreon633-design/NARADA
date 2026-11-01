# Phase 3 — end-to-end workflow and troubleshooting

[Index](README.md) · [Implementation scope](phase-3.md) · [Recovery rules](files-recovery.md)

## 1. Prepare a disposable desktop workspace

Use the current desktop/reference agent with an intact protected development profile. Its source has not been changed for this phase. Register an explicit local folder on the desktop, wait for its index to be ready, and use a few disposable files, one nested folder and a small PNG/JPEG.

Pairing is not file consent. On the desktop, grant the phone only what you want:

- **browse** — root listings, search and details;
- **read** — bounded image previews in addition to browse;
- **modify** — rename;
- **trash** — view/move into managed trash;
- **trash + create** — restore to the original parent.

Every selected operation also needs browse. No recursive/nonempty-directory mutation, overwrite or permanent deletion is supported.

## 2. Connect the phone

Install/upgrade the extracted Phase 3 debug APK on a disposable test device without clearing app data. The signing identity is the same development signer as earlier Android phases.

In **More → Devices**, enable local connections and complete the actual Phase 2 pairing workflow: offline QR/import/manual invitation, pinned bootstrap, proof, desktop approval, full fingerprint comparison and final phone confirmation. Select/reconnect the intended saved desktop. Keep NARADA visible on private IPv4 Wi-Fi/Ethernet; phone loopback, DNS/public addresses, cellular and VPN routes are not alternate transports.

The phone’s authenticated health result must advertise `files.v1`. A saved device or green health status does not itself grant a root.

## 3. Browse and search

Open **Files**. A first visit with eligible trust fetches roots; after an access change or expiry, tap **Refresh roots**. No roots can mean no browse grants or no currently available peer-visible roots—not invented sample content.

Tap a root, open directories, or use breadcrumb IDs. Search is explicitly submitted within the selected root; clearing search and submitting returns to its root. Choose name A–Z/Z–A, modified or size. **Next page / Previous** replace the current ≤16 rows. Previous history is bounded; return to the first page with Refresh.

Phone **Refresh view** performs fresh authentication and re-queries from the first page. It does **not** call owner-only reindex: the desktop’s watcher/reconciliation or owner refresh updates its catalog. Changed live objects can be omitted until reconciliation. Expired/invalidated cursors require a new first page; there is no stale-page merge.

## 4. Inspect a selected object

Open file details or the row’s more button. Details are fetched again and must match the selected version. A moved/changed object requires refresh before any new action.

**Show bounded image preview** requests only a re-encoded JPEG thumbnail, subject to current read permission and size/dimension checks on both sides. Original metadata is stripped by the desktop. The phone decodes pixels itself—no browser, HTML, PDF/SVG viewer, external app, network image cache or FileProvider export. Nothing is saved as an original or offline copy.

The whole view is memory-only. A successful user request renews its 60-second idle expiry. Closing details does not extend/cancel that original expiry. Drafts/selections are not restored after process recreation; an already saved operation is a separate protected recovery record.

## 5. Confirm a managed action

Choose **Rename on desktop**, **Move to managed trash**, or **Restore on desktop** when the relevant current grants allow it. Review the selected name/object and, if applicable, enter a safe name. A separate scrollable, secure confirmation screen requires an explicit checkbox. Identical rename names are not accepted; nothing is overwritten.

The phone saves its exact operation before sending. A matching receipt must be validated and durably saved before the UI says committed. Refresh afterwards for current entries; the app does not optimistically patch the list.

Restoring requires both trash and create. Desktop managed trash retains items for at least 14 days, has 200-item/100-MiB per-root quotas, and does not automatically purge or permanently delete. Only files and empty directories can be mutated.

## 6. Review an unconfirmed outcome

If communication or storage fails, inspect the desktop first. The action might already have happened.

- **Retry the same operation** resends the saved ID and unchanged arguments under the same pairing/current permissions. This can apply a request that never arrived; it is not merely checking status.
- **Review / dismiss local retry** requires acknowledgement and records a dismissed **unknown** outcome. It is not remote cancellation, failure, success or recovery.
- Never clear app data, remove the protected store, change pins, generate a replacement operation automatically, or repeatedly change names to “get around” an ambiguous outcome.

Read [the recovery decision table](files-recovery.md) before testing network loss/process interruption with important content.

## Common problems

| Problem | What to check |
|---|---|
| Not ready to browse | Foreground, enabled connections, eligible private LAN, preferred ACTIVE device, advertised file protocol, storage latch/retry block. |
| No roots | Desktop browse grants and registered root availability. Phone SAF permissions are unrelated. |
| Root blocked/index unavailable | Desktop path/ACL/exclusions/index and native filesystem safety. The phone cannot repair owner policy. |
| Stale cursor/version | Refresh; do not reuse another query’s cursor or rebase a saved mutation. |
| No preview | Current read grant, PNG/JPEG source, source/thumbnail bounds, changed source or strict decoder refusal. |
| Rename rejected | NFC name ≤180 UTF-8 bytes; no separators/devices/hidden-reserved names/trailing dot-space; inspect collisions. |
| Unknown mutation | Inspect desktop and journal; retry exact request or explicitly dismiss local retry. No automatic replacement. |
| Local provider error | Go to **More → Settings → Local storage**. Review/forget/regrant only the local read-only SAF grant. No remote download/export is implemented. |

## Next phase

Phase 4 must implement real verified transfers with immutable manifests/chunks/checkpoints, process-death recovery, private receipts, disk-full/revocation handling and a **separate explicit SAF export step**. Do not treat this preview, metadata listing or operation receipt as transfer progress or offline availability.
