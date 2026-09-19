# Dual-Layer Shape Architecture, Spatial Memory & L3 Backlog Selection

**Scope of this document.** This document specifies and visualizes the V3 dual-layer spatial shape architecture, the hardware-conscious spatial memory storage hierarchy, the adaptive dyadic downsampling method, and the distinct selection and harvesting path for the L3 unverified backlog queue.

Companion architectural decisions and references:
- [ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md): Legacy Archimedean spiral 1D mapping.
- [ADR-028](../adr/ADR-028-l3-backlog-cache.md): L3 unverified backlog cache (`backlogLocations`), `.mca` binning, and head-blocking promotion.
- [ADR-034](../adr/ADR-034-memory-shape-catalog.md): Memory shape catalog (`CircleOptimizedDualLayer`, `SquareOptimizedDualLayer`).
- [ADR-081](../adr/ADR-081-unified-blocked-biome-run-table.md) & [ADR-082](../adr/ADR-082-cache-conscious-b-plus-tree-primitive-arrays.md): Blocked run tables and cache-conscious primitive array pages.
- [ADR-085](../adr/ADR-085-spiral-addressed-hilbert-key-space.md): Spiral-addressed Hilbert key space for learned state.
- [ADR-088](../adr/ADR-088-configurable-downsampling-stride-filter.md): Configurable adaptive dyadic downsampling stride filter and keyed Feistel permutation.

---

## Executive Overview (Dual-Layer Spatial Selection)

Dual-layer spatial shapes eliminate random coordinate rerolls by indexing chunk space into coarse Chebyshev macro-tiles traversed by fine Hilbert space-filling curves. Known invalid regions are skipped in $O(1)$ time via segmented prefix-sum tables.

```mermaid
flowchart LR
    A["Virtual Target Index<br/>(0 .. totalValidSpace)"] --> B["Two-Tier Segmented Table<br/>(Skip Bad Chunks O(1))"]
    B --> C["1D Hilbert Key<br/>(Within Macro-Tile)"]
    C --> D["Chebyshev Macro Ring<br/>(2D Tile Placement)"]
    D --> E["Physical Chunk Coordinates<br/>(cx, cz)"]

    classDef step fill:#cfe2ff,stroke:#1f4e8a,stroke-width:1px,color:#0b1f3a;
    classDef target fill:#b7e4b7,stroke:#1f6b1f,stroke-width:2px,color:#0b2a0b;
    class A,B,C,D step;
    class E target;
```

---

## 1. Dual-Layer Coordinate Addressing and Point Selection Pipeline

Dual-layer shapes (`CircleOptimizedDualLayer`, `SquareOptimizedDualLayer`) address discrete chunk space at native 1-chunk resolution ($S=1$, 16 blocks per cell). Space is decomposed into a two-tier hierarchy:
1. **Coarse Macro-Grid:** Chebyshev square rings of macro-tiles (point edge $P$ chunks, derived dynamically as a power of two from radius $R$, e.g., $P \in \{8, 16, 32\}$).
2. **Fine Intra-Tile Traversal:** Continuous space-filling Hilbert curve $\mathcal{H}$ of order $m = \log_2(P)$ within each macro-tile ($P^2$ chunks per tile), rotated to match the macro-spiral travel direction (seam matching).

### Point Selection Flowchart

