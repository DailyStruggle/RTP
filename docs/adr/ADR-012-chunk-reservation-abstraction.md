# ADR-012 — `ChunkReservation` as an Internal Chunk Ticket Abstraction

**Status:** Accepted  
**Date:** 2026-04-15

---

## Context

Chunks shall be force-loaded during location pre-generation and active teleportation, and released promptly to avoid memory bloat. Managing chunk tickets directly at each call site — requesting a ticket, storing a reference, and releasing it inline — creates two recurring failure modes:

1. **Chunk leaks** — small errors in tracking (missed release on an exception path, a race between queue drain and task cancellation) cause chunks to remain force-loaded indefinitely, producing the memory-bloat problem documented in ADR-008.
2. **Platform variance** — different server software versions have different performance characteristics and requirements for chunk data access. A lookup table mapping chunk coordinates to reservation state is needed for efficient access, but ad-hoc call sites have no consistent place to maintain it.

Additionally, different server software versions change how chunk tickets are issued or queried. The concept of "holding a chunk loaded" shall be isolated behind a single boundary that can be adapted per platform without modifying call sites throughout the codebase.

---

## Decision

A dedicated `ChunkReservation` abstraction in `rtp-api` shall be utilized. `AutoCloseable` shall be implemented to encapsulate the full lifecycle of a chunk ticket: acquisition, lookup-table registration, and guaranteed release on `close()`.

`ChunkReservation` shall serve as an **internal API**: it is exposed for addons to inspect whether a chunk is reserved, but is **not intended to be constructed or implemented by addon developers**. Construction shall remain the exclusive responsibility of the platform adapter layer.

---

## Rationale

### Eliminates per-call-site ticket management errors
By making `ChunkReservation` `AutoCloseable`, it can be used in try-with-resources blocks, ensuring release even on exception paths without requiring every call site to replicate finally-block cleanup logic.

### Centralises the lookup table
The lookup table mapping chunk coordinates to active reservations is owned by `ChunkReservation` rather than scattered across queue management code. This gives a single source of truth for "is this chunk currently reserved?" queries, which is needed both for efficiency and for the leak-detection logic in `MemoryTracker` (see ADR-008).

### Isolates platform variance
Server software differences in chunk ticket APIs or data-access performance are absorbed inside `ChunkReservation`'s platform-specific implementations, leaving all call sites in `rtp-core` platform-agnostic.

---

## Alternatives Considered

| Alternative | Reason Rejected |
|-------------|----------------|
| Ad-hoc ticket management at each call site | Proven to produce leaks; no central lookup; brittle across platforms |
| WeakReference-only management | Handles clean disconnects but not force-loaded chunk leaks (see ADR-008); insufficient alone |
| Full addon-constructable API class | Addons have no need to create reservations; exposing construction would widen the supported API surface unnecessarily |

---

## Consequences

- **Positive:** Try-with-resources usage guarantees chunk release on all code paths, eliminating the class of leak described in ADR-008.
- **Positive:** Centralised lookup table enables efficient reservation queries and supports `MemoryTracker` leak detection.
- **Positive:** Platform-specific ticket behaviour is encapsulated; call sites in `rtp-core` are platform-agnostic.
- **Negative:** Addon developers can read reservation state but cannot construct reservations — any new use-case requiring reservation creation shall be routed through `rtp-core` or a future `rtp-api` factory method.
- **Negative:** `ChunkReservation` is in `rtp-api` (for visibility) but is not a fully open API class; this requires clear documentation to avoid misuse.
