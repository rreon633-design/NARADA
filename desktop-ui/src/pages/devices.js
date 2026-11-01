import QRCode from "qrcode";
import { h, replace } from "../lib/dom.js";
import { icon } from "../components/icons.js";
import {
  badge,
  button,
  modal,
  pageHeader,
  panelHead,
  toast,
  toggle,
} from "../components/ui.js";
import { agent } from "../services/bridge.js";
import { platformName, relativeTime } from "../lib/format.js";
import {
  parsePairingCode,
  decodePairingImage,
  fingerprintText,
} from "../lib/pairing.js";

const NO_CONTENT = { chat: false, attachment: false, roots: [] };
const liveJoin = (j) =>
  j &&
  ["connecting", "proving", "awaiting_approval", "finalizing"].includes(
    j.state,
  );
const labels = {
  online: "Connected",
  connecting: "Connecting",
  retrying: "Retrying",
  retry_stopped: "Retry paused",
  blocked: "Needs attention",
  revoked: "Revoked",
  offline: "Offline",
};
const watchers = new Set();
let invitationSecret = null;
export function clearPairingSecrets() {
  invitationSecret = null;
}
function watch(dialog, fn) {
  watchers.add(fn);
  dialog.dialog.addEventListener("close", () => watchers.delete(fn), {
    once: true,
  });
}
function note(text, glyph = "info") {
  return h(
    "div",
    { class: "inline-note" },
    icon(glyph, 16),
    h("span", {}, text),
  );
}
function fp(label, value) {
  return h(
    "div",
    { class: "fingerprint-block" },
    h("span", { class: "eyebrow" }, label),
    h("code", {}, fingerprintText(value)),
  );
}
function errorBox() {
  return h("p", {
    class: "connection-form-error",
    role: "alert",
    hidden: true,
  });
}
function showError(node, e) {
  node.hidden = false;
  node.textContent = e.message;
}
async function action(ctx, fn, message) {
  try {
    await fn();
    await ctx.refresh(false);
    if (message) toast(message);
  } catch (e) {
    toast(e.message, "error");
  }
}

