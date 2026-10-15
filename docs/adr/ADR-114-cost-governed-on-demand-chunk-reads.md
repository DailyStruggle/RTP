# ADR-114 - Cost-Governed On-Demand Chunk Reads and Native Fallback Admission

**Status:** Proposed
**Date:** 2026-10-14
**Extends:** [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md) (Archimedean spiral 1D mapping), [ADR-009](ADR-009-configurable-spatial-distributions.md) (Configurable spatial distributions), [ADR-016](ADR-016-anvil-subsystem.md) (Anvil read-only subsystem), [ADR-028](ADR-028-l3-backlog-cache.md) (L3 backlog cache order preservation), [ADR-109](ADR-109-measured-cost-probe-first-gate.md) (Measured-cost probe-first gate), [ADR-110](ADR-110-purpose-gated-live-loads.md) (Purpose-gated live loads)
**Related:** [ADR-067](ADR-067-adaptive-scan-rate-and-mca-header-generation-check.md) (MCA header generation check), [ADR-112](ADR-112-per-region-work-division-on-folia.md) (Per-region work division on Folia)

## Context

On-demand location generation executes when a player requires an immediate destination and no pre-cached candidate is available. This occurs during:
1. Empty-queue fallback in `QueueTask` when shared or personal queues are exhausted.
2. Custom biome requests (`/rtp biome:...`).
3. Teleport commands issued by senders with `rtp.unqueued` permission.
4. Synthetic or temporary regions (e.g. world-override regions per ADR-065) that do not drain an asynchronous pre-fill queue.

In all such cases, `LocationGenerator` dispatches `PregenTask` with `LoadPurpose.IMMEDIATE`.

Under `LoadPurpose.IMMEDIATE`, `PregenTask` evaluates `readOnly()` as `false` because `speculative()` is `false`. Consequently, immediate tasks completely bypass `RTPWorld.getChunkIfReadable`, `LiveLoadGate`, and `HeapPressureMonitor`. Instead, candidate chunks route directly into `RTPWorld.getOrLoadChunk`, which follows a static fallback chain: in-memory cache, then Anvil region-file probe (if `shouldPrefilter` is enabled), and finally native server chunk loading (`getChunkAtAsync`).

This unconstrained fallthrough creates a severe cost asymmetry:

- **Off-Thread Region-File Reading (`rtp-anvil`):** Work executes entirely on background worker threads (`AnvilIoPool`). Resource consumption is confined to disk reads, decompression (zlib/LZ4), and palette decoding. If a candidate chunk fails safety validation (ocean, lava, enclosed rock), the decoded data is discarded off-thread without consuming main-thread or regional tick budget, without allocating server level chunk objects, and without registering persistent or transient tickets in the server's chunk provider.
- **Native Server Chunk Loading / Generation:** Work engages the server's primary chunk pipeline: allocating full chunk structures, running the lighting engine, populating entities and block entities, registering tickets with the platform chunk provider, and—on Folia—scheduling work across regional tick threads. When an ungenerated or hazardous chunk is loaded natively and subsequently rejected, all server-side generation work is pure deadweight loss.
- **The Redundant Double-Parse Penalty on Good Chunks:** A player cannot be teleported into an `AnvilChunkView`; the destination chunk must ultimately be loaded as a live `LevelChunk` inside the server's chunk management subsystem. Consequently, whenever a candidate chunk is safe and accepted, parsing it off-thread via `AnvilChunkView` and subsequently loading it natively into the server performs **duplicate decompression, duplicate NBT tag parsing, and duplicate palette decoding**. If candidate acceptance rates are high ($P_{\text{safe}} > 75\%$), executing Anvil pre-filtering prior to native loading is strictly more CPU-intensive and time-consuming than direct native loading.

