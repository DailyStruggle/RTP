# Location selection — per-attempt pipeline

**Scope of this diagram.** This chart covers the behavior *inside* a single attempt of `LocationGenerator` — i.e., how one candidate `(x, y, z)` is proposed, chunk-loaded, and accepted or rejected. This is the **decision core** that drives everything downstream: every cached location (diagram 02), every `/rtp` teleport that reaches `Start LOAD` (diagram 01), and every `/rtp scan` hit (diagram 05) comes out of this loop. Related-but-separate behavior paths are intentionally **out of scope** here:

- **Who invoked us** — `/rtp` command selection (diagram 07), cache replenishment (diagram 02), `/rtp scan` (diagram 05), onEvent auto-teleport — all funnel into `ILocationGenerator.getLocation` but differ only in who owns the returned `GenerationResult`.
- **Outer attempt-loop re-entry plumbing** — `PregenTask.run()` / `rescheduleNextAttempt()` / `continueInline(...)` is the state-machine harness (ADR-015 Option B) that re-invokes `runAttempt` without blocking. See `PregenTask.java` header.
- **Chunk ticket lifecycle** (`ChunkReservation`, `MemoryTracker`) — see diagram 03.
- **Stale-chunk guard** on live-backed chunks — see [ADR-015](../adr/ADR-015-stale-chunk-guard-countbound-pipes.md).
> Companion walkthrough: [`CODE_TOUR.md` section 12 — Location selection per attempt](../dev/CODE_TOUR.md).

---

## Executive Overview (Per-Attempt Flow)

This overview illustrates how candidate coordinates progress through generation, probe-first gating, purpose-gated and cost-governed chunk resolution, and validation checks. Any failure causes the candidate to be recorded as invalid and prompts a retry, while full validation preloads view-distance chunks for instant arrival.

```mermaid
flowchart LR
    A["Shape Candidate<br/>(cx, cz)"] --> B{"Inside WorldBorder?"}
    B -- "No" --> Ret["Record Failure &<br/>Reschedule Attempt"]
    B -- "Yes" --> C["Resolve Chunk<br/>(Purpose-Gated &amp;<br/>Cost-Governed)"]
    C --> D{"Vertical & Biome<br/>Match?"}
    D -- "No" --> Ret
    D -- "Yes" --> E{"Safety & Claim<br/>Verifiers Pass?"}
    E -- "No" --> Ret
    E -- "Yes" --> F["Accept Location<br/>(Preload ChunkSet)"]

    classDef success fill:#b7e4b7,stroke:#1f6b1f,stroke-width:2px,color:#0b2a0b;
    classDef fail    fill:#f2b8b8,stroke:#8a1f1f,stroke-width:1px,color:#2a0b0b;
    classDef step    fill:#cfe2ff,stroke:#1f4e8a,stroke-width:1px,color:#0b1f3a;
    class A,C step;
    class Ret fail;
    class F success;
```

---

## Detailed Attempt Flowchart

