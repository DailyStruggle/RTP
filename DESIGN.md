# RTP System Architecture and Safety-Critical Design

## System Overview and Operational Guarantees
RTP (Random Teleport) is engineered to provide strictly bounded, high-performance random teleportation by decoupling location generation from user execution. Unlike traditional plugins that execute unbounded location searches synchronously (which introduces unpredictable latency and server instability), RTP guarantees O(1) constant-time response for end-users via an asynchronous, strictly managed queue system.

## Core Architectural Components

### 1. Asynchronous Queue-Based Pre-Generation
The core fail-safe mechanism of RTP is its `RegionQueueManager`. The system maintains a rigorous pipeline of pre-calculated, verified teleport locations.
- **Constant-Time Execution**: End-user teleport requests are fulfilled instantly from the pre-verified queue, ensuring zero blocking on the main server thread.
- **Bounded Computation Overhead**: The system asynchronously replenishes the queue within strict computational bounds, preventing server CPU spikes.
- **State Isolation**: Both global and isolated per-user queues are maintained to prevent resource starvation and handle concurrent high-frequency requests reliably.

### 2. Concurrency and Platform-Specific Thread Safety
RTP employs platform-specific adapters to ensure strict thread safety and optimal concurrent execution across disparate server environments:
- **`rtp-spigot`**: Standard synchronous fallback with bounded chunk loading.
- **`rtp-paper`**: Leverages asynchronous chunk loading APIs to prevent main-thread deadlocks.
- **`rtp-folia`**: Implements strictly isolated region-based multithreading, guaranteeing thread safety and data integrity during concurrent state mutations.

### 3. Deterministic Spatial Algorithms (Worst-Case Execution Time Guarantees)
RTP replaces unbounded random geometric selections with deterministic algorithms to ensure predictable Worst-Case Execution Time (WCET):
- **Archimedean Spirals**: A custom 1D sequence mapping (using Archimedean spirals for CIRCLE and SQUARE shapes) is employed rather than naive 2D rerolling or image compression algorithms. This directly mitigates two critical failure modes in traditional implementations:
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
- **Safe Extensibility**: Developers can inject custom `Shape` algorithms or claim-plugin validations (e.g., GriefPrevention) via the API without modifying or compromising the safety guarantees of the `rtp-core` module.