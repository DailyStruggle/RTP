# ADR-078 - Composable Cache Pipeline Stages, Domain Stage Nomenclature, and Dynamic Hot Quota Allocation

**Status:** Proposed
**Date:** 2026-09-02
**Extends:** [ADR-006](ADR-006-async-queue-pre-generation.md) (Async Queue Pre-Generation), [ADR-023](ADR-023-login-reserve-cache.md) (Login Reserve Cache), [ADR-028](ADR-028-l3-backlog-cache.md) (L3 Backlog Cache)
**Related:** [ADR-036](ADR-036-network-mode-multi-server-multi-proxy.md) (Network Mode), [ADR-043](ADR-043-personal-queue-permission-semantics.md) (Personal Queue Permission Semantics), `leafrtp-group-addon-ADR-002` (Group Placement Cache Pipeline)

## Context

Location pre-generation and caching in RTP has evolved from simple single-location buffers into multi-stage pipelines and specialized routing pools:
1. **Core Single-Target Pipelines:** The pipeline operates across candidate generation, off-tick region file screening, cold coordinate storage, and async chunk reservation acquisition (`RegionQueueManager`).
2. **Specialized Hot Pools:** Multiple specialized consumer sinks branch off the primary engine, including the Login Reserve (`loginLocations`, ADR-023), Personal Buckets (`perPlayerLocationQueue`, ADR-043), and Cross-Server Network Reservations (`networkKeptLocations`, ADR-036).
3. **Multi-Target / Addon Placements:** Addons such as `LeafRTPGroupAddon` require dedicated caching for composite group subspaces spanning multiple columns and multi-chunk footprints.

Current concrete state in `RegionQueueManager` (single-target engine):

| Field | Role | Stage in new nomenclature | Ticket state |
| :--- | :--- | :--- | :--- |
| `backlogLocations` (`BacklogLocationBuffer`, ADR-028) | Unverified FIFO candidates, one 32x32 bin screened per pulse | Backlog | none |
| `unkeptLocations` (`LockFreeLocationBuffer`) | Pre-verified coordinates, reservations released | Cold | none |
| `keptLocations` (`LockFreeLocationBuffer`) | Verified coordinates with live `keep(true)` reservations | Hot (general sink) | active |
| `loginLocations` (ADR-023) | Default-world reserve for join-time RTP | Hot (login sink) | active |
| `networkKeptLocations` (ADR-036) | Proxy token reservations | Hot (network sink) | active, token-pinned |
| `perPlayerLocationQueue` (ADR-043) | Per-UUID buckets | Hot (keyed sink) | active |
| `fastLocations` | Per-UUID in-flight `CompletableFuture<RTPLocation>` | promise, not a stage | transient |

Each sink today carries its own fill loop, drain routine, capacity arithmetic, and database save/delete callback wiring. `drainLoginCache`, the network release paths, and `clear`/`shutdown` each re-implement the same three steps: poll, close reservation, re-offer the bare coordinate to `unkeptLocations`. This duplication is the primary source of ticket-hygiene risk (S-002).

Three architectural challenges have emerged:
- **CPU Cache Analogy Confusion:** Informal shorthand terms (`L1`, `L2`, `L3`) carry hardware cache connotations (speed hierarchies, write-back mechanics) that mislead contributors. In RTP, tiers represent **chunk residency lifecycle and ticket states** rather than processor memory tiers.
- **Monolithic vs. Duplicated Pipeline Structures:** Hardcoding fixed 3-tier structures in a single class forces rigid tier counts on subsystems that only need 2 tiers or specialized branches. Conversely, writing bespoke queue managers for addons duplicates queue synchronization, backpressure, and chunk-ticket leak prevention (S-002).
- **Constrained Hot Memory & Ticket Thrashing:** Hot entries hold active chunk tickets in server memory (`keep(true)`), making hot capacity strictly resource-bounded. Statically pre-allocating hot slots across every permutation of region, profile, and shape bloats memory. Conversely, naive dynamic re-allocation risks chunk-ticket thrashing (rapid load/unload cycles).

## Decision

1. **Standardize Domain Stage Nomenclature**:
   - Retire `L1`, `L2`, and `L3` from production code and formal contracts in favor of domain-accurate terms:
     - **`Backlog` (or `Candidate`):** Unscreened spatial coordinate candidates sitting in memory awaiting off-tick file/biome/terrain validation. Zero chunk I/O, zero tickets.
     - **`Cold` (or `Unkept`):** Geometrically and terrain-verified coordinates whose chunk tickets have been released to minimize RAM usage. Zero tickets.
     - **`Hot` (or `Kept`):** Pre-verified destinations holding active `keep(true)` chunk tickets (`ChunkReservation`) in `MemoryTracker`, ready for zero-latency dispatch.
   - Sinks and queues shall use explicit naming: `hotQueue`, `coldQueue`, `backlogQueue`, `loginHotQueue`, `networkHotQueue`.

