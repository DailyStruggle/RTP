# Claim Plugin Compatibility & Audit Reference

This document provides the canonical compatibility, API interaction model, and version matrix for all 18 third-party land-claim and territory protection systems supported by LeafRTP (16 Bukkit/Paper/Folia checkers in `LeafRTPClaimAddon` and 2 native mod checkers on Fabric/NeoForge).

Claim integrations are packaged in `addons/LeafRTPClaimAddon` (Bukkit/Paper/Folia) and native mod platforms (`rtp-fabric`, `rtp-neoforge`). Every integration blocks teleports into claimed land.

---

## 1. Supported Integration Matrix

| Protection Plugin / System | Platform | Integration Seam | Query Style | Boundary Provider | Tested Upstream Releases |
|---|---|---|---|---|---|
| **Towny Advanced** | Bukkit / Paper / Folia | `TownyAdvancedChecker` | Native CompileOnly (`TownyAPI.isWilderness`) | Yes (`TownyBoundaryProvider`) | `0.100.x`, `0.99.x`, `0.98.x` |
| **GriefPrevention** | Bukkit / Paper / Folia | `GriefPreventionChecker` | Native CompileOnly (`DataStore.getClaimAt`) | Yes (`GriefPreventionBoundaryProvider`) | `16.18.x`, `16.17.x` |
| **Lands** | Bukkit / Paper / Folia | `LandsChecker` | Native CompileOnly (`LandsIntegration.isClaimed`) | Yes (`LandsChecker.getBoundaryAt`) | `7.x`, `6.x` |
| **SaberFactions** / FactionsUUID (`Factions`) | Bukkit / Paper / Folia | `SaberFactionsChecker` | Reflective (`Board.getInstance().getFactionAt(new FLocation(Location))`, `Faction.isWilderness`) | Yes (`FactionsBoundaryProvider`) | Best effort: tested against stubs of the SaberFactions / FactionsUUID `1.6.x` source, not a live server |
| **FactionsBridge** | Bukkit / Paper / Folia | `FactionsBridgeChecker` | Reflective (`FactionsBridge.getFactionsAPI().getFactionAt(Location)`, `Faction.isWilderness`) | No | Best effort: tested against stubs of the `1.4.2` source, not a live server |
| **GriefDefender** | Bukkit / Paper / Folia | `GriefDefenderChecker` | Native CompileOnly (`G griefDefender.getCore().getClaimAt`) | No | `2.1.x`, `2.0.x` |
| **WorldGuard** | Bukkit / Paper / Folia | `WorldGuardChecker` | Native CompileOnly (`RegionManager.getApplicableRegions`) | No | `7.0.x`, `7.1.x` |
| **RedProtect** | Bukkit / Paper / Folia | `RedProtectChecker` | Native CompileOnly (`RedProtect.get().getAPI().getRegion`) | No | `7.7.x` |
| **Residence** | Bukkit / Paper / Folia | `ResidenceChecker` | Reflective (`Residence.getInstance().getResidenceManager().getByLoc(Location)`) | No | Best effort: tested against stubs of the `6.0.x` source, not a live server |
| **CrashClaim** | Bukkit / Paper / Folia | `CrashClaimChecker` | Reflective (`CrashClaim.getPlugin().getApi().getClaim(Location)`) | No | Best effort: tested against stubs of the `1.0.42` source, not a live server |
| **HuskClaims** | Bukkit / Paper / Folia | `HuskClaimsChecker` | Reflective (`BukkitHuskClaimsAPI.getInstance().isClaimAt(Position)`) | No | Best effort: tested against stubs of the `1.5.x` source, not a live server |
| **HuskTowns** | Bukkit / Paper / Folia | `HuskTownsChecker` | Reflective (`BukkitHuskTownsAPI.getInstance().getClaimAt(Location)`) | No | Best effort: tested against stubs of the `3.x` source, not a live server; `2.x` unsupported |
| **PlotSquared** | Bukkit / Paper / Folia | `PlotSquaredChecker` | Reflective (`Location.at(String, int, int, int)`, `isPlotRoad`, `getPlot().hasOwner()`; owned plots and roads rejected) | No | Best effort: tested against stubs of the `v7` source, not a live server |
| **KingdomsX** (`Kingdoms`) | Bukkit / Paper / Folia | `KingdomsXChecker` | Reflective (`Land.getLand(Location).isClaimed()`) | No | Best effort: written from the KingdomsX API wiki (no public source); not tested on a live server |
| **UltimateClaims** | Bukkit / Paper / Folia | `UltimateClaimsChecker` | Reflective (`UltimateClaims.getInstance().getClaimManager().getClaim(String, int, int)`; `com.craftaro` or `com.songoda`) | No | Best effort: tested against stubs of the `2.4.0` source, not a live server |
| **minePlots** (mineCodes) | Bukkit / Paper / Folia | `MinePlotsChecker` | Direct API (`PlotServiceApi.getPlot(Location)` from the ServicesManager) | No | Best effort: compiled and tested against the published `plugin-api` `4.6.2`, not yet a live minePlots server |
| **FTB Chunks** | Fabric / NeoForge | `FTBChunksChecker` | Mod SPI (`FTBChunksAPI.isChunkClaimed`) | Planned | `2001.x` (1.20.x), `2100.x` (1.21.x) |
| **OpenPartiesAndClaims** | Fabric / NeoForge | `OpenPartiesAndClaimsChecker` | Mod SPI (`IOpenPartiesAndClaimsAPI.isClaimed`) | No | `0.21.x+` |

