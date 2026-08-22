# Budgeted cache generator (queue refill)

**Scope of this diagram.** This chart covers the *background* loop that keeps each region's location cache warm: the periodic `SelectionAPI.compute()` pulse, the region-by-region budget enforcement (`queueLen` / maxBiasedAttempts), the hand-off to `LocationGenerator`, and the insertion of successful results into the cache that diagram 01 pops from. Related-but-separate behavior paths are intentionally **out of scope** here:
- **What happens inside one candidate attempt** — see diagram 08 (location selection per attempt); `GenerateLocation` in this chart expands into that flowchart.
- **How a waiting `/rtp` consumes the cache** — see diagram 01 (`QueryCache` / `QueueWait`).
- **Chunk ticket book-keeping** — see diagram 03; every generated candidate reserves a ticket via `ChunkReservation`.

> Companion walkthrough: [`CODE_TOUR.md` section 3 — Budgeted cache generator](../dev/CODE_TOUR.md).

```mermaid
stateDiagram-v2
    PulseTrigger : selectionAPI.compute() [Async]
    [*] --> PulseTrigger

%% 1. Define State Containers (Strict Parser Syntax)
    state Budgeted_Task_Pipeline {
        InitBudget : Initialize Execution Budget
        CheckBudget : Budget Remaining? (Time or Count)
        CheckPeriod : Is Region Due? (Based on 'period')
        ExecuteRegion : region.execute() [Platform Scheduler]
        YieldTask : Yield to next tick/pulse
    }

    state Cache_Refill_State {
        SpawnWorker : Spawn Async LocationSearchWorker
        PushQueue : Add Valid Location to Cache
        WakePlayer : Un-stall Public Queue
    }

%% 2. Map Transitions
    PulseTrigger --> InitBudget

%% Budget Loop
    InitBudget --> CheckBudget
    CheckBudget --> CheckPeriod : Yes (Budget Available)

%% Period Rotation Gating
    CheckPeriod --> ExecuteRegion : Turn Reached (Execute)
    CheckPeriod --> CheckBudget : Not Due (Skip to Next Region)

    ExecuteRegion --> CheckBudget : Loop Next Region
    CheckBudget --> YieldTask : No (Budget Exhausted)

%% Fulfillment Branch
    ExecuteRegion --> SpawnWorker : Region Needs Locations
    SpawnWorker --> PushQueue
    PushQueue --> WakePlayer : (If players waiting)

%% Terminations
    WakePlayer --> [*]
    YieldTask --> [*]

%% Color legend: green = cache successfully grew / consumer woken, blue = async worker / scheduler pulse, yellow = cache touchpoint, grey = yield
    classDef success fill:#b7e4b7,stroke:#1f6b1f,stroke-width:2px,color:#0b2a0b;
    classDef async   fill:#cfe2ff,stroke:#1f4e8a,color:#0b1f3a;
    classDef data    fill:#fff2b3,stroke:#8a6d1f,color:#3a2f0b;
    classDef cleanup fill:#e2e2e2,stroke:#555555,color:#111111;
    class PushQueue,WakePlayer success
    class PulseTrigger,SpawnWorker,ExecuteRegion async
    class InitBudget,CheckBudget,CheckPeriod data
    class YieldTask cleanup
```