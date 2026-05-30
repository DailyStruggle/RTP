# RTP API Requirements
This document details the functional and non-functional requirements specific to the `rtp-api` module. As the primary integration point for external addons, stability and decoupling are paramount.

For design and implementation details that satisfy these requirements, see [`docs/dev/DESIGN.md`](../docs/dev/DESIGN.md).

## 1. Functional Requirements

### 1.1 Extensibility Interfaces
- **REQ-API-F-001 — Custom Shapes:** The system shall allow external plugins to register custom spatial geometries (`Shape`). Registration of a custom shape requires deriving from the concrete shape base classes and shall be served at the implementation-extension tier (`rtp-core`) rather than through the thin `rtp-api` contract surface, consistent with REQ-API-NF-002.
- **REQ-API-F-002 — Vertical Adjustors:** The system shall allow external plugins to register custom Y-axis adjustment logic (e.g., surface, cave, or custom logic). Registration shall be served at the implementation-extension tier (`rtp-core`) for the same reason as REQ-API-F-001.
- **REQ-API-F-003 — Validation Hooks:** The API shall provide hooks for external claim and protection plugins (e.g., GriefPrevention, WorldGuard) to validate location safety asynchronously.
- **REQ-API-F-006 — Bare-Command Root Action:** The API shall provide a single-binding hook through which an external addon may override the behaviour of a bare random-teleport command (no arguments) without affecting any subcommand. When no override is bound the system shall perform the classic random teleport, and an override that fails shall not suppress the classic behaviour.

### 1.2 Data Structures and Models
- **REQ-API-F-004 — Agnostic Representation:** Shared models (e.g., `RTPLocation`, `RTPWorld`, `RTPPlayer`) shall be platform-agnostic to ensure compatibility across Bukkit, Paper, Folia, and potential future server implementations.

### 1.3 Unified Command Framework
- **REQ-API-F-005 — Unified Command-Tree Contract:** The command framework shall be the single source of truth for the command tree across all supported platforms. Platform adapters shall not duplicate command-tree structure.

## 2. Non-Functional Requirements

### 2.1 Backward Compatibility
- **REQ-API-NF-001 — Semantic Versioning:** The API shall adhere to semantic versioning to prevent breaking changes for addon developers.
- **REQ-API-NF-002 — Implementation Decoupling:** API interfaces shall not expose internal implementation specifics of `rtp-core`, ensuring that core architecture can evolve without requiring API redesigns.

## 3. Strict Architectural Requirements

### 3.1 Immutable Interfaces & Thread Safety
- **REQ-API-ARCH-001 — State Integrity:** All exposed API interfaces shall be thread-safe and support both monolithic and region-threaded server environments. Shared models shall be immutable or use lock-free reads to eliminate race conditions.

### 3.2 Asynchronous Execution Contracts
- **REQ-API-ARCH-002 — Non-Blocking Guarantees:** External plugins implementing custom shapes or location validation shall not trigger synchronous chunk loads or perform heavy I/O operations that could stall the tick thread.

### 3.3 Fail-Safe Abstractions
- **REQ-API-ARCH-003 — Robust Exception Handling:** Unhandled exceptions from external addon code shall not corrupt the core execution pipeline.

### 3.4 Lock-Free Configuration Caching
- **REQ-API-ARCH-004 — High-Throughput Reads:** API methods reading plugin configuration definitions shall guarantee high-throughput reads without introducing synchronization bottlenecks.

### 3.5 Command Adapter Isolation
- **REQ-API-ARCH-005 — Platform-Neutral Command Adapter Boundary:** Platform-specific command-framework dependencies shall be scoped such that they do not become runtime dependencies of the public API surface, and shall not be re-exported through the command framework's public types.
