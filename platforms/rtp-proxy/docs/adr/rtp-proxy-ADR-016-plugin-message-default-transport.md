# rtp-proxy-ADR-016 — `PluginMessageNetworkBinding` (tier-1 non-durable default) + `transport.type: auto`

**Status:** Accepted
**Accepted:** 2026-06-12 (D-005 gate cleared by repo owner leaf)
**Date:** 2026-06-12
**Refines:** [ADR-036](../../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md) (*Amendment: Plugin-Message Promoted to Tier-1 Default*)
**Depends on:** [rtp-proxy-ADR-001](rtp-proxy-ADR-001-spi-shape.md), [rtp-proxy-ADR-002](rtp-proxy-ADR-002-network-yml-schema.md)
**Related:** [ADR-024](../../../../docs/adr/ADR-024-rtp-lite-assembly-variant.md) (lite ships this binding), [rtp-proxy-ADR-005](rtp-proxy-ADR-005-redis-binding.md) / [rtp-proxy-ADR-011](rtp-proxy-ADR-011-sql-network-state-binding.md) (durable peers)
**Source proposal:** `docs/dev/scratch/PROPOSAL-plugin-message-network-default.md`

## Context

The durable network transports (Redis, SQL) require infrastructure (a database or RESP server) that the rtp-lite assembly ([ADR-024](../../../../docs/adr/ADR-024-rtp-lite-assembly-variant.md)) deliberately drops, and that many small/medium networks do not run. Competing free plugins (EzRTP) ship a database-free cross-server story: a backend-only server selector that moves players over the proxy's built-in plugin-messaging vocabulary (`Connect`) and detects backend up/down by a direct `host:port` server-list ping. Its ceiling is that it transmits only *server up/down*, never *which regions/worlds are servable*, and its `servers:` list is hand-maintained per backend and therefore drifts.

We can match that zero-infrastructure baseline and beat its capability by carrying our richer `BackendHeartbeat` (region availability, warm-cache counts, load) over the same plugin-messaging channel, and by auto-detecting the proxy so backends self-advertise instead of being hand-listed.

## Decision

Add `PluginMessageNetworkBinding implements NetworkTransport` as the **tier-1, non-durable default** transport, shipped in both the lite and Pro editions. It is a peer implementation of `InMemoryNetworkStateBinding` — **no `rtp-proxy-common` SPI surface change**. The dispatcher, `BackendSelector`, reservation flow, and command/tab-complete layer remain transport-agnostic.

### Transport tier ordering (amends ADR-036)

`inMemory` (dev/test) -> **`plugin-message` (tier-1 non-durable default; lite + Pro)** -> `sql` (tier 2 durable; Pro; MySQL free, Postgres `LISTEN/NOTIFY` faster) -> `redis` (tier 3 durable, atomic Lua claim; Pro). `transport.type: auto` (the lite default) resolves to `plugin-message` on proxy auto-detect and to `disabled` when standalone.

### Module placement

Keep **as much as possible in `rtp-core`** behind a thin `NetworkBridge` seam: message framing, heartbeat assembly, TTL expiry, snapshot maintenance, and the `auto` state machine are all platform-neutral and live in `rtp-core`. Only the raw "send these bytes on `bungeecord:main` over player P's connection" / "receive bytes" primitive is delegated to a small per-platform shim (Bukkit `sendPluginMessage`, Fabric custom-payload, etc.). This is deliberately **not** Bukkit-only: a Fabric or NeoForge backend fronted by Velocity reaches the same binding. BungeeCord support is layered on the existing Velocity support later and is not required for this tier.

### `NetworkTransport` method mapping

| Method | Behaviour |
|---|---|
| `publishBackendHeartbeat(BackendHeartbeat)` | Serialize the record (reusing the Redis flat-field encoder `encodeBackend`/`decodeBackend`, so tiers 1/2/3 share one decoder and field set) and emit it on the proxy `Forward` (or `ForwardToPlayer`) sub-channel of `bungeecord:main`, throttled to `heartbeatTicks` (default 200). |
| `subscribeBackendHeartbeats(sink)` / `readSnapshot()` | Maintain the last-seen `BackendHeartbeat` per `serverId`; expire entries on `staleTimeoutMillis` (default 1500); invoke `sink` on change; assemble the `NetworkSnapshot`. |
| Move | The dispatcher's connect step emits `Connect <serverId>` (or `ConnectOther <player> <serverId>`) on the issuing player's connection; the destination backend detects the join and runs a normal local `/rtp`. |
| `claim` / `redeem` / `release` / `reapExpired` / `findReservation` | **Best-effort / no-op.** No durable token store. On a miss (region drained, world unloaded, backend gone), the destination's local pipeline bounces with a configurable S-007 "busy / no such region" message and the player re-issues. |

### Proxy auto-detection (`transport.type: auto`)

Two layers:

