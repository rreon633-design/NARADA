import { h, replace } from "../lib/dom.js";
import { icon } from "../components/icons.js";
import {
  badge,
  button,
  pageHeader,
  panelHead,
  modal,
  toast,
} from "../components/ui.js";
import { agent } from "../services/bridge.js";
import { openTransferDialog } from "../components/transfer-dialog.js";
import { bytes } from "../lib/format.js";
import {
  RUNNING,
  ATTENTION,
  filterTransfers,
  progressOf,
  transferLabel,
  transferHint,
} from "../lib/transfers.js";
export function transfers(ctx) {
  const view = h("div", { class: "page transfers-page" }),
    body = h("div");
  view.append(body);
  const s = {
    alive: true,
    loading: false,
    data: null,
    error: "",
    tab: "all",
    busy: false,
    online: ctx.connected,
    snapshot: ctx.snapshot,
    lastKey: "",
  };
  const peerName = (id) =>
    s.snapshot?.connections?.peers?.find((p) => p.peer_id === id)?.name ||
    "Saved peer";
  const focusKey = (job, action) => `${job.transfer_id}:${action}`;
  async function load(force = false) {
    if (!s.alive || s.loading) return;
    s.loading = true;
    try {
      const data = await agent.list_transfers();
      for (const field of ["jobs", "receives", "offers", "sent_offers"])
        if (!Array.isArray(data[field]) || data[field].length > 24)
          throw new Error(
            "The agent returned an invalid bounded transfer list.",
          );
      for (const job of [...data.jobs, ...data.receives]) progressOf(job);
      if (!s.alive) return;
      const key = JSON.stringify(data);
      s.data = data;
      const hadError = Boolean(s.error);
      s.error = "";
      if (force || key !== s.lastKey || hadError) {
        s.lastKey = key;
        draw();
      }
    } catch (e) {
      if (s.alive) {
        s.error = e.message;
        draw();
      }
    } finally {
      s.loading = false;
    }
  }
  async function action(job, target, verb, name = null) {
    if (s.busy) return;
    s.busy = true;
    try {
      await agent.transfer_control({
        transfer_id: job.transfer_id,
        target,
        action: verb,
        name,
      });
      toast(
        verb === "cancel" || verb === "decline"
          ? "Cancellation saved. Already published files are never removed."
          : "Transfer control saved.",
      );
      await load(true);
    } catch (e) {
      toast(e.message, "error");
    } finally {
      s.busy = false;
      draw();
    }
  }
  function confirmCancel(job, target, verb = "cancel") {
    let dialog;
    dialog = modal({
      title:
        verb === "decline" ? "Decline this file?" : "Cancel this transfer?",
      eyebrow: "KEEP EXISTING FILES SAFE",
      content: h(
        "div",
        { class: "transfer-dialog-grid" },
        h("strong", {}, job.name || job.manifest?.name),
        h(
          "p",
          {},
          "This stops further work. It does not delete source files or already published destination files. If a publication acknowledgement was lost, check the receiver before assuming the file was not delivered.",
        ),
      ),
      actions: [
        button("Keep transfer", () => dialog.close()),
        button(
          verb === "decline" ? "Decline file" : "Cancel transfer",
          () => {
            dialog.close();
            action(job, target, verb);
          },
          { kind: "primary" },
        ),
      ],
    });
  }
  function reviewReceive(job) {
    const filename = h("input", {
      class: "field",
      value: job.name,
      "aria-label": "Approved receive filename",
    });
    let dialog;
    dialog = modal({
      title:
        job.state === "awaiting_approval"
          ? "Receive this file?"
          : "Review this receive",
      eyebrow: "YOUR DESTINATION, YOUR DECISION",
      content: h(
        "div",
        { class: "transfer-dialog-grid" },
        h(
          "p",
          {},
          `${peerName(job.peer_id)} wants to send ${bytes(Number(job.size))} into ${job.root_label || "the approved folder"} / ${job.destination_directory || "(top level)"}.`,
        ),
        h(
          "label",
          { class: "transfer-field" },
          "Filename to publish",
          filename,
        ),
        h(
          "p",
          {},
          "No file is overwritten. Only identity-matched staging is resumable. Archives and executables are stored as inert files, never opened automatically.",
        ),
        h("code", { class: "transfer-digest" }, `SHA-256 · ${job.sha256}`),
        job.error_code &&
          h(
            "p",
            { class: "transfer-scope-note" },
            transferHint(job.error_code),
          ),
      ),
      actions: [
        button("Not now", () => dialog.close()),
        button(
          job.state === "awaiting_approval"
            ? "Approve receive"
            : "Resume receive",
          () => {
            dialog.close();
            action(
              job,
              "receive",
              job.state === "awaiting_approval" ? "accept" : "retry",
              filename.value,
            );
          },
          { kind: "primary" },
        ),
      ],
    });
  }
  function details(job) {
    let dialog;
    dialog = modal({
      title: "Transfer details",
      eyebrow: "VERIFIED, NOT ASSUMED",
      content: h(
        "div",
        { class: "transfer-dialog-grid" },
        h("strong", {}, job.name),
        h("p", {}, `${peerName(job.peer_id)} · ${transferLabel(job)}`),
        h(
          "dl",
          { class: "transfer-detail-list" },
          h("dt", {}, "Transfer ID"),
          h("dd", {}, job.transfer_id),
          h("dt", {}, "SHA-256"),
          h(
            "dd",
            { class: "transfer-digest" },
            job.sha256 || "Snapshot not ready",
          ),
          h("dt", {}, "Saved filename"),
          h("dd", {}, job.result?.name || "No confirmed publication"),
          h("dt", {}, "Verified bytes"),
          h("dd", {}, `${job.verified_bytes} / ${job.size}`),
        ),
        job.ack_pending &&
          h(
            "p",
            { class: "transfer-scope-note" },
            "This local copy is published and recorded. The source’s cleanup acknowledgement is still pending; delivery has not been guessed.",
          ),
        job.error_code && h("p", {}, transferHint(job.error_code)),
      ),
      actions: [button("Close", () => dialog.close())],
    });
  }
  function row(job, receive = false) {
    const p = progressOf(job),
      target = receive ? "receive" : "job",
      status = transferLabel(job);
    const control = (label, verb, leading, callback) =>
      button(label, callback || (() => action(job, target, verb)), {
        kind: "text",
        leading,
        disabled: s.busy || !s.online,
        "data-transfer-focus": focusKey(job, verb),
        "aria-label": `${label} ${job.name}`,
      });
    const controls = [];
    if (
      receive &&
      ["awaiting_approval", "blocked", "paused"].includes(job.state)
    )
      controls.push(
        control(
          job.state === "awaiting_approval" ? "Review" : "Resume",
          "accept",
          "play",
          () => reviewReceive(job),
        ),
      );
    else if (!receive && ["paused", "blocked", "failed"].includes(job.state))
      controls.push(
        control(job.state === "paused" ? "Resume" : "Retry", "retry", "play"),
      );
    if (
      [
        "queued",
        "running",
        "retry_wait",
        "receiving",
        "awaiting_approval",
      ].includes(job.state) &&
      !(receive && job.state === "awaiting_approval")
    )
      controls.push(control("Pause", "pause", "pause"));
    if (
      ![
        "completed",
        "cancelled",
        "cleaned",
        "recovery_required",
        "committing",
      ].includes(job.state)
    )
      controls.push(
        control(
          receive && job.state === "awaiting_approval" ? "Decline" : "Cancel",
          "cancel",
          "close",
          () =>
            confirmCancel(
              job,
              target,
              receive && job.state === "awaiting_approval"
                ? "decline"
                : "cancel",
            ),
        ),
      );
    return h(
      "article",
      {
        class: `transfer-row-real ${ATTENTION.has(job.state) ? "has-issue" : ""}`,
        "data-transfer-id": job.transfer_id,
      },
      h(
        "span",
        { class: "transfer-file-icon", "aria-hidden": "true" },
        icon(
          job.state === "completed"
            ? "check"
            : job.direction === "upload"
              ? "arrowUp"
              : "download",
          22,
        ),
      ),
      h(
        "div",
        { class: "transfer-row-content" },
        h(
          "div",
          { class: "transfer-row-title" },
          h("h3", {}, job.name),
          badge(
            status,
            job.state === "completed"
              ? "success"
              : ATTENTION.has(job.state)
                ? "warning"
                : "neutral",
          ),
        ),
        h(
          "p",
          { class: "transfer-peer" },
          `${receive ? "Incoming from" : job.direction === "upload" ? "To" : "From"} ${peerName(job.peer_id)}`,
          h("span", {}, " · "),
          job.state === "completed"
            ? "SHA-256 verified & published"
            : `${bytes(p.verified)} of ${bytes(p.size)} verified`,
        ),
        h(
          "div",
          { class: "transfer-meter-line" },
          h("progress", {
            class: "transfer-meter",
            value: p.percent,
            max: 100,
            "aria-label": `Verified bytes for ${job.name}`,
          }),
          h("span", { class: "transfer-percent" }, `${p.percent}%`),
        ),
        job.error_code &&
          h(
            "p",
            { class: "transfer-row-warning" },
            transferHint(job.error_code),
          ),
        job.ack_pending &&
          h(
            "p",
            { class: "transfer-row-warning" },
            "Published locally · source acknowledgement pending",
          ),
        h(
          "div",
          { class: "transfer-row-actions" },
          controls,
          button("Details", () => details(job), {
            kind: "text",
            "data-transfer-focus": focusKey(job, "details"),
            "aria-label": `Details for ${job.name}`,
          }),
        ),
      ),
    );
  }
  function offerCard(offer) {
    return h(
      "article",
      { class: "transfer-offer" },
      h("span", { class: "transfer-offer-icon" }, icon("inbox", 20)),
      h(
        "div",
        {},
        h("h3", {}, offer.manifest.name),
        h(
          "p",
          {},
          `${peerName(offer.peer_id)} · ${bytes(Number(offer.manifest.size))}`,
        ),
        h(
          "p",
          { class: "small" },
          "Choose a local folder before any download.",
        ),
        h(
          "div",
          { class: "transfer-offer-actions" },
          button(
            "Choose destination",
            () => openTransferDialog(ctx, { mode: "accept", offer }),
            {
              kind: "primary",
              disabled: !s.online,
              "data-transfer-focus": focusKey(offer, "accept"),
            },
          ),
          button("Decline", () => confirmCancel(offer, "offer", "decline"), {
            kind: "text",
            disabled: !s.online,
            "data-transfer-focus": focusKey(offer, "decline"),
          }),
        ),
      ),
    );
  }
  async function checkOffers() {
    try {
      await agent.poll_transfer_offers();
      toast("Checking saved pinned endpoints. New offers will appear here.");
      await load(true);
    } catch (e) {
      toast(e.message, "error");
    }
  }
  function cleanup() {
    let dialog;
    dialog = modal({
      title: "Clean eligible staging?",
      eyebrow: "BOUNDED LOCAL STORAGE",
      content: h(
        "p",
        {},
        "Remove only recognized, finished or cancelled source snapshots and cancelled receive staging. Live jobs, unresolved publication intents, unknown files and published copies are preserved. Receipts are retained; this is not a permanent-file delete command.",
      ),
      actions: [
        button("Keep staging", () => dialog.close()),
        button(
          "Clean staging",
          async () => {
            try {
              const result = await agent.cleanup_transfers();
              dialog.close();
              toast(
                `${result.cleaned} managed file(s) cleaned; ${result.preserved} preserved for review.`,
              );
              await load(true);
            } catch (e) {
              toast(e.message, "error");
            }
          },
          { kind: "primary" },
        ),
      ],
    });
  }
  async function pauseAll() {
    if (s.busy) return;
    s.busy = true;
    let count = 0;
    try {
      for (const job of s.data?.jobs || [])
        if (RUNNING.has(job.state)) {
          await agent.transfer_control({
            transfer_id: job.transfer_id,
            target: "job",
            action: "pause",
            name: null,
          });
          count++;
        }
      toast(
        `${count} local job(s) paused. Receiver-side publications are not undone.`,
      );
    } catch (e) {
      toast(`${count} job(s) paused. ${e.message}`, "error");
    } finally {
      s.busy = false;
      await load(true);
    }
  }
  function draw() {
    if (!s.alive) return;
    const focused = body.contains(document.activeElement)
      ? document.activeElement?.getAttribute("data-transfer-focus")
      : null;
    const data = s.data || {
      jobs: [],
      receives: [],
      offers: [],
      sent_offers: [],
      limits: {},
    };
    const active = data.jobs.filter(
      (j) => RUNNING.has(j.state) || j.state === "paused",
    ).length;
    const completed = data.jobs.filter((j) => j.state === "completed").length;
    const attention = data.jobs.filter((j) => ATTENTION.has(j.state)).length;
    const incoming = data.receives.filter(
      (j) => !["cleaned", "cancelled"].includes(j.state),
    );
    const filtered = filterTransfers(data.jobs, s.tab);
    const tab = (id, label, count) =>
      button(
        `${label}${count === undefined ? "" : ` (${count})`}`,
        () => {
          s.tab = id;
          draw();
        },
        {
          kind: "text",
          className: `transfer-tab ${s.tab === id ? "selected" : ""}`,
          "aria-pressed": s.tab === id ? "true" : "false",
          "data-transfer-focus": `tab:${id}`,
        },
      );
    const header = pageHeader(
      "FILE MOVEMENT",
      "Transfers",
      "Every byte checked. Every destination chosen by you.",
      [
        button("Pause all", pauseAll, {
          leading: "pause",
          disabled:
            s.busy || !s.online || !data.jobs.some((j) => RUNNING.has(j.state)),
          "data-transfer-focus": "pause-all",
        }),
        button("Browse files", () => ctx.navigate("files"), {
          kind: "primary",
          leading: "folder",
        }),
      ],
    );
    const stats = h(
      "div",
      { class: "transfer-stats" },
      [
        ["transfer", "In the queue", active, "Active and paused local jobs"],
        [
          "check",
          "Verified deliveries",
          completed,
          "Published with a durable receipt",
        ],
        [
          "inbox",
          "Awaiting your review",
          incoming.filter((j) => j.state === "awaiting_approval").length +
            data.offers.length,
          "Nothing received without approval",
        ],
      ].map(([i, label, count, sub]) =>
        h(
          "div",
          { class: "transfer-stat" },
          h("span", { class: "transfer-stat-icon" }, icon(i, 21)),
          h(
            "div",
            {},
            h("p", {}, label),
            h("strong", {}, s.data ? String(count).padStart(2, "0") : "—"),
            h("small", {}, sub),
          ),
        ),
      ),
    );
    const queue = h(
      "section",
      { class: "panel transfer-queue", "aria-label": "Local transfer queue" },
      h(
        "div",
        {
          class: "transfer-tabs",
          role: "group",
          "aria-label": "Transfer filters",
        },
        tab("all", "All"),
        tab("active", "Active", active),
        tab("completed", "Completed", completed),
        tab("attention", "Needs attention", attention),
      ),
      filtered.length
        ? filtered.map((j) => row(j))
        : h(
            "div",
            { class: "transfer-empty" },
            h("span", { class: "transfer-empty-symbol" }, icon("transfer", 34)),
            h(
              "h2",
              {},
              !s.data
                ? "Loading your queue…"
                : s.tab === "all"
                  ? "Your next file starts here"
                  : "Nothing in this view",
            ),
            h(
              "p",
              {},
              !s.data
                ? "Reading durable state from the local agent."
                : "Select an approved file to send or download. Your queue will show real checkpoints—not simulated activity.",
            ),
            button("Open Files", () => ctx.navigate("files"), {
              leading: "folder",
            }),
          ),
      h(
        "div",
        { class: "transfer-queue-footer" },
        icon("shield", 15),
        "Completed means verified and published. 100% staged does not mean delivered.",
      ),
    );
    const received =
      incoming.length > 0 &&
      h(
        "section",
        { class: "panel transfer-incoming", "aria-label": "Incoming receives" },
        panelHead(
          "Incoming receives",
          "Review the filename before bytes are accepted.",
        ),
        incoming.map((j) => row(j, true)),
      );
    const sent =
      data.sent_offers.length > 0 &&
      h(
        "section",
        { class: "panel transfer-sent", "aria-label": "Sent pull offers" },
        panelHead(
          "Files you offered",
          "The recipient chooses their destination and initiates the download.",
        ),
        data.sent_offers.map((j) =>
          h(
            "div",
            { class: "transfer-sent-row" },
            icon("link", 18),
            h(
              "div",
              {},
              h("strong", {}, j.name),
              h("p", {}, `${peerName(j.peer_id)} · ${transferLabel(j)}`),
            ),
            j.state === "ready" &&
              button("Cancel offer", () => confirmCancel(j, "offer"), {
                kind: "text",
                disabled: !s.online,
                "data-transfer-focus": focusKey(j, "cancel"),
              }),
          ),
        ),
      );
    const offers = h(
      "section",
      { class: "panel transfer-offers" },
      panelHead("Receive offers", "Pulled over your saved connection."),
      data.offers.length
        ? data.offers.map(offerCard)
        : h(
            "div",
            { class: "transfer-offers-empty" },
            icon("inbox", 26),
            h("h3", {}, "No loaded offers"),
            h(
              "p",
              {},
              "Check your paired devices. An offline peer may still have an offer waiting.",
            ),
          ),
      button("Check for offers", checkOffers, {
        leading: "sync",
        disabled: !s.online,
        "data-transfer-focus": "check-offers",
      }),
    );
    const storage = h(
      "section",
      { class: "panel transfer-storage" },
      panelHead(
        "A bounded workspace",
        "Small, explicit limits for this desktop slice.",
      ),
      h(
        "dl",
        {},
        h("dt", {}, "Per file"),
        h("dd", {}, "64 MiB"),
        h("dt", {}, "Chunk size"),
        h("dd", {}, "256 KiB"),
        h("dt", {}, "Source reserved"),
        h(
          "dd",
          {},
          `${bytes(Number(data.limits.source_reserved || 0))} / 128 MiB`,
        ),
        h("dt", {}, "Receive reserved"),
        h(
          "dd",
          {},
          `${bytes(Number(data.limits.receive_reserved || 0))} / 128 MiB`,
        ),
        h("dt", {}, "Snapshot lifetime"),
        h("dd", {}, "24 hours"),
      ),
      button("Clean eligible staging", cleanup, {
        kind: "text",
        leading: "database",
        disabled: !s.online,
        "data-transfer-focus": "cleanup",
      }),
      h(
        "p",
        { class: "small" },
        "Published copies and unknown files are never removed by cleanup.",
      ),
    );
    const trust = h(
      "div",
      { class: "transfer-trust-note" },
      icon("lock", 18),
      h(
        "div",
        {},
        h("strong", {}, "Private connection. Honest state."),
        h(
          "p",
          {},
          "Mutual TLS protects transport. Staged file bytes are ordinary local files, not end-to-end encrypted storage.",
        ),
        h(
          "p",
          {},
          "Native Windows and power-loss qualification remain pending. Android comes later.",
        ),
      ),
    );
    const error =
      s.error &&
      h(
        "div",
        { class: "transfer-error", role: "alert" },
        icon("alert", 18),
        h("p", {}, s.data ? `Showing last loaded state. ${s.error}` : s.error),
        button("Retry connection", () => load(true), { kind: "text" }),
      );
    replace(
      body,
      header,
      error,
      stats,
      h(
        "div",
        { class: "transfer-layout" },
        h("div", { class: "transfer-main" }, queue, received, sent),
        h(
          "aside",
          {
            class: "transfer-aside",
            "aria-label": "Transfer offers and storage",
          },
          offers,
          storage,
          trust,
        ),
      ),
      h(
        "p",
        { class: "transfer-history-note" },
        data.view_notice ||
          "Recent queue window: up to 24 local jobs. Retained history and staging are bounded; unresolved intents are never automatically purged.",
      ),
    );
    if (focused)
      [...body.querySelectorAll("[data-transfer-focus]")]
        .find((el) => el.getAttribute("data-transfer-focus") === focused)
        ?.focus({ preventScroll: true });
  }
  const timer = setInterval(() => {
    if (s.online && !document.hidden) load();
  }, 1600);
  view.dispose = () => {
    s.alive = false;
    clearInterval(timer);
  };
  view.updateTransferConnection = (snapshot, connected) => {
    s.snapshot = snapshot;
    s.online = connected;
    if (!connected) {
      s.error = "Local agent unavailable. No new progress has been inferred.";
      draw();
    }
  };
  draw();
  load();
  return view;
}