```mermaid
%% Color legend: green=accepting success; red=hard terminal / fail-fast sink; blue=async or thread-hop work; yellow=config / bookkeeping / data-driven choice.
flowchart TD
    Start([PregenTask.runAttempt<br/>attempt i / maxAttempts]):::async --> CapCheck{i &gt; maxAttempts<br/>or biomeChecks &gt;= maxBiomeChecks?}
    CapCheck -- Yes --> Exhausted[completeExhausted<br/>result = GenerationResult null, i, null]:::fail
    CapCheck -- No  --> ShapePick

%% 1. Shape + optional biomeRecall
    subgraph ShapeStage [Shape selection — MemoryShape.rand or biomeRecall]
        ShapePick{shape is<br/>MemoryShape?}
        ShapePick -- No  --> ShapeSelect[shape.select<br/>returns chunk cx, cz]
        ShapePick -- Yes --> Recall{biomeRecall<br/>and not defaultBiomes?}
        Recall -- No  --> MemRand[memoryShape.rand]:::data
        Recall -- Yes --> RecallHas{any biome keys<br/>in memory?}
        RecallHas -- Yes --> RecallPick[weighted pick<br/>by prefix sums]:::data
        RecallHas -- No  --> RecallForced{biomeRecallForced?}
        RecallForced -- Yes --> ForcedFail[result = empty GenerationResult<br/>log invalid state]:::fail
        RecallForced -- No  --> MemRand
        MemRand --> XZDecode[locationToXZ]
        RecallPick --> XZDecode
    end
    ShapeSelect --> BorderCheck
    XZDecode --> BorderCheck

%% 2. WorldBorder probe
    BorderCheck{inside<br/>WorldBorder?}
    BorderCheck -- No --> BorderFail[worldBorderFails++<br/>maxAttempts++]:::data
    BorderFail --> BorderCap{&gt; 1000 fails?}
    BorderCap -- Yes --> BorderAbort[result = empty<br/>log region outside border]:::fail
    BorderCap -- No  --> Reschedule[rescheduleNextAttempt<br/>outer loop re-enters]:::async
    BorderCheck -- Yes --> ProbeFirstCheck

%% 3. Purpose-gated and cost-governed chunk resolution (ADR-109, ADR-110, ADR-114, ADR-112)
    subgraph ChunkStage [Chunk resolution — probe-first, purpose-gated & governed deduplication]
        ProbeFirstCheck{ProbeFirstGovernor<br/>should probe?<br/>ADR-109}
        ProbeFirstCheck -- Yes --> ColumnProbe[probeChunkColumn minY-1..maxY<br/>Lean vertical surface test]:::async
        ColumnProbe --> ProbeOK{Probe safe?}
        ProbeOK -- No --> ProbeReject[gov.recordProbe reject<br/>FailTypes.vert/safety]:::fail
        ProbeOK -- Yes --> PurposeGate

        ProbeFirstCheck -- No --> PurposeGate{LoadPurpose?<br/>ADR-110 / ADR-114}

        %% Speculative Refill Branch (ADR-110)
        PurposeGate -- SPECULATIVE --> ReadOnly[world.getOrReadChunk cx, cz<br/>Resident cache or Anvil disk]:::async
        ReadOnly --> ReadHit{Present on disk<br/>or in cache?}
        ReadHit -- Yes --> SelfCheck
        ReadHit -- No  --> HeapCheck{Heap under<br/>pressure?<br/>HeapPressureMonitor}
        HeapCheck -- Yes --> DeferCandidate[completeDeferred<br/>gate.onDeferredCenter]:::fail
        HeapCheck -- No  --> PinCheck{LiveLoadGate<br/>tryAcquirePin &lt; cap?}
        PinCheck -- Yes (Pinned) --> NativeLoad[world.getOrLoadChunk cx, cz<br/>with ChunkReservation]:::async
        PinCheck -- No (Unpinned) --> NativeLoad
        PinCheck -- Defer --> DeferCandidate

        %% Immediate / On-Demand Branch (ADR-114)
        PurposeGate -- IMMEDIATE --> CacheTier1{Tier 1: In resident<br/>chunk cache?}
        CacheTier1 -- Yes --> SelfCheck
        CacheTier1 -- No  --> McaHeaderCheck{Zero-decode MCA check<br/>isOccupied &lt; 1&mu;s<br/>ADR-067 / ADR-114}
        McaHeaderCheck -- Ungenerated --> NativePermit
        McaHeaderCheck -- Generated --> DedupThreshold{P_reject &gt; threshold?<br/>C_file / C_native + W*C_sync}
        DedupThreshold -- "No (Safe / Normal Tick)" --> NativePermit
        DedupThreshold -- "Yes (Hazard / High MSPT)" --> AnvilPreFilter[AnvilChunkView decode<br/>Off-thread AnvilIoPool]:::async
        AnvilPreFilter --> AnvilSafe{Candidate safe?}
        AnvilSafe -- No (Hazard) --> AnvilReject[Discard off-thread<br/>Shed deadweight load<br/>0ms tick cost]:::fail
        AnvilSafe -- Yes --> NativePermit{ImmediateLoadGovernor<br/>Acquire in-flight permit<br/>Max 1/reg, 2 global}
        NativePermit -- Permit Acquired --> NativeLoad
        NativePermit -- Timeout / Backpressure --> BusyFail[FailTypes.timeout<br/>msgBusy / S-004]:::fail

        NativeLoad --> ChunkOK{resolved<br/>non-null?}
        ChunkOK -- No  --> NullChunk[FailTypes.nullChunk<br/>reason=ticketFailed or<br/>chunkLoadTimeout or<br/>asyncLoadNull]:::fail
        ChunkOK -- Yes --> SelfCheck{chunk.isSelfContained?<br/>anvil = yes / live = no}
        SelfCheck -- Yes --> Evaluate[proceedWithEvaluation<br/>stays on async thread]:::async
        SelfCheck -- No  --> LiveHop[dispatchLiveEvaluation<br/>Folia regional thread hop<br/>allocate ChunkReservation<br/>see diagram 03 / ADR-112]:::async
        LiveHop --> StaleGuard{stale-chunk<br/>guard passes?<br/>ADR-015}
        StaleGuard -- No --> StaleRetry[staleRetries++<br/>re-request or attribute<br/>staleChunkBeforeVert]:::fail
        StaleGuard -- Yes --> Evaluate
    end
    NullChunk --> Reschedule
    ProbeReject --> Reschedule
    DeferCandidate --> Reschedule
    AnvilReject --> Reschedule
    BusyFail --> Reschedule

%% 4. Vert adjust + biome filter
    Evaluate --> VertAdjust[vert.adjust chunk<br/>returns x,y,z or null]
    VertAdjust --> VertOK{non-null?}
    VertOK -- No --> VertFail[FailTypes.vert<br/>MemoryShape.addBadLocation]:::fail
    VertFail --> Reschedule
    VertOK -- Yes --> BiomeFilter{biome in<br/>biomeNames<br/>== biomeWhitelist?}
    BiomeFilter -- No --> BiomeFail[FailTypes.biome<br/>biomeChecks++<br/>maxAttempts++]:::fail
    BiomeFail --> BiomeCap{biomeChecks &gt;=<br/>maxBiomeChecks?}
    BiomeCap -- Yes --> Exhausted
    BiomeCap -- No  --> Reschedule
    BiomeFilter -- Yes --> LoadNeighbors

%% 5. Neighbour grid + safety y-scan
    subgraph SafetyStage [Safety check — 2r+1 squared neighbour grid]
        LoadNeighbors[getChunkAt for each<br/>neighbour in 2r+1 grid<br/>allOf with 5s timeout]:::async
        LoadNeighbors --> NeighborsOK{all non-null<br/>within 5s?}
        NeighborsOK -- No --> NeighborFail[FailTypes.nullChunk<br/>reason=neighborNull]:::fail
        NeighborsOK -- Yes --> YScan[y-scan x,y,z in<br/>+/- safetyRadius]
        YScan --> Safe{all blocks<br/>safe vs unsafeBlocks?}
        Safe -- No --> SafetyFail[FailTypes.safety<br/>MemoryShape.addBadLocation]:::fail
        Safe -- Yes --> Verifiers
    end
    NeighborFail --> Reschedule
    SafetyFail --> Reschedule

%% 6. External verifiers + success
    Verifiers[GlobalRegionVerifiers<br/>async chain<br/>claim plugins, custom]:::async
    Verifiers --> VerOK{all pass?}
    VerOK -- No --> VerFail[FailTypes.safetyExternal<br/>MemoryShape.addBadLocation]:::fail
    VerFail --> Reschedule
    VerOK == Yes ==> Success([completeSuccess<br/>MemoryShape.addBiomeLocation<br/>preload view-distance ChunkSet<br/>result = GenerationResult coords, i, chunks]):::success

    Reschedule -.outer loop.-> Start

    classDef success fill:#b7e4b7,stroke:#1f6b1f,stroke-width:2px,color:#0b2a0b;
    classDef fail    fill:#f2b8b8,stroke:#8a1f1f,stroke-width:1px,color:#2a0b0b;
    classDef async   fill:#c9dcf5,stroke:#28518a,stroke-width:1px,color:#0b1f3a;
    classDef data    fill:#f2e6a8,stroke:#8a6b1f,stroke-width:1px,color:#2a220b;
```

