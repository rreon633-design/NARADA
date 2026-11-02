// Pure setup policy. Recommendations never enable networking or grant trust.
export function isPrivateLan(ip) {
  if (typeof ip !== "string" || !/^\d{1,3}(\.\d{1,3}){3}$/.test(ip))
    return false;
  const n = ip.split(".").map(Number);
  if (n.some((v) => v > 255)) return false;
  return (
    n[0] === 10 ||
    (n[0] === 172 && n[1] >= 16 && n[1] <= 31) ||
    (n[0] === 192 && n[1] === 168)
  );
}
export function usableInterfaces(interfaces, development = false) {
  return (interfaces || []).filter(
    (i) =>
      i.eligible &&
      (isPrivateLan(i.ip) || (development && i.ip === "127.0.0.1")),
  );
}
export function suggestedInterface(interfaces, saved, development = false) {
  const usable = usableInterfaces(interfaces, development);
  if (usable.some((i) => i.ip === saved)) return saved;
  return usable.length === 1 ? usable[0].ip : "";
}
export function connectionSetupHint(interfaces, development = false) {
  const usable = usableInterfaces(interfaces, development);
  if (!usable.length)
    return "No eligible private network was found. Connect this computer to your trusted Wi-Fi/Ethernet and check its Windows network profile. Do not disable the firewall or use a public network.";
  if (usable.length > 1)
    return "More than one eligible network is available. Choose the adapter on the same trusted network as your phone. No adapter will be enabled until you confirm.";
  if (usable[0].ip === "127.0.0.1")
    return "Development loopback: this can connect test processes on this host, not a real phone.";
  return "Confirm the private network shared with your phone. A QR code is not a connection until you verify and approve the device.";
}

// This is only a plan for a labelled user action, not permission to enable a socket.
// Development loopback is deliberately excluded from the phone fast path.
export function phonePairingPlan(connection) {
  if (connection?.identity?.state !== "ready") return { kind: "blocked" };
  const config = connection.config;
  const ip = suggestedInterface(connection.interfaces, config.bind_ip);
  if (!ip) return { kind: "choose" };
  if (connection.listening && config.enabled && ip === config.bind_ip)
    return { kind: "ready", ip };
  if (
    !Number.isInteger(config.api_port) ||
    config.api_port < 0 ||
    config.api_port > 65535
  )
    return { kind: "choose" };
  return { kind: "enable", ip };
}
