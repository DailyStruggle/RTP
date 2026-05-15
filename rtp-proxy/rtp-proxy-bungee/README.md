# `rtp-proxy-bungee/` — BungeeCord / Waterfall Adapter (secondary)

> **Status: scaffolding only.** No code, no `build.gradle` yet. Gated by Rule D-005 and ADR-036 (outstanding). Phase 3 of [`docs/dev/MULTI_SERVER_PLAN.md`](../../docs/dev/MULTI_SERVER_PLAN.md).

The secondary proxy adapter. **One artifact covers both BungeeCord and Waterfall.** Waterfall-specific behaviour, if any, is detected at runtime — no separate distribution. Hexacord / FlameCord / Travertine are tolerated (BungeeCord-API compatible) but unsupported.

Activates only when the BungeeCord runtime is detected (`net.md_5.bungee.api.ProxyServer`).

## Scope

- Listen for `ServerConnectEvent` (the BungeeCord analogue of Velocity's `ServerPreConnectEvent`) and rewrite the destination based on a `BackendSelector` choice.
- Register `/rtp` through BungeeCord's native command registry (no Brigadier on Bungee).
- Publish a `proxy_state` heartbeat row keyed by `proxyId` (REQ-RTP-NET-014).
- Adapt BungeeCord's `CommandSender` to the `ProxySender` SPI from `rtp-proxy-common`.

Out of scope: same as Velocity adapter (no world state, no region authoring, no claim integration, no chunk handling).

## Planned package layout (notes only — not yet created)

```
src/main/java/io/github/dailystruggle/rtp/proxy/bungee/
├── RTPBungeePlugin.java         # extends net.md_5.bungee.api.plugin.Plugin
├── bootstrap/                   # Lifecycle hooks, network.enabled:false no-op gate
├── command/                     # Native BungeeCord command registry (NO Brigadier)
├── event/                       # ServerConnectEvent listener → ReservationClient
├── sender/                      # BungeeProxySender (adapts CommandSender)
└── telemetry/                   # Bungee-specific ProxyStatePublisher (TaskScheduler.runAsync)
```

## Planned top-level files (not yet created)

| File | Purpose | Created when |
|---|---|---|
| `REQUIREMENTS.md` | `REQ-RTP-PROXY-BUNGEE-NNN` requirements | **Next step** |
| `build.gradle` | Bungee plugin module; depends on `rtp-proxy-common` | Phase 3 |
| `src/main/resources/bungee.yml` | Bungee plugin descriptor | Phase 3 |
| `src/main/resources/network.yml` | Bundled default config | Phase 3 |

## Optional further subdirectories (notes only)

- `src/main/resources/messages/` — *may* host the proxy-side default `messages.yml`; defer until message-key list stabilises (shared with Velocity adapter — divergent defaults would violate the copy-paste deployment model).
- `src/test/...` — *will* host unit tests against mocked Bungee APIs. There is no widely-used embedded Bungee test harness; integration coverage relies on the Phase 3 devstack.
- `docs/` — *not* planned. Bungee/Waterfall-specific design notes go in `docs/dev/MULTI_SERVER_PLAN.md`.
- `src/main/java/.../waterfall/` — **not** planned. Waterfall divergences (if encountered) are handled by runtime feature-detection in the same package, not by a separate package. If a divergence becomes large enough to warrant separation, an ADR is required first.

## Runtime contract highlights

- No blocking I/O on the netty event-loop. All persistence/transport calls hop to `ProxyServer#getScheduler().runAsync(...)`.
- `transport.type: plugin-message` (D2 dev-only) emits a startup warning and refuses to enable network mode outside a developer profile.
- `network.enabled: false` ⇒ adapter registers no listeners and opens no transport connections (REQ-RTP-NET-002 parity).

## Cross-references

- [`../README.md`](../README.md) — umbrella overview.
- [`../../docs/dev/MULTI_SERVER_PLAN.md`](../../docs/dev/MULTI_SERVER_PLAN.md) — Phase 3 acceptance (BungeeCord + Postgres).
