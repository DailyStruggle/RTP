# ADR-097 — Per-Action Pre-Validated Caches and Bounded Subspace Placement Retries

**Status:** Accepted
**Date:** 2026-09-23
**Extends:** [ADR-078](ADR-078-composable-cache-pipeline-stages.md) (Composable Cache Pipeline Stages), [ADR-093](ADR-093-declarative-scripted-actions-via-core-confinement-and-subspace-placement.md) (Scripted Actions), [ADR-095](ADR-095-subspace-anchor-providers-and-near-teleport-primitives.md) (Subspace Anchor Providers)
**Related:** [ADR-079](ADR-079-cause-based-ttl-and-staged-expiration.md) (Cause-Based TTL and Spatial Memory Learning), S-001..S-005 (Safety Invariants)

---

## Context

In RTP's subspace placement architecture (ADR-093, ADR-095), a placement request selects an anchor $(X_0, Z_0)$, derives a lattice of safe candidate columns, and performs async external verification (`GlobalRegionVerifiers`, claim checks under S-003).

Two operational challenges arise when placements encounter real-world conditions:
1. **Encapsulating Claims & Single-Try Aborts:** When a target subspace is encapsulated by an un-scanned claim (e.g. WorldGuard, GriefPrevention, Towny), the external verifier rejects the slot. In ADR-095, the rejected chunk is captured and learned in `MemoryShape` as `FailTypes.safetyExternal`. However, without a retry mechanism, the single attempt fails closed immediately (`INSUFFICIENT_SAFE_SLOTS`), failing the action or group teleport even though adjacent terrain outside the claim is completely safe.
2. **On-Demand Placement Latency:** Actions with stationary or background-discoverable anchors (e.g. `scatter`, `location`, `nearclaim` perimeters) perform full anchor resolution, candidate lattice screening, and chunk reservations at invocation time. While fast ($\sim 15\text{–}30\text{ µs}$ off-tick), zero-latency dispatch from pre-warmed chunks (matching standard `/rtp` L1/hot queue behavior) is desirable for high-frequency actions without creating monolithic, unbounded queues.

---

## Decision

### 1. Bounded Asynchronous Placement Retries (`GroupPlacementDispatcher`)

`GroupProfileSpec` and `GroupPlacementDispatcher` support bounded placement retries (`retries`, clamped $\ge 1$, default $1$ for raw group profiles, default $3$ for scripted actions):
- When `allocate(...)` fails with `INSUFFICIENT_SAFE_SLOTS` (whether due to Stage-1 lattice culling or live verifier rejection):
  1. Any rejected chunks are dynamically recorded into the parent region's `MemoryShape` tagged as `FailTypes.safetyExternal` (ADR-079).
  2. If the current attempt index is less than `retries`, a fresh attempt is asynchronously chained off-tick via `SubspaceAnchorResolver.resolveAnchor(...)`.
  3. Because the prior failure shifted the boundary conditions in `MemoryShape`, subsequent lattice selections immediately prune the newly discovered claim chunks in $O(1)$ (or select a different perimeter edge for `ClaimHazardAnchorSource`).
  4. If any attempt succeeds, it proceeds to ticket handover and teleport dispatch. If all attempts are exhausted, it fails closed cleanly (`INSUFFICIENT_SAFE_SLOTS`).

### 2. Per-Action Pre-Validated Cache Sinks (`ActionHotSink`)

Under ADR-078's composable cache stage architecture:
- Action definitions can specify a small, bounded pre-validated cache (`placement.cacheSize`, default $0$, typically $1\text{–}3$ for stationary actions like `scatter`, `location`, `nearclaim`).
- Pre-validated entries hold prepared `GroupPlacementResult` placements with live `ChunkReservation`s, populated asynchronously in the background or during idle pulses.
- Dynamic entity actions (`nearplayer` targeting a moving player) bypass pre-validated caching and run on-demand with bounded retries.

### 3. Mandatory Pre-Flight Revalidation Prior to Placement (S-001, S-003)

Because world state can change while a candidate sits in cache (e.g., players build claims, place hazardous blocks, or ignite fires):
- Upon cache hit, before any participant is dispatched, the engine performs a non-blocking **Pre-Flight Recheck**:
  1. **Resident Block Recheck:** Evaluates column standability against the already-resident chunk via `region.candidateValidator()`.
  2. **External Claim Recheck (S-003):** Re-evaluates `GlobalRegionVerifiers.checkGlobalRegionVerifiers(dest)`.
- If revalidation fails:
  - The entry is immediately disposed and its chunk reservation closed (S-002).
  - The failed chunk is tagged into `MemoryShape` as `FailTypes.safetyExternal`.
  - The engine pops the next cached entry or falls back to live bounded-retry placement.

---

## Safety & Invariant Guarantees

- **S-001 & S-003 (No Unsafe Destinations / No Claim Incursions):** Pre-flight revalidation guarantees that cached entries are verified against the live world state at dispatch time.
- **S-002 (No Leaked Chunk Tickets):** All retry discard paths, cache evictions, and failed revalidations immediately release their reservations.
- **S-004 (Fail-Closed Contract):** If all retries are exhausted or all cached entries are invalid, the action fails closed with an explicit error reason.
- **S-005 (Zero Main-Thread Chunk I/O):** All retries, anchor re-draws, and pre-flight rechecks execute asynchronously off-tick.
