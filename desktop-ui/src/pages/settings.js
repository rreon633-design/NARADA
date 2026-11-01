import { h } from "../lib/dom.js";
import { icon } from "../components/icons.js";
import {
  badge,
  button,
  pageHeader,
  panelHead,
  toggle,
} from "../components/ui.js";
import { validateName, settingsEqual, platformName } from "../lib/format.js";
import { isNative } from "../services/bridge.js";
export function settings(ctx) {
  if (!ctx.snapshot)
    return h(
      "div",
      { class: "page" },
      pageHeader(
        "PREFERENCES",
        "Workspace settings",
        "Connect to the local agent to load your saved preferences.",
      ),
      button("Retry connection", ctx.refresh, { leading: "sync" }),
    );
  const original = { ...ctx.snapshot.settings };
  const draft = { ...original };
  const validation = h("p", {
    class: "field-error",
    id: "name-error",
    role: "alert",
  });
  const saveButton = button("Save changes", save, {
    kind: "primary",
    leading: "check",
    disabled: true,
  });
  const saveStatus = h(
    "span",
    { class: "save-status" },
    icon("check", 14),
    "All changes saved locally",
  );
  const cancelButton = button(
    "Discard changes",
    () => {
      ctx.setDirty(false);
      ctx.refresh(true);
    },
    { disabled: true },
  );
  function changed() {
    const dirty = !settingsEqual(original, draft);
    ctx.setDirty(dirty);
    saveButton.disabled =
      !dirty || Boolean(validateName(draft.device_name)) || !ctx.connected;
    cancelButton.disabled = !dirty;
    saveStatus.replaceChildren(
      icon(dirty ? "info" : "check", 14),
      document.createTextNode(
        dirty ? "You have unsaved changes" : "All changes saved locally",
      ),
    );
    saveStatus.classList.toggle("unsaved", dirty);
  }
  async function save() {
    const error = validateName(draft.device_name);
    validation.textContent = error || "";
    if (error) return;
    saveButton.disabled = true;
    try {
      await ctx.saveSettings(draft);
      ctx.setDirty(false);
      await ctx.refresh(true);
    } catch (e) {
      validation.textContent = e.message;
      changed();
    }
  }
  const nameInput = h("input", {
    class: "text-input",
    id: "device-name",
    value: draft.device_name,
    maxlength: 96,
    autocomplete: "off",
    "aria-describedby": "name-help name-error",
    oninput: (e) => {
      draft.device_name = e.target.value;
      validation.textContent = validateName(draft.device_name) || "";
      changed();
    },
    disabled: !ctx.connected,
  });
  const change = (key) => (value) => {
    draft[key] = value;
    changed();
  };
  const tabGeneral = button(
    "General",
    () => general.scrollIntoView({ behavior: "smooth", block: "start" }),
    { kind: "tab", leading: "sliders", className: "selected" },
  );
  const general = h(
    "section",
    { class: "panel settings-section", id: "general" },
    panelHead("General", "The details that make this space yours."),
    h(
      "div",
      { class: "name-setting" },
      h(
        "label",
        { class: "setting-title", for: "device-name" },
        "Workspace name",
      ),
      h(
        "p",
        { class: "setting-description", id: "name-help" },
        "A familiar name for this desktop. Stored only in local metadata.",
      ),
      h(
        "div",
        { class: "name-input-wrap" },
        h("span", { class: "input-leading" }, icon("laptop", 18)),
        nameInput,
      ),
      validation,
    ),
    toggle(
      "Close to system tray",
      isNative
        ? "Keep the desktop shell in the tray when the window closes."
        : "Saved for the native Windows app. Browsers do not have a system tray.",
      draft.close_to_tray,
      change("close_to_tray"),
    ),
    toggle(
      "Reduce motion",
      "Keep transitions quiet and minimize interface animations.",
      draft.reduce_motion,
      change("reduce_motion"),
    ),
    h(
      "div",
      { class: "setting-row" },
      h(
        "div",
        { class: "setting-label" },
        h("span", { class: "setting-title" }, "Start with Windows"),
        h(
          "span",
          { class: "setting-description" },
          "Startup registration is not enabled in this development build.",
        ),
      ),
      badge("Not implemented"),
    ),
  );
  const privacy = h(
    "section",
    { class: "panel settings-section", id: "privacy" },
    panelHead(
      "Privacy & notifications",
      "Thoughtful defaults. You stay in control.",
    ),
    toggle(
      "Allow notifications",
      "Preference for upcoming transfer and chat notifications; delivery is not yet implemented.",
      draft.notifications_enabled,
      change("notifications_enabled"),
    ),
    toggle(
      "Keep notification contents private",
      "Hide message bodies and private filenames in future notifications.",
      draft.hide_notification_previews,
      change("hide_notification_previews"),
    ),
    h(
      "div",
      { class: "policy-note" },
      icon("shield", 18),
      h(
        "div",
        {},
        h("strong", {}, "Local-first is not a toggle."),
        h(
          "p",
          {},
          "Peer networking is opt-in and local-only. Private history, drafts and E2EE state live in a separate protected store. No cloud account or external telemetry is enabled.",
        ),
      ),
    ),
  );
  const about = h(
    "section",
    { class: "panel settings-section", id: "about" },
    panelHead("About this build", "An honest look at where things stand."),
    h(
      "dl",
      { class: "about-list" },
      h(
        "div",
        {},
        h("dt", {}, "Application"),
        h("dd", {}, "NARADA ", badge("0.3.0")),
      ),
      h(
        "div",
        {},
        h("dt", {}, "Current phase"),
        h("dd", {}, "01 · Desktop foundation"),
      ),
      h(
        "div",
        {},
        h("dt", {}, "Runtime"),
        h("dd", {}, platformName(ctx.snapshot.agent.platform)),
      ),
      h(
        "div",
        {},
        h("dt", {}, "Sensitive storage"),
        h("dd", {}, "Not implemented · no secrets stored"),
      ),
    ),
    button("View implementation roadmap", ctx.openRoadmap, {
      kind: "text",
      trailing: "arrow",
    }),
  );
  return h(
    "div",
    { class: "page settings-page" },
    pageHeader(
      "MAKE IT YOURS",
      "Workspace settings",
      "Small preferences. A space that feels like you.",
    ),
    h(
      "div",
      { class: "settings-layout" },
      h(
        "aside",
        { class: "settings-nav", "aria-label": "Settings sections" },
        tabGeneral,
        button(
          "Privacy & notifications",
          () => privacy.scrollIntoView({ behavior: "smooth" }),
          { kind: "tab", leading: "shield" },
        ),
        button(
          "About this build",
          () => about.scrollIntoView({ behavior: "smooth" }),
          { kind: "tab", leading: "info" },
        ),
        button("Diagnostics", () => ctx.navigate("diagnostics"), {
          kind: "tab",
          leading: "activity",
        }),
      ),
      h("div", { class: "settings-content" }, general, privacy, about),
    ),
    h(
      "div",
      { class: "savebar" },
      saveStatus,
      h("div", { class: "button-group" }, cancelButton, saveButton),
    ),
  );
}