ADR-109 and ADR-110 left this tradeoff unresolved for on-demand tasks:
- ADR-109 created an EWMA cost equation to balance center-column probes against full decodes, but measured only RTP-side wall-clock time and explicitly rejected pricing native loads because external server-side memory and scheduling costs cannot be observed via RTP wall time alone.
- ADR-110 introduced admission gating (`LiveLoadGate`) and heap backpressure (`HeapPressureMonitor`), but explicitly restricted them to `LoadPurpose.SPECULATIVE` queue refills, leaving `LoadPurpose.IMMEDIATE` tasks with unrestricted access to live chunk loads.

Under high player traffic, empty-queue bursts, or sparse exploration radii, immediate tasks can trigger multiple concurrent native chunk generations, resulting in tick stalls (high MSPT), GC pauses, and watchdog crashes.

### Optimization Objectives & Spatial Distribution Constraints

The system must satisfy two performance objectives while upholding an absolute architectural invariant:

1. **Primary Metric (A) — Minimizing CPU Cost per Teleport ($C_{\text{cpu}}/\text{tp}$):** The total computational energy expended across server worker threads, tick threads, lighting engines, and disk decompression pipelines to deliver a single safe teleportation destination must be minimized. Discarded generation and unhedged native loads represent direct deadweight loss, but duplicate decompression and parsing of safe chunks also constitutes deadweight loss.
2. **Co-Dependent Metric (B) — Minimizing Command-to-Teleport Latency ($T_{\text{cmd}\to\text{tp}}$):** The elapsed wall-clock time between command invocation and player arrival at the safe destination must be minimized, reducing player waiting disutility. Unnecessary sequential pipeline stages on safe chunks directly inflate command latency.
3. **Foundational Invariant (C) — 2D Spatial Distribution Preservation ($f(x, z)$):** Under ADR-001, ADR-009, and ADR-028, candidates must strictly follow the configured 2D probability density function (e.g. `Flat`, `Normal`, `Exponential`). Candidate selection shall not compromise dispersion or cluster players into previously explored pockets.

#### The Deduplication Objective: Heuristic & Empirical Path Selection

Because an off-thread Anvil read cannot replace the final native load for an accepted destination, candidate evaluation must **deduplicate effort** depending on empirical data collection and heuristics:

#### Thread Asymmetry: Main-Thread Tick Budget vs. Async Worker Capacity

A critical structural disparity exists between computational work performed on off-thread worker pools versus the server's main tick loop (or regional tick threads on Folia):

- **Asynchronous Worker Pool (`AnvilIoPool`):** Resource capacity is elastic and scales across available background hardware CPU cores. Work executed off-thread does not consume server tick time, does not stall entity ticking, and imposes zero direct frame or block lag on connected players.
- **Main Thread / Regional Tick Threads (`ServerLevel`):** The tick budget is strictly finite and inelastic (a non-negotiable $50\text{ ms}$ ceiling for 20 TPS). Every millisecond consumed on the main or regional thread is subtracted from the shared budget for entities, block events, redstone, and player physics. Discarding a natively loaded candidate consumes main-thread time for ticket registration, chunk holder synchronization, and entity tracking, creating negative externalities for all players.

To maximize aggregate system welfare, computational expenditure must be decomposed into asynchronous worker core-time ($C_{\text{async}}$) and main/regional tick time ($C_{\text{sync}}$), weighted by the scarcity of the tick budget:
$$\text{Cost}_{\text{effective}} = C_{\text{async}} + W_{\text{tick}}(\text{MSPT}) \cdot C_{\text{sync}}$$
where $W_{\text{tick}}(\text{MSPT})$ is the dynamic shadow price of main-thread tick time:
$$W_{\text{tick}}(\text{MSPT}) = 1 + \alpha + \beta \cdot \left(\frac{\max(0, \text{MSPT} - 35)}{10}\right)^2$$
Under healthy conditions ($\text{MSPT} \le 25\text{ ms}$), $W_{\text{tick}} \approx 5\text{--}10$; under tick distress ($\text{MSPT} > 35\text{ ms}$), $W_{\text{tick}}$ escalates quadratically, reflecting the severe penalty of tick stalls.

