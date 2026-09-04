# ADR-079 — Cause-Based TTL and Staged Expiration on Spatial Memory Segments

**Status:** Proposed
**Date:** 2026-09-03

## Context

RTP's spatial memory subsystem (`MemoryShape`, [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md)) maps 2D coordinate space onto a 1D Archimedean spiral and tracks invalid sectors as Run-Length Encoded (RLE) intervals in `badKeysCache`. [ADR-052](ADR-052-outcome-metrics-and-cause-tagged-bad-locations.md) added `badCauseCache` to tag each bad-location run with a `LocationGenerator.FailTypes` cause byte, using a "first-cause-wins" rule when adjacent runs coalesce.

Currently, all bad-location runs in `MemoryShape` are treated as monotonic and permanent: once an index is marked bad, it remains bad indefinitely unless the operator manually runs `/rtp scan reset`, reconfigures the shape bounds, or changes safety parameters.

In a live production environment, this permanence creates significant operational problems:
1. **Dynamic Rejection Causes:** While natural terrain properties (`biome`, `worldBorder`, `vert`, `safety`) are static or semi-permanent, external restrictions (`safetyExternal` via claim plugins like GriefPrevention, Towny, Lands, WorldGuard) and player placement dispersion (`uniquePlacement`) are dynamic.
2. **Permanent Land Lockout:** If a player claims a plot of land, RTP marks the candidate chunk bad under `safetyExternal`. If the player later unclaims or abandons the land, the area remains permanently blacklisted in spatial memory. Server operators are forced to discard entire regional caches (`/rtp scan reset`)—losing hours of expensive off-tick terrain scanning—just to refresh dynamic claims.
3. **Opaque External Verifier Contract:** The `RegionVerifierRegistry` API ([ADR-026](ADR-026-external-hook-api-surface.md)) currently accepts untyped `Predicate<RTPCoords>` or async functions without identifying the backing verifier. RTP cannot distinguish whether a verifier represents a permanent server spawn or a transient player claim, making uniform or per-checker expiration configuration difficult.
4. **Re-generation Churn on Expiration:** A naive hard deletion of expired segments causes immediate tick spikes: if an expired segment is hit on the next selection pass and the claim is still active (which is typical for weeks-long claims), the server must re-execute chunk loads, vertical raycasts, and verifier invocations from scratch.

## Decision

1. **Volatility Tiers & Core Cause Defaults:**
   Classify rejection causes directly using pre-existing `LocationGenerator.FailTypes` categories into three persistence and expiration tiers:
   - **Static Tier (TTL = $\infty$):** Natural terrain and geometric limits (`biome`, `worldBorder`, `vert`, `safety`, `prefilterBiome`, `prefilterRange`). Persisted permanently to `.bin` cache files. Reset only on world configuration or seed changes.
   - **Dynamic Tier (TTL = Finite, e.g. 7–30 days):** Dynamic player and administrative state (`safetyExternal`, `uniquePlacement`). Tracked with an expiration epoch. By default, all external verifiers inherit the `causes.safetyExternal` TTL baseline.
   - **Transient Tier (TTL = 0):** Ephemeral operational failures (`timeout`, `nullChunk`, `ungenerated`). Discarded immediately without entering persistent spatial memory.

2. **Selective Coalescing Rule (Refining ADR-052):**
   - Adjacent bad-location runs in `MemoryShape` shall **only** coalesce if both runs belong to the same volatility tier (Static vs. Dynamic).
   - A static run ($\text{TTL} = \infty$) shall **never** coalesce with a dynamic run ($\text{TTL} < \infty$). This prevents permanent natural terrain from expiring prematurely and prevents temporary player claims from becoming immortalized.
   - When two dynamic runs coalesce, the merged run inherits $\max(\text{TTL}_A, \text{TTL}_B)$ to prioritize CPU and chunk I/O stability over high-churn fragmentation, while retaining the first cause byte under ADR-052's first-cause-wins rule.
   - In `MemoryShape`, run expiration epochs are maintained in an aligned `long[] badExpiryCache` (storing expiration unix epoch seconds, where `<= 0` indicates infinite/static retention), matching the indexing of `badKeysCache` and `badCauseCache`.

