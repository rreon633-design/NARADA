import { transfers } from "./pages/transfers.js";
import "./styles/app.css";
import "./styles/content.css";
import { h, replace } from "./lib/dom.js";
import { icon, logo } from "./components/icons.js";
import {
  badge,
  button,
  iconButton,
  modal,
  toggle,
  toast,
} from "./components/ui.js";
import { agent, isNative, mode, exportDiagnostics } from "./services/bridge.js";
import { home, activityRow } from "./pages/home.js";
import { settings } from "./pages/settings.js";
import { diagnostics, future } from "./pages/foundation.js";
import { chat } from "./pages/chat.js";
import { sync } from "./pages/sync.js";
import { files } from "./pages/files.js";
import { devices, clearPairingSecrets } from "./pages/devices.js";
import { validateName } from "./lib/format.js";

const routes = [
  {
    id: "home",
    label: "Home",
    icon: "home",
    hint: "Overview of your local workspace",
  },
  {
    id: "files",
    label: "Files",
    icon: "folder",
    hint: "Approved folders · Phase 3",
  },
  {
    id: "chat",
    label: "Chat",
    icon: "chat",
    hint: "Protected history and real delivery receipts",
  },
  {
    id: "transfers",
    label: "Transfers",
    icon: "transfer",
    hint: "Resumable file transfers · Phase 4",
  },
  {
    id: "sync",
    label: "Sync & offline",
    icon: "sync",
    hint: "Version-preserving pins and metadata freshness",
  },
  {
    id: "devices",
    label: "Devices",
    icon: "devices",
    hint: "Trusted-device pairing · Phase 2",
  },
  {
    id: "settings",
    label: "Settings",
    icon: "settings",
    hint: "Name, privacy, and interface preferences",
  },
  {
    id: "diagnostics",
    label: "Diagnostics",
    icon: "activity",
    hint: "Actual database and workspace health",
  },
];
const validRoute = (id) => (routes.some((r) => r.id === id) ? id : "home");
const state = {
  route: validRoute(location.hash.slice(1)),
  snapshot: null,
  connected: false,
  connecting: true,
  error: null,
  busy: false,
  dirty: false,
};
const root = document.getElementById("app");
const nav = h("nav", { class: "primary-nav", "aria-label": "Main navigation" });
const secondaryNav = h("nav", {
  class: "secondary-nav",
  "aria-label": "Support navigation",
});
const agentCard = h("div", { class: "sidebar-agent" });
const headerStatus = h("div", { class: "header-status" });
const breadcrumb = h("div", { class: "top-breadcrumb" });
const main = h("main", {
  class: "main-content",
  id: "main-content",
  tabindex: "-1",
});
const errorBanner = h("div", {
  class: "connection-banner",
  role: "status",
  hidden: true,
});
const topbar = h(
  "header",
  { class: "topbar" },
  h(
    "button",
    {
      class: "brand",
      onclick: () => navigate("home"),
      "aria-label": "NARADA home",
    },
    logo(32),
    h("span", { class: "brand-wordmark" }, "NARADA"),
    h("span", { class: "brand-divider" }),
  ),
  breadcrumb,
  h(
    "button",
    {
      class: "command-search",
      onclick: openSearch,
      "aria-label": "Search pages and settings",
    },
    icon("search", 16),
    h("span", {}, "Find something…"),
    h("kbd", {}, "Ctrl K"),
  ),
  h(
    "div",
    { class: "top-actions" },
    headerStatus,
    h("span", { class: "top-action-separator" }),
    iconButton("bell", "View activity", showActivity),
    iconButton("help", "Help and local-first guide", openHelp),
    h(
      "button",
      {
        class: "workspace-avatar",
        "aria-label": "Open workspace settings",
        onclick: () => navigate("settings"),
      },
      "N",
    ),
  ),
);
const sidebar = h(
  "aside",
  { class: "sidebar" },
  h("p", { class: "nav-overline" }, "WORKSPACE"),
  nav,
  h("div", { class: "sidebar-spacer" }),
  secondaryNav,
  h(
    "button",
    { class: "phase-card", onclick: openRoadmap },
    h(
      "span",
      { class: "phase-card-top" },
      h("span", { class: "phase-indicator" }),
      "VERIFIED FILE MOVEMENT",
      icon("arrowUp", 13),
    ),
    h("span", { class: "phase-card-title" }, "One good step at a time."),
    h(
      "span",
      { class: "phase-card-sub" },
      "Desktop preview",
      h("span", {}, "In progress"),
    ),
    h("span", { class: "phase-progress" }, h("i")),
  ),
  agentCard,
);
root.append(
  h(
    "a",
    {
      class: "skip-link",
      href: "#main-content",
      onclick: (e) => {
        e.preventDefault();
        main.focus();
      },
    },
    "Skip to main content",
  ),
  h(
    "div",
    { class: "application-shell" },
    topbar,
    sidebar,
    h("div", { class: "main-frame" }, errorBanner, main),
  ),
);