#### Empirical Deduplication Equation

Let:
- $C_{\text{file, async}}$ be the off-thread CPU core-time to decode an Anvil chunk ($2\text{--}5\text{ ms}$). Anvil reads consume **zero** main-thread tick time ($C_{\text{file, sync}} = 0$).
- $C_{\text{native, async}}$ be the asynchronous worker time for server-side native chunk I/O and generation ($10\text{--}50\text{ ms}$).
- $C_{\text{native, sync}}$ be the mandatory main-thread or regional tick-thread time for native ticket registration, chunk holder tracking, and level admission ($0.5\text{--}3\text{ ms}$).
- $P_{\text{reject}}$ be the empirical candidate rejection rate (hazards, ocean, void, biome mismatch).
- $P_{\text{safe}} = 1 - P_{\text{reject}}$ be the candidate acceptance rate.

If the system executes an off-thread Anvil pre-filter before native loading:
- For accepted candidates: pays both $C_{\text{file, async}}$ and the full native load ($C_{\text{native, async}} + W_{\text{tick}} \cdot C_{\text{native, sync}}$). The Anvil parse is duplicate work.
- For rejected candidates: pays only $C_{\text{file, async}}$ entirely off-thread. The main tick thread pays **$0\text{ ms}$**.

$$\mathbb{E}[\text{Cost}_{\text{anvil\_first}}] = C_{\text{file, async}} + (1 - P_{\text{reject}}) \cdot \left( C_{\text{native, async}} + W_{\text{tick}}(\text{MSPT}) \cdot C_{\text{native, sync}} \right)$$

If the system routes directly to native loading (bypassing Anvil):
- Both accepted and rejected candidates engage the server chunk pipeline, consuming main-thread tick budget:

$$\mathbb{E}[\text{Cost}_{\text{direct}}] = C_{\text{native, async}} + W_{\text{tick}}(\text{MSPT}) \cdot C_{\text{native, sync}}$$

The net difference in aggregate effective system cost is:
$$\Delta \mathcal{L} = \mathbb{E}[\text{Cost}_{\text{anvil\_first}}] - \mathbb{E}[\text{Cost}_{\text{direct}}] = C_{\text{file, async}} - P_{\text{reject}} \cdot \left( C_{\text{native, async}} + W_{\text{tick}}(\text{MSPT}) \cdot C_{\text{native, sync}} \right)$$

The governor engages off-thread Anvil pre-filtering only when $\Delta \mathcal{L} < 0$, yielding the dynamic threshold:
$$P_{\text{reject}} > \frac{C_{\text{file, async}}}{C_{\text{native, async}} + W_{\text{tick}}(\text{MSPT}) \cdot C_{\text{native, sync}}}$$

- **Healthy Tick State ($\text{MSPT} \le 30\text{ ms}$) in Safe Terrain ($P_{\text{reject}} \le 20\%$):**
  The denominator is moderate. In safe terrain (plains, forests), $P_{\text{reject}}$ falls below the threshold. The system **deduplicates** by bypassing Anvil and loading directly, avoiding duplicate async parsing and minimizing command-to-teleport latency.
- **Tick Stress ($\text{MSPT} > 35\text{ ms}$) or Hazardous Terrain ($P_{\text{reject}} > 25\%$):**
  $W_{\text{tick}}(\text{MSPT})$ inflates the denominator, driving the threshold near zero. Even a small risk of native candidate rejection threatens the tick loop. The system shifts decisively to off-thread Anvil pre-filtering, happily paying the duplicate async worker cycles to completely insulate the main tick loop from discarded candidate churn.

#### The 2D Distribution Breakdown in Naive Generation Skipping

