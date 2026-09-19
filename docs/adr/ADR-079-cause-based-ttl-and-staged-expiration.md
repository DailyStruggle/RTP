# ADR-079 — Cause-Based TTL and Staged Expiration on Spatial Memory Segments

**Status:** Proposed
**Date:** 2026-09-03

## Context

RTP's spatial memory subsystem (`MemoryShape`, [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md)) maps 2D coordinate space onto a 1D Archimedean spiral and tracks invalid sectors as Run-Length Encoded (RLE) intervals in `badKeysCache`. [ADR-052](ADR-052-outcome-metrics-and-cause-tagged-bad-locations.md) added `badCauseCache` to tag each bad-location run with a `LocationGenerator.FailTypes` cause byte, using a "first-cause-wins" rule when adjacent runs coalesce.

Currently, all bad-location runs in `MemoryShape` are treated as monotonic and permanent: once an index is marked bad, it remains bad indefinitely unless the operator manually runs `/rtp scan reset`, reconfigures the shape bounds, or changes safety parameters.

In a live production environment, this permanence creates significant operational problems:
1. **Dynamic Rejection Causes:** While natural terrain properties (`biome`, `worldBorder`, `vert`, `safety`) are static or semi-permanent, external restrictions (`safetyExternal` via claim plugins like GriefPrevention, Towny, Lands, WorldGuard) and player placement dispersion (`uniquePlacement`) are dynamic.
2. **Permanent Land Lockout:** If a player claims a plot of land, RTP marks the candidate chunk bad under `safetyExternal`. If the player later unclaims or abandons the land, the area remains permanently blacklisted in spatial memory. Server operators are forced to discard entire regional caches (`/rtp scan reset`)—losing hours of expensive off-tick terrain scanning—just to refresh dynamic claims.
3. **Opaque External Verifier Contract:** The `RegionVerifierRegistry` API ([ADR-026](ADR-026-external-hook-api-surface.md)) currently accepts only untyped `Predicate<RTPCoords>` or async functions without an identifier or mutability contract. RTP cannot distinguish whether a verifier represents a permanent server spawn or a transient player claim.
4. **Re-generation Churn on Expiration:** A naive hard deletion of expired segments causes immediate tick spikes: if an expired segment is hit on the next selection pass and the claim is still active (which is typical for weeks-long claims), the server must re-execute chunk loads, vertical raycasts, and verifier invocations from scratch.

## Decision

1. **Volatility Tiers & Baseline Defaults:**
   Classify rejection causes into three persistence and expiration tiers:
   - **Static Tier (TTL = $\infty$):** Natural terrain and geometric limits (`biome`, `worldBorder`, `prefilterBiome`, `prefilterRange`). Persisted permanently to `.bin` cache files. Reset only on world configuration or seed changes.
   - **Dynamic Tier (TTL = Finite, e.g. 7–30 days):** Dynamic player and administrative state (`safetyExternal`, `uniquePlacement`, `safety`/`vert` long-tail decay). Tracked with an expiration epoch.
   - **Transient Tier (TTL = 0):** Ephemeral operational failures (`timeout`, `nullChunk`, `ungenerated`). Discarded immediately without entering persistent spatial memory.

2. **Selective Coalescing Rule (Refining ADR-052):**
   - Adjacent bad-location runs in `MemoryShape` shall **only** coalesce if both runs belong to the same volatility tier (Static vs. Dynamic).
   - A static run ($\text{TTL} = \infty$) shall **never** coalesce with a dynamic run ($\text{TTL} < \infty$). This prevents permanent natural terrain from expiring prematurely and prevents temporary player claims from becoming immortalized.
   - When two dynamic runs coalesce, the merged run inherits $\max(\text{TTL}_A, \text{TTL}_B)$ to prioritize CPU and chunk I/O stability over high-churn fragmentation, while retaining the first cause byte under ADR-052's first-cause-wins rule.
   - In `MemoryShape`, run expiration epochs are maintained in an aligned `int[] badExpiryEpochCache` (storing expiration time in coarse minutes since server epoch, where `<= 0` indicates infinite/static retention), matching the indexing of `badKeysCache` and `badCauseCache`.

3. **Staged Segment Deletion ("Probation / Side Space"):**
   Implement a two-phase lazy expiration lifecycle rather than hard instant deletion:
   - **Active Phase ($0 \le t < 1\times \text{TTL}$):** The segment resides in `badKeysCache` and is bypassed by candidate selection in $O(\log N)$ time.
   - **Probation Phase ($1\times \text{TTL} \le t < 2\times \text{TTL}$):**
     - During the off-tick `flushAndRebuild()` or periodic pulse, expired segments are omitted from the rebuilt `badKeysCache` and transferred to a bounded probation buffer (`ConcurrentHashMap<Long, ProbationRecord>` keyed by `chunkKey` or spiral index, capped at 8,192 entries with FIFO eviction on overflow).
     - Coordinates in this segment become selectable again by candidate generation.
     - **Re-Hit & Re-Rejected (Still Claimed):** When candidate verification in `PregenTask` fails and `shape.addBadLocation()` / `addBadChunk()` is called, if the key matches an entry in the probation buffer, the segment is **immediately restored** into `pendingBadLocations` / active spatial memory with a refreshed TTL, bypassing full terrain re-mapping.
     - **Re-Hit & Succeeded (Now Safe):** If candidate verification succeeds, the entry is dropped from the probation buffer; the sector is confirmed valid.
   - **Eviction Phase ($t \ge 2\times \text{TTL}$):** If no candidate touches the segment during the probation window, the record is silently pruned during the next rebuild pass.