const ctx = {
  get snapshot() {
    return state.snapshot;
  },
  get connected() {
    return state.connected;
  },
  get busy() {
    return state.busy;
  },
  navigate,
  refresh,
  openSetup,
  openRoadmap,
  openHelp,
  showActivity,
  runDiagnostics,
  saveSettings,
  exportDiagnostics: () => {
    try {
      exportDiagnostics(state.snapshot);
      toast("Redacted diagnostics downloaded.");
    } catch (e) {
      toast(e.message, "error");
    }
  },
  setDirty(value) {
    state.dirty = value;
  },
};

let chromeKey;
function renderChrome() {
  const key = JSON.stringify([
    state.route,
    state.connected,
    state.connecting,
    state.error,
    state.snapshot?.settings,
    state.snapshot?.agent.version,
  ]);
  if (key === chromeKey) return;
  chromeKey = key;
  const current = routes.find((r) => r.id === state.route);
  replace(
    breadcrumb,
    icon(current.icon, 16),
    h("span", {}, current.label),
    h("span", { class: "breadcrumb-slash" }, "/"),
    h("span", { class: "breadcrumb-workspace" }, "Personal workspace"),
  );
  replace(
    nav,
    routes
      .filter((r) => r.id !== "diagnostics")
      .map((route) =>
        h(
          "button",
          {
            class: `nav-item ${state.route === route.id ? "active" : ""}`,
            "aria-current": state.route === route.id ? "page" : null,
            onclick: () => navigate(route.id),
            "aria-label": route.label,
            title: route.label,
          },
          icon(route.icon, 20),
          h("span", {}, route.label),
          state.route === route.id && icon("chevron", 15, "nav-chevron"),
        ),
      ),
  );
  replace(
    secondaryNav,
    h(
      "button",
      {
        class: `nav-item secondary ${state.route === "diagnostics" ? "active" : ""}`,
        "aria-current": state.route === "diagnostics" ? "page" : null,
        onclick: () => navigate("diagnostics"),
        "aria-label": "Diagnostics",
        title: "Diagnostics",
      },
      icon("activity", 19),
      h("span", {}, "Diagnostics"),
    ),
    h(
      "button",
      {
        class: "nav-item secondary",
        onclick: openHelp,
        "aria-label": "Help & getting started",
        title: "Help & getting started",
      },
      icon("help", 19),
      h("span", {}, "Help & getting started"),
      icon("arrowUp", 13, "nav-chevron"),
    ),
  );
  replace(
    headerStatus,
    h("span", { class: `small-dot ${state.connected ? "" : "off"}` }),
    h(
      "span",
      {},
      state.connected
        ? "Agent online"
        : state.connecting
          ? "Starting agent"
          : "Agent offline",
    ),
  );
  replace(
    agentCard,
    h("span", { class: "agent-avatar" }, icon("laptop", 19)),
    h(
      "span",
      { class: "agent-card-copy" },
      h("strong", {}, state.snapshot?.settings.device_name || "My workspace"),
      h(
        "span",
        {},
        h("span", { class: `small-dot ${state.connected ? "" : "off"}` }),
        mode,
      ),
    ),
    badge("0.7"),
  );
  errorBanner.hidden = !state.error;
  if (state.error)
    replace(
      errorBanner,
      icon("alert", 16),
      h(
        "span",
        {},
        (state.snapshot ? "Showing last loaded data. " : "") + state.error,
      ),
      button("Retry", () => refresh(true), { kind: "text", leading: "sync" }),
    );
  document.body.classList.toggle(
    "reduce-motion",
    Boolean(state.snapshot?.settings.reduce_motion),
  );
  document.title = `${current.label} · NARADA`;
}
function renderPage() {
  if (state.route === "settings" && state.dirty) return;
  main.firstElementChild?.dispose?.();
  const view =
    state.route === "home"
      ? home(ctx)
      : state.route === "settings"
        ? settings(ctx)
        : state.route === "diagnostics"
          ? diagnostics(ctx)
          : state.route === "files"
            ? files(ctx)
            : state.route === "devices"
              ? devices(ctx)
              : state.route === "transfers"
                ? transfers(ctx)
                : state.route === "chat"
                  ? chat(ctx)
                  : state.route === "sync"
                    ? sync(ctx)
                    : future(ctx, state.route);
  replace(main, view);
  main.dataset.route = state.route;
}
function render() {
  renderChrome();
  renderPage();
}
let refreshSequence = 0;
let appliedSequence = 0;
async function refresh(redraw = true) {
  const sequence = ++refreshSequence;
  const previous = state.connected;
  try {
    const snapshot = await agent.snapshot();
    if (sequence < appliedSequence) return;
    appliedSequence = sequence;
    state.snapshot = snapshot;
    state.connected = true;
    state.error = null;
  } catch (e) {
    if (sequence < appliedSequence) return;
    appliedSequence = sequence;
    state.connected = false;
    state.error = e.message;
  } finally {
    state.connecting = false;
    renderChrome();
    if (redraw || previous !== state.connected) renderPage();
    else if (state.route === "devices")
      main.firstElementChild?.updateConnections?.(state.snapshot);
    else if (state.route === "transfers")
      main.firstElementChild?.updateTransferConnection?.(
        state.snapshot,
        state.connected,
      );
    else if (["chat", "sync"].includes(state.route))
      main.firstElementChild?.updateContentConnection?.(
        state.snapshot,
        state.connected,
      );
    else if (state.route === "files")
      main.firstElementChild?.updateFileConnection?.(
        state.snapshot,
        state.connected,
      );
  }
}
function navigate(route, push = true) {
  route = validRoute(route);
  if (state.dirty && route === state.route) return;
  if (state.dirty && route !== state.route) {
    if (!push) history.pushState(null, "", `#${state.route}`);
    let dialog;
    dialog = modal({
      title: "Leave these changes behind?",
      eyebrow: "UNSAVED PREFERENCES",
      content: h(
        "p",
        { class: "muted" },
        "Your saved preferences are safe. Discard the edits on this page to continue.",
      ),
      actions: [
        button("Keep editing", () => dialog.close()),
        button(
          "Discard & continue",
          () => {
            state.dirty = false;
            dialog.close();
            navigate(route, true);
          },
          { kind: "primary" },
        ),
      ],
    });
    return;
  }
  if (state.route === "devices" && route !== "devices") clearPairingSecrets();
  state.route = route;
  state.dirty = false;
  if (push) history.pushState(null, "", `#${route}`);
  render();
  main.scrollTop = 0;
  queueMicrotask(() =>
    main.querySelector("h1")?.focus({ preventScroll: true }),
  );
}
async function saveSettings(value) {
  if (!state.connected)
    throw Error("Reconnect to the local agent before saving.");
  const saved = await agent.saveSettings(value);
  state.snapshot.settings = saved;
  state.dirty = false;
  toast("Preferences saved to your local workspace.");
  return saved;
}
async function runDiagnostics() {
  if (state.busy || !state.connected) return;
  state.busy = true;
  renderPage();
  try {
    const result = await agent.diagnostics();
    await refresh(false);
    state.snapshot.last_diagnostics = result;
    toast(
      result.checks.some((c) => c.status === "failed")
        ? "A check needs attention. Open diagnostics for details."
        : "Health check complete. Your local foundation looks good.",
    );
  } catch (e) {
    toast(e.message, "error");
  } finally {
    state.busy = false;
    renderPage();
  }
}
function openSetup() {
  if (!state.connected)
    return toast("Connect to the local agent before setting up.", "error");
  let step = 0;
  let draft = { ...state.snapshot.settings };
  const content = h("div", { class: "setup-wizard" });
  let instance;
  function draw() {
    const progress = h(
      "div",
      { class: "wizard-progress" },
      [0, 1, 2].map((i) => h("span", { class: i <= step ? "active" : "" })),
    );
    let body;
    let nameInput;
    const error = h("p", { class: "field-error", role: "alert" });
    if (step === 0) {
      nameInput = h("input", {
        class: "text-input large",
        id: "setup-name",
        value: draft.device_name,
        autocomplete: "off",
        maxlength: 96,
        oninput: (e) => {
          draft.device_name = e.target.value;
          error.textContent = validateName(draft.device_name) || "";
        },
      });
      body = h(
        "div",
        {},
        h("span", { class: "wizard-icon" }, icon("laptop", 27)),
        h("h3", {}, "Give this space a familiar name."),
        h(
          "p",
          { class: "muted" },
          "Something that feels like yours. You can change it any time.",
        ),
        h(
          "label",
          { class: "field-label", for: "setup-name" },
          "Workspace name",
        ),
        nameInput,
        error,
      );
    } else if (step === 1) {
      body = h(
        "div",
        {},
        h("span", { class: "wizard-icon" }, icon("shield", 27)),
        h("h3", {}, "Start with a little peace of mind."),
        h(
          "p",
          { class: "muted" },
          "These preferences stay in your local metadata. Messaging and notifications arrive in later phases.",
        ),
        toggle(
          "Private notification previews",
          "Keep future message bodies and filenames out of notifications.",
          draft.hide_notification_previews,
          (v) => (draft.hide_notification_previews = v),
        ),
        toggle(
          "Reduce interface motion",
          "A calmer workspace, with fewer transitions.",
          draft.reduce_motion,
          (v) => (draft.reduce_motion = v),
        ),
      );
    } else {
      body = h(
        "div",
        {},
        h("span", { class: "wizard-icon complete" }, icon("check", 27)),
        h("h3", {}, "A good foundation for what’s next."),
        h(
          "p",
          { class: "muted" },
          "Save your workspace preferences to the running local agent.",
        ),
        h(
          "div",
          { class: "setup-review" },
          h(
            "div",
            {},
            h("span", {}, "Workspace"),
            h("strong", {}, draft.device_name.trim()),
          ),
          h(
            "div",
            {},
            h("span", {}, "Storage"),
            h("strong", {}, "Local metadata"),
          ),
          h(
            "div",
            {},
            h("span", {}, "Peer networking"),
            h("strong", {}, "Disabled"),
          ),
          h(
            "div",
            {},
            h("span", {}, "Available now"),
            h("strong", {}, "Trusted desktop pairing"),
          ),
        ),
        error,
      );
    }
    const next = button(
      step === 2 ? "Make yourself at home" : "Continue",
      async () => {
        if (step === 0) {
          const invalid = validateName(draft.device_name);
          if (invalid) {
            error.textContent = invalid;
            nameInput.focus();
            return;
          }
        }
        if (step < 2) {
          step++;
          draw();
          return;
        }
        next.disabled = true;
        try {
          await saveSettings({ ...draft, setup_completed: true });
          instance.close();
          await refresh(true);
        } catch (e) {
          error.textContent = e.message;
          next.disabled = false;
        }
      },
      { kind: "primary", trailing: step === 2 ? "check" : "arrow" },
    );
    replace(
      content,
      progress,
      body,
      h(
        "div",
        { class: "wizard-actions" },
        step
          ? button(
              "Back",
              () => {
                step--;
                draw();
              },
              { leading: "back" },
            )
          : h("span", { class: "small muted" }, "1 minute, all local."),
        next,
      ),
    );
    if (nameInput)
      queueMicrotask(() => {
        nameInput.focus();
        nameInput.select();
      });
  }
  instance = modal({
    title: "Let’s make it yours.",
    eyebrow: "WELCOME TO NARADA",
    content,
    className: "setup-modal",
  });
  draw();
}
function openRoadmap() {
  const phases = [
    [
      "01",
      "Desktop foundation",
      "Local agent, durable preferences, app shell and diagnostics.",
      "Implemented",
    ],
    [
      "02",
      "Trusted connections",
      "Protected identities, real QR, mutual TLS and revocation. Desktop only.",
      "Implemented",
    ],
    [
      "03",
      "Your approved files",
      "Safe folder access, browsing, previews and recoverable mutations.",
      "Implemented",
    ],
    [
      "04",
      "Reliable transfers",
      "Bounded snapshots, verified chunks, explicit receive approval and resumable publication.",
      "Source implemented",
    ],
    [
      "05–06",
      "Private conversations",
      "Olm messages, protected history/outbox and authenticated age attachments. Review gates remain open.",
      "Source implemented",
    ],
    [
      "07",
      "Ready to go offline",
      "Stable metadata snapshots, scoped journals and version-preserving desktop pins.",
      "Source implemented",
    ],
    [
      "08–09",
      "A release you can trust",
      "Host checks and release tooling are provided. Native execution, independent review and signing remain external gates.",
      "Qualification pending",
    ],
  ];
  const content = h(
    "div",
    {},
    h(
      "p",
      { class: "muted" },
      "One reliable layer at a time. The interface never pretends a future capability is already connected.",
    ),
    h(
      "div",
      { class: "roadmap-list" },
      phases.map(([num, title, desc, status], i) =>
        h(
          "div",
          { class: `roadmap-row ${i === 1 ? "current" : ""}` },
          h("span", { class: "roadmap-number" }, num),
          h(
            "div",
            { class: "roadmap-copy" },
            h("h3", {}, title),
            h("p", {}, desc),
          ),
          badge(status, i === 1 ? "success" : "neutral"),
        ),
      ),
    ),
    h(
      "div",
      { class: "inline-note" },
      icon("info", 16),
      "Desktop phases 1–2 still need native Windows and security qualification. Android is not implemented in this slice.",
    ),
  );
  modal({
    title: "Good things, built carefully.",
    eyebrow: "THE NARADA ROADMAP",
    content,
    className: "roadmap-modal",
  });
}
function openHelp() {
  modal({
    title: "Closer together. Still yours.",
    eyebrow: "THE LOCAL-FIRST WAY",
    content: h(
      "div",
      { class: "help-content" },
      h(
        "p",
        { class: "help-lead" },
        "NARADA is being built to connect a Windows laptop and Android phone without a mandatory account or cloud.",
      ),
      h(
        "div",
        { class: "help-point" },
        h("span", {}, icon("laptop", 22)),
        h(
          "div",
          {},
          h("h3", {}, "It begins on your desktop."),
          h(
            "p",
            {},
            "This phase runs a real Rust agent, stores preferences in SQLite, and gives your workspace a home.",
          ),
        ),
      ),
      h(
        "div",
        { class: "help-point" },
        h("span", {}, icon("shield", 22)),
        h(
          "div",
          {},
          h("h3", {}, "You choose the connection."),
          h(
            "p",
            {},
            "Desktop pairing is now real: opt-in networking, a one-time QR, private-key proof and your approval. No folders or chat endpoints are exposed. Android comes later.",
          ),
        ),
      ),
      h(
        "div",
        { class: "help-point" },
        h("span", {}, icon("lock", 22)),
        h(
          "div",
          {},
          h("h3", {}, "No pretend privacy promises."),
          h(
            "p",
            {},
            "The current database holds non-sensitive metadata. Chat encryption, safe file access, and Windows release qualification remain explicit gates.",
          ),
        ),
      ),
      h(
        "div",
        { class: "help-build-note" },
        badge(mode),
        h(
          "p",
          {},
          isNative
            ? "This window communicates with the user agent through local OS-protected IPC."
            : "This browser talks to the development agent through a same-origin proxy. The preview is not the native Windows application.",
        ),
      ),
    ),
    actions: [
      button(
        "Explore the roadmap",
        () => {
          document.querySelector("dialog[open]")?.close();
          openRoadmap();
        },
        { kind: "primary", trailing: "arrow" },
      ),
    ],
  });
}
function showActivity() {
  const list = h(
    "div",
    { class: "drawer-activity-list" },
    state.snapshot?.activity.length
      ? state.snapshot.activity.map(activityRow)
      : h("p", { class: "quiet-empty" }, "No activity is available yet."),
  );
  let dialog;
  dialog = modal({
    title: "Workspace activity",
    eyebrow: "THE LITTLE THINGS",
    content: h(
      "div",
      {},
      h(
        "p",
        { class: "muted" },
        "Local events from your running agent. No file or message content is included.",
      ),
      list,
    ),
    className: "activity-drawer",
    actions: [
      button(
        "Clear history",
        () => {
          let confirmation;
          confirmation = modal({
            title: "Clear local activity?",
            content: h(
              "p",
              { class: "muted" },
              "This removes only activity history. Workspace preferences and health-check results are preserved.",
            ),
            actions: [
              button("Keep history", () => confirmation.close()),
              button(
                "Clear history",
                async () => {
                  try {
                    await agent.clearActivity();
                    confirmation.close();
                    dialog.close();
                    await refresh(true);
                    toast("Local activity history cleared.");
                  } catch (e) {
                    toast(e.message, "error");
                  }
                },
                { kind: "primary" },
              ),
            ],
          });
        },
        { disabled: !state.connected },
      ),
      button("Done", () => dialog.close(), { kind: "primary" }),
    ],
  });
}
function openSearch() {
  const results = h("div", { class: "command-results" });
  let dialog;
  const input = h("input", {
    class: "command-input",
    type: "search",
    placeholder: "Go somewhere in your workspace…",
    "aria-label": "Search pages and settings",
    autocomplete: "off",
    oninput: draw,
    onkeydown: (e) => {
      if (e.key === "Enter") results.querySelector("button")?.click();
      if (e.key === "ArrowDown") {
        e.preventDefault();
        results.querySelector("button")?.focus();
      }
    },
  });
  function draw() {
    const term = input.value.toLowerCase();
    const matches = routes.filter((r) =>
      `${r.label} ${r.hint}`.toLowerCase().includes(term),
    );
    replace(
      results,
      matches.length
        ? matches.map((r) =>
            h(
              "button",
              {
                class: "command-result",
                onclick: () => {
                  dialog.close();
                  navigate(r.id);
                },
              },
              icon(r.icon, 19),
              h("span", {}, h("strong", {}, r.label), h("small", {}, r.hint)),
              icon("arrow", 15),
            ),
          )
        : h(
            "p",
            { class: "quiet-empty" },
            "No matching pages. Try “settings” or “health”.",
          ),
    );
  }
  dialog = modal({
    title: "A shortcut to your space.",
    eyebrow: "QUICK NAVIGATION",
    content: h(
      "div",
      {},
      h("div", { class: "command-input-wrap" }, icon("search", 20), input),
      results,
      h(
        "p",
        { class: "command-hint" },
        "Enter to open the first result",
        h("kbd", {}, "Esc"),
        "to close",
      ),
    ),
    className: "command-modal",
  });
  draw();
  input.focus();
}
window.addEventListener("popstate", () =>
  navigate(location.hash.slice(1), false),
);
window.addEventListener("keydown", (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
    e.preventDefault();
    if (!document.querySelector("dialog[open]")) openSearch();
  }
});
window.addEventListener("beforeunload", (e) => {
  if (state.dirty) {
    e.preventDefault();
    e.returnValue = "";
  }
});
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) refresh(false);
});
render();
refresh(true);
let lastPoll = 0;
setInterval(() => {
  if (
    !document.hidden &&
    (["devices", "files"].includes(state.route) ||
      Date.now() - lastPoll >= 15000)
  ) {
    lastPoll = Date.now();
    refresh(false);
  }
}, 1500);
