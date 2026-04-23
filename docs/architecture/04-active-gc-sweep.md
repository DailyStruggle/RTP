# Active GC sweep

**Scope of this diagram.** This chart covers the periodic background sweep that audits `MemoryTracker`'s registered allocations — cancelling stale `TeleportPipelineTask` instances, releasing abandoned `ChunkReservation` tickets, and logging leaks. This is the **safety net** that catches any allocation which escaped a normal cleanup path in diagrams 01, 02, 03, or 05. Related-but-separate behavior paths are intentionally **out of scope** here:
- **Normal cleanup** — every pipeline in diagrams 01/02/05 releases its own tickets/tasks on success and on expected failure; GC only runs for *abandoned* allocations (player disconnect mid-LOAD, exception below `thenAccept`, plugin reload races).
- **Chunk ticket semantics** — see diagram 03 for what `reservation.close()` actually does.
- **ChunkUnloadProcessor** (non-Folia per-tick unload pacing) — a different timer, separate concern; see `CODE_TOUR.md` §10 and diagram 07.

> Companion walkthrough: [`CODE_TOUR.md` §5 — Active GC sweep](../dev/CODE_TOUR.md).

```mermaid
stateDiagram-v2
    TimerTrigger : Async GC Timer Pulse
    [*] --> TimerTrigger

%% 1. Define State Containers (Strict Parser Syntax)
    state Internal_Tracking_Sweep {
        FetchMap : Iterate Tracked Reservations
        CheckTimeout : Age > Configured Timeout?
        ForceClose : reservation.close()
        DecCounter : inFlight.getAndDecrement()
        Untrack : Remove from Tracked Map
    }

    state Native_Ticket_Sweep {
        FetchServer : Query Server for RTP Tickets
        CheckTracked : Is Ticket Tracked Internally?
        DropOrphan : Remove Plugin Chunk Ticket
    }

%% 2. Internal Sweep Logic
    TimerTrigger --> FetchMap
    FetchMap --> CheckTimeout

%% Internal Branches
    CheckTimeout --> ForceClose : Yes (Stalled)
    ForceClose --> DecCounter
    DecCounter --> Untrack
    Untrack --> FetchMap : Loop Next

    CheckTimeout --> FetchMap : No (Healthy, Loop Next)

%% 3. Transition to Native Sweep
    FetchMap --> FetchServer : Internal Sweep Complete

%% 4. Native Sweep Logic
    FetchServer --> CheckTracked

%% Native Branches
    CheckTracked --> DropOrphan : No (Orphaned Leak Found)
    DropOrphan --> FetchServer : Loop Next

    CheckTracked --> FetchServer : Yes (Valid, Loop Next)

%% Termination
    FetchServer --> [*] : GC Pulse Complete

%% Color legend: red = leak caught (forced close / orphan drop), blue = async timer / sweep, yellow = bookkeeping iteration
    classDef fail  fill:#f4b7b7,stroke:#8a1f1f,color:#3a0b0b;
    classDef async fill:#cfe2ff,stroke:#1f4e8a,color:#0b1f3a;
    classDef data  fill:#fff2b3,stroke:#8a6d1f,color:#3a2f0b;
    class ForceClose,DropOrphan fail
    class TimerTrigger,FetchMap,FetchServer async
    class CheckTimeout,CheckTracked,DecCounter,Untrack data
```