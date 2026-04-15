# RTP Folia Adapter Requirements

This document outlines the strict requirements for the `rtp-folia` module. Folia's region-based multithreading necessitates a fundamentally different concurrency model compared to standard Spigot or Paper environments.

## 1. Functional Requirements

### 1.1 Region-Based Multithreading Compliance
- **REQ-FOLIA-F-001 — Thread Isolation:** The adapter must ensure all teleportations, chunk loads, and entity manipulations execute exclusively on the correct regional thread associated with the target location.
- **REQ-FOLIA-F-002 — Task Scheduling:** The module must utilize Folia's `RegionScheduler`, `GlobalRegionScheduler`, and `EntityScheduler` to dispatch synchronous tasks. The standard Bukkit `BukkitScheduler` must not be used for regional operations.

### 1.2 Data Integrity and Safety
- **REQ-FOLIA-F-003 — State Mutation:** Concurrent state mutations must be strictly managed to guarantee data integrity across disparate region threads.
- **REQ-FOLIA-F-004 — Cross-Region Operations:** Teleportation between different Folia regions (e.g., cross-world or long-distance) must be safely pipelined to prevent thread violations, state corruption, or race conditions.

## 2. Strict Architectural Requirements

### 2.1 Thread Ownership Yielding (Zero-Tick Delay Optimization)
- **REQ-FOLIA-ARCH-001 — Mandatory Ownership Checks:** All regional task dispatchers must perform a pre-emptive `Bukkit.isOwnedByCurrentRegion(world, x, z)` check.
- **REQ-FOLIA-ARCH-002 — Immediate Execution:** If the current thread already owns the target region, the task **MUST** be executed immediately (synchronously) to bypass the `RegionScheduler`'s 1-tick scheduling overhead.
- **REQ-FOLIA-ARCH-003 — Bounced Execution:** Only when ownership is not held should the task be submitted to `Bukkit.getRegionScheduler().run(...)`. This ensures O(0) execution latency for local regional operations.

### 2.2 Strict Execution Cap Enforcement (Deterministic Region Ticks)
- **REQ-FOLIA-ARCH-004 — Prohibited Time Checks:** Implementations within regional threads are strictly prohibited from using `System.currentTimeMillis()` or `System.nanoTime()` for task-duration-based yielding. Region-based multithreading makes system-time-based slicing non-deterministic.
- **REQ-FOLIA-ARCH-005 — Mandatory Pipe Usage:** All iterative background operations (e.g., `RegionQueue` replenishment) must utilize the `CountBoundTaskPipe`.
- **REQ-FOLIA-ARCH-006 — Count-Based Slicing:** Task execution must be bounded by a fixed instruction count (number of tasks per tick) rather than wall-clock time, ensuring that RTP never contributes to "tick-skip" or "tick-extension" on any individual Folia region thread.

### 2.3 Economy Context Isolation (ThreadAccessException Prevention)
- **REQ-FOLIA-ARCH-007 — Regional Economy Prohibition:** Standard Vault/Economy transactions (withdrawals, deposits, balance checks) are strictly prohibited from executing on Folia Region threads.
- **REQ-FOLIA-ARCH-008 — Mandatory Context Delegation:** All economy interactions must be explicitly delegated to either the `GlobalRegionScheduler` or the `AsyncScheduler`.
- **REQ-FOLIA-ARCH-009 — Async State Pipelining:** Results from economy transactions (e.g., success/failure of a withdrawal) must be piped back to the originating region via a scheduled task if further regional state mutation is required. Direct access to `Vault` from a region thread must be treated as a critical architectural failure.

### 2.4 Native Asynchronous Chunk Operations
- **REQ-FOLIA-ARCH-010 — Folia Async APIs:** The Folia implementation must exclusively rely on Folia's native asynchronous chunk load APIs. It must rigorously manage the native chunk ticket lifecycle, allocating tickets for validation and explicitly destroying them when no longer actively queued.
