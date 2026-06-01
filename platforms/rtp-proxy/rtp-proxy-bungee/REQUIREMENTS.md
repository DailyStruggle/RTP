# `rtp-proxy-bungee/` — Requirements

> **Status: draft, D-005 gated on ADR-036 (outstanding).** Module-scope requirements for the secondary proxy adapter, covering BungeeCord and Waterfall in a single artifact. Complements [`../REQUIREMENTS.md`](../REQUIREMENTS.md) (umbrella), [`../rtp-proxy-common/REQUIREMENTS.md`](../rtp-proxy-common/REQUIREMENTS.md), and [`../../docs/dev/REQUIREMENTS.md` §1.6](../../../docs/dev/REQUIREMENTS.md).
>
> Authoring style: `shall` / `shall not`, absolute state, no implementation actions.

## Scope

The BungeeCord proxy adapter, with runtime feature-detection covering Waterfall. Phase 3 of [`../../docs/dev/MULTI_SERVER_PLAN.md`](../../../docs/dev/MULTI_SERVER_PLAN.md). These requirements bind this module only.

## Namespace

`REQ-RTP-PROXY-BUNGEE-NNN`.

## Requirements

### REQ-RTP-PROXY-BUNGEE-001 — BungeeCord and Waterfall Runtime
The adapter shall target the current BungeeCord API on Java 21 or later and shall additionally function on Waterfall without code divergence. Waterfall-specific behaviour, where required, shall be selected by runtime feature-detection and not by a separate artifact. (Aligns with REQ-RTP-SYS-001 and REQ-RTP-PROXY-009.)

### REQ-RTP-PROXY-BUNGEE-002 — Server Rewrite via `ServerConnectEvent`
Backend rewrites for network teleports shall occur within a `ServerConnectEvent` handler — the BungeeCord analogue of Velocity's `ServerPreConnectEvent`. The handler shall not block.

### REQ-RTP-PROXY-BUNGEE-003 — No Brigadier Dependency
The adapter shall register `/rtp` through BungeeCord's native command registry and shall use the non-Brigadier surface of `commands-api`. The adapter shall not import Velocity-only command classes and shall not declare a Brigadier runtime dependency.

### REQ-RTP-PROXY-BUNGEE-004 — Scheduler Discipline
Persistence and transport calls shall execute on `ProxyServer#getScheduler().runAsync(...)`. Synchronous I/O from event handlers shall not occur.

### REQ-RTP-PROXY-BUNGEE-005 — Plugin-Message Transport Eligibility
When `transport.type: plugin-message` is selected, the adapter shall emit a startup warning and shall refuse to enable network mode outside a developer profile. (Refines D2 in [`../../docs/dev/MULTI_SERVER_PLAN.md`](../../../docs/dev/MULTI_SERVER_PLAN.md).)

### REQ-RTP-PROXY-BUNGEE-006 — Tab-Completion Parity
Tab-completion shall behave equivalently to the Velocity adapter — cross-network query, merge, and time-bounded cache — within BungeeCord's command-completion model. (Refines REQ-RTP-PROXY-011.)

### REQ-RTP-PROXY-BUNGEE-007 — Fork Compatibility Statement
BungeeCord-API-compatible forks (notably Waterfall, which is explicitly supported; Hexacord, FlameCord, and Travertine, which are tolerated but unsupported) shall not require a separate adapter artifact or a vendor-specific configuration knob.

## Traceability

Each requirement above shall receive a row in [`../../docs/dev/TRACEABILITY.md`](../../../docs/dev/TRACEABILITY.md) at status *unimplemented* upon ratification of ADR-036.

## Cross-References

- [`README.md`](README.md) — module overview.
- [`../REQUIREMENTS.md`](../REQUIREMENTS.md) — umbrella requirements.
- [`../../docs/dev/MULTI_SERVER_PLAN.md`](../../../docs/dev/MULTI_SERVER_PLAN.md) — Phase 3 acceptance baseline.
