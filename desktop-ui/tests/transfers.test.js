import test from "node:test";
import assert from "node:assert/strict";
import {
  progressOf,
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
  assert.equal(
    progressOf({ state: "completed", size: "0", verified_bytes: "0" }).percent,
    100,
  );
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
