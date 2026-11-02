import { pinDialog } from "../components/content-dialog.js";
import { selectChatAttachment } from "../lib/content.js";
import { openTransferDialog } from "../components/transfer-dialog.js";
import { h, replace } from "../lib/dom.js";
import { icon } from "../components/icons.js";
import {
  badge,
  button,
  iconButton,
  pageHeader,
  panelHead,
  modal,
  toast,
} from "../components/ui.js";
import { agent, isNative, chooseRoot } from "../services/bridge.js";
import { bytes, relativeTime } from "../lib/format.js";
import {
  parseExclusions,
  validMutationName,
  grantForRole,
  roleForGrant,
  boundedLayout,
  validatePreview,
  resolveRootSelection,
} from "../lib/files.js";

// Session-only navigation preference; never persist personal paths or file content here.
const lastRoots = new Map();
let lastDevice = "local";

export function files(ctx) {
  const view = h("div", { class: "page files-page phase3-files" });
  const s = {
    alive: true,
    device: lastDevice,
    roots: [],
    root: null,
    requestedRootId: lastRoots.get(lastDevice) || null,
    directory: null,
    entries: [],
    crumbs: [],
    selected: null,
    preview: null,
    search: "",
    sort: "name",
    grid: false,
    trash: false,
    cursor: null,
    history: [],
    next: null,
    busy: false,
    error: null,
    notice: "",
    expanded: new Map(),
    topFolders: new Map(),
    snapshot: ctx.snapshot,
    online: ctx.connected,
    sequence: 0,
    layout: boundedLayout(localStorage.getItem("narada.file-layout")),
  };
  const body = h("div");
  view.append(body);
  let timer,
    pointerCleanup = null;
  const can = (cap) => Boolean(s.root?.capabilities?.includes(cap));
  const peers = () =>
    s.snapshot?.connections?.peers?.filter(
      (p) => p.state === "active" && p.direction === "outbound" && p.endpoint,
    ) || [];
  const query = (q) =>
    s.device === "local"
      ? agent.file_query(q)
      : agent.remote_file_query({ peer_id: s.device, query: q });
  const mutate = (m) =>
    s.device === "local"
      ? agent.file_mutation(m)
      : agent.remote_file_mutation({ peer_id: s.device, mutation: m });
  const currentQuery = () =>
    s.trash
      ? { kind: "trash", root_id: s.root.root_id, cursor: s.cursor }
      : s.search
        ? {
            kind: "search",
            root_id: s.root.root_id,
            query: s.search,
            cursor: s.cursor,
            sort: s.sort,
          }
        : {
            kind: "browse",
            root_id: s.root.root_id,
            directory_id: s.directory,
            cursor: s.cursor,
            sort: s.sort,
          };
  function clearContent(clearTree = true) {
    s.entries = [];
    s.selected = null;
    s.preview = null;
    s.crumbs = [];
    if (clearTree) {
      s.expanded.clear();
      s.topFolders.clear();
    }
    s.next = null;
  }
  async function load(reset = true, quiet = false) {
    const seq = ++s.sequence;
    if (reset) {
      s.cursor = null;
      s.history = [];
    }
    s.busy = true;
    if (!quiet) draw();
    try {
      const response = await query({ kind: "roots" });
      if (seq !== s.sequence || !s.alive) return;
      const previous = s.root;
      s.roots = response.roots;
      const selection = resolveRootSelection(
        s.roots,
        s.root?.root_id || s.requestedRootId,
      );
      s.root = selection.root;
      if (selection.missing) {
        clearContent();
        s.notice = "Choose another folder. None was opened automatically.";
      }
      if (
        previous?.index_epoch !== s.root?.index_epoch ||
        previous?.revision !== s.root?.revision
      ) {
        s.expanded.clear();
        s.topFolders.clear();
      }
      if (!previous && s.root && !selection.missing) {
        s.requestedRootId = s.root.root_id;
        lastRoots.set(s.device, s.root.root_id);
        s.directory = null;
        s.trash = false;
        clearContent();
      }
      if (s.root) {
        const data = await query(currentQuery());
        if (seq !== s.sequence || !s.alive) return;
        s.root = data.root;
        if (!s.trash && !s.topFolders.has(s.root.root_id)) {
          const top =
            !s.directory && !s.search
              ? data
              : await query({
                  kind: "browse",
                  root_id: s.root.root_id,
                  directory_id: null,
                  cursor: null,
                  sort: "name",
                });
          if (seq !== s.sequence || !s.alive) return;
          s.topFolders.set(s.root.root_id, {
            entries: top.entries.filter((e) => e.kind === "directory"),
            more: Boolean(top.next_cursor),
          });
        }
        s.entries = data.entries;
        s.crumbs = data.breadcrumbs || [
          { name: s.root.label, object_id: null },
        ];
        s.next = data.next_cursor;
        s.notice = data.notice || "";
        if (s.selected) {
          const updated = s.entries.find(
            (e) => e.object_id === s.selected.object_id,
          );
          if (!updated || updated.version !== s.selected.version) {
            s.selected = null;
            s.preview = null;
          } else s.selected = updated;
        }
      } else {
        clearContent();
        if (!selection.missing)
          s.notice = "No folders have been added or shared with this device.";
      }
      s.error = null;
    } catch (e) {
      if (seq !== s.sequence || !s.alive) return;
      clearContent();
      s.error = e.message;
      if (
        s.device !== "local" ||
        [
          "DEVICE_REVOKED",
          "PERMISSION_DENIED",
          "ACCESS_DENIED",
          "ROOT_UNAVAILABLE",
        ].includes(e.code)
      ) {
        s.roots = [];
        s.root = null;
      }
    } finally {
      if (seq === s.sequence && s.alive) {
        s.busy = false;
        draw();
      }
    }
  }
  function chooseFolder(root, directory = null) {
    const changed = s.root?.root_id !== root.root_id;
    s.root = root;
    s.requestedRootId = root.root_id;
    lastRoots.set(s.device, root.root_id);
    s.notice = "";
    s.directory = directory;
    s.search = "";
    s.trash = false;
    clearContent(changed);
    load();
  }
  function select(entry) {
    s.selected = entry;
    s.preview = null;
    draw();
  }
  async function preview() {
    const chosen = s.selected;
    if (!chosen || !s.root) return;
    const rootId = s.root.root_id,
      device = s.device;
    s.busy = true;
    draw();
    try {
      const p = await query({
        kind: "preview",
        root_id: rootId,
        object_id: chosen.object_id,
        expected_version: chosen.version,
      });
      if (
        !s.alive ||
        s.device !== device ||
        s.root?.root_id !== rootId ||
        s.selected?.object_id !== chosen.object_id
      )
        return;
      s.preview = validatePreview(p, chosen.object_id, chosen.version);
    } catch (e) {
      s.preview = { error: e.message };
    } finally {
      if (s.alive) {
        s.busy = false;
        draw();
      }
    }
  }
  function inputField(label, node, hint) {
    const id = "files-" + crypto.randomUUID();
    node.id = id;
    return h(
      "label",
      { class: "file-field", for: id },
      h("span", {}, label),
      node,
      hint && h("small", {}, hint),
    );
  }
  function addRoot() {
    const path = h("input", {
      class: "text-input",
      type: "text",
      placeholder: isNative
        ? "Choose a folder…"
        : "/absolute/path/on/the/agent/host",
      autocomplete: "off",
      readonly: isNative,
      spellcheck: "false",
    });
    const name = h("input", {
      class: "text-input",
      type: "text",
      maxlength: 48,
      placeholder: "For example, Design library",
    });
    const excluded = h("textarea", {
      class: "text-input",
      rows: 3,
      placeholder: "Private\nDrafts/Unpublished",
    });
    const error = h("p", { class: "form-error", role: "alert" });
    let d;
    const save = button(
      "Register folder",
      async () => {
        error.textContent = "";
        save.disabled = true;
        try {
          if (!path.value.trim() || !name.value.trim())
            throw new Error("Choose a folder and give it a display label.");
          const registered = await agent.register_root({
            path: path.value.trim(),
            label: name.value.trim(),
            exclusions: parseExclusions(excluded.value),
          });
          d.close();
          toast("Folder added on this computer. No device has access yet.");
          s.root = registered;
          s.requestedRootId = registered.root_id;
          lastRoots.set(s.device, registered.root_id);
          s.directory = null;
          await load();
          ctx.refresh(false);
          if (isNative && s.root?.root_id === registered.root_id)
            await shareDialog();
        } catch (e) {
          error.textContent = e.message;
        } finally {
          save.disabled = false;
        }
      },
      { kind: "primary", leading: "folder" },
    );
    const pick = isNative
      ? button(
          "Choose folder…",
          async () => {
            try {
              const v = await chooseRoot();
              if (v) {
                path.value = v;
                if (!name.value)
                  name.value =
                    v.split(/[\\/]/).filter(Boolean).at(-1) ||
                    "Approved folder";
              }
            } catch (e) {
              error.textContent = e.message;
            }
          },
          { leading: "folder" },
        )
      : null;
    d = modal({
      eyebrow: "AN EXPLICIT BOUNDARY",
      title: "Add an approved folder",
      className: "file-dialog",
      content: h(
        "div",
        {},
        h(
          "p",
          { class: "inline-note" },
          icon("shield", 17),
          isNative
            ? "Only this folder will be indexed. No peer receives access until you grant it."
            : "Development preview: this path is on the development agent host, not your browser’s computer. Use a disposable test folder.",
        ),
        pick,
        inputField(
          isNative ? "Selected folder" : "Agent-host folder path",
          path,
          "Whole disks, system/application directories, links and overlapping roots are refused.",
        ),
        inputField("Display label", name),
        h(
          "details",
          { open: !isNative },
          h("summary", {}, "Advanced: exclude subfolders"),
          inputField(
            "Excluded relative prefixes",
            excluded,
            "One per line. Hidden files and unsafe entries are always omitted. No wildcards.",
          ),
        ),
        error,
      ),
      actions: [button("Cancel", () => d.close()), save],
    });
  }
  function policyDialog() {
    const root = s.root;
    if (!root) return;
    const exclusions = h("textarea", {
      class: "text-input",
      rows: 5,
      value: (root.exclusions || []).join("\n"),
    });
    const error = h("p", { class: "form-error", role: "alert" });
    let d;
    const save = button(
      "Save exclusions",
      async () => {
        save.disabled = true;
        try {
          await agent.update_root_policy({
            root_id: root.root_id,
            expected_revision: root.revision,
            exclusions: parseExclusions(exclusions.value),
          });
          d.close();
          clearContent();
          await load();
          toast("Current root policy saved.");
        } catch (e) {
          error.textContent = e.message;
        } finally {
          save.disabled = false;
        }
      },
      { kind: "primary" },
    );
    d = modal({
      eyebrow: "OWNER-ONLY ROOT POLICY",
      title: root.label,
      content: h(
        "div",
        {},
        h("p", { class: "file-host-path" }, root.path),
        inputField(
          "Excluded relative prefixes",
          exclusions,
          "Excluding a directory excludes all its descendants. The app never follows symbolic links or junctions.",
        ),
        h(
          "p",
          { class: "muted small" },
          "Up to 8 folders and 5,000 checked entries each. Local metadata is not encrypted.",
        ),
        error,
      ),
      actions: [
        button(
          "Unregister folder",
          () => {
            d.close();
            removeDialog(root);
          },
          { kind: "danger" },
        ),
        button("Cancel", () => d.close()),
        save,
      ],
    });
  }
  function removeDialog(root) {
    let d;
    const error = h("p", { class: "form-error", role: "alert" });
    const act = button(
      "Unregister folder",
      async () => {
        act.disabled = true;
        try {
          await agent.remove_root({
            root_id: root.root_id,
            expected_revision: root.revision,
          });
          d.close();
          s.root = null;
          // Keep the removed opaque ID until the user chooses another folder.
          clearContent();
          await load();
          ctx.refresh(false);
          toast("Root and device grants removed. No files were deleted.");
        } catch (e) {
          error.textContent = e.message;
        } finally {
          act.disabled = false;
        }
      },
      { kind: "danger" },
    );
    d = modal({
      eyebrow: "STOP SHARING, KEEP THE FILES",
      title: `Unregister ${root.label}?`,
      content: h(
        "div",
        {},
        h(
          "p",
          {},
          "This withdraws every device grant for the root and invalidates its sessions. Original files and retained trash stay on disk. Re-register the same directory to restore retained items later; grants are not restored automatically.",
        ),
        error,
      ),
      actions: [button("Keep registered", () => d.close()), act],
    });
  }
  async function shareDialog() {
    const root = s.root;
    if (!root) return;
    let snapshot;
    try {
      snapshot = await agent.snapshot();
    } catch (e) {
      toast(e.message, "error");
      return;
    }
    const devices = snapshot.connections.peers.filter(
      (p) => p.state === "active",
    );
    const peerSelect = h(
      "select",
      { class: "text-input", "aria-label": "Device to authorize" },
      h("option", { value: "" }, "Choose a paired device"),
      devices.map((p) => h("option", { value: p.peer_id }, p.name)),
    );
    const role = h(
      "select",
      { class: "text-input", "aria-label": "Folder permission level" },
      h(
        "option",
        { value: "custom", disabled: true },
        "Custom existing access — choose a level to change",
      ),
      h("option", { value: "none" }, "No access"),
      h("option", { value: "browse" }, "Browse names and metadata"),
      h("option", { value: "preview" }, "View & download (read-only)"),
      h("option", { value: "offline" }, "Browse + read + offline pins"),
      h(
        "option",
        { value: "manage" },
        "Browse, preview, rename, trash + restore",
      ),
    );
    const error = h("p", { class: "form-error", role: "alert" });
    let d;
    peerSelect.addEventListener("change", () => {
      const p = devices.find((p) => p.peer_id === peerSelect.value);
      const c =
        p?.permissions.roots.find((g) => g.root_id === root.root_id)
          ?.capabilities || [];
      role.value = roleForGrant(c);
    });
    const save = button(
      "Save device access",
      async () => {
        save.disabled = true;
        try {
          const p = devices.find((p) => p.peer_id === peerSelect.value);
          if (!p) throw new Error("Choose an active paired device.");
          const grant = grantForRole(root.root_id, role.value);
          const roots = p.permissions.roots.filter(
            (g) => g.root_id !== root.root_id,
          );
          if (grant.capabilities.length) roots.push(grant);
          await agent.update_peer_permissions({
            peer_id: p.peer_id,
            expected_revision: p.grant_revision,
            permissions: { ...p.permissions, roots },
          });
          d.close();
          toast(
            "Device grant saved. Previous authorization sessions were invalidated.",
          );
          ctx.refresh(false);
        } catch (e) {
          error.textContent = e.message;
        } finally {
          save.disabled = false;
        }
      },
      { kind: "primary", disabled: !devices.length },
    );
    d = modal({
      eyebrow: "ONE DEVICE. ONE FOLDER.",
      title: `Share ${root.label}`,
      content: h(
        "div",
        {},
        h(
          "p",
          {},
          "Review this device’s access before saving. No access stops future sharing; files and received copies are kept.",
        ),
        !devices.length &&
          h(
            "p",
            { class: "inline-note" },
            "No active paired devices. Pair one in Devices first.",
          ),
        inputField("Paired device", peerSelect),
        inputField(
          "Permission level",
          role,
          "Manage includes restore into the original folder, not uploads or permanent deletion. Nonempty folder mutations are blocked.",
        ),
        error,
      ),
      actions: [button("Cancel", () => d.close()), save],
    });
  }
  function mutationDialog(kind) {
    const entry = s.selected,
      root = s.root,
      device = s.device;
    if (!entry || !root) return;
    const name = h("input", {
      class: "text-input",
      value: entry.name,
      autocomplete: "off",
      spellcheck: "false",
    });
    const error = h("p", { class: "form-error", role: "alert" });
    let d,
      attempt = null;
    const title =
      kind === "rename"
        ? "Rename this item"
        : kind === "trash"
          ? "Move this item to managed trash?"
          : "Restore this retained item";
    const action = button(
      kind === "rename"
        ? "Rename item"
        : kind === "trash"
          ? "Move to trash"
          : "Restore item",
      async () => {
        action.disabled = true;
        error.textContent = "";
        try {
          if (!attempt) {
            if (kind !== "trash" && !validMutationName(name.value))
              throw new Error(
                "Use a safe NFC name, without hidden/device names, path separators or trailing dots/spaces.",
              );
            attempt = {
              root_id: root.root_id,
              operation_id: crypto.randomUUID(),
              object_id: entry.object_id,
              expected_version: entry.version,
              action: kind === "trash" ? { kind } : { kind, name: name.value },
            };
            name.disabled = true;
          }
          if (device !== s.device)
            throw new Error("The selected device changed. Reopen this action.");
          const result = await mutate(attempt);
          if (result.state !== "committed")
            throw new Error(
              "The operation was not confirmed. Refresh before proceeding.",
            );
          d.close();
          s.selected = null;
          s.preview = null;
          await load();
          toast(
            kind === "trash"
              ? "Moved to managed trash. Nothing was permanently deleted."
              : kind === "restore"
                ? "Restored without replacing any existing file."
                : "Renamed without overwriting a destination.",
          );
        } catch (e) {
          error.textContent =
            e.message +
            " Retry uses the same operation identifier; cancel and refresh if the result is uncertain.";
          action.querySelector("span").textContent = "Retry same action";
        } finally {
          action.disabled = false;
        }
      },
      { kind: kind === "trash" ? "danger" : "primary" },
    );
    d = modal({
      eyebrow: "VERSION-CHECKED · NEVER OVERWRITE",
      title,
      content: h(
        "div",
        {},
        h(
          "p",
          { class: "file-host-path" },
          entry.relative_path || entry.original_path || entry.name,
        ),
        kind !== "trash" &&
          inputField(
            kind === "restore" ? "Restore filename" : "New name",
            name,
          ),
        h(
          "p",
          { class: "muted" },
          kind === "trash"
            ? "The selected file or empty folder is moved within this root. Retention is at least 14 days; this build never automatically purges. Quota: 100 MiB / 200 items per root."
            : kind === "restore"
              ? "The original parent must still exist. Choose another filename if occupied. Existing data is never replaced."
              : "The current file version must still match. Nonempty folders are deliberately not renamed in this slice.",
        ),
        error,
      ),
      actions: [button("Cancel", () => d.close()), action],
    });
  }
  async function refreshIndex() {
    if (s.busy) return;
    s.busy = true;
    draw();
    try {
      if (s.root && s.device === "local")
        await agent.reindex_root({ root_id: s.root.root_id });
      await load();
    } catch (e) {
      s.error = e.message;
      s.busy = false;
      clearContent();
      draw();
    }
  }
  async function expand(root, entry) {
    const device = s.device,
      seq = s.sequence;
    const key = `${root.root_id}:${entry.object_id}`;
    if (s.expanded.has(key)) {
      s.expanded.delete(key);
      draw();
      return;
    }
    try {
      const page = await query({
        kind: "browse",
        root_id: root.root_id,
        directory_id: entry.object_id,
        cursor: null,
        sort: "name",
      });
      if (!s.alive || device !== s.device || seq !== s.sequence) return;
      s.expanded.set(key, {
        entries: page.entries.filter((e) => e.kind === "directory"),
        more: Boolean(page.next_cursor),
      });
      draw();
    } catch (e) {
      toast(e.message, "error");
    }
  }
  function treeNode(root, entry, depth = 0) {
    if (depth >= 32)
      return h("small", { class: "tree-hint" }, "Maximum tree depth reached.");
    const key = `${root.root_id}:${entry.object_id}`;
    const expanded = s.expanded.get(key);
    return h(
      "div",
      { class: "folder-branch", style: `--depth:${depth}` },
      h(
        "div",
        { class: "folder-branch-row" },
        iconButton(
          expanded ? "chevronDown" : "chevron",
          `${expanded ? "Collapse" : "Expand"} ${entry.name}`,
          () => expand(root, entry),
          { "aria-expanded": String(Boolean(expanded)) },
        ),
        button(entry.name, () => chooseFolder(root, entry.object_id), {
          kind: "text",
          leading: "folder",
          className: s.directory === entry.object_id ? "selected-folder" : "",
        }),
      ),
      expanded &&
        h(
          "div",
          {},
          expanded.entries.map((e) => treeNode(root, e, depth + 1)),
          expanded.more &&
            h(
              "small",
              { class: "tree-hint" },
              "Open this folder for its paginated list.",
            ),
        ),
    );
  }
  function separator(side) {
    const el = h("div", {
      class: "file-resizer",
      role: "separator",
      tabindex: "0",
      "aria-orientation": "vertical",
      "aria-label":
        side === "tree" ? "Resize folder tree" : "Resize file details",
      "aria-valuemin": side === "tree" ? 185 : 240,
      "aria-valuemax": side === "tree" ? 310 : 400,
      "aria-valuenow": s.layout[side],
      "data-file-focus": `resize-${side}`,
    });
    const apply = (value) => {
      s.layout[side] = Math.max(
        side === "tree" ? 185 : 240,
        Math.min(side === "tree" ? 310 : 400, value),
      );
      const panes = view.querySelector(".file-panes");
      panes?.style.setProperty(`--${side}-width`, `${s.layout[side]}px`);
      el.setAttribute("aria-valuenow", s.layout[side]);
      localStorage.setItem("narada.file-layout", JSON.stringify(s.layout));
    };
    el.addEventListener("keydown", (e) => {
      if (["ArrowLeft", "ArrowRight"].includes(e.key)) {
        e.preventDefault();
        apply(
          s.layout[side] +
            (e.key === "ArrowRight" ? 12 : -12) * (side === "tree" ? 1 : -1),
        );
      }
    });
    el.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      pointerCleanup?.();
      const x = e.clientX,
        start = s.layout[side];
      const move = (v) =>
        apply(start + (v.clientX - x) * (side === "tree" ? 1 : -1));
      const stop = () => {
        window.removeEventListener("pointermove", move);
        window.removeEventListener("pointerup", stop);
        pointerCleanup = null;
      };
      pointerCleanup = stop;
      window.addEventListener("pointermove", move);
      window.addEventListener("pointerup", stop, { once: true });
    });
    return el;
  }
  function inspector() {
    const entry = s.selected;
    return h(
      "aside",
      { class: "file-inspector", "aria-label": "File details" },
      h(
        "span",
        { class: "inspector-title" },
        s.trash ? "RETAINED ITEM" : "FILE DETAILS",
      ),
      !entry
        ? h(
            "div",
            { class: "file-inspector-empty" },
            icon("file", 39),
            h("h3", {}, "A closer look"),
            h(
              "p",
              {},
              "Select an item to inspect its actual metadata and permitted actions.",
            ),
          )
        : h(
            "div",
            {},
            h(
              "div",
              { class: "selected-file-icon" },
              icon(entry.kind === "directory" ? "folder" : "file", 35),
            ),
            h("h2", { class: "selected-file-name" }, entry.name),
            badge(entry.kind === "directory" ? "Folder" : "File"),
            h(
              "dl",
              { class: "file-detail-list" },
              h("dt", {}, "Location"),
              h("dd", {}, entry.relative_path || entry.original_path),
              h("dt", {}, "Size"),
              h(
                "dd",
                {},
                entry.kind === "directory"
                  ? "Directory"
                  : `${bytes(Number(entry.size))} · ${entry.size} bytes`,
              ),
              h("dt", {}, s.trash ? "Retained until at least" : "Modified"),
              h(
                "dd",
                {},
                s.trash
                  ? new Date(entry.retain_until).toLocaleString()
                  : new Date(entry.modified).toLocaleString(),
              ),
              h("dt", {}, "Version"),
              h("dd", { class: "file-version" }, entry.version),
            ),
            !s.trash &&
              entry.kind === "file" &&
              h(
                "div",
                { class: "file-preview" },
                s.preview?.error
                  ? h("p", { role: "status" }, s.preview.error)
                  : s.preview
                    ? h("img", {
                        src: `data:image/jpeg;base64,${s.preview.data}`,
                        alt: `Re-encoded preview of ${entry.name}`,
                        width: s.preview.width,
                        height: s.preview.height,
                      })
                    : button("Load image preview", preview, {
                        leading: "eye",
                        disabled:
                          s.busy || !can("read") || !entry.preview_candidate,
                      }),
                h(
                  "small",
                  {},
                  "PNG/JPEG only · source ≤5 MiB · 256 px output · metadata stripped",
                ),
              ),
            h(
              "div",
              { class: "file-action-stack" },
              s.trash
                ? button("Restore item", () => mutationDialog("restore"), {
                    leading: "back",
                    disabled: s.busy || !can("trash") || !can("create"),
                  })
                : [
                    entry.kind === "directory" &&
                      button(
                        "Open folder",
                        () => chooseFolder(s.root, entry.object_id),
                        { leading: "folder" },
                      ),
                    button("Rename", () => mutationDialog("rename"), {
                      leading: "edit",
                      disabled: s.busy || !can("modify"),
                    }),
                    button("Move to trash", () => mutationDialog("trash"), {
                      leading: "trash",
                      disabled: s.busy || !can("trash"),
                    }),
                    entry.kind === "file" &&
                      button(
                        s.device === "local"
                          ? "Send to device"
                          : "Download file",
                        () =>
                          openTransferDialog(ctx, {
                            mode: s.device === "local" ? "upload" : "download",
                            device: s.device,
                            root: s.root,
                            entry,
                          }),
                        {
                          leading:
                            s.device === "local" ? "transfer" : "download",
                          disabled:
                            s.busy ||
                            !can("read") ||
                            Number(entry.size) > 67108864,
                        },
                      ),
                    entry.kind === "file" &&
                      s.device !== "local" &&
                      button(
                        "Keep offline",
                        () =>
                          pinDialog(ctx, {
                            device: s.device,
                            root: s.root,
                            entry,
                          }),
                        {
                          leading: "pin",
                          disabled:
                            s.busy ||
                            !can("read") ||
                            !can("sync_read") ||
                            Number(entry.size) > 67108864,
                        },
                      ),
                    entry.kind === "file" &&
                      s.device === "local" &&
                      button(
                        "Attach in Chat",
                        () => {
                          selectChatAttachment({
                            name: entry.name,
                            size: entry.size,
                            source: {
                              root_id: s.root.root_id,
                              object_id: entry.object_id,
                              expected_version: entry.version,
                            },
                          });
                          ctx.navigate("chat");
                        },
                        {
                          leading: "chat",
                          disabled: s.busy || Number(entry.size) > 8388608,
                        },
                      ),
                    entry.kind === "file" &&
                      s.device === "local" &&
                      button(
                        "Offer for download",
                        () =>
                          openTransferDialog(ctx, {
                            mode: "offer",
                            device: s.device,
                            root: s.root,
                            entry,
                          }),
                        {
                          leading: "link",
                          disabled: s.busy || Number(entry.size) > 67108864,
                        },
                      ),
                    entry.kind === "file" &&
                      Number(entry.size) > 67108864 &&
                      h(
                        "small",
                        { class: "transfer-scope-note" },
                        "Transfers currently support files up to 64 MiB.",
                      ),
                  ],
            ),
          ),
      h("div", { class: "inspector-rule" }),
      h(
        "p",
        { class: "inspector-safe" },
        icon("shield", 15),
        "Only current root grants. No arbitrary paths.",
      ),
    );
  }
  function draw() {
    if (!s.alive) return;
    const focused = body.contains(document.activeElement)
      ? document.activeElement?.getAttribute("data-file-focus")
      : null;
    const device = h(
      "select",
      {
        class: "file-device-select",
        "aria-label": "File source",
        value: s.device,
        onchange: (e) => {
          s.device = e.target.value;
          lastDevice = s.device;
          s.root = null;
          s.requestedRootId = lastRoots.get(s.device) || null;
          s.notice = "";
          s.directory = null;
          s.trash = false;
          s.search = "";
          clearContent();
          load();
        },
      },
      h("option", { value: "local" }, "This desktop"),
      s.device !== "local" &&
        !peers().some((p) => p.peer_id === s.device) &&
        h(
          "option",
          { value: s.device, disabled: true },
          "Previously selected device — unavailable",
        ),
      peers().map((p) => h("option", { value: p.peer_id }, p.name)),
    );
    device.value = s.device;
    const search = h("input", {
      class: "file-search-input",
      type: "search",
      "aria-label": "Search file names",
      placeholder: "Search this folder’s catalog…",
      value: s.search,
      disabled: !s.root || s.busy || s.trash,
    });
    const form = h(
      "form",
      {
        class: "file-search",
        onsubmit: (e) => {
          e.preventDefault();
          s.search = search.value.trim();
          s.selected = null;
          s.preview = null;
          load();
        },
      },
      icon("search", 15),
      search,
    );
    const sort = h(
      "select",
      {
        class: "file-sort",
        "aria-label": "Sort files",
        disabled: !s.root || s.busy || s.trash,
        onchange: (e) => {
          s.sort = e.target.value;
          load();
        },
      },
      [
        ["name", "Name A–Z"],
        ["name_desc", "Name Z–A"],
        ["modified", "Newest modified"],
        ["size", "Largest first"],
      ].map(([v, t]) => h("option", { value: v }, t)),
    );
    sort.value = s.sort;
    const controls = h(
      "div",
      { class: "button-group" },
      s.device === "local" &&
        button("Add folder", addRoot, {
          leading: "plus",
          kind: "primary",
          disabled: !s.online || s.busy || s.roots.length >= 8,
        }),
    );
    const tree = h(
      "aside",
      { class: "folder-tree", "aria-label": "Approved folder tree" },
      h(
        "div",
        { class: "tree-label" },
        s.device === "local"
          ? "FOLDERS ON THIS COMPUTER"
          : "FOLDERS SHARED WITH YOU",
        badge(s.roots.length),
      ),
      s.roots.map((r) =>
        h(
          "div",
          { class: "root-branch" },
          button(r.label, () => chooseFolder(r), {
            kind: "text",
            leading: "folder",
            className: `root-node ${s.root?.root_id === r.root_id ? "selected-folder" : ""}`,
          }),
          s.root?.root_id === r.root_id &&
            !s.trash &&
            (s.topFolders.get(r.root_id)?.entries || []).map((e) =>
              treeNode(r, e),
            ),
          s.root?.root_id === r.root_id &&
            !s.trash &&
            s.topFolders.get(r.root_id)?.more &&
            h(
              "small",
              { class: "tree-hint" },
              "Open the root for its complete paginated list.",
            ),
          r.state !== "ready" && badge(r.state, "warning"),
        ),
      ),
      h(
        "p",
        { class: "tree-hint" },
        s.device === "local"
          ? "Adding a folder does not share it. Select it, then review Device access. Chat works without any folders."
          : "Only folders this device is currently allowed to browse appear.",
      ),
      s.root &&
        button(
          "Managed trash",
          () => {
            s.trash = true;
            s.search = "";
            s.directory = null;
            s.selected = null;
            s.preview = null;
            load();
          },
          { kind: "text", leading: "trash", disabled: !can("trash") },
        ),
      s.root &&
        s.device === "local" &&
        h(
          "div",
          { class: "root-owner-controls" },
          button("Device access", shareDialog, {
            leading: "shield",
            kind: "text",
          }),
          button("Folder policy", policyDialog, {
            leading: "settings",
            kind: "text",
          }),
        ),
    );
    const list = s.error
      ? h(
          "div",
          { class: "file-empty file-error", role: "alert" },
          icon("alert", 32),
          h("h2", {}, "Access needs attention."),
          h("p", {}, s.error),
          button("Refresh first page", () => load(), {
            leading: "sync",
            disabled: s.busy,
          }),
        )
      : !s.root
        ? h(
            "div",
            { class: "file-empty" },
            h("div", { class: "stacked-folder" }, icon("folder", 46)),
            h("span", { class: "eyebrow" }, "NOTHING SHARED BY DEFAULT"),
            h(
              "h2",
              {},
              s.busy
                ? "Loading your folders…"
                : s.requestedRootId
                  ? "The selected folder is unavailable."
                  : s.device === "local"
                    ? "A folder. Chosen by you."
                    : "No folders granted yet.",
            ),
            h(
              "p",
              {},
              s.busy
                ? "Checking the selected device’s current folders and permissions."
                : s.requestedRootId
                  ? s.notice
                  : s.device === "local"
                    ? "Register a specific folder on the agent host. Browse it locally, then choose whether a paired device gets access."
                    : "Ask this device’s owner to grant a root in Files → Device access. Pairing alone grants no files.",
            ),
            s.device === "local" &&
              !s.requestedRootId &&
              button("Add your first folder", addRoot, {
                kind: "primary",
                leading: "plus",
                disabled: !s.online || s.busy,
              }),
          )
        : !s.entries.length
          ? h(
              "div",
              { class: "file-empty" },
              icon(s.trash ? "trash" : "folder", 36),
              h(
                "h2",
                {},
                s.busy
                  ? "Loading the real catalog…"
                  : s.trash
                    ? "Nothing retained here."
                    : s.search
                      ? "No matching filenames."
                      : "No visible entries here.",
              ),
              h(
                "p",
                {},
                s.root.state !== "ready"
                  ? `Index status: ${s.root.state}. Ask the owner to refresh or review its policy.`
                  : "Hidden, excluded, unsafe and changed entries are not shown.",
              ),
            )
          : h(
              "div",
              {
                class: s.grid ? "file-grid" : "file-row-list",
                role: "list",
                "aria-label": s.trash ? "Retained items" : "Files and folders",
              },
              s.entries.map((e) =>
                h(
                  "div",
                  { role: "listitem" },
                  h(
                    "button",
                    {
                      class: `file-row ${s.selected?.object_id === e.object_id ? "selected" : ""}`,
                      type: "button",
                      "data-file-focus": `${s.device}:${s.root.root_id}:${e.object_id}`,
                      "aria-label": `${s.trash ? "Retained " : ""}${e.name}`,
                      "aria-pressed": String(
                        s.selected?.object_id === e.object_id,
                      ),
                      onclick: () => select(e),
                      ondblclick: () => {
                        if (e.kind === "directory" && !s.trash)
                          chooseFolder(s.root, e.object_id);
                      },
                    },
                    h(
                      "span",
                      { class: "file-row-name" },
                      h(
                        "span",
                        { class: `file-kind-icon ${e.kind}` },
                        icon(e.kind === "directory" ? "folder" : "file", 22),
                      ),
                      h(
                        "span",
                        {},
                        h("strong", {}, e.name),
                        h(
                          "small",
                          {},
                          s.search
                            ? e.relative_path
                            : e.kind === "directory"
                              ? "Folder"
                              : e.preview_candidate
                                ? "Image · preview available"
                                : "File",
                        ),
                      ),
                    ),
                    h(
                      "span",
                      { class: "file-row-size" },
                      e.kind === "directory" ? "—" : bytes(Number(e.size)),
                    ),
                    h(
                      "span",
                      { class: "file-row-date" },
                      relativeTime(s.trash ? e.trashed_at : e.modified),
                    ),
                  ),
                ),
              ),
            );
    const workspace = h(
      "section",
      { class: "file-workspace" },
      h(
        "div",
        { class: "file-toolbar" },
        iconButton(
          "home",
          "Open root folder",
          () => {
            if (s.root) chooseFolder(s.root);
          },
          { disabled: !s.root || s.busy },
        ),
        h(
          "div",
          {
            class: "file-breadcrumbs",
            role: "navigation",
            "aria-label": "Folder breadcrumb",
          },
          (s.trash
            ? [{ name: "Managed trash", object_id: null }]
            : s.crumbs
          ).map((c, i) =>
            h(
              "span",
              {},
              i > 0 && icon("chevron", 11),
              button(c.name, () => chooseFolder(s.root, c.object_id), {
                kind: "text",
                disabled: s.busy || s.trash,
              }),
            ),
          ),
        ),
        h("span", { class: "toolbar-spacer" }),
        iconButton("sync", "Refresh file index", refreshIndex, {
          disabled: !s.online || s.busy,
        }),
        h(
          "div",
          { class: "view-mode" },
          iconButton(
            "list",
            "List view",
            () => {
              s.grid = false;
              draw();
            },
            { "aria-pressed": String(!s.grid) },
          ),
          iconButton(
            "grid",
            "Grid view",
            () => {
              s.grid = true;
              draw();
            },
            { "aria-pressed": String(s.grid) },
          ),
        ),
      ),
      h("div", { class: "file-filterbar" }, device, form, sort),
      h(
        "div",
        {
          class: "file-panes",
          style: `--tree-width:${s.layout.tree}px;--inspector-width:${s.layout.inspector}px`,
        },
        tree,
        separator("tree"),
        h(
          "div",
          { class: "file-table-area" },
          !s.grid &&
            h(
              "div",
              { class: "file-columns" },
              h("span", {}, "Name"),
              h("span", {}, "Size"),
              h("span", {}, s.trash ? "Trashed" : "Modified"),
            ),
          list,
          s.root &&
            h(
              "div",
              { class: "file-pagination" },
              h(
                "span",
                {},
                `${s.entries.length} visible · page ${s.history.length + 1}`,
              ),
              button(
                "Previous",
                () => {
                  s.cursor = s.history.pop() ?? null;
                  load(false);
                },
                { disabled: !s.history.length || s.busy },
              ),
              button(
                "Next",
                () => {
                  s.history.push(s.cursor);
                  s.cursor = s.next;
                  load(false);
                },
                { disabled: !s.next || s.busy },
              ),
            ),
        ),
        separator("inspector"),
        inspector(),
      ),
    );
    replace(
      body,
      pageHeader(
        "YOUR LOCAL LIBRARY",
        "Files",
        "Your approved folders. Nothing beyond them.",
        controls,
      ),
      h(
        "div",
        { class: "phase-note" },
        badge("OPTIONAL FOLDER SHARING"),
        h(
          "span",
          {},
          "Chat needs no folder. Added folders stay private until you grant access.",
        ),
      ),
      workspace,
      h(
        "div",
        { class: "inline-note file-status", role: "status" },
        icon(s.busy ? "clock" : "shield", 15),
        s.busy
          ? "Working…"
          : s.error
            ? "Content was cleared because it could not be reauthorized."
            : s.root
              ? `${s.root.entry_count} indexed · ${s.root.omitted_count} omitted · ${s.root.state}${s.root.error_code ? ` (${s.root.error_code})` : ""}. ${s.trash ? s.notice : "Hidden files and unsafe objects are excluded."}`
              : "No folder is selected. Choose one from the list; pairing alone grants no file access.",
      ),
    );
    if (focused)
      body
        .querySelector(`[data-file-focus="${CSS.escape(focused)}"]`)
        ?.focus({ preventScroll: true });
  }
  view.updateFileConnection = (snapshot, online) => {
    const previous = s.snapshot?.connections?.peers?.find(
      (p) => p.peer_id === s.device,
    );
    s.snapshot = snapshot;
    s.online = online;
    const current = s.snapshot?.connections?.peers?.find(
      (p) => p.peer_id === s.device,
    );
    if (
      online &&
      s.device !== "local" &&
      current &&
      JSON.stringify(previous?.remote_permissions) !==
        JSON.stringify(current.remote_permissions)
    ) {
      s.sequence++;
      clearContent();
      s.roots = [];
      s.root = null;
      load();
      return;
    }
    if (
      !online ||
      (s.device !== "local" &&
        (!snapshot?.connections?.config?.enabled ||
          !peers().some((p) => p.peer_id === s.device)))
    ) {
      s.sequence++;
      s.busy = false;
      s.error = online
        ? "The selected peer is no longer active."
        : "The agent is offline. Content was cleared; reconnect and refresh.";
      clearContent();
      s.roots = [];
      s.root = null;
      draw();
    }
  };
  view.dispose = () => {
    s.alive = false;
    s.sequence++;
    clearInterval(timer);
    pointerCleanup?.();
    s.preview = null;
    s.entries = [];
    s.selected = null;
    s.expanded.clear();
    s.topFolders.clear();
  };
  draw();
  load();
  timer = setInterval(() => {
    if (
      s.alive &&
      s.online &&
      !s.busy &&
      !pointerCleanup &&
      !document.hidden &&
      !document.querySelector("dialog[open]") &&
      !view.querySelector("input:focus,select:focus,textarea:focus") &&
      !s.cursor
    )
      load(false, true);
  }, 5000);
  return view;
}
