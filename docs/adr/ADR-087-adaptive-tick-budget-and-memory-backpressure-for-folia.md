# ADR-087 — Adaptive Tick-Budget, Memory Backpressure, and Dynamic Workload Regulation for Folia Execution

**Status:** Proposed  
**Date:** 2026-09-07  

## Context

On Folia, server execution is partitioned into independent regional threads, each responsible for ticking its own cluster of chunks, entities, and player event pipelines. Unlike monolithic single-threaded engines (Spigot / Paper) where background tasks can be pinned to off-peak ticks or serialized behind the server tick loop:

1. **The 50ms Tick Cliff:** Minecraft operates on a 50ms tick budget ($1000\text{ ms} / 20\text{ ticks}$). When player activity (mob AI, redstone, entity collisions, inventory and combat packets) consumes 40–48ms of a region's tick, any additional background work scheduled onto that region's scheduler—such as candidate vertical adjustment (`vert.adjust`), safety verification, or chunk loads—pushes that region's tick duration beyond 50ms.
2. **Event Delay and Task Starvation:** When a Folia region overruns 50ms, tasks queued in that region's scheduler queue cannot execute within their target tick and spill over into subsequent ticks. This introduces compounding task lag: player network packets (movement, block break/place, combat actions) face multi-tick delays, producing player-perceived rubberbanding, hit delay, and desynchronization.
3. **Region Over-Instantiation and Thread Pool Saturation (The EzRTP Pitfall):** 
   - Folia partitions ticking space dynamically: chunks separated by more than the region merge radius form distinct, independent ticking regions. These regions are scheduled as tasks onto a fixed-size worker thread pool sized according to available CPU cores (`ForkJoinPool` or `ThreadPoolExecutor`).
   - When an RTP plugin scatters unconstrained chunk loads or candidate evaluations across widely dispersed coordinates simultaneously, it instantiates dozens of temporary, disjoint Folia regions—**far exceeding the number of available CPU worker threads**.
   - Because the number of active regions exceeds available worker threads, regions must time-share the thread pool. If several regions block on chunk I/O or heavy generation, worker threads become saturated. The thread pool enters severe lock contention and context-switching thrash, leaving queued regions waiting in thread pool task queues without getting ticked.
   - Under this condition, tasks in out-prioritized regions sit un-ticked for multiple seconds until Folia's thread-starvation emergency recovery / watchdog triggers. In benchmark testing of competing plugins (EzRTP on Folia 26.1 recorded in `docs/FRONT_PAGE_LITE.md`), this region over-instantiation caused **7 watchdog stalls**, with one region remaining unresponsive for **20.4 seconds** before emergency recovery intervened.
4. **Dynamic Environment and Platform Drift:** Server environments are rarely static. Operators frequently swap jar platforms (e.g. migrating from Paper to Folia, or changing hardware/JVM configurations) without updating existing plugin configs (`period`, `cacheCap`, `asyncAllottedTime`). A cost model hardcoded with static unit estimates will misjudge the true platform footprint.
5. **Multi-Regional Allocation Churn:** Background cache replenishment (`RegionCacheTask`, `PregenTask`, and `Region.compute()`) generates candidate coordinates across diverse spatial locations. On Folia, this causes concurrent task dispatch and chunk loading across multiple thread regions simultaneously. In JVMs with elastic collectors (such as G1GC on 16–32 GB heaps), this concurrent instantiation of chunk sections, voxel shapes, and light nibble arrays causes rapid young-gen (Eden) expansion (up to 8+ GB) before garbage collection occurs.
6. **No Control Over Downstream Startup Flags:** Plugin developers cannot dictate JVM startup flags, garbage collector tuning, or heap sizing on downstream servers. Background execution must adapt autonomously to available tick bandwidth and memory capacity out of the box.

## Decision

Introduce an **Adaptive Bandwidth Governor** in `rtp-core` and platform adapters that uses **empirical before-and-after cost differential telemetry** to dynamically regulate background pulse frequency (`period`), contract/expand cache capacities (`cacheCap`), and bound multi-region concurrency to prevent thread starvation.

### 1. Empirical Before/After Differential Telemetry (Zero Static Assumptions)

