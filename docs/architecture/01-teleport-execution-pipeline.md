# Teleport execution pipeline

**Scope of this diagram.** This chart covers the end-to-end lifecycle of a *single* `/rtp` teleport once `SelectionAPI.getRegion` has already resolved a region (diagram 08) — from the cache-vs-queue-vs-unqueued branch, through the async `SETUP` → `LOAD` stages, region-thread safety eval, entity-scheduler teleport, and guaranteed cleanup. This is the **outer loop** that orchestrates all other pipelines. Related-but-separate behavior paths are intentionally **out of scope** here and documented elsewhere:
- **How a candidate `(x, y, z)` is accepted or rejected inside one attempt** — see diagram 09 (location selection per attempt); `EvalBlocks` in this chart is a single node that expands into that entire flowchart.
- **How the cache was filled** — see diagram 02 (budgeted cache generator); `QueueWait` simply pops a result prepared by that loop.
- **How chunk tickets are actually issued and released** — see diagram 03 (chunk ticket lifecycle); `ReqTicket` and `Teardown` are abstractions over it.
- **Region / world resolution** — see diagram 08 (`/rtp` command region selection); this chart starts after a region has been chosen.
- **Failure attribution and user-facing messages** — see `CODE_TOUR.md` §7 and S-004 / S-007.

> Companion walkthrough: [`CODE_TOUR.md` §2 — Teleport pipeline (end-to-end)](../dev/CODE_TOUR.md).

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
        GenRandom : [SETUP Stage] Generate (X,Z)
        ReqTicket : [LOAD Stage] addPluginChunkTicket()
    }

    state Region_Thread_Context {
        EvalBlocks : Evaluate Safety (isSafe)
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
    QueueWait --> ReqTicket : Location Prepared (Spawn Async Worker)

%% Unqueued Path
    perm_check --> GenRandom : Has 'unqueued' perm (Start SETUP)

%% Generation & Ticket Pipeline
    GenRandom --> ReqTicket : Coords Generated
    ReqTicket --> EvalBlocks : CompletableFuture.thenAccept(...)

%% Evaluation Branches
    EvalBlocks --> MovePlayer : Safe Landing Found
    EvalBlocks --> GenRandom : Unsafe (Retry Search Loop)
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
    class GenRandom,ReqTicket,EvalBlocks async
    class QueryCache,QueueWait data
    class Teardown cleanup
```