```mermaid
%% Color legend: green=success/accept, red=rejection/boundary fail, blue=computation/transformation, yellow=table lookup/state
flowchart TD
    Start([Shape Candidate Selection<br/>select or rand]):::async --> StrideDerive[Derive Effective Stride S<br/>deriveEffectiveStride]:::data
    StrideDerive --> ModeCheck{Selection Mode?<br/>ACCUMULATE vs REJECT}

    subgraph StridePermutation [Dyadic Stride & Feistel Permutation]
        ModeCheck -- ACCUMULATE --> RangeCalc[Compute totalGood = range - badCells<br/>Virtual Good Domain 0..totalGood-1]:::data
        RangeCalc --> PhaseSeq[Compute Dyadic Phase Offset &phi;<br/>reverse t mod S >>> 32 - log2 S]:::data
        PhaseSeq --> SubsetBound[Compute Subset Size<br/>subsetSize = totalGood - 1 - &phi; / S + 1]:::data
        SubsetBound --> FeistelPRP[4-Round Keyed Feistel Network<br/>permutedK = FeistelPermute k, subsetSize, K_epoch]:::async
        FeistelPRP --> VirtualIdx[Calculate Virtual Good Index<br/>vIndex = permutedK * S + &phi;]:::data
        VirtualIdx --> ResolveAcc[Resolve Virtual Index via Segmented Table<br/>Tier 1 Directory Bisect -> Tier 2 Local Bin]:::data

        ModeCheck -- REJECT / Direct --> DirectPRP[Feistel Permutation on Raw Range<br/>permutedLoc = FeistelPermute t, range, K_epoch]:::async
        DirectPRP --> ResolveAccDirect[Raw 1D Key = permutedLoc]:::data
    end

    ResolveAcc --> KeyDecode[Key Decomposition<br/>key = macroIndex * P^2 + hilbertOffset]:::data
    ResolveAccDirect --> KeyDecode

    subgraph CoordinateDecomposition [1D Key to 2D Chunk Resolution]
        KeyDecode --> MacroRing[Decode Chebyshev Ring K & Side<br/>px, pz macro-tile coordinates]:::data
        MacroRing --> SeamOrientation[Derive Seam Orientation<br/>orientationFor px, pz]:::data
        SeamOrientation --> HilbertDecode[Inverse Hilbert Transform<br/>hilbertToXY h, P, orientation -> localX, localZ]:::data
        HilbertDecode --> PhysicalCoord[Compose Physical Chunk Coordinates<br/>cx = cenX + px * P + localX<br/>cz = cenZ + pz * P + localZ]:::data
    end

    PhysicalCoord --> BoundaryCheck{In Geometric Bounds?<br/>Circular distSq or Square Chebyshev}
    BoundaryCheck -- In Bounds --> Success([Return Physical Chunk Coordinates<br/>cx, cz]):::success
    BoundaryCheck -- Out of Bounds --> RerollCheck{Attempts < 100?}
    RerollCheck -- Yes --> Start
    RerollCheck -- No --> Exhausted([Selection Fallback / Null]):::fail

    classDef success fill:#b7e4b7,stroke:#1f6b1f,stroke-width:2px,color:#0b2a0b;
    classDef fail    fill:#f2b8b8,stroke:#8a1f1f,stroke-width:1px,color:#2a0b0b;
    classDef async   fill:#c9dcf5,stroke:#28518a,stroke-width:1px,color:#0b1f3a;
    classDef data    fill:#f2e6a8,stroke:#8a6b1f,stroke-width:1px,color:#2a220b;
```

---

## 2. Virtual Index Resolution via Two-Tier Segmented Table (ACCUMULATE Mode)

When operating under `MODE_ACCUMULATE`, candidate selection generates a virtual rank $T \in [0, \text{totalGood})$ where all known bad chunks have been subtracted from the addressable domain ($\text{totalGood} = \text{totalRange} - \text{totalCovered}$).

To achieve zero hole re-rolls without scanning large fragmented run arrays, `SegmentedKeyRunTable.resolveAccumulate(long target)` performs a **two-tier prefix-sum directory resolution**:
1. **Tier-1 Directory Bisect ($O(\log \text{Bins})$):** Evaluates cumulative good counts across fixed-size bins via `dirBadPrefixSums` ($32 \times 32$ chunks = $1{,}024$ chunks/bin) to identify the target bin $b$.
2. **Tier-2 Local Bin Resolution ($O(\log \text{RunsPerBin})$):** Computes $\text{localTarget} = T - \text{goodBefore}$, then iteratively searches the local primitive `starts` and `badPrefixSums` arrays until physical convergence.
3. **Physical 1D Key Composition:** $\text{physicalKey} = b \cdot \text{binSize} + \text{localOffset}$.

### Virtual-to-Physical Resolution Flowchart

