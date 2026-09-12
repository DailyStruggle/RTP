# ADR-092 — Hybrid Bit-Packed and 16-Bit Run Container Hazard Table

- **Status:** Proposed
- **Date:** 2026-09-09
- **Deciders:** Maintainer, Architectural Review
- **Consulted:** ADR-079, ADR-081, ADR-085, ADR-088, ADR-089
- **Informed:** Core Contributors, Server Operators

---

### Context & Problem Statement

In `rtp-core`, spatial invalidity (safety hazards, water, lava, void, obstacles) is learned by background scans and runtime pipeline probes. Currently, `MemoryShape` persists and manages this state through monolithic flat arrays (`badKeysCache`, `badLengthsCache`, `badCauseCache`, `badExpiryCache`).

Empirical audit of live production server datasets (`default.bin` and `test.bin` from Minecraft Overworld saves spanning millions of chunks) revealed three critical architectural bottlenecks:

1. **Monolithic Storage Inefficiency:**
   Every single hazard entry is serialized as `key (4/8 bytes) + delta (4/8 bytes) + cause (1 byte) + expiresAt (8 bytes) = 17 to 25 bytes per run`. In the live `test.bin` dataset ($1.90\text{ million}$ bad chunks across $33,647$ runs), **$100.0\%$ of the recorded runs were permanent geographic terrain hazards** (`expiresAt == 0L`, `cause == FailTypes.misc`). Over $302\text{ KB}$ ($> 53\%$ of the file section) was squandered serializing repeated zero timestamps and default cause bytes.
2. **Cold-Start Point Fragmentation (Day-Zero Problem):**
   When an operator installs the plugin on a server without running pre-scans, bad chunks are discovered one by one at runtime as players execute `/rtp`. Under pure Run-Length Encoding (RLE), each isolated single-chunk hazard ($len = 1$) fragments the run table, triggering $O(N)$ array insertions, sorting passes, and lock contention on every discovered obstacle.
3. **Terrain Bimodality (Micro-Noise vs. Macro-Continents):**
   Minecraft terrain has a bimodal distribution:
   - Coastlines, lakes, and trees produce thousands of micro-runs ($< 8$ chunks), which causes severe run table fragmentation if unmerged.
   - Deep oceans and continents produce massive continuous runs ($> 64$ chunks, accounting for $63.5\%$ of all physical hazard volume).
   A pure bitset is wasteful on large continents ($128\text{ bytes}$ per region even for 2 ocean runs), while a pure RLE table fragments and balloons on unmerged micro-noise.

---

### Decision

We adopt a **Two-Tier Layered Hazard Architecture** backed by a **Hybrid Bit-Packed and 16-Bit Container Table (`HybridHazardTable`)**:

#### 1. Layer Separation: Permanent Terrain Base vs. Ephemeral TTL Delta
- **Layer 1: Permanent Base Table (Geographic Ground Truth):**
  Stores permanent terrain hazards (oceans, deep water, lava, void). Carries **zero timestamps** and **zero cause metadata**. Stored as high-density 16-bit local run containers ($4$ bytes per run) or 1-bit-per-chunk bitsets.
- **Layer 2: Ephemeral TTL Delta Table (Dynamic Probation / Temp Locks):**
  Stores only active temporary invalidations (probationary re-checks per ADR-079, temporary claim locks, player builds). Carries explicit `(chunkKey, causeOrdinal, expiryEpoch)` tuples. Represents $< 0.1\%$ of locations and reaps expired entries in $O(1)$.

#### 2. The 1:1 Anvil Bin Hierarchy & "Bin of Bins" Macro-Sectors
- **1 Memory Bin = 1 Anvil Region File ($32 \times 32$ chunks = $1,024$ chunks):**
  Matches `MemoryShape` ($P=32$) and Minecraft `.mca` files 1:1.
- **Discarded Bins = "Full Bins" (`covered == 1024`):**
  When 16/16 candidate trials fail (or solid deep ocean is detected), the bin is marked `covered = 1024` (a "Full Bin") with zero internal runs or bitmasks. Candidate selection (`resolveAccumulate`) and file I/O skip the bin in $O(1)$ without reopening the file.