In early proposals, attempting to minimize CPU by skipping ungenerated candidates (via MCA headers) and stepping sequentially along a spiral curve caused severe mathematical defects:
- **Spatial Coupling & Survivor Bias:** Pregenerated chunks on Minecraft servers are not uniformly distributed; they cluster around spawn `(0, 0)`, player travel corridors, and established settlements. Conditioning candidate acceptance on disk presence funnels players into populated or claimed bases, defeating random wilderness dispersal.
- **Absorbing-Boundary Spikes:** Stepping sequentially along a 1D space-filling curve from an ungenerated chunk until hitting a generated one converts candidate selection into a first-hitting-time problem, collapsing the probability mass of entire ungenerated tracts onto the perimeter chunks of generated pockets.
- **Directional Anisotropy & Capture Basins:** Sequential spiral traversal imposes a fixed chirality and radial drift, destroying radial symmetry and allowing small clusters of pregenerated chunks to capture up to $64\times$ their fair spatial share of teleports.

Therefore, on-demand governance must achieve CPU and tick savings without distorting the 2D spatial distribution.

## Decision

1. **Three-Tier Chunk Resolution Hierarchy with Governed Deduplication:**
   On-demand execution (`LoadPurpose.IMMEDIATE`) shall resolve candidates through three explicit tiers governed by empirical cost-outcome heuristics to eliminate duplicate parsing and redundant loading:
   - **Tier 1 (Resident Cache):** Resident chunks (`isChunkLoaded(cx, cz)`) and cached views (`getCachedChunk(key)`) shall be evaluated immediately with zero disk I/O and zero generation overhead.
   - **Tier 2 (Governed Off-Thread Pre-Filter):** When the candidate chunk is present on disk (verified in $<1\ \mu\text{s}$ via `AnvilRegionOccupancyCache`), the decision whether to decode via `AnvilChunkView` off-thread vs. bypass directly to Tier 3 native loading is governed dynamically by `ImmediateLoadGovernor`:
     * **Deduplication Bypass (Direct Native Load):** If empirical candidate rejection is below the thread-weighted threshold ($P_{\text{reject}} \le \frac{C_{\text{file, async}}}{C_{\text{native, async}} + W_{\text{tick}}(\text{MSPT}) \cdot C_{\text{native, sync}}}$) and server tick health is stable ($\text{MSPT} \le 30\text{ ms}$), the task bypasses off-thread Anvil decoding and dispatches directly to Tier 3 native loading. This eliminates the duplicate decompression and NBT parsing penalty on safe chunks, delivering the destination with a single read and minimizing both CPU expenditure and command-to-teleport latency.
     * **Pre-Filter Engagement:** If candidate rejection exceeds the threshold ($P_{\text{reject}} > \frac{C_{\text{file, async}}}{C_{\text{native, async}} + W_{\text{tick}}(\text{MSPT}) \cdot C_{\text{native, sync}}}$), or the query is predicate-constrained, or the server is under tick distress ($\text{MSPT} > 35\text{ ms}$), the candidate is decoded and verified off-thread on `AnvilIoPool`. If rejected, it sheds an expensive discarded native load and protects the main/regional tick loop.
   - **Tier 3 (Cost-Governed Native Load Admission):** Live native chunk loading and generation (for bypass candidates, Anvil-verified safe candidates, or ungenerated chunks) shall proceed under the supervision of the `ImmediateLoadGovernor` concurrency semaphore.

   *Spatial & Deduplication Invariant:* When a candidate coordinate $(cx, cz)$ is drawn from $f(x, z)$, Tier 1 and Tier 2 serve as transparent acceleration and deduplication paths. In safe terrain, bypassing Anvil eliminates duplicate decode overhead; in hazardous terrain, Anvil pre-filtering eliminates deadweight native generation. In all cases, coordinates remain strictly distributed according to $f(x, z)$.