```mermaid
%% Color legend: green=resolved output, blue=computation/logic, yellow=directory/table lookup, red=failure/clamp
flowchart TD
    InVirtual([Virtual Target Rank T<br/>0 &le; T < totalGood]):::data --> InitialGuess[Initial Bin Estimate<br/>b = min floor T / binSize, numBins - 1]:::data
    InitialGuess --> PrefixLookup[Read Preceding Bad Sum<br/>badBefore = dirBadPrefixSums b - 1<br/>goodBefore = b * binSize - badBefore]:::fast

    PrefixLookup --> RangeCheck{goodBefore &le; T &lt; goodBefore + binGood?}

    RangeCheck -- "T < goodBefore" --> BisectLeft[Tier-1 Binary Search Left<br/>Search range 0 .. b-1 in dirBadPrefixSums<br/>Find highest mid where mid*binSize - badBefore &le; T]:::async
    RangeCheck -- "T &ge; goodBefore + binGood" --> BisectRight[Tier-1 Binary Search Right<br/>Search range b+1 .. numBins-1 in dirBadPrefixSums<br/>Find highest mid where mid*binSize - badBefore &le; T]:::async
    RangeCheck -- Matches Bin b --> TargetBinIdentified[Target Bin b Confirmed]:::fast

    BisectLeft --> TargetBinIdentified
    BisectRight --> TargetBinIdentified

    TargetBinIdentified --> LocalTargetCalc[Calculate Local Target Offset<br/>localTarget = T - goodBefore<br/>Fetch Bin b Reference]:::data

    subgraph Tier2Local [Tier-2 Cache-Conscious Local Bin Resolution]
        LocalTargetCalc --> BinStateCheck{Bin State?}
        BinStateCheck -- "isEmpty() (count == 0)" --> FastEmpty[localOffset = localTarget]:::success
        BinStateCheck -- "isFull()" --> FullFail([Return -1: Bin Completely Covered]):::fail
        BinStateCheck -- Mixed Bad Runs --> IterativeSearch[Iterative Physical Guess Loop<br/>guess = localTarget + currentBad]:::async

        IterativeSearch --> LocalBisect[Binary Search starts Array in Bin b<br/>idx = binarySearch starts, 0, count, guess]:::data
        LocalBisect --> SumLookup[Lookup Local Prefix Sum<br/>newBad = idx > 0 ? badPrefixSums idx - 1 : 0]:::data
        SumLookup --> ConvergeCheck{newBad == currentBad?}
        ConvergeCheck -- No --> UpdateBad[currentBad = newBad]:::async --> IterativeSearch
        ConvergeCheck -- Yes (Converged) --> LocalResolved[localOffset = localTarget + currentBad]:::success
    end

    FastEmpty --> BoundsCheck{localOffset < binSize?}
    LocalResolved --> BoundsCheck

    BoundsCheck -- Yes --> ComposePhysical[Compose Physical 1D Hilbert Key<br/>key = b * binSize + localOffset]:::success
    BoundsCheck -- No --> OverflowFail([Return -1: Exceeds Bin Boundary]):::fail

    ComposePhysical --> OutPhysical([Physical 1D Hilbert Coordinate Key]):::success

    classDef success fill:#b7e4b7,stroke:#1f6b1f,stroke-width:2px,color:#0b2a0b;
    classDef fail    fill:#f2b8b8,stroke:#8a1f1f,stroke-width:1px,color:#2a0b0b;
    classDef async   fill:#c9dcf5,stroke:#28518a,stroke-width:1px,color:#0b1f3a;
    classDef data    fill:#f2e6a8,stroke:#8a6b1f,stroke-width:1px,color:#2a220b;
    classDef fast    fill:#d5e8d4,stroke:#82b366,stroke-width:1.5px,color:#1b381b;
```

---

## 3. Spatial Memory and Run Table Data Storage Architecture

Spatial hazards, bad chunks, and learned terrain memory are managed through `MemoryShape` and cached in `SegmentedKeyRunTable`. Ground truth is retained at strict 1-chunk resolution ($S=1$).

### Storage Hierarchy and On-Disk Layout