- **Hierarchical Macro-Sectors ("Bin of Bins"):**
  For massive scale ($R > 1024$), $32 \times 32$ Anvil bins group into Level-2 Macro-Sectors ($1,024$ MCA files = $1,048,576$ chunks). Solid ocean sectors collapse to a 1-byte flag, ensuring the world directory consumes $< 1\text{ MB}$ of RAM. Mixed bins use 16-bit RLE runs ($4$ bytes/run) or 16-long bitmasks ($128$ bytes max).

#### 3. Bounded Adaptive Thresholding
A 64K block automatically selects the most compact representation:
$$\text{ContainerType} = \begin{cases} \text{SolidLand} & \text{if } \text{badCount} == 0 \\ \text{SolidHazard} & \text{if } \text{badCount} == 65536 \\ \text{RunContainer} & \text{if } \text{runCount} \le 2048 \text{ (cost } \le 8192\text{ bytes)} \\ \text{BitmaskContainer} & \text{if } \text{runCount} > 2048 \text{ (hard cap at } 8192\text{ bytes)} \end{cases}$$
This provides a hard mathematical guarantee: **no 64K block can ever exceed 8 KB in RAM or on disk**, regardless of hostile griefing or fractal noise.

#### 4. Morphological Gap-Bridging Tuning (`minGap = 1, maxGap = 32`)
In `MemoryShape.computeAdmissibleGap()`, the lower bridging bound is reduced from $8$ down to $1$ while preserving the strict upper ceiling of $32$:
$$\text{admissibleGap} = \text{clamp}(1, 32, \min(\text{leftLength}, \text{rightLength}))$$
- Empirical verification on 256 MCA files demonstrates that reducing `minGap` to 1 restores **$7,495$ safe chunks (+6.05% of world territory / 1.9 million square blocks)** of scenic beaches, islands, and riverbanks from being blacklisted as collateral damage, at a nominal cost of $+7.3\text{ KB}$ under the hybrid model.

---

### Empirical Measurements & Verification

Direct empirical benchmarks executed on real Minecraft Overworld `.mca` region saves:

#### 1. Scaled Real Overworld Save ($1,024$ Region Files / $541,696$ Chunks):
- **Baseline Shipped `.bin`:** $390,613\text{ bytes}$ ($390.6\text{ KB}$).
- **Pure Bitpacked (128B max/region):** $65,681\text{ bytes}$ ($65.7\text{ KB}$).
- **Custom 16-bit RLE (Raw Unmerged Res 0):** $91,960\text{ bytes}$ ($92.0\text{ KB}$).
- **Hybrid Adaptive (Merged `minGap = 1`):** **$53,549\text{ bytes}$ ($53.5\text{ KB}$)** — **86.3% size reduction**, restoring $7,495$ safe chunks while beating pure bitpacked by $12\text{ KB}$!
- **Hybrid Adaptive (Merged `minGap = 8`):** **$24,105\text{ bytes}$ ($24.1\text{ KB}$)** — **93.8% size reduction (2.7x smaller than pure bitpacked)**!

#### 2. Query Latency ($1,000,000$ Point Lookups):
- **Current Global Flat Array:** $112.6\text{ ns / lookup}$ (global binary search over $33\text{k}$ elements).
- **Custom 16-bit Container:** **$64.0\text{ ns / lookup}$ (1.76x speedup)** due to $O(1)$ container indexing + local L1-cached binary search.
- **Bitmask Word Query:** **$< 2.0\text{ ns / lookup}$** (single shift and mask in CPU registers).

---

### Criteria Matrix

