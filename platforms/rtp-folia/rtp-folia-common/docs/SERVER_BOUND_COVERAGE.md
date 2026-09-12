# rtp-folia-common: Server-Bound Path Coverage Inventory

> Created: 2026-09-11
> Scope: the platform-coupled classes in `rtp-folia-common` that JVM unit tests
> cannot reach (Folia region/entity/global schedulers, live world/chunk access,
> live player teleport). JaCoCo measures 8.7% instruction coverage here and is
> **not** the metric for this module (see
> [`ENTERPRISE_READINESS.md` item 21](../../../docs/dev/ENTERPRISE_READINESS.md)
> and [`COVERAGE_PLAN.md`](../../../docs/dev/COVERAGE_PLAN.md)). This document is
> the honest substitute: an explicit inventory of the server-bound paths that
> matter and a map from each path to the in-game `rtp test *` subcommand that
> exercises it against a live Folia server.

## How to read this

- **Path** - the platform-only method (or cohesive group) whose behaviour can
  only be validated against a running server.
- **Owning `rtp test *`** - the subcommand that drives the path in-game. `full`
  means it is included in the `rtp test full`/`all` sweep
  (`TestFullCmd.SHIPPED_SUBCOMMAND_NAMES`).
- **Assertion today** - what the owning subcommand actually checks. `warn-only`
  means the step passes if nothing logs `WARNING`/`SEVERE` (the `FullAudit`
  contract); it does not positively assert the server-side effect.
- **Status** - `covered` (a subcommand drives it), `partial` (driven but only
  warn-only, no positive effect assertion), or `GAP` (no owning subcommand).

A `GAP` row is a real, named coverage hole - not a percentage. Closing a gap
means adding (or extending) an `rtp test *` subcommand that drives the path and
asserts its observable server effect, then moving the row to `covered`.

---

## 1. `FoliaRTPWorld` (chunk I/O, biome, force-load, persistence)

| Path | Owning `rtp test *` | Assertion today | Status |
|---|---|---|---|
| `getChunkAt` / `loadLiveChunk` / `getChunkAtAsync` (async chunk load, S-005) | `async-chunk-load` (in `full`) | timing series + no-warn; confirms off-thread load | partial |
| `probeChunkColumn` (async column probe) | `async-chunk-load`, `biome-source` | warn-only | partial |
| `getBiome` / `readBiomesInRegionFile` / `canonicaliseBiome` | `biome-source` (in `full`) | warn-only | partial |
| `shouldPrefilter` / anvil gate + `logGateSkip` | `anvil-prefilter` (in `full`) | warn-only | partial |
| `isChunkLoaded` / `isChunkGenerated` / `getCachedChunk` | `async-chunk-load` (incidental) | not asserted | partial |
| `setForceLoadedImpl` / `getServerForceLoadedCount` (S-002 ticketing) | - | - | **GAP** (`chunk-ticket` uses sentinel objects, not the live force-load path) |
| `keepChunkAt` / `forgetChunkAt` / `forgetChunks` (cache lifecycle) | - | - | **GAP** |
| `platform(RTPLocation)` (block placement on region thread) | - | - | **GAP** |
| `setBlocks` / `restoreBlocks` / `restoreBlockEntities` | - | - | **GAP** |
| `save` / `isInactive` / `getCacheSize` | - | - | **GAP** |
| `currentUnsafeBlocks` / `isVanilla` / `environment` | `commands` (indirect) | not asserted | **GAP** |
| `getMaxHeight` / `getMinHeight` / `getSeed` / `name` / `id` | api-compat (indirect) | not asserted | partial |

## 2. `FoliaRTPChunk` (live chunk snapshot + safety reads)

| Path | Owning `rtp test *` | Assertion today | Status |
|---|---|---|---|
| `keep(boolean)` (chunk ticket open/release, S-002) | - | - | **GAP** |
| `isAir` / `isSafe(..)` / `getSurfaceHeight` / `getSkyLight` | `safety-verifier` (in `full`) | warn-only | partial |
| `getBiome(x,y,z)` | `biome-source` | warn-only | partial |
| `reconciledAirBlocks` / `extractProperties` (anvil-backed) | `anvil-prefilter` | warn-only | partial |
| `isGenerated` / `isLoaded` / `isSelfContained` / `isAnvilBacked` | `async-chunk-load` (incidental) | not asserted | partial |
| `unload` | - | - | **GAP** |

## 3. `FoliaSchedulerImpl` (region / entity / async / global dispatch)

| Path | Owning `rtp test *` | Assertion today | Status |
|---|---|---|---|
| `runTaskAsynchronously` / `runTaskTimerAsynchronously` | `scheduler` (in `full`) | dispatch confirmed via tier probe | covered |
| `runTask()` (global region) / `runTaskLater(Runnable,long)` | `scheduler` | tier probe | covered |
| `runTask(RTPLocation,..)` / `runTask(world,cx,cz,..)` (region) | `scheduler`, `folia-ownership` | dispatch + ownership check | partial |
| `runTaskTimer(world,cx,cz,..)` / `runTaskLater(world,..)` (region timers) | - | - | **GAP** |
| `runTaskForPlayer` (entity scheduler, teleport dispatch) | `async-reply` (in `full`) | warn-only | partial |
| `cancelTask` | `cancel` (excluded from sweep) | state transition | covered |