Rather than relying on static a priori unit estimates, the governor measures actual resource deltas and applies smoothing over periodic sampling windows to make low-overhead, lower-compute actuation decisions:

- **CPU / Execution Delta ($\Delta T_{\text{unit}}$):**
  - Captured around each chunk load and verification unit:
    $$\Delta T_{\text{sample}} = t_{\text{post}} - t_{\text{pre}}$$
  - To prevent transient I/O spikes or individual cold chunk loads from skewing actuation, raw samples are smoothed into an Exponential Weighted Moving Average (EWMA):
    $$\Delta T_{\text{unit}} \leftarrow \alpha \cdot \Delta T_{\text{sample}} + (1 - \alpha) \cdot \Delta T_{\text{unit}} \quad (\alpha = 0.1)$$
  - Records the actual wall-clock / CPU execution cost on the current platform (reflecting whether chunks were warm in OS pagecache, cold on NVMe/HDD, or loaded asynchronously via Folia).
- **Periodic Memory Footprint Sampling ($\Delta M_{\text{unit}}$):**
  - Evaluating memory allocation at the granularity of individual nanosecond operations introduces prohibitive CPU overhead and is distorted by concurrent allocations from other threads.
  - Instead, memory telemetry is gathered **periodically at the batch cycle / pulse level**:
    - The governor samples tenured and total heap state periodically via `HeapPressureMonitor` (e.g. at the conclusion of each batch replenishment pulse or every $N$ completed promotions).
    - Amortized per-unit retained footprint is calculated across the batch window:
      $$\Delta M_{\text{unit}} \leftarrow \beta \cdot \left(\frac{\Delta \text{retainedHeap}_{\text{batch}}}{N_{\text{promotions}}}\right) + (1 - \beta) \cdot \Delta M_{\text{unit}} \quad (\beta = 0.05)$$
    - Negative batch deltas (induced by GC cycles occurring between pulses) are ignored for unit footprint expansion but inform immediate downward pressure relief.
  - This amortized periodic sampling ensures the governor makes reliable capacity-down-adjustment decisions with virtually zero compute overhead.
- **Regional Dispatch Canary ($\Delta t_{\text{dispatch}}$):**
  - Stamped when scheduling onto a regional thread: `scheduledNanos = System.nanoTime()`.
  - Stamped upon thread entry: $\Delta t_{\text{dispatch}} = \text{executionNanos} - \text{scheduledNanos}$.
  - Detects scheduler backlog: $\Delta t_{\text{dispatch}} > 100\text{ ms}$ signals that the target region scheduler is saturated. Raw canary readings are checked immediately for the regional circuit breaker, while a smoothed metric tracks long-term regional health.

### 2. Workload Inference and Budget-Driven Actuation

#### 2.1 Inferred Workload Model
In RTP's pulse architecture (`AsyncTaskProcessing` $\to$ `Region.execute()`):
- Let $R$ be the number of active configured regions requiring replenishment over a cycle.
- Over a baseline cycle of `period` ticks, the engine infers $R$ units of replenishment demand ($R$ regions = $R$ units per `period`, or $\frac{R}{\text{period}}$ units/tick).
- Each unit requires on average $\Delta T_{\text{unit}}$ execution time and retains $\Delta M_{\text{unit}}$ memory.

#### 2.2 Adaptive Pulse Cadence (`period` Expansion)
To respect an operator or dynamically calculated CPU tick budget $B_{\text{cpu}}$ (derived from MSPT headroom: $B_{\text{cpu}} = \max(0, \text{budgetMSPT} - \text{currentMSPT}) \times \text{headroomShare}$):
$$\text{Expected CPU Cost per Tick} = \frac{R \times \Delta T_{\text{unit}}}{\text{period}} \le B_{\text{cpu}}$$

The adaptive pulse cadence scales dynamically:
$$\text{period}_{\text{adaptive}} = \max\left(\text{period}_{\text{config}}, \left\lceil \frac{R \times \Delta T_{\text{unit}}}{B_{\text{cpu}}} \right\rceil \right)$$

