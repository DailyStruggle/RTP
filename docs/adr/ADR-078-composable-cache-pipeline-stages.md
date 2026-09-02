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
   - Rather than a monolithic 3-tier container class, decompose cache pipelines into discrete, generic, bounded stage buffers:
     ```java
     public class CacheStage<T> implements AutoCloseable {
       private final String name;
       private final int capacity;
       private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
       private final Consumer<T> onEvict; // e.g. AutoCloseable::close for Hot stage

       public Optional<T> poll() { ... }
       public boolean offer(T item) { ... }
       public void close() { ... }
     }
     ```
   - Stages are decoupled from fixed tier counts, enabling arbitrary pipeline topologies (e.g., 2-stage `Cold -> Hot`, 3-stage `Backlog -> Cold -> Hot`, or branching sinks).
   - Stages are connected by asynchronous promotion transitions (`StageTransition<From, To>`):
     ```java
     @FunctionalInterface
     public interface StageTransition<From, To> {
       CompletableFuture<Optional<To>> promote(From source);
     }
     ```

3. **Branching Architecture: Cold Inventory and Alternate Hot Sinks**:
   - The `Cold` stage acts as the shared, low-overhead inventory of verified coordinates.
   - Specialized hot pools (`loginLocations`, `networkKeptLocations`, `personalBuckets`, `groupPresets`) branch off the `Cold` stage as independent hot sinks.
   - All hot sinks adhere to the same lifecycle invariant (S-002): upon disabling, draining, or eviction, chunk tickets are deterministically closed, and bare coordinates are recycled back into `Cold`.

4. **Criteria Fungibility, Validation Signatures, and Zero-I/O Transfers**:
   - **Validation Equivalence Contract (`ValidationSignature`):** Validation methods across sinks are identical if and only if they match across target scope (world + shape bounds), vertical geometry (`VerticalAdjustor` + elevation tolerance), block/biome rules, and active verifiers.
   - **Reified Identifiable Predicates (`IdentifiablePredicate` in `rtp-api`):** To avoid unreliable, expensive reflection over opaque Java lambdas, predicates optionally implement `IdentifiablePredicate<T>` (yielding an immutable `PredicateKey(type, parameters)`). Comparing predicate chains executes via deterministic $O(N)$ set-equality checks without reflection.
   - **Backward Compatibility:** Raw `Predicate<T>` instances passed by legacy addons default to opaque non-fungible descriptors without breaking binary compatibility or changing existing `rtp-api` method signatures.
   - **Fungible Hot Sinks (Identical Criteria):** Sinks sharing identical criteria (or topological parity sharing the same upstream `Cold` instance, e.g. General Hot `/rtp` and Login Hot `loginLocations`) form a **Fungible Hot Pool**. Re-allocating capacity between them is an $O(1)$ pointer transfer of the active `ChunkReservation` with **zero chunk load/unload cycles** and zero chunk thrashing.
   - **Non-Fungible Sinks (Different Criteria / Shapes):** Sinks with distinct shapes or spatial criteria (e.g. single `/rtp` vs multi-chunk group presets) cannot swap active tickets directly. Rebalancing non-fungible sinks shall never evict active hot chunks; it shall adjust the **promotion rate from their respective Cold queues**.

5. **Combinatorial Subsumption (Superset Caching)**:
   - For multi-target / group placements, hot caches shall store maximal-capacity subspaces ($N_{\max}$) for each shape profile. Because a safe subspace of size $N$ satisfies any incoming request of size $k \le N$, superset caching eliminates cache partition fragmentation and maximizes hit rates without additional chunk allocations.

6. **Periodic Region Compute-Tick Re-Allocation**:
   - Dynamic quota evaluation across sinks shall execute on the periodic region compute pulse (`Region.execute()` or addon async pulse), rather than reactively on every player event.
   - On each pulse:
     1. Evaluate demand/usage counters across registered sinks.
     2. Balance fungible hot entries via direct queue transfer.
     3. Refill under-quota sinks via gated async promotion from their respective Cold queues.
   - Hysteresis: a hot entry shall not be evicted for rebalancing purposes before a minimum residence time (default 60s). Quota changes shall be applied as promotion gating, not eviction.

