# ADR-008 — MemoryTracker as Active Task GC with WeakReference Deallocation

**Status:** Accepted
**Date:** 2026-04-15

## Context

RTP's pre-generation pipeline loads chunks asynchronously to validate candidate teleport locations. Minecraft's chunk-loading API requires the plugin to hold a **chunk ticket** (a force-load reference) for each chunk it is actively processing. These tickets shall be explicitly released when the chunk is no longer needed; the JVM garbage collector has no knowledge of server-side chunk tickets and cannot release them automatically.

Without an explicit tracking mechanism, chunks marked as force-loaded by the plugin can be **left force-loaded indefinitely**. Over long server uptimes this causes growing memory bloat. Restarts do not permanently fix the problem if the plugin re-loads the same chunks from its saved state on startup.

Root causes for leaked tickets include:
- A player disconnecting mid-teleport, leaving an in-flight task with an open chunk ticket and no owner to trigger cleanup.
- Tasks exceeding their expected execution window due to server lag, entering a stalled state that no standard code path cleans up.

Standard Java mechanisms (`try-finally`, `WeakReference`) are insufficient alone:
- `try-finally` only covers the happy path within a single call frame; it cannot detect a task that stalls indefinitely or an object that is abandoned across thread boundaries.
- `WeakReference` allows the JVM to reclaim the Java object, but does not release the associated server-side chunk ticket held by the Minecraft engine.

## Decision

The resource management architecture shall feature a **dual-layer strategy**:

1. **Active watchdog:** A central registry shall track all live teleport tasks and their associated chunk tickets. A periodic diagnostic pulse shall scan for entries that have exceeded their expected execution window, flag them as leaks, and forcibly push them through a safe cleanup phase (releasing chunk tickets and removing queue entries). This detects and recovers from stalled tasks, abandoned mid-teleport pipelines, and other leak patterns that `try-finally` cannot catch.

2. **Passive GC bridge:** Task and location objects that are no longer strongly referenced (e.g., because the owning player has disconnected and no live code path holds a reference) shall be tracked via weak references. When the JVM reclaims the object, the associated chunk ticket shall be explicitly released. This handles the clean disconnection case without requiring the active watchdog to poll for it.

Together, the active tracker catches leaks that persist despite live references (stalled tasks), while weak references handle clean deallocation of truly abandoned objects.

For implementation and code-level details (e.g., `MemoryTracker`, `TeleportPipelineTask`), see [DESIGN.md §6 — Active Task and Resource Tracking](../dev/DESIGN.md#6-active-task-and-resource-tracking-memory-and-chunk-management).

## Consequences

- **Positive:**
  - Force-loaded chunks are guaranteed to be released within a bounded time window even if the owning task stalls or its player disconnects.
  - The active tracker provides observable diagnostics: leaked tasks can be logged and counted, enabling developers to identify and fix new leak patterns as they are discovered.
  - Long-running servers remain stable without restart intervention caused by chunk bloat.

- **Negative / Trade-offs:**
  - The `MemoryTracker` registry adds a small per-task overhead (registration, deregistration, periodic scan).
  - The watchdog timeout shall be tuned: too short and legitimate slow tasks are incorrectly flagged; too long and leaks persist for longer before cleanup.
  - The dual-layer approach adds code complexity; contributors shall register new task types with `MemoryTracker` and use the appropriate reference pattern, or new leak vectors can be introduced.

## References

- Implementing classes: `MemoryTracker`, `TeleportPipelineTask` (`rtp-core`); chunk ticket management in platform adapters (`rtp-spigot`, `rtp-paper`, `rtp-folia`)
- Design reference: [`DESIGN.md` §6 — Active Task and Resource Tracking](../DESIGN.md)
- Related: [ADR-006](ADR-006-async-queue-pre-generation.md) (pre-generation pipeline), [ADR-007](ADR-007-per-user-isolated-queues.md) (orphaned per-user queue cleanup on disconnect)
- Requirements: `REQ-RTP-NF-003` (memory stability), `REQ-RTP-NF-004` (chunk allocation management)