2. **Composable Cache Stage Architecture (`CacheStage<T>`)**:
   - Rather than a monolithic 3-tier container class, decompose cache pipelines into discrete, generic, bounded stage buffers.
   - `CacheStage<T>` is an **interface**, not a concrete container. Storage is pluggable so an existing buffer can be wrapped rather than replaced: `RingCacheStage` delegates to `LockFreeLocationBuffer` (power-of-two masked ring, lock-free counters, database callbacks intact) and `SimpleCacheStage` backs new pipelines with a `ConcurrentLinkedQueue`. Migration phase 3 is behavior-preserving only because of this split.
   - Stages are decoupled from fixed tier counts, enabling arbitrary pipeline topologies (e.g., 2-stage `Cold -> Hot`, 3-stage `Backlog -> Cold -> Hot`, or branching sinks).
   - Stages are connected by asynchronous promotion transitions (`StageTransition<From, To>`) that return an explicit outcome carrying a rejection reason, never a bare `Optional` (S-004).
   - **Disposal is terminal.** A stage's `onDispose` handler releases resources and nothing else; it never re-offers into another stage. Recycling a demoted coordinate back into `Cold` is the transition layer's job, so there is no mutual re-offer path between a full `Hot` and a full `Cold` stage, and `close()` during shutdown disposes rather than recycles.
   - **Internal movement is silent.** Moving an entry between stages of the same pipeline shall not fire the persistence callbacks (`onAdd` / `onRemove`), because the underlying row still describes a live location; only pipeline ingress and terminal disposal are persistence-visible.

3. **Branching Architecture: Cold Inventory and Alternate Hot Sinks**:
   - The `Cold` stage acts as the shared, low-overhead inventory of verified coordinates.
   - Specialized hot pools (`loginLocations`, `networkKeptLocations`, `personalBuckets`, `groupPresets`) branch off the `Cold` stage as independent hot sinks.
   - All hot sinks adhere to the same lifecycle invariant (S-002): upon disabling, draining, or eviction, chunk tickets are deterministically closed. Whether the bare coordinate is then recycled into `Cold` is decided by the demotion transition, not by the disposal handler; shutdown closes without recycling.

4. **Fungibility by Transfer-Time Recheck Against the Resident Chunk**:
   - **Recheck, do not prove equivalence.** A `Hot` entry holds a live `keep(true)` reservation, so its chunk is already resident. Asking "would the destination sink accept this entry?" is therefore a memory-only operation: world identity is a reference comparison, shape/distance containment and vertical bounds are arithmetic on the coordinate, and block/biome rules read an already-loaded chunk. Transfer eligibility shall be decided by running the destination sink's acceptance check against the candidate entry, not by statically comparing sink criteria.
   - **No chunk I/O, no S-005 exposure.** The recheck loads nothing. Entry count is bounded by the hot capacity (`activeChunkCap`), so a full rebalance pass is bounded in-memory work.
   - **Fail direction.** A recheck can only reject an entry the destination would not have accepted; it cannot claim fungibility for an entry it did not actually test. An entry that fails the destination check is left in place, never force-transferred.
   - **Extrinsic verifiers are excluded from recheck.** Verifiers that call outside the server process or require a specific thread (claim-plugin lookups, S-003) shall not be re-run per transfer. A sink carrying any extrinsic verifier is transfer-eligible only from a sink sharing the same upstream `Cold` instance by reference and applying no criteria that upstream does not already guarantee, where common provenance establishes equivalence without re-evaluation.
   - **Transfer mechanics.** An accepted transfer is an `O(1)` hand-off of the entry and its active `ChunkReservation` between hot stages, with **zero chunk load/unload cycles** and zero chunk thrashing.
   - **Non-transferable sinks.** Sinks whose entries cannot pass the destination check (distinct shapes, or a footprint smaller than the destination requires) and externally leased entries (proxy tokens, ADR-036) are never rebalanced by transfer. Their balance is adjusted only through the **promotion rate from their respective Cold queues**, and rebalancing shall never evict an active hot chunk.
   - **Deferred: production-time epoch stamping.** Stamping each entry with an identity for the pipeline configuration that verified it would let staleness and equivalence be decided without re-running any check, and would additionally identify entries cached under superseded config after a reload. Not adopted here; recorded as an open follow-up.

5. **Combinatorial Subsumption (Superset Caching)**:
   - For multi-target / group placements, hot caches shall store maximal-capacity subspaces (`N_max`) for each shape profile. Because a safe subspace of size `N` satisfies any incoming request of size `k <= N`, superset caching eliminates cache partition fragmentation and maximizes hit rates without additional chunk allocations.
   - Subsumption is enforced as precondition rule 0 of the transfer-eligibility test (`a.chunkCostPerEntry() >= b.chunkCostPerEntry()`); it authorizes the size comparison only, and the entry itself is still certified by provenance or recheck.

6. **Periodic Region Compute-Tick Re-Allocation**:
   - Dynamic quota evaluation across sinks shall execute on the periodic region compute pulse (`Region.execute()` or addon async pulse), rather than reactively on every player event.
   - On each pulse:
     1. Evaluate demand/usage counters across registered sinks.
     2. Balance transfer-eligible hot entries via direct queue transfer.
     3. Refill under-quota sinks via gated async promotion from their respective Cold queues.
   - Hysteresis: a hot entry shall not be evicted for rebalancing purposes before a minimum residence time (default 60s). Quota changes shall be applied as promotion gating, not eviction.

