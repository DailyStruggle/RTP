# RTP System Architecture and High-Reliability Design

**Current Plugin Version:** `@version@`

> **Note:** In the context of this architecture, **RTP** holds a dual meaning: **R**andom **T**ele**P**ort and **R**eal-**T**ime **P**rocess. This reflects the core design philosophy that the plugin's codebase should be structured and analyzed with the rigor of highly reliable server software.

## System Overview and Operational Guarantees
The system is engineered to provide strictly bounded, high-performance random teleportation by decoupling location generation from user execution. Unlike traditional plugins that execute unbounded location searches synchronously (which introduces unpredictable latency and server instability), RTP guarantees O(1) constant-time response for end-users via an asynchronous, strictly managed queue system.

## Core Architectural Components

### 1. Asynchronous Queue-Based Pre-Generation
The core reliability mechanism of RTP is its `RegionQueueManager`. The system maintains a rigorous pipeline of pre-calculated, verified teleport locations.
- **Constant-Time Execution**: End-user teleport requests are fulfilled instantly from the pre-verified queue, ensuring zero blocking on the main server thread.
- **Bounded Computation Overhead**: The system asynchronously replenishes the queue within strict computational bounds, preventing server CPU spikes.
- **State Isolation**: Both global and isolated per-user queues are maintained to prevent resource starvation and handle concurrent high-frequency requests reliably.

#### 1.1 Cache Tier Model (L1 / L2 / L3)

The general-purpose location pool inside `RegionQueueManager` is layered into tiers, each with a distinct cost / readiness profile. Tiers drain top-down on `/rtp` and are refilled bottom-up by `Region.execute()`.

| Tier | Field | State of entries | Chunk I/O on use | Configuration cap | Persisted to DB |
|---|---|---|---|---|---|
| **L1 — hot / kept** | `keptLocations` (`LockFreeLocationBuffer`) | Fully verified; chunks currently held with `keep(true)` (plugin chunk ticket) | None — chunks already loaded | `activeChunkCap` | Yes |
| **L2 — cold / unkept** | `unkeptLocations` (`LockFreeLocationBuffer`) | Fully verified through the teleport pipeline; chunk reservations released | One async chunk re-load on promotion to L1 | `cacheCap` | Yes |
| **L3 — backlog / binned** *(implemented, [ADR-028](../adr/ADR-028-l3-backlog-cache.md))* | `backlogLocations` (`BacklogLocationBuffer`, nullable) | **Unverified** spiral picks with a tri-state per-entry `Validity` (`UNVERIFIED` / `VALIDATED` / `INVALIDATED`); head-blocking FIFO | None on L3 itself; verification is region-prefilter only (Anvil `.mca` / Linear `.linear`, [ADR-016](../adr/ADR-016-anvil-subsystem.md), [ADR-077](../adr/ADR-077-multi-format-region-support.md)) | `backlogCacheCap` (default `1000`; lite default `0` ⇒ disabled) | No |

Refill / promotion flow:

```
shape pick ─▶ L3 backlog ─(region-verified, in-order)─▶ L2 unkept ─▶ L1 kept ─▶ /rtp
                     ▲ refill (pure math, no chunk I/O)
                     │ verify: one bin (32×32 chunks = one .mca/.linear) per Region.execute() pulse
```

L3 design invariants ([ADR-028](../adr/ADR-028-l3-backlog-cache.md)):