- When running on high-end hardware with low $\Delta T_{\text{unit}}$, `period` remains at its configured minimum.
- If the server platform is changed to Folia with high region thread contention, $\Delta T_{\text{unit}}$ naturally increases, automatically expanding `period` to pace work across ticks without choking the server.

#### 2.3 Dynamic Cache Capacity Down-Adjustment (`cacheCap` Floating)
Live hot locations pin chunk tickets (`keep(true)`). To prevent memory exhaustion:
$$R \times \text{cacheCap}_{\text{effective}} \times \Delta M_{\text{unit}} \le B_{\text{mem}}$$

Where $B_{\text{mem}} = \text{maxHeap} \times \text{maxHeapPercent} - \text{retainedBaseHeap}$.

Each region's effective hot cap is dynamically clamped:
$$\text{cacheCap}_{\text{effective}} = \min\left(\text{cacheCap}_{\text{config}}, \max\left(1, \left\lfloor \frac{B_{\text{mem}}}{R \times \Delta M_{\text{unit}}} \right\rfloor \right)\right)$$

- When memory pressure increases (or heap headroom drops), `cacheCap` contracts automatically, shedding chunk tickets back to Folia's chunk unloader.
- When memory is ample, `cacheCap` floats back up to its configured ceiling.

### 3. Anti-Starvation, Tracked Scheduling & Multi-Region Concurrency Bounding

To prevent the EzRTP failure mode (where creating more active Folia regions than available worker threads exhausts the pool, starving un-ticked regions for tens of seconds until emergency recovery):

1. **Zero Untracked Worker Threads (100% Server Visibility):**
   - The plugin strictly avoids creating standalone background thread pools (`new Thread()`, `ForkJoinPool.commonPool()`, unmonitored `Executors`). 
   - All async and regional operations are dispatched exclusively through the server's native scheduler interfaces (`RTPScheduler` $\to$ Folia `AsyncScheduler`, `RegionScheduler`, `GlobalRegionScheduler`).
   - General async processing (`AsyncTaskProcessing`) executes on exactly **one thread at a time** via Folia's `AsyncScheduler`. A region thread is only engaged when a specific chunk coordinate verification is tied in.
   - This ensures the server watchdog, tick monitor, and profilers (Spark, Paper timings) maintain full transparency over every RTP task, and the server can manage thread lifecycles during shutdown or reload.
2. **In-Scheduler Folia Active Region Tracking:**
   - In `FoliaSchedulerImpl`, the scheduler differentiates between **already-existing ticking regions** vs **new disjoint region instantiations**:
     - Tasks scheduled onto an already-existing, active region (e.g. `Bukkit.isOwnedByCurrentRegion` or chunks already resident in an active Folia region) incur zero region-instantiation overhead and simply queue onto that region's thread.
     - Tasks scheduled onto ungenerated or unbuffered chunks create new, temporary ticking regions in Folia's `ThreadedRegionizer`.
   - The scheduler actively tracks the set of coordinates / distinct Folia region hashes currently in flight:
     $$\text{activeRegionCount} = \text{inFlightRegionalTasks.keySet().size()}$$
   - When new disjoint region instantiations approach the concurrency ceiling:
     $$C_{\text{max\_regional\_inflight}} \le \max\left(1, \left\lfloor \frac{\text{availableProcessors}}{2} \right\rfloor\right)$$
     The scheduler defers new candidate regional dispatches until in-flight checks complete, preventing the creation of disjoint Folia regions faster than the server's thread pool can process them.
   - *Why RTP has avoided stalls historically up to ~14 TP/s:* RTP's single-threaded async pulse (`AsyncTaskProcessing`) combined with the Anvil/Linear off-tick prefilter (ADR-016, ADR-077) evaluates candidates directly from disk without touching live chunks or creating Folia regions. Live regional dispatches only occur on the final promotion path. However, when the prefilter is disabled, on custom ungenerated worlds, or under high burst loads, formalizing this in-scheduler gating ensures RTP remains immune to Folia region exhaustion across all deployments.
