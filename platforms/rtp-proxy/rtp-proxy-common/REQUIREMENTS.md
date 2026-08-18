# `rtp-proxy-common/` — Requirements

> **Status: draft, D-005 gated on ADR-036 (outstanding).** Module-scope requirements for the shared proxy-side SPI module. Complements [`../REQUIREMENTS.md`](../REQUIREMENTS.md) (umbrella) and [`../../docs/dev/REQUIREMENTS.md` section 1.6](../../../docs/dev/REQUIREMENTS.md) (system-level `REQ-RTP-NET-NNN`).
>
> Authoring style: `shall` / `shall not`, absolute state, no implementation actions.

## Scope

`rtp-proxy-common` hosts the host-independent SPI, the reference `BackendSelector`, the reservation client, trigger sources, the `network.yml` schema, and message keys. These requirements bind this module only.

## Namespace

`REQ-RTP-PROXY-COMMON-NNN`.

## Requirements

### REQ-RTP-PROXY-COMMON-001 — Host-Independent SPI
`rtp-proxy-common` shall not import any proxy-vendor class (`com.velocitypowered.*`, `net.md_5.bungee.*`) and shall not import any backend-platform class (`org.bukkit.*`, `net.minecraft.*`, `net.fabricmc.*`). Proxy-specific types shall be reached only through the `ProxySender` and `RtpDispatcher` SPIs.

### REQ-RTP-PROXY-COMMON-002 — Pure-Function Selector
`BackendSelector#choose(RtpRequest, NetworkSnapshot)` shall be implementable as a pure function of its declared inputs. The reference weighted-average implementation shall not perform I/O during evaluation.

### REQ-RTP-PROXY-COMMON-003 — Snapshot Freshness Filter
The reference selector shall exclude any backend whose `lastSeenEpochMs` is older than `loadBalancer.staleAfterMs` relative to the snapshot timestamp.

### REQ-RTP-PROXY-COMMON-004 — Capped Retry Chain
The reference dispatcher shall implement a capped-retry chain parameterised by `loadBalancer.maxRetries`, `loadBalancer.attemptTimeoutMs`, and `loadBalancer.cooldownMs`. Upon exhausting the cap, the dispatcher shall surface a configurable failure message and shall not silently swallow the failure (REQ-RTP-S-004).

### REQ-RTP-PROXY-COMMON-005 — Trigger Source Plurality
`rtp-proxy-common` shall ship a `CommandTriggerSource`, a `JoinTriggerSource`, and an `EventTriggerSource`. Each shall be independently enableable via configuration.

### REQ-RTP-PROXY-COMMON-006 — Single Config Schema
`rtp-proxy-common` shall define and validate the canonical `network.yml` schema. A proxy adapter shall not extend the schema with vendor-only keys; vendor-specific behaviour shall be expressed via schema-defined `transport.type` or `loadBalancer.*` parameters only.

### REQ-RTP-PROXY-COMMON-007 — Transport Binding Pluggability
The `NetworkTransport` SPI shall accept, without source change to `rtp-proxy-common`, the following bindings: a Redis (Lettuce, asynchronous) binding, a Postgres binding, a generic-SQL polling binding, an in-memory binding, and a developer-only `plugin-message` binding. The binding selection shall be schema-driven.

### REQ-RTP-PROXY-COMMON-008 — Hot-Spot Counter Locality
The reference selector's `recentPicks` decaying counter shall be evaluable from per-proxy local state. A shared-store round-trip per selection shall not be required in the version-1 hot path.

## Traceability

Each requirement above shall receive a row in [`../../docs/dev/TRACEABILITY.md`](../../../docs/dev/TRACEABILITY.md) at status *unimplemented* upon ratification of ADR-036.

## Cross-References

- [`README.md`](README.md) — module overview.
- [`../REQUIREMENTS.md`](../REQUIREMENTS.md) — umbrella requirements.
- [`../../docs/dev/MULTI_SERVER_PLAN.md`](../../../docs/dev/MULTI_SERVER_PLAN.md) section *Load Balancer*, section *Trigger Abstraction*, section *Reservation Tokens*, section *Storage Topology*.
