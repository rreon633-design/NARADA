import { h } from "../lib/dom.js";
import { agent } from "../services/bridge.js";
import { button, modal, toast } from "./ui.js";
import { contentHint } from "../lib/content.js";
export async function destinationDialog(
  ctx,
  { title, name, submit, locked = null },
) {
  let dialog;
  const error = h("p", { class: "form-error", role: "alert" });
  const roots = h("select", {
    class: "text-input",
    "aria-label": "Local destination folder",
    disabled: Boolean(locked),
  });
  const filename = h("input", {
    class: "text-input",
    value: locked?.name || name,
    maxlength: 180,
    "aria-label": "Destination filename",
    disabled: Boolean(locked),
  });
  const confirm = button(
    "Save verified copy",
    async () => {
      confirm.disabled = true;
      error.textContent = "";
      try {
        const destination = locked || {
          root_id: roots.value,
          directory_id: null,
          name: filename.value.trim(),
        };
        if (!destination.root_id || !destination.name)
          throw Error("Choose a registered folder and filename.");
        await submit(destination);
        dialog.close();
        toast(
          "The agent recorded the request. Its actual result is shown in the workspace.",
        );
        await ctx.refresh(false);
      } catch (e) {
        error.textContent = `${e.message} ${contentHint(e.code)}`;
        confirm.disabled = false;
      }
    },
    { kind: "primary", disabled: true, leading: "download" },
  );
  dialog = modal({
    title,
    eyebrow: "EXPLICIT LOCAL DESTINATION",
    content: h(
      "div",
      { class: "content-form" },
      h(
        "p",
        { class: "muted" },
        "Only a registered local folder is eligible. Existing files are never overwritten; nothing is opened or executed automatically.",
      ),
      h("label", {}, h("span", {}, "Approved folder"), roots),
      h("label", {}, h("span", {}, "Filename"), filename),
      locked &&
        h(
          "p",
          { class: "small muted" },
          "An export intent already exists. Retry its original destination to recover the receipt; do not guess a different outcome.",
        ),
      error,
    ),
    actions: [button("Cancel", () => dialog.close()), confirm],
  });
  try {
    const response = await agent.list_roots();
    for (const root of response.roots || [])
      if (!["blocked", "indexing"].includes(root.state))
        roots.append(h("option", { value: root.root_id }, root.label));
    if (locked) roots.value = locked.root_id;
    confirm.disabled = !roots.value || !ctx.connected;
    if (!roots.value)
      error.textContent = "Register an eligible local folder in Files first.";
  } catch (e) {
    error.textContent = e.message;
  }
  return dialog;
}
export function pinDialog(ctx, { device, root, entry }) {
  const pin_id = crypto.randomUUID();
  return destinationDialog(ctx, {
    title: "Keep a version offline",
    name: entry.name,
    submit: async (destination) => {
      await agent.create_pin({
        pin_id,
        peer_id: device,
        source: {
          root_id: root.root_id,
          object_id: entry.object_id,
          expected_version: entry.version,
        },
        destination,
      });
      ctx.navigate("sync");
    },
  });
}
export function exportAttachmentDialog(ctx, message) {
  const operation_id =
    message.attachment.export_operation_id || crypto.randomUUID();
  return destinationDialog(ctx, {
    title: "Export authenticated attachment",
    name: message.attachment.name,
    locked: message.attachment.export_destination || null,
    submit: (destination) =>
      agent.export_attachment({
        message_id: message.message_id,
        operation_id,
        destination,
      }),
  });
}
export function contentDetails(title, data) {
  return modal({
    title,
    eyebrow: "ACTUAL SAVED STATE",
    content: h(
      "div",
      { class: "content-details" },
      Object.entries(data).map(([name, value]) =>
        h(
          "div",
          {},
          h("strong", {}, name),
          h(
            "span",
            {},
            typeof value === "object"
              ? JSON.stringify(value, null, 2)
              : String(value ?? "—"),
          ),
        ),
      ),
    ),
  });
}