2. **Decoupled Governance: Blind Teleports vs. Constrained Queries:**
   The governance policy shall branch based on whether the command is an unconstrained wilderness teleport or a predicate-constrained query:

   - **Mode A: Blind Wilderness Teleports (`pregeneratedPreference == 0.0`, default):**
     - For standard unconstrained `/rtp`, candidates are drawn from $f(x, z)$.
     - **No Spatial Redirection:** If the sampled candidate $(cx, cz)$ is ungenerated on disk (offset = 0 in header bitmap), it shall **not** be discarded in search of existing disk chunks. Discarding ungenerated chunks would divert wilderness exploration into explored territory.
     - **Governed Deduplication on Generated Chunks:** If the candidate chunk is already generated on disk, the governor evaluates whether candidate rejection rates justify off-thread pre-filtering. Where $P_{\text{reject}}$ is below the dynamic thread-weighted threshold ($P_{\text{reject}} \le \frac{C_{\text{file, async}}}{C_{\text{native, async}} + W_{\text{tick}}(\text{MSPT}) \cdot C_{\text{native, sync}}}$) and tick health is normal, the task bypasses off-thread Anvil decoding and proceeds directly to live native loading, avoiding the duplicate decode penalty on safe chunks. Where rejection rates are high or tick pressure elevates $W_{\text{tick}}(\text{MSPT})$, off-thread Anvil pre-filtering is engaged to discard hazards before native admission and insulate the main/regional tick loop.
     - **Native Generation & Admission:** Ungenerated candidates and deduplicated direct-load candidates are admitted subject to `ImmediateLoadGovernor` in-flight concurrency permits, heap pressure, and tick stability ($\text{MSPT} \le 35\text{ ms}$).
     - If the permit is occupied or the server is under tick distress, the task awaits permit availability within a bounded queue timeout ($T_{\text{wait}} \le 1200\text{ ms}$). If the timeout expires without an available slot, the task completes cleanly with `msgBusy` (S-004, S-007) rather than corrupting the world's 2D landing distribution.

   - **Mode B: Predicate-Constrained Queries (e.g. `/rtp biome:...`):**
     - For filtered queries, candidate acceptance is already constrained by an external predicate (biome, block surface, height).
     - The task queries the MCA region file header (ADR-067 Option B) via `anvil-api` ($<0.1\text{ ms}$ off-thread).
     - If the chunk has a sector offset of zero, it is ungenerated and cannot satisfy the biome filter without full live generation.
     - The task may prune ungenerated candidates off-thread to avoid wasteful generation of rejected biomes, but **must** draw alternative candidates via **Independent Resampling** (Decision 3), never sequential curve traversal.

   - **Mode C: Explicit Operator Preference (`pregeneratedPreference > 0.0`):**
     - When an operator explicitly configures `performance.pregeneratedPreference > 0.0` in `performance.yml`, preferential landing in pregenerated chunks is an authorized policy.
     - To prevent macro-scale clustering (e.g. all players collapsing into spawn), candidate selection shall use **Macro-Bin Stratified Sampling**:
       1. Partition the region into coarse spatial bins (32×32 chunk `.mca` region files, matching ADR-028, ADR-067, and ADR-112).
       2. Sample the target macro-bin according to the configured 2D distribution $P(\text{bin}) = \int_{\text{bin}} f(x, z)\,dx\,dz$.
       3. Within the selected macro-bin, prefer pregenerated safe chunks with probability $p = \text{pregeneratedPreference}$. If no safe pregenerated chunk exists within that bin, or with probability $1 - p$, sample and generate natively within that same bin.
       4. This guarantees macro-scale spatial dispersion matches $f(x, z)$ across the full world border, while capturing Anvil compute savings within the chosen sector.

3. **Independent Resampling Invariant (Prohibition of Sequential Curve Traversal):**
   - On-demand tasks shall **never** advance sequentially ($s, s+1, s+2, \dots$) along an Archimedean spiral or Hilbert curve to find alternative candidates.
   - Sequential traversal creates absorbing-boundary concentrations on the perimeters of generated zones and introduces directional/radial drift.
   - Whenever a candidate is rejected (due to safety hazards, biome mismatch, or macro-bin filtering), the task shall draw a new, independent pseudo-random sample $s_{i+1} \sim f(x, z)$ across the valid domain.

