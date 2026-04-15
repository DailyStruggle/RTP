# RTP Paper Adapter Requirements

This document outlines the requirements for the `rtp-paper` module. This adapter leverages the enhanced APIs available exclusively on Paper servers to maximize performance.

## 1. Functional Requirements

### 1.1 Asynchronous Chunk Operations
- **REQ-PAPER-F-001 — Non-Blocking Generation:** The adapter must utilize Paper's asynchronous chunk loading APIs (`getChunkAtAsync`) for location validation and generation.
- **REQ-PAPER-F-002 — Deadlock Prevention:** The implementation must guarantee that asynchronous requests are handled strictly via callbacks or futures, completely eliminating synchronous waits that could deadlock the main server thread.

### 1.2 Performance Optimization
- **REQ-PAPER-F-003 — API Utilization:** The adapter should prioritize Paper-specific optimizations over Bukkit fallbacks to ensure the highest possible teleportation throughput with minimal TPS impact.

## 2. Strict Architectural Requirements

### 2.1 Ephemeral Memory Retention (Plugin Chunk Tickets)
- **REQ-PAPER-ARCH-001 — Ticket Over Retention:** The adapter must strictly utilize `world.addPluginChunkTicket(cx, cz, plugin)` instead of the legacy `Chunk.setForceLoaded(true)` or `Chunk.keep()` methods.
- **REQ-PAPER-ARCH-002 — Leak Prevention:** By using plugin-owned tickets, the system ensures that if the plugin is disabled or the server restarts, memory for these chunks is immediately reclaimed by the JVM rather than being permanently held by the world's internal force-loaded map.

### 2.2 Time-Bounded Execution (Main-Thread Stewardship)
- **REQ-PAPER-ARCH-003 — Time-Slicing Enforcement:** All background operations executed on the main server thread (e.g., location validation, pre-generation queue replenishment) must utilize the `TimeBoundTaskPipe`.
- **REQ-PAPER-ARCH-004 — Latency Protection:** Task pipelines must be bounded by wall-clock time (nanoseconds) rather than task counts, ensuring that even under high load, the plugin yields execution to the server tick before causing TPS degradation.

### 2.3 Ticket Lifecycle Cleansing (Automatic Release)
- **REQ-PAPER-ARCH-005 — Lifecycle Pairing:** Every chunk ticket allocation MUST be paired with an explicit release (`world.removePluginChunkTicket`).
- **REQ-PAPER-ARCH-006 — Forced Reclamation:** If a teleportation request is dropped, cancelled, or the associated player disconnects, the system must immediately purge all associated chunk tickets. This must be handled via the core `MemoryTracker` and the adapter's cleanup implementation.
