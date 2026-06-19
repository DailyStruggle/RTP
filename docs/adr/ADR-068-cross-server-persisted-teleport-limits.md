# ADR-068 - Cross-Server Persisted Teleport Limits

**Status:** Accepted
**Date:** 2026-06-19

## Context

RTP enforces two per-player "teleport limit" mechanisms:

- **Cooldown.** The guard in `RTP.java` (and `RTPCmd.compute()`) compares `dt = now - latestTeleportData.get(uuid).time` against the configured cooldown returned by `RTPCommandSender.cooldown()` (permission `rtp.cooldown.<n>` or `ConfigKeys.teleportCooldown`). The per-player last-teleport timestamp lives in `RTP.latestTeleportData` (`TeleportData.time`).
- **Usage cap** (BetterRTP `LockAfter` parity). `UsageCapTracker` keeps a per-player rolling `Window{count,start}` gated by `lockAfterUses` / `lockAfterResetSeconds`.

Two gaps exist today:

1. **Persistence is uneven.** The cooldown anchor (`TeleportData.time`) is already persisted: it is queued into `AbstractSQLDatabaseAccessor.writeQueue` via `cacheValue(TeleportData)`, flushed to whatever `DatabaseAccessor` is configured (`YamlFileDatabase` in the lite assembly, `SQLite`/`H2`/`MySQL`/`PostgreSQL` otherwise), and reloaded into `latestTeleportData` on startup. The usage cap is **not** persisted at all - `UsageCapTracker` is purely in-memory and `clear()`-ed on reload/shutdown, so it is lost on restart.
2. **No cross-server view.** In a multiserver (proxy) deployment each backend tracks limits independently, so a player can dodge a cooldown or usage cap by hopping backends.

The network subsystem already provides the seams needed to close both gaps:

- A swappable `NetworkTransport` SPI (`rtp-proxy-common`) with concrete bindings `RedisNetworkStateBinding`, `SqlNetworkStateBinding`, `InMemoryNetworkStateBinding`, and the lite-tier `PluginMessageNetworkBinding` (`rtp-core`), all selected by one switch: `transport.type` in `NetworkModeBootstrap.openTransport` (`auto` | `plugin-message` | `redis` | `sql` | `in-memory` | `disabled`), mirrored proxy-side in `NetworkBindings.open`.
- A connect boundary already used for routing: `RtpVelocityPlugin.onServerPreConnect` looks up reservation state when a player connects or hops backends.
- A heartbeat/gossip channel (`publishBackendHeartbeat` / `readSnapshot`) and, on the lite tier, the BungeeCord plugin-message vocabulary carried over the player's own connection.

Per the lite assembly contract (ADR-024), the lite jar strips JDBC/Jedis/Lettuce drivers and the `transport/{redis,sql}/` subtrees but keeps file-backed YAML persistence (`YamlFileDatabase`) and the plugin-message transport. Durability is therefore not synonymous with Redis/SQL: a file-backed store satisfies it in lite.

This decision is constrained by existing prohibitions: no chunk I/O or blocking on a tick thread (S-005), no silently swallowed teleport failures (S-004), the network-disabled byte-identical no-op contract (REQ-RTP-NET-002), and the lite-jar driver-exclusion audit (ADR-024).

## Decision

Introduce cross-server, persisted teleport limits structured along **two orthogonal axes**, sharing one contract across the lite and full editions.

### Axis 1 - Durability (where the authoritative copy lives)

A `TeleportLimitStore` abstraction in `rtp-core` that the cooldown guard and `UsageCapTracker` consult, layered as a decorator chain selected at bootstrap:

- **`local` (default, always present).** Each backend persists both the cooldown anchor and the usage-cap window to its own `DatabaseAccessor` (`YamlFileDatabase` in lite; SQL otherwise). Cooldown persistence already exists; this closes the usage-cap gap by writing the rolling window to the same store.
- **`proxy` (optional, multiserver).** A durable store on the proxy holds the authoritative cross-server record, exposed behind a durable-store interface with edition-specific implementations:
  - lite: a **file-backed YAML or JSON** store, mirroring `YamlFileDatabase`, with zero driver dependencies.
  - full (Pro): `SqlNetworkStateBinding` / `RedisNetworkStateBinding`.

Every backend persists locally **in all modes** (write-through cache), so a proxy or transport outage degrades to local enforcement rather than failing open, and a backend restart recovers from its local store, then reconciles on the next connect.

### Axis 2 - Transport (how state propagates), swappable

Propagation rides whichever `NetworkTransport` is configured, independent of where the durable copy lives:

