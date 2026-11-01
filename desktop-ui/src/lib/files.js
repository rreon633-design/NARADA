export function parseExclusions(text) {
  const values = text
    .split(/\r?\n/)
    .map((s) => s.trim().replace(/\/+$/, ""))
    .filter(Boolean);
  if (
    values.length > 16 ||
    new TextEncoder().encode(values.join("")).length > 1024
  )
    throw new Error(
      "Use at most 16 exclusion prefixes totaling 1,024 UTF-8 bytes.",
    );
  if (
    values.some(
      (s) =>
        s.startsWith("/") ||
        s.includes("\\") ||
        s.split("/").some((p) => p === "." || p === ".." || !p) ||
        /[\x00-\x1f:*?]/.test(s),
    )
  )
    throw new Error(
      "Use relative prefixes such as Private or Documents/Drafts, not paths or wildcards.",
    );
  return values;
}
export function validMutationName(name) {
  if (
    !name ||
    new TextEncoder().encode(name).length > 180 ||
    name.normalize("NFC") !== name ||
    /[\\/:<>"|?*\x00-\x1f\x7f-\x9f\u202a-\u202e\u2066-\u2069]/.test(name) ||
    /[. ]$/.test(name) ||
    name.startsWith(".")
  )
    return false;
  return !/^(con|prn|aux|nul|conin\$|conout\$|com[1-9¹²³]|lpt[1-9¹²³])(?:\.|$)/i.test(
    name,
  );
}
export function grantForRole(rootId, role) {
  const levels = {
    none: [],
    browse: ["browse"],
    preview: ["browse", "read"],
    offline: ["browse", "read", "sync_read"],
    manage: ["browse", "read", "create", "modify", "trash"],
  };
  if (!Object.hasOwn(levels, role))
    throw new Error("Choose an explicit permission level.");
  return { root_id: rootId, capabilities: [...levels[role]] };
}
export function boundedLayout(raw) {
  try {
    const v = JSON.parse(raw || "{}");
    return {
      tree: Math.max(185, Math.min(310, Number(v.tree) || 220)),
      inspector: Math.max(240, Math.min(400, Number(v.inspector) || 278)),
    };
  } catch {
    return { tree: 220, inspector: 278 };
  }
}

export function validatePreview(p, objectId, version) {
  if (
    p.object_id !== objectId ||
    p.version !== version ||
    p.mime !== "image/jpeg" ||
    !Number.isInteger(p.width) ||
    !Number.isInteger(p.height) ||
    p.width < 1 ||
    p.height < 1 ||
    p.width > 256 ||
    p.height > 256 ||
    typeof p.data !== "string" ||
    p.data.length > 44000 ||
    !/^[A-Za-z0-9+/]*={0,2}$/.test(p.data)
  )
    throw new Error("The peer returned an invalid bounded preview.");
  const data = Uint8Array.from(atob(p.data), (c) => c.charCodeAt(0));
  if (data.length > 32768)
    throw new Error("The preview exceeds its byte limit.");
  const actual = boundedJpegDimensions(data);
  if (
    actual.type !== "image/jpeg" ||
    actual.width !== p.width ||
    actual.height !== p.height ||
    actual.width > 256 ||
    actual.height > 256
  )
    throw new Error(
      "The preview pixel dimensions do not match its bounded envelope.",
    );
  return p;
}

// Only the single-frame baseline JPEG emitted by the agent is accepted. Reject
// extra frames, DNL/alternative dimension markers and trailing embedded images.
function boundedJpegDimensions(b) {
  const fail = () => {
    throw new Error("The preview is not a bounded single-frame JPEG.");
  };
  if (b[0] !== 255 || b[1] !== 216) fail();
  let i = 2,
    frame = null;
  while (i + 4 <= b.length) {
    if (b[i++] !== 255) fail();
    const marker = b[i++];
    const len = (b[i] << 8) | b[i + 1];
    if (len < 2 || i + len > b.length) fail();
    if (marker === 192) {
      if (frame || len !== 17 || b[i + 2] !== 8 || b[i + 7] !== 3) fail();
      const height = (b[i + 3] << 8) | b[i + 4],
        width = (b[i + 5] << 8) | b[i + 6];
      if (!width || !height || width > 256 || height > 256) fail();
      frame = { width, height, type: "image/jpeg" };
    } else if (marker === 218) {
      if (!frame) fail();
      i += len;
      while (i < b.length) {
        if (b[i++] !== 255) continue;
        const m = b[i++];
        if (m === 0 || (m >= 208 && m <= 215)) continue;
        if (m === 217 && i === b.length) return frame;
        fail();
      }
      fail();
    } else if (![224, 219, 196, 221].includes(marker)) fail();
    i += len;
  }
  fail();
}
