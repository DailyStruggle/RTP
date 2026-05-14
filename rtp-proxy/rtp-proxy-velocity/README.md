# `rtp-proxy-velocity/` — Velocity Adapter (primary)

> **Status: scaffolding only.** No code, no `build.gradle` yet. Gated by Rule D-005 and ADR-025 (outstanding). Phase 2 of [`docs/dev/MULTI_SERVER_PLAN.md`](../../docs/dev/MULTI_SERVER_PLAN.md).

The primary proxy adapter. Targets **Velocity 3.3.x or later on Java 21+**. Activates only when the Velocity runtime is detected on the classpath (`com.velocitypowered.api.proxy.ProxyServer`).

## Scope

- Register `/rtp` through Velocity's Brigadier command manager via the `commands-api` Brigadier bridge.
- Listen for `ServerPreConnectEvent` and rewrite the destination based on a `BackendSelector` choice.
- Publish a `proxy_state` heartbeat row keyed by `proxyId` (REQ-RTP-NET-014).
- Adapt Velocity's `CommandSource` to the `ProxySender` SPI from `rtp-proxy-common`.

Out of scope: world state, region authoring, claim integration, chunk handling — all of those are backend-side per REQ-RTP-NET-005.

## Planned package layout (notes only — not yet created)

```
src/main/java/io/github/dailystruggle/rtp/proxy/velocity/
├── RTPVelocityPlugin.java       # @Plugin(id="rtp") entry point
├── bootstrap/                   # Velocity DI module, lifecycle hooks, network.enabled:false no-op gate
├── command/                     # Brigadier wiring (reuses commands-api bridge)
├── event/                       # ServerPreConnectEvent listener → ReservationClient
├── sender/                      # VelocityProxySender (adapts CommandSource)
└── telemetry/                   # Velocity-specific ProxyStatePublisher (async Scheduler driver)
```

## Planned top-level files (not yet created)

| File | Purpose | Created when |
|---|---|---|
| `REQUIREMENTS.md` | `REQ-RTP-PROXY-VELOCITY-NNN` requirements | **Next step** |
| `build.gradle` | Velocity plugin module; depends on `rtp-proxy-common` | Phase 2 |
| `src/main/resources/velocity-plugin.json` | Velocity plugin descriptor | Phase 2 |
| `src/main/resources/network.yml` | Bundled default config (mirrors the straw-man in the plan) | Phase 2 |

## Optional further subdirectories (notes only)

- `src/main/resources/messages/` — *may* host the proxy-side default `messages.yml` once REQ-RTP-NET-006 message keys are enumerated. Defer until the message-key list stabilises so we don't ship two divergent defaults.
- `src/test/...` — *will* host integration tests against an embedded Velocity test harness, if one exists at the time; otherwise unit tests against mocked Velocity APIs only.
- `docs/` — *not* planned. Velocity-specific design notes go in `docs/dev/MULTI_SERVER_PLAN.md`; runtime troubleshooting goes in `docs/admin/proxies/TROUBLESHOOTING.md`.
- `src/main/java/.../transport/` — **not** planned at this layer. Transport bindings (Redis, Postgres, generic SQL, in-memory) live in `rtp-core` under the network-state member of `AbstractSQLDatabaseAccessor` per D3; the Velocity adapter only *consumes* the binding it is handed.

## Runtime contract highlights

- No blocking I/O on the netty event-loop. All persistence/transport calls hop to `Scheduler.buildTask(...).delay(...).repeat(...)` async tasks.
- Capped retry chain on backend rejection: `maxRetries=3`, `attemptTimeoutMs=1500`, `cooldownMs=2000` (defaults; see *Load Balancer* in the plan).
- `network.enabled: false` ⇒ adapter registers no listeners and opens no transport connections (REQ-RTP-NET-002 parity).

## Cross-references

- [`../README.md`](../README.md) — umbrella overview.
- [`../../docs/dev/MULTI_SERVER_PLAN.md`](../../docs/dev/MULTI_SERVER_PLAN.md) — Phase 2 acceptance (2× Velocity + 2× Paper + Redis; DragonflyDB parity).
- [`../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md`](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md) — Brigadier bridge rationale.
