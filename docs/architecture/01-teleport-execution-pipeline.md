# Teleport execution pipeline

**Scope of this diagram.** This chart covers the end-to-end lifecycle of a *single* `/rtp` teleport once `SelectionAPI.getRegion` has already resolved a region (diagram 07) — from the cache-vs-queue-vs-unqueued branch, through the async `SETUP` → `LOAD` stages, region-thread safety eval, entity-scheduler teleport, and guaranteed cleanup. This is the **outer loop** that orchestrates all other pipelines. Related-but-separate behavior paths are intentionally **out of scope** here and documented elsewhere:
- **How a candidate `(x, y, z)` is accepted or rejected inside one attempt** — see diagram 08 (location selection per attempt); `EvalBlocks` in this chart is a single node that expands into that entire flowchart.
- **How the cache was filled** — see diagram 02 (budgeted cache generator); `QueueWait` simply pops a result prepared by that loop.
- **How chunk tickets are actually issued and released** — see diagram 03 (chunk ticket lifecycle); `ReqTicket` and `Teardown` are abstractions over it.
- **Region / world resolution** — see diagram 07 (`/rtp` command region selection); this chart starts after a region has been chosen.
- **Failure attribution and user-facing messages** — see `CODE_TOUR.md` section 7 and S-004 / S-007.

> Companion walkthrough: [`CODE_TOUR.md` section 2 — Teleport pipeline (end-to-end)](../dev/CODE_TOUR.md).

---

## Executive Overview (Macro Pipeline)

This high-level overview shows the core execution flow of an `/rtp` request. Pre-verified locations are served immediately from cache, while cache misses trigger asynchronous on-demand candidate generation (`LoadPurpose.IMMEDIATE`) using governed deduplication (ADR-114) and safety verification before handing off to player teleportation.

```mermaid
flowchart LR
    A["Player Runs /rtp"] --> B{"Location in Cache?"}

    %% Fast Path
    B -- "Yes [Instant]" --> C["Hot L1 Cache<br/>(Pre-verified Location)"]

    %% Async Generation Path
    B -- "No [On-Demand Fill]" --> D["Spiral Generator<br/>(Archimedean 1D Math)"]
    D --> E["Governed Deduplication<br/>(Tier 2 Anvil vs Direct Load)"]
    E --> F["Safety & Claim Scan<br/>(Lava, Void, Claims)"]
    F --> C

    %% Teleport Execution
    C --> G["Teleport Player<br/>(Entity Scheduler)"]

    classDef fast fill:#b7e4b7,stroke:#1f6b1f,stroke-width:2px,color:#0b2a0b;
    classDef async fill:#cfe2ff,stroke:#1f4e8a,stroke-width:1px,color:#0b1f3a;
    classDef finish fill:#d1e7dd,stroke:#0f5132,stroke-width:2px,color:#0f5132;
    class B,C fast;
    class D,E,F async;
    class G finish;
```

---

## Detailed Teleport State Machine

The detailed lifecycle below tracks thread boundaries, asynchronous chunk reservations, safety evaluation on the region thread, and teardown cleanup.