1. **Passive probe (gate, not source of truth).** Read whether proxy forwarding is enabled: Spigot `settings.bungeecord: true` (`spigot.yml`); Paper `proxies.bungee-cord` / `proxies.velocity` (`paper-global.yml`). Arms network mode; supplies neither this backend's proxy name nor the sibling list.
2. **Active handshake (the real detect + topology learn).** Register `bungeecord:main` always; on the first player join send `GetServer` (proxy replies with this backend's own name) and `GetServers` (proxy replies with the full backend-name list).

State machine: `UNKNOWN` -> (passive probe true) `ARMED` -> (first join: send handshake) `PROBING` -> (reply within `staleTimeoutMillis`) `ENABLED`; else `DISABLED` (standalone). Heartbeat gossip begins only in `ENABLED`. **Re-probe** on first join and on proxy reconnect (a standalone server that later joins a proxy must not require a restart).

**Velocity audit (2026-06-13):** modern Velocity honors the full BungeeCord plugin-messaging vocabulary (`Connect`, `ConnectOther`, `GetServer`, `GetServers`, `Forward`, `ForwardToPlayer`, ...) through its **own built-in handler** on `bungeecord:main`, gated by `bungee-plugin-message-channel = true` in `velocity.toml` (the default). The `rtp-proxy-velocity` adapter therefore needs **no** channel registration or handler code for the tier-1 transport: the backend's `NetworkBridge` shim speaks to Velocity's native handler directly, and replies (`GetServer`/`GetServers`, forwarded `Forward` payloads) arrive on the backend's registered incoming `bungeecord:main` channel. The only operator-side requirement is leaving `bungee-plugin-message-channel` enabled. Holding the channel open independent of player presence (so an empty/paused backend can still gossip) remains the optional proxy-companion enhancement noted under *Honest limitations*, not a tier-1 requirement.

### `network.yml` (lite) shape

Minimal; no hand-typed server list in `auto` mode. `servers:` is an optional per-server policy overlay (permission / display-name / hide-when-unavailable) keyed by discovered names. `host`/`port` are gone (availability is the heartbeat, not a socket ping).

### Tab-completion / validation

A `NetworkRegionParameter` whose `values()` returns the cached availability snapshot for the chosen target and whose `isRelevant` validates against the same snapshot. **Key rule: when the snapshot is unknown/stale, `isRelevant` must accept** (let the destination decide); suggestion filtering may stay strict. Otherwise transport lag becomes user-visible command failures. The snapshot provider is pluggable (config overlay / plugin-message / SQL / Redis).

## Honest limitations (the durable-tier upgrade boundary)

1. **Everything rides a player connection.** `GetServers`, `Forward`, and `Connect` need an online player on the sending backend. Auto-detect completes only on first join; a player-empty backend cannot broadcast its heartbeat.
2. **Self-pausing backends.** Fabric/NeoForge backends self-pause after ~60 s idle: their availability goes stale (`staleTimeoutMillis` is far shorter than the pause window, so they are correctly shown stale -> treat unknown as accept). A `Connect` move still wakes a paused backend; only its *advertised availability* is unreliable while paused.
3. **Single-proxy fan-out.** `Forward` only reaches backends visible to one proxy. Multi-proxy (RedisBungee-style) networks need tier 2/3.
4. **Non-durable.** No reservation survives a proxy restart (ADR-036 D2). Worst case is a re-issued `/rtp`.
5. **Optional proxy companion (future).** The existing `rtp-proxy-velocity` / `rtp-proxy-bungee` plugins can hold the channel open independent of player presence and relay heartbeats reliably — an enhancement, not a baseline requirement.

## Safety (S-00x)

The destination always runs the normal local pipeline (spiral -> chunk -> safety), so S-001/S-004/S-005 hold unchanged; tier-1 transports *intent*, not a coordinate. Move failures are logged, never swallowed (S-004); busy/invalid messages are configurable (S-007).

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Keep plugin-message dev-only; require a DB for any cross-server RTP | Leaves the free build with no cross-server story; cedes the EzRTP parity gap; forces small networks onto infrastructure they do not run. |
| Match EzRTP exactly (server up/down ping + hand-typed `servers:`) | Transmits no region availability and drifts per-backend; we already model richer `BackendHeartbeat` data, so a heartbeat over `Forward` is strictly better at the same infra cost. |
| New compact binary wire format for `Forward` payloads | Adds a second decoder to maintain; reusing the Redis flat-field encoder keeps tier 1/2/3 on one field set. |
| Put the binding in a Bukkit adapter | Excludes Fabric/NeoForge backends behind Velocity; the `NetworkBridge`-in-`rtp-core` seam keeps the binding reachable from every platform. |

## Consequences

- **Positive:** DB-free cross-server RTP in lite and Pro; backends self-advertise (no hand-typed list); real region availability beats EzRTP's up/down ping; zero SPI change; durable Pro tiers reused unchanged behind the same SPI; lite jar gains no driver.
- **Negative / Trade-offs:** non-durable (re-issue on miss); player-connection-dependent (stale availability for empty/paused backends); single-proxy fan-out only. These are the documented upgrade boundary to the SQL/Redis tiers, not defects.

## References

- ADR-036 *Amendment: Plugin-Message Promoted to Tier-1 Default*; `MULTI_SERVER_PLAN.md` *Amendment: Plugin-Message Default Tier*.
- ADR-024 (lite assembly; 2026-06-12 amendment ships this binding).
- rtp-proxy-ADR-001 (SPI shape), rtp-proxy-ADR-002 (`network.yml` schema), rtp-proxy-ADR-005 (Redis peer), rtp-proxy-ADR-011 (SQL peer).
- `REQ-RTP-NET-007`, `-009`, `-011`, `-012`, `-014`.
- Source proposal: `docs/dev/scratch/PROPOSAL-plugin-message-network-default.md`.
