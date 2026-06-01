# `rtp-proxy/` — Proxy Adapters (umbrella)

> **Status: scaffolding only.** No code, no `build.gradle`, no `settings.gradle` inclusion yet. This umbrella exists so the directory shape and per-module `REQUIREMENTS.md` documents can land **before** ADR-036 ratifies them. Gated by Rule D-005 (see [`.junie/AGENTS.md`](../../.junie/AGENTS.md)).

This directory hosts RTP's proxy-side adapters, per [`docs/dev/MULTI_SERVER_PLAN.md`](../../docs/dev/MULTI_SERVER_PLAN.md). A "proxy" here means a server-list-rewriting Java proxy that sits in front of multiple Minecraft backends and routes RTP requests across them. Protocol translators (Geyser, ViaProxy) and library-based custom proxies (MCProtocolLib) are **out of scope** — see *Non-Goals (v1)* in the plan.

## In-scope adapters

| Subproject | Target | Phase | Status |
|---|---|---|---|
| [`rtp-proxy-common/`](rtp-proxy-common/) | Shared SPI; no proxy-vendor imports | 1 | Scaffolding |
| [`rtp-proxy-velocity/`](rtp-proxy-velocity/) | Velocity 3.3.x+ (primary) | 2 | Scaffolding |
| [`rtp-proxy-bungee/`](rtp-proxy-bungee/) | BungeeCord + Waterfall (secondary; one artifact) | 3 | Scaffolding |

Backend-side glue (`NetworkBridge`, `BackendStatePublisher`, `ReservationTokenTable`, network-state member of `AbstractSQLDatabaseAccessor`) does **not** live here — per the plan it lands in `rtp-core` as an optional, default-disabled subsystem.

## Distribution model

Single artifact, per REQ-RTP-NET-003. The proxy modules shade into the existing umbrella `rtp-plugin` assembly; the runtime detects which proxy is hosting it (`com.velocitypowered.api.proxy.ProxyServer` on classpath ⇒ Velocity; `net.md_5.bungee.api.ProxyServer` ⇒ BungeeCord/Waterfall; otherwise backend mode) and activates the right entry point. No separate "proxy build".

## Planned top-level files (not yet created)

| File | Purpose | Created when |
|---|---|---|
| `REQUIREMENTS.md` | Umbrella `REQ-RTP-PROXY-NNN` requirements covering all adapters | **Next step** |
| `build.gradle` | Aggregator (no sources of its own) | Phase 1 (after ADR-036) |
| `docs/adr/` | Subproject ADRs (`rtp-proxy-ADR-NNN-…`) | First ADR (Phase 1 SPI shape) |

## Optional further subdirectories (notes only)

The following are **not** created now — they are reserved structural slots so contributors don't invent parallel locations later:

- `rtp-proxy/docs/adr/` — per-subproject ADRs using `rtp-proxy-ADR-NNN-<slug>.md` numbering that restarts at `001` inside this directory (per `AGENTS.md > Self-Updating Protocol`).
- `rtp-proxy/docs/dev/` — *not* planned. Proxy-axis dev docs stay in `docs/dev/MULTI_SERVER_PLAN.md`. Do not fork a parallel dev-docs tree here.
- `rtp-proxy/docs/admin/` — *not* planned. Admin docs live in [`docs/admin/proxies/`](../../docs/admin/proxies/) and stay there.
- `rtp-proxy/scripts/` — *not* planned. Curve-plot generation and devstack compose files live under top-level `scripts/` per the admin-docs INDEX.

## Subproject ADRs (forthcoming)

Per `AGENTS.md`, when an ADR is authored here it must also get a row in the *Subproject ADRs* table of [`docs/adr/README.md`](../../docs/adr/README.md). The global `docs/adr/` keeps its own independent sequence (ADR-036 — multi-server proxy support — is global, not per-subproject).

## Cross-references

- [`docs/dev/MULTI_SERVER_PLAN.md`](../../docs/dev/MULTI_SERVER_PLAN.md) — the design plan this directory implements.
- [`docs/dev/REQUIREMENTS.md` §1.6](../../docs/dev/REQUIREMENTS.md) — system-level `REQ-RTP-NET-NNN` requirements.
- [`docs/admin/proxies/INDEX.md`](../../docs/admin/proxies/INDEX.md) — operator-facing documentation stub.
- [`.junie/AGENTS.md`](../../.junie/AGENTS.md) — agent / contributor rules (D-005, architecture boundaries, self-updating protocol).
