# Using Files and preserving data

[README](../README.md) · [Run/build/test](desktop-development.md) · [Pairing](pairing-and-recovery.md) · [Implementation limits](phase-3-implementation.md)

**Development data only until native filesystem/security qualification is complete.** This guide describes the actual Phase 3 UI. It does not grant access to your computer merely because you opened a browser preview.

## 1. Register one folder locally

1. Start NARADA and open **Files**. A new profile contains no registered roots and no invented files.
2. Choose **Add folder**. In a native Windows build the folder dialog is implemented, but its execution is not yet qualified. In the browser preview enter an absolute folder path **on the agent host**; it is not a browser upload or a local browser filesystem picker.
3. Use a small disposable folder, a short display label, and optional excluded relative prefixes such as `Private` or `Drafts/Unpublished`, one per line. Exclusions are not wildcards.
4. Register it. This permits local indexing only. **No peer receives a grant automatically.**
5. Inspect the actual list, tree, grid and details. Refresh performs an owner rescan. Search covers this approved root's catalog; sort/pagination do not enumerate arbitrary disks.

For a simple Unix development fixture, create a new directory inside your project (not inside `.cache`, `.narada-data` or an existing private folder):

```sh
mkdir -m 700 narada-test-folder
printf 'Disposable NARADA test file.\n' > narada-test-folder/notes.txt
```

Use the absolute path to that new folder in the form. On Windows, create a dedicated folder outside AppData/system locations and select it. Do not register your home directory, code credentials, application databases, network shares or irreplaceable data.

## 2. Grant a paired device access

1. Pair the two real desktop agents using [the pairing walkthrough](pairing-and-recovery.md). Check fingerprints and approve deliberately.
2. On the folder's owning desktop, select its root and choose **Device access**.
3. Select exactly one active peer and a role:
   - **No access** removes that root grant.
   - **Browse names and metadata** cannot read pixels or mutate files.
   - **Browse + image previews** adds bounded PNG/JPEG preview, not file download.
   - **Manage** adds rename, trash and restore; not upload, recursive moves or permanent delete.
4. Save. This changes the grant revision and invalidates old authorization sessions.
5. On the joining desktop, choose the inviter under **File source**. Only currently granted roots appear.

Remote sources currently work for active outbound peers with a saved endpoint. The inviter does not automatically obtain a reverse endpoint for an inbound peer. Receiving a root grant and being able to initiate the reverse connection are separate requirements. No address is guessed or trusted from an unsolicited advertisement.

The real reference launcher is `npm run dev:reference` (UI 5175). It is a separate process/profile/identity. The source package and preview do not auto-pair or auto-share it.

## 3. Preview and ordinary operations

- Select a file to see its real size, relative location, timestamp and opaque version. Human-readable size is rounded; the inspector also retains exact decimal bytes.
- **Load image preview** requires read permission. It returns stripped/re-encoded pixels only. Unsupported documents remain inert files.
- **Rename** requires confirmation and the current version. Existing destinations, case/Unicode collisions, unsafe names and nonempty folders are refused.
- **Move to trash** moves a file or empty folder into the same root's managed area; it does not permanently delete it.
- **Managed trash → Restore item** restores to the original parent. If the name is occupied, cancel/refresh and choose another filename. No replace-existing option exists.
- A failed/uncertain dialog offers **Retry same action**, preserving the operation identifier and arguments. Do not manufacture a new ID to bypass an uncertain result.
- Pane sizes are local layout preferences. File contents, previews, selected names and grants are not persisted in browser localStorage.

The UI periodically refreshes current metadata. It clears content when reauthorization fails, the agent goes offline, trust disappears or observed grants change. This cannot erase bytes that a recipient already received while authorized.

## 4. Exclude or stop sharing

**Folder policy** changes subtree exclusions. Already known IDs do not bypass current policy. **Unregister folder** withdraws grants and deactivates the root, but leaves original files, `.narada-trash`, catalog history and journal records intact.

Re-registering the **same filesystem directory identity** restores the root ID and retained history. Old grants do not come back. A new directory at the same path is not automatically treated as the old root. Do not rename/replace roots while expecting the agent to silently follow them.

## 5. Recovery rules

| Situation | Safe next step |
|---|---|
| Stale version / external modification | Cancel, refresh and review the latest item before starting a new action. |
| Connection loss after clicking a mutation | Retry the identical operation ID/body. A timeout alone does not establish that nothing happened. |
| Existing destination | Preserve both files. Choose a different name in a newly reviewed action. |
| `OPERATION_INDETERMINATE` / recovery required | Stop mutating that root. Preserve its original location, managed trash, profile/database and any moved locations for qualified recovery. Do not delete or rewrite journal rows to unblock it. |
| Missing or changed retained bytes | Listing reports unavailable items. Records/quota are deliberately preserved; locate and preserve the data rather than assuming successful deletion. |
| Managed trash exists without a saved identity | Do not adopt, clear or replace the directory. A crash may have occurred before identity persistence; preserve it for owner recovery. |
| Root identity changed or a link appeared | Restore/verify the original directory or explicitly choose a new safe root. The agent will not follow replacements automatically. |
| Index limit / blocked index | Choose a smaller root or exclude subtrees, then refresh. No hidden “full index” is available beyond the limits. |
| Trash full | Restore retained items to release quota. This build has no permanent-delete/purge route. |
| Receipt history full | Stop mutations and preserve the profile. A qualified history-retention/recovery mechanism is future work. |
| Corrupt database or missing identity key | Preserve the files and original key material. Never initialize a fresh identity over the existing trusted profile. |

The recovery tests inject prepared/moved/missing states into **disposable test databases**. They do not prove every possible crash/power-loss ordering, filesystem flush or hardware failure. There is no automatic repair wizard in this slice.

## 6. Before using valuable data

Complete [native Windows qualification](windows-validation.md), independent filesystem/decoder security review and fault-injection testing. Specifically test reparse/junction/short-name aliases, other writers, root/directory movement, hardlinks, volume changes, antivirus locks, disk-full and crash boundaries. No GNU cross-build, browser screenshot or passing loopback suite waives those gates.
