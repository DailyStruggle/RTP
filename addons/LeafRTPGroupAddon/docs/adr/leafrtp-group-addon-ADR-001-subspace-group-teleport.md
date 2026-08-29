# leafrtp-group-addon-ADR-001 - Multi-Entity Subspace Teleportation via Parent Region Memory Capture and Two-Stage (Chunk Pre-Filter + Block Bin) Candidate Selection

**Status:** Accepted
**Date:** 2026-08-27
**Supersedes:** `leafrtp-party-addon-ADR-001` (Party Teleport via Shared Prepared Destination)

## Context

Multi-player teleportation (co-op party teleports, 1v1 PvP duels, squad vs squad skirmishes, and pursuit/bounty drops) is often requested by server operators.

Competitor plugins (e.g. JustRTP, BetterRTP) build bespoke, fragmented execution paths for each of these features. Because their internal cache is merely a flat queue of global coordinates, any request requiring local spatial proximity (`nearplayer`, `party`, `pvp duel`, `nearclaim`) bypasses their cache and falls back to live, synchronous brute-force chunk loading loops on the main thread, introducing severe MSPT spikes and thread-safety risks on Folia.

LeafRTP models world safety via structured spatial data structures (`MemoryShape`, run-length / prefix-sum bad-location bitsets, and off-tick Anvil/Linear NBT probes). Rather than creating fragmented search engines, all multi-player teleportation can be unified into a single geometric and spatial primitive: **a relative Subspace Shape that captures memory from the parent Region**.

## Decision

Implement `LeafRTPGroupAddon` as a platform-neutral `RTPAddon` powered by a unified **`SubspaceShape`** engine:

### 1. The Subspace Shape & the Units Contract
1. **Anchor Resolution:** The operation resolves a primary anchor coordinate $(X_0, Z_0)$ and its owning `Region`. The anchor is drawn from the region's pre-warmed L1/L2 queue (or supplied by a live target entity/claim boundary).
2. **Unit reality.** The parent `MemoryShape` stores validity at **chunk** granularity (one 1D spiral index per 16x16 chunk; see `addBadChunk` / `chunkToLocations`). Player placement, separation, and elevation are in **blocks**. Counting not-known-bad chunk bits is therefore **not** a count of standable player slots - it only says which chunks are worth examining. The two units must not be conflated.
3. **Two-stage selection:**
   * **Stage 1 - chunk pre-filter (chunk units).** The anchor block coordinate is reduced to a chunk coordinate ($X_0 \gg 4$). Chunks in the bounded $N{\times}N$ footprint (`subspaceChunkRadius`) that are known bad in the inherited `MemoryShape` are discarded. This is a *necessary, not sufficient* screen: an unmarked chunk is "not known bad" (unexplored), never "verified good".
   * **Stage 2 - block bin (block units).** Block columns inside the surviving chunks are sampled and screened by the shared **`CandidateValidator`** (`rtp-core`), obtained via `Region.candidateValidator()`. This is *not* a group-specific safety re-implementation: it resolves a real standable $Y$ via the region's `VerticalAdjustor` and applies the same block-clearance verdict as the standard `/rtp` queue path through the shared `SafetyScan` helper (extracted from `QueueTask.runSafetyScan`, so there is a single definition of "safe column" - S-001, no drift). The count of validated block candidates is the **true** slot count. The sampling stride is **derived internally from `minSeparation`** (with slight oversampling) rather than exposed as a separate `blockStep` knob: a stride distinct from the enforced separation only duplicates the spacing constraint (a stride $\ge$ separation makes the separation check dead; a stride $<$ separation wastes screening), so `minSeparation` is the single spacing knob. The `SubspaceShape.BlockValidator` interface is retained only as a deterministic **test seam**. Because core forbids blocking on futures (`RTPArchitectureTest`), the validator is non-blocking (reads only resident chunks, fails closed otherwise) and the inherently-async claim / global-verifier check (S-003, ADR-026) is applied by the caller as a separate non-blocking stage on each selected slot, exactly as `QueueTask` runs it after its safety verdict.

### 2. Capacity Invariant: Fail-Closed Denial
* Capacity is measured against **block-validated** slots (Stage 2 bin size), never against chunk bits. If the bin holds fewer validated, sufficiently separated candidates than the required participant count $N$ - or cannot satisfy minimum separation $d_{min}$ / elevation tolerance $\Delta Y_{max}$ (blocks) - the location **shall be denied fail-closed**.
* Denied locations emit structured telemetry (`INSUFFICIENT_SAFE_SLOTS`, S-004 audited) and return the anchor, rather than bypassing safety checks (S-001) or dropping players.
* Because the footprint is a small fixed $N{\times}N$ chunk group (e.g. 3x3 = 48x48 blocks = ~2304 columns), Stage 2 screening is bounded and cheap: dozens of standable slots are typically found for any realistic $N$ without unbounded search.

### 3. Declarative Profile Presets
A single execution engine handles all gameplay modes via declarative configuration presets. Footprint is expressed in chunks (`subspaceChunkRadius`) and spacing in blocks (`minSeparation`, `elevationTolerance`); the Stage 2 sampling stride is derived from `minSeparation`:
* **`party` (Co-op Cluster):** tight 3x3 chunk footprint, small `minSeparation`, matching surface elevation.
* **`duel` (1v1 PvP Opposing Poles):** wider footprint so two opponents can be spaced far apart in blocks, elevation tolerance tight.
* **`skirmish` (Team vs Team):** wide footprint hosting two separated team clusters.
* **`pursuit` (Perimeter Ring):** single participant on a ring around a live target anchor.

## Alternatives Considered

| Alternative | Why Rejected |
| :--- | :--- |
| **Search per player and reconcile** | Multiplies chunk loads and search cost by $N$; duplicates search code and causes race conditions. |
| **Stack all players on one coordinate (`SAME`)** | Causes suffocation, collision glitches, and immediate friendly-fire chaos. |
| **Bespoke modules per game mode (Party vs Duel vs Arena)** | Creates maintenance fragmentation for identical spatial math; declarative profiles over a single subspace engine achieve full parity with zero bloat. |

## Consequences

* **Positive:**
  * Zero new live chunk loads: Stage 1 uses pre-cached chunk memory; Stage 2 screens a bounded, anchor-local bin off-tick.
  * Honest units: capacity denial is measured in block-validated slots, not chunk bits, so it never over- or under-estimates available positions.
  * One unified code path to test, benchmark, and maintain across all multi-entity game modes; Stage 2 reuses the L3 bin-screening primitive rather than a new validation engine.
* **Negative / Trade-offs:**
  * Heavily obstructed terrain (e.g., deep mountain ravines, dense oceans) may trigger capacity denials more frequently in strict duel profiles, requiring fallback to the next anchor.

## References

* [ADR-001](../../../../docs/adr/ADR-001-archimedean-spiral-1d-mapping.md) - Archimedean spiral 1D mapping.
* [ADR-016](../../../../docs/adr/ADR-016-anvil-subsystem.md) - Anvil off-tick prefilter subsystem.
* [ADR-028](../../../../docs/adr/ADR-028-l3-backlog-cache.md) - L3 backlog cache; the bin-screening primitive Stage 2 reuses at subspace scope.
* [ADR-057](../../../../docs/adr/ADR-057-platform-agnostic-addon-spi.md) - Platform-agnostic addon SPI.
* [`REQUIREMENTS.md`](../../REQUIREMENTS.md) - Subproject requirements.