3. **Spatial Distribution Balance and Candidate Compute Reordering:**
   - In RTP's tiered pipeline (ADR-078), new Folia ticking regions are primarily instantiated at two specific transition points:
     1. **L2 (Cold) $\to$ L1 (Hot) Promotion:** Activating a chunk ticket (`keep(true)`) and binding coordinates to live server state.
     2. **L2 Secondary Live Load Path:** When Anvil/Linear disk pre-filtering (ADR-016, ADR-077) returns `UNKNOWN` (e.g. ungenerated chunks or custom generator pipelines), forcing candidate evaluation to fall back to a live chunk load.
   - *The Spatial Skew Hazard:* If the governor simply paused or restricted evaluations strictly to chunks that are "already loaded" whenever the regional concurrency ceiling is approached, the spatial distribution of generated teleport targets would severely distort. Teleports would cluster unnaturally around existing player clusters, spawn points, or previously loaded chunks, violating the uniform Archimedean spiral distribution guarantee (ADR-001).
   - *Compute Categorization and Reordering:* To maintain spatial distribution balance without violating the regional concurrency budget:
     - Candidate tasks in the pipeline are classified by operation type:
       - **Type A (Zero Region Creation):** Pure mathematical coordinate generation, bitmap lookups, and off-tick Anvil/Linear chunk pre-filtering (ADR-016, ADR-077).
       - **Type B (Existing Region Execution):** Candidate evaluations whose chunk coordinates fall within an already-active Folia ticking region (`Bukkit.isOwnedByCurrentRegion` or active region cluster).
      - **Type C (New Disjoint Region Creation):** Promotions (L2 $\to$ L1) or live fallback reads on unbuffered chunks that will instantiate a new ticking region in Folia's `ThreadedRegionizer`.
    - Rather than halting pipeline progress or dropping candidate coordinates, the scheduler dynamically reorders or paces tasks:
      - When the disjoint region ceiling ($C_{\text{max\_regional\_inflight}}$) is saturated, the pipeline prioritizes Type A (pre-filtering upcoming coordinates in the spiral sequence) and Type B (evaluating coordinates in existing regions), while deferring Type C transitions (leaving them in their current cache tier) until in-flight regions complete and retire.
      - If no Type A or Type B tasks are ready and the regional ceiling remains full, the pipeline temporarily paces/pauses further Type C dispatches rather than selecting non-uniform candidate coordinates. This preserves true Archimedean spiral spatial balance without starving the worker thread pool.
4. **Optimized Spatial Distribution on Count-Bound Pipes:**
   - Rather than naively firing tasks in arbitrary pick order onto the Count-Bound pipe, candidate precomputations are spatially dispersed and load-balanced across distinct regions.
   - If multiple candidates map to the *same* Folia region, they are coalesced and executed sequentially on that region's thread, rather than flooding the region's task queue with competing dispatches.
   - If candidates span different regions, the dispatcher interleaves execution across regional threads with anti-starvation aging ($t_{\text{dormant}}$) so no single region thread experiences a task pile-up.
5. **Natural Bound by Cache Capacity Across Transitions (Zero New Queues):**
   - The deferred generation "backlog" under compute reordering is **not a new queue or data structure**. It is simply the existing candidates already sitting inside the existing cache tiers:
     - **L3 $\to$ L2 (Backlog $\to$ Cold):** Candidates already residing in `queueManager.backlogLocations` awaiting Anvil validation or live secondary load.
     - **L2 $\to$ L1 (Cold $\to$ Hot):** Validated candidates already residing in `queueManager.unkeptLocations` awaiting promotion and chunk ticket activation (`keep(true)`).
   - Because these structures are already strictly bounded by `backlogCacheCap` and `cacheCap` (ADR-028, ADR-078), **the backlog is inherently capacity-bounded by design**.
   - Compute reordering simply selectively polls or paces which entries transition to the next tier:
     - When the disjoint Folia regional ceiling ($C_{\text{max\_regional\_inflight}}$) is saturated, entries requiring new disjoint region creation simply remain in their current tier (L3 or L2).
     - They are **not dropped** if they are already in progress or stored in the cache buffer; they simply wait for in-flight promotions to finish and decrement the counter before their transition is processed.
     - If the destination tier is full, the transition naturally pauses under existing deficit checks (`deficit = cap - (current + inFlight)`).
   - This eliminates any risk of unbounded memory growth or state discard without inventing synthetic queues.