| Criterion | Baseline Flat Arrays | Pure Bitpacked | Pure 16-Bit RLE | Hybrid Adaptive (ADR-092) |
|:---|:---:|:---:|:---:|:---:|
| **Storage Density (Merged)** | Poor ($17\text{--}25$ B/run) | Moderate ($128$ B/region) | Excellent ($4$ B/run) | **Best ($4$ B/run or $8$ KB cap)** |
| **Cold-Start Insert Cost** | $O(N)$ array copy | $O(1)$ bit-set ($< 2\text{ ns}$) | $O(N)$ array copy | **$O(1)$ bit-set ($< 2\text{ ns}$)** |
| **Noise-Bomb Resistance** | Fails (run explosion) | Immune ($128$ B cap) | Fails (run explosion) | **Immune ($8$ KB hard cap)** |
| **Accumulate Mode Parity** | Native $O(\log N)$ | Slow linear scan | Native $O(\log N)$ | **Native $O(\log N)$ via prefix sums** |
| **Scenic Land Preservation** | Sacrifices land | Preserves land | Preserves land | **Preserves land (`minGap = 1`)** |
| **External Dependencies** | Zero | Zero | Zero | **Zero (Pure primitive Java)** |

---

### Supplement (2026-09-10): period-modulated container vs the L3 dyadic usage layout

A fourth container was prototyped and measured against the **real shipped L3 selection path** (`SquareOptimizedDualLayer.selectL3Candidate`: dyadic stride + keyed Feistel PRP), not against terrain. The generator drove non-repeating marks into 1024-chunk sub-bins from the first teleports (1k) through near-complete exploration (500k), on a range of 4,194,304 (4,096 sub-bins). Each bin was priced under four **exact** representations: raw bitmask (128 B), array (2 B/mark), gap-coded / Elias-Fano (`count * log2(1024/count)` bits, the exact sparse floor), and an **exact period-modulated bitmask** ("period P, offset r, bit i set means position `r + i*P` is a hit"). The period encoder partitions a bin's marks by residue `r = offset mod P`, stores one narrow bitmask of `1024/P` bits per residue lane that holds a mark, and sweeps P for the minimum - lossless, nothing discarded.

| marks | marks/bin | bitmask | array | gap (E-F) | period (exact) |
|---|---|---|---|---|---|
| 1,000 | 1.1 | 113,280 | 2,000 | 1,884 | 2,885 |
| 20,000 | 4.9 | 521,088 | 40,000 | 20,597 | 43,391 |
| 50,000 | 12.2 | 524,288 | 100,000 | 41,114 | 100,104 |
| 200,000 | 48.8 | 524,288 | 400,000 | 111,451 | 343,112 |
| 500,000 | 122.1 | 524,288 | 1,000,000 | 193,395 | 532,470 |

The finding is a clean negative for the period container, with a structural cause:

1. **The exact period-modulated bitmask never wins over all three.** A per-bin winner tally across the full density sweep found it the sole minimum in **0 of 4,096 bins** at every count. At high fill its per-lane headers even push it *above* a raw bitmask (532,470 vs 524,288 B), because every residue that holds a mark costs its own lane bitmask and header.

2. **The reason is structural: the dyadic resonance is inter-bin, not intra-bin.** The selector spaces marks by *which* sub-bin is hit and *when* (the distance/uniqueness property), but within a bin `key % 1024 = 256*(k mod 4) + phaseOffset` ranges over all 1,024 offsets, so as a bin fills its marks occupy **every residue** mod P. Partitioning by residue therefore yields as many lanes as residues, and there is no single coarse sublattice for a period bitmask to collapse onto. The chosen period pins at the finest useful value until, at saturation, it falls back to `P = 1` (a plain bitmask).

3. **Exact gap-coded (Elias-Fano) keys dominate at every density.** At the sparse end they equal the entropy floor; at 122 marks/bin they are still 2.7x smaller than a bitmask (193 KB vs 524 KB) while remaining exact. Because Elias-Fano's high-bit layer *is* itself a period-P bitmask (with the within-lane offset kept in cheap low bits rather than partitioned into separate lanes), it captures the same coarse structure the period container was reaching for, without the per-residue lane overhead.

