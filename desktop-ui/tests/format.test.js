import test from "node:test";
import assert from "node:assert/strict";
import {
  bytes,
  uptime,
  relativeTime,
  validateName,
  settingsEqual,
  platformName,
} from "../src/lib/format.js";

test("storage size formatting uses actual byte counts", () => {
  assert.equal(bytes(0), "0 B");
  assert.equal(bytes(2048), "2 KB");
  assert.equal(bytes(1024 ** 2), "1.0 MB");
  assert.equal(bytes(NaN), "—");
  assert.equal(bytes(-1), "—");
});
test("uptime has honest just-started state", () => {
  assert.equal(uptime(4), "Just started");
  assert.equal(uptime(120), "2m");
  assert.equal(uptime(3660), "1h 1m");
});
test("timestamps can be deterministically formatted", () => {
  const now = Date.parse("2026-10-30T10:00:00Z");
  assert.equal(relativeTime("2026-10-30T09:58:00Z", now), "2 min ago");
  assert.equal(relativeTime("invalid", now), "—");
});
test("name validation matches protocol boundaries", () => {
  for (const v of ["", " ", "a".repeat(49), "name\nnext"])
    assert.ok(validateName(v));
  assert.equal(validateName("  Studio  "), null);
  assert.equal(validateName("నారద"), null);
});
test("preferences compare semantically, not by key order", () => {
  const a = {
    device_name: "Work",
    close_to_tray: true,
    notifications_enabled: true,
    hide_notification_previews: true,
    reduce_motion: false,
    setup_completed: true,
  };
  assert.equal(settingsEqual(a, { ...a }), true);
  assert.equal(settingsEqual(a, { ...a, reduce_motion: true }), false);
});
test("development OS is not mislabeled as Windows", () => {
  assert.equal(platformName("linux"), "Linux · development");
  assert.equal(platformName("windows"), "Windows");
});
