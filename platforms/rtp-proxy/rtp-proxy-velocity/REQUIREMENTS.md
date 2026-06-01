# `rtp-proxy-velocity/` — Requirements

> **Status: draft, D-005 gated on ADR-036 (outstanding).** Module-scope requirements for the primary proxy adapter, targeting Velocity. Complements [`../REQUIREMENTS.md`](../REQUIREMENTS.md) (umbrella), [`../rtp-proxy-common/REQUIREMENTS.md`](../rtp-proxy-common/REQUIREMENTS.md), and [`../../docs/dev/REQUIREMENTS.md` §1.6](../../../docs/dev/REQUIREMENTS.md).
>
> Authoring style: `shall` / `shall not`, absolute state, no implementation actions.

## Scope

The Velocity proxy adapter. Phase 2 of [`../../docs/dev/MULTI_SERVER_PLAN.md`](../../../docs/dev/MULTI_SERVER_PLAN.md). These requirements bind this module only.

## Namespace

`REQ-RTP-PROXY-VELOCITY-NNN`.

## Requirements

### REQ-RTP-PROXY-VELOCITY-001 — Velocity Runtime
The adapter shall target Velocity 3.3.x or later on Java 21 or later, and shall activate only when the Velocity runtime is detected on the classpath. (Aligns with REQ-RTP-SYS-001 and REQ-RTP-PROXY-009.)

### REQ-RTP-PROXY-VELOCITY-002 — Brigadier Command Hosting
`/rtp` and its subcommands shall be registered through Velocity's Brigadier command manager via the `commands-api` Brigadier bridge. The adapter shall not register a parallel non-Brigadier command surface for the same commands.

### REQ-RTP-PROXY-VELOCITY-003 — Server Rewrite via `ServerPreConnectEvent`
Backend rewrites for network teleports shall occur within a `ServerPreConnectEvent` handler. The handler shall not block; long-running coordination shall execute on an asynchronous Velocity `Scheduler` task before any I/O.

### REQ-RTP-PROXY-VELOCITY-004 — Player Session Continuity
The adapter shall not disconnect a player to change destination. A failed transfer shall surface a configurable message and leave the player on the current backend.

### REQ-RTP-PROXY-VELOCITY-005 — Tab-Completion Routing
Tab-completion for `/rtp` arguments shall query backends through the configured transport, merge the responses, and serve subsequent responses from a local time-bounded cache. The cache duration shall be configurable. (Refines REQ-RTP-PROXY-011.)

### REQ-RTP-PROXY-VELOCITY-006 — Telemetry Scheduling
`ProxyStatePublisher` shall publish heartbeats via `Scheduler.buildTask(...).delay(...).repeat(...)` on the asynchronous pool. Heartbeat emission shall not occur on the netty event-loop.

### REQ-RTP-PROXY-VELOCITY-007 — Plugin Identity and Version Coupling
The plugin shall declare `id = "rtp"` and a version coupled to the umbrella `rtp-plugin` version. A Velocity host whose `schemaVersion` is incompatible with a backend's reported `schemaVersion` shall refuse to claim reservation tokens for that backend and shall surface a configurable error. (Supports REQ-RTP-NET-009 schema negotiation.)

## Traceability

Each requirement above shall receive a row in [`../../docs/dev/TRACEABILITY.md`](../../../docs/dev/TRACEABILITY.md) at status *unimplemented* upon ratification of ADR-036.

## Cross-References

- [`README.md`](README.md) — module overview.
- [`../REQUIREMENTS.md`](../REQUIREMENTS.md) — umbrella requirements.
- [`../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md`](../../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md) — Brigadier bridge rationale.
- [`../../docs/dev/MULTI_SERVER_PLAN.md`](../../../docs/dev/MULTI_SERVER_PLAN.md) — Phase 2 acceptance baseline.