4. **In-Flight Native Generation Bounding (`ImmediateLoadGovernor`):**
   - Live chunk generation for on-demand tasks shall be bounded by an in-flight concurrency semaphore:
     * Maximum 1 concurrent native generation per region.
     * Maximum 2 concurrent native generations server-wide across all unqueued on-demand executions.
   - **Resource Backpressure:** If `HeapPressureMonitor.underPressure()` is true, native generation requests are held in a bounded FIFO wait queue until pressure subsides.
   - **Tick Degradation Damping:** Candidate admission accounts for externalized tick penalties:
     $$\Phi_{\text{tick}}(\text{MSPT}) = \begin{cases} 0 & \text{if } \text{MSPT} \le 35\text{ ms} \\ \gamma \cdot \left(\frac{\text{MSPT} - 35}{10}\right)^2 & \text{if } \text{MSPT} > 35\text{ ms} \end{cases}$$
     When $\Phi_{\text{tick}}(\text{MSPT}) > 0$, concurrent native generation permits are restricted to 1 server-wide, pacing generation to protect the tick loop.

5. **Pipelined Async Execution and Regional Thread Handoff:**
   In alignment with ADR-112:
   - Candidate coordinate selection, MCA header presence checks, Anvil file parsing, and safety pre-evaluations shall remain strictly on asynchronous worker threads (`AnvilIoPool`).
   - Only when a candidate is verified safe (or approved for live generation) shall the task acquire a transient `ChunkReservation` (S-002).
   - On Folia, completion of chunk futures shall dispatch to the destination region thread via `RTPScheduler.runTask(at, ...)` within the regional `syncAllottedTime` token bucket for final arrival verification and player teleportation.

6. **Safety and Fail-Closed Invariants:**
   - **S-001 (Destination Safety):** The live chunk safety check immediately prior to player teleportation remains authoritative; skipping or gating early reads changes only cost, never final safety.
   - **S-002 (Chunk Tickets):** All chunk reservations acquired for immediate loads shall be registered with `MemoryTracker` and released on completion, cancellation, or error.
   - **S-004 (Failure Visibility):** Teleport futures shall always complete. If candidate attempts are exhausted or deferred, the pipeline shall complete with failure feedback (`msgBusy` / `msgFailed`).
   - **S-005 (Async I/O):** Zero synchronous chunk loading on tick threads.

7. **Completion and Verification Criteria:**
   Implementation of ADR-114 shall be deemed complete when the following measurable criteria are met under benchmark verification (`scripts/bench_rtp.py` / acceptance harness):
   - **Criterion A (CPU Cost per TP via Deduplication & Gating):**
     - In high-acceptance terrain ($P_{\text{safe}} > 75\%$): Mean CPU core-time per teleport decreases by eliminating duplicate Anvil parsing on safe chunks, routing directly to a single native load.
     - In hazardous or constrained terrain ($P_{\text{reject}} > 25\%$): Mean CPU core-time decreases by $\ge 70\%$ by shedding discarded native loads via off-thread pre-filtering.
     - In ungenerated terrain: Deadweight live generation of discarded candidates is eliminated via $<1\ \mu\text{s}$ MCA occupancy checks and concurrency admission.
   - **Criterion B (Command-to-Teleport Latency):**
     - In pregenerated terrain: median ($P_{50}$) latency $\le 80\text{ ms}$; 95th percentile ($P_{95}$) latency $\le 250\text{ ms}$.
     - In ungenerated terrain: single-request $P_{95}$ latency $\le 1200\text{ ms}$; burst traffic exhibits bounded queuing without watchdog stalls or thread exhaustion.
   - **Criterion C (2D Spatial Distribution Fidelity):**
     - Across $\ge 10,000$ simulated on-demand teleports, spatial landing distributions shall be evaluated against the configured distribution $f(x, z)$ via 2D Kolmogorov-Smirnov / Chi-Square goodness-of-fit testing.
     - The null hypothesis of distribution equivalence shall not be rejected ($p > 0.05$), confirming zero statistically significant spatial clustering, boundary accumulation, or directional drift.
   - **Criterion D (Tick Stability):** Under a burst of 10 concurrent immediate `/rtp` requests, server tick time degradation shall not exceed $\Delta \text{MSPT} \le 1.5\text{ ms}$, with zero watchdog thread warnings and zero unhandled exceptions.