7. **Hot Topology: Per-Access-Method Sinks Retained**:
   - Each access method keeps its own hot stage. `loginLocations` (ADR-023), `networkKeptLocations` (ADR-036), and `perPlayerLocationQueue` (ADR-043) remain distinct sinks with their own capacities; this ADR centralizes their lifecycle without merging them.
   - Whether criteria-equivalent general sinks should instead share a single hot stage with per-sink floors is **open and not decided here**. See *Open Question: Shared Pool with Floor vs. Per-Access-Method Reservation* below.

8. **Placement and Module Boundaries**:
   - `rtp-api`: unchanged. Deciding transfer eligibility by recheck requires no new public value types, so no `rtp-api` surface is added and addons keep passing plain `Predicate<T>`.
   - `rtp-core`: `CacheStage<T>`, `KeyedCacheStage<K, T>`, `StageTransition<From, To>`, `CachePipeline`, `HotSink`, `HotBudgetAllocator`. Generic and placement-agnostic - no group/addon domain types.
   - Addons (`LeafRTPGroupAddon` and future modules): domain entry types, candidate suppliers, screening transitions, footprint-ticket promotion transitions. Addons shall not add fields to `RegionQueueManager`, `RegionSettings`, or `Region`.

## Detailed Design

### Stage Contracts

```java
// rtp-core: bounded stage contract with deterministic, terminal disposal.
public interface CacheStage<T> extends AutoCloseable {
  String name();

  Optional<T> poll();            // removes; caller takes ownership of any ticket; fires onRemove
  boolean offer(T item);         // on overflow: onDispose.accept(item), returns false; fires onAdd

  Optional<T> pollSilently();    // internal movement out: no onRemove
  boolean offerSilently(T item); // internal movement in: no onAdd

  int size();
  int capacity();
  int resizeCapacity(int newCapacity); // returns the applied capacity; surplus disposed
  void close();                  // drains, applying onDispose to every entry; never recycles
}

// Storage-specific implementations, both satisfying one shared contract suite.
public final class RingCacheStage<T> implements CacheStage<T>   // wraps LockFreeLocationBuffer
public final class SimpleCacheStage<T> implements CacheStage<T> // ConcurrentLinkedQueue
```

Invariants:
- `onDispose` is the single disposal path and is **terminal**. For hot stages it closes the `ChunkReservation` (or a composite closer) and stops there. Nothing else in the codebase closes a reservation held by a stage, and no disposal handler offers into another stage.
- Recycling is a transition, not a disposal. Demoting a hot entry to `Cold` is `hot.pollSilently()` -> strip and close the reservation -> `cold.offer(bareCoordinate)`; if `Cold` is full, `Cold`'s own `onDispose` runs once and the sequence ends. No stage's disposal can trigger another's.
- `poll()` / `offer()` are the persistence-visible pipeline boundary and fire the configured `onAdd` / `onRemove` database callbacks. `pollSilently()` / `offerSilently()` are the internal movement pair and fire neither; stage-to-stage promotion and demotion shall use them, because firing `onRemove` for a location that still exists deletes a live row (the composite-key race guarded by the existing `LoginCacheTask` regression test).
- `offer()` never blocks and never loses a ticket: overflow disposes via `onDispose` rather than dropping silently.
- Capacity is advisory-bounded, not strict-locked: sizes are read from lock-free counters, so transient overshoot by concurrent producers is permitted and reconciled on the next pulse.
- `resizeCapacity` returns the capacity actually applied, which may exceed the request. `RingCacheStage` cannot resize a masked ring in place, so it allocates a new power-of-two ring, migrates entries preserving publication order, disposes any surplus, and swaps the reference; callers shall use the returned value rather than assuming the requested one.

```java
// rtp-core: an async promotion edge between two stages.
@FunctionalInterface
public interface StageTransition<From, To> {
  CompletableFuture<TransitionOutcome<To>> promote(From source);
}

// Explicit outcome; a rejection always carries a reason (S-004).
public sealed interface TransitionOutcome<To> {
  record Promoted<To>(To value) implements TransitionOutcome<To> {}
  record Rejected<To>(RejectionReason reason, String detail) implements TransitionOutcome<To> {}
}

public enum RejectionReason {
  UNSAFE_BLOCK, BIOME_EXCLUDED, OUT_OF_BOUNDS, CLAIM_PROTECTED,
  RESERVATION_FAILED, BUDGET_EXHAUSTED, SHUTTING_DOWN, ERROR
}
```

Transition rules:
- Transitions run off the main thread (S-005). Screening transitions perform region-file reads through `AnvilIoPool` (ADR-016/ADR-077); promotion transitions perform async chunk reservation.
- A transition never returns an empty result without a reason: rejection is `Rejected(reason, detail)`, and it shall never swallow an exception (S-004). Exceptional completion maps to `Rejected(ERROR, message)`, is logged via `RTP.log(Level.WARNING, msg, e)`, and the source entry is disposed.
- Promotion is idempotent per entry: an entry is removed from the source stage before the transition starts, so a failed promotion returns the bare coordinate to `Cold` rather than duplicating it.

