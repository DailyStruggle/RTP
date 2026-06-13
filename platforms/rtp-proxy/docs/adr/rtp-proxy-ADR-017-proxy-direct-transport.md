# rtp-proxy-ADR-017: proxy-direct transport (player-independent backend->proxy socket)

- Status: Accepted
- Date: 2026-06-13
- Supersedes: none
- Related: rtp-proxy-ADR-016 (plugin-message default transport), rtp-proxy-ADR-010 (security hardening / HMAC), rtp-proxy-ADR-005 (Redis binding), rtp-proxy-ADR-011 (SQL binding), ADR-024 (rtp-lite assembly)

## Context

On the DB-free plugin-message tier (`transport.type: auto` / `plugin-message` / `proxy-cache`), cross-server `/rtp region=<server>:<region>` tab-completion could not converge when no players were online on the peer backends. The reason is structural, not a wiring bug:

- Minecraft/Velocity plugin messaging is **per-player-connection**. Velocity only holds a `ServerConnection` to a backend while a player is routed through it; it keeps no idle connections to backends. `RegisteredServer.sendPluginMessage` returns "could the message be sent" and is a no-op when no player is connected to that backend. `RegisteredServer.ping()` is the only player-independent reach, but it uses the status protocol and cannot carry a custom plugin message.
- Therefore a player-empty backend can neither push its region list to the proxy nor be solicited by the proxy. The proxy companion has no independent knowledge of a backend's region names (it has no worlds / `regions.yml` / SelectionAPI of its own).

The previously-shipped fallbacks each forfeit one property the operator wanted:

- Backend heartbeat push (plugin-message): real region names, no operator config, but **requires a player warm-up** (rides a player connection).
- Operator-declared `servers.<id>.regions` map (direction B): real names, no warm-up, but **manual per-server config**.
- Durable store (Redis/SQL): real names, no warm-up, no per-server config, but **needs a database**, excluded by the lite / DB-free edition.

Real region names with **zero players AND zero per-server config AND no database** is impossible *over plugin messaging* because there is no player-independent backend<->proxy channel there.

## Decision

Introduce a new `transport.type: proxy-direct` tier in which the **backend opens its OWN outbound TCP connection to the proxy companion**, identified by a configured, mirrorable proxy address list. This is not the Minecraft play session, so it is not player-gated: a player-empty backend dials the proxy at startup, publishes its real region list, and reads the merged network snapshot back.

- **Wire protocol** (`ProxyDirectWire`, `rtp-proxy-common`): length-prefixed frames over `Socket`. Request = one signed payload (a `BackendHeartbeatCodec` string); response = `int count` then one signed payload per row. Each payload carries `int schemaVersion`, `UTF hmacHex` (`""` = unsigned), `UTF payload`. HMAC-SHA-256 via the existing `HmacVerifier` (rtp-proxy-ADR-010), reusing `network.secretEnv`; unsigned fallback matches the Redis binding's legacy 4-arg path. A frame failing verification is dropped (S-004), never thrown.
- **Backend side** (`ProxyDirectNetworkBinding`, `rtp-core`): a `NetworkTransport` that, on each `BackendStatePublisher` tick (driven by `RTP.scheduler` — no raw backend thread), opens a short-lived socket to each configured proxy, pushes its heartbeat, and ingests the returned snapshot into a stale-bounded local view that `readSnapshot()` / `PeerRegionRegistry` read. Reservation-token methods are unsupported (non-durable discovery tier, like `ProxyCacheNetworkBinding`).
- **Proxy side** (`ProxyDirectListener`, `rtp-proxy-velocity`): a `ServerSocket` + worker pool (a legitimate proxy-JVM raw-executor carve-out) that ingests pushes into and serves snapshots from the same `VelocityProxyAvailabilityCache` used by the `rtp:net` plugin-message companion. Enabled by `transport.direct.enabled: true` or `transport.type: proxy-direct`; binds `transport.direct.bindHost` (default `0.0.0.0`) : `transport.direct.port` (default `25599`).
- **No assumed-default topology seeding.** A server that has not actually reported is not fabricated with a guessed `default` region (an offline backend is not a valid RTP target). Real names arrive player-independently via proxy-direct; the operator `servers:` map remains an optional override; live pushes win while fresh.

Config schema (mirrorable — identical on every backend):

```yaml
transport:
  type: proxy-direct
  proxies: ["proxy-a:25599", "proxy-b:25599"]   # default port 25599
# proxy side:
transport:
  type: auto            # the proxy's own state binding is unchanged
  direct:
    enabled: true
    port: 25599
    bindHost: "0.0.0.0"
```

## Consequences

- Cross-server region tab-completion converges on the lite/DB-free edition with **zero players on any backend and zero per-server operator config** - only a single mirrorable proxy-address stanza per backend (far less than EZRTP's per-server list).
- Backends now make outbound TCP connections to the proxy; operators must allow `backend -> proxy:25599` on the internal network. HMAC (when `RTP_NET_SECRET` is set) authenticates pushes.
- The tier is non-durable: it carries region availability for discovery, not reservation tokens. Durable cross-server reservations still require the SQL/Redis tier.
- Multi-proxy: a backend publishes to every listed proxy; each proxy holds the full picture from backend pushes, so no proxy<->proxy coordination is needed for region discovery.

## Alternatives considered

- **Proxy seeds from its own `getAllServers()` (velocity.toml) with assumed `default`**: works with zero players/config for *discovery*, but cannot supply real region names and would advertise offline servers - rejected per the "don't assume defaults for a server that isn't online" requirement.
- **Keep relying on player warm-up / operator config / durable store**: each forfeits one of the three operator wants (see Context).
