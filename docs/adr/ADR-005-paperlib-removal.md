# ADR-005 — Removal of PaperLib in Favour of Native Paper APIs

**Status:** Accepted
**Date:** 2026-04-15

## Context

The `rtp-paper` adapter originally used [PaperLib](https://github.com/PaperMC/PaperLib) — a compatibility shim library published by the PaperMC team — to access Paper-specific features (primarily asynchronous chunk loading) while maintaining a fallback path for Spigot servers that did not have those APIs.

PaperLib was designed for a period when Paper and Spigot shared a largely common API surface, and plugin authors needed a safe way to call Paper-only methods without crashing on Spigot. Its `getChunkAtAsync` wrapper, for example, would call the native Paper async API if available, or fall back to a synchronous Bukkit call otherwise.

Since then, Paper has substantially differentiated its implementation from Spigot's and has deprecated many of the older compatibility APIs that PaperLib was bridging. The gap between Paper and Spigot has grown to the point where PaperLib's original purpose — providing a single codebase that runs on both — is less relevant. RTP already maintains separate adapter modules (`rtp-spigot`, `rtp-paper`) for each platform, so the cross-platform shim layer PaperLib provides is redundant.

## Decision

Remove the PaperLib dependency from `rtp-paper` and call Paper's native async chunk loading APIs directly.

Since `rtp-paper` is already a Paper-only module (it is never loaded on a Spigot server), there is no need for a compatibility shim. Calling Paper's native APIs directly removes an unnecessary dependency, eliminates the indirection layer, and ensures the adapter uses the current, non-deprecated API surface.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep PaperLib | PaperLib's bridging purpose is obsolete given RTP's per-platform adapter architecture. Retaining it adds a dependency with no benefit and ties the adapter to an API layer that Paper itself is moving away from. |
| Merge `rtp-paper` and `rtp-spigot` into a single adapter using PaperLib for compatibility | Reintroduces the coupling that the separate adapter modules were designed to avoid; prevents using Paper-only APIs that have no Spigot equivalent. |
| Use reflection to call Paper APIs without a compile-time dependency | Fragile, hard to maintain, and unnecessary given that `rtp-paper` already has a hard compile-time dependency on Paper. |

## Consequences

- **Positive:**
  - Removes a runtime dependency; operators no longer need PaperLib on their server when running the Paper adapter.
  - The adapter uses Paper's current, actively maintained async chunk loading API directly, without a deprecated shim in the call path.
  - Reduces indirection and simplifies the call stack for async chunk operations.

- **Negative / Trade-offs:**
  - The `rtp-paper` adapter is now strictly Paper-only at compile time; it cannot be loaded on a plain Spigot server (this was already the intended deployment model).
  - Any future Paper API changes must be handled directly in `rtp-paper` rather than being absorbed by a compatibility library.

## References

- PaperLib repository: https://github.com/PaperMC/PaperLib
- Implementing module: `rtp-paper` (all version submodules)
- Changelog entry: [`CHANGELOG.md` — 2.0.18](../../CHANGELOG.md)
- Upgrade notes for operators: [`MIGRATION.md`](../MIGRATION.md)
- Requirements: `REQ-RTP-S-001` (platform compatibility), `REQ-PAPER-F-001` (async chunk loading)
