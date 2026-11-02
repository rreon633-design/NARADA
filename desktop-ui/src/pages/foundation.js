import { h } from "../lib/dom.js";
import { icon } from "../components/icons.js";
import {
  badge,
  button,
  iconButton,
  pageHeader,
  panelHead,
  toast,
} from "../components/ui.js";
import { bytes, platformName, relativeTime } from "../lib/format.js";
import { agent, mode } from "../services/bridge.js";
export function diagnostics(ctx) {
  const s = ctx.snapshot;
  const d = s?.last_diagnostics;
  return h(
    "div",
    { class: "page diagnostics-page" },
    pageHeader(
      "A LITTLE PEACE OF MIND",
      "Workspace health",
      "Real checks. Clear results. All in one place.",
      h(
        "div",
        { class: "button-group" },
        button(
          "Export safe support summary",
          async () => {
            try {
              const summary = await agent.support_bundle();
              const url = URL.createObjectURL(
                new Blob([JSON.stringify(summary, null, 2)], {
                  type: "application/json",
                }),
              );
              const link = h("a", {
                href: url,
                download: "NARADA-support-summary.json",
              });
              link.click();
              setTimeout(() => URL.revokeObjectURL(url), 1000);
            } catch (e) {
              toast(e.message, "error");
            }
          },
          { leading: "shield", disabled: !ctx.connected },
        ),
        button("Export report", ctx.exportDiagnostics, {
          leading: "download",
          disabled: !d,
        }),
        button(
          ctx.busy ? "Checking…" : "Run health check",
          ctx.runDiagnostics,
          {
            kind: "primary",
            leading: "activity",
            disabled: ctx.busy || !ctx.connected,
          },
        ),
      ),
    ),
    h(
      "div",
      { class: `health-banner ${ctx.connected ? "" : "offline"}` },
      h(
        "div",
        { class: "health-mark" },
        icon(ctx.connected ? "shield" : "alert", 29),
      ),
      h(
        "div",
        {},
        h(
          "h2",
          {},
          ctx.connected
            ? "Your local foundation is running."
            : "The local agent is unavailable.",
        ),
        h(
          "p",
          {},
          ctx.connected
            ? s?.connections?.listening
              ? "The agent is responding. Protected peer connections are enabled. File operations require current registered-root grants."
              : "The agent is responding. No peer listener is currently active."
            : "Start the agent, then retry. No successful state is being simulated.",
        ),
      ),
      badge(
        ctx.connected ? "Agent online" : "Agent offline",
        ctx.connected ? "success" : "warning",
      ),
    ),
    h(
      "div",
      { class: "diagnostic-summary" },
      summary("server", "Agent version", s?.agent.version || "—"),
      summary(
        "database",
        "Metadata storage",
        s ? bytes(s.storage.database_bytes) : "—",
      ),
      summary(
        "link",
        "Local transport",
        s?.agent.ipc_transport || "Not connected",
      ),
      summary("laptop", "Runtime", s ? platformName(s.agent.platform) : "—"),
    ),
    h(
      "section",
      { class: "panel checks-panel" },
      panelHead(
        "System checks",
        d
          ? `Last checked ${relativeTime(d.checked_at)} · ${d.duration_ms} ms`
          : "Run your first health check to inspect the actual metadata database.",
        d &&
          badge(
            `${d.checks.filter((c) => c.status === "passed").length} passed`,
            "success",
          ),
      ),
      d
        ? h(
            "div",
            { class: "checks-list" },
            d.checks.map((check) =>
              h(
                "div",
                { class: "check-row" },
                h(
                  "span",
                  { class: `check-symbol ${check.status}` },
                  icon(
                    check.status === "passed"
                      ? "check"
                      : check.status === "failed"
                        ? "alert"
                        : "info",
                    17,
                  ),
                ),
                h(
                  "div",
                  { class: "check-copy" },
                  h("h3", {}, check.label),
                  h("p", {}, check.detail),
                ),
                badge(
                  check.status === "passed"
                    ? "Passed"
                    : check.status === "failed"
                      ? "Needs attention"
                      : check.status === "warning"
                        ? "Development only"
                        : "Not applicable",
                  check.status === "passed" ? "success" : "neutral",
                ),
              ),
            ),
          )
        : h(
            "div",
            { class: "checks-empty" },
            icon("activity", 38),
            h("h3", {}, "Let’s check the essentials."),
            h(
              "p",
              {},
              "Database integrity, durable writes, preferences, and network isolation.",
            ),
            button("Run first check", ctx.runDiagnostics, {
              kind: "primary",
              disabled: !ctx.connected,
            }),
          ),
    ),
    h(
      "div",
      { class: "diagnostic-notes" },
      h(
        "div",
        { class: "info-card" },
        icon("lock", 20),
        h(
          "div",
          {},
          h("h3", {}, "A report without the private parts."),
          h(
            "p",
            {},
            "Exported reports leave out workspace names, host paths, files, messages, and keys. You choose when to download one.",
          ),
        ),
      ),
      h(
        "div",
        { class: "info-card" },
        icon("info", 20),
        h(
          "div",
          {},
          h("h3", {}, mode),
          h(
            "p",
            {},
            "Browser checks run against the development Rust agent. Native Windows tray, packaging, and access controls need Windows qualification.",
          ),
        ),
      ),
    ),
  );
}
function summary(glyph, label, value) {
  return h(
    "div",
    { class: "summary-card" },
    h("span", { class: "summary-label" }, icon(glyph, 16), label),
    h("strong", {}, value),
  );
}
const futureCopy = {
  chat: {
    title: "Chat",
    eyebrow: "WORDS, JUST BETWEEN YOU",
    icon: "chat",
    phase: 5,
    heading: "A conversation without the cloud.",
    body: "Private, one-to-one messaging between your laptop and phone. For now, we’re building the foundations that make trust possible.",
    features: [
      [
        "lock",
        "End-to-end encryption",
        "A reviewed protocol, never home-made cryptography.",
      ],
      [
        "clock",
        "An honest outbox",
        "Pending means pending. Delivered means received.",
      ],
      [
        "file",
        "Thoughtful attachments",
        "Encrypted snapshots, separate download status.",
      ],
    ],
  },
  sync: {
    title: "Sync & offline",
    eyebrow: "KEEP WHAT MATTERS CLOSE",
    icon: "sync",
    phase: 7,
    heading: "The right files, even offline.",
    body: "Keep selected laptop files on your phone and refresh them safely. No silent overwrites, no surprise deletion of your last good copy.",
    features: [
      [
        "folder",
        "Only what you choose",
        "Selective pinning with clear storage limits.",
      ],
      [
        "clock",
        "Freshness you can see",
        "Know which version you have and when it refreshed.",
      ],
      [
        "shield",
        "The safe version stays",
        "Old content remains until the new copy is verified.",
      ],
    ],
  },
};
export function future(ctx, route) {
  const c = futureCopy[route];
  return h(
    "div",
    { class: "page future-page" },
    pageHeader(
      c.eyebrow,
      c.title,
      "Part of your NARADA workspace.",
      badge("Unavailable in this build"),
    ),
    h(
      "section",
      { class: "future-hero" },
      h(
        "div",
        { class: "future-orbit" },
        h("span", { class: "orbit-ring" }),
        h("span", { class: "future-icon" }, icon(c.icon, 44)),
      ),
      h("span", { class: "eyebrow" }, "GOOD THINGS NEED A SOLID FOUNDATION"),
      h("h2", {}, c.heading),
      h("p", {}, c.body),
      button("Build limitations", ctx.openRoadmap, {
        kind: "primary",
        trailing: "arrow",
      }),
    ),
    h(
      "div",
      { class: "feature-preview-grid" },
      c.features.map(([glyph, title, body]) =>
        h(
          "section",
          { class: "panel feature-preview" },
          icon(glyph, 23),
          h("h3", {}, title),
          h("p", {}, body),
        ),
      ),
    ),
    h(
      "div",
      { class: "inline-note" },
      icon("lock", 15),
      "This feature is not enabled. Use the real Files and Transfers workspaces; chat and sync are never simulated.",
    ),
  );
}
