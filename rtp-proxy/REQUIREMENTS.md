# `rtp-proxy/` — Umbrella Requirements

> **Status: draft, D-005 gated on ADR-025 (outstanding).** This document defines module-scope requirements that apply to **every** proxy adapter under `rtp-proxy/`. It complements the system-level `REQ-RTP-NET-NNN` requirements in [`../docs/dev/REQUIREMENTS.md` §1.6](../docs/dev/REQUIREMENTS.md) and the design narrative in [`../docs/dev/MULTI_SERVER_PLAN.md`](../docs/dev/MULTI_SERVER_PLAN.md).
>
> Authoring style: `shall` / `shall not`, absolute state, no implementation actions (per [`../docs/dev/RULES.md`](../docs/dev/RULES.md)).

## Scope

These requirements bind any module under `rtp-proxy/` that ships an adapter for a Java proxy host (Velocity, BungeeCord, Waterfall, or any future addition). They do not bind backend platforms (`rtp-spigot`, `rtp-paper`, `rtp-folia`, `rtp-fabric`) and do not bind `rtp-core` directly.

## Namespace

`REQ-RTP-PROXY-NNN`. Per-adapter modules use their own namespaces (`REQ-RTP-PROXY-COMMON-NNN`, `REQ-RTP-PROXY-VELOCITY-NNN`, `REQ-RTP-PROXY-BUNGEE-NNN`).

## Requirements

### REQ-RTP-PROXY-001 — Adapter SPI Conformance
Every proxy adapter shall implement the service-provider interfaces defined by `rtp-proxy-common`. A proxy adapter shall not define or expose a parallel SPI scoped to a single proxy flavour.

### REQ-RTP-PROXY-002 — No World State
A proxy adapter shall not own world data, region definitions, chunk handles, or entity references. Authoritative world state shall reside on backends only. (Refines REQ-RTP-NET-005.)

### REQ-RTP-PROXY-003 — Non-Blocking Event Loop
A proxy adapter shall not perform blocking I/O on a proxy event-loop thread. Persistence, transport, and reservation-claim operations shall execute on an asynchronous scheduler appropriate to the host proxy. (Refines REQ-RTP-NET-007.)

### REQ-RTP-PROXY-004 — Reservation Claim Idempotency
A proxy adapter shall claim reservation tokens via the shared-store atomic primitive defined by `rtp-proxy-common`. A `PENDING → CLAIMED` race between concurrent proxies shall be resolved by row-count atomicity, and a losing claim shall surface a configurable failure message. (Refines REQ-RTP-NET-012 and REQ-RTP-NET-014.)

### REQ-RTP-PROXY-005 — Proxy Heartbeat Publication
Each proxy adapter shall publish a `proxy_state` row keyed by `proxyId` to the configured shared store at the configured cadence. The heartbeat shall be emitted asynchronously. (Refines REQ-RTP-NET-014.)

### REQ-RTP-PROXY-006 — Configurable Messaging
A proxy adapter shall route every user-visible message — routing, queueing, success, failure — through the `messages.yml`-equivalent mechanism defined by `rtp-proxy-common`. A user-facing string shall not be hardcoded in adapter source. (Refines REQ-RTP-NET-006 and REQ-RTP-F-013.)

### REQ-RTP-PROXY-007 — Authenticated Transport
A proxy adapter shall reject any inbound coordination payload whose HMAC fails verification or whose `schemaVersion` is unsupported. Rejections shall be audited under REQ-RTP-S-004. (Refines REQ-RTP-NET-009.)

### REQ-RTP-PROXY-008 — Disabled-Mode No-Op
With `network.enabled: false`, a proxy adapter shall not register listeners, shall not open transport connections, shall not write `proxy_state` rows, and shall not otherwise mutate the shared store. (Refines REQ-RTP-NET-001 and REQ-RTP-NET-002.)

### REQ-RTP-PROXY-009 — Single-Artifact Activation
A proxy adapter shall activate only when its host proxy runtime is detected on the classpath. It shall not activate on a backend, shall not activate on a different proxy flavour, and shall not require a separate distribution artifact. (Refines REQ-RTP-NET-003.)

### REQ-RTP-PROXY-010 — Adapter Isolation
A proxy adapter shall not import another proxy adapter's classes and shall not depend on backend-side platform classes (`org.bukkit.*`, `net.fabricmc.*`, `net.minecraft.*`). Shared logic shall reside in `rtp-proxy-common`. (Architecture Boundaries.)

### REQ-RTP-PROXY-011 — Bounded Tab-Completion Cost
Cross-network tab-completion shall be bounded in fan-out and shall be served from a configurable local cache. An unbounded broadcast to all backends per keystroke shall not occur.

## Traceability

Each requirement above shall receive a row in [`../docs/dev/TRACEABILITY.md`](../docs/dev/TRACEABILITY.md) at status *unimplemented* upon ratification of ADR-025.

## Cross-References

- [`README.md`](README.md) — umbrella directory overview.
- [`../docs/dev/MULTI_SERVER_PLAN.md`](../docs/dev/MULTI_SERVER_PLAN.md) — design narrative.
- [`../docs/dev/REQUIREMENTS.md`](../docs/dev/REQUIREMENTS.md) §1.6 — `REQ-RTP-NET-NNN` system-level requirements.
- [`../docs/dev/RULES.md`](../docs/dev/RULES.md) — authoring style guide.