8. **Observability and Telemetry:**
   - A new row `ImmediateGovernorRow` shall be published in `RTPMetricsExtension`:
     `region`, `mode` (`BLIND_GOVERNED`, `PREDICATE_FILTERED`, `STRATIFIED_PREGEN`), `pSafeFile`, `pSafeNative`, `ewmaFileReadMs`, `ewmaNativeLoadMs`, `ewmaCpuCoreMsPerTp`, `latencyP50Ms`, `latencyP95Ms`, `mcaHeaderSkips`, `throttledGenerations`, `activeGenerations`, `distributionFidelityPValue`.
   - Diagnostic transitions shall be logged at INFO under the tag `[RTP][immediate-gov]`.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Unconditional Anvil decode for all generated chunks | Duplicates decompression, NBT parsing, and palette decoding for every safe chunk; statistically more CPU and time consuming than purely loading when rejection rates are low. |
| Sequential spiral walk to find generated chunks | Introduces first-hitting-time absorbing boundaries, severe spatial clustering at explored borders, and directional drift. |
| Blind spatial rejection of ungenerated chunks | Causes survivor bias; funnels players into previously explored/claimed bases near spawn, defeating random wilderness exploration. |
| Apply ADR-109 pricing directly to native loads | ADR-109 measures only RTP-side wall-clock time, ignoring external server tick drag, scheduling latency, and heap churn. |
| Strict deferral on immediate tasks (ADR-110 `readOnly`) | Causes immediate teleports to fail outright on fresh worlds where no region files exist yet. |
| Unconstrained live load fallthrough (status quo) | Leads to tick collapse, high MSPT spikes, and watchdog timeouts during peak traffic or empty-queue bursts. |
| Mandatory world pregeneration | Imposes major, often prohibitive disk-space costs on server operators and cannot adapt to dynamic world borders. |

## Consequences

- **Positive:** Minimizes CPU expenditure and command latency for on-demand teleports while strictly preserving mathematical 2D spatial distribution invariants; eliminates deadweight chunk generation without funnelling players into populated player bases; bounds server tick degradation during traffic surges; provides verifiable acceptance thresholds for compute, latency, and distribution fidelity.
- **Negative / Trade-offs:** In completely ungenerated worlds where `pregeneratedPreference == 0.0`, teleports must execute native generation under concurrency control, meaning CPU savings in ungenerated terrain are derived from concurrency pacing rather than skipping generation.

## References

- [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md) — Archimedean spiral 1D mapping for location selection.
- [ADR-009](ADR-009-configurable-spatial-distributions.md) — Configurable spatial distributions: Flat, Normal, Exponential.
- [ADR-016](ADR-016-anvil-subsystem.md) — Anvil read-only subsystem (prefilter, backed chunk view).
- [ADR-028](ADR-028-l3-backlog-cache.md) — L3 backlog cache order preservation (distribution invariance contract).
- [ADR-067](ADR-067-adaptive-scan-rate-and-mca-header-generation-check.md) — Automatic PRESCAN, adaptive scan rate control, and `.mca`-header generation check.
- [ADR-109](ADR-109-measured-cost-probe-first-gate.md) — Measured-cost probe-first gate.
- [ADR-110](ADR-110-purpose-gated-live-loads.md) — Purpose-gated live loads.
- [ADR-112](ADR-112-per-region-work-division-on-folia.md) — Per-region work division on Folia.
- `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/PregenTask.java`
- `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/QueueTask.java`
- `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/LocationGenerator.java`
