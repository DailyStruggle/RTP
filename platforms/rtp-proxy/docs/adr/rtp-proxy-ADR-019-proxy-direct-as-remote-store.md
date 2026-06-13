# rtp-proxy-ADR-019: proxy-direct is a remote view of the proxy's in-memory store

- Status: Accepted
- Date: 2026-06-13
- Supersedes: the teleport leg of rtp-proxy-ADR-018 (the bespoke `OP_ENROL` / `OP_REDEEM_QUERY` opcodes and the backend `ProxyDirectRedeemListener`)
- Keeps: rtp-proxy-ADR-017 (the player-independent backend->proxy TCP socket)
- Related: rtp-proxy-ADR-014 (backend-owned `/rtp` with network queue), rtp-proxy-ADR-011 (transport SPI), ADR-024 (rtp-lite assembly)

## Context

rtp-proxy-ADR-018 carried cross-server teleport over the proxy-direct socket with two
bespoke verbs (`OP_ENROL`, `OP_REDEEM_QUERY`) plus a dedicated backend
`ProxyDirectRedeemListener` that re-issued `/rtp` on arrival. That was a second, parallel
teleport protocol layered beside the existing one: it bypassed the `NetworkRequestQueue`
(which `openRequestQueue` returned `null` for on this tier) and re-implemented the
arrival path that `JoinTriggerSource` already performs on the Redis/SQL tiers.

The intended model (user, 2026-06-13) is simpler: the proxy plugin should play the role
Redis plays. `proxy-direct` should be "just another data-management transport like
`redis`/`sql`" - a `NetworkTransport` + `NetworkRequestQueue` binding pair whose backing
store is the proxy's own in-memory maps, reached over the socket. Everything else
(discovery, enrolment, dispatch, arrival redeem) should run through the same SPI calls
and the same proxy-side code the durable tiers use.

## Decision

Make `proxy-direct` a thin RPC client/server over the existing socket:

1. **Wire (`ProxyDirectWire`).** One opcode per SPI method invoked *from a backend* (the
   only RPC direction): `OP_HEARTBEAT` (publishBackendHeartbeat + readSnapshot),
   `OP_FLUSH_PENDING`, `OP_POLL_STATUS`, `OP_CANCEL`, `OP_FIND_RESERVATION`, `OP_REDEEM`,
   `OP_LIST_ACTIVE`. Each is `byte opcode` + signed request + signed response (single or
   count-framed list), reusing the HMAC framing. Proxy-local methods (`claim`,
   `dequeueReady`, `transition`, `release`, `reapExpired`) are never sent.

2. **Backend client (`rtp-core`).** `ProxyDirectNetworkBinding` is a full RPC
   `NetworkTransport` (heartbeat/snapshot + `findReservation`/`redeem`/`listActiveForServer`;
   `claim` throws - a backend never claims). A new `ProxyDirectNetworkRequestQueue`
   implements `NetworkRequestQueue` (`enrol`/`flushPending`/`pollStatus`/`cancel` via RPC;
   `dequeueReady`/`transition` are proxy-local no-ops).

3. **`NetworkModeBootstrap`.** When the transport is a `ProxyDirectNetworkBinding`, the
   request queue is the remote client above, so the standard flush-sink / status-poll
   wiring lights up unchanged. The bespoke `directBinding.enrol` flush branch and the
   `ProxyDirectRedeemListener` are removed; arrival redeem returns to the standard
   `JoinTriggerSource` (which now RPCs `findReservation`/`redeem` to the proxy).

4. **Proxy server (`ProxyDirectListener`).** Dispatches each RPC against the proxy's
   already-constructed in-memory `NetworkTransport` + `NetworkRequestQueue`. The proxy's
   existing `TransportRequestTriggerSource` drains the queue and the dispatcher relocates
   the player via `createConnectionRequest`; the pre-connect/arrival reservation path is
   unchanged.

## Consequences

- `transport.type` switches a backend between `redis`, `sql`, and `proxy-direct` with no
  behavioural branch anywhere except the binding factory - the definition of a drop-in
  data-management option.
- Net new type: one (`ProxyDirectNetworkRequestQueue`). Net removed: the two teleport
  opcodes, `ProxyDirectRedeemListener`, and the proxy-side pending-region map.
- Reservation tokens are retained on this tier (so `JoinTriggerSource` is reused
  verbatim). Slimming proxy-direct to a reservation-free pending map is a possible future
  cleanup, intentionally out of scope here.
- RPC uses one short-lived socket per call (matching the existing heartbeat exchange); a
  keep-alive pool is a later optimisation, not needed for correctness. Backend reservation
  RPCs are dispatched on `RTP.scheduler`'s async tier / the existing buffer + status-cache
  timers, so no join/tick thread blocks.
- Single-proxy-simple: a backend dials every configured proxy and the first definite
  answer wins. Cross-proxy ownership remains a durable-tier concern.
