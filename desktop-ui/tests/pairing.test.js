import test from "node:test";
import assert from "node:assert/strict";
import QRCode from "qrcode";
import {
  parsePairingCode,
  decodeQrPixels,
  imageDimensions,
  fingerprintText,
} from "../src/lib/pairing.js";
const code = () =>
  JSON.stringify({
    format: "narada-pairing",
    version: 1,
    invitation_id: "9d60f47b-7760-4dbc-81f6-00c90d94b054",
    token: "a".repeat(64),
    endpoint: "127.0.0.1:45821",
    fingerprint: "b".repeat(64),
    device_id: "c".repeat(64),
    name: "Disposable fixture",
    expires_at: new Date(Date.now() + 120000).toISOString(),
  });
test("real generated QR round-trips through the offline decoder", () => {
  const text = code();
  const qr = QRCode.create(text, { errorCorrectionLevel: "M" });
  const scale = 5,
    margin = 4,
    size = (qr.modules.size + margin * 2) * scale;
  const pixels = new Uint8ClampedArray(size * size * 4).fill(255);
  for (let y = 0; y < qr.modules.size; y++)
    for (let x = 0; x < qr.modules.size; x++)
      if (qr.modules.get(y, x))
        for (let yy = 0; yy < scale; yy++)
          for (let xx = 0; xx < scale; xx++) {
            const p =
              (((y + margin) * scale + yy) * size + (x + margin) * scale + xx) *
              4;
            pixels[p] = pixels[p + 1] = pixels[p + 2] = 0;
          }
  assert.equal(decodeQrPixels(pixels, size, size), text);
});
test("expired, future, unsupported and malformed invitation codes fail locally", () => {
  const valid = JSON.parse(code());
  for (const bad of [
    { ...valid, version: 0 },
    { ...valid, version: 2 },
    { ...valid, format: "remote-login" },
    { ...valid, token: "short" },
    { ...valid, owner_admin: true },
    { ...valid, expires_at: new Date(Date.now() - 1).toISOString() },
    { ...valid, expires_at: new Date(Date.now() + 3600000).toISOString() },
    { ...valid, endpoint: "https://example.com/" },
  ])
    assert.throws(() => parsePairingCode(JSON.stringify(bad)));
  assert.throws(() => parsePairingCode("X".repeat(5000)));
  assert.throws(() => parsePairingCode("not-json"));
});
test("PNG dimensions are checked before allocating a decoded bitmap", () => {
  const data = new Uint8Array(24);
  data.set([137, 80, 78, 71, 13, 10, 26, 10]);
  const v = new DataView(data.buffer);
  v.setUint32(8, 13);
  data.set([73, 72, 68, 82], 12);
  v.setUint32(16, 304);
  v.setUint32(20, 304);
  assert.deepEqual(imageDimensions(data.buffer), {
    width: 304,
    height: 304,
    type: "image/png",
  });
  v.setUint32(16, 100000);
  assert.throws(() => imageDimensions(data.buffer));
});
test("SVG, truncated JPEG and unreasonable pixel buffers are rejected", () => {
  assert.throws(() =>
    imageDimensions(new TextEncoder().encode('<svg onload="evil()"/>').buffer),
  );
  assert.throws(() =>
    imageDimensions(new Uint8Array([255, 216, 255, 192, 0, 0]).buffer),
  );
  assert.throws(() => decodeQrPixels(new Uint8ClampedArray(16), 99999, 99999));
  assert.throws(() =>
    decodeQrPixels(new Uint8ClampedArray(16).fill(255), 2, 2),
  );
});
test("full fingerprints remain readable without inventing a short security code", () => {
  const f = "0123456789abcdef".repeat(4);
  assert.equal(fingerprintText(f).replaceAll(" ", ""), f);
  assert.equal(fingerprintText(null), "Not available");
});