```java
// rtp-core: keyed partition for per-UUID or per-profile buckets.
public class KeyedCacheStage<K, T> implements AutoCloseable {
  public CacheStage<T> open(K key, int capacity);  // idempotent
  public void closeKey(K key);                     // drains that partition via onDispose
  public Optional<T> poll(K key);
}
```

`KeyedCacheStage<UUID, RTPLocation>` replaces the bespoke `perPlayerLocationQueue` handling (ADR-043); `KeyedCacheStage<String, GroupSubspace>` replaces the profile-keyed maps in the group addon.

A keyed stage registers as a **single** `HotSink` covering all of its partitions, so it has one sink identity for budgeting and eligibility purposes. Because its entries are leased to a specific key, that sink reports `isExternallyLeased() == true` and is therefore never a transfer source or destination; its partitions are balanced only through the `Cold` promotion gate.

### Hot Sink Contract

```java
// rtp-core
public interface HotSink<T> {
  String name();
  CacheStage<T> stage();
  CacheStage<?> coldSource();          // upstream inventory; identity establishes common provenance
  boolean accepts(T entry);            // resident-chunk recheck; no chunk I/O, no extrinsic calls
  boolean hasExtrinsicVerifier();      // true if acceptance depends on an out-of-process check
  boolean isExternallyLeased();        // true if entries are pinned to a token or key
  boolean narrowsBeyondColdSource();   // true if the sink applies criteria its Cold source does not
  int chunkCostPerEntry();             // footprint size; 1 for single-target
  long demandWeight();                 // EWMA request counter, updated on pulse
}
```

Transfer-eligibility test. Rules 0 and 1 are preconditions on the sink pair; rule 2 certifies the individual entry:

0. **Preconditions.** Neither sink is externally leased, and the footprint relation `a.chunkCostPerEntry() >= b.chunkCostPerEntry()` holds. A proxy token (ADR-036) or a keyed personal bucket (ADR-043) pins a specific coordinate to a specific holder, so a leased sink is neither source nor destination. The footprint relation is the subsumption rule of decision item 5 stated directly: a verified subspace of capacity `N` may serve a destination requesting `k <= N` slots, never the reverse, and for single-target sinks it degenerates to equality (`1 >= 1`). Subsumption authorizes only the size comparison, never the entry itself. Failing this, stop.
1. **Provenance certification.** `a.coldSource() == b.coldSource() && !b.narrowsBeyondColdSource()` - the destination applies nothing the shared upstream did not already guarantee, so the entry is certified without further checks. Provenance alone is not sufficient: a sink that narrows further (for example a default-world-only reserve branching off a wider `Cold` stage) falls through to rule 2.
2. **Entry recheck.** `!b.hasExtrinsicVerifier() && b.accepts(entry)` - the destination's own acceptance check, evaluated against the candidate entry on its already-resident chunk.
3. Otherwise not transfer-eligible; balance the sink through its `Cold` promotion gate instead.

An entry passing rule 0 is still uncertified until rule 1 or rule 2 certifies it; a surplus-capacity subspace transferred under subsumption is trimmed to `k` targets by the transition, and the released remainder is disposed through the source stage's `onDispose`.

`accepts(T)` is a pure, in-memory predicate over the entry: world identity by reference, shape and distance containment, vertical bounds and elevation tolerance, then block and biome rules read from the chunk the entry's own reservation is already holding. It shall not load a chunk (S-005) and shall not invoke an extrinsic verifier. A sink whose acceptance genuinely depends on an out-of-process check reports `hasExtrinsicVerifier() == true` and is eligible only under rule 1, so the answer is never guessed - it is either measured or declined.

`accepts(T)` is defined only for entries holding a live reservation, since its block and biome checks read the resident chunk. It shall **fail closed**: an entry whose reservation is `null` (a `Cold` coordinate) or already closed shall be rejected rather than inspected, and the implementation shall neither dereference the absent reservation nor fall back to loading the chunk. Cold entries reach a hot sink only through the promotion transition, which performs full verification with I/O off the main thread; they are never certified by recheck.

### Budget Allocation

- The operator configures a single global ceiling of resident cached chunks per region (`max-cached-chunks`), replacing independent per-sink caps as the primary tuning knob. Existing per-sink caps remain honored as upper bounds.
- On each region compute pulse `HotBudgetAllocator` computes each sink's target as proportional to `demandWeight() / chunkCostPerEntry()`, normalized to the ceiling, with a configurable floor per sink so a rarely used sink still resolves from `Cold` promptly.
- Floors are honored before proportional shares and are bounded by `sum(floors) <= max-cached-chunks`; a sink below its floor is topped up by transfer of a transfer-eligible entry from a sibling above its share, never by opening a new reservation while the budget is saturated.
- A refill-rate knob caps how many `Cold` -> `Hot` promotions may be in flight per pulse, bounding I/O churn. It is a churn bound and a description of post-burst recovery speed, not a latency control: its ceiling is set by storage throughput, so it is not an actionable remedy on slow storage.
- Applying targets: transfer-eligible entries move directly between sinks (`O(1)` queue transfer, zero chunk I/O); everything else receives changed promotion gates only. No active reservation is closed to satisfy a quota.
- Because `RingCacheStage.resizeCapacity` rounds up to a power of two, per-sink applied capacities may sum above `max-cached-chunks`. Capacity is a promotion gate, not an allocation: the allocator shall bound residency by the global ceiling when admitting promotions, so rounding slack stays unfilled rather than becoming resident chunks.