export function devices(ctx) {
  const local = h("section", { class: "panel this-device identity-panel" });
  const network = h("section", { class: "panel network-panel" });
  const requests = h("div", { class: "pairing-requests" });
  const peers = h("section", { class: "panel peers-panel" });
  const discovery = h("section", { class: "panel discovery-panel" });
  const headerActions = h("div", { class: "button-group" });
  let lastLocal,
    lastNetwork,
    lastPeers,
    lastRequests,
    lastDiscovery,
    lastActions;
  const page = h(
    "div",
    { class: "page devices-page" },
    pageHeader(
      "PERSONAL CONNECTIONS",
      "Your devices",
      "Good connections start with trust. Yours to approve. Yours to revoke.",
      headerActions,
    ),
    h(
      "div",
      { class: "connection-scope-note" },
      badge("DESKTOP · PHASE 02"),
      h(
        "span",
        {},
        "Real pairing. No accounts or internet required. Files are available only through explicit root grants; chat and Android remain later phases.",
      ),
    ),
    h(
      "div",
      { class: "connections-layout" },
      h("aside", { class: "identity-column" }, local, network),
      h("div", { class: "connections-main" }, requests, peers, discovery),
    ),
    h(
      "div",
      { class: "device-trust-note" },
      h("div", { class: "privacy-icon small" }, icon("shield", 22)),
      h(
        "div",
        {},
        h("h3", {}, "Finding a device is not trusting it."),
        h(
          "p",
          {},
          "A one-time code pins the identity. Mutual TLS proves the private key. Only your explicit approval establishes trust. Every device starts with no file, chat or attachment access.",
        ),
      ),
    ),
  );
  function update(snapshot) {
    const c = snapshot?.connections;
    if (!c) {
      replace(
        local,
        h("p", { class: "muted" }, "Waiting for the connection service…"),
      );
      return;
    }
    if (
      invitationSecret &&
      invitationSecret.invitation_id !== c.invitation?.invitation_id
    )
      invitationSecret = null;
    const keyLocal = JSON.stringify([
      c.identity,
      snapshot.settings.device_name,
      snapshot.agent.platform,
    ]);
    if (keyLocal !== lastLocal) {
      lastLocal = keyLocal;
      replace(
        local,
        h(
          "div",
          { class: "identity-top" },
          h("span", { class: "device-avatar" }, icon("laptop", 29)),
          badge("This desktop"),
        ),
        h("h2", {}, snapshot.settings.device_name),
        h(
          "p",
          { class: "muted small" },
          `${platformName(snapshot.agent.platform)} · NARADA ${snapshot.agent.version}`,
        ),
        h(
          "div",
          { class: "identity-protection" },
          icon(c.identity.state === "ready" ? "lock" : "alert", 17),
          h("span", {}, c.identity.protection),
        ),
        c.identity.state === "ready"
          ? fp("THIS DESKTOP FINGERPRINT", c.identity.fingerprint)
          : note(
              c.identity.error?.message ||
                "Protected identity unavailable. Connections are blocked.",
              "alert",
            ),
        h(
          "p",
          { class: "identity-footnote" },
          c.identity.protection.includes("Development")
            ? "Loopback-only test vault, not native OS protection. Windows uses current-user DPAPI; native qualification remains open."
            : "Your private transport key stays outside metadata SQLite. Native Windows validation remains a separate release gate.",
        ),
        button("Workspace settings", () => ctx.navigate("settings"), {
          className: "full-width",
          trailing: "arrow",
        }),
      );
    }
    const keyNetwork = JSON.stringify([
      c.config,
      c.endpoint,
      c.error,
      c.listening,
      c.identity.state,
      ctx.connected,
    ]);
    if (keyNetwork !== lastNetwork) {
      lastNetwork = keyNetwork;
      replace(
        network,
        panelHead(
          "Connection controls",
          "Off unless you choose otherwise.",
          icon("sliders", 18),
        ),
        h(
          "div",
          { class: "network-state" },
          h("span", { class: `small-dot ${c.listening ? "" : "off"}` }),
          h(
            "strong",
            {},
            c.listening ? "Connections enabled" : "Connections disabled",
          ),
        ),
        c.endpoint && h("code", { class: "endpoint-text" }, c.endpoint),
        h(
          "p",
          { class: "small muted" },
          c.listening
            ? "TLS 1.3 · certificate pins · mutual authentication"
            : "No peer listener is open. Your saved device trust is preserved.",
        ),
        c.error && note(c.error.message, "alert"),
        button(
          c.listening ? "Network settings" : "Enable connections",
          () => configure(ctx),
          {
            kind: c.listening ? "secondary" : "primary",
            className: "full-width",
            leading: c.listening ? "settings" : "link",
            disabled: !ctx.connected || c.identity.state !== "ready",
          },
        ),
        c.listening &&
          button("Disable connections", () => disable(ctx), {
            kind: "text",
            className: "full-width",
          }),
      );
    }
    const keyActions = JSON.stringify([
      c.listening,
      Boolean(c.invitation),
      ctx.connected,
    ]);
    if (keyActions !== lastActions) {
      lastActions = keyActions;
      replace(
        headerActions,
        button("Join with a code", () => join(ctx), {
          leading: "link",
          disabled: !c.listening || !ctx.connected,
        }),
        button("Pair a device", () => createInvitation(ctx), {
          kind: "primary",
          leading: "plus",
          disabled: !c.listening || !ctx.connected || Boolean(c.invitation),
        }),
      );
    }
    const keyRequests = JSON.stringify([c.invitation, c.join, c.view_notice]);
    if (keyRequests !== lastRequests) {
      lastRequests = keyRequests;
      replace(
        requests,
        c.view_notice && note(c.view_notice),
        c.invitation &&
          h(
            "section",
            { class: "request-card" },
            h("div", { class: "request-mark" }, icon("shield", 22)),
            h(
              "div",
              { class: "request-copy" },
              h(
                "h2",
                {},
                c.invitation.pending
                  ? c.invitation.pending.approved
                    ? "Waiting for final confirmation"
                    : "A device is asking to connect"
                  : "Your invitation is open",
              ),
              h(
                "p",
                {},
                c.invitation.pending
                  ? `${c.invitation.pending.name} · ${c.invitation.pending.proof_verified ? "Private-key proof verified" : "Waiting for private-key proof"}`
                  : "One claim only. Expires after two minutes. It grants no access by itself.",
              ),
            ),
            h(
              "div",
              { class: "button-group" },
              c.invitation.pending &&
                button(
                  c.invitation.pending.approved ? "Approved" : "Review request",
                  () => review(ctx, c.invitation.pending),
                  {
                    kind: "primary",
                    disabled:
                      !c.invitation.pending.proof_verified ||
                      c.invitation.pending.approved,
                  },
                ),
              invitationSecret &&
                !c.invitation.pending &&
                button("View QR code", () => showInvitation(ctx)),
              button(
                "Cancel invitation",
                () =>
                  action(
                    ctx,
                    () =>
                      agent.cancel_pairing({
                        invitation_id: c.invitation.invitation_id,
                      }),
                    "Invitation cancelled.",
                  ),
                { kind: "text" },
              ),
            ),
          ),
        c.join &&
          h(
            "section",
            {
              class: `join-progress-card ${c.join.state === "failed" ? "has-error" : ""}`,
            },
            h(
              "div",
              { class: "request-mark" },
              icon(
                c.join.state === "paired"
                  ? "check"
                  : c.join.state === "failed"
                    ? "alert"
                    : "link",
                22,
              ),
            ),
            h(
              "div",
              { class: "request-copy" },
              h("h2", {}, joinTitle(c.join.state)),
              h(
                "p",
                {},
                c.join.error?.message ||
                  (liveJoin(c.join)
                    ? "On the owner desktop, compare this device’s fingerprint and approve the request."
                    : c.join.state === "paired"
                      ? "The peer confirmed durable trust. Content access is still disabled."
                      : "No new trust will be established by this attempt."),
              ),
              liveJoin(c.join) &&
                fp(
                  "SHOW THIS REQUESTER FINGERPRINT TO THE OWNER",
                  c.join.local_fingerprint,
                ),
            ),
            liveJoin(c.join) &&
              button("Cancel joining", () =>
                action(
                  ctx,
                  () => agent.cancel_join({ attempt_id: c.join.attempt_id }),
                  "Joining cancelled.",
                ),
              ),
          ),
      );
    }
    const keyPeers = JSON.stringify(
      c.peers.map((p) => [
        p.peer_id,
        p.state,
        p.connection_state,
        p.grant_revision,
        p.permissions,
        p.remote_permissions,
        p.last_error?.code,
      ]),
    );
    if (keyPeers !== lastPeers) {
      lastPeers = keyPeers;
      const active = c.peers.filter((p) => p.state === "active");
      replace(
        peers,
        panelHead(
          "Trusted devices",
          "Only devices you actually paired appear here.",
          badge(`${active.length} paired`),
        ),
        c.peers.length
          ? h(
              "div",
              { class: "peer-list" },
              c.peers.map((p) => peerCard(ctx, p)),
            )
          : h(
              "div",
              { class: "connections-empty" },
              h("div", { class: "empty-device-symbol" }, icon("devices", 43)),
              h("h2", {}, "A little room for connection."),
              h(
                "p",
                {},
                "Pair another desktop or a separate reference agent. No device is trusted just because it’s on your network.",
              ),
              h(
                "span",
                { class: "empty-caption" },
                c.listening
                  ? "CREATE AN INVITATION, OR JOIN WITH A CODE ABOVE"
                  : "ENABLE CONNECTIONS TO GET STARTED",
              ),
            ),
      );
    }
    const keyDiscovery = JSON.stringify(c.discovery);
    if (keyDiscovery !== lastDiscovery) {
      lastDiscovery = keyDiscovery;
      replace(
        discovery,
        panelHead(
          "Nearby, not yet trusted",
          "Optional mDNS · selected interface only",
          badge(
            c.discovery.state === "searching"
              ? "Discovering"
              : c.discovery.state === "degraded"
                ? "Manual fallback"
                : "Off",
          ),
        ),
        h("p", { class: "discovery-explainer" }, c.discovery.message),
        c.discovery.candidates.length
          ? h(
              "div",
              { class: "discovery-list" },
              c.discovery.candidates.map((candidate) =>
                h(
                  "div",
                  { class: "discovery-candidate" },
                  icon("devices", 18),
                  h(
                    "span",
                    {},
                    h("strong", {}, "Unverified device"),
                    h("code", {}, candidate.endpoint),
                  ),
                  badge("Pairing still required"),
                ),
              ),
            )
          : h(
              "p",
              { class: "discovery-empty" },
              "No discovery candidates. You can always use a one-time code or a saved peer’s manual endpoint.",
            ),
      );
    }
    for (const fn of watchers) fn(c);
  }
  page.updateConnections = update;
  update(ctx.snapshot);
  return page;
}
function peerCard(ctx, p) {
  return h(
    "article",
    {
      class: `peer-card ${p.state === "revoked" ? "peer-revoked" : ""}`,
      "data-peer-id": p.peer_id,
    },
    h(
      "div",
      { class: "peer-card-header" },
      h("span", { class: "peer-avatar" }, icon("laptop", 23)),
      h(
        "div",
        { class: "peer-name" },
        h("h3", {}, p.name),
        h(
          "p",
          {},
          `${platformName(p.platform)} · ${p.direction === "inbound" ? "Connects to this desktop" : "Pinned remote endpoint"}`,
        ),
      ),
      badge(
        ctx.connected
          ? labels[p.connection_state] || p.connection_state
          : "Status unavailable",
        p.connection_state === "online"
          ? "success"
          : ["blocked", "retry_stopped"].includes(p.connection_state)
            ? "warning"
            : "neutral",
      ),
    ),
    h(
      "div",
      { class: "peer-access" },
      icon("shield", 15),
      h(
        "span",
        {},
        p.state === "revoked"
          ? "Trust removed · connections denied"
          : p.state === "pending"
            ? "Awaiting durable confirmation · not active"
            : `${p.permissions.roots.length} shared roots · ${p.permissions.chat ? "Chat allowed" : "No chat access"} · ${p.permissions.attachment ? "Attachments allowed" : "No attachment access"}`,
      ),
    ),
    p.last_error && note(p.last_error.message, "alert"),
    h(
      "div",
      { class: "peer-card-footer" },
      h(
        "span",
        { class: "small muted" },
        p.last_seen
          ? `Last authenticated ${relativeTime(p.last_seen)}`
          : "No authenticated health exchange yet",
      ),
      h(
        "div",
        { class: "button-group" },
        button("Details & permissions", () => permissions(ctx, p), {
          kind: "text",
        }),
        p.state !== "revoked" &&
          p.direction === "outbound" &&
          p.connection_state !== "online" &&
          button(
            p.state === "pending" ? "Retry confirmation" : "Retry connection",
            () =>
              action(
                ctx,
                () => agent.reconnect_peer({ peer_id: p.peer_id }),
                "A new pinned connection attempt has started.",
              ),
            { leading: "sync" },
          ),
      ),
    ),
  );
}
function configure(ctx) {
  const c = ctx.snapshot.connections;
  const errors = errorBox();
  const select = h(
    "select",
    { id: "connection-interface", class: "connection-input" },
    c.interfaces.map((i) =>
      h(
        "option",
        { value: i.ip, disabled: !i.eligible },
        `${i.name} · ${i.ip} (${i.profile})`,
      ),
    ),
  );
  select.value = c.interfaces.some(
    (i) => i.eligible && i.ip === c.config.bind_ip,
  )
    ? c.config.bind_ip
    : c.interfaces.find((i) => i.eligible)?.ip || "";
  const port = h("input", {
    id: "connection-port",
    class: "connection-input",
    type: "number",
    min: 0,
    max: 65535,
    value: c.config.api_port,
  });
  const discover = h("input", {
    id: "connection-discovery",
    type: "checkbox",
    checked: c.config.discovery,
  });
  const changed = () => {
    discover.disabled = select.value.startsWith("127.");
    if (discover.disabled) discover.checked = false;
  };
  select.addEventListener("change", changed);
  changed();
  let dialog;
  const save = button(
    c.listening ? "Save network settings" : "Enable connections",
    async () => {
      const value = Number(port.value);
      if (
        !Number.isInteger(value) ||
        value < 0 ||
        value > 65535 ||
        !select.value
      )
        return showError(
          errors,
          Error("Choose an eligible interface and a port from 0 to 65535."),
        );
      save.disabled = true;
      try {
        await agent.configure_connections({
          enabled: true,
          bind_ip: select.value,
          api_port: value,
          discovery: discover.checked,
        });
        dialog.close();
        await ctx.refresh(false);
        toast("Protected connections enabled on the selected interface.");
      } catch (e) {
        showError(errors, e);
        save.disabled = false;
        await ctx.refresh(false);
      }
    },
    { kind: "primary", leading: "link" },
  );
  dialog = modal({
    title: "Choose where to connect.",
    eyebrow: "EXPLICIT NETWORK CONSENT",
    content: h(
      "div",
      { class: "connection-form" },
      note(
        "Only eligible local interfaces can be used. The Linux development preview is loopback-only. Windows LAN interfaces must be Private or Domain-authenticated.",
      ),
      h("label", { for: "connection-interface" }, "Network interface"),
      select,
      h("label", { for: "connection-port" }, "Listen port"),
      port,
      h(
        "p",
        { class: "small muted" },
        "Use 0 to select an available port. The selected port is saved for reconnects.",
      ),
      h(
        "label",
        { class: "connection-checkbox", for: "connection-discovery" },
        discover,
        h("span", {}, "Discover nearby devices"),
      ),
      h(
        "p",
        { class: "small muted" },
        "Discovery never establishes trust. Loopback tests use pairing codes instead.",
      ),
      note(
        "Changing this policy closes active sessions and invitations. Saved trust remains. NARADA never opens firewall rules automatically.",
      ),
      errors,
    ),
    actions: [button("Not now", () => dialog.close()), save],
  });
}
function disable(ctx) {
  let dialog;
  dialog = modal({
    title: "Disconnect for now?",
    eyebrow: "PAUSE ALL PEER CONNECTIONS",
    content: h(
      "p",
      { class: "muted" },
      "This closes peer sessions, cancels invitations and stops discovery. Your protected identity and saved trust stay on this desktop.",
    ),
    actions: [
      button("Keep enabled", () => dialog.close()),
      button(
        "Disable connections",
        async () => {
          await action(
            ctx,
            () =>
              agent.configure_connections({
                ...ctx.snapshot.connections.config,
                enabled: false,
              }),
            "Connections disabled. Saved trust is preserved.",
          );
          dialog.close();
        },
        { kind: "primary" },
      ),
    ],
  });
}
async function createInvitation(ctx) {
  try {
    invitationSecret = await agent.start_pairing();
    await ctx.refresh(false);
    showInvitation(ctx);
  } catch (e) {
    toast(e.message, "error");
  }
}
function showInvitation(ctx) {
  if (!invitationSecret)
    return toast(
      "The secret is only shown in the window that created it. Cancel the current invitation to create a replacement.",
      "error",
    );
  const current = invitationSecret;
  const canvas = h("canvas", {
    class: "pairing-qr",
    role: "img",
    "aria-label": "One-time NARADA pairing QR code",
  });
  const qr = h("div", { class: "pairing-qr-wrap" }, canvas);
  const timer = h("p", { class: "invitation-timer", role: "status" });
  const status = h("div", { class: "invitation-status" });
  const manual = h("textarea", {
    id: "invitation-text",
    class: "connection-input invitation-text",
    readonly: true,
    rows: 4,
    spellcheck: "false",
    value: current.code,
  });
  const copy = button(
    "Copy pairing code",
    async () => {
      try {
        await navigator.clipboard.writeText(manual.value);
        toast("One-time code copied. Only share it with the intended device.");
      } catch {
        details.open = true;
        manual.focus();
        manual.select();
        toast(
          "Select and copy the text code. Clipboard access is unavailable here.",
          "error",
        );
      }
    },
    { leading: "link" },
  );
  const details = h(
    "details",
    { class: "manual-code" },
    h("summary", {}, "Use a text code instead"),
    h(
      "label",
      { for: "invitation-text", class: "small" },
      "Sensitive one-time pairing code",
    ),
    manual,
  );
  let dialog;
  const cancel = button("Cancel invitation", async () => {
    await action(
      ctx,
      () => agent.cancel_pairing({ invitation_id: current.invitation_id }),
      "Invitation cancelled.",
    );
    dialog.close();
  });
  dialog = modal({
    title: "Pair with this desktop.",
    eyebrow: "ONE CODE · ONE DEVICE · TWO MINUTES",
    className: "pairing-modal",
    content: h(
      "div",
      {},
      h(
        "p",
        { class: "muted" },
        "On the other desktop, choose Join with a code. Read this QR image offline or paste the text code, then return here to approve.",
      ),
      qr,
      timer,
      copy,
      details,
      status,
      note(
        "Do not share this invitation publicly. A private-key proof and your separate approval are still required.",
      ),
    ),
    actions: [cancel, button("Close", () => dialog.close())],
  });
  QRCode.toCanvas(canvas, current.code, {
    scale: 4,
    margin: 4,
    errorCorrectionLevel: "M",
    color: { dark: "#171a17", light: "#ffffff" },
  }).catch(() => {
    replace(
      qr,
      note("The QR renderer failed. The text code still works.", "alert"),
    );
  });
  let requesterFingerprint;
  const update = (c) => {
    const inv = c.invitation;
    if (inv?.pending) requesterFingerprint = inv.pending.fingerprint;
    if (!inv || inv.invitation_id !== current.invitation_id) {
      canvas.width = canvas.height = 0;
      manual.value = "";
      current.code = "";
      copy.disabled = cancel.disabled = true;
      details.hidden = true;
      replace(qr, icon("shield", 50));
      const paired = c.peers.find(
        (p) => p.fingerprint === requesterFingerprint && p.state === "active",
      );
      timer.textContent = paired
        ? "Pairing confirmed · trust saved"
        : "This invitation is closed or expired.";
      if (paired)
        dialog.dialog.querySelector(".modal-header h2").textContent =
          "You’re connected.";
      replace(
        status,
        h(
          "p",
          { class: "muted" },
          paired
            ? `${paired.name} is now trusted. Content access still requires explicit folder, chat and attachment consent.`
            : "Check Trusted devices for a confirmed pairing. A new pairing always needs a new code.",
        ),
      );
      return;
    }
    const seconds = Math.max(
      0,
      Math.ceil((Date.parse(inv.expires_at) - Date.now()) / 1000),
    );
    timer.textContent = `Expires in ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")} · one claim only`;
    if (inv.pending)
      replace(
        status,
        h("h3", {}, inv.pending.name),
        h(
          "p",
          { class: "muted small" },
          inv.pending.proof_verified
            ? inv.pending.approved
              ? "Approved. Waiting for final acknowledgement."
              : "Private-key proof verified. Compare the fingerprint before approving."
            : "Certificate received. Waiting for the private-key proof.",
        ),
        inv.pending.proof_verified &&
          !inv.pending.approved &&
          button("Review request", () => review(ctx, inv.pending), {
            kind: "primary",
            leading: "shield",
          }),
      );
  };
  watch(dialog, update);
  update(ctx.snapshot.connections);
  dialog.dialog.addEventListener(
    "close",
    () => {
      current.code = "";
      manual.value = "";
      canvas.width = canvas.height = 0;
      invitationSecret = null;
    },
    { once: true },
  );
}
function review(ctx, pending) {
  const errors = errorBox();
  const check = h("input", { type: "checkbox", id: "compare-fingerprints" });
  let dialog;
  const approve = button(
    "Approve this device",
    async () => {
      approve.disabled = true;
      try {
        await agent.approve_pairing({
          attempt_id: pending.attempt_id,
          permissions: NO_CONTENT,
        });
        dialog.close();
        await ctx.refresh(false);
        toast("Approved. Waiting for the joining device’s final confirmation.");
      } catch (e) {
        showError(errors, e);
        approve.disabled = !check.checked;
      }
    },
    { kind: "primary", leading: "shield", disabled: true },
  );
  check.addEventListener("change", () => {
    approve.disabled = !check.checked;
  });
  dialog = modal({
    title: `Trust ${pending.name}?`,
    eyebrow: "LOCAL OWNER APPROVAL",
    content: h(
      "div",
      {},
      note(
        "Names and platform labels are self-reported. Compare the full requester fingerprint with the fingerprint shown on the joining desktop.",
      ),
      fp("REQUESTER FINGERPRINT", pending.fingerprint),
      fp("THIS OWNER DESKTOP", ctx.snapshot.connections.identity.fingerprint),
      h(
        "label",
        { class: "connection-checkbox", for: "compare-fingerprints" },
        check,
        h(
          "span",
          {},
          "I compared the requester fingerprint on the other device.",
        ),
      ),
      h(
        "div",
        { class: "permission-summary" },
        h("h3", {}, "Connection only. Nothing shared."),
        h(
          "p",
          {},
          "No file roots, chat or attachment access. Owner controls are never granted to a peer.",
        ),
      ),
      errors,
    ),
    actions: [
      button("Reject request", async () => {
        await action(
          ctx,
          () => agent.reject_pairing({ attempt_id: pending.attempt_id }),
          "Pairing rejected. That invitation cannot be reused.",
        );
        dialog.close();
      }),
      approve,
    ],
  });
  watch(dialog, (c) => {
    const p = c.invitation?.pending;
    if (!p || p.attempt_id !== pending.attempt_id || p.approved) {
      approve.disabled = true;
      check.disabled = true;
      showError(
        errors,
        Error(
          "This request is no longer awaiting approval. Refresh the device list.",
        ),
      );
    } else approve.disabled = !p.proof_verified || !check.checked;
  });
}
function join(ctx) {
  const errors = errorBox();
  const preview = h("div", { class: "code-preview" });
  const input = h("textarea", {
    id: "join-code",
    class: "connection-input",
    rows: 5,
    maxlength: 4096,
    autocomplete: "off",
    spellcheck: "false",
    placeholder: "Paste the one-time NARADA code here…",
  });
  const endpoint = h("input", {
    id: "join-endpoint",
    class: "connection-input",
    type: "text",
    maxlength: 80,
    placeholder: "Optional · 192.168.1.20:port",
    autocomplete: "off",
  });
  const file = h("input", {
    type: "file",
    id: "pairing-image",
    accept: "image/png,image/jpeg",
    class: "qr-file-input",
  });
  file.addEventListener("change", async () => {
    errors.hidden = true;
    try {
      input.value = await decodePairingImage(file.files[0]);
      inspect();
      toast("QR decoded on this device. No image was uploaded.");
    } catch (e) {
      showError(errors, e);
    } finally {
      file.value = "";
    }
  });
  function inspect() {
    errors.hidden = true;
    try {
      const code = parsePairingCode(input.value);
      replace(
        preview,
        h("h3", {}, code.name),
        fp("PINNED OWNER FINGERPRINT", code.fingerprint),
      );
    } catch {
      replace(preview);
    }
  }
  input.addEventListener("input", inspect);
  let dialog;
  const submit = button(
    "Request pairing",
    async () => {
      try {
        parsePairingCode(input.value);
      } catch (e) {
        showError(errors, e);
        return;
      }
      submit.disabled = true;
      try {
        await agent.join_pairing({
          code: input.value,
          endpoint_override: endpoint.value.trim() || null,
        });
        input.value = "";
        dialog.close();
        await ctx.refresh(false);
        toast(
          "Request started. Compare fingerprints and approve on the owner desktop.",
        );
      } catch (e) {
        showError(errors, e);
        submit.disabled = false;
      }
    },
    { kind: "primary", leading: "link" },
  );
  dialog = modal({
    title: "Join a trusted desktop.",
    eyebrow: "OFFLINE QR OR TEXT CODE",
    content: h(
      "div",
      { class: "connection-form" },
      note(
        "Open a different desktop or reference-agent workspace to generate a code. This desktop cannot pair with itself.",
      ),
      h("label", { for: "join-code" }, "Pairing code"),
      input,
      h(
        "label",
        { for: "pairing-image", class: "qr-file-label" },
        icon("file", 18),
        h("span", {}, "Choose QR image · PNG or JPEG, up to 5 MB"),
        file,
      ),
      h(
        "p",
        { class: "small muted" },
        "Decoded locally. No camera permission, upload, internet or external QR service.",
      ),
      preview,
      h(
        "details",
        { class: "manual-code" },
        h("summary", {}, "Manual route override"),
        h("label", { for: "join-endpoint" }, "Bootstrap endpoint"),
        endpoint,
        h(
          "p",
          { class: "small muted" },
          "Use only the current private IPv4 address and invitation port. The QR identity pin cannot be changed.",
        ),
      ),
      errors,
    ),
    actions: [button("Not now", () => dialog.close()), submit],
  });
  dialog.dialog.addEventListener(
    "close",
    () => {
      input.value = "";
      file.value = "";
    },
    { once: true },
  );
}
function joinTitle(state) {
  return (
    {
      connecting: "Contacting the pinned owner",
      proving: "Proving this desktop’s private key",
      awaiting_approval: "Waiting for owner approval",
      finalizing: "Confirming durable trust",
      paired: "Pairing confirmed",
      cancelled: "Joining cancelled",
      failed: "Pairing needs attention",
    }[state] || state
  );
}
function permissions(ctx, peer) {
  let current = peer;
  const errors = errorBox();
  const summary = h(
    "div",
    { class: "permission-summary" },
    h("h3", {}, "What this device can access"),
    h(
      "p",
      {},
      "Folder grants are managed in Files. Chat and attachment consent are separate; both devices must opt in. Changes invalidate previous authorization sessions.",
    ),
    h(
      "div",
      { class: "permission-line" },
      h("span", {}, "Shared file roots"),
      h("strong", {}, String(peer.permissions.roots.length)),
    ),
    h(
      "div",
      { class: "permission-line" },
      h("span", {}, "Chat consent"),
      h("strong", {}, peer.permissions.chat ? "Granted" : "Not granted"),
    ),
    h(
      "div",
      { class: "permission-line" },
      h("span", {}, "Attachment consent"),
      h("strong", {}, peer.permissions.attachment ? "Granted" : "Not granted"),
    ),
    h(
      "div",
      { class: "permission-line" },
      h("span", {}, "Local owner commands"),
      h("strong", {}, "Never delegated"),
    ),
  );
  let chatConsent = peer.permissions.chat,
    attachmentConsent = peer.permissions.attachment;
  const contentConsent = h(
    "section",
    { class: "content-consent" },
    h("h3", {}, "Private content permissions"),
    toggle(
      "Allow encrypted chat",
      "Receive E2EE messages from this paired identity. No plaintext fallback.",
      chatConsent,
      (value) => {
        chatConsent = value;
      },
      { disabled: peer.state !== "active" },
    ),
    toggle(
      "Allow encrypted attachments",
      "Requires chat consent too. Receiving ciphertext never opens a file.",
      attachmentConsent,
      (value) => {
        attachmentConsent = value;
      },
      { disabled: peer.state !== "active" },
    ),
    button(
      "Save content permissions",
      async () => {
        try {
          if (attachmentConsent && !chatConsent)
            throw Error("Enable chat before granting attachments.");
          await agent.update_peer_permissions({
            peer_id: current.peer_id,
            expected_revision: current.grant_revision,
            permissions: {
              ...current.permissions,
              chat: chatConsent,
              attachment: attachmentConsent,
            },
          });
          dialog.close();
          await ctx.refresh(false);
          toast("Content consent saved. Previous sessions were invalidated.");
        } catch (e) {
          showError(errors, e);
        }
      },
      { kind: "primary", disabled: peer.state !== "active" },
    ),
  );
  const endpoint = h("input", {
    id: "peer-endpoint",
    class: "connection-input",
    maxlength: 80,
    value: peer.endpoint || "",
    autocomplete: "off",
  });
  let dialog;
  const reset = button(
    "Reset to connection-only",
    async () => {
      try {
        await agent.update_peer_permissions({
          peer_id: current.peer_id,
          expected_revision: current.grant_revision,
          permissions: NO_CONTENT,
        });
        dialog.close();
        await ctx.refresh(false);
        toast(
          "Content grants cleared. Existing authorization sessions were invalidated.",
        );
      } catch (e) {
        showError(errors, e);
      }
    },
    { disabled: peer.state !== "active" },
  );
  dialog = modal({
    title: peer.name,
    eyebrow: "PINNED IDENTITY & SCOPED PERMISSIONS",
    content: h(
      "div",
      { class: "connection-form" },
      fp("SAVED CERTIFICATE FINGERPRINT", peer.fingerprint),
      summary,
      contentConsent,
      peer.direction === "outbound" &&
        peer.state === "active" &&
        h(
          "details",
          { class: "manual-code" },
          h("summary", {}, "Update saved endpoint"),
          h("label", { for: "peer-endpoint" }, "Private IPv4 endpoint"),
          endpoint,
          h(
            "p",
            { class: "small muted" },
            "Routing can change. The saved identity pin cannot. No DNS, public IPs, redirects or metadata endpoints are accepted.",
          ),
          button("Save endpoint & retry", async () => {
            try {
              await agent.set_peer_endpoint({
                peer_id: current.peer_id,
                endpoint: endpoint.value.trim(),
              });
              dialog.close();
              await ctx.refresh(false);
              toast(
                "Endpoint updated; the original identity pin is unchanged.",
              );
            } catch (e) {
              showError(errors, e);
            }
          }),
        ),
      note(
        `Grant revision ${peer.grant_revision} · authorization epoch ${peer.session_epoch}. A permission change invalidates existing sessions.`,
      ),
      errors,
    ),
    actions: [
      reset,
      peer.state !== "revoked"
        ? button(
            "Revoke device",
            () => {
              dialog.close();
              revoke(ctx, current);
            },
            { kind: "danger" },
          )
        : button("Done", () => dialog.close(), { kind: "primary" }),
    ],
  });
  watch(dialog, (c) => {
    const fresh = c.peers.find((p) => p.peer_id === peer.peer_id);
    if (fresh && fresh.grant_revision !== current.grant_revision) {
      for (const control of dialog.dialog.querySelectorAll(
        ".modal-footer button, details button, .content-consent button, .content-consent input",
      ))
        control.disabled = true;
      reset.disabled = true;
      showError(
        errors,
        Error(
          "This device’s permissions changed. Close and reopen the details before editing.",
        ),
      );
    }
  });
}
function revoke(ctx, peer) {
  let dialog;
  const errors = errorBox();
  const confirm = button(
    "Revoke trust",
    async () => {
      confirm.disabled = true;
      try {
        await agent.revoke_peer({
          peer_id: peer.peer_id,
          expected_revision: peer.grant_revision,
        });
        dialog.close();
        await ctx.refresh(false);
        toast(
          "Trust revoked. Active sessions closed; future connections are denied.",
        );
      } catch (e) {
        showError(errors, e);
        confirm.disabled = false;
      }
    },
    { kind: "danger" },
  );
  dialog = modal({
    title: `Revoke ${peer.name}?`,
    eyebrow: "REMOVE DEVICE TRUST",
    content: h(
      "div",
      {},
      h(
        "p",
        { class: "muted" },
        "Revocation is saved before connections are closed. This device cannot reconnect using its old trust. Re-pairing requires a fresh invitation, private-key proof and owner approval.",
      ),
      fp("IDENTITY BEING REVOKED", peer.fingerprint),
      errors,
    ),
    actions: [button("Keep device", () => dialog.close()), confirm],
  });
}
