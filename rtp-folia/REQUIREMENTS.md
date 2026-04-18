# RTP Folia Adapter Requirements
This document outlines the strict requirements for the `rtp-folia` module. Folia's region-based multithreading necessitates a fundamentally different concurrency model compared to standard Spigot or Paper environments.

For design and implementation details that satisfy these requirements, see [`docs/dev/DESIGN.md`](../docs/dev/DESIGN.md).

## 1. Functional Requirements

### 1.1 Region-Based Multithreading Compliance
- **REQ-FOLIA-F-001 — Thread Isolation:** The adapter shall ensure all teleportations, chunk loads, and entity manipulations execute on the correct regional thread associated with the target location.
- **REQ-FOLIA-F-002 — Task Scheduling:** The module shall dispatch synchronous tasks through Folia's region-aware schedulers rather than the standard Bukkit scheduler.

### 1.2 Data Integrity and Safety
- **REQ-FOLIA-F-003 — State Mutation:** Concurrent state mutations shall be managed to guarantee data integrity across disparate region threads.
- **REQ-FOLIA-F-004 — Cross-Region Operations:** Teleportation between different Folia regions shall be safely pipelined to prevent thread violations, state corruption, or race conditions.

## 2. Strict Architectural Requirements

### 2.1 Thread Ownership Yielding (Zero-Tick Delay Optimization)
- **REQ-FOLIA-ARCH-001 — Mandatory Ownership Checks:** Regional task dispatchers shall verify thread ownership before scheduling.
- **REQ-FOLIA-ARCH-002 — Immediate Execution:** If the current thread already owns the target region, the task shall be executed immediately to avoid unnecessary scheduling overhead.
- **REQ-FOLIA-ARCH-003 — Bounced Execution:** Only when thread ownership is not held shall the task be submitted to the regional scheduler.

### 2.2 Strict Execution Cap Enforcement (Deterministic Region Ticks)
- **REQ-FOLIA-ARCH-004 — No Time-Based Yielding in Regional Threads:** Implementations within regional threads shall not use wall-clock time for task-duration-based yielding, as region-based multithreading makes time-based slicing non-deterministic.
- **REQ-FOLIA-ARCH-005 — Count-Bound Execution:** All iterative background operations within regional threads shall be bounded by a fixed instruction count rather than elapsed time.
- **REQ-FOLIA-ARCH-006 — Count-Based Slicing:** Task execution shall be bounded per tick to ensure RTP never contributes to tick-skip or tick-extension on any individual Folia region thread.

### 2.3 Economy Context Isolation (ThreadAccessException Prevention)
- **REQ-FOLIA-ARCH-007 — Regional Economy Prohibition:** Economy transactions (withdrawals, deposits, balance checks) shall not execute on Folia region threads.
- **REQ-FOLIA-ARCH-008 — Mandatory Context Delegation:** All economy interactions shall be delegated to a globally or asynchronously scheduled context.
- **REQ-FOLIA-ARCH-009 — Async State Pipelining:** Results from economy transactions shall be piped back to the originating region via a scheduled task if further regional state mutation is required.

### 2.4 Native Asynchronous Chunk Operations
- **REQ-FOLIA-ARCH-010 — Async Chunk Loading:** Chunk loading in the Folia implementation shall be asynchronous, managing the chunk ticket lifecycle explicitly — allocating tickets for validation and releasing them when no longer needed.