- **Order preservation.** Entries are inserted in spiral-selection order and never reordered. The promotion contract to L2 is *head-only, contiguous-verified*: an unverified entry at L3 head blocks all later entries from advancing, even if those later entries have already been anvil-verified. This preserves the spatial-distribution semantics established by the Archimedean spiral mapping ([ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md)).
- **One bin per pulse.** Each `Region.execute()` pulse picks the bin (region file = 32 × 32 chunks; `.linear` or `.mca` per [ADR-077](../adr/ADR-077-multi-format-region-support.md)) containing the *oldest* unverified L3 entry, runs the region pre-filter for every L3 entry whose chunk lies inside that bin, and marks each entry's `verified` flag. This bounds per-pulse work (count-bound on Folia per [ADR-015](../adr/ADR-015-stale-chunk-guard-countbound-pipes.md)) and amortizes the single region-file read across all in-bin candidates.
- **Anvil/Linear-only verification.** L3 verification is a cheap *rejection filter* — prefilter passes still face the full pipeline (chunk load + vert + biome + safety) at L2 → L1 promotion. Prefilter rejects are dropped without chunk I/O, DB row, or chunk reservation. On platforms / worlds where the region pre-filter is unavailable (e.g. unflushed region files, custom generators, Fabric pre-stabilization), L3 entries fall through as "verified by default" so L3 never stalls a freshly generated world.
- **No DB persistence.** Unverified entries are not written through `installDatabaseCallbacks`; spiral re-selection after restart is cheap and avoids a "tentative row" schema delta.
- **Default-off in lite.** The `rtp-lite` assembly ([ADR-024](../adr/ADR-024-rtp-lite-assembly-variant.md)) ships with the key omitted; runtime default `0` keeps the trimmed memory profile. The full assembly defaults to `1000`, sized well above `cacheCap` so binning yields meaningful amortization.

Two further per-region buffers exist outside this tier model and are documented for completeness (they are *not* general-purpose tiers):

- **Login reserve** — `loginLocations` (nullable, default-world only), [ADR-023](../adr/ADR-023-login-reserve-cache.md). Filled by `LoginCacheTask` on its own event-driven loop (startup burst + `PlayerQuitEvent`); consumed at join time before the regular `/rtp` path.
- **Per-player queue / fast cache** — `perPlayerLocationQueue` + `playerQueue` + `fastLocations`. The fairness primitive for queued players and the per-player prefilled future for already-online clients.

### 2. Concurrency and Platform-Specific Thread Safety
RTP employs platform-specific adapters to ensure strict thread safety and optimal concurrent execution across disparate server environments:
- **`rtp-bukkit`**: The Bukkit API exposes only `Consumer`-based async chunk overloads (the `CompletableFuture`-returning `World#getChunkAtAsync(int,int)` is a Paper addition). On pure Spigot the adapter reflectively probes for the Paper overload and, when absent, falls back to a synchronous `world.getChunkAt(...)` scheduled onto the primary thread via `Bukkit.getScheduler().runTask(...)`. The caller's `CompletableFuture` is unblocked, but the chunk I/O itself is not off-tick. Off-tick *safety evaluation* on pure Spigot is achieved only for candidates covered by the Anvil read-only pre-filter (ADR-016).
- **`rtp-paper`**: Leverages asynchronous chunk loading APIs to prevent main-thread deadlocks.
- **`rtp-folia`**: Implements strictly isolated region-based multithreading, guaranteeing thread safety and data integrity during concurrent state mutations.

### 3. Deterministic Spatial Algorithms (Strict Execution Time Bounds)
RTP replaces unbounded random geometric selections with deterministic algorithms to ensure predictable maximum execution times:
- **Archimedean Spirals**: A custom 1D sequence mapping (using Archimedean spirals for CIRCLE and SQUARE shapes) is employed rather than naive 2D rerolling or image compression algorithms. The rationale for this choice — including alternatives considered and the original mathematical proof authored by the plugin's sole developer — is recorded in [ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md). This directly mitigates two critical failure modes in traditional implementations:
  - **Distribution Skew (Clustering)**: Traditional algorithms inherently skew towards outer bounds. The 1D mapping ensures mathematically verified, perfectly uniform spatial distribution.
  - **Non-Deterministic Execution (Unbounded Rerolling)**: Naive algorithms "reroll" upon hitting invalid sectors (e.g., oceans, protected regions), which causes execution time to decay exponentially as invalid space increases. By mapping 2D space to a 1D sequence, RTP preemptively subtracts "bad sectors" from the pool. This guarantees deterministic, stable computation time, completely eliminating the risk of infinite loops or execution timeouts.
- **Stateful Memory Tracking**: The `MemoryShape` caching system persistently tracks invalid regions, ensuring the system never wastes cycles validating known bad states, maintaining algorithmic efficiency and deterministic behavior over time.
- **Mathematical Distributions**: Spatial algorithms support Flat, Normal, and Exponential distributions to provide configurable, yet highly deterministic spatial selection.

