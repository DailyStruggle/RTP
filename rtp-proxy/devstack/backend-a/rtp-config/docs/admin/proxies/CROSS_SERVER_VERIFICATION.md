# Cross-Server `/rtp` Verification

Operator-facing manual verification of the cross-server `/rtp` round-trip on
a multi-proxy, multi-backend network. The dev-facing automated fixture for
the same scenarios lives at [`rtp-proxy/devstack/`](../../../rtp-proxy/devstack/README.md).

Single-backend / in-memory transport precursor:
[`SINGLE_BACKEND_VERIFICATION.md`](SINGLE_BACKEND_VERIFICATION.md).

Reference: [`MULTI_SERVER_PLAN.md`](../../dev/MULTI_SERVER_PLAN.md),
[ADR-036](../../adr/ADR-036-network-mode-multi-server-multi-proxy.md).

## Prerequisites

- Two Velocity proxies sharing the same `forwarding.secret`.
- Two Paper backends, each with `RTP` installed and registered against
  both proxies' `[servers]` block.
- One Redis instance reachable from every proxy and backend on a single
  resolvable host:port.
- A shared 32-byte HMAC secret in `RTP_NET_SECRET` on every JVM.
- `rtp-proxy-velocity` on both proxies, `rtp-plugin` on both backends, all
  built from the same release tag.

## Configuration outline

On each proxy (`plugins/rtp-proxy-velocity/network.yml`):

```yaml
network:
  enabled: true
  proxyId: "proxy-a"          # unique per proxy
  role: proxy
  secretEnv: RTP_NET_SECRET
transport:
  type: redis
  redis: { host: redis, port: 6379 }
```

On each backend (`plugins/RTP/network.yml`):

```yaml
network:
  enabled: true
  serverId: "backend-a"       # unique per backend
  role: backend
  secretEnv: RTP_NET_SECRET
transport:
  type: redis
  redis: { host: redis, port: 6379 }
```

`proxyId` and `serverId` are network-wide identifiers; collisions cause one
participant to overwrite another's heartbeat row and silently degrade. Pick
short, stable strings (hostnames work).

## Scenario 1: stack boot

Bring everything up and confirm all five processes report healthy startup.

Pass criteria:

- Both proxies log `RTP network mode enabled (proxy-X)`.
- Both backends log `RTP network mode enabled (backend-X)`.
- No `WARNING` lines mention `RTP_NET_SECRET`, `transport`, or `Redis`.

## Scenario 2: heartbeat convergence

Within ~5 seconds of full boot, all four participants must publish a
heartbeat row to Redis.

```powershell
redis-cli KEYS 'rtp:net:backend:*'
redis-cli KEYS 'rtp:net:proxy:*'
```

Pass criteria: 4 keys total - 2 under `:backend:`, 2 under `:proxy:`.

Common failure mode: 0 keys. Cause is almost always `RTP_NET_SECRET`
unset on at least one JVM, or `transport.type: redis` missing on one
participant (it silently runs in-memory).

## Scenario 3: cross-server `/rtp` round-trip

The end-to-end happy path. Required: a live Minecraft 1.21.x client.

1. Connect the client to proxy-a (default backend: backend-a).
2. Run `/server backend-b` once to ensure the client's session is known
   to backend-b's permission map. Then `/server backend-a` again.
3. From backend-a, run `/rtp`. The backend's `NetworkRouter` enrols the
   request in the shared wait queue (`rtp:net:wq:ready` LIST + per-player
   `rtp:net:wq:status:<pid>` HASH). Some proxy worker dequeues the
   envelope, runs the `BackendSelector` (kept-count + killSwitch +
   region availability), claims a reservation token against the chosen
   backend's `networkKeptLocations` pool, and issues the transfer.
4. On arrival, the destination backend's `JoinTriggerSource` calls
   `findReservation -> redeem`, and on REDEEMED pins the pre-warmed
   coordinate via `RegionQueueManager.acceptRedeemedReservation(...)`
   so the immediately-following `/rtp` dispatch consumes that exact
   coord (no second pipeline run).

L6 queue + reservation state transitions an operator can observe:

- `rtp:net:wq:status:<pid>` HASH cycles through `PENDING -> ROUTING ->
  RESERVED -> COMPLETED` (or `FAILED/CANCELLED` on the failure paths);
  the HASH is DEL'd by the terminal `transition.lua` script along with
  the envelope HASH and the `rtp:net:wq:seen` SET entry.
