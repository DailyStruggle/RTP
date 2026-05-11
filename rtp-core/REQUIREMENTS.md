# RTP Core Requirements
This document details the functional requirements specific to the `rtp-core` module. This module contains the platform-agnostic teleportation logic, memory tracking, and geometric algorithms.

For design and implementation details that satisfy these requirements, see [`docs/dev/DESIGN.md`](../docs/dev/DESIGN.md).

## 1. Functional Requirements

### 1.1 Queue Management and Teleport Execution
- **REQ-CORE-F-001 — Asynchronous Pre-Generation:** The system shall maintain queues of pre-calculated, verified teleport locations.
- **REQ-CORE-F-002 — Bounded Generation:** The system shall replenish queues within strictly bounded limits to prevent CPU and memory exhaustion.
- **REQ-CORE-F-009 — Backlog Verification Order:** Where a pre-verification staging buffer is provided upstream of the verified location queues, the buffer shall preserve insertion order of candidate locations, shall not surface a candidate as ready until its destination has been classified as valid, shall not stall on a subsequent candidate when an earlier candidate has been classified as invalid, and shall amortize verification work across periodic pulses such that no single pulse blocks the server beyond the configured pulse budget.

### 1.2 Mathematical Determinism
- **REQ-CORE-F-003 — Uniform Distribution:** Core algorithms shall guarantee uniform spatial distribution.
- **REQ-CORE-F-004 — Strict Execution Time Guarantees:** Algorithms shall be constrained to deterministic time bounds, preemptively subtracting invalid sectors instead of unbounded rerolling.

### 1.3 State Tracking and Persistence
- **REQ-CORE-F-005 — Spatial Memory:** The caching system shall persistently track invalid and biome locations to eliminate redundant calculations.
- **REQ-CORE-F-006 — Database Integration:** Validated locations and spatial memory shall be persistently stored to survive server restarts.

### 1.4 Active Garbage Collection
- **REQ-CORE-F-007 — Memory Tracking:** The system shall enforce strict execution lifespan boundaries on asynchronous tasks.
- **REQ-CORE-F-008 — Chunk Recovery:** Abandoned or orphaned chunk allocations shall be actively identified and immediately released back to the server.

## 2. Strict Architectural Requirements

### 2.1 Lock-Free Atomic Configuration Access
- **REQ-CORE-ARCH-001 — Lock-Free Configuration Storage:** Configuration data shall be stored in lock-free collections that guarantee O(1) read access without thread contention.
- **REQ-CORE-ARCH-002 — Direct Key Access:** Configuration values shall be accessed directly by key. Public methods shall return immutable views or primitive values, ensuring concurrent readers never block each other or the main server thread.

### 2.2 Pipeline Fault Encapsulation and Resource Release
- **REQ-CORE-ARCH-003 — Pipeline Exception Wrapping:** Every phase of the teleportation pipeline (Setup, Load, Teleport, Cleanup) shall be wrapped in structured fault-handling so that exceptions in one phase do not corrupt subsequent phases.
- **REQ-CORE-ARCH-004 — Resource Release on Exit:** On every exit path (normal, exception, cancellation), chunk reservations shall be released, teleport data shall be untracked, and in-flight calculation counters shall be decremented.

### 2.3 Deterministic Asynchronous State Pulsing
- **REQ-CORE-ARCH-005 — Pulse-Driven Maintenance:** Background maintenance (diagnostics, database flushing, task pipe processing) shall be driven by periodic timers rather than on-demand invocation.
- **REQ-CORE-ARCH-006 — Time-Bounded Pulse Tasks:** All pulsed tasks shall accept an available-time budget and cease execution once that budget is exhausted, ensuring deterministic overhead per tick.

### 2.4 Strict Lifespan Tracking for Volatile State
- **REQ-CORE-ARCH-007 — Resource Registration:** All objects that consume server resources (memory, chunks, threads) shall be registered with a central tracker upon instantiation.
- **REQ-CORE-ARCH-008 — Lifespan Enforcement:** A maximum lifespan shall be defined for every tracked object. Any object that exceeds its lifespan shall be forcefully invalidated and cleaned up by the diagnostic pulse.

### 2.5 Decoupled Platform-Agnostic Core Logic
- **REQ-CORE-ARCH-009 — Interface-Only Platform Access:** The `rtp-core` module shall interact with the server environment exclusively through platform-agnostic interfaces.
- **REQ-CORE-ARCH-010 — No Platform Imports:** Direct references to platform-specific classes shall be absent from `rtp-core`. All concurrency shall be handled via abstractions provided by the server adapter, ensuring that core logic remains platform-neutral and testable in isolation.

## 3. Non-Functional Requirements

### 3.1 Shutdown Persistence
- **REQ-CORE-NF-001 — Deterministic Shutdown Persistence:** Volatile spatial-memory state shall be flushed to the configured persistence backend on plugin shutdown before the shutdown flag is set, ensuring no state accumulated between the last periodic drain and shutdown is lost.
