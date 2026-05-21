# `rtp-proxy` Subproject ADRs

This directory holds Architectural Decision Records scoped to the `rtp-proxy/` subtree (the proxy adapter module family). Numbering is **independent** of the global `docs/adr/` sequence and restarts at `001` here, per the *Self-Updating Protocol* in `.junie/AGENTS.md`.

> Project-wide rationale for the proxy subsystem lives in the umbrella **[ADR-036 — Network Mode (Multi-Server, Multi-Proxy)](../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md)**. The ADRs in this directory **refine** ADR-036; they do not supersede it.

## Naming

Use the per-directory pattern: `rtp-proxy-ADR-NNN-<slug>.md`. The slug is a short kebab-case summary of the decision (e.g., `spi-shape`, `redis-binding`, `velocity-bootstrap`).

Add a row to the *Subproject ADRs* table in [`docs/adr/README.md`](../../../docs/adr/README.md) whenever a new ADR lands here.

## Status Lifecycle

- **Proposed** — drafted, not yet ratified. No implementation may rely on the decision (D-005).
- **Accepted** — ratified; implementation may proceed.
- **Superseded** — replaced by a later ADR. Link the successor in the header.
- **Deprecated** — no longer applies but kept for historical context.

## Planned Series (from ADR-036)

The following ADR numbers are reserved by ADR-036's *Phased Subproject ADR Plan*. ADRs `-001` through `-010` are now drafted (Status: `Proposed`) and pending ratification alongside ADR-036; each refines a specific slice of the umbrella decision so Phase 1+ code can proceed without re-litigating contracts.

| ADR | Slug | Phase | Status | Scope (short) |
|-----|------|-------|--------|---------------|
| `rtp-proxy-ADR-001` | `spi-shape` | Phase 1 | Proposed | `RtpDispatcher`, `BackendSelector`, `NetworkTransport`, `ProxySender`, `ReservationClient` SPI surfaces |
| `rtp-proxy-ADR-002` | `network-yml-schema` | Phase 1 | Proposed | Canonical `network.yml` schema + validation rules |
| `rtp-proxy-ADR-003` | `in-memory-binding` | Phase 1 | Proposed | Reference `InMemoryNetworkStateBinding` for tests and `enabled:false` parity |
| `rtp-proxy-ADR-004` | `weighted-average-selector` | Phase 1 | Proposed | Reference selector: metrics, curves, parameter bounds, fallback chain |
| `rtp-proxy-ADR-005` | `redis-binding` | Phase 2 | Accepted (2026-05-18) | Lettuce-backed binding; Dragonfly/KeyDB parity contract; HMAC-on-wire + Lua SHA1 sidecar amendments |
| `rtp-proxy-ADR-006` | `velocity-bootstrap` | Phase 2 | Accepted (2026-05-18) | Velocity plugin entry, `ServerPreConnectEvent` flow, Brigadier wiring |
| `rtp-proxy-ADR-007` | `postgres-binding` | Phase 3 | Proposed (superseded by ADR-011 sketch) | `LISTEN/NOTIFY` + `SELECT … FOR UPDATE SKIP LOCKED` binding |
| `rtp-proxy-ADR-008` | `bungee-bootstrap` | Phase 3 | Proposed | BungeeCord/Waterfall single-artifact entry, `ServerConnectEvent` flow |
| `rtp-proxy-ADR-009` | `generic-sql-binding` | Phase 4 | Proposed (superseded by ADR-011 sketch) | Portable polling-based fallback binding (MySQL/MariaDB/H2/SQLite-dev) |
| `rtp-proxy-ADR-010` | `security-hardening` | Phase 4 | Accepted (2026-05-18) | HMAC envelope, `schemaVersion` negotiation, kill switch (heartbeat `boolean killSwitch` field amendment) |
| `rtp-proxy-ADR-011` | `sql-network-state-binding` | Phase 2b/2e | Accepted (2026-05-18) | DB-as-bus default for Phase 2e; folds ADR-007 + ADR-009 sketches; Redis (ADR-005) becomes opt-in latency upsell |
| `rtp-proxy-ADR-012` | `proxy-role-participant-default` | Phase 2b | Proposed | Participant-vs-router toggle; participant default; implicit-via-wiring (no new config knob) |
| `rtp-proxy-ADR-013` | `proxy-accessor-registration` | Phase 2b | Proposed | `RTPProxyAccessor` + `RtpProxy.proxyAccessor` slot (mirrors `RTP.serverAccessor`); replaces ADR-002's `Class.forName` probe |

Additional ADRs may be inserted at the tail of the series (`-ADR-014`, `-015`, …) as needs arise. **Do not** renumber existing ADRs.

## References

- Umbrella: [`docs/adr/ADR-036`](../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md)
- Plan: [`docs/dev/MULTI_SERVER_PLAN.md`](../../../docs/dev/MULTI_SERVER_PLAN.md)
- Requirements: [`rtp-proxy/REQUIREMENTS.md`](../../REQUIREMENTS.md), [`rtp-proxy-common/REQUIREMENTS.md`](../../rtp-proxy-common/REQUIREMENTS.md), [`rtp-proxy-velocity/REQUIREMENTS.md`](../../rtp-proxy-velocity/REQUIREMENTS.md), [`rtp-proxy-bungee/REQUIREMENTS.md`](../../rtp-proxy-bungee/REQUIREMENTS.md)
- Network REQs: [`docs/dev/REQUIREMENTS.md §1.6`](../../../docs/dev/REQUIREMENTS.md) (`REQ-RTP-NET-001…014`)