3. **Dual-Array Rebuild & Staged Probation Buffer:**
   Implement a two-phase lazy expiration lifecycle computed in a single off-tick rebuild pass without dynamic object allocation or FIFO queues:
   - **Dual Sorted Arrays in `flushAndRebuild()`:**
     During the rebuild pass, candidate runs are partitioned simultaneously into two parallel, compact, sorted 1D RLE structures:
     1. **Active Tier (`badKeysCache`, `badPrefixSumsCache`, `badCauseCache`, `badExpiryCache`):** Contains runs where $t < 1\times \text{TTL}$ (or $\text{TTL} \le 0$). Bypassed by candidate selection in $O(\log N)$ time.
     2. **Probation Tier (`probationKeysCache`, `probationPrefixSumsCache`, `probationCauseCache`, `probationExpiryCache`):** Contains runs where $1\times \text{TTL} \le t < 2\times \text{TTL}$. Omitted from active candidate avoidance, so candidate coordinates become selectable again.
     3. **Eviction:** Runs where $t \ge 2\times \text{TTL}$ are silently dropped during rebuild without requiring secondary cleanup tasks.
   - **Fast $O(\log M)$ Probation Search & Restoration:**
     - When candidate verification in `PregenTask` fails and `shape.addBadLocation(key, cause)` / `addBadChunk(key, cause)` is called, a binary search on `probationKeysCache` checks if the key falls within an existing probation run.
     - **Re-Hit & Re-Rejected (Still Claimed):** If matched, the entire probationary run is immediately restored to `pendingBadLocations` / active spatial memory with a refreshed TTL, bypassing full terrain re-mapping with zero heap allocation.
     - **Re-Hit & Succeeded (Now Safe):** If candidate selection succeeds, the sector naturally remains in circulation; the probationary entry drops out on the next rebuild.

4. **Class-Based Verifier Attribution & Zero Addon-Directory Coupling:**
   - Instead of inventing arbitrary string keys or scanning addon jar files to discover TTL options, verifier identity is derived directly from the verifier's class type.
   - Extend `RegionVerifierRegistry` in `rtp-api` with overloads accepting the verifier class/source:
     ```java
     void register(Class<?> source, Predicate<RTPCoords> verifier);
     void registerAsync(Class<?> source, Function<RTPCoords, CompletableFuture<Boolean>> verifier);
     ```
     Existing methods (`register(Predicate<RTPCoords>)`) remain supported as default methods delegating to `register(verifier.getClass(), verifier)`.
   - Internal verifier evaluation in `GlobalRegionVerifiers` captures the registering class, returning a structured result:
     ```java
     public record VerifierCheckResult(boolean passed, @Nullable Class<?> verifierClass)
     ```
   - This allows `PregenTask` to attribute rejections in stats and logs (`safetyExternal[GriefPreventionChecker]`) and resolve the applicable TTL dynamically without requiring the addon to parse configuration or declare custom duration objects.

5. **Configuration Hierarchy (`advanced/ttl.yml`):**
   Provide an operator configuration under `advanced/ttl.yml` driven by core `FailTypes` with optional class-name overrides:
   ```yaml
   # Causes map directly to LocationGenerator.FailTypes
   causes:
     biome: -1
     worldBorder: -1
     vert: -1
     safety: -1
     uniquePlacement: 30d
     safetyExternal: 14d   # Base default for all external claim checkers

   # Optional overrides keyed by verifier class name (simple name or FQN)
   verifiers:
     WorldGuardChecker: -1       # Permanent retention for server regions/spawns
     GriefPreventionChecker: 14d # Custom retention for player claims
   ```
   - **Resolution Order:** `verifiers.<ClassName>` -> `causes.safetyExternal` -> default dynamic tier (14d).
   - Server operators have immediate, granular control over any claim plugin without requiring addon directory scans, addon restarts, or custom code updates in addon modules.

