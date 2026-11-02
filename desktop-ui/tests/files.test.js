import { readFileSync } from "node:fs";
import test from "node:test";
import assert from "node:assert/strict";
import {
  parseExclusions,
  validMutationName,
  grantForRole,
  roleForGrant,
  boundedLayout,
  validatePreview,
  resolveRootSelection,
} from "../src/lib/files.js";
test("file names reject traversal, streams, devices and bidi overrides", () => {
  for (const n of [
    "../a",
    "a/b",
    "a\\b",
    "CON.txt",
    "file:stream",
    "a.",
    "a ",
    ".private",
    "a\u202eb",
    "e\u0301",
  ]) {
    assert.equal(validMutationName(n), false, n);
  }
  assert.equal(validMutationName("నారద.png"), true);
});
test("exclusions are relative bounded prefixes", () => {
  assert.deepEqual(parseExclusions("Private/\n Drafts/Ideas \n"), [
    "Private",
    "Drafts/Ideas",
  ]);
  for (const s of [
    "../outside",
    "/etc",
    "C:\\private",
    "*.pem",
    "A\n".repeat(17),
  ])
    assert.throws(() => parseExclusions(s));
});
test("folder roles grant only the chosen root", () => {
  assert.deepEqual(grantForRole("a", "preview"), {
    root_id: "a",
    capabilities: ["browse", "read"],
  });
  assert.deepEqual(grantForRole("b", "none").capabilities, []);
  assert.throws(() => grantForRole("a", "admin"));
});
test("layout preferences are bounded and do not contain file state", () => {
  assert.deepEqual(boundedLayout('{"tree":5000,"inspector":1}'), {
    tree: 310,
    inspector: 240,
  });
  assert.deepEqual(boundedLayout("invalid"), { tree: 220, inspector: 278 });
});
test("peer preview envelopes cannot request huge decode dimensions or active formats", () => {
  for (const p of [
    { mime: "image/svg+xml", data: "PHN2Zz4=" },
    { mime: "image/jpeg", data: "", width: 100000, height: 100000 },
    { mime: "image/jpeg", data: "AAAA", width: 256, height: 256 },
  ])
    assert.throws(() =>
      validatePreview({ ...p, object_id: "x", version: "v" }, "x", "v"),
    );
});

const jpg = readFileSync(
  new URL("../../tests/fixtures/file-preview.jpg", import.meta.url),
);
const envelope = (data) => ({
  object_id: "fixture",
  version: "version",
  mime: "image/jpeg",
  width: 32,
  height: 20,
  data: data.toString("base64"),
});
test("actual reencoded JPEG is accepted only for the selected object and version", () => {
  assert.equal(validatePreview(envelope(jpg), "fixture", "version").width, 32);
  assert.throws(() => validatePreview(envelope(jpg), "other", "version"));
  assert.throws(() => validatePreview(envelope(jpg), "fixture", "stale"));
});
test("encoded dimensions cannot exceed the claimed thumbnail envelope", () => {
  const copy = Buffer.from(jpg);
  const at = copy.indexOf(Buffer.from([255, 192]));
  assert.ok(at > 0);
  copy.writeUInt16BE(4000, at + 7);
  assert.throws(() => validatePreview(envelope(copy), "fixture", "version"));
});
test("additional JPEG frames and trailing embedded images are refused", () => {
  assert.throws(() =>
    validatePreview(envelope(Buffer.concat([jpg, jpg])), "fixture", "version"),
  );
  const at = jpg.indexOf(Buffer.from([255, 192]));
  const frame = jpg.subarray(at, at + 19);
  const duplicate = Buffer.concat([
    jpg.subarray(0, at),
    frame,
    jpg.subarray(at),
  ]);
  assert.throws(() =>
    validatePreview(envelope(duplicate), "fixture", "version"),
  );
});

test("remembered folder is stable across reordered catalogs", () => {
  const a = { root_id: "a" },
    b = { root_id: "b" };
  assert.equal(resolveRootSelection([b, a], "a").root, a);
  assert.equal(resolveRootSelection([b, a], null).root, b);
});
test("missing folder never silently becomes another folder", () => {
  assert.deepEqual(resolveRootSelection([{ root_id: "other" }], "missing"), {
    root: null,
    missing: true,
  });
  assert.deepEqual(resolveRootSelection([], null), {
    root: null,
    missing: false,
  });
});

test("custom folder permissions are never rounded up to a broader role", () => {
  assert.equal(roleForGrant(["read", "browse"]), "preview");
  assert.equal(roleForGrant(["browse", "modify"]), "custom");
  assert.equal(
    roleForGrant(["browse", "read", "sync_read", "modify"]),
    "custom",
  );
  assert.throws(() => grantForRole("root", "custom"));
});
