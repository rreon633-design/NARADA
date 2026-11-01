import { h, announce } from "../lib/dom.js";
import { icon } from "./icons.js";
export const badge = (text, kind = "neutral") =>
  h("span", { class: `badge badge-${kind}` }, text);
export const button = (
  text,
  onclick,
  {
    kind = "secondary",
    leading,
    trailing,
    disabled = false,
    className = "",
    ...attrs
  } = {},
) =>
  h(
    "button",
    {
      type: "button",
      class: `btn btn-${kind} ${className}`,
      onclick,
      disabled,
      ...attrs,
    },
    leading && icon(leading, 16),
    h("span", {}, text),
    trailing && icon(trailing, 16),
  );
export const iconButton = (name, label, onclick, attrs = {}) =>
  h(
    "button",
    {
      type: "button",
      class: "icon-button",
      "aria-label": label,
      title: label,
      onclick,
      ...attrs,
    },
    icon(name, 19),
  );
export function pageHeader(eyebrow, title, description, actions) {
  return h(
    "div",
    { class: "page-header" },
    h(
      "div",
      {},
      h("p", { class: "eyebrow" }, eyebrow),
      h("h1", { tabindex: "-1" }, title),
      h("p", { class: "page-description" }, description),
    ),
    actions && h("div", { class: "page-actions" }, actions),
  );
}
export const panelHead = (title, subtitle, action) =>
  h(
    "div",
    { class: "panel-head" },
    h(
      "div",
      {},
      h("h2", {}, title),
      subtitle && h("p", { class: "muted small" }, subtitle),
    ),
    action,
  );
export const divider = () => h("div", { class: "divider" });
export function toast(text, type = "success") {
  document.querySelector(".toast")?.remove();
  const el = h(
    "div",
    { class: `toast toast-${type}`, role: "status" },
    icon(type === "error" ? "alert" : "check", 17),
    h("span", {}, text),
  );
  document.body.append(el);
  setTimeout(() => el.remove(), 4200);
  announce(text);
}
export function modal({
  title,
  eyebrow,
  content,
  actions = [],
  className = "",
}) {
  const previous = document.activeElement;
  const id = `dialog-${crypto.randomUUID()}`;
  const dialog = h("dialog", {
    class: `modal ${className}`,
    "aria-labelledby": id,
  });
  const close = () => dialog.close();
  dialog.append(
    h(
      "div",
      { class: "modal-header" },
      h(
        "div",
        {},
        eyebrow && h("p", { class: "eyebrow" }, eyebrow),
        h("h2", { id }, title),
      ),
      iconButton("close", "Close dialog", close),
    ),
    h("div", { class: "modal-content" }, content),
  );
  if (actions.length)
    dialog.append(h("div", { class: "modal-footer" }, actions));
  dialog.addEventListener("click", (e) => {
    if (e.target === dialog) {
      const r = dialog.getBoundingClientRect();
      if (
        e.clientX < r.left ||
        e.clientX > r.right ||
        e.clientY < r.top ||
        e.clientY > r.bottom
      )
        close();
    }
  });
  dialog.addEventListener(
    "close",
    () => {
      dialog.remove();
      if (previous?.isConnected) previous.focus();
    },
    { once: true },
  );
  document.getElementById("overlays").append(dialog);
  dialog.showModal();
  return { dialog, close };
}
export function toggle(
  label,
  description,
  value,
  onChange,
  { disabled = false, id } = {},
) {
  const input = h("input", {
    type: "checkbox",
    class: "switch-input",
    checked: value,
    disabled,
    id: id || `switch-${crypto.randomUUID()}`,
    onchange: (e) => onChange(e.target.checked),
  });
  const control = h(
    "span",
    { class: "switch-track", "aria-hidden": "true" },
    h("span", { class: "switch-thumb" }),
  );
  return h(
    "label",
    { class: `setting-row ${disabled ? "is-disabled" : ""}`, for: input.id },
    h(
      "span",
      { class: "setting-label" },
      h("span", { class: "setting-title" }, label),
      h("span", { class: "setting-description" }, description),
    ),
    h("span", { class: "switch" }, input, control),
  );
}
export function deviceArt() {
  const wrap = h("div", { class: "device-art", "aria-hidden": "true" });
  wrap.innerHTML = `<svg viewBox="0 0 420 240" fill="none" xmlns="http://www.w3.org/2000/svg">
  <defs><linearGradient id="screen" x1="80" y1="60" x2="230" y2="190" gradientUnits="userSpaceOnUse"><stop stop-color="#f9faf6"/><stop offset="1" stop-color="#e9ece5"/></linearGradient><filter id="shadow" x="-30%" y="-30%" width="160%" height="180%"><feDropShadow dx="0" dy="9" stdDeviation="8" flood-color="#20281b" flood-opacity=".08"/></filter></defs>
  <circle cx="215" cy="128" r="101" stroke="#dce1d6" stroke-dasharray="2 7"/><circle cx="215" cy="128" r="78" stroke="#e8ebe3"/>
  <path d="M245 131h71" stroke="#aeb9a6" stroke-dasharray="3 5"/><circle cx="265" cy="131" r="4" fill="#7d9071"/><circle cx="286" cy="131" r="3" fill="#b3bda9"/>
  <g filter="url(#shadow)"><rect x="64" y="64" width="188" height="128" rx="10" fill="#fff" stroke="#454d42" stroke-width="2"/><rect x="72" y="72" width="172" height="110" rx="5" fill="url(#screen)"/><path d="M52 193h212l-7 9H60z" fill="#fafbf8" stroke="#454d42" stroke-width="1.8"/><path d="M142 193h32l-3 4h-26z" fill="#d5dad0"/>
  <rect x="137" y="106" width="40" height="40" rx="11" fill="#21271f"/><path d="M148 135v-18h4l10 12v-12h4v18h-4l-10-12v12z" fill="white"/>
  <rect x="296" y="86" width="56" height="111" rx="11" fill="#fff" stroke="#454d42" stroke-width="2"/><rect x="301" y="92" width="46" height="99" rx="7" fill="url(#screen)"/><path d="M318 96h12" stroke="#454d42" stroke-width="3" stroke-linecap="round"/><rect x="313" y="129" width="23" height="23" rx="7" fill="#21271f"/><path d="M319 146v-11h3l4 6v-6h3v11h-3l-4-6v6z" fill="white"/><path d="M319 185h11" stroke="#a9b0a3" stroke-width="2" stroke-linecap="round"/></g>
  <g filter="url(#shadow)"><rect x="191" y="34" width="135" height="28" rx="14" fill="white" stroke="#e2e6dc"/><circle cx="205" cy="48" r="3" fill="#768a69"/><text x="215" y="51.5" fill="#4f5d47" font-family="Segoe UI,Arial,sans-serif" font-size="8.5" letter-spacing="1.1">LOCAL BY DESIGN</text></g>
  <rect x="251" y="172" width="37" height="37" rx="12" fill="#fafcf7" stroke="#d8dfd0"/><path d="M264 187v-3a5 5 0 0 1 10 0v3m-12 0h14v11h-14z" stroke="#5c6e50" stroke-width="1.5"/><path d="M269 191v3" stroke="#5c6e50" stroke-width="1.5"/>
  </svg>`;
  return wrap;
}