## 4. `FoliaRTPPlayer` (live teleport + client effects)

| Path | Owning `rtp test *` | Assertion today | Status |
|---|---|---|---|
| `setLocation` (entity-scheduler teleport, S-001/S-004) | `stress`, `async-reply` (in `full`) | warn-only; does not assert landing region/thread | partial |
| `scheduleOnSelf` (entity scheduler self-dispatch) | `async-reply` | warn-only | partial |
| `setRespawnLocation` | - | - | **GAP** |
| `getLocation` / `isOnline` / `getViewDistance` / `setViewDistance` | `commands` / stress (incidental) | not asserted | partial |
| `sendClientBlockChange(s)` / `getClientBlock` / `parseBlockData` | - | - | **GAP** |
| `showProgressBar` / `clearProgressBar` / bossbar helpers | - | - | **GAP** |
| `performCommand` / `hasPermission` / `getEffectivePermissions` | `commands` (indirect) | not asserted | partial |

## 5. `AbstractFoliaServerAccessor` (server surface / SPI wiring)

| Path | Owning `rtp test *` | Assertion today | Status |
|---|---|---|---|
| `getRTPWorld` / `getRTPWorlds` / `getPlayer` / `getSender` | `commands`, `api-compat` (in `full`) | not asserted | partial |
| `getScheduler` / `createTaskPipe` / `getLocationGenerator` | `scheduler`, `api-compat` | not asserted | partial |
| `getTPS` / `getWorldBorder` / `createNativeWorldBorder` | - | - | **GAP** |
| `getBiomes` / `materials` / `blockTagSnapshot` / `rebuildBlockTagSnapshot` | `biome-source` (indirect) | not asserted | partial |
| `sendMessage(..)` family / `announce` / `format` | `commands` (indirect) | not asserted | partial |
| `start` / `stop` / lifecycle hook | - | - | **GAP** (server bootstrap; devstack boot only) |
| `menuPermissionProbe` / `menuEffectivePermissions` / `menuLocale` / `menuRegionDescriptor` | - | - | **GAP** |
| `getServerIntVersion` / `getServerVersion` / `getPlatform` | `api-compat` (in `full`) | asserted (version bucketing) | covered |

## 6. `FoliaMetricsBinding` (region TPS sampling)

| Path | Owning `rtp test *` | Assertion today | Status |
|---|---|---|---|
| `scheduleGlobalSampler` / `globalTick` / `cancelGlobalSampler` | - (unit-tested via `tickGlobalSamplerForTest`) | JVM `FoliaMetricsBindingTest` | covered (unit) |
| `recordRegionTick(..)` / `foliaRegions` / `aggregateTps` | - | `FoliaMetricsBindingTest` | covered (unit) |
| live `tps1m/5m/15m` / `mspt` under real region load | - | - | **GAP** (live-load validation only) |

---

## 7. Summary of gaps (the honest coverage statement)

The green in-game sweep and the JVM suite together leave these server-bound
surfaces with **no owning positive-assertion test**:

1. Live chunk force-load ticketing and release - `FoliaRTPWorld.setForceLoadedImpl`
   / `getServerForceLoadedCount` / `keepChunkAt` / `forgetChunkAt` /
   `FoliaRTPChunk.keep` / `unload` (S-002). `rtp test chunk-ticket` validates the
   `MemoryTracker` bookkeeping with sentinels but never opens a real Folia ticket.
2. Live block placement / restore - `FoliaRTPWorld.platform`, `setBlocks`,
   `restoreBlocks`, `restoreBlockEntities`.
3. World persistence - `FoliaRTPWorld.save` / `isInactive`.
4. Region-thread timers - `FoliaSchedulerImpl.runTaskTimer(world,..)` /
   `runTaskLater(world,..)`.
5. Player client effects - `sendClientBlockChange(s)`, bossbar progress bars,
   `setRespawnLocation`.
6. Server surface - `getTPS`, world-border creation/lookup, menu permission /
   locale / region-descriptor probes, `start`/`stop` lifecycle.
7. Teleport landing assertion - `FoliaRTPPlayer.setLocation` runs under `stress`
   but is only warn-audited; it does not assert the entity landed on the owning
   region thread at a safe block (S-001).

### Recommended next steps (deferred until the devstack acceptance CI exists)

- Convert the highest-value `partial` rows to positive assertions: teleport
  landing (region-thread + safe block), and a `MemoryTracker` leak assertion
  after the sweep (tickets + tasks back to zero, S-002/S-004 - matches
  `ENTERPRISE_READINESS.md` item 24).
- Add a `chunk-ticket`-adjacent subcommand that opens and releases a **real**
  Folia force-load ticket and asserts `getServerForceLoadedCount` returns to
  baseline, closing gap 1.
- Any new production instrumentation to surface a "paths touched" count in the
  sweep footer is a multi-class change to the Folia adapters and MUST start as a
  D-005 proposal; this document is the doc-only prerequisite for that decision.

> This inventory is the metric for this module, not JaCoCo. Keep it in sync when
> `rtp test *` subcommands are added or when a Folia adapter grows a new
> server-bound path.
