import jsQR from "jsqr";
export const MAX_CODE_BYTES = 4096;
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
export function parsePairingCode(text, now = Date.now()) {
  if (new TextEncoder().encode(text).length > MAX_CODE_BYTES)
    throw Error("This pairing code is too large.");
  let code;
  try {
    code = JSON.parse(text);
  } catch {
    throw Error(
      "Paste a complete NARADA pairing code, or choose its QR image.",
    );
  }
  const fields = [
    "format",
    "version",
    "invitation_id",
    "token",
    "endpoint",
    "fingerprint",
    "device_id",
    "name",
    "expires_at",
  ];
  if (
    !code ||
    Object.keys(code).some((k) => !fields.includes(k)) ||
    code.format !== "narada-pairing" ||
    code.version !== 1
  )
    throw Error("This pairing format or version is not supported.");
  if (
    ![code.fingerprint, code.device_id, code.token].every(
      (v) => typeof v === "string" && /^[a-f0-9]{64}$/i.test(v),
    ) ||
    typeof code.name !== "string" ||
    !code.name.trim() ||
    [...code.name].length > 48 ||
    typeof code.invitation_id !== "string" ||
    !/^[a-f0-9-]{36}$/i.test(code.invitation_id) ||
    typeof code.endpoint !== "string" ||
    !/^(?:https:\/\/)?\d{1,3}(?:\.\d{1,3}){3}:\d{1,5}$/.test(code.endpoint)
  )
    throw Error("The pairing code is incomplete or malformed.");
  const remaining = Date.parse(code.expires_at) - now;
  if (!Number.isFinite(remaining))
    throw Error("The invitation expiry is invalid.");
  if (remaining <= 0)
    throw Error("This invitation has expired. Ask the owner for a new one.");
  if (remaining > 150000)
    throw Error(
      "Check both device clocks: the invitation lifetime is invalid.",
    );
  return code;
}
export function fingerprintText(value) {
  return value?.match(/.{1,8}/g)?.join(" ") || "Not available";
}
export function imageDimensions(buffer) {
  const bytes = new Uint8Array(buffer);
  const view = new DataView(buffer);
  let width, height, type;
  if (
    bytes.length >= 24 &&
    [137, 80, 78, 71, 13, 10, 26, 10].every((v, i) => bytes[i] === v)
  ) {
    if (
      view.getUint32(8) !== 13 ||
      String.fromCharCode(...bytes.slice(12, 16)) !== "IHDR"
    )
      throw Error("The PNG header is invalid.");
    width = view.getUint32(16);
    height = view.getUint32(20);
    type = "image/png";
  } else if (bytes[0] === 255 && bytes[1] === 216) {
    type = "image/jpeg";
    let offset = 2;
    while (offset + 4 < bytes.length) {
      if (bytes[offset++] !== 255) break;
      while (bytes[offset] === 255) offset++;
      const marker = bytes[offset++];
      if (marker === 217 || marker === 218 || offset + 2 > bytes.length) break;
      const length = view.getUint16(offset);
      if (length < 2 || offset + length > bytes.length) break;
      if (
        marker >= 192 &&
        marker <= 207 &&
        ![196, 200, 204].includes(marker) &&
        length >= 7
      ) {
        height = view.getUint16(offset + 3);
        width = view.getUint16(offset + 5);
        break;
      }
      offset += length;
    }
  }
  if (
    !width ||
    !height ||
    width > 4096 ||
    height > 4096 ||
    width * height > 16777216
  )
    throw Error(
      "Choose a valid PNG or JPEG no larger than 4096 × 4096 pixels.",
    );
  return { width, height, type };
}
export function decodeQrPixels(data, width, height) {
  if (
    width < 1 ||
    height < 1 ||
    width * height > 4194304 ||
    data.length !== width * height * 4
  )
    throw Error("The QR image dimensions are unsupported.");
  const result = jsQR(data, width, height, {
    inversionAttempts: "attemptBoth",
  });
  if (!result?.data)
    throw Error(
      "No readable QR code found. Use a clear image with all four edges visible, or paste the text code.",
    );
  parsePairingCode(result.data);
  return result.data;
}
export async function decodePairingImage(file) {
  if (!file || file.size > MAX_IMAGE_BYTES)
    throw Error("Choose a QR image smaller than 5 MB.");
  if (file.type && !["image/png", "image/jpeg"].includes(file.type))
    throw Error(
      "Only PNG and JPEG images are accepted. SVGs and remote URLs are not loaded.",
    );
  const data = await file.arrayBuffer();
  const dims = imageDimensions(data);
  const bitmap = await createImageBitmap(new Blob([data], { type: dims.type }));
  try {
    const scale = Math.min(1, 2048 / Math.max(bitmap.width, bitmap.height));
    const canvas = document.createElement("canvas");
    canvas.width = Math.round(bitmap.width * scale);
    canvas.height = Math.round(bitmap.height * scale);
    const context = canvas.getContext("2d", { willReadFrequently: true });
    if (!context)
      throw Error(
        "This desktop runtime could not decode the image. Paste the text code instead.",
      );
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    return decodeQrPixels(
      context.getImageData(0, 0, canvas.width, canvas.height).data,
      canvas.width,
      canvas.height,
    );
  } finally {
    bitmap.close();
  }
}