```mermaid
%% Color legend: green=CPU cache/fast, blue=memory structure, yellow=disk format, grey=bounds
flowchart TB
    subgraph MemoryHierarchy [In-Memory Storage Hierarchy - SegmentedKeyRunTable]
        direction TB
        MemShape[MemoryShape Instance<br/>Continuous 1D Key Space 0..Range-1]:::data

        subgraph Tier1 [Tier 1 Directory - L1/L2 Cache Resident]
            DirKeys[dirBinStarts: long array<br/>Monotonic starting keys per bin]:::fast
            DirBad[dirBadPrefixSums: int array<br/>Cumulative bad chunks per bin &le; 64 ints]:::fast
            DirBins[bins: Bin array reference table]:::fast
        end

        subgraph Tier2 [Tier 2 Local Bins - Cache-Conscious Primitive Pages]
            Bin0["Bin 0 (Span 0..B-1)<br/>starts: int[] (relative offset)<br/>lengths: int[] (run lengths)<br/>badPrefixSums: int[]<br/>coveredCells: long"]:::page
            Bin1["Bin 1 (Span B..2B-1)<br/>starts: int[]<br/>lengths: int[]<br/>badPrefixSums: int[]<br/>coveredCells: long"]:::page
            BinN["Bin N (Span NB..Range-1)<br/>starts: int[]<br/>lengths: int[]<br/>badPrefixSums: int[]<br/>coveredCells: long"]:::page
        end
    end

    subgraph DiskPersistence [Persistent Disk Layout - .bin Format Version 5]
        direction TB
        Header["File Header (16-32 bytes)<br/>- Magic: 0x5254504D ('RTPM')<br/>- Format Version: 5<br/>- Curve ID: CURVE_SPIRAL_HILBERT (0x02)<br/>- Point Edge P: 8, 16, or 32<br/>- Key Width: 4 bytes (int) or 8 bytes (long)<br/>- Config Hash: stable 64-bit hash (ADR-022)"]:::disk
        Payload["Run Record Payload<br/>[Run 0: startKey (int/long), length (int/long)]<br/>[Run 1: startKey (int/long), length (int/long)]<br/>...<br/>[Run M: startKey, length]"]:::disk
    end

    MemShape --> DirKeys
    MemShape --> DirBad
    MemShape --> DirBins
    DirBins --> Bin0
    DirBins --> Bin1
    DirBins --> BinN

    Bin0 -.flushAndRebuild / save.-> Payload
    Bin1 -.flushAndRebuild / save.-> Payload
    BinN -.flushAndRebuild / save.-> Payload
    Header --- Payload

    classDef fast    fill:#b7e4b7,stroke:#1f6b1f,stroke-width:1.5px,color:#0b2a0b;
    classDef page    fill:#c9dcf5,stroke:#28518a,stroke-width:1px,color:#0b1f3a;
    classDef disk    fill:#f2e6a8,stroke:#8a6b1f,stroke-width:1px,color:#2a220b;
    classDef data    fill:#e8d5f5,stroke:#5c288a,stroke-width:1px,color:#220b3a;
```

---

## 4. Downsampling Method: Dyadic Stride and Keyed Permutation

To prevent player arrival clumping across massive borders while retaining 100% safety ground truth, an adaptive dyadic downsampling filter operates inside the selection tier (ADR-088).

### Downsampling State Machine and Phase Permutation

```mermaid
%% Color legend: green=active phase candidate, blue=cryptographic mixing, yellow=phase progression, red=epoch transition
flowchart TD
    subgraph StrideConfig [Stride Derivation & Nyquist Bound]
        InitDomain[Domain Size N / totalGood]:::data --> DeriveS[deriveAdaptiveStride<br/>S in 1, 4, 16, 64, 256]:::data
        DeriveS --> NyquistCheck{Nyquist Bound Check<br/>S &le; binArea / 2 ?<br/>binArea = P^2}
        NyquistCheck -- S > binArea/2 --> ClampS[Clamp Stride S = max 1, binArea / 2]:::data
        NyquistCheck -- S &le; binArea/2 --> ValidS[Final Stride S & bits = log2 S]:::data
    end

    subgraph PhaseProgression [Dyadic Bit-Reversal Anti-Resonance]
        ValidS --> AdvCounter[Advance Monotonic Counter t<br/>t = selectionCounter.getAndIncrement]:::async
        AdvCounter --> EpochSlice[Epoch Slice: epoch = t / subsetCapacity<br/>subsetIdx = epoch mod S]:::data
        EpochSlice --> BitRev[Dyadic Bit-Reversal Bisection Sequence<br/>&phi; = reverse subsetIdx >>> 32 - bits]:::data
        BitRev --> PhaseExhaustion["Exhaust all candidates in active phase &phi; before next rotation<br/>(Guarantees physical distance d &ge; &radic;S chunks)"]:::data
    end

    subgraph FeistelNetwork [4-Round Feistel Pseudorandom Permutation]
        PhaseExhaustion --> KeyDerive["Derive Round Key K_epoch<br/>feistelSalt ^ (epoch * 0x517CC1B727220A95L) ^ (&phi; * 0x9E3779B97F4A7C15L)"]:::async
        KeyDerive --> Permute[Cycle-Walking Feistel Permutation<br/>Strict Bijection: 0 duplicate chunks across subset]:::async
        Permute --> KeyCoord[Physical Hilbert Key = permutedK * S + &phi;]:::success
    end

    subgraph ExpansionRatchet [Radial Expansion Ratchet]
        AdjustRange[Dynamic adjustRange triggered]:::data --> IncEpoch[expansionEpoch++<br/>Atomic Ratchet]:::data
        IncEpoch --> ResetCounter[Reset selectionCounter & backlogCounter to 0]:::data
        ResetCounter -.New Salt / Modulus.-> KeyDerive
    end

    classDef success fill:#b7e4b7,stroke:#1f6b1f,stroke-width:2px,color:#0b2a0b;
    classDef async   fill:#c9dcf5,stroke:#28518a,stroke-width:1px,color:#0b1f3a;
    classDef data    fill:#f2e6a8,stroke:#8a6b1f,stroke-width:1px,color:#2a220b;
```

