# rtp-proxy-ADR-018: Cross-server teleport execution on the proxy-direct tier

- Status: Superseded by rtp-proxy-ADR-019 (teleport leg only; the permissions decision below still stands)
- Date: 2026-06-13
- Supersedes: none
- Related: rtp-proxy-ADR-017 (proxy-direct transport), rtp-proxy-ADR-014 (backend-owned `/rtp` with network queue), ADR-024 (rtp-lite assembly)

## Context

rtp-proxy-ADR-017 introduced the `proxy-direct` transport: each backend opens its OWN
outbound TCP socket to the Velocity companion to publish its real region list and read
the merged availability snapshot, with zero players and no operator config. That work
made cross-server `/rtp region=` tab-completion converge on a lobby. It deliberately
stopped at region NAME discovery: the tier carried no teleport request.

On the durable tiers (Redis/SQL), cross-server teleport works because both the backend
and the proxy connect to the same external store: the backend writes an enrolment, the
proxy's `TransportRequestTriggerSource` drains it and moves the player, and the
destination backend redeems a per-player reservation token (`findReservation` /
`redeem`) on join. The lite / DB-free tier has no such shared store, and an in-memory
queue cannot bridge two JVMs, so on `proxy-direct` the backend's enrolment was dropped
(`NetworkModeBootstrap.openRequestQueue` returns `null` for these tiers) and arrivals
were never redeemed.

The user constraint for lite is explicit: cross-server `/rtp` is **teleport EXECUTION
on arrival**, not load-balancing. There must be no per-player coordinate reservation and
no "confirm a specific coordinate" round-trip; the proxy simply relocates the player and
the destination backend runs `/rtp` locally.

## Decision

Carry the cross-server teleport over the EXISTING proxy-direct socket using two new
opcodes, reusing the `BackendHeartbeatCodec` HMAC framing (`ProxyDirectWire`). No shared
store, no reservation tokens, no load-balancing.

1. **Leg 1 - ENROL (backend -> proxy).** When the router decides CROSS_SERVER, the
   backend's enrolment flush sink calls `ProxyDirectNetworkBinding.enrol(playerId,
   server, region)`, which dials the proxy with an `OP_ENROL` frame carrying
   `playerId\u0001server\u0001region`. The proxy's `ProxyDirectListener` relocates the
   player with `player.createConnectionRequest(targetServer).fireAndForget()` and parks
   the requested region in a transient in-memory map keyed by player UUID (60s TTL).
   This is player-independent on the lobby side (the lobby dials the proxy on its own
   socket), so no carrier player is required to start the move.

2. **Leg 2 - REDEEM-QUERY (destination backend -> proxy).** On the destination backend,
   `ProxyDirectRedeemListener` (registered on the player-join lifecycle hook) calls
   `ProxyDirectNetworkBinding.pollRedeem(playerId)` over the backend's OWN outbound
   socket with an `OP_REDEEM_QUERY` frame. The proxy returns and clears the parked
   region. If present, the backend re-issues the player's intent locally:
   `rtp region=<region>` (or a bare `rtp` when no region was named), hopping to the
   player's owning thread first (Folia entity scheduler / Bukkit main thread).

The proxy's parked entry is NOT a coordinate reservation - it is a transient region
hand-off, has a TTL, and never blocks a backend coordinate.

## Permissions

A qualified `server:region` target is now gated by TWO independent permissions, mirroring
the local `rtp.regions.<region>` model: `rtp.servers.<server>` (destination backend) AND
`rtp.regions.<region>` (region). `rtp.servers.*` defaults to op in both `plugin.yml`
descriptors. The gate is enforced in the `RegionParameter` validator (which also filters
tab-completion), so a forged argument cannot bypass it.

## Consequences

- Cross-server `/rtp` executes on lite with zero players on any backend and no operator
  `servers:` config, using only the proxy-direct socket already used for discovery.
- No durable store, no reservation tokens, no load-balancing on this tier - by design.
  Operators who want coordinate-accurate reservation / load-balancing use Redis/SQL.
- The proxy-direct wire gained a one-byte opcode prefix (`OP_PUSH`/`OP_ENROL`/
  `OP_REDEEM_QUERY`); both ends ship together, so there is no mixed-version concern.
- The existing `JoinTriggerSource` still runs on `proxy-direct` but is a benign no-op
  there (`findReservation` defaults to empty); the new redeem path is independent.
