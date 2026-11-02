import { h } from "../lib/dom.js";
import { icon } from "../components/icons.js";
import {
  badge,
  button,
  panelHead,
  pageHeader,
  deviceArt,
} from "../components/ui.js";
import { bytes, relativeTime, uptime } from "../lib/format.js";

export function home(ctx) {
  const s = ctx.snapshot;
  const completed =
    Number(ctx.connected) +
    Number(Boolean(s?.settings.setup_completed)) +
    Number(Boolean(s?.last_diagnostics));
  const stats = [
    {
      icon: "folder",
      label: "Added folders",
      value: String(s?.shared_roots ?? 0).padStart(2, "0"),
      detail: "Sharing is granted separately",
      route: "files",
    },
    {
      icon: "devices",
      label: "Paired devices",
      value: String(s?.paired_devices ?? 0).padStart(2, "0"),
      detail: "Approved devices",
      route: "devices",
    },
    {
      icon: "transfer",
      label: "Active transfers",
      value: String(s?.active_transfers ?? 0).padStart(2, "0"),
      detail: "Current transfer jobs",
      route: "transfers",
    },
    {
      icon: "database",
      label: "Local data",
      value: s ? bytes(s.storage.database_bytes) : "—",
      detail: "Metadata stored on this host",
      route: "diagnostics",
    },
  ];
  return h(
    "div",
    { class: "page home-page" },
    pageHeader(
      "YOUR WORKSPACE",
      "Welcome to your own space.",
      null,
      h(
        "span",
        { class: "date-label" },
        icon("clock", 14),
        new Date().toLocaleDateString(undefined, {
          month: "short",
          day: "numeric",
          year: "numeric",
        }),
      ),
    ),
    h(
      "section",
      { class: "welcome-panel", "aria-label": "Welcome to NARADA" },
      h(
        "div",
        { class: "welcome-copy" },
        h(
          "span",
          { class: "hero-kicker" },
          h("span", { class: "small-dot" }),
          "A CLOSER CONNECTION",
        ),
        h("h2", {}, "Your devices. ", h("br"), "Your private corner."),
        h("p", {}, "Connect your computer and phone without a cloud account."),
        h(
          "div",
          { class: "hero-actions" },
          button(
            s?.settings.setup_completed
              ? "Connect your phone"
              : "Set up this computer",
            () =>
              s?.settings.setup_completed
                ? ctx.navigate("devices")
                : ctx.openSetup(),
            { kind: "primary", trailing: "arrow", disabled: !ctx.connected },
          ),
          s?.settings.setup_completed &&
            button("Computer settings", () => ctx.navigate("settings"), {
              kind: "text",
            }),
          button("How it works", ctx.openHelp, {
            kind: "text",
            trailing: "chevron",
          }),
        ),
      ),
      deviceArt(),
    ),
    h(
      "section",
      { class: "stats-grid", "aria-label": "Workspace overview" },
      stats.map((stat) =>
        h(
          "button",
          { class: "stat-card", onclick: () => ctx.navigate(stat.route) },
          h(
            "div",
            { class: "stat-top" },
            h("span", { class: "stat-label" }, stat.label),
            icon(stat.icon, 19),
          ),
          h("div", { class: "stat-number" }, stat.value),
          h(
            "div",
            { class: "stat-bottom" },
            h("span", {}, stat.detail),
            icon("arrowUp", 13),
          ),
        ),
      ),
    ),
    h(
      "div",
      { class: "dashboard-columns" },
      h(
        "div",
        { class: "dashboard-primary" },
        h(
          "section",
          { class: "panel setup-panel" },
          panelHead("Get started", null, badge(`${completed} of 3 ready`)),
          h(
            "div",
            { class: "setup-steps" },
            step(
              "01",
              ctx.connected
                ? "Local service is ready"
                : "Reconnect the local service",
              "Local service status, not a phone connection.",
              ctx.connected,
              () => ctx.navigate("diagnostics"),
              "View status",
            ),
            step(
              "02",
              "Name this computer",
              "A device label, not a folder.",
              Boolean(s?.settings.setup_completed),
              ctx.openSetup,
              s?.settings.setup_completed ? "Edit setup" : "Personalize",
              !ctx.connected,
            ),
            step(
              "03",
              "Check this computer",
              "Check local storage and service status.",
              Boolean(s?.last_diagnostics),
              ctx.runDiagnostics,
              s?.last_diagnostics ? "Check again" : "Run check",
              ctx.busy || !ctx.connected,
            ),
          ),
          h(
            "div",
            { class: "setup-footnote" },
            icon("info", 14),
            "Connect your phone in Devices. Share folders separately in Files.",
          ),
        ),
        h(
          "section",
          { class: "panel recent-panel" },
          panelHead(
            "Recent activity",
            null,
            button("View all", ctx.showActivity, {
              kind: "text",
              trailing: "chevron",
            }),
          ),
          h(
            "div",
            { class: "activity-list" },
            s?.activity.length
              ? s.activity.slice(0, 3).map((item) => activityRow(item))
              : h(
                  "div",
                  { class: "quiet-empty" },
                  icon("clock", 20),
                  h(
                    "span",
                    {},
                    ctx.connected
                      ? "New activity appears here."
                      : "Waiting for the local agent…",
                  ),
                ),
          ),
        ),
      ),
      h(
        "aside",
        { class: "dashboard-secondary" },
        h(
          "section",
          { class: "privacy-card" },
          h("div", { class: "privacy-icon" }, icon("shield", 26)),
          h("span", { class: "eyebrow" }, "YOURS MEANS YOURS"),
          h("h2", {}, "Private is the\ndefault."),
          h(
            "p",
            {},
            "Connections and sharing stay off until you approve them.",
          ),
          h("div", { class: "privacy-rule" }),
          h(
            "div",
            { class: "privacy-point" },
            icon("check", 15),
            "No account to create",
          ),
          h(
            "div",
            { class: "privacy-point" },
            icon("check", 15),
            "No mandatory cloud",
          ),
          h(
            "div",
            { class: "privacy-point" },
            icon("check", 15),
            "You decide what’s shared",
          ),
          button("Our local-first approach", ctx.openHelp, {
            kind: "text",
            trailing: "arrow",
          }),
        ),
        h(
          "section",
          { class: "panel system-card" },
          panelHead("Under the hood", null, icon("server", 17)),
          statusLine(
            "Local agent",
            ctx.connected ? "Running" : "Unavailable",
            ctx.connected,
          ),
          statusLine("Metadata", s ? "SQLite · WAL" : "Not connected"),
          statusLine(
            "Peer network",
            s?.connections?.listening ? "Enabled · TLS 1.3" : "Disabled",
          ),
          statusLine("Agent uptime", s ? uptime(s.agent.uptime_seconds) : "—"),
          button("Open diagnostics", () => ctx.navigate("diagnostics"), {
            kind: "text",
            trailing: "arrow",
            className: "full-width",
          }),
        ),
      ),
    ),
    h(
      "div",
      { class: "workspace-bottom" },
      h(
        "span",
        {},
        icon("lock", 13),
        "A local foundation. Built with intention.",
      ),
      h(
        "span",
        {},
        "NARADA 0.7.1",
        h("span", { class: "separator-dot" }, "·"),
        "Trusted desktop connections",
      ),
    ),
  );
}
function step(number, title, description, done, action, actionLabel, disabled) {
  return h(
    "div",
    { class: "setup-step" },
    h(
      "span",
      { class: `step-marker ${done ? "done" : ""}` },
      done ? icon("check", 16) : number,
    ),
    h(
      "div",
      { class: "step-copy" },
      h("h3", {}, title),
      h("p", {}, description),
    ),
    button(actionLabel, action, {
      kind: "text",
      trailing: "chevron",
      disabled,
    }),
  );
}
function statusLine(label, value, online = false) {
  return h(
    "div",
    { class: "status-line" },
    h("span", {}, label),
    h(
      "span",
      { class: online ? "status-online" : "" },
      online && h("span", { class: "small-dot" }),
      value,
    ),
  );
}
export function activityRow(item) {
  const glyph =
    {
      agent: "server",
      settings: "settings",
      diagnostics: "activity",
      maintenance: "clock",
    }[item.kind] || "info";
  return h(
    "div",
    { class: "activity-row" },
    h(
      "span",
      { class: `activity-glyph activity-${item.kind}` },
      icon(glyph, 17),
    ),
    h(
      "div",
      { class: "activity-copy" },
      h("strong", {}, item.title),
      h("p", {}, item.detail),
    ),
    h(
      "time",
      {
        datetime: item.created_at,
        title: new Date(item.created_at).toLocaleString(),
      },
      relativeTime(item.created_at),
    ),
  );
}