### 4. Persistent State and Fault Tolerance
- **Database Integration**: All identified safe and unsafe spatial data is persistently committed to a reliable datastore (e.g., SQLite, MySQL, H2).
- **Restart Resilience**: This continuous state persistence ensures the system's spatial knowledge survives server restarts, providing fault tolerance and preventing redundant validation overhead upon system recovery.

### 5. Isolated Regional Contexts
- RTP strictly isolates state between world regions, ensuring parameters such as spatial geometry, permissions, and queue capacities do not cause cross-region interference or race conditions.

### 6. Active Task and Resource Tracking (Memory and Chunk Management)
To guarantee system stability and prevent server exhaustion, RTP employs a rigorous `MemoryTracker` that acts as an active garbage collection and monitoring system:
- **Task Pipeline Monitoring**: The tracker enforces strict lifespan boundaries on asynchronous execution pipelines (e.g., `TeleportPipelineTask`). If a task exceeds its expected execution window, it is flagged as a memory leak and forcefully pushed into a safe cleanup phase to prevent infinite looping and thread stalling.
- **Chunk Allocation Management**: The system rigorously tracks active chunk tickets, distinguishing between pre-generation queue allocations and active player teleportations. This ensures that background location generation never overwhelms server RAM.
- **Orphaned Allocation Recovery**: By actively monitoring queued locations and teleport data, the system instantly identifies abandoned allocations (e.g., when a player disconnects mid-teleport) and immediately releases associated chunks back to the server.

## Extensibility and API Boundaries
The `rtp-api` module provides a strict, defined interface for external integrations:
- **Safe Extensibility**: Developers can inject custom `Shape` algorithms or claim-plugin validations (e.g., GriefPrevention) via the API without modifying or compromising the reliability guarantees of the `rtp-core` module.

## Platform Adapter Design Details

### rtp-core Implementation Notes
- **Lock-Free Configuration Storage**: Configuration data is stored in `EnumMap`/`ConcurrentHashMap` structures that guarantee O(1) read access. Public methods return immutable views or primitive values accessed via `FactoryValue.getData()`.
- **Pipeline Phases**: The teleportation pipeline is divided into four phases — Setup, Load, Teleport, and Cleanup — each managed by `TeleportPipelineTask`. Every phase wraps exceptions to prevent corruption of subsequent phases.
- **Resource Release on Exit**: On every exit path (normal, exception, cancellation), `TeleportPipelineTask.runCleanup()` releases chunk reservations, untracks teleport data, and decrements in-flight calculation counters.
- **Pulse-Driven Maintenance**: Background maintenance is driven by `MemoryTracker.runDiagnostics()` and task-pipe processing via `RTPTaskPipe`. All pulsed tasks accept an available-time budget (in milliseconds) and cease execution once that budget is exhausted.
- **Lifespan Enforcement**: `MemoryTracker` assigns a maximum lifespan to every `TeleportPipelineTask`. The diagnostic pulse forcefully invalidates and cleans up any task exceeding its lifespan.
- **Concurrency Abstractions**: All blocking and async operations are dispatched via `SyncTaskProcessing` and `AsyncTaskProcessing` abstractions implementing `RTPRunnable`, keeping `rtp-core` free of direct platform imports.

### rtp-api Implementation Notes
- **Registration Guards**: Custom `Shape` / vertical-adjustor registration is an implementation-extension capability served at the `rtp-core` tier via the typed `RTP.addShape(Shape)` / `RTP.addVerticalAdjustor(VerticalAdjustor)` entry points (two-tier API model, [ADR-051](../adr/ADR-051-two-tier-api-extension-model.md)). The thin `rtp-api` contract no longer exposes untyped `addShape(Object)` shims. Contract-surface delegates that remain on `RTPAPI` (e.g. `hooks()`) still throw `IllegalStateException` if accessed before core is loaded (REQ-RTP-S-006).
- **Location Generator Interface**: `ILocationGenerator` is the core abstraction through which `GenerationContext` flows; platform accessors implement this to wire into the teleport pipeline.
- **Lock-Free Config Caching**: API-level configuration caches use `EnumMap` and `ConcurrentHashMap` to ensure high-throughput reads without synchronization bottlenecks.
- **Exception Isolation**: Addon-supplied `Shape` or validation `Predicate`/`Function` implementations are called inside `try-finally` blocks so that unhandled addon exceptions do not escape into the core pipeline.

