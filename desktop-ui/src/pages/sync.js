import { h, replace } from "../lib/dom.js";
import { icon } from "../components/icons.js";
import {
  badge,
  button,
  modal,
  pageHeader,
  panelHead,
  toast,
} from "../components/ui.js";
import { agent } from "../services/bridge.js";
import { bytes } from "../lib/format.js";
import { pinState, retainedBytes, contentHint } from "../lib/content.js";
import { contentDetails } from "../components/content-dialog.js";
export function sync(ctx) {
  let snapshot = ctx.snapshot,
    connected = ctx.connected,
    disposed = false,
    loading = false,
    data = null,
    fingerprint = "";
  const body = h("div", {}),
    error = h("div", { class: "content-error", role: "alert", hidden: true });
  const page = h(
    "div",
    { class: "page sync-page" },
    pageHeader(
      "KEEP WHAT MATTERS CLOSE",
      "Sync & offline",
      "One-way refresh. New versions get new files. Your previous copy stays yours.",
      h(
        "div",
        { class: "button-group" },
        button("Choose a file", () => ctx.navigate("files"), {
          leading: "plus",
        }),
        button(
          "Check for changes",
          async () => {
            try {
              await agent.refresh_sync();
              toast(
                "A metadata check was requested. Content completion is tracked separately.",
              );
              await load();
            } catch (e) {
              showError(e);
            }
          },
          { kind: "primary", leading: "sync", disabled: !connected },
        ),
      ),
    ),
    error,
    body,
  );
  function showError(e) {
    error.hidden = false;
    replace(
      error,
      icon("alert", 18),
      h("div", {}, h("strong", {}, e.message), h("p", {}, contentHint(e.code))),
    );
  }
  async function control(pin, action) {
    error.hidden = true;
    try {
      await agent.pin_control({ pin_id: pin.pin_id, action });
      await load();
    } catch (e) {
      showError(e);
    }
  }
  function unpin(pin) {
    let d;
    d = modal({
      title: "Stop tracking this pin?",
      eyebrow: "LOCAL COPIES ARE KEPT",
      content: h(
        "p",
        {},
        "Unpinning stops future checks and cancels pending refresh attempts. Already-published files and older versions are never deleted. A publication already in flight can still finish safely.",
      ),
      actions: [
        button("Keep pin", () => d.close()),
        button(
          "Unpin · keep files",
          async () => {
            await control(pin, "unpin");
            d.close();
          },
          { kind: "danger" },
        ),
      ],
    });
  }
  function card(pin) {
    const peer = snapshot?.connections?.peers?.find(
      (p) => p.peer_id === pin.peer_id,
    );
    const receipt = pin.current_receipt,
      progress = pin.progress;
    const total = Number(progress?.size),
      verified = Number(progress?.verified_bytes);
    const percent =
      Number.isSafeInteger(total) &&
      total > 0 &&
      verified >= 0 &&
      verified <= total
        ? Math.floor((verified * 100) / total)
        : null;
    return h(
      "article",
      { class: "pin-card", "data-pin-id": pin.pin_id },
      h(
        "div",
        { class: "pin-card-top" },
        h("div", { class: "pin-file-mark" }, icon("file", 25)),
        h(
          "div",
          { class: "pin-title" },
          h("h3", {}, pin.destination.name),
          h(
            "p",
            {},
            `${peer?.name || "Saved paired device"} · selected file only`,
          ),
        ),
        badge(pinState(pin), pin.state === "current" ? "success" : "neutral"),
      ),
      h(
        "div",
        { class: "pin-status-grid" },
        h(
          "div",
          {},
          h("span", {}, "SOURCE / METADATA"),
          h(
            "strong",
            {},
            pin.state === "source_deleted"
              ? "Removed at source"
              : pinState(pin),
          ),
        ),
        h(
          "div",
          {},
          h("span", {}, "LOCAL PUBLICATION"),
          h("strong", {}, receipt ? "Copy published" : "No completed copy yet"),
        ),
        h(
          "div",
          {},
          h("span", {}, "VERSIONS RECORDED"),
          h("strong", {}, `${pin.versions.length} / 3`),
        ),
      ),
      progress &&
        h(
          "div",
          { class: "pin-progress" },
          h(
            "div",
            {},
            h("span", {}, `Transfer: ${progress.phase || progress.state}`),
            h(
              "span",
              {},
              percent === null ? "Awaiting manifest" : `${percent}% staged`,
            ),
          ),
          percent !== null &&
            h("progress", {
              max: 100,
              value: percent,
              "aria-label": `Verified transfer progress for ${pin.destination.name}`,
            }),
        ),
      pin.error_code &&
        h(
          "p",
          { class: "pin-warning" },
          icon("alert", 15),
          `${pin.error_code} · ${contentHint(pin.error_code)}`,
        ),
      receipt &&
        h(
          "p",
          { class: "pin-copy-note" },
          icon("check", 15),
          `${receipt.name} · ${bytes(Number(receipt.size))} · integrity verified at publication. Local edits/deletions are not silently undone.`,
        ),
      h(
        "div",
        { class: "pin-actions" },
        button("Check source", () => control(pin, "check"), {
          leading: "sync",
          disabled: !connected || pin.state === "paused",
        }),
        pin.state === "paused"
          ? button("Resume refresh", () => control(pin, "resume"), {
              disabled: !connected,
            })
          : button("Pause refresh", () => control(pin, "pause"), {
              disabled: !connected,
            }),
        pin.state === "attention" &&
          button("Retry safely", () => control(pin, "refresh"), {
            disabled: !connected,
          }),
        receipt &&
          button("Inspect local folder", () => ctx.navigate("files"), {
            leading: "folder",
          }),
        button("Unpin", () => unpin(pin), {
          kind: "text",
          disabled: !connected,
        }),
      ),
      pin.versions.length > 0 &&
        h(
          "details",
          { class: "pin-versions" },
          h("summary", {}, "Publication receipts & previous versions"),
          pin.versions.map((v) =>
            h(
              "div",
              { class: "pin-version" },
              h("span", {}, `Version ${v.ordinal} · ${v.receipt.name}`),
              h("span", {}, bytes(Number(v.size))),
              button(
                "Details",
                () =>
                  contentDetails("Published pin version", {
                    Filename: v.receipt.name,
                    "Published at": v.receipt.published_at,
                    "Source version": v.version,
                    "SHA-256": v.receipt.sha256,
                    "Root ID": v.receipt.root_id,
                    Policy:
                      "Unpin and ordinary cache cleanup never delete this published file.",
                  }),
                { kind: "text" },
              ),
            ),
          ),
        ),
    );
  }
  function draw() {
    const pins = data?.pins || [],
      current = pins.filter((p) => p.current_receipt).length,
      attention = pins.filter((p) =>
        ["attention", "source_deleted", "source_changed"].includes(p.state),
      ).length;
    replace(
      body,
      h(
        "div",
        { class: "sync-overview" },
        h("div", { class: "sync-overview-icon" }, icon("sync", 30)),
        h(
          "div",
          {},
          h(
            "h2",
            {},
            !data
              ? "Loading actual pin state…"
              : !pins.length
                ? "Your offline shelf is ready."
                : attention
                  ? "Some sources need your attention."
                  : "Keep a good copy. Then refresh safely.",
          ),
          h(
            "p",
            {},
            "A metadata cursor is not a download receipt. Only verified publication creates a recorded local copy.",
          ),
        ),
        badge(
          !data
            ? "Loading"
            : `${current} published ${current === 1 ? "copy" : "copies"}`,
          current ? "success" : "neutral",
        ),
      ),
      h(
        "div",
        { class: "sync-stats" },
        stat("pin", "Tracked selections", data ? `${pins.length} / 8` : "—"),
        stat(
          "database",
          "Published bytes, recorded",
          data ? bytes(retainedBytes(pins)) : "—",
        ),
        stat("shield", "Overwrite policy", "Never"),
      ),
      h(
        "div",
        { class: "sync-columns" },
        h(
          "section",
          { class: "panel sync-pins", "aria-label": "Pinned files" },
          panelHead(
            "Your pinned files",
            "Desktop development slice · selected files, not whole folders",
          ),
          pins.length
            ? h("div", { class: "pin-list" }, pins.map(card))
            : h(
                "div",
                { class: "sync-empty" },
                icon("pin", 38),
                h(
                  "h3",
                  {},
                  data
                    ? "Choose what you want to keep."
                    : "Waiting for the agent…",
                ),
                h(
                  "p",
                  {},
                  "In Files, select a remote file and choose Keep offline. The source owner must grant Browse, Read and Sync read. Choose a local approved destination for its copy.",
                ),
                button("Browse approved files", () => ctx.navigate("files"), {
                  kind: "primary",
                  leading: "folder",
                }),
              ),
        ),
        h(
          "aside",
          { class: "sync-policy", "aria-label": "Offline safety and limits" },
          h(
            "section",
            { class: "panel" },
            h("span", { class: "eyebrow" }, "ONE DIRECTION. CLEAR OWNERSHIP."),
            h("h2", {}, "Nothing replaces your last good copy."),
            policy(
              "check",
              "Publish, then switch",
              "New bytes must verify before a new receipt becomes the current pin version.",
            ),
            policy(
              "copy",
              "Previous versions stay",
              "Each refresh chooses a different filename. Edits, conflicts and source deletion never trigger silent deletion.",
            ),
            policy(
              "lock",
              "Current grants, every time",
              "Snapshot, journal and pin-source chunks require current Sync read consent. Revocation stops new reads.",
            ),
          ),
          h(
            "section",
            { class: "panel sync-limits" },
            h("h2", {}, "Bounded by design"),
            h(
              "p",
              {},
              "8 active pins · 3 published versions per pin · 128 MiB active reservation · 64 MiB per file.",
            ),
            h(
              "p",
              {},
              "Offline copies live in your chosen registered folder. Their publication receipts record a past verification, not a claim that another app has never edited them.",
            ),
            h(
              "p",
              { class: "small muted" },
              "Android SAF and native Windows execution are still separate qualification gates.",
            ),
          ),
        ),
      ),
    );
  }
  function stat(glyph, label, value) {
    return h(
      "div",
      { class: "sync-stat panel" },
      icon(glyph, 19),
      h("div", {}, h("span", {}, label), h("strong", {}, value)),
    );
  }
  function policy(glyph, title, detail) {
    return h(
      "div",
      { class: "sync-policy-row" },
      icon(glyph, 18),
      h("div", {}, h("h3", {}, title), h("p", {}, detail)),
    );
  }
  async function load() {
    if (disposed || loading || !connected) return;
    loading = true;
    try {
      const value = await agent.list_pins();
      if (disposed) return;
      const next = JSON.stringify(value);
      data = value;
      if (next !== fingerprint) {
        fingerprint = next;
        draw();
      }
    } catch (e) {
      if (!disposed) showError(e);
    } finally {
      loading = false;
    }
  }
  page.updateContentConnection = (value, live) => {
    const changed = connected !== live;
    snapshot = value;
    connected = live;
    if (changed) {
      draw();
      for (const b of page.querySelectorAll(".page-actions button"))
        b.disabled = !connected;
    }
  };
  page.dispose = () => {
    disposed = true;
    clearInterval(timer);
  };
  draw();
  load();
  const timer = setInterval(load, 1600);
  return page;
}