7. **Hot Topology: Shared Pool with a Guaranteed Floor for Fungible Sinks**:
   - Criteria-fungible consumers (identical `ValidationSignature`, or the same upstream `Cold` instance by reference) shall be served from **one shared hot stage**, with each named sink retaining a small operator-configurable guaranteed floor rather than a full pre-partitioned buffer.
   - The floor is a minimum, not an allotment: capacity above the sum of floors is pooled, and floor satisfaction is achieved by zero-I/O pointer transfer between fungible sinks, never by chunk load/unload.
   - ADR-023's guarantee is a **burst-throughput** guarantee (an instantaneous fill to player cap shall be fully served within 5 seconds), not a per-join residency guarantee. It shall be carried by **prepared depth**, not by refill rate: the pipeline shall accumulate, during idle and low-demand periods, at least one prepared location per expected burst arrival, held predominantly in `Cold` (verified coordinates, zero resident chunks, therefore cheap to stockpile deeply). Refill rate is an upper bound on recovery after a burst, not a remedy for slow storage - on slow storage the achievable rate is low by definition, which is precisely why depth must be built ahead of demand. Rationale under *Decision: Shared Pool with Floor vs. Per-Access-Method Reservation* below.
   - Non-fungible sinks (distinct signature or multi-chunk footprint) and externally leased entries (proxy tokens, ADR-036; keyed personal buckets, ADR-043) keep their own stages; pooling does not apply to them.

8. **Placement and Module Boundaries**:
   - `rtp-api`: `IdentifiablePredicate<T>`, `PredicateKey`, `ValidationSignature` (value types only; no platform imports, no queue implementation).
   - `rtp-core`: `CacheStage<T>`, `KeyedCacheStage<K, T>`, `StageTransition<From, To>`, `CachePipeline`, `HotSink`, `HotBudgetAllocator`. Generic and placement-agnostic - no group/addon domain types.
   - Addons (`LeafRTPGroupAddon` and future modules): domain entry types, candidate suppliers, screening transitions, footprint-ticket promotion transitions. Addons shall not add fields to `RegionQueueManager`, `RegionSettings`, or `Region`.

## Detailed Design

### Stage Contracts

```java
// rtp-core: bounded buffer with deterministic disposal.
public class CacheStage<T> implements AutoCloseable {
  public CacheStage(String name, int capacity, Consumer<T> onEvict);

  public Optional<T> poll();          // removes; caller takes ownership of any ticket
  public boolean offer(T item);       // on overflow: onEvict.accept(item), returns false
  public int size();
  public int capacity();
  public void resizeCapacity(int newCapacity); // surplus entries evicted via onEvict
  public void close();                // drains, applying onEvict to every entry
}
```

Invariants:
- `onEvict` is the single disposal path. For hot stages it is `ChunkReservation::close` (or a composite closer) followed by re-offer of the bare coordinate to the owning `Cold` stage. Nothing else in the codebase closes a reservation held by a stage.
- `poll()` transfers ownership. A polled entry is no longer the stage's responsibility; the consumer must dispatch or dispose it (S-002, S-004).
- `offer()` never blocks and never loses a ticket: overflow disposes via `onEvict` rather than dropping silently.
- Capacity is advisory-bounded, not strict-locked: sizes are read from lock-free counters, so transient overshoot by concurrent producers is permitted and reconciled on the next pulse.

```java
// rtp-core: an async promotion edge between two stages.
@FunctionalInterface
public interface StageTransition<From, To> {
  CompletableFuture<Optional<To>> promote(From source);
}
```

Transition rules:
- Transitions run off the main thread (S-005). Screening transitions perform region-file reads through `AnvilIoPool` (ADR-016/ADR-077); promotion transitions perform async chunk reservation.
- A transition returning `Optional.empty()` means "candidate rejected" and shall record a structured reason; it shall never swallow an exception (S-004). Exceptional completion is logged via `RTP.log(Level.WARNING, msg, e)` and the source entry is disposed.
- Promotion is idempotent per entry: an entry is removed from the source stage before the transition starts, so a failed promotion returns the bare coordinate to `Cold` rather than duplicating it.

```java
// rtp-core: keyed partition for per-UUID or per-profile buckets.
public class KeyedCacheStage<K, T> implements AutoCloseable {
  public CacheStage<T> open(K key, int capacity);  // idempotent
  public void closeKey(K key);                     // drains that partition via onEvict
  public Optional<T> poll(K key);
}
```

`KeyedCacheStage<UUID, RTPLocation>` replaces the bespoke `perPlayerLocationQueue` handling (ADR-043); `KeyedCacheStage<String, GroupSubspace>` replaces the profile-keyed maps in the group addon.

### Hot Sink Contract

