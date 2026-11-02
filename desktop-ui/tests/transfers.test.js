import test from "node:test";
import assert from "node:assert/strict";
import {
  progressOf,
  progressMotion,
  transferBytes,
  transferLabel,
  filterTransfers,
  validateDestination,
  eligibleRecipients,
  transferHint,
} from "../src/lib/transfers.js";
const root = {
  root_id: "4ed9d57f-46bf-4bdb-a481-abde3c0ad104",
  capabilities: ["browse", "create"],
};
function completedJob() {
  const transfer_id = "a0a0a0a0-1234-4234-8234-123456789abc";
  const sha256 = "a".repeat(64);
  return {
    transfer_id,
    sha256,
    state: "completed",
    size: "0",
    verified_bytes: "0",
    result: {
      transfer_id,
      state: "completed",
      size: "0",
      sha256,
      manifest_id: "b".repeat(64),
      root_id: root.root_id,
      name: "empty.txt",
      published_at: "2026-11-02T00:00:00Z",
    },
  };
}
test("transfer counts are bounded canonical decimals", () => {
  for (const n of [
    "01",
    "-1",
    "1e3",
    "1.2",
    "67108865",
    "99999999999999999999",
    12,
  ])
    assert.throws(() => transferBytes(n));
  assert.equal(transferBytes("67108864"), 67108864);
});
test("progress comes only from actual verified byte counts", () => {
  assert.deepEqual(
    progressOf({ state: "running", size: "100", verified_bytes: "24" }),
    { size: 100, verified: 24, percent: 24 },
  );
  assert.throws(() => progressOf({ size: "4", verified_bytes: "5" }));
});
test("all bytes staged is not a completed transfer", () => {
  const j = {
    state: "running",
    phase: "verifying",
    size: "3",
    verified_bytes: "3",
  };
  assert.equal(progressOf(j).percent, 100);
  assert.equal(transferLabel(j), "Verifying & publishing");
  assert.notEqual(transferLabel(j), "Completed");
});
test("empty files complete only after publication", () => {
  assert.equal(
    progressOf({ state: "queued", size: "0", verified_bytes: "0" }).percent,
    0,
  );
  assert.equal(progressOf(completedJob()).percent, 100);
  assert.throws(() =>
    progressOf({ state: "completed", size: "5", verified_bytes: "4" }),
  );
});
test("filters preserve paused and ambiguous states", () => {
  const jobs = [
    "paused",
    "running",
    "completed",
    "recovery_required",
    "cancelled",
  ].map((state) => ({ state }));
  assert.equal(filterTransfers(jobs, "active").length, 2);
  assert.equal(filterTransfers(jobs, "completed").length, 1);
  assert.equal(
    filterTransfers(jobs, "attention")[0].state,
    "recovery_required",
  );
});
test("destination picker never sends paths or grants", () => {
  assert.deepEqual(validateDestination(root, "notes.txt"), {
    root_id: root.root_id,
    directory_id: null,
    name: "notes.txt",
  });
  for (const n of ["../a", "CON.txt", "a/b", ".secret", "a:stream"])
    assert.throws(() => validateDestination(root, n));
  assert.throws(() =>
    validateDestination({ ...root, capabilities: ["browse"] }, "good.txt"),
  );
});
test("inbound recipients need pull offers and existing root grants", () => {
  const peers = [
    {
      state: "active",
      peer_id: "a",
      endpoint: null,
      permissions: {
        roots: [{ root_id: root.root_id, capabilities: ["browse", "read"] }],
      },
    },
    {
      state: "active",
      peer_id: "b",
      endpoint: "saved",
      permissions: { roots: [] },
    },
  ];
  assert.deepEqual(
    eligibleRecipients(peers, root.root_id).map((p) => p.peer_id),
    ["b"],
  );
  assert.deepEqual(
    eligibleRecipients(peers, root.root_id, true).map((p) => p.peer_id),
    ["a"],
  );
});
test("error guidance does not infer delivery or expose peer text", () => {
  assert.match(
    transferHint("COMMIT_OUTCOME_UNKNOWN"),
    /do not assume delivery/,
  );
  assert.match(transferHint("NAME_COLLISION"), /Nothing was overwritten/);
  assert.doesNotMatch(
    transferHint("<script>secret-path</script>"),
    /<|script|secret-path/,
  );
});

test("completed state without matching receipt is rejected", () => {
  const done = completedJob();
  assert.equal(progressOf(done).percent, 100);
  for (const result of [
    null,
    {},
    { ...done.result, transfer_id: root.root_id },
    { ...done.result, sha256: "b".repeat(64) },
    { ...done.result, size: "1" },
    { ...done.result, state: "committing" },
    { ...done.result, root_id: "../root" },
    { ...done.result, root_id: [root.root_id] },
    { ...done.result, manifest_id: ["b".repeat(64)] },
    { ...done.result, name: "../overwrite" },
    { ...done.result, manifest_id: "bad" },
    { ...done.result, published_at: "not a receipt timestamp" },
  ]) {
    assert.throws(() => progressOf({ ...done, result }), /receipt/);
  }
});
test("pending source acknowledgement does not erase a valid receiver receipt", () => {
  const done = completedJob();
  assert.equal(progressOf({ ...done, ack_pending: true }).percent, 100);
});
test("motion interpolates only finite increases between checked percentages", () => {
  assert.deepEqual(progressMotion(20, 40), {
    from: 0.2,
    to: 0.4,
    duration: 180,
  });
  assert.equal(progressMotion(20, 40, true), null);
  for (const [from, to] of [
    [undefined, 40],
    [40, 40],
    [60, 20],
    [-1, 10],
    [0, 101],
    [NaN, 20],
    [10, Infinity],
  ])
    assert.equal(progressMotion(from, to), null);
});
test("network guidance retains the original transfer without re-pairing", () => {
  assert.match(transferHint("PEER_UNREACHABLE"), /Re-pairing is not needed/);
  assert.match(
    transferHint("CONNECTION_INTERRUPTED"),
    /do not create another copy/,
  );
});