---

## 5. Distinct Selection Path for Filling L3 Backlog and Anvil Verification

The L3 backlog cache (`backlogLocations`, ADR-028) uses a decoupled, high-throughput selection path upstream of the cold (`unkeptLocations`) and hot (`keptLocations`) caches.

### Key Architectural Distinctions: L3 vs. Standard Cache Pipeline

| Dimension | Standard Cache Pipeline (L1 / L2) | L3 Backlog Cache Pipeline (`backlogLocations`) |
| :--- | :--- | :--- |
| **Selection Entrypoint** | `shape.select()` via `LocationGenerator` | `CircleOptimizedDualLayer.selectBacklogCandidate()` / `shape.select()` |
| **Verification Depth** | Full pipeline: chunk I/O + `vert.adjust()` + biome + 2r+1 safety + external claims | Off-tick Anvil pre-filter only (`AnvilIoPool`, zero chunk loading) |
| **Candidate State** | Fully verified safe coordinate | Tri-state validity: `UNVERIFIED` $\to$ `VALIDATED` or `INVALIDATED` |
| **Spatial Grouping** | Independent random candidates | Binned into $32 \times 32$ chunk `.mca` region files via `WorldBacklogBinIndex` |
| **Promotion Model** | Immediate cache push on attempt completion | Head-blocking contiguous promotion: only `VALIDATED` head entries advance |

### L3 Backlog Lifecycle and Bin Verification Flowchart

```mermaid
%% Color legend: green=promoted/validated, red=invalidated/dropped, blue=async/off-tick worker, yellow=queue/binning
sequenceDiagram
    autonumber
    participant Reg as Region.execute() Pulse
    participant Backlog as BacklogLocationBuffer (L3)
    participant BinIdx as WorldBacklogBinIndex (World-Level)
    participant Anvil as AnvilIoPool / RegionFileReader
    participant Cold as unkeptLocations (L2 Cold Cache)

    Note over Reg,Backlog: Phase 1: Shape-Only Refill (Time-Sliced & Hysteresis Gated)
    Reg->>Reg: Check Hysteresis: size < threshold * capacity?
    alt Refill Active & Heap Not Under Pressure
        loop While backlog not full & pulse budget remains
            Reg->>Reg: selectBacklogCandidate() via Dyadic Stride PRP
            Reg->>Backlog: offerUnverified(RTPLocation coords, unverified)
            Backlog-->>Reg: BacklogEntry (State = UNVERIFIED)
            Reg->>BinIdx: insert(RegionFileCoord (cx>>5, cz>>5), BacklogEntry)
        end
    end

    Note over Reg,Anvil: Phase 2: One-Bin-Per-Pulse Off-Tick Anvil Verification
    Reg->>Backlog: peekOldestUnverified()
    alt Oldest Entry Found
        Backlog-->>Reg: oldestEntry (targetBin = RegionFileCoord)
        Reg->>BinIdx: snapshot(targetBin)
        BinIdx-->>Reg: List<BacklogEntry> (cross-region candidates in same .mca)
        Reg->>Anvil: Batch check biome & block headers off-tick (ADR-016)
        Anvil-->>Reg: Batch verification decisions
        loop For each entry in bin snapshot
            alt Anvil prefilter passes
                Reg->>Backlog: setValidity(VALIDATED)
            else Hazardous or rejected biome
                Reg->>Backlog: setValidity(INVALIDATED)
            end
        end
    end

    Note over Reg,Cold: Phase 3: Head-Blocking Promotion to L2
    loop Drain Head of Backlog
        Backlog->>Backlog: peek head entry
        alt Head is INVALIDATED
            Backlog->>Backlog: drop head entry (reclaim capacity)
        else Head is VALIDATED
            Backlog->>Backlog: poll head entry
            Backlog->>Cold: push into unkeptLocations (promoted to L2)
        else Head is UNVERIFIED
            Note over Backlog: Stop draining! Head-of-line blocking preserves FIFO order
        end
    end
```

