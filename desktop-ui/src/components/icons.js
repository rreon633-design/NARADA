const paths = {
  send: '<path d="m3 3 18 9-18 9 4-9zM7 12h14"/>',
  pin: '<path d="m8 3 8 0-1 6 4 4v2H5v-2l4-4zM12 15v7"/>',
  copy: '<rect x="8" y="8" width="13" height="13" rx="2"/><path d="M16 8V3H3v13h5"/>',
  pause: '<path d="M8 5v14M16 5v14" stroke-width="3"/>',
  play: '<path d="m8 4 12 8-12 8z"/>',
  inbox:
    '<path d="m3 4-2 13v4h22v-4L21 4zM2 15h6l2 3h4l2-3h6M12 4v8m-3-3 3 3 3-3"/>',
  trash: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7"/>',
  edit: '<path d="m15 4 5 5M4 20l5-1L21 7l-5-5L4 14z"/>',
  eye: '<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>',
  sliders:
    '<path d="M4 7h16M4 17h16"/><circle cx="9" cy="7" r="3" fill="currentColor"/><circle cx="15" cy="17" r="3" fill="currentColor"/>',
  home: '<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z"/>',
  folder:
    '<path d="M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  chat: '<path d="M21 11.5a8.5 8.5 0 0 1-8.5 8.5 9 9 0 0 1-4-.9L3 21l1.9-5.5a9 9 0 0 1-.9-4A8.5 8.5 0 0 1 12.5 3h.5a8.5 8.5 0 0 1 8 8z"/>',
  transfer: '<path d="M7 3v16m-4-4 4 4 4-4M17 21V5m-4 4 4-4 4 4"/>',
  sync: '<path d="M20 8a8 8 0 0 0-14-3L3 8m0-5v5h5M4 16a8 8 0 0 0 14 3l3-3m0 5v-5h-5"/>',
  devices:
    '<rect x="3" y="3" width="14" height="12" rx="2"/><path d="M7 21h6m-3-6v6"/><rect x="16" y="10" width="6" height="11" rx="1.5"/>',
  laptop:
    '<rect x="4" y="3" width="16" height="13" rx="2"/><path d="m2 20 2-4h16l2 4zM9 20h6"/>',
  phone:
    '<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M10 5h4m-3 14h2"/>',
  settings:
    '<path d="m9 3-.6 2.3-2 .9-2.1-.7-2 3.4L4 10.5v3l-1.7 1.6 2 3.4 2.1-.7 2 .9L9 21h4l.6-2.3 2-.9 2.1.7 2-3.4-1.7-1.6v-3l1.7-1.6-2-3.4-2.1.7-2-.9L13 3z"/><circle cx="11" cy="12" r="3"/>',
  activity: '<path d="M3 12h4l3-8 4 16 3-8h4"/>',
  shield:
    '<path d="m12 3 8 4v6c0 5-8 8-8 8s-8-3-8-8V7z"/><path d="m9 12 2 2 4-4"/>',
  lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 5v2"/>',
  search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
  arrow: '<path d="M4 12h16m-6-6 6 6-6 6"/>',
  arrowUp: '<path d="M6 18 18 6M6 6h12v12"/>',
  chevron: '<path d="m9 5 7 7-7 7"/>',
  chevronDown: '<path d="m6 9 6 6 6-6"/>',
  back: '<path d="M20 12H4m6-6-6 6 6 6"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>',
  bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/>',
  help: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9a2.5 2.5 0 1 1 4 2c-1.5.8-1.5 1.5-1.5 2m0 3h.01"/>',
  database:
    '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 4 16 4 16 0V5M4 12c0 4 16 4 16 0"/>',
  server:
    '<rect x="3" y="3" width="18" height="7" rx="2"/><rect x="3" y="14" width="18" height="7" rx="2"/><path d="M7 6.5h.01M7 17.5h.01m5-11h5m-5 11h5"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10h.01"/>',
  alert: '<path d="m12 3 10 18H2zM12 9v5m0 3h.01"/>',
  download: '<path d="M12 3v12m-5-5 5 5 5-5M4 15v5h16v-5"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
  list: '<path d="M9 6h12M9 12h12M9 18h12M3 6h.01M3 12h.01M3 18h.01"/>',
  file: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M8 13h8m-8 4h5"/>',
  more: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
  leaf: '<path d="M20 3C9 2 3 8 5 15c1 4 6 5 9 3 5-3 6-8 6-15ZM4 21l10-11"/>',
  link: '<path d="m10 13 4-4M8 15l-2 2a3 3 0 0 1-4-4l5-5a3 3 0 0 1 4 0m2 8a3 3 0 0 0 4 0l5-5a3 3 0 0 0-4-4l-2 2"/>',
  command:
    '<path d="M9 7V5a2 2 0 1 0-2 2h10a2 2 0 1 0-2-2v14a2 2 0 1 0 2-2H7a2 2 0 1 0 2 2z"/>',
};
export function icon(name, size = 20, className = "") {
  const el = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  for (const [k, v] of Object.entries({
    viewBox: "0 0 24 24",
    width: size,
    height: size,
    fill: "none",
    stroke: "currentColor",
    "stroke-width": "1.65",
    "stroke-linecap": "round",
    "stroke-linejoin": "round",
    "aria-hidden": "true",
    class: `icon ${className}`,
  }))
    el.setAttribute(k, String(v));
  el.innerHTML = paths[name] || paths.info; // Constant application-owned SVG paths; never user content.
  return el;
}
export function logo(size = 34) {
  const el = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  el.setAttribute("viewBox", "0 0 64 64");
  el.setAttribute("width", size);
  el.setAttribute("height", size);
  el.setAttribute("aria-hidden", "true");
  el.innerHTML =
    '<rect width="64" height="64" rx="18" fill="#171a17"/><path d="M17 45V20h6l18 19V20h6v25h-6L23 26v19z" fill="white"/><path d="M23 20h18" stroke="#80867e" stroke-width="2"/>';
  return el;
}