```java
// rtp-core
public interface HotSink<T> {
  String name();
  ValidationSignature signature();
  CacheStage<T> stage();
  CacheStage<?> coldSource();          // upstream inventory, used for fungibility by reference
  int chunkCostPerEntry();             // footprint size; 1 for single-target
  long demandWeight();                 // EWMA request counter, updated on pulse
}
```

Fungibility test, in order of cost:
1. `a.coldSource() == b.coldSource()` - identical by topology, no further checks.
2. `a.signature().equals(b.signature())` - identical by structural value comparison.
3. `a.signature().subsumes(b.signature())` - one-directional: a verified superset footprint satisfies the smaller request, not vice versa.
4. Otherwise non-fungible.

`ValidationSignature` is a record over: world identity, shape id plus shape parameters, vertical adjustor type with `minY`/`maxY`/`elevationTolerance`, biome and block rule sets, the set of `PredicateKey` values for active verifiers, and slot cardinality plus `minSeparation`. Any opaque `PredicateKey` (`opaque:` / `legacy:` prefix from an unidentified lambda) forces the whole signature to compare unequal to everything but itself - fail-closed, never a false fungibility claim.

### Budget Allocation

- The operator configures a single global ceiling of resident cached chunks per region (`max-cached-chunks`), replacing independent per-sink caps as the primary tuning knob. Existing per-sink caps remain honored as upper bounds.
- On each region compute pulse `HotBudgetAllocator` computes each sink's target as proportional to `demandWeight() / chunkCostPerEntry()`, normalized to the ceiling, with a configurable floor per sink so a rarely used sink still resolves from `Cold` promptly.
- Floors are honored before proportional shares and are bounded by `sum(floors) <= max-cached-chunks`; a fungible sink below its floor is topped up by entry transfer from a sibling above its share, never by opening a new reservation while the budget is saturated.
- The burst guarantee is budgeted as **prepared depth**, expressed as a target `Cold` depth per region (`prepared-locations` target, defaulted from player cap) that background fill accumulates whenever demand is below capacity. `Cold` depth is not charged against `max-cached-chunks` because a `Cold` entry holds no reservation; only `Hot` residency is.
- A refill-rate knob caps how many `Cold` -> `Hot` promotions may be in flight per pulse. It exists to bound I/O churn and to describe post-burst recovery speed; it is explicitly **not** the control that satisfies ADR-023, since its ceiling is set by storage throughput and cannot be raised past it. Slow storage is compensated by deeper prepared `Cold` depth and longer accumulation, not by a higher rate.
- Applying targets: fungible sinks exchange entries directly (O(1) queue transfer, zero chunk I/O); non-fungible sinks receive changed promotion gates only.

### Decision: Shared Pool with Floor vs. Per-Access-Method Reservation

The alternative to branching sinks is a single hot pool that every access method draws from, with no reservation per access method. Both are viable on top of the contracts above; the comparison below is what drove decision item 7.

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

What resolved the general `/rtp` vs. login reserve (ADR-023) case:

1. **Guarantee shape is depth, not rate.** ADR-023's intent is that an instantaneous fill to player cap (for example 0 -> 200) is fully served within 5 seconds. Serving that from live selection is impossible on slow storage, so the guarantee is satisfied by having the work already done before the burst arrives: prepared, pre-verified locations of depth at least the expected burst size. The 5s figure bounds how fast prepared entries are handed out, not how fast they are produced.
2. **Rate is not an actionable operator remedy.** Promotion cost equals one asynchronous chunk load plus in-memory block checks, so the achievable refill rate is a property of the storage, world, and platform (Folia region threading vs. single-threaded Paper), not a setting. Slow storage *is* a low refill-rate ceiling; instructing such an operator to raise the rate is vacuous. What a slow-storage deployment can do is accumulate depth over a longer idle window, which is affordable precisely because prepared `Cold` entries are coordinates only and hold no chunk tickets.
3. **Depth belongs in `Cold`, not in a `Hot` reserve.** The storage-bound work is verification, and that is already complete for a `Cold` entry; what remains is the destination chunk load, which the teleport itself requires under any topology. Burst coverage is therefore stockpiled cheaply and deeply in `Cold`, while `Hot` depth buys only the removal of that last unavoidable load and is the part that must stay small and budgeted.
4. **Hot reserves are frequently idle in practice.** The login *hot* reserve is often observed non-empty and idle, which is wasted chunk residency; that is why its depth is already implicitly clamped to server scale and server software. Pooling that idle hot capacity raises `/rtp` hit rate at no cost to joins, because join coverage comes from `Cold` depth rather than from that reserve.
5. **A hot floor covers the residual risk.** The only thing a shared hot pool loses is protection against `/rtp` drain coinciding with a join burst. A small per-sink floor restores it without pre-partitioning: because the sinks are fungible, satisfying a floor is an O(1) entry transfer at zero chunk I/O, and the floor collapses toward zero when joins are rare.