---

## 2. API Design & Safety Contracts

### Fail-Closed Execution
All checkers implement a strict **fail-closed on error** policy ([ADR-118](../adr/ADR-118-claim-checkers-compile-against-published-apis.md)):
- If the claim plugin is not installed or not enabled, the check reports "not claimed".
- If the claim plugin is enabled and throws during verification, the destination is rejected and a warning is logged (throttled to one per minute per integration).
- If the claim plugin is enabled but its API does not match what the addon expects (missing class or method), every destination is rejected and one error is logged per integration until `/rtp reload`. Update the claim plugin or the addon, or set the integration's `integrations.yml` flag to `false`, to resume teleports.

### Decoupled Reflection Architecture
Integrations marked **Reflective** do not link compile-time classes into the LeafRTP jar. They dynamically inspect public API singletons at runtime:
- No hard compile dependencies that could introduce transitive version conflicts.
- Graceful no-op when the plugin is absent or uninstalled.
- Zero classpath pollution on platforms running alternative fork implementations.

### Folia Threading Caveats
LeafRTP checks destinations across async workers and regional threads:
- Synchronous claim verifiers perform fast in-memory spatial tree lookups (`DataStore`, spatial hash tables, or chunk maps) without initiating disk chunk I/O, so they never load chunks on the main thread.
- Where claim plugins mutate internal state or are not thread-safe, LeafRTP provides asynchronous verification APIs (`RegionVerifierRegistry.registerAsync`) to safely hop to the target region thread when required.

---

## 3. Configuration

Claim verifiers are enabled in `plugins/RTP/addons/integrations.yml`:

```yaml
version: 1.0

# Set a key to true to prevent RTP destinations from landing in protected land
rerollTownyAdvanced: false
rerollGriefPrevention: false
rerollLands: false
rerollWorldGuard: false
rerollSaberFactions: false
rerollGriefDefender: false
rerollPlotSquared: false
rerollHuskClaims: false
rerollHuskTowns: false
rerollKingdomsX: false
rerollResidence: false
rerollRedProtect: false
rerollCrashClaim: false
rerollUltimateClaims: false
rerollMinePlots: false
rerollFactionsBridge: false
```

Reload configuration at runtime via `/rtp reload` without restarting the server.
