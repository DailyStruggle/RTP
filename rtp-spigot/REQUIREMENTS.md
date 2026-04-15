# RTP Spigot Adapter Requirements

This document outlines the requirements for the `rtp-spigot` module. This adapter serves standard Bukkit/Spigot server implementations where advanced asynchronous features or region-based multithreading are unavailable.

## 1. Functional Requirements

### 1.1 Synchronous Fallback
- **REQ-SPIGOT-F-001 — Chunk Loading:** The adapter must implement standard, synchronous chunk loading utilizing the Bukkit API.
- **REQ-SPIGOT-F-002 — Bounded Execution:** Synchronous tasks must be strictly rate-limited and bounded to prevent freezing the main server thread during location generation or validation.

### 1.2 Event Handling
- **REQ-SPIGOT-F-003 — Platform Events:** The module must capture Bukkit-specific events (e.g., player movement, world loading/unloading) and accurately route them to `rtp-core` handlers without leaking platform-specific classes into the core module.

## 2. Strict Architectural Requirements

### 2.1 Ephemeral Memory Retention (Plugin Chunk Tickets)
- **REQ-SPIGOT-ARCH-001 — Ticket Over Retention:** The adapter must strictly utilize `world.addPluginChunkTicket(cx, cz, plugin)` instead of the legacy `Chunk.setForceLoaded(true)` or `Chunk.keep()` methods.
- **REQ-SPIGOT-ARCH-002 — Leak Prevention:** By using plugin-owned tickets, the system ensures that if the plugin is disabled or the server restarts, memory for these chunks is immediately reclaimed by the JVM rather than being permanently held by the world's internal force-loaded map.

### 2.2 Time-Bounded Execution (Main-Thread Stewardship)
- **REQ-SPIGOT-ARCH-003 — Time-Slicing Enforcement:** All background operations executed on the main server thread (e.g., location validation, pre-generation queue replenishment) must utilize the `TimeBoundTaskPipe`.
- **REQ-SPIGOT-ARCH-004 — Latency Protection:** Task pipelines must be bounded by wall-clock time (nanoseconds) rather than task counts, ensuring that even under high load, the plugin yields execution to the server tick before causing TPS degradation.

### 2.3 Ticket Lifecycle Cleansing (Automatic Release)
- **REQ-SPIGOT-ARCH-005 — Lifecycle Pairing:** Every chunk ticket allocation MUST be paired with an explicit release (`world.removePluginChunkTicket`).
- **REQ-SPIGOT-ARCH-006 — Forced Reclamation:** If a teleportation request is dropped, cancelled, or the associated player disconnects, the system must immediately purge all associated chunk tickets. This must be handled via the core `MemoryTracker` and the adapter's cleanup implementation.
