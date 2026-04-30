# Chunk ticket lifecycle

**Scope of this diagram.** This chart covers the lifecycle of a *single* chunk ticket (`ChunkReservation`) — from allocation on behalf of a pending task, through `MemoryTracker` registration, through release on every exit path (normal, exception, disconnect, plugin disable). This is the **invariant core** that S-002 (no permanently force-loaded chunks) and S-005 (no sync chunk loads) both depend on. Related-but-separate behavior paths are intentionally **out of scope** here:
- **Who allocates the ticket and why** — see diagram 01 (teleport pipeline `ReqTicket` stage), diagram 02 (cache generator `GenerateLocation`), diagram 05 (scan crawler), and diagram 08 (per-attempt chunk resolution). All of them funnel through the state machine shown here.
- **Platform-specific async chunk-load primitive** — Spigot `PaperLib.getChunkAtAsync`, Folia `RegionizedWorld.getChunkAtAsync`, Fabric `ServerWorld.getChunkFuture` — see `rtp-spigot` / `rtp-paper` / `rtp-folia` / `rtp-fabric` world adapters.
- **Anvil pre-filter** (which *avoids* needing a ticket at all) — see `CODE_TOUR.md` §7 nuance on S-005 and ADR-016.

> Companion walkthrough: [`CODE_TOUR.md` §4 — Chunk ticket lifecycle](../dev/CODE_TOUR.md).

```mermaid
stateDiagram-v2
    [*] --> ReqTicket : Task Needs Chunk

    %% 1. Chunk Loading Context
    state Chunk_Reservation_Phase {
        ReqTicket : addPluginChunkTicket()
        TrackRes : MemoryTracker.track()
    }

    %% 2. Execution Context
    state Task_Execution_Phase {
        EvalBlocks : Block Evaluation (isSafe)
    }

    %% 3. Safety Net Context (Background GC)
    state Active_Garbage_Collection {
        SweepTask : Periodic Async Sweep
        CheckStale : Is Ticket Older Than Timeout?
        ForceClose : Force reservation.close()
    }

    %% 4. Teardown Context
    state Guaranteed_Teardown_Phase {
        CloseRes : reservation.close() [try-finally]
        DropTicket : removePluginChunkTicket()
        UntrackRes : MemoryTracker.untrack()
    }

    %% Transitions
    ReqTicket --> TrackRes
    TrackRes --> EvalBlocks : Future Completes

    %% Success / Expected Failure Path
    EvalBlocks --> CloseRes : Teleport Done / Invalid Block Found

    %% Memory Leak / Stall Path
    EvalBlocks --> SweepTask : Pipeline Stalls / Exception Thrown
    
    %% GC Loop
    SweepTask --> CheckStale
    CheckStale --> SweepTask : Ticket Valid (Keep Alive)
    CheckStale --> ForceClose : Ticket Stale (Abandoned)

    %% Teardown Funnel
    CloseRes --> DropTicket
    ForceClose --> DropTicket
    DropTicket --> UntrackRes

    UntrackRes --> [*] : RAM Freed

%% Color legend: green = RAM released (both normal + GC paths converge), red = leak-catch / forced close, blue = async work, yellow = MemoryTracker bookkeeping
    classDef success fill:#b7e4b7,stroke:#1f6b1f,stroke-width:2px,color:#0b2a0b;
    classDef fail    fill:#f4b7b7,stroke:#8a1f1f,color:#3a0b0b;
    classDef async   fill:#cfe2ff,stroke:#1f4e8a,color:#0b1f3a;
    classDef data    fill:#fff2b3,stroke:#8a6d1f,color:#3a2f0b;
    class UntrackRes success
    class ForceClose fail
    class ReqTicket,EvalBlocks,SweepTask async
    class TrackRes,CheckStale,CloseRes,DropTicket data
```