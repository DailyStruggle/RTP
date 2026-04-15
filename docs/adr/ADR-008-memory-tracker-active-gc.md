# ADR-008 — MemoryTracker as Active Task GC with WeakReference Deallocation

**Status:** Accepted
**Date:** 2026-04-15

## Context

RTP's pre-generation pipeline loads chunks asynchronously to validate candidate teleport locations. Minecraft's chunk-loading API requires the plugin to hold a **chunk ticket** (a force-load reference) for each chunk it is actively processing. These tickets must be explicitly released when the chunk is no longer needed; the JVM garbage collector has no knowledge of server-side chunk tickets and cannot release them automatically.

During early development, a failure mode was observed in production: chunks were being marked as force-loaded by the plugin and were **occasionally left force-loaded indefinitely**. Over long server uptimes this caused growing memory bloat, requiring periodic server restarts. Restarts did not permanently fix the problem because the plugin re-loaded the same chunks from its saved state on startup, immediately reproducing the leak.

Root causes included:
- A player disconnecting mid-teleport, leaving an in-flight `TeleportPipelineTask` with an open chunk ticket and no owner to trigger cleanup.
- Tasks that exceeded their expected execution window due to server lag, entering a stalled state that no existing code path cleaned up.

Standard Java mechanisms (`try-finally`, `WeakReference`) are insufficient alone:
- `try-finally` only covers the happy path within a single call frame; it cannot detect a task that stalls indefinitely or an object that is abandoned across thread boundaries.
- `WeakReference` allows the JVM to reclaim the Java object, but does not release the associated server-side chunk ticket, which is held by the Minecraft engine — not the JVM heap.

## Decision

Implement a **dual-layer resource management strategy**:

1. **`MemoryTracker` (active watchdog):** A central registry that tracks all live `TeleportPipelineTask` instances and their associated chunk tickets. A periodic task scans for entries that have exceeded their expected execution window, flags them as leaks, and forcibly pushes them through a safe cleanup phase (releasing chunk tickets and removing queue entries). This detects and recovers from stalled tasks, abandoned mid-teleport pipelines, and other leak patterns that `try-finally` cannot catch.

2. **`WeakReference` deallocation (passive GC bridge):** Task and location objects that are no longer strongly referenced (e.g., because the owning player has disconnected and no live code path holds a reference) are tracked via `WeakReference`. When the JVM reclaims the object, the associated chunk ticket is explicitly released. This handles the clean disconnection case without requiring the active watchdog to poll for it.

Together, the active tracker catches leaks that persist despite live references (stalled tasks), while weak references handle clean deallocation of truly abandoned objects.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| `try-finally` only | Only covers synchronous, single-frame cleanup. Cannot detect a task stalled across tick boundaries or abandoned due to a player disconnect during async execution. |
| `WeakReference` only | JVM reclaims the Java object but does not release the server-side chunk ticket held by the Minecraft engine. Force-loaded chunks remain loaded even after the Java wrapper is GC'd. |
| No explicit tracking (rely on plugin disable cleanup) | Chunk tickets accumulate during uptime and are only released on server restart. Does not solve the re-load-on-startup reproduction of the leak. |
| OS/JVM heap monitoring | Monitors JVM memory, not Minecraft chunk ticket state. Chunk tickets are server-engine resources invisible to standard JVM profilers. |

## Consequences

- **Positive:**
  - Force-loaded chunks are guaranteed to be released within a bounded time window even if the owning task stalls or its player disconnects.
  - The active tracker provides observable diagnostics: leaked tasks can be logged and counted, enabling developers to identify and fix new leak patterns as they are discovered.
  - Servers that previously required periodic restarts due to chunk bloat can run indefinitely without intervention.

- **Negative / Trade-offs:**
  - The `MemoryTracker` registry adds a small per-task overhead (registration, deregistration, periodic scan).
  - The watchdog timeout must be tuned: too short and legitimate slow tasks are incorrectly flagged; too long and leaks persist for longer before cleanup.
  - The dual-layer approach adds code complexity; contributors must register new task types with `MemoryTracker` and use the appropriate reference pattern, or new leak vectors can be introduced.

## References

- Implementing classes: `MemoryTracker`, `TeleportPipelineTask` (`rtp-core`); chunk ticket management in platform adapters (`rtp-spigot`, `rtp-paper`, `rtp-folia`)
- Design reference: [`DESIGN.md` §6 — Active Task and Resource Tracking](../DESIGN.md)
- Related: [ADR-006](ADR-006-async-queue-pre-generation.md) (pre-generation pipeline), [ADR-007](ADR-007-per-user-isolated-queues.md) (orphaned per-user queue cleanup on disconnect)
- Requirements: `REQ-RTP-NF-003` (memory stability), `REQ-RTP-NF-004` (chunk allocation management)
