# `/rtp scan` task crawler

**Scope of this diagram.** This chart covers the admin-triggered bulk pre-warm loop — `/rtp scan <region> <count>` — that crawls a region's spiral index in bounded ticks, pushes accepted locations into the same per-region cache that diagram 02 fills, and reports progress. Related-but-separate behavior paths are intentionally **out of scope** here:
- **What makes a single candidate acceptable** — see diagram 09 (per-attempt pipeline); scan just invokes it in a loop.
- **How `/rtp` consumes those pre-warmed locations** — see diagram 01 (cache-hot branch).
- **Background refill without admin intervention** — see diagram 02; the two crawlers both write to the same cache but scan is one-shot, budget-bounded by count rather than `queueLen`.
- **Command parsing / permissions for `/rtp scan`** — see `commands-api` and `BukkitBaseRTPCmd`; this chart starts after the command has been dispatched.

> Companion walkthrough: [`CODE_TOUR.md` §6 — Scan task crawler](../dev/CODE_TOUR.md).

```mermaid
stateDiagram-v2
    CmdTrigger : Admin executes /rtp scan
    [*] --> CmdTrigger

%% 1. Define State Containers (Strict Parser Syntax)
    state Async_Scan_Worker {
        CheckLimit : Check Scan Budget & Pause State
        AcquireGate : inFlightGate.acquire() (Throttle)
        GenCoords : shape.locationToXZ()
        BorderCheck : Fast WorldBorder Math
        ReqChunk : world.getOrLoadChunk()
        DrainGate : inFlightGate.acquire(MAX_PENDING)
    }

    state Future_Callback_Context {
        CheckContained : isSelfContained()? (Anvil Check)
        AnvilBiome : Fast Biome Pre-filter
    }

    state Region_Thread_Context {
        VertAdjust : vert.adjust() (Find Surface)
        PhysBiome : Live Biome Verification
        SafeCheck : Radius isSafe() Check
        MarkBad : shape.addBadLocation()
    }

    state Batch_WrapUp_Phase {
        CalcStats : Calculate CPS, ETA, Land %
        SaveState : Save to .scan disk file
        YieldTask : RTP.scheduler.runTaskAsynchronously()
    }

%% 2. The Throttled Worker Loop
    CmdTrigger --> CheckLimit
    CheckLimit --> AcquireGate : Budget Available
    CheckLimit --> DrainGate : Budget Exhausted

    AcquireGate --> GenCoords
    GenCoords --> BorderCheck
    BorderCheck --> ReqChunk : Inside Border
    BorderCheck --> MarkBad : Outside Border (Math Reject)

%% 3. Chunk Loading & Callback
    ReqChunk --> CheckContained : .whenComplete(...)
    CheckContained --> AnvilBiome : Yes (From Disk)
    CheckContained --> VertAdjust : No (Live Generation)

    AnvilBiome --> VertAdjust : Passed Pre-filter
    AnvilBiome --> MarkBad : Failed Pre-filter

%% 4. Region Thread Block Evaluation
    VertAdjust --> PhysBiome : Surface Found
    VertAdjust --> MarkBad : Void/Ceiling

    PhysBiome --> SafeCheck : Biome Valid
    PhysBiome --> MarkBad : Biome Invalid

    SafeCheck --> ReleaseGate : Safe
    SafeCheck --> MarkBad : Lava / Unsafe Block

    MarkBad --> ReleaseGate

    ReleaseGate : inFlightGate.release()
    ReleaseGate --> CheckLimit : Queue Next Point

%% 5. Wrap-Up & Checkpointing
    DrainGate --> CalcStats : Await trailing chunks
    CalcStats --> SaveState : Log Output
    SaveState --> YieldTask : Range Not Met
    SaveState --> [*] : Scan Complete / Cancelled

%% Color legend: green = safe location found, red = rejected candidate, blue = async/scheduler work, yellow = bookkeeping/checkpoint
    classDef success fill:#b7e4b7,stroke:#1f6b1f,stroke-width:2px,color:#0b2a0b;
    classDef fail    fill:#f4b7b7,stroke:#8a1f1f,color:#3a0b0b;
    classDef async   fill:#cfe2ff,stroke:#1f4e8a,color:#0b1f3a;
    classDef data    fill:#fff2b3,stroke:#8a6d1f,color:#3a2f0b;
    class SafeCheck success
    class MarkBad fail
    class CmdTrigger,AcquireGate,ReqChunk,DrainGate,YieldTask async
    class CheckLimit,BorderCheck,CheckContained,AnvilBiome,VertAdjust,PhysBiome,ReleaseGate,CalcStats,SaveState data
```