- **Push-on-connect, not poll-per-teleport.** When the proxy routes/transfers a player (at the `ServerPreConnectEvent` boundary, or at login before connect completes), it sends **one** small snapshot to the target backend: the player's `{cooldownRemaining, usageCount, usageWindowRemaining}`. The backend seeds `latestTeleportData` / `UsageCapTracker` and enforces locally for the whole session with **zero per-teleport round trips**.
- **Write-behind on teleport.** On a successful teleport the backend records locally and sends one fire-and-forget delta toward the durable store. Traffic is O(connects + teleports) one-way pushes, not O(teleports) request/response pairs.
- The plugin-message tier carries the snapshot (its strength: rides the player connection, smallest packet, "on or before connect") but, being non-durable (ADR-016: best-effort no-op reservation methods, carrier-player dependent), it is a **transport only** - durability for that tier comes from the `local` YAML store on each backend plus the optional proxy YAML/JSON store.

### Wire format - relative time, not absolute clocks

The snapshot carries **remaining durations** (`cooldownRemainingMillis`, `usageWindowRemainingMillis`) computed by the sender at send time; the receiver rebases against its own `now` on arrival. Clock skew then affects only in-flight transfer latency (a few seconds), not the absolute host clock offset - no NTP requirement and no single-clock-source constraint. State at rest may still be stored as the receiver's local absolute epoch (`now + remaining`); only the wire is relative.

### Reconciliation

When both a proxy value and a local value exist, the more conservative (max-consumed) value wins, so neither a backend restart nor a stale local copy lets a player under-pay a limit.

### Configuration

```yaml
# network.yml (meaningful only when network is enabled)
limits:
  store: local            # local | proxy
  share-cooldowns: true
  share-usage-caps: true
# config.yml (single-server / always)
persistLimits: true       # local DB persistence of the usage-cap window (cooldown already persists)
```

Defaults: `persistLimits: true`; `store: local` (current behavior, no surprise) with `proxy` an opt-in. Everything stays inert when network mode is disabled (REQ-RTP-NET-002 no-op preserved).

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Poll the durable store on every `/rtp` (synchronous read on the request path) | Adds a network round trip (and reply) per teleport; risks blocking a tick thread (S-005) or stalling the command. Push-on-connect amortizes to one snapshot per connect. |
| Absolute epoch timestamps on the wire | Requires synchronized clocks across hosts; backend clock skew silently mis-scales cooldowns. Relative durations bound the error to transfer latency. |
| Require Redis/SQL for any cross-server persistence | Excludes the lite edition, which strips those drivers (ADR-024). A file-backed YAML/JSON store provides durability with zero driver deps. |
| Proxy-only authority, no local persistence | A proxy/transport outage would fail open (no enforcement) and a backend restart would lose all session state. Local write-through keeps every backend independently correct. |
| New dedicated transport SPI for limit propagation | The existing `NetworkTransport` heartbeat/gossip + connect-boundary seams already carry small per-player payloads; a parallel SPI would duplicate selection, lifecycle, and the lite/Pro split. |

## Consequences

- **Positive:** Usage caps survive restarts (parity with cooldown). Limits become cross-server, closing the backend-hop dodge. The lite edition gets a complete driver-free story (plugin-message transport + YAML/JSON file durability). Per-teleport network traffic stays at zero on the hot path. Skew handling needs no clock infrastructure. Network-disabled deployments are byte-identical no-ops.
- **Negative / Trade-offs:** A second store and a decorator chain add code in `rtp-core` and a new durable-store interface plus impls. Push-on-connect means a player who connects during a transport hiccup may, worst case, get one extra teleport before the snapshot arrives (fail-open-to-local is deliberate: availability over strict correctness on the connect path). The proxy-side file store needs atomic write-replace and concurrency care. Reconciliation rules (max-consumed) must be specified precisely to avoid double-counting a teleport recorded both locally and via write-behind.

## References

- ADR-024 (rtp-lite assembly variant; driver exclusion + YAML persistence retained)
- ADR-036 (network mode umbrella) and ADR-049 (platform-neutral network lift)
- rtp-proxy-ADR-016 (plugin-message default transport; non-durable best-effort reservation methods)
- rtp-proxy-ADR-006 (Velocity bootstrap; `ServerPreConnectEvent` redeem hook)
- rtp-proxy-ADR-011 (SQL network-state binding)
- REQ-RTP-NET-002 (network-disabled no-op contract); S-004, S-005 prohibitions
- Code: `RTP.latestTeleportData`, `UsageCapTracker`, `AbstractSQLDatabaseAccessor.cacheValue(TeleportData)`, `YamlFileDatabase`, `NetworkModeBootstrap.openTransport`, `NetworkTransport`, `PluginMessageNetworkBinding`, `RtpVelocityPlugin.onServerPreConnect`