How to read this chart:

- **The single success condition is `Success` (green, thick edge).** There is exactly one accepting state in the whole per-attempt pipeline: `VerOK == Yes ==> Success`, styled green and reached via a thick `==>` edge, rendered as a stadium `([...])` node. Every other terminal — `Exhausted`, `BorderAbort`, `ForcedFail`, and every `*Fail` node — is a non-success outcome that either ends the outer loop or re-enters via `Reschedule`. When tracing a "teleport never completes" bug, the question is always "why did control never reach `Success`?"

- **Every rejection path ends the same way** — attribute a `FailTypes` bucket, optionally `addBadLocation` on a `MemoryShape` so the spiral won't re-propose it, release the `ChunkReservation` if one was allocated (diagram 03), and call `rescheduleNextAttempt`. This uniformity is why S-004 (no silently discarded failures) can be audited from a single place.
- **`FailTypes` is the public failure vocabulary.** The buckets (`biome`, `worldBorder`, `vert`, `safety`, `safetyExternal`, `nullChunk`, `timeout`, `misc`) are the only labels any admin ever sees in `rtp verbose` output; the sub-reason strings (`neighborNull`, `ticketFailed`, `chunkLoadTimeout`, `asyncLoadNull`, `staleChunkBeforeVert`, `OUTSIDE_BORDER`, `biome=<NAME>`) are diagnostics layered on top. A regression guard (`ReqRtpS004NullChunkAttributionTest`) locks the `nullChunk` attribution — do not refactor without reading it.
- **Shape is the algorithmic heart** — `MemoryShape.rand()` is the bounded Archimedean spiral 1D mapping (ADR-001). `biomeRecall` swaps the uniform pick for a prefix-sum weighted pick over previously accepted-by-biome entries — it dramatically speeds up rare-biome targeting at the cost of clustering (`biomeRecallForced: false` falls back to uniform when memory is empty).
- **Probe-first column check (ADR-109).** Before invoking full chunk resolution, `ProbeFirstGovernor` determines whether to run a lean column probe (`probeChunkColumn`) across `minY-1..maxY`. If the column fails standing-surface safety (e.g. lava, ocean), the candidate is rejected in $<0.2\text{ ms}$ without decoding the full chunk payload, dynamically adapting probe usage based on an EWMA cost-benefit model.
- **Purpose-gated live loads (ADR-110).** Background queue refills run under `LoadPurpose.SPECULATIVE`. The task calls `world.getOrReadChunk(cx, cz)` to read resident cache or Anvil disk files off-tick. Chunks missing from disk require acquiring a pin via `LiveLoadGate.tryAcquirePin()` bounded by `activeChunkCap`; if pin slots are full or `HeapPressureMonitor` signals memory distress, candidate attempts defer cleanly rather than competing with active player traffic or thrashing the JVM heap.
- **Cost-governed on-demand deduplication & native admission (ADR-114).** Immediate on-demand tasks (`LoadPurpose.IMMEDIATE`: empty-queue fallback, `/rtp biome:...`, unqueued teleports) bypass speculative gating and follow a thread-weighted deduplication policy. Chunk presence on disk is checked in $<1\ \mu\text{s}$ via `AnvilRegionOccupancyCache`. If present, the governor evaluates whether candidate rejection rates justify off-thread pre-filtering ($P_{\text{reject}} > \frac{C_{\text{file, async}}}{C_{\text{native, async}} + W_{\text{tick}}(\text{MSPT}) \cdot C_{\text{native, sync}}}$). In safe terrain under stable ticks, the task bypasses Anvil and loads directly to eliminate duplicate decompression and NBT parsing. In hazardous terrain or under tick distress ($\text{MSPT} > 35\text{ ms}$), off-thread Anvil pre-filtering is engaged to shed deadweight native generation and protect communal tick health. Native loads are admitted through `ImmediateLoadGovernor` in-flight semaphores (max 1 per region, max 2 global) with tick damping $\Phi_{\text{tick}}(\text{MSPT})$. See diagram 15 for the dedicated specification.
- **Folia regional thread handoff (ADR-112).** Chunk futures complete off the region thread. `PregenTask` continues candidate evaluation asynchronously before dispatching to the owning region scheduler for arrival verification and stale-chunk gating.
- **Self-contained vs live-backed chunks branch the threading model.** Anvil-backed chunks (`isSelfContained() == true`, see ADR-016) are thread-safe for reads and stay on the async worker; live server chunks must hop to the region-owning thread via `dispatchLiveEvaluation`, which also allocates the `ChunkReservation` (see diagram 03 for ticket lifecycle) and arms the ADR-015 stale-chunk guard. Getting this branch wrong is the single most common S-005 violation when porting to a new platform.
- **`maxAttempts` is soft.** Every biome reject and every worldborder reject *increments* `maxAttempts` (up to the biome-checks cap), so the loop has a budget proportional to legitimate misses. This is deliberate: `vert` and `safety` failures cost a hard attempt, but biome misses are cheap and effectively free against the cap until `maxBiomeChecks` is hit.
- **Success preloads a view-distance `ChunkSet`.** `completeSuccess` does not just return coordinates — it allocates (asynchronously) the chunks the player will see on arrival (`max(safetyRadius, viewDistanceSelect)` radius) and transfers ownership of that `ChunkSet` via `GenerationResult` so the caller can hand it to the LOAD stage (diagram 01) without a second round-trip.
- **Config keys that change behavior here**: `RegionSettings.shape`, `vert`, `cacheCap`, `spatialResolution`, `unsafeBlocks`, `safetyRadius`, `biomeWhitelist`, `biomeRecall`, `biomeRecallForced`, `maxAttempts`, `maxBiomeChecks`, `worldBorderOverride`, plus `performance.viewDistanceSelect`. Everything else is code-level and should not be edited per region.