```mermaid
stateDiagram-v2
    CmdTrigger : Player executes /rtp
    [*] --> CmdTrigger

    state cache_check <<choice>>
    state perm_check <<choice>>

%% 1. Define State Containers (No quotes or aliases)
    state Command_Execution_Context {
        QueryCache : Query Region Cache
    }

    state Public_Queue_Context {
        QueueWait : Wait for SelectionAPI Pulse
    }

    state Async_Worker_Context {
        GenRandom : [SETUP Stage] Generate (X,Z) ~ f(x,z)
        GovernLoad : [LOAD Stage] Governed Deduplication & Semaphore (ADR-114)
        ReqTicket : addPluginChunkTicket() & ChunkReservation
    }

    state Region_Thread_Context {
        EvalBlocks : Evaluate Safety (isSafe) & Stale Guard
    }

    state Entity_Scheduler_Context {
        MovePlayer : Execute Teleportation
    }

    state Guaranteed_Cleanup_Phase {
        Teardown : reservation.close() & GC
    }

%% 2. Map Transitions
    CmdTrigger --> QueryCache
    QueryCache --> cache_check

%% Hot Cache Path
    cache_check --> ReqTicket : Cache Hot (Pop Location -> Start LOAD)

%% Cold Cache Logic
    cache_check --> perm_check : Cache Empty

%% Public Queue Loop
    perm_check --> QueueWait : Lacks 'unqueued' perm
    QueueWait --> QueueWait : Loop while Cache Empty
    QueueWait --> ReqTicket : Location Prepared by Refill (diagram 02)

%% Unqueued / Immediate Path (ADR-110, ADR-114)
    perm_check --> GenRandom : Has 'unqueued' perm (LoadPurpose.IMMEDIATE)

%% Generation & Ticket Pipeline
    GenRandom --> GovernLoad : Coords Generated
    GovernLoad --> ReqTicket : Dedup Bypass or Safe Prefilter (Permit Acquired)
    GovernLoad --> Teardown : In-Flight Timeout / Backpressure (msgBusy)
    ReqTicket --> EvalBlocks : Future Completes (ADR-112 Async-to-Sync Handoff)

%% Evaluation Branches
    EvalBlocks --> MovePlayer : Safe Landing Found
    EvalBlocks --> GenRandom : Unsafe (Resample s_i+1 ~ f(x,z))
    EvalBlocks --> Teardown : Max Retries Exhausted

%% Finalization
    MovePlayer --> Teardown
    Teardown --> [*] : inFlightCalculations.getAndDecrement()

%% Color legend: green = happy-path accepting step, blue = async/deferred stage, yellow = cache/data touchpoint, grey = guaranteed cleanup
    classDef success fill:#b7e4b7,stroke:#1f6b1f,stroke-width:2px,color:#0b2a0b;
    classDef async   fill:#cfe2ff,stroke:#1f4e8a,color:#0b1f3a;
    classDef data    fill:#fff2b3,stroke:#8a6d1f,color:#3a2f0b;
    classDef cleanup fill:#e2e2e2,stroke:#555555,color:#111111;
    class MovePlayer success
    class GenRandom,GovernLoad,ReqTicket,EvalBlocks async
    class QueryCache,QueueWait data
    class Teardown cleanup
```

---

## Architectural Notes & Cross-References

- **Hot cache vs unqueued dispatch:** Serving pre-verified locations from the L1 queue (`keptLocations`) avoids all generation latency. When a player possesses `rtp.unqueued` or when the public queue is depleted, `PregenTask` runs under `LoadPurpose.IMMEDIATE`.
- **Governed on-demand deduplication (ADR-114):** Immediate tasks do not blindly trigger unconstrained native chunk loads. The `ImmediateLoadGovernor` evaluates the thread-weighted threshold ($P_{\text{reject}} > \frac{C_{\text{file, async}}}{C_{\text{native, async}} + W_{\text{tick}}(\text{MSPT}) \cdot C_{\text{native, sync}}}$). In safe terrain under stable ticks, it deduplicates by bypassing Anvil and dispatching directly to native loading, eliminating duplicate decompression and NBT decoding. In hazardous terrain or under tick distress, it engages off-thread Anvil pre-filtering to shed bad candidates without consuming main-thread tick budget.
- **In-flight generation bounding:** Live native generation during unqueued bursts is strictly bounded by regional (max 1) and global (max 2) semaphores, protecting the shared tick loop from sudden MSPT spikes and watchdog timeouts.
- **Asynchronous regional handoff (ADR-112):** On Folia, chunk futures complete off the region thread. `TeleportPipelineTask` continues generation results asynchronously before handing off to the regional entity scheduler within the regional token bucket for arrival validation and teleportation.
- **Guaranteed teardown (S-002, S-004):** Chunk reservations are tracked in `MemoryTracker` and closed on every exit path (successful teleport, hazard rejection, timeout, or cancellation).

Related documentation:
- Companion deep-dive on chunk resolution: [`docs/architecture/15-cost-governed-chunk-reads-and-native-admission.md`](15-cost-governed-chunk-reads-and-native-admission.md).
- Candidate selection per attempt: [`docs/architecture/08-location-selection-per-attempt.md`](08-location-selection-per-attempt.md).
- Chunk ticket lifecycle: [`docs/architecture/03-chunk-ticket-lifecycle.md`](03-chunk-ticket-lifecycle.md).
- Governing ADRs: [ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md), [ADR-015](../adr/ADR-015-stale-chunk-guard-countbound-pipes.md), [ADR-110](../adr/ADR-110-purpose-gated-live-loads.md), [ADR-112](../adr/ADR-112-per-region-work-division-on-folia.md), [ADR-114](../adr/ADR-114-cost-governed-on-demand-chunk-reads.md).