6. **Regional Circuit Breaker:**
   - If a specific region records canary dispatch latency $\Delta t_{\text{dispatch}} > 100\text{ ms}$ or region MSPT $> 45\text{ ms}$, background tasks targeting *that specific region* yield immediately, while independent, unburdened regions continue processing within their allocated budget.

### 4. Elastic Heap Awareness

In `HeapPressureMonitor`, normalize tenured heap measurements against total JVM maximum capacity (`Runtime.getRuntime().maxMemory()`) and enforce an absolute free-memory floor ($> 512\text{ MB}$ or $1\text{ GB}$ free guarantees unpressured state), preventing G1GC's elastic young/old region resizing from triggering false-positive pause alarms on large heaps.

### 5. Operator Visibility and Telemetry Feedback

Silent adaptation risks operator confusion (e.g. wondering why `/rtp info` shows reduced hot counts or longer pulse cadences under heavy server load). The governor communicates behavior changes through transparent, rate-limited operator telemetry:

1. **Structured Console / Audit Logging:**
   - When the governor initiates an adaptation state transition (e.g. `period` stretches beyond configured baseline, `cacheCap` floats down, or a regional circuit breaker triggers), it emits a structured log via `RTP.log(Level.INFO, ...)`:
     ```
     [RTP][Governor] Server load detected (MSPT=46.2ms, tenured=86%). Adapting: period 20 -> 38 ticks, cacheCap 10 -> 4.
     [RTP][Governor] Regional dispatch lag (114ms) on world 'survival' (cx=142, cz=-88). Circuit breaker tripped; holding regional tasks.
     ```
   - When conditions recover and metrics normalize, a recovery log confirms return to configured baselines (`[RTP][Governor] Server headroom recovered. Restoring baseline parameters.`).
   - Log emission is damped by an anti-spam hysteresis cooldown (minimum 30–60 seconds between identical adaptation announcements) to prevent console spam during oscillating load.
2. **Operator Command Visibility (`/rtp info` / `/rtp status`):**
   - The `/rtp info` and administrative diagnostic commands display live governor status:
     - Configured vs. Effective `period` (e.g. `Period: 20 ticks (effective: 35 ticks [CPU budget])`).
     - Configured vs. Effective `cacheCap` (e.g. `Cache Cap: 10 (effective: 5 [memory headroom])`).
     - Active Folia In-Flight Regions vs. Ceiling (e.g. `Regional Concurrency: 3 / 6 in-flight`).
   - Displays clear diagnostic hints if tasks are deferred due to regional ceiling saturation.
3. **Configurable Messages & Metrics Export:**
   - In keeping with **REQ-RTP-F-013** and **REQ-RTP-S-007**, all administrative feedback strings are configurable via `messages.yml`.
   - Exposes governor state via `metrics-api` (`rtp_governor_period_ticks`, `rtp_governor_effective_cache_cap`, `rtp_governor_regional_inflight_count`, `rtp_governor_circuit_breaker_trips`).

### 6. Mechanical Specification & Implementation Layout

To translate these requirements into unambiguous, concrete code without architectural drift:

1. **Component Placement:**
   - **`AdaptiveGovernor` (`rtp-core`):**
     - Sits in `io.github.dailystruggle.rtp.common.selection.region` or `...common.governor`.
     - Maintains smoothed EWMA telemetry ($\Delta T_{\text{unit}}$, $\Delta M_{\text{unit}}$) and evaluates periodic actuation decisions.
     - Sources MSPT headroom via `RTPAPI.getMetricsSnapshot().mspt` (or `MetricsSnapshotRing` aggregate fallback if single-region).
     - Directly computes `effectivePeriod` and `effectiveCacheCap`.
   - **`AsyncTaskProcessing` Actuation Point:**
     - Reads `governor.getEffectivePeriod()` on each iteration rather than re-registering timers with the scheduler, dynamically altering `betweenTime = (period / size) - 1` without scheduler churn.
   - **`RegionQueueManager` / Promotion Actuation Point:**
     - Clamps hot target quota using `min(configuredCacheCap, governor.getEffectiveCacheCap())`.
     - Defers Type C promotions (L2 $\to$ L1 or live fallback chunk loads) when `governor.isRegionalConcurrencySaturated()`.