6. **Unified Disk Storage (`BIN_VERSION 3`):**
   - Because active and probation segments have inherently zero spatial overlap, all retained segments are serialized to disk in a single continuous 1D sorted stream.
   - Increment `BIN_VERSION` from `2` to `3`. Each bad-run entry on disk stores 25 bytes:
     - `startKey` (`long`, 8 bytes)
     - `delta` / `length` (`long`, 8 bytes)
     - `cause` (`byte`, 1 byte)
     - `expiresAtEpochSeconds` (`long`, 8 bytes; `<= 0` indicates static/infinite retention)
   - On load, segments are partitioned based on current wall-clock epoch:
     - $t < \text{expiresAt} \implies$ placed in active array (`badKeysCache`).
     - $\text{expiresAt} \le t < \text{expiresAt} + \text{TTL} \implies$ placed in probation array (`probationKeysCache`).
     - $t \ge \text{expiresAt} + \text{TTL} \implies$ pruned on load (offline decay during server downtime).
   - Reading legacy files (`BIN_VERSION <= 2`) treats `expiresAt` as `<= 0` (permanent), guaranteeing full backward compatibility.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Instant hard deletion of expired segments | Triggers heavy chunk loading and pipeline re-checks when claims are still valid. Staged probation allows $O(1)$ restoration of still-bad sectors. |
| Coalescing dynamic runs with $\min(\text{TTL})$ | Causes large, long-lived claim segments to expire rapidly when bordered by a short-lived claim, increasing scan churn. |
| Allowing static and dynamic runs to coalesce | Causes static ocean/mountain runs to inherit finite TTLs and re-verify periodically, or causes dynamic claims to become permanent. |
| Requiring string registration keys and scanning addon jars | Forces RTP core to scan the filesystem/JARs to discover config options; using pre-existing `FailTypes` with verifier class names eliminates addon-directory probing. |
| Forcing addons to read RTP config files | Violates addon encapsulation; third-party addons should focus on claim checking while RTP core handles configuration hierarchy. |
| Per-location 64-bit expiration timestamps | Destroys the memory compactness of the RLE interval structure; storing coarse epoch buckets per coalesced run preserves zero-heap efficiency. |

## Maintainability & Operational Factors

1. **Subsystem Cohesion & Domain Modeling:**
   - Decouples geometric coordinate mapping (`MemoryShape` Archimedean spiral bijection) from assumptions of static immutability.
   - Leverages core-side `LocationGenerator.FailTypes` directly, ensuring full alignment with `badCauseCache` (ADR-052) and `/rtp stats` outcome metrics.

2. **Loose Coupling & API Compatibility:**
   - Additive overloads taking `Class<?> source` with default methods prevent breaking binary and source compatibility for third-party or legacy claim addons (S-006, ADR-026).
   - Zero coupling to the addon filesystem or JAR manifests: `rtp-core` does not need to scan addon directories to discover configurable verifiers. Addons register naturally with their checker class.

3. **Performance & Concurrency Guardrails:**
   - **Zero-Allocation Hot Path:** The selection path (`MemoryShape.rand()` and binary search on `badKeysCache`) remains lock-free and zero-allocation. All segment aging, probation transitions, and evictions are executed off-tick during periodic pulses or rebuild passes (`flushAndRebuild()`).
   - **Bounded RLE Growth:** Segmenting runs across volatility boundaries increases RLE run count by only 5–8% in typical workloads, preserving cache compactness.
   - **Probation Side-Space Sizing:** The quarantine buffer is bounded by active candidate selection volume and cleared lazily, preventing heap bloat.

4. **Observability & Diagnostics:**
   - Granular failure attribution (`safetyExternal[<VerifierName>]`) surfaces exact rejection bottlenecks in `/rtp stats`, verbose logs, and debugging tools, eliminating opaque failure buckets.
   - Visualizing active vs. probationary segments is supported through existing debug shape JSON exports.

5. **Persistence & Migration Safety:**
   - Unified single-stream disk serialization (`BIN_VERSION 3`) stores expiration epoch seconds per run, enabling smooth offline decay across server restarts while maintaining backward compatibility for legacy `BIN_VERSION <= 2` caches.

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