### Open Question: Shared Pool with Floor vs. Per-Access-Method Reservation

Non-normative. The alternative to branching sinks is a single hot pool that every access method draws from, with no reservation per access method. Both are viable on top of the contracts above. Nothing in this section is decided; it is recorded so the trade-off does not have to be reconstructed later. The current decision (item 7) is to retain per-access-method sinks.

| Dimension | Single shared hot pool | Per-access-method reserved sinks |
| :--- | :--- | :--- |
| Resident chunk count | Lowest: one pool sized to aggregate peak demand, no per-sink slack | Higher: each sink holds idle slack; sum of peaks rather than peak of sum |
| Hit rate under mixed load | Highest for fungible consumers - all capacity is available to whoever asks first | Lower: a sink can miss while a sibling sits full |
| Starvation risk | Real: `/rtp` spam can drain the pool just as a login burst arrives, pushing joins onto a synchronous-feeling `Cold` promotion | None for the reserved consumer; this is exactly why ADR-023 exists |
| Latency guarantee | Statistical only | Per-sink guarantee for as long as the reserve holds |
| Non-fungible consumers | Impossible: a group subspace or a foreign-world token cannot be served from a single-location pool | Required |
| External leases | Impossible: proxy tokens (ADR-036) must pin a specific coordinate for a specific token id | Natural fit |
| Code volume | Minimal: one fill loop, one drain path | Higher, unless the sink lifecycle is centralized (this ADR's `HotSink` + `CacheStage`) |
| Rebalancing cost | None - nothing to rebalance | Needs the pulse-driven allocator described above |

What the table settles by capability: non-fungible consumers (group subspaces, foreign-world coordinates) and externally leased entries (proxy tokens, ADR-036; keyed personal buckets, ADR-043) cannot be served from a shared single-location pool at all. These keep their own stages regardless of the general-case choice.

Open considerations for the general `/rtp` vs. login reserve (ADR-023) case:

1. **The availability goal is depth, not rate.** The informal target is that prepared locations are on hand to cover an instantaneous fill from the current online count up to the player cap. No normative latency figure is set here, and ADR-023 does not state one; a 5s tolerance has been mentioned informally as roughly when a waiting player starts to notice, but it is not a requirement. Depth ahead of demand is the controlling variable, since refill rate during a burst is bounded by storage throughput.
2. **Depth is affordable in `Cold` and expensive in `Hot`.** A `Cold` entry is a verified coordinate with no reservation - tens of bytes, cheap to stockpile to player-cap depth. A `Hot` entry of the same coordinate pins a resident chunk. This asymmetry is the strongest datum in the comparison, and it cuts both ways: it argues for deep `Cold` inventory *and* for keeping any hot reserve small, which is what ADR-023 already does by capping the login reserve at the player cap.
3. **Starvation vs. idle residency is the actual trade.** A shared pool risks `/rtp` drain coinciding with a join burst; separate reserves risk a sink sitting idle while a sibling misses. Which dominates is an empirical question about observed reserve occupancy, and no measurement exists yet.
4. **A per-sink floor would be the middle option.** If pooling were adopted, a small floor restores burst protection without pre-partitioning, and satisfying a floor is an `O(1)` transfer at zero chunk I/O between transfer-eligible sinks.

Resolving this needs measurement rather than further argument, and the question to measure is narrower than "is the reserve well sized". Pooling only pays if reserved hot capacity sits idle *at the moments it is missed elsewhere*: a reserve that is empty exactly when `/rtp` is also starved would not have been rescued by pooling, and a reserve that is full while `/rtp` starves is capacity a pool could have lent. So the decisive datum is the **joint** state - hot fill across sinks sampled at the instant of a miss - not either occupancy series on its own.

`METRICS_PLAN.md` already specifies both halves: `loginReserveExhaustion` as a host-level counter incremented at the login-reserve fall-through branch (Phase M1), and per-region `loginFill` / `loginCap` alongside `keptFill` / `keptCap` as `RegionQueueRow` fields within `regionQueueStatus` (Phase M2). The correlation the decision rests on needs the per-region row, so item 7 is gated on M2 rather than M1, and keeps the existing topology until then.

### Maintainability Impact

This ADR is net **consolidating**, but the saving is in the number of hand-written copies of an invariant rather than in line count. Measured against the current tree:

| Signal | Measured today | After migration |
| :--- | :--- | :--- |
| Sites calling `reservation().close()` by hand | ~25, spread over `rtp-core`, `rtp-plugin`, and the bukkit/folia/fabric/neoforge world adapters; `Region.java` alone holds 6 and `RegionQueueManager` 4 | One disposal handler per stage; adapters keep only the teleport-completion close, which is not a cache concern |
| Bounded-buffer implementations | 2 (`LockFreeLocationBuffer` 247 lines, `BacklogLocationBuffer` 326 lines) with overlapping capacity, counter, and callback logic and no shared contract | 2 storage implementations behind 1 contract with 1 shared test suite |
| Duplicated hot-to-cold drain routines | 3 in `RegionQueueManager`, already collapsed into `demoteToUnkept`; per-sink fill/drain bodies remain | 1 demotion transition |
| Addon queue plumbing | `GroupCacheWorker` 202 + `GroupSubspaceCache` 181 lines re-implementing promotion and disposal | Re-expressed on `CacheStage` / `KeyedCacheStage` / `StageTransition` |
| Tier vocabularies in use | 3 (`keptLocations`/`unkeptLocations`, `L1`/`L2`/`L3`, hot/cold/backlog) | 1 |

The load-bearing argument is defect-class elimination, not volume. `releaseToNetworkKept` leaked a `ChunkReservation` on both of its demotion branches while two sibling methods in the same class closed it correctly: four call sites implemented one invariant by hand and one implemented half of it. Centralizing demotion made that bug unrepresentable. The obligation being removed is also **growing**, not static: ADR-023, ADR-028, ADR-036, and ADR-043 each added a sink, and each addition added another hand-written disposal path.

Costs to record honestly:

- **Additive-first phasing creates a debt window.** Phases 1-2 add contracts that nothing consumes, so line count rises before anything is deleted. A migration abandoned after phase 2 leaves dead abstraction and is strictly worse than not starting. Phases 3-6 are therefore not optional follow-ups.
- **New contract surface must be learned.** `poll` vs `pollSilently` and disposal-is-terminal are two rules a contributor must know before touching cache code. Note that `offerSilently` currently has exactly one production caller (`Region.java`), so the persistence-callback hazard it guards is presently near-invisible in the code; promoting it to an explicit contract pair makes a latent rule discoverable, which is part of the maintainability gain rather than only a cost.
- **The phase 2 rename is not purely internal.** `L1`/`L2`/`L3` leaks into operator-visible strings (`ClearCacheCmd`'s description and its completion message), so the nomenclature pass touches user-facing output and must respect REQ-RTP-F-013 message configurability, not just javadoc.
- **`HotBudgetAllocator` (phase 5) is the one component with no duplication to retire.** It is net new complexity justified by capability, not by maintainability, and should be argued on its own merits rather than counted as consolidation.

### Migration Phases

Behavior-preserving, one phase per change set, each independently buildable and testable:

1. **Introduce contracts (additive, no behavior change).** Add `CacheStage`, `KeyedCacheStage`, `StageTransition`, `HotSink` to `rtp-core`. No new `rtp-api` types are required. Nothing consumes them yet.
2. **Nomenclature pass.** Rename internal identifiers, javadoc, and config comments from `L1`/`L2`/`L3` to `hot`/`cold`/`backlog`. The pass covers the canonical documentation that carries the retired vocabulary as well as source: the tier table in `DESIGN.md`, the alias rows in `GLOSSARY.md` and `AGENTS.md`, the group addon's `GroupSubspaceCache(int l1KeptCap, int l2ColdCap, int l3BacklogCap)` parameters and its test `@DisplayName`s. ADR-028 keeps its title and number as the historical record and gains a nomenclature note pointing here rather than being retitled. Public config keys and database column names are not renamed in this phase; operator-facing renames follow the standard migration/version-bump rules.
3. **Wrap existing buffers.** Back `keptLocations`, `unkeptLocations`, and `backlogLocations` with `RingCacheStage` over the existing `LockFreeLocationBuffer` storage and database callbacks, mapping the buffer's existing silent operations onto `pollSilently` / `offerSilently`. Field names and public accessors are preserved.
4. **Centralize sink lifecycle.** Re-express `loginLocations`, `networkKeptLocations`, and `perPlayerLocationQueue` as `HotSink` registrations sharing the region's `Cold` stage. Delete the duplicated drain routines in favor of `CacheStage.close()` plus the single demotion transition, the shape already established by `RegionQueueManager.demoteToUnkept`. ADR-023, ADR-036, and ADR-043 observable behavior is unchanged.
5. **Introduce the allocator.** `HotBudgetAllocator` on the region compute pulse, defaulting to the current static caps so existing deployments see no change until `max-cached-chunks` is set. Sink topology is unchanged: the allocator adjusts promotion gates and moves transfer-eligible entries, and never pools distinct sinks.
6. **Port the group addon.** `GroupSubspaceCache` and `GroupCacheWorker` are re-expressed on `CacheStage` / `KeyedCacheStage` / `StageTransition`, deleting the addon's duplicated queue plumbing.

### Verification Plan

- `CacheStage` contract tests: overflow disposes exactly once, `close()` disposes every entry, `poll()` transfers ownership, `resizeCapacity` down-sizing disposes the surplus, concurrent producer/consumer never double-disposes.
- Ticket-hygiene tests using a counting mock `ChunkReservation`: opened count equals closed count after every fill/drain/clear/shutdown/reload sequence, including failed and exceptionally-completed transitions.
- Storage-wrap tests: `RingCacheStage` over `LockFreeLocationBuffer` and `SimpleCacheStage` both pass one shared `CacheStage` contract suite; `resizeCapacity` returns the applied (power-of-two-rounded) capacity for the ring implementation and the exact value for the simple one, and an allocate-and-migrate resize neither loses nor double-disposes an entry under concurrent producers.
- Silent-transfer tests: an internal promotion or demotion fires no `onAdd`/`onRemove` callback and leaves the persisted row intact, while ingress and terminal disposal do fire them. The existing composite-key race regression shall pass unmodified.
- Disposal-terminality tests: `onDispose` on a hot stage closes the reservation and performs no re-offer; a demotion into a full `Cold` stage disposes the surplus without recursing into `Cold`'s disposal; `close()` during shutdown recycles nothing.
- Transition-outcome tests: every rejection path yields a `Rejected` with a populated `RejectionReason`; an exceptionally-completed transition yields `ERROR`, logs at `WARNING`, and disposes the source entry (S-004).
- Recheck tests: `accepts(T)` performs zero chunk loads (asserted against a chunk-provider mock that fails the test on any load call); an entry outside the destination's shape, world, or vertical bounds is rejected; an entry violating the destination's block or biome rules is rejected; a sink reporting `hasExtrinsicVerifier()` is never transfer-eligible except under common provenance.
- Eligibility precondition tests: an externally leased sink (proxy token, keyed bucket) is never transfer-eligible; a source whose `chunkCostPerEntry` is smaller than the destination's is never transfer-eligible, while an equal or larger footprint satisfies rule 0; a sink branching off the same `Cold` stage but reporting `narrowsBeyondColdSource()` is not certified by rule 1 and falls through to the rule 2 recheck.
- Subsumption tests: an `N`-capacity subspace serves a request of size `k <= N` and never one of size `k > N`, subsumption never authorizes a transfer that `accepts(T)` rejects, and a subspace trimmed to `k` targets disposes its released remainder exactly once.
- Recheck fail-closed tests: `accepts(T)` returns false for an entry whose reservation is `null` and for one whose reservation is already closed, without dereferencing it and without any chunk-load call.
- Allocator hysteresis tests: a quota change inside the minimum residence window performs no eviction; a sustained demand change is applied as promotion gating.
- Nomenclature guard (phase 2): a source scan asserts no `L1`/`L2`/`L3` identifiers remain in production code or javadoc, while public config keys and database column names are unchanged. Operator-visible strings that currently carry the old vocabulary (`ClearCacheCmd` description and completion message) are covered by the message-configurability suite (REQ-RTP-F-013) rather than hardcoded in the rename.
- Regression tests: existing login reserve (ADR-023), network reservation (ADR-036), personal queue (ADR-043), and backlog (ADR-028) suites shall pass unmodified through phases 2-4.
- Allocator tests: a non-eligible rebalance never closes an active reservation, and a floor deficit is satisfied by entry transfer with zero reservation open/close operations.
- Each phase ends with a full multi-module build.
- `TRACEABILITY.md` gains rows for the new contracts and their suites (`CacheStage`, `KeyedCacheStage`, `StageTransition`, `HotSink`, `HotBudgetAllocator`) against the requirements they discharge - REQ-RTP-S-002 for ticket hygiene, REQ-RTP-S-004 for transition outcomes, REQ-RTP-S-005 for the zero-load recheck - added in the phase that introduces each.

## Alternatives Considered

| Alternative | Why Rejected |
| :--- | :--- |
| **Option A: Monolithic `TieredCache<C, V, H>` class** | Hardcodes a rigid 3-tier structure; fails to accommodate 2-stage caches, partitioned personal buckets, or multiple specialized hot sinks. |
| **Option B: Retain `L1`/`L2`/`L3` terminology** | Causes conceptual confusion with CPU cache hierarchies; obscures chunk residency and ticket ownership semantics. |
| **Option C: Static manual hot queue configuration for all sinks** | Combinatorial explosion of configured parameters; causes operator error and excessive idle chunk allocations. |
| **Option D: Reactive per-event quota re-allocation** | High frequency flapping and chunk-ticket churn under concurrent player load. |
| **Option E: One shared hot pool for every access method, no reserves at all** | Rejected as a universal rule: it cannot serve non-fungible consumers (group subspaces, foreign-world coordinates) and cannot honor externally pinned leases (proxy tokens, ADR-036; keyed personal buckets, ADR-043). Whether it should apply to the general single-target sinks is left open - see *Open Question* above. |
| **Option F: Reflection over lambda bytecode / captured fields to prove predicate equivalence** | Lambdas compile to synthetic `invokedynamic` classes with hidden capture fields; equivalence is not reliably decidable, costs CPU per pulse, and breaks under module encapsulation and remapping. Superseded by transfer-time recheck (decision item 4), which answers the same question by evaluation instead of comparison. |
| **Option F2: Structural criteria comparison (`ValidationSignature` over reified `PredicateKey` values)** | Compares sink criteria field by field. Fails closed on an unidentifiable predicate but **not** on an omitted dimension: a future safety-affecting setting that nobody adds to the record makes two different sinks compare equal, yielding a false equivalence claim in a safety-critical path. Also requires permanent lockstep maintenance with every such setting. Rejected because the resident-chunk recheck it was avoiding is cheaper than the comparison itself. |
| **Option G: Keep bespoke per-sink fill/drain routines** | Each duplicate drain path is an independent opportunity to leak or double-close a reservation (S-002); centralizing disposal in `CacheStage.onDispose` removes the class of bug entirely. |
| **Option H: Concrete `CacheStage` class owning its own queue** | Would force every existing buffer to be replaced rather than wrapped, discarding `LockFreeLocationBuffer`'s ring, its lock-free counters, and its database callback wiring in one non-incremental step. Rejected for an interface with pluggable storage, which is what makes migration phase 3 behavior-preserving. |
| **Option I: Satisfy the join-burst goal by raising the `Cold` -> `Hot` refill rate** | Refill rate is bounded by storage throughput, so the deployments that need it most (slow storage) are exactly the ones that cannot raise it; the knob would be an unactionable instruction. Depth ahead of demand is the controlling variable instead. |

## Consequences

- **Positive:**
  - Standardizes clear, domain-accurate terminology (`Backlog`, `Cold`, `Hot`) across core and addons.
  - Enables addons (`LeafRTPGroupAddon`, future arena/team modules) to construct robust caching pipelines by supplying only domain candidate generators and validation logic.
  - Guarantees S-002 (chunk ticket leak prevention) centrally in `CacheStage.onDispose`, with disposal terminal so no stage can recycle into another during teardown.
  - Zero-I/O transfers between transfer-eligible sinks eliminate chunk thrashing, and eligibility is established by evaluating the destination's own acceptance check rather than by asserting criteria equivalence.
  - Combinatorial superset caching minimizes chunk footprint for variable group sizes.
  - Deletes duplicated fill/drain plumbing across `RegionQueueManager` sinks and the group addon; see *Maintainability Impact* for the measured duplication being retired.
  - No new `rtp-api` surface: addons keep passing plain `Predicate<T>` and there is no opt-in interface to adopt for eligibility.
  - Existing sink topology is preserved, so ADR-023, ADR-036, and ADR-043 keep their observable behavior and their reserves.
- **Negative / Trade-offs:**
  - This is a broad refactor of a safety-critical subsystem; it is staged into six behavior-preserving phases specifically so each step can be regression-tested against the existing ADR-023/028/036/043 suites.
  - Eligibility is decided per entry rather than once per sink pair, so the allocator does bounded per-entry work on each pulse instead of a single cached comparison. Bounded by hot capacity, and it buys the removal of an entire equivalence-proving apparatus.
  - Sinks carrying an extrinsic verifier (claim-plugin lookups, S-003) are only ever transfer-eligible under common provenance, so they rebalance more slowly through promotion gating.
  - `CacheStage` carries a silent and a persistence-visible variant of each movement operation, so an implementer choosing the wrong pair either deletes a live row or leaves an orphan. That is contract surface a single `poll`/`offer` pair would not have; it is covered by dedicated tests rather than by convention.
  - `RingCacheStage.resizeCapacity` allocates and migrates rather than resizing in place, so a resize is O(n) and rounds up to a power of two; the allocator therefore treats capacity targets as approximate and resizes on the pulse rather than per request.
  - Floors are a new operator concept alongside caps and the global budget, and an over-set floor reintroduces idle residency.
  - Dynamic quota allocation requires lightweight per-sink demand counters and hysteresis timestamps.
  - Staleness after a config reload remains unaddressed: entries verified under superseded criteria are not identified as such. Deferred with the epoch-stamping follow-up in decision item 4.
  - Operator-facing config gains `max-cached-chunks` as the primary knob while legacy per-sink caps remain as upper bounds, so documentation and migration notes must explain both.

## References

- [ADR-006](ADR-006-async-queue-pre-generation.md) - Async Queue Pre-Generation
- [ADR-023](ADR-023-login-reserve-cache.md) - Login Reserve Cache
- [ADR-028](ADR-028-l3-backlog-cache.md) - L3 Backlog Cache
- [ADR-016](ADR-016-anvil-subsystem.md) - Anvil Subsystem (`AnvilIoPool`, off-tick screening)
- [ADR-036](ADR-036-network-mode-multi-server-multi-proxy.md) - Network Mode
- [ADR-043](ADR-043-personal-queue-permission-semantics.md) - Personal Queue Permission Semantics
- [ADR-077](ADR-077-multi-format-region-support.md) - Multi-Format Region Support
- `leafrtp-group-addon-ADR-002` - Group Placement Cache Pipeline
- REQ-RTP-S-002 (No permanently force-loaded chunks / ticket hygiene)
- REQ-RTP-S-004 (No silently discarded teleport failures)
- REQ-RTP-S-005 (No chunk loading on main thread)