Hence decision item 7: shared hot pool, per-sink hot floor, and burst capacity carried by prepared `Cold` depth. Implied follow-ups: `HotBudgetAllocator` exposes a prepared-depth target alongside `max-cached-chunks`, with refill rate demoted to an I/O-churn and recovery-speed bound; and verification asserts that depth is present *before* the burst rather than that a rate can be raised *during* it.

### Migration Phases

Behavior-preserving, one phase per change set, each independently buildable and testable:

1. **Introduce contracts (additive, no behavior change).** Add `IdentifiablePredicate`, `PredicateKey`, `ValidationSignature` to `rtp-api`; `CacheStage`, `KeyedCacheStage`, `StageTransition`, `HotSink` to `rtp-core`. Nothing consumes them yet.
2. **Nomenclature pass.** Rename internal identifiers, javadoc, and config comments from `L1`/`L2`/`L3` to `hot`/`cold`/`backlog`. Public config keys and database column names are not renamed in this phase; operator-facing renames follow the standard migration/version-bump rules.
3. **Wrap existing buffers.** Back `keptLocations`, `unkeptLocations`, and `backlogLocations` with `CacheStage` disposal semantics while keeping the existing `LockFreeLocationBuffer` storage and database callbacks. Field names and public accessors are preserved.
4. **Centralize sink lifecycle.** Re-express `loginLocations`, `networkKeptLocations`, and `perPlayerLocationQueue` as `HotSink` registrations sharing the region's `Cold` stage. Delete the duplicated drain routines in favor of `CacheStage.close()`. ADR-023, ADR-036, and ADR-043 observable behavior is unchanged.
5. **Collapse fungible general sinks into the shared pool with floors.** Apply decision item 7: `loginLocations` becomes a floor-holding `HotSink` view over the shared general hot stage instead of a pre-partitioned buffer, with floor satisfaction by pointer transfer only. This phase shall not land before the prepared-depth target is in place, since it is `Cold` depth - not the removed hot reserve - that carries ADR-023; behavior is validated by the depth test below.
6. **Introduce the allocator.** `HotBudgetAllocator` on the region compute pulse, defaulting to the current static caps so existing deployments see no change until `max-cached-chunks` is set. Prepared-depth accumulation defaults to the existing effective queue sizing so no deployment loses depth.
7. **Port the group addon.** `GroupSubspaceCache` and `GroupCacheWorker` are re-expressed on `CacheStage` / `KeyedCacheStage` / `StageTransition`, deleting the addon's duplicated queue plumbing.

### Verification Plan

- `CacheStage` unit tests: overflow disposes exactly once, `close()` disposes every entry, `poll()` transfers ownership, `resizeCapacity` down-sizing disposes the surplus, concurrent producer/consumer never double-disposes.
- Ticket-hygiene tests using a counting mock `ChunkReservation`: opened count equals closed count after every fill/drain/clear/shutdown/reload sequence, including failed and exceptionally-completed transitions.
- Signature tests: identical configs compare equal; any differing dimension compares unequal; opaque predicate keys never compare equal across instances; `subsumes` is asymmetric.
- Regression tests: existing login reserve (ADR-023), network reservation (ADR-036), personal queue (ADR-043), and backlog (ADR-028) suites shall pass unmodified through phases 2-5.
- Allocator tests: quota change under hysteresis performs zero reservation open/close operations for fungible sinks; non-fungible rebalance never closes an active reservation; a floor deficit on a fungible sink is satisfied by entry transfer with zero reservation open/close operations.
- ADR-023 depth test: with `Cold` accumulated to the prepared-depth target and the hot stage forcibly drained, a simultaneous join burst equal to the configured player cap shall be fully served within the 5s window on both a region-threaded (Folia) and a single-threaded (Paper) harness, including under an artificially slowed chunk-I/O harness where live selection cannot keep up. The negative case shall also be asserted: with `Cold` depth starved, the burst is not served - proving depth, not refill rate, is the controlling variable.
- Accumulation test: background fill restores prepared `Cold` depth toward target during idle periods without exceeding the refill-rate I/O bound, and prepared `Cold` depth is not charged against `max-cached-chunks`.
- Each phase ends with a full multi-module build.