**Consequence for the container tier.** The measured win for L3 usage state is **Elias-Fano / bit-packed-delta coding of the sparse key set**, which is exact, needs no retention tradeoff, and stays below the bitmask cap through the entire life of a region. This is where the "sparse array devolves to a bitmask" transition should be intercepted: the raw 2 B/key array crosses the 128 B/bin bitmask near ~64 marks/bin, but an Elias-Fano container does not, so the roaring hierarchy's bitmask tier is reached far later (or not at all) for L3-driven usage state. A standalone period-modulated bitmask is declined - it only ever helps against a *raw* bitmask, which the sparse tier should not be reaching. Prototype: `FrequencyContainerPrototypeBenchmarkTest` (ADR-080 opt-in tier).

#### Access latency: a memory win is not a time win

Bytes are only one axis. The two hot operations pull in opposite directions - `isBad(location)` (point check) versus `accumulate(rank)` (produce the r-th good key, a select over the complement). Timed over 2M ops on the L3 usage set, full domain (`accessLatencyOnL3Usage`):

| marks | runs | point ns/op (rle / array / bitmask) | accumulate ns/op (rle / array / bitmask) |
|---|---|---|---|
| 20,000 | 19,902 | 78.8 / 69.6 / **5.8** | **79.1** / 87.9 / 138.5 |
| 100,000 | 97,696 | 94.2 / 93.4 / **2.6** | **103.1** / 109.9 / 139.3 |
| 200,000 | 190,511 | 124.5 / 110.2 / **3.4** | 144.6 / **115.5** / 161.9 |

Three consequences:

1. **The bitmask is 20x-40x faster at point-check (2.6-5.8 ns) but the *slowest* at accumulate (138-162 ns).** Accumulate is the L3 hot path - it is how a candidate is produced - so a memory-motivated move toward the bitmask family regresses the operation called most. This is the concrete form of "a memory win is not a time win".

2. **RLE does not coalesce on scattered usage.** The shipped RLE baseline (binary search + `A -> A+N` linear map) is balanced and best-or-tied at accumulate, but on L3 marks runs ~= marks (190,511 runs for 200,000 marks), so it captures almost no run structure and its footprint (3.8 MB) is ~5x the raw array (800 KB) with no accumulate advantage over it. RLE earns its place on *terrain* (contiguous hazard), not on scattered usage state.

3. **For L3 usage state the balanced choice is the sparse key array / Elias-Fano, not RLE or bitmask.** Array accumulate (88-116 ns) matches or beats both, at a fraction of the memory. Elias-Fano would keep the array's small footprint and improve point-check toward `O(1)`, but its accumulate needs a resident **select index** - and since the bitmask (the structure closest to bare Elias-Fano's high-bit layer) is the accumulate-slowest here, that index quality is the deciding risk, not the byte count. Any D-005 proposal must therefore benchmark Elias-Fano accumulate ns/op with its select index, not just its size.

#### Decision (2026-09-10): roaring container hierarchy, Elias-Fano deferred

The shipped **roaring container hierarchy is the chosen representation** (`Unallocated` / `SolidLand` / `SolidHazard` / `Array` / `Run` / `Bitmask`). Elias-Fano is **deferred, not adopted** - it shows a smaller footprint in the measurements above, but its advantage is size, its accumulate cost depends on a select-index whose latency is unproven, and roaring already spans the whole density range with a predictable per-bin choice. Elias-Fano shall be revisited only when data shows it beats the roaring hierarchy on accumulate latency at equal correctness, not on bytes alone.

A bin's preferred container is cheaply predictable from its cardinality and structure (sparse -> array, contiguous -> run, dense/aperiodic -> bitmask), so the hierarchy needs no search - the transition thresholds already encode the choice.

**Memory at large scale is mitigated without a new representation**, by either of:

1. **Rotating in-memory bins** - keep a bounded working set of hot bins resident and page cold bins to their `.bin`-backed form, so heap is bounded by the working set rather than by total explored area.
2. **Devolving to per-bin counts plus quota scanning** - for bins too large to hold exactly, retain only an occupancy count and reconstruct on demand by scanning under a quota, reaffirming the count so the estimate cannot silently drift. This trades exactness for a fixed per-bin cost at the extreme tail while keeping the safety direction (a reaffirmed count over-scans rather than under-marks).