- `rtp:net:tok:<tokenId>` HASH transitions `PENDING -> CLAIMED ->
  CONSUMED` (or `RELEASED` on `PLAYER_DISCONNECTED` / `TTL_EXPIRED` /
  `BACKEND_REJECTED`); CONSUMED/RELEASED rows are removed by the
  release Lua. The reaper's `ReleaseSink` fires `releaseToNetworkKept`
  on the owning backend so the coord returns to the pool.
- Heartbeat HASH `regionKeptCounts` field on the chosen backend drops
  by 1 at claim time and is replenished on the next pulse.

Pass criteria:

- Client lands on the destination backend at a valid coordinate.
- Destination backend log contains a `JoinTriggerSource ... redeemed` line.
- `redis-cli KEYS 'rtp:net:tok:*'` shows zero entries after the redeem
  completes (terminal `transition.lua` deleted the row).
- `redis-cli KEYS 'rtp:net:wq:status:*'` shows zero entries for the
  player UUID (terminal envelope cleanup).

Common failure modes:

- Client lands but no `/rtp` runs: HMAC verify failed (secret mismatch),
  or `JoinTriggerSource` not registered (network bootstrap aborted).
- `/rtp` runs locally on the source backend: `NetworkRouter` gated to
  `local` (check `routing.mode` in `network.yml`), token-bucket rate
  limit exhausted, no backend qualified (all killSwitch / zero
  `keptCount` / region unavailable), or the dispatcher fell through to
  the local pipeline. Check the backend log for `RoutingDecision`
  `FallbackReason` entries and the proxy log for `DispatchOutcome`
  warnings.
- Wait-queue envelope stuck in `PENDING`: no proxy worker is draining
  `rtp:net:wq:ready` (proxy `TransportRequestTriggerSource` not started,
  or all proxies are down). The reservation TTL reaper will clear stale
  entries, but the player observes a fallback message.

## Scenario 4: kill mid-flight

Verify that an orphan reservation is reaped without manual cleanup.

1. Seed a reservation (operator: log into a client and run `/rtp`, then
   immediately `Ctrl+C` the destination backend before the client's
   `PlayerJoinEvent` fires; dev: use `run-acceptance.ps1 -Scenario killmidflight`,
   which seeds via the claim Lua and kills the backend container).
2. Wait `reservation.ttlMs + reservation.reapIntervalMs` (default 5 min
   total in production; 35s in the devstack).
3. `redis-cli KEYS 'rtp:net:reservation:*'` must report zero matching keys.

Pass criteria: the row clears within the budget without operator intervention.

## Scenario 5: kill switch

Verify that flipping `network.killSwitch: true` on a proxy halts new claims
without affecting in-flight redeems.

1. Edit `proxy-a/network.yml`, set `network.killSwitch: true`, restart proxy-a.
2. From a client on proxy-a, run `/rtp`. The proxy must reject the request
   with `MSG_NETWORK_KILL_SWITCH` (configurable via `messages.yml`).
3. From a client on proxy-b (unaffected), `/rtp` continues to work.

Pass criteria: proxy-a rejects with the configured message; no claim is
written to Redis; proxy-b is unaffected.

Note: the devstack's `run-acceptance.ps1 -Scenario killswitch` performs a
wiring smoke test by issuing a synthetic claim with a sentinel value. It
does NOT exercise the real proxy gate - that requires a live client and
the manual procedure above. Treat the harness result as a regression
canary for the Lua contract, not as authoritative acceptance evidence.

## Teardown

- `network.enabled: false` on every participant restores byte-identical
  pre-Phase-2 behavior (REQ-RTP-NET-002).
- Reservation rows older than their TTL are reaped automatically. To
  flush manually: `redis-cli --scan --pattern 'rtp:net:*' | xargs redis-cli DEL`.

## Troubleshooting

| Symptom                                                  | Likely cause                                   |
|----------------------------------------------------------|------------------------------------------------|
| Backend logs `RTP_NET_SECRET is required` at startup     | Env var unset on that JVM                      |
| Heartbeat keys present but `/rtp` runs locally           | Proxy `serverId` mismatch (selector saw none)  |
| `redeem` returns `WRONG_SERVER`                          | Player landed on a backend other than the one claimed; usually a Velocity `try` fallback |
| `redeem` returns `ALREADY_CONSUMED`                      | Duplicate `PlayerJoinEvent` (benign; second is a no-op) |
| `redeem` returns `EXPIRED`                               | Transfer took longer than `reservation.ttlMs`; increase TTL or shorten transfer latency |
