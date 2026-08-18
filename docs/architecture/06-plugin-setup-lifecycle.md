# Plugin setup lifecycle

**Scope of this diagram.** This chart covers the one-shot Bukkit-family plugin lifecycle — `onLoad` (JDBC probe / fail-fast) → `onEnable` (strictly ordered: metrics, server-model resolution, reflective accessor wiring, DB setup, Chunky probe, startup-task drains, command binding, event registration, fallback-region rebind, integrations, effects, non-Folia `ChunkUnloadProcessor`, DB processing loop, PAPI, doc extraction) → `onDisable` bail-out on any failure. This is the **setup-time counterpart** to every runtime loop in diagrams 01–05 and 08–09. Related-but-separate behavior paths are intentionally **out of scope** here:
- **Fabric entry point** — Fabric uses a different bootstrap (`ModInitializer`), out of scope per `REQUIREMENTS.md section 0`.
- **Runtime teleport / cache / GC / scan loops** — see diagrams 01, 02, 04, 05; this chart ends once those loops are running.
- **Config reload** — a separate code path (`/rtp reload`) that re-runs a subset of `onEnable` steps.
- **Per-command dispatch** — see diagrams 08 (region selection) and 09 (location selection); this chart stops at `BindCmds`.

> Companion walkthrough: [`CODE_TOUR.md` section 10 — Plugin setup lifecycle](../dev/CODE_TOUR.md).

```mermaid
stateDiagram-v2
    BukkitLoad : Server classloads plugin JAR
    [*] --> BukkitLoad

%% 1. onLoad Phase (pre-world)
    state onLoad_Phase {
        ProbeSQLite : Class.forName org.sqlite.JDBC
        FailFast    : throw IllegalStateException
    }

%% 2. onEnable Phase (ordered)
    state onEnable_Phase {
        InitMetrics   : new Metrics(bstats)
        ResolveModel  : BukkitServerProvider.resolveServerModel()
        InstAccessor  : Reflect into RTPServerAccessor + RTPScheduler
        AccessorStart : RTP.serverAccessor.start plugin
        NewRTP        : new RTP -- wires API instance
        SetupDB       : BukkitDatabaseHandler.setupDatabase
        ChunkyProbe   : ChunkyBorderChecker.loadChunky
        RunStartup1   : RTP.startupTasks.execute MAX
        BindCmds      : bind /rtp and /wild executor and tab-completer
        DrainStartup  : runTaskLater -- drain startupTasks
        RegEvents     : setupBukkitEvents -- sync same tick
        RebindWorlds  : rebindFallbackRegionsForAllLoadedWorlds
        Integrations  : runTaskLater -- setupIntegrations
        EffectsHook   : runTaskLater -- BukkitEffectsHandler.setupEffects
        ChunkUnloader : non-Folia ChunkUnloadProcessor timer
        DBLoop        : DatabaseProcessing.start
        RunStartup2   : drain startupTasks again
        PAPI          : PAPI_expansion.register if present
        ExtractDocs   : JarUtils.extractDocs dataFolder
    }

%% 3. Failure bail-out
    state Disable_OnError {
        OnDisable : onDisable teardown
    }

%% Wiring
    BukkitLoad --> ProbeSQLite
    ProbeSQLite --> InitMetrics : OK
    ProbeSQLite --> FailFast    : JDBC missing

    InitMetrics --> ResolveModel
    ResolveModel --> InstAccessor
    InstAccessor --> AccessorStart : success
    InstAccessor --> OnDisable     : reflection failure
    AccessorStart --> NewRTP
    NewRTP --> SetupDB
    SetupDB --> ChunkyProbe : success
    SetupDB --> OnDisable   : DB failure
    ChunkyProbe --> RunStartup1
    RunStartup1 --> BindCmds
    BindCmds --> DrainStartup
    DrainStartup --> RegEvents
    RegEvents --> RebindWorlds
    RebindWorlds --> Integrations
    Integrations --> EffectsHook
    EffectsHook --> ChunkUnloader
    ChunkUnloader --> DBLoop
    DBLoop --> RunStartup2
    RunStartup2 --> PAPI
    PAPI --> ExtractDocs
    ExtractDocs --> [*] : plugin enabled

    OnDisable --> [*] : plugin disabled

%% Color legend: green = plugin ready, red = fail-fast / onDisable bail-out, blue = deferred (runTaskLater) steps, yellow = data/config wiring
    classDef success fill:#b7e4b7,stroke:#1f6b1f,stroke-width:2px,color:#0b2a0b;
    classDef fail    fill:#f4b7b7,stroke:#8a1f1f,color:#3a0b0b;
    classDef async   fill:#cfe2ff,stroke:#1f4e8a,color:#0b1f3a;
    classDef data    fill:#fff2b3,stroke:#8a6d1f,color:#3a2f0b;
    class ExtractDocs success
    class FailFast,OnDisable fail
    class DrainStartup,Integrations,EffectsHook,ChunkUnloader,DBLoop async
    class SetupDB,ChunkyProbe,BindCmds,RegEvents,RebindWorlds,PAPI data
```

Notes on ordering (do not reorder without reading):

- **`setupBukkitEvents` runs synchronously** inside `onEnable`, not via `runTaskLater`. A previous bug (see `OnWorldLoadUnload` Javadoc) missed `WorldLoadEvent`s fired on tick 1 by generators like Multiverse when the listener registration was deferred.
- **`rebindFallbackRegionsForAllLoadedWorlds`** is the safety net for worlds loaded *before* the listener was installed.
- **Integrations and effects** are deferred one tick so that other plugins have finished their own `onEnable`.
- **`startupTasks` is drained three times** (eagerly, then on tick 1, then again at end of `onEnable`) because integrations and event handlers can push new startup tasks during setup.
- **On Folia, no `ChunkUnloadProcessor`** — Folia handles chunk lifetime per region. The `isFolia()` guard is the only behavior split in this diagram.
- **`onDisable` is invoked on any fatal init failure.** It cancels all RTP-owned Bukkit tasks, kills the per-subsystem processors (`AsyncTeleportProcessing`, `SyncTeleportProcessing`, `ScanTaskProcessing`, `DatabaseProcessing`), and calls `RTP.stop()`.
