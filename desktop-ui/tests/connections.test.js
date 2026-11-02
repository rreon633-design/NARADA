import test from "node:test";
import assert from "node:assert/strict";
import {
  isPrivateLan,
  suggestedInterface,
  usableInterfaces,
  connectionSetupHint,
  phonePairingPlan,
} from "../src/lib/connections.js";
const loop = { ip: "127.0.0.1", eligible: true };
const lan = { ip: "192.168.1.4", eligible: true };
test("phone setup never recommends the first loopback adapter or a saved loopback address", () => {
  assert.equal(suggestedInterface([loop, lan], loop.ip), lan.ip);
});
test("multiple LAN adapters require a choice unless the saved one is still eligible", () => {
  const other = { ip: "10.0.0.2", eligible: true };
  assert.equal(suggestedInterface([loop, lan, other], loop.ip), "");
  assert.equal(suggestedInterface([loop, lan, other], lan.ip), lan.ip);
});
test("public, unknown and revoked network eligibility are not relaxed", () => {
  assert.deepEqual(
    usableInterfaces([
      loop,
      { ...lan, eligible: false },
      { ip: "8.8.8.8", eligible: true },
    ]),
    [],
  );
  assert.match(connectionSetupHint([loop]), /No eligible private network/);
});
test("explicit development mode retains loopback fixtures without claiming phone support", () => {
  assert.equal(suggestedInterface([loop], loop.ip, true), loop.ip);
  assert.match(connectionSetupHint([loop], true), /not a real phone/);
});
test("private IPv4 validation does not accept URLs, DNS, invalid octets or link-local", () => {
  for (const value of [
    "https://192.168.1.2",
    "localhost",
    "169.254.1.2",
    "172.32.0.1",
    "192.168.999.2",
    "0.0.0.0",
    "127.0.0.1",
  ])
    assert.equal(isPrivateLan(value), false);
  for (const value of [
    "10.0.0.1",
    "172.16.0.1",
    "172.31.255.254",
    "192.168.1.2",
  ])
    assert.equal(isPrivateLan(value), true);
});

const connection = (interfaces, config = {}, listening = false) => ({
  identity: { state: "ready" },
  interfaces,
  listening,
  config: {
    enabled: false,
    bind_ip: "127.0.0.1",
    api_port: 35680,
    discovery: false,
    ...config,
  },
});
test("phone fast path enables only a sole eligible private adapter", () => {
  assert.deepEqual(phonePairingPlan(connection([loop, lan])), {
    kind: "enable",
    ip: lan.ip,
  });
  assert.equal(phonePairingPlan(connection([loop])).kind, "choose");
  assert.equal(
    phonePairingPlan(connection([{ ...lan, eligible: false }])).kind,
    "choose",
  );
});
test("saved eligible interface is reused; ambiguous and invalid configurations stay manual", () => {
  const other = { ip: "10.0.0.2", eligible: true };
  assert.equal(phonePairingPlan(connection([lan, other])).kind, "choose");
  assert.deepEqual(
    phonePairingPlan(connection([lan, other], { bind_ip: other.ip })),
    { kind: "enable", ip: other.ip },
  );
  assert.equal(
    phonePairingPlan(connection([lan], { api_port: 65536 })).kind,
    "choose",
  );
  assert.equal(
    phonePairingPlan(connection([lan], { api_port: "35680" })).kind,
    "choose",
  );
});
test("an eligible existing listener needs no reconfiguration or preference changes", () => {
  const original = connection(
    [lan],
    { enabled: true, bind_ip: lan.ip, api_port: 45678, discovery: true },
    true,
  );
  const before = structuredClone(original);
  assert.deepEqual(phonePairingPlan(original), { kind: "ready", ip: lan.ip });
  assert.deepEqual(original, before);
});
test("an unready identity cannot enter the fast path", () => {
  assert.equal(
    phonePairingPlan({ ...connection([lan]), identity: { state: "blocked" } })
      .kind,
    "blocked",
  );
  assert.equal(phonePairingPlan(null).kind, "blocked");
});