## Alternatives Considered

| Alternative | Why Rejected |
| :--- | :--- |
| **Option A: Monolithic `TieredCache<C, V, H>` class** | Hardcodes a rigid 3-tier structure; fails to accommodate 2-stage caches, partitioned personal buckets, or multiple specialized hot sinks. |
| **Option B: Retain `L1`/`L2`/`L3` terminology** | Causes conceptual confusion with CPU cache hierarchies; obscures chunk residency and ticket ownership semantics. |
| **Option C: Static manual hot queue configuration for all sinks** | Combinatorial explosion of configured parameters; causes operator error and excessive idle chunk allocations. |
| **Option D: Reactive per-event quota re-allocation** | High frequency flapping and chunk-ticket churn under concurrent player load. |
| **Option E: One shared hot pool for every access method, no reserves at all** | Adopted *within* a fungibility class, but with a per-sink floor (decision item 7) so `/rtp` drain cannot coincide with a join burst. Rejected as a universal rule: it cannot serve non-fungible consumers (group subspaces, foreign-world coordinates) and cannot honor externally pinned leases (proxy tokens, ADR-036; keyed personal buckets, ADR-043). |
| **Option F: Reflection over lambda bytecode / captured fields to prove predicate equivalence** | Lambdas compile to synthetic `invokedynamic` classes with hidden capture fields; equivalence is not reliably decidable, costs CPU per pulse, and breaks under module encapsulation and remapping. Replaced by reified `PredicateKey`. |
| **Option G: Keep bespoke per-sink fill/drain routines** | Each duplicate drain path is an independent opportunity to leak or double-close a reservation (S-002); centralizing disposal in `CacheStage.onEvict` removes the class of bug entirely. |
| **Option H: Retain a dedicated pre-partitioned *hot* login reserve sized for join bursts** | A *hot* reserve deep enough to cover an instantaneous fill to player cap is not affordable in resident chunks, and a shallow one does not change the burst outcome; it is also frequently observed idle. Superseded by deep prepared `Cold` depth + shared hot pool + per-sink hot floor. The prepared-location guarantee itself is retained, relocated from `Hot` to `Cold`. |
| **Option I: Satisfy the join-burst guarantee by raising the `Cold` -> `Hot` refill rate** | Refill rate is bounded by storage throughput, so the deployments that need it most (slow storage) are exactly the ones that cannot raise it; the knob would be an unactionable instruction. Burst coverage is instead pre-accumulated as prepared depth while demand is low. |

## Consequences

- **Positive:**
  - Standardizes clear, domain-accurate terminology (`Backlog`, `Cold`, `Hot`) across core and addons.
  - Enables addons (`LeafRTPGroupAddon`, future arena/team modules) to construct robust caching pipelines by supplying only domain candidate generators and validation logic.
  - Guarantees S-002 (chunk ticket leak prevention) centrally in `CacheStage.onEvict`.
  - Zero-I/O transfers between fungible sinks eliminate chunk thrashing.
  - Combinatorial superset caching minimizes chunk footprint for variable group sizes.
  - Deletes duplicated fill/drain plumbing across `RegionQueueManager` sinks and the group addon.
  - Pooling the frequently idle login hot reserve lowers resident chunk count and raises `/rtp` hit rate without weakening the join guarantee, which prepared `Cold` depth plus the per-sink hot floor now carries explicitly.
  - Burst capacity becomes storage-independent at serve time: prepared depth is accumulated while demand is low, so a slow-storage deployment is never asked to do work during the burst that its storage cannot sustain.
- **Negative / Trade-offs:**
  - This is a broad refactor of a safety-critical subsystem; it is staged into seven behavior-preserving phases specifically so each step can be regression-tested against the existing ADR-023/028/036/043 suites.
  - ADR-023's guarantee moves from "hot reserve depth" to "prepared `Cold` depth", so it holds only once accumulation has caught up after a restart or reload; that warm-up window becomes an operator-visible property, and slow storage lengthens the window rather than degrading the served burst.
  - Floors are a new operator concept alongside caps and the global budget, and an over-set floor reintroduces the idle residency that pooling removes.
  - Dynamic quota allocation requires lightweight per-sink demand counters and hysteresis timestamps.
  - Addons using raw lambdas as verifiers lose fungibility until they adopt `IdentifiablePredicate`; behavior is correct either way, only shared-pool eligibility differs.
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
