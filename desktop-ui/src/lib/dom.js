export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (key === "class") el.className = value;
    else if (key === "text") el.textContent = value;
    else if (key === "checked") el.checked = Boolean(value);
    else if (key === "value") el.value = value;
    else if (key === "disabled") el.disabled = Boolean(value);
    else if (key.startsWith("on") && typeof value === "function")
      el.addEventListener(key.slice(2).toLowerCase(), value);
    else el.setAttribute(key, value === true ? "" : String(value));
  }
  const append = (child) => {
    if (Array.isArray(child)) child.forEach(append);
    else if (child !== null && child !== undefined && child !== false)
      el.append(
        child instanceof Node ? child : document.createTextNode(String(child)),
      );
  };
  children.forEach(append);
  return el;
}

export function replace(el, ...children) {
  el.replaceChildren(...children.flat().filter(Boolean));
  return el;
}
export function announce(text) {
  const el = document.getElementById("announcements");
  if (el) el.textContent = text;
}
