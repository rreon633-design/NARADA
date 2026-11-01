import { h, replace } from "../lib/dom.js";
import { button, modal, toast } from "./ui.js";
import { agent } from "../services/bridge.js";
import { bytes } from "../lib/format.js";
import {
  MAX_FILE_BYTES,
  eligibleRecipients,
  validateDestination,
} from "../lib/transfers.js";
export async function openTransferDialog(
  ctx,
  { mode, device, root, entry, offer },
) {
  const isOffer = mode === "offer",
    accept = mode === "accept",
    download = mode === "download" || accept;
  const name = offer?.manifest?.name || entry?.name;
  const size = Number(offer?.manifest?.size ?? entry?.size);
  if (!Number.isSafeInteger(size) || size < 0 || size > MAX_FILE_BYTES)
    return toast(
      "This development slice supports ordinary files up to 64 MiB.",
      "error",
    );
  const peerOptions = eligibleRecipients(
    ctx.snapshot?.connections?.peers,
    root?.root_id,
    isOffer,
  );
  const peers = h(
    "select",
    { class: "field", "aria-label": "Recipient device" },
    h("option", { value: "" }, "Choose a device"),
    peerOptions.map((p) => h("option", { value: p.peer_id }, p.name)),
  );
  const roots = h(
    "select",
    { class: "field", "aria-label": "Destination folder", disabled: true },
    h("option", { value: "" }, "Loading approved folders…"),
  );
  const filename = h("input", {
    class: "field",
    value: name,
    "aria-label": "Destination filename",
    autocomplete: "off",
    spellcheck: "false",
  });
  const status = h("p", { class: "transfer-dialog-status", role: "status" });
  let available = [],
    sequence = 0,
    busy = false,
    dialog;
  const peerId = () =>
    accept ? offer.peer_id : download ? device : peers.value;
  const transferId = offer?.transfer_id || crypto.randomUUID();
  const source = entry && {
    root_id: root.root_id,
    object_id: entry.object_id,
    expected_version: entry.version,
  };
  const submit = button(
    isOffer
      ? "Create pull offer"
      : accept
        ? "Accept & download"
        : download
          ? "Start download"
          : "Queue for approval",
    async () => {
      if (busy) return;
      try {
        const peer_id = peerId();
        if (!peer_id) throw new Error("Choose a recipient device first.");
        const destination = isOffer
          ? null
          : validateDestination(
              available.find((r) => r.root_id === roots.value),
              filename.value,
            );
        busy = true;
        submit.disabled = true;
        status.textContent = "Saving the reviewed transfer…";
        if (isOffer)
          await agent.create_transfer_offer({
            transfer_id: transferId,
            peer_id,
            source,
          });
        else if (accept)
          await agent.accept_transfer_offer({
            transfer_id: transferId,
            peer_id,
            destination,
          });
        else
          await agent.start_transfer({
            transfer_id: transferId,
            peer_id,
            direction: download ? "download" : "upload",
            source,
            destination,
          });
        dialog.close();
        toast(
          isOffer
            ? "Pull offer created. The recipient can check for offers and choose a destination."
            : "Transfer queued. Progress will reflect durable receiver checkpoints.",
        );
        ctx.navigate("transfers");
      } catch (e) {
        status.textContent = e.message;
        status.classList.add("is-error");
      } finally {
        busy = false;
        if (dialog.dialog.isConnected) submit.disabled = false;
      }
    },
    { kind: "primary", leading: isOffer ? "link" : "transfer", disabled: true },
  );
  async function loadRoots() {
    const current = ++sequence;
    if (isOffer) {
      submit.disabled = !peers.value;
      return;
    }
    roots.disabled = true;
    submit.disabled = true;
    available = [];
    if (!peerId()) {
      replace(roots, h("option", { value: "" }, "Choose a device first"));
      return;
    }
    status.textContent = "Loading currently approved destinations…";
    try {
      const result = download
        ? await agent.list_roots()
        : await agent.remote_file_query({
            peer_id: peerId(),
            query: { kind: "roots" },
          });
      if (current !== sequence || !dialog.dialog.isConnected) return;
      available = result.roots.filter(
        (r) =>
          r.capabilities?.includes("browse") &&
          r.capabilities?.includes("create") &&
          r.state !== "unavailable",
      );
      replace(
        roots,
        h(
          "option",
          { value: "" },
          available.length
            ? "Choose an approved folder"
            : "No writable approved folders",
        ),
        available.map((r) => h("option", { value: r.root_id }, r.label)),
      );
      roots.disabled = !available.length;
      status.textContent = available.length
        ? "The file will be saved at the top level of the selected folder. Existing names are never overwritten."
        : download
          ? "Add an approved local folder in Files first. Nothing has been queued."
          : "The receiver must grant Browse + Create for an approved folder. This dialog never changes permissions.";
      submit.disabled = !roots.value;
    } catch (e) {
      if (current === sequence) {
        status.textContent = e.message;
        status.classList.add("is-error");
      }
    }
  }
  roots.addEventListener("change", () => {
    submit.disabled = busy || !roots.value;
  });
  peers.addEventListener("change", loadRoots);
  dialog = modal({
    title: isOffer
      ? "Offer a file"
      : accept
        ? "Accept this file?"
        : download
          ? "Download a verified copy"
          : "Send a verified copy",
    eyebrow: "REVIEW THIS TRANSFER",
    className: "transfer-dialog",
    content: h(
      "div",
      { class: "transfer-dialog-grid" },
      h(
        "div",
        { class: "transfer-review-file" },
        h("strong", {}, name),
        h("span", {}, `${bytes(size)} · ordinary file · no automatic opening`),
      ),
      !download &&
        h("label", { class: "transfer-field" }, "Recipient device", peers),
      !isOffer &&
        h("label", { class: "transfer-field" }, "Approved destination", roots),
      !isOffer && h("label", { class: "transfer-field" }, "Save as", filename),
      h(
        "div",
        { class: "transfer-consent-note" },
        isOffer
          ? "Only recipients with an existing Browse + Read grant are listed. The recipient chooses where to save the file and pulls it over their saved connection. This offer grants no new folder access."
          : download
            ? "You are approving a copy into this local folder. SHA-256 and size are verified before no-overwrite publication. Archives stay inert."
            : "The receiver must approve the filename before any bytes are staged. Upload permission alone does not accept this offer. Existing files will not be replaced.",
      ),
      isOffer &&
        !peerOptions.length &&
        h(
          "p",
          { class: "transfer-scope-note" },
          "No eligible recipient. Review the selected folder’s Read grant in Devices first.",
        ),
      status,
    ),
    actions: [button("Not now", () => dialog.close()), submit],
  });
  if (download) await loadRoots();
  else if (!isOffer) {
    replace(roots, h("option", { value: "" }, "Choose a device first"));
    status.textContent =
      "Choose a device with a saved outbound endpoint, or use a pull offer for an inbound-only peer.";
  }
}