---

## 6. Time and Memory Complexity: Architectural Benchmarks & Competitor Comparison

The architectural migration from flat 1D Archimedean spirals to dual-layer space-filling Hilbert geometries backed by two-tier `SegmentedKeyRunTable` structures yields provable asymptotic and empirical improvements in time and heap complexity.

### Asymptotic Complexity & Operational Predictability Comparison

| Architecture / Engine | Selection Time Complexity | ACCUMULATE Resolution Time | Dynamic Mark Reconciliation | Retained Heap Memory Complexity | Latency Variance / Predictability |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Dual-Layer Segmented Hilbert (Current RTP)** | $\mathcal{O}(1)$ dyadic stride + 4-round Feistel PRP | $\mathcal{O}(\log \text{Bins} + \log \text{RunsPerBin})$ ($\sim 4\text{ to }6\text{ bisect steps}$) | $\mathcal{O}(\text{RunsPerBin} \log \text{RunsPerBin})$ (dirty bin local only) | $\mathcal{O}(M)$ primitive arrays, near-full collapse to $\mathcal{O}(1)$ singleton ($\le 25\text{ kB}$) | **Deterministic:** Bound L1/L2 cache traversal ($13\text{--}35\text{ ns}$); zero hole re-rolls; strictly bounded jitter |
| **Pure Archimedean Spiral (Prior RTP RLE)** | $\mathcal{O}(1)$ polar spiral math ($\sqrt{k/\pi}$) | $\mathcal{O}(I \cdot \log M)$ iterative fixed-point over global array ($I \approx 3\text{--}5$) | $\mathcal{O}(M \log M)$ global rebuild across full world array | $\mathcal{O}(M)$ global flat `long[]` arrays, fragmented across spiral rings ($31\text{ kB}$) | **Moderate Variance:** Global binary search miss penalties; high reconciliation pauses ($27\text{ }\mu\text{s/mark}$) |
| **EzRTP (Coarse Online Verification)** | $\mathcal{O}(1)$ random coordinate generation (`max-attempts: 16`) | No ACCUMULATE mode; $\mathcal{O}(\text{re-roll})$ on rejection | $\mathcal{O}(1)$ per-attempt check; no run table compression | $\mathcal{O}(N)$ monitoring/stats heap maps, unbounded over uptime | **High Variance / Unpredictable:** Exponential geometric rejection tails (measured p99 tail $\sim 1.9\text{ s}$); heap pressure drives GC pauses |
| **JustRTP (TTL Pre-Cache & Positive Reuse Pool)** | $\mathcal{O}(1)$ `ConcurrentLinkedQueue.poll()` or positive reuse pool | No ACCUMULATE mode; cache misses fall back to full pipeline | No persistent negative hazard index; periodic async refill timer | $\mathcal{O}(C)$ queue objects (`Location` references) + bounded spot pool with time TTL | **Bimodal & Skewed Variance:** Fast when pool warm, but exhibits heavy spatial clustering / Pólya urn feedback and severe latency spikes when TTL drains |

---

### Empirical Proofs & Benchmark Findings

