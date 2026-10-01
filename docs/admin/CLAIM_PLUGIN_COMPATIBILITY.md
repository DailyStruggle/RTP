# Claim Plugin Compatibility & Audit Reference

This document provides the canonical compatibility, API interaction model, and version matrix for all 18 third-party land-claim and territory protection systems supported by LeafRTP (16 Bukkit/Paper/Folia checkers in `LeafRTPClaimAddon` and 2 native mod checkers on Fabric/NeoForge).

Claim integrations are packaged in `addons/LeafRTPClaimAddon` (Bukkit/Paper/Folia) and native mod platforms (`rtp-fabric`, `rtp-neoforge`). All integrations enforce **REQ-RTP-S-003** (no teleport into claim-protected land) and route through `RTPHooks.verifiers()` per **ADR-026** and **ADR-069**.

---

## 1. Supported Integration Matrix

| Protection Plugin / System | Platform | Integration Seam | Query Style | Boundary Provider | Tested Upstream Releases |
|---|---|---|---|---|---|
| **Towny Advanced** | Bukkit / Paper / Folia | `TownyAdvancedChecker` | Native CompileOnly (`TownyAPI.isWilderness`) | Yes (`TownyBoundaryProvider`) | `0.100.x`, `0.99.x`, `0.98.x` |
| **GriefPrevention** | Bukkit / Paper / Folia | `GriefPreventionChecker` | Native CompileOnly (`DataStore.getClaimAt`) | Yes (`GriefPreventionBoundaryProvider`) | `16.18.x`, `16.17.x` |
| **Lands** | Bukkit / Paper / Folia | `LandsChecker` | Native CompileOnly (`LandsIntegration.isClaimed`) | Yes (`LandsChecker.getBoundaryAt`) | `7.x`, `6.x` |
| **SaberFactions** | Bukkit / Paper / Folia | `SaberFactionsChecker` | Reflective (`Board.getFactionAt`) | Yes (`FactionsBoundaryProvider`) | `v4.x`, `v3.x` |
| **FactionsBridge** | Bukkit / Paper / Folia | `FactionsBridgeChecker` | Reflective (`FactionsBridge.getFactionsAPI`) | No | `1.3.x+` (Bridges FactionsUUID, Saber, Kingdoms) |
| **GriefDefender** | Bukkit / Paper / Folia | `GriefDefenderChecker` | Native CompileOnly (`G griefDefender.getCore().getClaimAt`) | No | `2.1.x`, `2.0.x` |
| **WorldGuard** | Bukkit / Paper / Folia | `WorldGuardChecker` | Native CompileOnly (`RegionManager.getApplicableRegions`) | No | `7.0.x`, `7.1.x` |
| **RedProtect** | Bukkit / Paper / Folia | `RedProtectChecker` | Native CompileOnly (`RedProtect.get().getAPI().getRegion`) | No | `7.7.x` |
| **Residence** | Bukkit / Paper / Folia | `ResidenceChecker` | Reflective (`Residence.getInstance().getClaimAt`) | No | `5.1.x`, `5.0.x` |
| **CrashClaim** | Bukkit / Paper / Folia | `CrashClaimChecker` | Reflective (`CrashClaim.getApi().getClaim`) | No | `1.x` |
| **HuskClaims** | Bukkit / Paper / Folia | `HuskClaimsChecker` | Reflective (`BukkitHuskClaimsAPI.isClaimAt`) | No | `1.4.x`, `1.3.x` |
| **HuskTowns** | Bukkit / Paper / Folia | `HuskTownsChecker` | Reflective (`BukkitHuskTownsAPI.getClaimAt`) | No | `3.0.x`, `2.x` |
| **PlotSquared** | Bukkit / Paper / Folia | `PlotSquaredChecker` | Reflective (`PlotAPI.wrapPlayer` / `PlotArea.getPlot`) | No | `v7.x`, `v6.x` |
| **KingdomsX** | Bukkit / Paper / Folia | `KingdomsXChecker` | Reflective (`Kingdoms.getLand`) | No | `1.15.x+` |
| **UltimateClaims** | Bukkit / Paper / Folia | `UltimateClaimsChecker` | Reflective (`ClaimManager.getClaim`) | No | `2.x`, `1.x` |
| **MinePlots** | Bukkit / Paper / Folia | `MinePlotsChecker` | Reflective (`MinePlots.getPlotAt`) | No | `1.x` |
| **FTB Chunks** | Fabric / NeoForge | `FTBChunksChecker` | Mod SPI (`FTBChunksAPI.isChunkClaimed`) | Planned (`ADR-099`) | `2001.x` (1.20.x), `2100.x` (1.21.x) |
| **OpenPartiesAndClaims** | Fabric / NeoForge | `OpenPartiesAndClaimsChecker` | Mod SPI (`IOpenPartiesAndClaimsAPI.isClaimed`) | No | `0.21.x+` |

---

## 2. API Design & Safety Contracts

### Fail-Closed Execution
All checkers implement a strict **fail-closed on error** policy:
- If a claim plugin throws an unexpected runtime exception, reflection error, or threading exception during verification, the exception is caught, logged once, and the destination is rejected (`!isInClaim` returns `false`).
- If an architectural incompatibility is detected, the integration disables itself cleanly for the session to prevent server log spam or crashes, while respecting Rule **S-004** (no silent swallow of teleport failures).

### Decoupled Reflection Architecture
Integrations marked **Reflective** do not link compile-time classes into the LeafRTP jar. They dynamically inspect public API singletons at runtime:
- No hard compile dependencies that could introduce transitive version conflicts.
- Graceful no-op when the plugin is absent or uninstalled.
- Zero classpath pollution on platforms running alternative fork implementations.

### Folia Threading Caveats
LeafRTP checks destinations across async workers and regional threads:
- Synchronous claim verifiers perform fast in-memory spatial tree lookups (`DataStore`, spatial hash tables, or chunk maps) without initiating disk chunk I/O, conforming to **S-005**.
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