Related code:

- `rtp-core`: `PregenTask`, `QueueTask`, `PregenState`, `LocationGenerator`, `LiveLoadGate`, `ProbeFirstGovernor`, `ImmediateLoadGovernor`, `GlobalRegionVerifiers`, `selectors/memory/shapes/MemoryShape`, `selectors/verticalAdjustors/*`.
- `rtp-api`: `ILocationGenerator`, `GenerationContext`, `GenerationResult`, `RTPChunk`, `RTPWorld`, `LoadPurpose`.
- ADRs: [ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md) (spiral), [ADR-015](../adr/ADR-015-stale-chunk-guard-countbound-pipes.md) (stale guard + Option B), [ADR-016](../adr/ADR-016-anvil-subsystem.md) (Anvil probe-first), [ADR-109](../adr/ADR-109-measured-cost-probe-first-gate.md) (measured-cost probe-first gate), [ADR-110](../adr/ADR-110-purpose-gated-live-loads.md) (purpose-gated live loads), [ADR-112](../adr/ADR-112-per-region-work-division-on-folia.md) (per-region work division on Folia), [ADR-114](../adr/ADR-114-cost-governed-on-demand-chunk-reads.md) (cost-governed on-demand chunk reads).
- Companion deep-dive: [`docs/architecture/15-cost-governed-chunk-reads-and-native-admission.md`](15-cost-governed-chunk-reads-and-native-admission.md).
- Requirements: `REQ-RTP-S-004` (no silent failures), `REQ-RTP-S-005` (no sync chunk I/O on main thread) — see [`TRACEABILITY.md`](../dev/TRACEABILITY.md).