#### 1. ACCUMULATE Mode Resolution Latency (11.2x Speedup)
Empirical runs on real Minecraft world save data ($r = 96$ chunks, `SegmentedKeyRunTableBenchmarkTest` / `AccumulateOffsetResolutionBenchmarkTest`):
* **Prior Flat Global Loop (`MemoryShape.resolve`):** **$1{,}571.4\text{ ns/op}$** average. Required repeated global binary searches over the complete set of runs.
* **Dual-Layer Segmented Two-Tier Resolution:** **$140.0\text{ ns/op}$** average (**11.22x faster**). Tier-1 directory lookup bisects `dirBadPrefixSums` ($\le 64$ entries) strictly in L1/L2 cache, leaving local resolution to $\le 15$ runs within the bin.
* **Accuracy:** 100.00% coordinate equivalence over $>50{,}000$ random samples against the flat model.

#### 2. Stored Runs, Heap Footprint, and Scale (Three-Way Benchmark)
Across varying world radii (measured via `ThreeWayScaleBenchmarkTest` on real terrain):

| World Scale | Model | Stored Runs | Heap Footprint | Lookup Latency | Reconcile Latency | Speedup vs Original |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **0.8 km (spawn)**<br>$r = 50\text{ chunks}$ | Prior Spiral RLE<br>Flat Hilbert<br>**Dual-Layer Segmented** | 331<br>189<br>**210** | 5.3 kB<br>3.0 kB<br>**4.7 kB** | 75.3 ns<br>67.2 ns<br>**62.0 ns** | 2,422 ns/mark<br>1,380 ns/mark<br>**519 ns/mark** | 1.0x<br>1.12x<br>**1.21x** |
| **4.1 km (medium)**<br>$r = 256\text{ chunks}$ | Prior Spiral RLE<br>Flat Hilbert<br>**Dual-Layer Segmented** | 1,937<br>1,273<br>**1,322** | 31.0 kB<br>20.4 kB<br>**14.1 kB** | 34.9 ns<br>26.8 ns<br>**20.6 ns** | 4,966 ns/mark<br>4,232 ns/mark<br>**1,148 ns/mark** | 1.0x<br>1.30x<br>**1.70x** |
| **8.2 km (large)**<br>$r = 512\text{ chunks}$ | Prior Spiral RLE<br>Flat Hilbert<br>**Dual-Layer Segmented** | 1,937<br>1,273<br>**1,506** | 31.0 kB<br>20.4 kB<br>**24.4 kB** | 20.9 ns<br>18.4 ns<br>**13.4 ns** | 27,904 ns/mark<br>32,043 ns/mark<br>**1,292 ns/mark** | 1.0x<br>1.14x<br>**1.56x** |

* **Run Reduction & Heap Inversion:** The Archimedean spiral slices 2D terrain clusters into discontinuous 1D ring segments, multiplying runs. The 2D-localized Hilbert curve packs adjacent chunks into contiguous runs (producing **42% fewer coalesced runs** on circular domains). Combined with near-full bin collapse, the segmented table at 4.1 km uses **14.1 kB**—less than half the heap of the prior spiral (31.0 kB).
* **Reconciliation Speedup (21.6x faster):** When learning a new hazard or bad chunk, flat models must sort and re-coalesce the entire world run table ($27{,}904\text{ ns/mark}$). Segmented tables dirty only the affected local bin, rebuilding $10\text{--}25$ local runs off-tick in **$1{,}292\text{ ns/mark}$**.

#### 3. Time Cost, Memory Footprint & Predictability Comparison: Code-Audited Competitor Analysis

##### EzRTP: Unbounded Heap Tracking vs. Segmented Primitive Bounds
* **Memory Bounds & Footprint Cost:**
  * As published in `ez-plugins/EzRTP`, coordinate selection relies on online shape generation (`search-pattern: random|circle|square|triangle|diamond`) with a configured candidate attempt ceiling (`max-attempts: 16`). Unsafe location monitoring (`unsafe-location-monitoring.monitoring.enabled`) and performance stats record metrics into heap-resident collections. Without continuous Run-Length Encoding or space-filling key spaces, tracking unsafe coordinates incurs Java object overhead ($\sim 160\text{--}200\text{ bytes}$ per coordinate), which scales unbounded with exploration ($O(N)$).
  * In contrast, RTP's dual-layer spatial memory bounds retained footprint to $\le 25\text{ kB}$ total per world via Run-Length Encoding and primitive arrays (`int[]`), collapsing ocean regions to $O(1)$ singletons.
