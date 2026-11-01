import { h, replace } from "../lib/dom.js";
import { icon } from "../components/icons.js";
import {
  badge,
  button,
  iconButton,
  modal,
  pageHeader,
  toast,
} from "../components/ui.js";
import { agent } from "../services/bridge.js";
import { bytes } from "../lib/format.js";
import {
  messageBytes,
  validMessage,
  messageState,
  attachmentState,
  cipherProgress,
  contentHint,
  canCompose,
  chatAttachment,
  clearChatAttachment,
} from "../lib/content.js";
import {
  contentDetails,
  exportAttachmentDialog,
} from "../components/content-dialog.js";
let lastPeer = "";
export function chat(ctx) {
  let snapshot = ctx.snapshot,
    connected = ctx.connected,
    disposed = false,
    loading = false,
    busy = false,
    peerId = lastPeer,
    status = null,
    conversations = [],
    messages = [],
    older = [],
    olderCursor = null,
    filter = "",
    draftFor = null,
    draftTimer,
    draftWork = Promise.resolve(),
    submission = null,
    drawKey = "";
  const error = h("div", {
    class: "content-error",
    role: "alert",
    hidden: true,
  });
  const contacts = h("div", { class: "chat-contacts" });
  const threadHead = h("div", { class: "chat-thread-head" });
  const timeline = h("div", {
    class: "chat-timeline",
    role: "log",
    "aria-label": "Conversation messages",
    "aria-live": "polite",
    "aria-relevant": "additions",
  });
  const composerMeta = h("div", { class: "chat-composer-meta" });
  const chosen = h("div", { class: "chat-selected-attachment" });
  const text = h("textarea", {
    class: "chat-input",
    rows: 2,
    maxlength: 4096,
    "aria-label": "Private message",
    placeholder: "Write a private message…",
    autocomplete: "off",
    spellcheck: "true",
    oninput: () => {
      updateComposer();
      scheduleDraft();
    },
    onkeydown: (e) => {
      if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
        e.preventDefault();
        if (!send.disabled) sendMessage();
      }
    },
  });
  const send = button("Send privately", sendMessage, {
    kind: "primary",
    leading: "send",
    "aria-label": "Send privately",
    disabled: true,
  });
  const choose = iconButton(
    "file",
    "Choose attachment from approved Files",
    async () => {
      await saveDraft();
      ctx.navigate("files");
    },
  );
  const composer = h(
    "div",
    { class: "chat-composer" },
    chosen,
    h("div", { class: "chat-compose-row" }, choose, text, send),
    composerMeta,
  );
  const badgeSlot = h("span", {});
  const search = h("input", {
    class: "chat-search",
    type: "search",
    placeholder: "Find a device…",
    "aria-label": "Filter conversations",
    oninput: (e) => {
      filter = e.target.value;
      drawContacts();
    },
  });
  const page = h(
    "div",
    { class: "page chat-page" },
    pageHeader(
      "WORDS, JUST BETWEEN YOU",
      "Chat",
      "Private conversations with your paired devices. No cloud mailbox. No assumed delivery.",
      h(
        "div",
        { class: "button-group" },
        badgeSlot,
        button("Privacy & encryption", privacy, { leading: "shield" }),
        button("Refresh", () => refresh(), { leading: "sync" }),
      ),
    ),
    error,
    h(
      "div",
      { class: "chat-workspace panel" },
      h(
        "aside",
        { class: "chat-sidebar", "aria-label": "Conversations" },
        h(
          "div",
          { class: "chat-sidebar-heading" },
          h("h2", {}, "Your conversations"),
          button("Devices", () => ctx.navigate("devices"), {
            kind: "text",
            leading: "plus",
          }),
        ),
        search,
        contacts,
        h(
          "div",
          { class: "chat-sidebar-note" },
          icon("lock", 15),
          h(
            "span",
            {},
            "History and drafts are protected on this agent. Message previews never enter diagnostics.",
          ),
        ),
      ),
      h(
        "section",
        { class: "chat-thread", "aria-label": "Selected conversation" },
        threadHead,
        timeline,
        composer,
      ),
    ),
    h(
      "div",
      { class: "content-trust-line" },
      icon("shield", 15),
      h(
        "span",
        {},
        "Delivered means the recipient committed the message. An attachment is available only after its own authentication checks.",
      ),
    ),
  );
  const peers = () => snapshot?.connections?.peers || [];
  const peer = () => peers().find((p) => p.peer_id === peerId);
  function showError(e) {
    error.hidden = false;
    replace(
      error,
      icon("alert", 18),
      h(
        "div",
        {},
        h("strong", {}, e.message || "The request could not be confirmed."),
        h("p", {}, contentHint(e.code)),
      ),
    );
  }
  async function saveDraft() {
    clearTimeout(draftTimer);
    if (!peerId || draftFor !== peerId || !status?.ready || !connected) return;
    const payload = { peer_id: peerId, text: text.value };
    draftWork = draftWork
      .catch(() => {})
      .then(() => agent.save_chat_draft(payload));
    try {
      await draftWork;
    } catch (e) {
      if (!disposed) showError(e);
    }
  }
  function scheduleDraft() {
    clearTimeout(draftTimer);
    draftTimer = setTimeout(saveDraft, 650);
  }
  async function select(id) {
    if (id === peerId) return;
    await saveDraft();
    peerId = lastPeer = id;
    draftFor = null;
    text.value = "";
    older = [];
    messages = [];
    drawKey = "";
    submission = null;
    draw();
    await refresh();
    text.focus();
  }
  function drawContacts() {
    const known = new Map(conversations.map((c) => [c.peer_id, c]));
    const all = peers().filter(
      (p) =>
        p.name.toLowerCase().includes(filter.toLowerCase()) &&
        (p.state === "active" || known.has(p.peer_id)),
    );
    replace(
      contacts,
      all.length
        ? all.map((p) => {
            const c = known.get(p.peer_id);
            return h(
              "button",
              {
                class: `chat-contact ${p.peer_id === peerId ? "selected" : ""}`,
                "aria-pressed": String(p.peer_id === peerId),
                onclick: () => select(p.peer_id),
              },
              h(
                "span",
                { class: "chat-avatar" },
                icon(p.platform?.includes("android") ? "phone" : "laptop", 21),
              ),
              h(
                "span",
                { class: "chat-contact-copy" },
                h("strong", {}, p.name),
                h(
                  "span",
                  {},
                  p.state !== "active"
                    ? "Trust revoked · history kept"
                    : !p.permissions.chat
                      ? "Chat permission not enabled"
                      : p.connection_state === "online"
                        ? "Paired device · online"
                        : "Paired device · offline",
                ),
              ),
              c?.unread > 0
                ? h(
                    "span",
                    {
                      class: "chat-unread",
                      "aria-label": `${c.unread} unread messages`,
                    },
                    c.unread,
                  )
                : null,
            );
          })
        : h(
            "div",
            { class: "chat-no-contacts" },
            icon("devices", 28),
            h(
              "h3",
              {},
              filter ? "No matching device" : "Start with a real device",
            ),
            h(
              "p",
              {},
              "Pair a device, then enable chat consent on both sides.",
            ),
            button("Manage devices", () => ctx.navigate("devices"), {
              kind: "text",
            }),
          ),
    );
  }
  function updateComposer() {
    const p = peer(),
      attached = chatAttachment();
    const allowed = canCompose(p, status?.ready, connected);
    text.disabled = busy || !allowed;
    choose.disabled = busy || !allowed || !p?.permissions?.attachment;
    send.disabled =
      busy ||
      !allowed ||
      !validMessage(text.value, Boolean(attached)) ||
      Boolean(attached && !p?.permissions?.attachment);
    replace(
      composerMeta,
      h(
        "span",
        {},
        !connected
          ? "Agent offline — no write can be confirmed"
          : !p
            ? "Choose a paired device"
            : !p.permissions.chat
              ? "Enable chat consent in Devices"
              : "Enter to send · Shift Enter for a new line",
      ),
      h(
        "span",
        { class: messageBytes(text.value) > 4096 ? "form-error" : "" },
        `${messageBytes(text.value)} / 4096 bytes`,
      ),
    );
    replace(
      chosen,
      attached
        ? h(
            "div",
            { class: "attachment-chip" },
            icon("file", 16),
            h("span", {}, attached.name),
            h(
              "small",
              {},
              `${bytes(Number(attached.size))} · encrypted snapshot on send`,
            ),
            iconButton("close", "Remove selected attachment", () => {
              clearChatAttachment();
              updateComposer();
            }),
          )
        : null,
    );
  }
  function draw() {
    drawContacts();
    const p = peer();
    replace(
      threadHead,
      h("span", { class: "chat-avatar" }, icon("chat", 21)),
      h(
        "div",
        {},
        h("h2", {}, p?.name || "A conversation, not a cloud"),
        h(
          "p",
          {},
          p
            ? connected && p.connection_state === "online"
              ? "Paired connection available"
              : "Offline · pending messages stay on this agent"
            : "Choose a device to read its protected history.",
        ),
      ),
      status?.ready && badge("Olm encrypted", "neutral"),
    );
    replace(
      badgeSlot,
      badge(
        status?.ready
          ? "Protected store ready"
          : status
            ? "Protected store locked"
            : "Loading protected state",
        status?.ready ? "success" : "neutral",
      ),
    );
    const key = JSON.stringify([peerId, messages, olderCursor, status?.ready]);
    if (key !== drawKey) {
      const stay =
        timeline.scrollHeight - timeline.scrollTop - timeline.clientHeight < 70;
      drawKey = key;
      replace(
        timeline,
        olderCursor &&
          button("Load older messages", loadOlder, { kind: "text" }),
        messages.length
          ? messages.map(messageCard)
          : h(
              "div",
              { class: "chat-empty-thread" },
              h("div", { class: "chat-empty-mark" }, icon("chat", 32)),
              h(
                "h3",
                {},
                !status
                  ? "Opening protected history…"
                  : !status.ready
                    ? "Your private history is locked."
                    : p
                      ? "Your conversation starts here."
                      : "Your words stay between your devices.",
              ),
              h(
                "p",
                {},
                !status?.ready && status
                  ? "No key, no plaintext fallback. Existing state has not been reset."
                  : "No seeded messages. No fake contacts. Pair a device and give it explicit chat permission.",
              ),
            ),
      );
      if (stay) timeline.scrollTop = timeline.scrollHeight;
    }
    updateComposer();
  }
  function messageCard(m) {
    const mine = m.direction === "outgoing";
    return h(
      "article",
      {
        class: `chat-message ${mine ? "outgoing" : "incoming"}`,
        "data-message-id": m.message_id,
        "aria-label": `${mine ? "Outgoing" : "Incoming"} message`,
      },
      h(
        "div",
        { class: "message-bubble" },
        m.text && h("p", { class: "message-text" }, m.text),
        m.attachment && attachmentCard(m),
        h(
          "div",
          { class: "message-footer" },
          h(
            "time",
            { datetime: new Date(m.created_at * 1000).toISOString() },
            new Date(m.created_at * 1000).toLocaleTimeString([], {
              hour: "2-digit",
              minute: "2-digit",
            }),
          ),
          h(
            "span",
            { class: `message-state ${m.state}` },
            m.state === "delivered" && icon("check", 12),
            mine ? messageState(m) : "Received",
          ),
        ),
      ),
      m.error_code &&
        h(
          "p",
          { class: "message-error" },
          `${m.error_code} · ${contentHint(m.error_code)}`,
        ),
      mine &&
        !["delivered", "cancelled"].includes(m.state) &&
        h(
          "div",
          { class: "message-controls" },
          button(
            "Retry saved message",
            () =>
              action(() =>
                agent.chat_control({
                  message_id: m.message_id,
                  action: "retry",
                }),
              ),
            { kind: "text", disabled: !connected },
          ),
          button("Cancel future sends", () => cancel(m), {
            kind: "text",
            disabled: !connected,
          }),
        ),
    );
  }
  function attachmentCard(m) {
    const a = m.attachment,
      progress = cipherProgress(a);
    return h(
      "div",
      { class: "message-attachment" },
      h(
        "div",
        { class: "message-attachment-title" },
        h("span", { class: "attachment-file-icon" }, icon("file", 23)),
        h(
          "span",
          {},
          h("strong", {}, a.name),
          h("small", {}, `${bytes(Number(a.size))} · encrypted attachment`),
        ),
      ),
      h("p", { class: "attachment-state" }, attachmentState(a)),
      a.state === "queued" &&
        progress !== null &&
        h(
          "div",
          {},
          h("progress", {
            max: 100,
            value: progress,
            "aria-label": "Verified ciphertext download",
          }),
          h(
            "small",
            {},
            progress === 100
              ? "Ciphertext staged; authenticating plaintext…"
              : `${progress}% ciphertext verified`,
          ),
        ),
      a.error_code &&
        h("p", { class: "message-error" }, contentHint(a.error_code)),
      h(
        "div",
        { class: "attachment-actions" },
        m.direction === "incoming" &&
          ["not_requested", "paused"].includes(a.state) &&
          button(
            a.state === "paused"
              ? "Resume download"
              : "Download encrypted file",
            () =>
              action(() =>
                agent.fetch_attachment({ message_id: m.message_id }),
              ),
            { leading: "download", disabled: !connected },
          ),
        a.state === "queued" &&
          button(
            "Pause",
            () =>
              action(() =>
                agent.pause_attachment({ message_id: m.message_id }),
              ),
            { disabled: !connected },
          ),
        ["available", "exported"].includes(a.state) &&
          button(
            a.state === "exported"
              ? "View export receipt"
              : "Export verified copy",
            () =>
              a.export
                ? contentDetails("Attachment export receipt", a.export)
                : exportAttachmentDialog(ctx, m),
            { leading: "folder", disabled: !connected },
          ),
        iconButton("info", "Attachment integrity details", () =>
          contentDetails(a.name, {
            "Message state": messageState(m),
            "Attachment state": attachmentState(a),
            "Plaintext SHA-256": a.sha256,
            "Plaintext bytes": a.size,
            "Ciphertext bytes": a.cipher_size,
            Export: a.export || "Not exported",
          }),
        ),
      ),
    );
  }
  async function action(fn) {
    error.hidden = true;
    try {
      await fn();
      await refresh();
    } catch (e) {
      showError(e);
    }
  }
  function cancel(m) {
    let d;
    d = modal({
      title: "Stop future sends?",
      eyebrow: "NOT A REMOTE UNSEND",
      content: h(
        "p",
        {},
        "The receiver may already have committed this message even if its receipt was lost. Cancellation stops future attempts; it cannot erase another device’s history. A verified late receipt still takes priority.",
      ),
      actions: [
        button("Keep pending", () => d.close()),
        button(
          "Stop future sends",
          async () => {
            await action(() =>
              agent.chat_control({
                message_id: m.message_id,
                action: "cancel",
              }),
            );
            d.close();
          },
          { kind: "danger" },
        ),
      ],
    });
  }
  async function sendMessage() {
    if (send.disabled) return;
    const attached = chatAttachment(),
      body = text.value,
      target = peerId;
    const signature = JSON.stringify([target, body, attached?.source || null]);
    if (!submission || submission.signature !== signature)
      submission = { signature, id: crypto.randomUUID() };
    busy = true;
    updateComposer();
    clearTimeout(draftTimer);
    error.hidden = true;
    try {
      await draftWork.catch(() => {});
      await agent.send_chat({
        message_id: submission.id,
        peer_id: target,
        text: body,
        attachment: attached?.source || null,
      });
      text.value = "";
      clearChatAttachment();
      submission = null;
      older = [];
      await refresh();
    } catch (e) {
      showError(e);
    } finally {
      busy = false;
      updateComposer();
      text.focus();
    }
  }
  async function loadOlder() {
    if (!olderCursor || loading) return;
    try {
      const result = await agent.chat_history({
        peer_id: peerId,
        before: olderCursor,
      });
      older = [
        ...new Map(
          [...result.messages, ...messages].map((m) => [m.message_id, m]),
        ).values(),
      ];
      olderCursor = result.older_cursor;
      messages = older;
      draw();
    } catch (e) {
      showError(e);
    }
  }
  async function refresh() {
    if (disposed || loading || !connected) {
      updateComposer();
      return;
    }
    loading = true;
    const selected = peerId;
    try {
      const fresh = await agent.content_status();
      if (disposed) return;
      status = fresh;
      if (!status.ready) {
        messages = [];
        conversations = [];
        if (status.error) showError(status.error);
        draw();
        return;
      }
      const result = await agent.list_conversations();
      if (disposed) return;
      conversations = result.conversations || [];
      if (!peerId) {
        const first =
          peers().find((p) => p.state === "active" && p.permissions.chat) ||
          peers().find((p) => p.state === "active");
        if (first) peerId = lastPeer = first.peer_id;
      }
      if (peerId) {
        const target = peerId;
        const history = await agent.chat_history({
          peer_id: target,
          before: null,
        });
        if (disposed || target !== peerId || (selected && selected !== peerId))
          return;
        const ids = new Set(history.messages.map((m) => m.message_id));
        messages = [
          ...older.filter((m) => !ids.has(m.message_id)),
          ...history.messages,
        ];
        if (!older.length) olderCursor = history.older_cursor;
        if (draftFor !== target) {
          text.value = history.draft || "";
          draftFor = target;
        }
        if (
          document.visibilityState === "visible" &&
          history.messages.some((m) => m.unread)
        )
          await agent.mark_chat_read({ peer_id: target });
      }
      draw();
    } catch (e) {
      if (!disposed) showError(e);
    } finally {
      loading = false;
    }
  }
  function privacy() {
    let d;
    d = modal({
      title: "A private endpoint, with clear limits",
      eyebrow: "ENCRYPTION & RETENTION",
      content: h(
        "div",
        { class: "content-form" },
        h(
          "p",
          {},
          "Messages use the vodozemac Olm ratchet, not TLS-only chat. History, drafts, inbox/outbox state and attachment keys are authenticated ciphertext in a separate transactional store. Native Windows uses an OS-wrapped storage key; this Linux preview uses an explicit development test vault.",
        ),
        h(
          "p",
          {},
          "Attachments use the age streaming format. Message delivery and attachment availability are separate. There are no read receipts, automatic file launches or message previews in exported diagnostics.",
        ),
        h(
          "p",
          {},
          "Limits: 4096 text bytes, 128 retained messages, 8 MiB per attachment and a 128 MiB ciphertext budget. Cleanup only considers expired eligible cache data; unresolved and pending work is preserved.",
        ),
        h(
          "p",
          { class: "content-qualification" },
          "Independent integration/security review, native Windows qualification and release signing remain open. The age Rust library is pre-1.0; no release certification is implied.",
        ),
      ),
      actions: [
        button(
          "Review eligible cleanup",
          async () => {
            try {
              const result = await agent.cleanup_content();
              toast(
                `${result.ciphertext_files_cleaned} expired ciphertext files cleaned; published files kept.`,
              );
              older = [];
              await refresh();
              d.close();
            } catch (e) {
              showError(e);
            }
          },
          { disabled: !connected || !status?.ready },
        ),
        button("Done", () => d.close(), { kind: "primary" }),
      ],
    });
  }
  page.updateContentConnection = (value, live) => {
    snapshot = value;
    connected = live;
    draw();
  };
  page.dispose = () => {
    saveDraft();
    disposed = true;
    clearTimeout(draftTimer);
    clearInterval(timer);
  };
  draw();
  refresh();
  const timer = setInterval(refresh, 1600);
  return page;
}