Consequently, the container codec is treated as complete for its purpose; the outstanding work in ADR-085 §23i is the selection-path integration and the scale-mitigation policy above, not a fourth codec.

#### Decision (2026-09-10): sweep-driven residency, stride-group storage, single dyadic layout

The access pattern determines the storage and residency policy, so it is fixed first. The L3 selection path emits **unique, non-repeating** candidates in a deterministic dyadic-stride order (stride + Feistel phase schedule, ADR-088). Recency therefore carries no reuse signal - a bin just visited is the *least* likely to be needed again until the sweep wraps a full rotation - so LRU / "heat" eviction is rejected. The following is adopted instead:

- **Sweep-predicted (Belady) residency.** Because the access sequence is known ahead from the selector's cursor, residency is driven by predicted distance-to-next-access, not recency. Keep a bounded window of stride groups around the cursor resident; prefetch the group the cursor is about to enter; release the group behind it. There is no re-promote-on-heat rule - rematerialization is **prefetch-on-approach**, triggered when the predicted next-access distance falls under a lead time.

- **Single source of dyadic order.** Rather than the harvester re-deriving the traversal to "match" the selector, the harvester shall **read the selector's cursor, or be driven by it** - one definition of the dyadic order, shared. This removes any risk of the two drifting.

- **Ordering invariant (S-shaped correctness).** All bin traversal on the selection and warming paths is dyadic-stride and non-repeating. Spatial bin iteration on these paths is prohibited: it would break both prefetch locality and the uniqueness guarantee. **Exception:** `ScanTask` is intentionally sequential, iterating a region beginning-to-end to *complete* the dataset (full-coverage pre-scan); it is the deliberate carve-out from the dyadic-order rule and shall not be "fixed" to dyadic order.

- **Stride group as the unified unit.** The stride group (a phase/subset residue class of the dyadic schedule) is simultaneously the file shard, the prefetch unit, the devolution unit, and the future DB partition key. One dyadic-stride layout serves both the usage overlay and the terrain/Anvil base - no separate region-ordered layout is needed, because no path traverses bins spatially (the Anvil harvester probes dyadically even within a bin, and is not yet production-wired, so no spatial order exists to preserve).

- **Devolution trigger.** Devolve to memoryless count-only bins only when the resident stride-group window exceeds the configured memory budget, taking the groups furthest behind the cursor first, and only for groups above a worth-it floor (bitmask-tier density; sparse array/run groups stay exact). A devolved group reaffirms its count on its next scheduled scan, preserving the safety direction (over-scan, never under-mark). Rotation to the on-disk shard is preferred over devolution whenever a backing store exists, because it is lossless.

- **Sharded persistence (v6).** Save/restore shards the single per-shape blob into a manifest (pinned stride, P, curve, key width, version bump) plus one independently loadable section per stride group, each carrying its bins' TTL/epoch tags (ADR-079). The stride is pinned and the store re-shards on any stride change (the same upward-ratchet discipline as the v5 `P` field); an old shard is never reinterpreted under a new stride.

- **Database backing (deferred).** When state moves to a database the stride group becomes the partition key `(world, shape, strideGroup, binIndex)`, a sweep step is one keyed partition read, and writes are async and off-tick (S-005). This is designed with, not separately from, the network-mode state bindings ([ADR-036](ADR-036-network-mode-multi-server-multi-proxy.md), `MULTI_SERVER_PLAN.md`).

### Consequences

#### Positive:
- **76% to 94% Hazard Size Cut:** Slashes disk and heap consumption across all world scales.
- **Instant Cold-Start Updates:** Live player teleports mark bad chunks in $< 2\text{ ns}$ without triggering expensive array reallocations.
- **No Third-Party Bloat:** Implemented strictly with primitive Java arrays (`int[]`, `long[]`, `char[]`), maintaining zero library dependencies.
- **Land Quality:** Recovers millions of blocks of safe beaches and islands by setting `minGap = 1`.

#### Negative:
- **Format Version Bump:** Requires bumping `.bin` format version to Version 6 to serialize container-tagged payloads while retaining backward compatibility for loading Version $\le 5$ files.