* **Lookup Time & Variance Profile:**
  * EzRTP has no continuous domain mapping or ACCUMULATE mode. Point generation relies on rejection sampling: candidate coordinates are picked at random and evaluated online against safety filters (biomes, WorldGuard, unsafe blocks). In dense hazard regions (e.g., oceans), rejection rates compound, causing long re-roll tails (measured p99 tail $\sim 1.9\text{ seconds}$).
  * RTP's Segmented Hilbert table provides deterministic $O(\log \text{Bins} + \log \text{RunsPerBin})$ lookup ($13.4\text{--}20.6\text{ ns/op}$) with near-zero latency variance, guaranteeing zero re-rolls within known bad runs.

##### JustRTP: TTL Pre-Cache & Positive Reuse Pool vs. Continuous Spatial Memory
* **Recall Architecture & Memory Cost:**
  * As published in `kotorinet/justRTP` (`LocationCacheManager.java`), JustRTP implements pre-caching via per-world FIFO queues: `ConcurrentHashMap<String, ConcurrentLinkedQueue<Location>>` targeting a fixed capacity (default `cache_size: 20`) serialized to `cache.yml`.
  * Later updates also incorporate positive reuse memory ("smart location cache" learning good landing spots from real teleports with a bounded pool and an entry TTL of $\sim 15\text{ minutes}$).
  * Rather than storing a mathematical negative index of unsafe terrain, JustRTP caches and recycles known-good coordinates. Memory cost is bounded to the positive pool ($O(C)$ boxed `Location` instances) with time-based eviction, discarding spatial state once the TTL elapses.
* **Time Cost, Bimodal Variance & Spatial Clumping Skew:**
  * **Warm State:** When cached entries are available in the queue or positive reuse pool, retrieval is $O(1)$ ($\sim 30\text{--}65\text{ ms}$ dispatch latency).
  * **Drained / Burst State:** When the pre-cache empties under burst traffic or when entries expire past their TTL, requests fall back to on-demand generation. In hazardous areas, refill loops repeatedly test, load, and reject the exact same unsafe chunks across cycles because rejected hazard geometry is not retained.
  * **Preferential Attachment / Spatial Skew:** As proved in `DistributionSkewBenchmarkTest`, positive reuse memory introduces a Pólya urn feedback loop: early random discoveries get recycled and reinforced, creating intense player arrival clumping into localized hotspots (higher Gini inequality and lower spatial entropy) while leaving wide swaths of the map unvisited.
* **RTP Dual-Layer Predictability:**
  * RTP maintains negative rejection memory over the mathematical complement of bad space via Run-Length Encoding.
  * Combined with permanent off-tick Anvil pre-filtering (`rtp-anvil`), the unverified L3 backlog (`backlogLocations`), and the dyadic downsampling stride filter with cycle-walking Feistel PRP (ADR-088), RTP guarantees uniform spatial dispersion, zero duplicate chunks, and flat, deterministic response times regardless of server uptime.

---

## Related Code and Implementation Map

- **Dual-Layer Shapes:**
  - `CircleOptimizedDualLayer`: continuous Chebyshev-addressed Hilbert curve, inscribed inner-donut bound, adaptive dyadic downsampling stride filter.
  - `SquareOptimizedDualLayer`: square Chebyshev macro-grid, dynamic `PointEdgeSelector`, loopless $O(1)$ candidate coordinate generation.
  - `SegmentedKeyRunTable`: two-tier hardware cache-conscious secondary run table with bin-local binary search and prefix sum routing.
- **L3 Backlog Subsystem:**
  - `BacklogLocationBuffer`: order-preserving FIFO buffer with tri-state validity (`UNVERIFIED`, `VALIDATED`, `INVALIDATED`) and heuristic cleaning.
  - `WorldBacklogBinIndex`: cross-region weak index mapping `RegionFileCoord` to candidate entries for `.mca` batch verification.
  - `Region.processBacklog()`: hysteresis-gated refill, one-bin-per-pulse Anvil pre-filtering, and head-blocking L2 promotion.
- **Verification and Safety:**
  - `AnvilRegionByteCache` / `anvil-api`: off-tick `.mca` and `.linear` NBT pre-filtering without server chunk tickets (`REQ-RTP-S-005`).
