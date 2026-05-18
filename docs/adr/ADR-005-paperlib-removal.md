# ADR-005 — Removal of PaperLib in Favour of Native Paper APIs

**Status:** Accepted
**Date:** 2026-04-15

## Context

[PaperLib](https://github.com/PaperMC/PaperLib) is a cross-platform compatibility shim (async chunk loading, etc.) intended for plugins that run on both Paper and plain Spigot. RTP maintains separate adapter modules (`rtp-bukkit`, `rtp-paper`) for each platform, so the Paper adapter has no Spigot code path to shim and a cross-platform compatibility library adds no value there.

## Decision

`rtp-paper` shall not depend on PaperLib. It calls Paper's native async chunk loading APIs directly.

Because `rtp-paper` is loaded only on Paper servers, a compatibility shim is unnecessary. Direct native calls remove a runtime dependency, eliminate an indirection layer, and keep the adapter on the current, non-deprecated Paper API surface.

## Consequences

- **Positive:**
  - Removes a runtime dependency; operators no longer need PaperLib on their server when running the Paper adapter.
  - The adapter uses Paper's current, actively maintained async chunk loading API directly, without a deprecated shim in the call path.
  - Reduces indirection and simplifies the call stack for async chunk operations.

- **Negative / Trade-offs:**
  - The `rtp-paper` adapter is now strictly Paper-only at compile time; it cannot be loaded on a plain Spigot server (this was already the intended deployment model).
  - Any future Paper API changes shall be handled directly in `rtp-paper` rather than being absorbed by a compatibility library.

## References

- PaperLib repository: https://github.com/PaperMC/PaperLib
- Implementing module: `rtp-paper` (all version submodules)
- Changelog entry: [`CHANGELOG.md` — 2.0.18](../../CHANGELOG.md)
- Upgrade notes for operators: [`MIGRATION.md`](../MIGRATION.md)
- Requirements: `REQ-RTP-S-001` (platform compatibility), `REQ-PAPER-F-001` (async chunk loading)
