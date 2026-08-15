# Cross-Server RTP for Addons

When RTP runs in network mode behind a proxy (Velocity / BungeeCord), it keeps a shared, live view
of every backend. An addon can ride that same view to offer destinations on other servers - a warp
menu whose rows are remote shards, an NPC that scatters you onto a different survival world, a "find
me a fresh server" command. You do not write any transport: you read a snapshot, claim a coordinate,
and let the backend redeem it.

This page is the worked example for addon authors. For the operator (`network.yml`) side see
[admin/proxies/INDEX.md](../admin/proxies/INDEX.md); for a local (single-server) menu see
[ADDON_MENUS.md](ADDON_MENUS.md).

> The proxy already runs the hard part - heartbeats, shared state (Redis/SQL/in-memory), reservation
> tokens, the connect handoff. Your addon composes those primitives. The only new code you write is
> layout plus a `readSnapshot()` call and a `claim(...)` call. Safety, validation, and the actual
> teleport stay inside the backend engine.

## The flow at a glance

| Step | Existing seam | What you do |
|---|---|---|
| 1. Discover online backends and their regions | `NetworkTransport.readSnapshot()` -> `NetworkSnapshot` (per-backend `BackendHeartbeat`) | List remote servers/regions as rows |
| 2. Decorate each row | fields already in the heartbeat (player count, TPS, "region ready") | Draw the row |
| 3. Reserve a coordinate on the chosen backend | `NetworkTransport.claim(serverId, playerId, ttl, Optional.of(regionKey))` -> `ReservationToken` | Call on click |
| 4. Route the player; backend re-attaches the region | the backend's join trigger redeems the token and runs `rtp region=<server>:<region>` | Send the player through the proxy |

The wire form `/rtp region=<server>:<region>` and the region-carrying `claim(...)` overload already
ship, so "RTP onto another server" is a presentation feature on top of the existing transport.

## Dependency

Depend on `rtp-proxy-common` for the proxy SPI (it is not in `rtp-api` / `rtp-core`):

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies {
    compileOnly 'com.github.DailyStruggle.RTP:rtp-api:VERSION'
    // proxy SPI lives in rtp-proxy-common, not rtp-api/rtp-core:
    compileOnly 'com.github.DailyStruggle.RTP:rtp-proxy-common:VERSION'
}
```

## Happy path

```java
// 1. Read the live network view (off-tick; never block a region thread on this).
NetworkSnapshot snapshot = transport.readSnapshot();

// 2. Build one row per online backend region.
for (BackendHeartbeat backend : snapshot.backends()) {
    if (!backend.online()) continue;
    for (String regionKey : backend.regions()) {
        addRow(backend.serverId(), regionKey,
               backend.playerCount(), backend.tps()); // decorate from the heartbeat
    }
}

// 3. On click: reserve a coordinate on the chosen backend.
CompletableFuture<ReservationToken> reservation =
    transport.claim(serverId, playerId, ttl, Optional.of(regionKey));

reservation.whenComplete((token, error) -> {
    // 4. Hop back to the host scheduler before touching the player (S-005).
    RTP.scheduler.runTask(() -> {
        if (error != null || token == null) {
            // No reservation: fall through to local routing, never a fatal error (S-004).
            openLocalFallback(playerId);
            return;
        }
        routePlayerToServer(playerId, serverId); // your proxy connect call
        // The backend's join trigger redeems the token and runs rtp region=<server>:<region>.
    });
});
```

## The rules that keep it safe

- Respect the transport threading contract. Every `NetworkTransport` future completes on a
  transport-owned executor, not a server tick thread. You must hop back to the host scheduler
  (`RTP.scheduler`) before touching world or player state (S-005). Treat a missing or expired
  reservation as "no reservation, fall through to local routing" - never as a fatal error, and never
  swallow it silently (S-004).
- Network rows are proxy-gated. Remote destinations only exist when a `NetworkTransport` binding
  (Redis / SQL / in-memory) is installed. With no binding, degrade to local targets - exactly as the
  GUI addon falls back to the classic teleport when no renderer is registered. Check for the binding
  and hide remote rows when it is absent; do not silently no-op.
- Reservations expire on purpose. A `ReservationToken` carries a TTL. If the player never connects,
  the proxy reaps the held coordinate so it is not lost. Pick a TTL that comfortably covers your
  connect handoff, and do not assume a token stays valid indefinitely.

## What you do not do

- You do not open your own Redis/SQL connection - read through `NetworkTransport`.
- You do not teleport the player yourself on the remote backend - the backend redeems the token and
  runs its own safe, validated `rtp region=<server>:<region>`.
- You do not re-implement backend selection policy if you do not need to - the proxy already has a
  `BackendSelector` for "pick any healthy backend"; use `claim` with an explicit `serverId` only when
  the player chose a specific one.

## Where the proxy SPI lives

`NetworkTransport`, `NetworkSnapshot`, `BackendHeartbeat`, `BackendSelector`, and `ReservationToken`
live in `rtp-proxy-common` - not in `rtp-api` or `rtp-core`. These are proxy-tier types; see
[ADR-036](../adr/ADR-036-network-mode-multi-server-multi-proxy.md) for the design and
[MULTI_SERVER_PLAN.md](MULTI_SERVER_PLAN.md) for the roadmap.

## See also

- [ADDON_MENUS.md](ADDON_MENUS.md) - building a local destination menu on `rtp-api`.
- [FOR_ADDON_DEVELOPERS.md](../FOR_ADDON_DEVELOPERS.md) - the addon-developer landing page.
- [admin/proxies/INDEX.md](../admin/proxies/INDEX.md) - the operator-facing network-mode docs.
