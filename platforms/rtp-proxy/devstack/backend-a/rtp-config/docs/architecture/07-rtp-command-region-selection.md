# `/rtp` command — world/region selection

**Scope of this diagram.** This chart covers *only* the `/rtp` (and alias `/wild`) command path from dispatch through `SelectionAPI.getRegion(player)` to the point where the teleport pipeline (diagram 01) or the public queue (diagram 02) takes over. Related-but-separate behavior paths are intentionally **out of scope** here and documented elsewhere:

- **onEvent auto-teleport** (join / respawn / …) — different entry point (`OnPlayerJoin`, `OnPlayerRespawn`, etc.) gated by `rtp.onevent.<event>` and `onEventParsing`.
- **`SelectionAPI.tempRegion(...)`** — addon/command extensibility that bypasses the world/region override loops by overriding `RegionKeys` directly against a base region.
- **`/rtp scan`** — a separate long-lived worker with its own throttle; see diagram 05.
- **Pipeline stages after `Start LOAD`** — see diagram 01.
- **Cache replenishment** driving the "Cache has verified location?" decision — see diagram 02.

```mermaid
flowchart TD
    Start([Player runs /rtp<br/>or /wild]) --> CmdParse

%% 1. Command layer decision
    subgraph CommandLayer [Command Layer — commands-api + Bukkit dispatch]
        CmdParse{Valid args?}
        CmdParse -- No --> MsgBad[msgBadParameter<br/>messages.yml<br/>REQ-RTP-F-013 / S-007]
        CmdParse -- Yes --> BusyCheck{RTP busy?<br/>inFlightCalculations}
        BusyCheck -- Yes --> MsgBusy[msgBusy<br/>messages.yml<br/>REQ-RTP-F-013 / S-007]
        BusyCheck -- No  --> ResolvePlayer
    end

%% 2. World + Region selection (SelectionAPI.getRegion(player))
    subgraph WorldResolution [World resolution — WorldKeys.requirePermission / override loop]
        ResolvePlayer[player.getLocation.world.name] --> WorldPerm{worldParser<br/>requirePermission?}
        WorldPerm -- No  --> PickRegion
        WorldPerm -- Yes --> WorldHasPerm{player has<br/>rtp.worlds.<name>?}
        WorldHasPerm -- Yes --> PickRegion
        WorldHasPerm -- No  --> WorldOverride[worldName :=<br/>WorldKeys.override<br/>default: default]
        WorldOverride --> LoopGuardW{worldName seen<br/>before?}
        LoopGuardW -- Yes --> ThrowW[IllegalStateException<br/>infinite override loop]
        LoopGuardW -- No  --> WorldPerm
    end

    subgraph RegionResolution [Region resolution — RegionKeys.requirePermission / override loop]
        PickRegion[regionName :=<br/>WorldKeys.region] --> RegionPerm{regionParser<br/>requirePermission?}
        RegionPerm -- No  --> RegionReady
        RegionPerm -- Yes --> RegionHasPerm{player has<br/>rtp.regions.<name>?}
        RegionHasPerm -- Yes --> RegionReady
        RegionHasPerm -- No  --> RegionOverride[regionName :=<br/>RegionKeys.override]
        RegionOverride --> LoopGuardR{regionName seen<br/>before?}
        LoopGuardR -- Yes --> ThrowR[IllegalStateException<br/>infinite override loop]
        LoopGuardR -- No  --> RegionPerm
    end

%% 3. Queue vs unqueued split
    RegionReady[Region resolved] --> UnqPerm{player has<br/>rtp.unqueued?}
    UnqPerm -- Yes --> CachePop{Cache has<br/>verified location?}
    UnqPerm -- No  --> CachePop
    CachePop == Yes ==> StartLOAD([Start LOAD stage<br/>see diagram 01]):::success
    CachePop -- No --> UnqPerm2{has rtp.unqueued?}
    UnqPerm2 -- Yes --> AdHoc[Spawn async search now<br/>SETUP stage]:::async
    UnqPerm2 -- No  --> EnqueuePublic[Park in public queue<br/>wait for selectionAPI pulse<br/>see diagram 02]:::async

%% 4. Per-region behavior is DATA, not code (see prose below)
    ShapeChoice[RegionSettings<br/>shape / vert / cacheCap /<br/>worldBorderOverride / price]:::data
    RegionReady --> ShapeChoice

%% Color legend: green = happy path into LOAD, red = config-bug throws / bad-param, blue = async/queue work, yellow = data-driven settings
    classDef success fill:#b7e4b7,stroke:#1f6b1f,stroke-width:2px,color:#0b2a0b;
    classDef fail    fill:#f4b7b7,stroke:#8a1f1f,color:#3a0b0b;
    classDef async   fill:#cfe2ff,stroke:#1f4e8a,color:#0b1f3a;
    classDef data    fill:#fff2b3,stroke:#8a6d1f,color:#3a2f0b;
    class MsgBad,MsgBusy,ThrowW,ThrowR fail
```

How to read this chart:

- **The decision tree is almost entirely data-driven.** Nothing in `rtp-core` hardcodes "if world == X, use shape Y". Behavior is the product of `world.yml`, `region.yml`, `messages.yml`, plus per-player permission nodes.
- **Two nested override loops** — world-level (`WorldKeys.override`) and region-level (`RegionKeys.override`) — protect against infinite loops via a `Set<String> attempted` guard, throwing `IllegalStateException` on cycle detection. This is the only place in the normal flow that throws rather than attributes a failure, because it is a configuration bug, not a teleport failure.
- **Permission nodes involved** (non-exhaustive):
  - `rtp.worlds.<worldName>` — gates access to a world; failure falls through the world `override`.
  - `rtp.regions.<regionName>` — gates access to a region; failure falls through the region `override`.
  - `rtp.unqueued` — bypasses the public queue and forces an ad-hoc async search. Dangerous on large servers; see [ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md) on why the search is still bounded.
  - `rtp.effect.<stage>.*` — decorates pipeline stages with sounds/particles/etc. (see [`docs/admin/EVENTS_AND_EFFECTS.md`](../admin/EVENTS_AND_EFFECTS.md)).
  - `rtp.onevent.<event>` — opts the player into auto-teleport on lifecycle events (join / respawn / …). Requires `onEventParsing: true`.
- **`RegionSettings`** (`shape`, `vert`, `cacheCap`, `activeChunkCap`, `price`, `spatialResolution`, `worldBorderOverride`) is the *only* source of truth for per-region behavior. To change a region's shape or cache size, edit `region.yml`; never fork code per region.
- **Busy / invalid messages are configurable** (S-007, REQ-RTP-F-013). All user-visible strings come from `messages.yml`; never hardcode them in a command or adapter.
- **`tempRegion(...)`** (SelectionAPI) lets an addon or command spawn a one-shot region by overriding specific keys against a named base region (default: `default`). It is the supported extensibility point for custom per-call behavior — prefer it over constructing `Region` directly.