### rtp-bukkit Implementation Notes
- **Plugin Chunk Tickets**: Chunk retention is implemented via `world.addPluginChunkTicket(cx, cz, plugin)` and released via `world.removePluginChunkTicket(cx, cz, plugin)`. This is preferred over `Chunk.setForceLoaded(true)`, which permanently marks chunks in the world's force-loaded map and is not reclaimed on plugin disable.
- **Time-Bounded Slicing**: Main-thread operations use `TimeBoundTaskPipe` to yield after a wall-clock time budget (e.g., 2 ms per tick) is exhausted, preventing TPS degradation.
- **Forced Reclamation**: Player disconnect listeners call `reservation.close()` on all in-flight `TeleportPipelineTask` instances associated with that player.
- **Chunk Load Path (pure Spigot)**: `BukkitRTPWorld.loadChunkFuture` reflectively probes for Paper's `World#getChunkAtAsync(int,int) → CompletableFuture<Chunk>`. On pure Spigot that overload is absent (Bukkit ships only the `Consumer`-based variants), so the fallback schedules a synchronous `world.getChunkAt(...)` onto the primary thread via `Bukkit.getScheduler().runTask(plugin, ...)`. This does **not** make chunk I/O itself off-tick on pure Spigot — the caller's future is simply unblocked while the load runs on tick. No blanket "fully async chunk loading on all platforms" claim is made for the `rtp-bukkit` adapter.
- **Anvil Read-Only Pre-Filter (pure Spigot)**: To recover off-tick safety evaluation for the common case, `BukkitRTPWorld.getChunkAt` routes unloaded, vanilla-generator candidates through `AnvilPrefilter.probeDetailed(...)` (ADR-016). `REJECT` short-circuits without any chunk load; `ACCEPT` publishes an `AnvilChunkView` into a bounded `anvilCache` and the `BukkitRTPChunk` hybrid dispatches `isAir` / `isSafe` / `getSkyLight` / `getSurfaceHeight` from the off-tick view. `UNKNOWN` verdicts, already-loaded chunks, worlds with a custom `ChunkGenerator`, and I/O errors fall through to the main-thread-bounced `loadChunkFuture` path; prefilter coverage therefore defines effective off-tick coverage on pure Spigot.

### rtp-paper Implementation Notes
- **Async Chunk Loading**: `BukkitRTPWorld.getChunkAtAsync(cx, cz)` calls Paper's `world.getChunkAtAsync(cx, cz)`, returning a `CompletableFuture<Chunk>` that resolves on a Paper worker thread without touching the main thread.
- **Callback-Only Pattern**: No `.join()` or synchronous waits are used on futures; all continuation logic is attached via `.thenApply` / `.whenComplete` callbacks, eliminating deadlock risk.
- **Ticket Lifecycle**: Same plugin-owned chunk ticket approach as `rtp-bukkit`; Paper's async API handles ticket acquisition internally, and explicit release is still performed via `reservation.close()` in all exit paths.

### rtp-folia Implementation Notes
- **Thread Ownership Checks**: Before scheduling a regional task, the adapter calls `Bukkit.isOwnedByCurrentRegion(entity/location)`. If ownership is confirmed, the task runs immediately; otherwise it is submitted via `RegionScheduler.run(plugin, location, task)`.
- **Count-Bound Execution**: Within regional threads, iterative background operations use `CountBoundTaskPipe` with a fixed instruction count (not wall-clock time), because Folia's per-region ticks make time-based slicing non-deterministic.
- **Economy Delegation**: Vault economy calls (`withdraw`, `deposit`, `getBalance`) are never invoked on a Folia region thread. They are dispatched to `GlobalRegionScheduler` or `AsyncScheduler`, and results are piped back to the originating region via a follow-up scheduled task.
- **Chunk Ticket Lifecycle**: `getChunkAtAsync` allocates a Folia chunk ticket for validation, and `reservation.close()` explicitly removes the ticket when the chunk is no longer needed, preventing permanent force-loading.