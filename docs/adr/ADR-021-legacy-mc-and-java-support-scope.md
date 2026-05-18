# ADR-021 — Legacy Minecraft and Java Support Are Out of Scope

**Status:** Accepted
**Date:** 2026-04-22

## Context

RTP currently targets Java 21+ (REQ-RTP-SYS-001) and the Minecraft versions enumerated by the versioned platform adapter submodules (ADR-010): `v1_20_R1`, `v1_21_R1`, `v26_1_R1` across Spigot, Paper, and Folia, plus the in-progress Fabric frontier (`rtp-fabric`, see `MULTI_PLATFORM_PLAN.md`). Requests periodically surface to "adapt for older Minecraft versions on older Java versions" — typically Java 8 / MC 1.8–1.12, or Java 17 / MC 1.16–1.19.

Several load-bearing architectural decisions assume modern APIs that are unavailable, or available only via heavy shims, on legacy servers:

- **Folia threading model** (`DESIGN.md`, AGENTS.md) — `Bukkit.isOwnedByCurrentRegion`, Entity/Region/Global schedulers, and `CompletableFuture`-chained async pipelines (`thenCompose`/`thenAccept`) exist only on modern Paper/Folia.
- **S-005 (no sync chunk I/O on the main thread)** — enforced via the platform adapter's async chunk abstraction. Pre-1.13/1.14 Bukkit has no `getChunkAtAsync`; satisfying S-005 there requires a PaperLib-style shim (already removed per ADR-005) or a bespoke worker pool.
- **Chunk ticket lifecycle** (ADR-012, S-002) — `ChunkReservation` assumes the modern chunk-ticket API. Legacy servers would need a wholly different force-load strategy and parallel regression coverage.
- **Anvil pre-filter** (ADR-016) and **stale-chunk guard** (ADR-015) — both written against modern chunk-section and ticket semantics.
- **Java 21 language/library surface** — records, pattern `switch`, sealed types, virtual threads, and 21-only collection factories are used freely throughout `rtp-core` and `rtp-api`. Downgrading would permanently tax every future change.

The current active frontier is Fabric, which is still unstable (S-005 violation in `FabricWorld.getChunkAt`; null stub in `FabricServerAccessor.getLocationGenerator`; unresolved Loom dependency). Opening a second unstable frontier — legacy MC — would compound risk without a committed owner.

## Decision

RTP shall remain a **Java 21+, modern-Minecraft** plugin. Legacy Minecraft versions (anything older than the oldest `v*_R*` adapter already shipped) and legacy Java versions (anything older than Java 21) are **explicitly out of scope**.

Users on older server versions shall be directed to the last RTP release that supported their server, rather than maintained against an ongoing code path.

This decision shall be revisited only when **all** of the following trigger conditions are met:

1. The Fabric frontier (`rtp-fabric`) is stable — S-005, S-002, and S-004 regression guards green, `getLocationGenerator` returning a real implementation, Loom build green.
2. A named maintainer volunteers to own a legacy adapter end-to-end (build, NMS mappings, CI toolchain, S-00x proofs, and ongoing maintenance).
3. Sustained, documented user demand for a specific legacy MC band (issue volume, not single requests).

If revisited, the minimum plan is:

- **Phase 1** — Static audit of `rtp-core` / `rtp-api` for Java 21-only APIs; backport cost estimate gates further work.
- **Phase 2** — Prototype a single legacy adapter (e.g., Paper 1.19 on Java 17) with async chunk loading; prove S-005, S-002, and S-004 regression tests (`ReqRtpS005ChunkLoadingTest`, `ReqRtpS004NullChunkAttributionTest`) still pass.
- **Phase 3** — Only if Phase 2 is clean, consider older bands (e.g., Java 8 / pre-1.13) on their own merits.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Add `rtp-bukkit-legacy` adapter(s) for MC 1.16–1.19 on Java 17 now | Doubles the test matrix and shim surface while Fabric is still unstable; no committed owner. |
| Multi-release JAR with Java 8 core + Java 21 adapters | `rtp-core` uses Java 21 language features pervasively; the backport would permanently tax every core change and risk S-005/S-002 regressions in the legacy path. |
| Reintroduce PaperLib to cover async chunk loading on older Paper | Explicitly reversed by ADR-005; would reopen a decision the project has already paid to close. |
| Drop legacy support silently (no ADR) | Leaves the question open, invites repeated requests, and provides no revisit criteria. |

## Consequences

- **Positive:** Keeps a single modern code path; preserves the Folia async contract and S-005/S-002 proofs; frees maintainer bandwidth for the Fabric frontier; provides a clear, citable answer to recurring "support 1.12 / Java 8?" requests.
- **Negative / Trade-offs:** Server operators on legacy MC cannot use current RTP releases and must fall back to historical builds; contributors with legacy-only environments are blocked from contributing to adapters.

## References

- REQ-RTP-SYS-001 (Java 21+), `docs/dev/REQUIREMENTS.md §0` (scope)
- ADR-005 — PaperLib removal
- ADR-010 — Versioned platform adapter submodules
- ADR-012 — `ChunkReservation` chunk ticket abstraction
- ADR-015 — Stale-chunk guard for count-bound pipes
- ADR-016 — Anvil subsystem
- `docs/dev/MULTI_PLATFORM_PLAN.md` — Fabric frontier status
- `docs/dev/DESIGN.md` — Folia threading model