2. **Platform Regional Concurrency Tracker (`FoliaSchedulerImpl`):**
   - Coordinates in-flight regional tasks keyed by region coordinate bucket: `(world.getUID(), cx >> 3, cz >> 3)` (Folia's standard 8-chunk merge radius) to identify distinct ticking regions.
   - Maintains an atomic permit or in-flight map: `ConcurrentHashMap<RegionBucketKey, AtomicInteger> inFlightRegionalTasks`.
   - Compares active bucket count against $C_{\text{max\_regional\_inflight}} = \max(1, \lfloor \text{availableProcessors} / 2 \rfloor)$.
   - Exposes `boolean isRegionalConcurrencySaturated()` and `boolean isRegionSaturated(world, cx, cz)` to `rtp-core` via `RTPScheduler` SPI methods. For non-Folia platforms, these return `false` ($C = \infty$).
3. **Actuation Hysteresis & Decay Rates:**
   - **Fast Throttle:** Immediately increases `effectivePeriod` and trips circuit breaker upon detecting $\Delta t_{\text{dispatch}} > 100\text{ ms}$ or $\text{MSPT} > 45\text{ ms}$.
   - **Damped Recovery:** Decays `effectivePeriod` back toward `period_config` and restores `cacheCap` gradually over a minimum 30-second stabilization window with zero active canary spikes.
   - **Log Cooldown:** Enforces a 60-second anti-spam cooldown between identical state transition announcements to console.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Fixed Operator Configuration Knobs** | Downstream server operators rarely tune background concurrency, and static limits cannot react to dynamic in-game events (e.g. boss fights, mob farms, raids). |
| **Static A Priori Unit Cost Estimation** | Breaks when platforms change (e.g. swapping Paper for Folia) or when storage I/O profiles differ (NVMe vs spinning disk vs network SAN). Measuring empirical before/after delta self-corrects immediately. |
| **Global Thread-Sleep Throttling** | Sleeping background worker threads does not relieve backpressure on Folia region scheduler queues once tasks have already been dispatched. |
| **Unbounded Regional Dispatch** | Replicates the EzRTP defect on Folia: worker thread pool exhaustion starves un-ticked regions, resulting in multi-second regional freezes and watchdog stalls. |
| **Bypassing Region Schedulers Entirely** | Violates S-005 and Folia thread-safety contracts; reading live world state or adjusting vertical coordinates off-region-thread causes race conditions or explicit `ThreadAccessException`. |

## Consequences

- **Positive:**
  - Adapts automatically when an operator changes server jar platforms (e.g. Paper to Folia) without manual config edits.
  - Prevents background RTP generation from pushing active Folia regions over the 50ms tick cliff.
  - Eliminates the EzRTP multi-second regional starvation and watchdog stalls by bounding concurrent regional dispatches and employing anti-starvation fair scheduling.
  - Automatically contracts cache sizes and stretches pulse intervals during high load or tight memory, protecting server stability.
- **Negative / Trade-offs:**
  - Cache replenishment slows down during server-wide compute or memory strain, temporarily serving teleports from cold/backlog reserves or on-demand resolution.

## References

- Folia Regional Threading and RegionScheduler Contracts
- [ADR-004](ADR-004-countbound-taskpipe-on-folia.md): CountBoundTaskPipe Instead of TimeBoundTaskPipe on Folia Regional Threads
- [ADR-015](ADR-015-stale-chunk-guard-countbound-pipes.md): Stale-Chunk Guard for Count-Bound Pipes
- [ADR-067](ADR-067-automatic-prescan-and-adaptive-rate-control.md): Automatic PRESCAN, Adaptive Scan Rate Control, and .mca-Header Generation Check
- [ADR-078](ADR-078-composable-cache-pipeline-stages.md): Composable Cache Pipeline Stages and Dynamic Hot Quota Allocation
- Competitor Benchmark Analysis: EzRTP Folia Watchdog Stalls (7 stalls, 20.4s freeze) recorded in `docs/FRONT_PAGE_LITE.md`