4. **Backward-Compatible Verifier API & Pipeline Contract:**
   - Extend `RegionVerifierRegistry` in `rtp-api` with overloads accepting an identifier and default TTL:
     ```java
     void register(String name, Duration defaultTtl, Predicate<RTPCoords> verifier);
     void registerAsync(String name, Duration defaultTtl, Function<RTPCoords, CompletableFuture<Boolean>> verifier);
     ```
     Legacy methods (`register(Predicate<RTPCoords>)`) remain fully supported, defaulting to `name = "verifier"` and `defaultTtl = null` (or negative duration), representing $\infty$ (permanent) and guaranteeing zero breakage for existing or un-migrated addons.
   - Internal verifier evaluation in `GlobalRegionVerifiers` returns a structured result:
     ```java
     public record VerifierCheckResult(boolean passed, @Nullable String verifierName, @Nullable Duration ttl)
     ```
     allowing `PregenTask` to attribute rejections (`safetyExternal[<verifierName>]`) and pass the resolved duration into `MemoryShape.addBadChunk()`.

5. **Configuration Overrides (`advanced/ttl.yml`):**
   Provide an optional operator configuration under `advanced/ttl.yml` to define global cause baselines and allow per-verifier overrides (`external-verifiers.<name>`) without requiring addon updates.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Instant hard deletion of expired segments | Triggers heavy chunk loading and pipeline re-checks when claims are still valid. Staged probation allows $O(1)$ restoration of still-bad sectors. |
| Coalescing dynamic runs with $\min(\text{TTL})$ | Causes large, long-lived claim segments to expire rapidly when bordered by a short-lived claim, increasing scan churn. |
| Allowing static and dynamic runs to coalesce | Causes static ocean/mountain runs to inherit finite TTLs and re-verify periodically, or causes dynamic claims to become permanent. |
| Forcing addons to read RTP config files | Violates addon encapsulation; third-party addons should declare programmatic domain defaults and let RTP core handle configuration overrides. |
| Per-location 64-bit expiration timestamps | Destroys the memory compactness of the RLE interval structure; storing coarse epoch buckets per coalesced run preserves zero-heap efficiency. |

## Maintainability & Operational Factors

1. **Subsystem Cohesion & Domain Modeling:**
   - Decouples geometric coordinate mapping (`MemoryShape` Archimedean spiral bijection) from assumptions of static immutability.
   - Places volatility and lifecycle attribution at the integration boundary (`RegionVerifierRegistry`), where domain knowledge naturally resides.

2. **Loose Coupling & API Compatibility:**
   - Additive overloads with default methods prevent breaking binary and source compatibility for third-party or legacy claim addons (S-006, ADR-026).
   - Addon code remains decoupled from RTP configuration parsing: addons declare domain defaults in Java, while core manages administrative overrides via `advanced/ttl.yml`.

3. **Performance & Concurrency Guardrails:**
   - **Zero-Allocation Hot Path:** The selection path (`MemoryShape.rand()` and binary search on `badKeysCache`) remains lock-free and zero-allocation. All segment aging, probation transitions, and evictions are executed off-tick during periodic pulses or rebuild passes (`flushAndRebuild()`).
   - **Bounded RLE Growth:** Segmenting runs across volatility boundaries increases RLE run count by only 5–8% in typical workloads, preserving cache compactness.
   - **Probation Side-Space Sizing:** The quarantine buffer is bounded by active candidate selection volume and cleared lazily, preventing heap bloat.

4. **Observability & Diagnostics:**
   - Granular failure attribution (`safetyExternal[<VerifierName>]`) surfaces exact rejection bottlenecks in `/rtp stats`, verbose logs, and debugging tools, eliminating opaque failure buckets.
   - Visualizing active vs. probationary segments is supported through existing debug shape JSON exports.

5. **Persistence & Migration Safety:**
   - Restricting disk serialization (`.bin`) to the Static Tier ($\text{TTL} = \infty$) preserves full binary format compatibility (`BIN_VERSION 2`, ADR-052) with zero schema migrations required.

## Consequences

- **Positive:**
  - Abandoned land claims and expired unique placements naturally cycle back into circulation without requiring administrative `/rtp scan reset`.
  - Static world terrain remains permanently cached, preserving baseline performance.
  - The probation side space prevents tick spikes and re-generation overhead on recurring failures.
  - Granular verifier attribution enables detailed failure metrics per claim plugin in `/rtp stats`.
  - Existing addons remain 100% binary and behaviorally compatible.
- **Negative / Trade-offs:**
  - Segmenting RLE runs by volatility tier slightly increases total run count in `badKeysCache`.
  - Additional memory bookkeeping for the probationary side-space buffer (bounded by active selection traffic).

## References

- `docs/adr/ADR-001-archimedean-spiral-1d-mapping.md` (1D spiral indexing).
- `docs/adr/ADR-026-external-hook-api-surface.md` (`RegionVerifierRegistry`).
- `docs/adr/ADR-052-outcome-metrics-and-cause-tagged-bad-locations.md` (Cause-tagged bad-location runs and `badCauseCache`).
- `docs/adr/ADR-069-claim-integrations-extracted-to-bundled-addon.md` (`LeafRTPClaimAddon`).
- `rtp-core` `selectors/memory/shapes/MemoryShape.java`.
