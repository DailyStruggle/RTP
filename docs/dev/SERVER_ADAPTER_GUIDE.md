# Server Adapter Requirements & Implementation Guide

This document is the authoritative specification and developer guide for implementing a new platform server adapter for LeafRTP. It outlines the architectural boundaries, mandatory safety prohibitions, SPI interface contracts, and the exact function-by-function requirements needed to bridge the platform-neutral RTP engine to any Minecraft server platform (such as Bukkit, Paper, Folia, Fabric, NeoForge, Minestom, Sponge, or future server runtimes).

---

## 1. Architectural Role & Boundary Rules

### 1.1 The Engine vs. Platform Seam
LeafRTP is structured to maintain strict architectural boundaries (governed by [ARCHITECTURE.md](ARCHITECTURE.md) and enforced by ArchUnit tests):

```
┌─────────────────────────────────────────────────────────────────┐
│                      rtp-api & SPIs                             │
│   (Public interfaces, shared models, commands-api, anvil-api)   │
└───────────────────────────────▲─────────────────────────────────┘
                                │
┌───────────────────────────────┴─────────────────────────────────┐
│                          rtp-core                               │
│  (Region queues, spiral math, MemoryTracker, safety pipeline)   │
│                 ZERO PLATFORM / NMS IMPORTS                     │
└───────────────────────────────▲─────────────────────────────────┘
                                │
                 Adapter Bridge via RTPServerAccessor
                                │
┌───────────────────────────────┴─────────────────────────────────┐
│                     Platform Adapter                            │
│  (rtp-bukkit, rtp-paper, rtp-folia, rtp-fabric, rtp-neoforge)  │
│  Translates platform events, levels, chunks, players, scheduler │
└─────────────────────────────────────────────────────────────────┘
```

1. **Zero Platform Imports in Core:** Neither `rtp-core` nor `rtp-api` may import platform-specific packages (`org.bukkit.*`, `net.minecraft.*`, `net.fabricmc.*`, `net.neoforged.*`, etc.).
2. **Platform Access via SPI:** `rtp-core` interacts with the host server exclusively through `io.github.dailystruggle.rtp.api.server.RTPServerAccessor` and its associated interfaces (`RTPScheduler`, `RTPWorld`, `RTPChunk`, `RTPPlayer`, `RTPCommandSender`).
3. **Fail-Closed Contract (S-006):** Public `rtp-api` delegates throw `IllegalStateException` if invoked before `rtp-core` and the server accessor have completed initialization.

### 1.2 Multi-Version Strategy
- If targeting multiple incompatible native NMS versions (e.g. Spigot/Paper versions), follow **ADR-010** by splitting into compile-time versioned submodules (`<platform>-common`, `<platform>-v1_20_R1`, `<platform>-v1_21_R1`, etc.) with a single runtime selector at bootstrap.
- If targeting unified or runtime-deobfuscated platforms (e.g. modern Fabric deobfuscated or NeoForge Mojmap at runtime per **ADR-033**), a single adapter module can compile directly against platform APIs, using version carriers only when bytecode compatibility breaks across major releases.

---

## 2. Non-Negotiable Safety Prohibitions (S-001 .. S-007)

Every platform adapter must strictly satisfy the core safety rules defined in [REQUIREMENTS.md](REQUIREMENTS.md#3-prohibition-requirements):

| Rule | Requirement | Adapter Implementation Responsibility |
|---|---|---|
| **S-001** | **No unsafe-block teleport destinations** | `RTPChunk#isSafe()` and `materialNameAt()` must fail closed on null, unknown, or unresolvable block IDs rather than passing the safety check. Never bypass core block-safety evaluation. |
| **S-002** | **No permanently force-loaded chunks** | `RTPWorld#keepChunkAt()` must use transient plugin chunk tickets (e.g. `addPluginChunkTicket`), NEVER persistent force-loading (like Bukkit `Chunk#setForceLoaded(true)` or native forge persistent tickets). All tickets must release on `reservation.close()`, `forgetChunkAt()`, and `forgetChunks()`. |
| **S-003** | **No teleport into claim-protected land** | Platform adapters must expose or register external claim hooks (GriefPrevention, Towny, WorldGuard, FTB Chunks, etc.) through `RTPServerAccessor#registerClaimHandler()` or core `ClaimCheckProvider`. |
| **S-004** | **No silently discarded teleport failures** | `RTPPlayer#setLocation()` must return a `CompletableFuture<Boolean>` that ALWAYS completes. Catch-and-swallow blocks are prohibited. Command overrides must log `msgInvalidCommand` and `msgBadParameter` at `WARNING` level (`REQ-RTP-S-004`). |
| **S-005** | **Zero synchronous chunk loads on tick threads** | `RTPWorld#getChunkAtAsync()` must NEVER call synchronous `world.getChunkAt()` or blocking `.get()` on the main/tick thread. Off-tick Anvil reading ([ADR-016](ADR-016-anvil-subsystem.md)) must be utilized for ungenerated or unloaded pre-filtering. |
| **S-006** | **No NPE when addons call API before core loads** | Methods must fail closed with `IllegalStateException` if accessed pre-init, rather than returning `null` or silently no-oping. |
| **S-007** | **Configurable messages** | User-facing messages must never be hardcoded; all responses route through `messages.yml` keys. |

---

## 3. Core SPI Interface Contracts & Function Reference

A server adapter must implement six primary SPI interfaces from `rtp-api`:
1. `RTPServerAccessor`
2. `RTPScheduler`
3. `RTPWorld<T>`
4. `RTPChunk<T>`
5. `RTPPlayer`
6. `RTPCommandSender`
along with the event bridge and lifecycle hook (`PlayerLifecycleHook`).

---

### 3.1 `RTPServerAccessor`
`io.github.dailystruggle.rtp.api.server.RTPServerAccessor` is the central service locator and lifecycle gateway instantiated and bound by the platform adapter on startup. It extends the platform-neutral `io.github.dailystruggle.server.api.ServerAccessor` from `api/server-api`.

#### Lifecycle & Subsystems
- `void start()` / `void start(Object pluginHandle)`
  - **Purpose:** Initializes the adapter, captures the host plugin or mod container, configures data folders, registers event bridges, and installs backend factories (e.g. `backendStateSamplerFactory`, `networkBridgeFactory`).
  - **Thread Context:** Main server bootstrap thread.
- `void stop()`
  - **Purpose:** Gracefully tears down the adapter during server shutdown or mod reload. Must call `rtpWorld.forgetChunks()` on all loaded worlds to release chunk tickets and flush database writes.
  - **Thread Context:** Server shutdown/stopping thread.
- `RTPScheduler getScheduler()`
  - **Purpose:** Returns the platform's `RTPScheduler` implementation.
  - **Usage:** Core uses this for all task scheduling (sync, async, and regional).
- `RTPTaskPipe createTaskPipe()` / `Object createCachePipe()`
  - **Purpose:** Instantiates task-draining pipes (e.g. `TimeBoundTaskPipe` on Bukkit/Fabric/NeoForge; `CountBoundTaskPipe` on Folia) used to pace calculation pulses within tick budgets.
- `ILocationGenerator getLocationGenerator()`
  - **Purpose:** Returns the location generator instance (standard `LocationGenerator`).
- `PlayerLifecycleHook getPlayerLifecycleHook()`
  - **Purpose:** Returns the platform-agnostic lifecycle hook that dispatches player join, quit, and teleportation events.

#### Common Server Identity, Compatibility & Logging
These methods are declared by `ServerAccessor` and are available through `RTPServerAccessor`:
- `String getServerVersion()`
  - **Purpose:** Returns the server-reported version string (e.g. `"git-Paper-400 (MC: 1.21.1)"`).
- `String getPlatform()`
  - **Purpose:** Returns a platform identifier such as `"paper"`, `"folia"`, `"fabric"`, or `"neoforge"`.
- `Integer getServerIntVersion()`
  - **Purpose:** Normalized integer version (e.g. `20` for 1.20.x, `21` for 1.21.x) for quick version gating.
- `PlatformFamily getPlatformFamily()`
  - **Purpose:** Returns `BUKKIT`, `FABRIC`, `NEOFORGE`, or `UNKNOWN`; Bukkit-derived platforms including Paper and Folia use `BUKKIT`.
  - **Usage:** Used by addons and core for platform-aware capability gating.
- `boolean isCompatible(PlatformFamily family, int minVersion, int maxVersion)`
  - **Purpose:** Compatibility check for addons to verify if the server platform satisfies their requirements.
- `void log(Level level, String message)` / `void log(Level level, String message, Throwable throwable)`
  - **Purpose:** Writes through the platform's server logger; `ServerAccessors.log(...)` uses a generic logger before the accessor is installed.

#### RTP-Specific Services
- `File getPluginDirectory()`
  - **Purpose:** Returns the base directory where `config.yml`, `regions.yml`, and `messages.yml` reside.
- `Object getPlugin()`
  - **Purpose:** Returns the underlying platform container handle (e.g. `JavaPlugin`, `ModContainer`).

#### World & Player Registry
- `RTPWorld<?> getRTPWorld(String name)` / `RTPWorld<?> getRTPWorld(UUID uuid)`
  - **Purpose:** Retrieves or wraps the specified world into an `RTPWorld`. Must cache wrappers to ensure object identity across queries.
- `List<RTPWorld<?>> getRTPWorlds()`
  - **Purpose:** Returns all currently loaded server dimensions/levels.
- `RTPPlayer getPlayer(UUID uuid)` / `RTPPlayer getPlayer(String name)`
  - **Purpose:** Retrieves or wraps an online player into an `RTPPlayer`. Must return `null` if the player is offline.
- `Collection<RTPPlayer> getOnlinePlayers()`
  - **Purpose:** Returns wrappers for all currently connected players.
- `RTPCommandSender getSender(UUID uuid)`
  - **Purpose:** Resolves a command sender: returns the matching `RTPPlayer` if online, or console sender if UUID represents the console (or zeroes).
- `void registerWorld(Object level)` / `void unregisterWorld(Object level)`
  - **Purpose:** Dynamically registers or drops dimension wrappers upon world load/unload events to prevent memory leaks.
- `void registerPlayer(Object player)` / `void unregisterPlayer(Object player)`
  - **Purpose:** Dynamically binds/unbinds native player handles on join/quit (REQ-RTP-S-004 memory hygiene).

#### Server State & Communication
- `double getTPS(int ticks)`
  - **Purpose:** Returns the rolling ticks-per-second average over the requested sample window (100, 600, etc.). Integrates with the metrics subsystem.
- `void announce(String message, String permission)`
  - **Purpose:** Broadcasts a formatted message to all online players holding the given permission (or all players if permission is null/empty).
- `boolean hasEconomy()` / `Object getEconomyProvider()`
  - **Purpose:** Exposes currency / Vault integration if available on the platform.

#### Dynamic Customization Hooks
- `void setWorldBorderFunction(Function<String, ?> function)`
  - **Purpose:** Overrides default world border calculations with platform native borders.
- `void setShapeFunction(Function<String, ?> shapeFunction)`
  - **Purpose:** Provides custom region boundary shape factories.
- `void setBiomeGetter(Function<RTPLocation, String> getter)`
  - **Purpose:** Overrides live biome resolution logic.
- `void registerClaimHandler(Function<RTPLocation, Boolean> handler)`
  - **Purpose:** Injects a platform-specific land protection check (returns `true` if location is claimed/protected).

---

### 3.2 `RTPScheduler`
`io.github.dailystruggle.rtp.api.scheduling.RTPScheduler` abstracts task execution and timer scheduling across different concurrency architectures.

#### Standard Scheduling (Single-Threaded / Worker Platforms)
- `RTPTask runTask(Runnable runnable)`
  - **Purpose:** Schedules a task to execute on the main server/tick thread on the next tick.
- `RTPTask runTaskLater(Runnable runnable, long delayTicks)`
  - **Purpose:** Schedules a task to execute on the main thread after `delayTicks`.
- `RTPTask runTaskTimer(Runnable runnable, long delayTicks, long periodTicks)`
  - **Purpose:** Schedules a repeating task on the main thread.
- `RTPTask runTaskAsynchronously(Runnable runnable)`
  - **Purpose:** Dispatches work immediately onto an asynchronous thread pool.
- `RTPTask runTaskLaterAsynchronously(Runnable runnable, long delayTicks)`
  - **Purpose:** Delays work for `delayTicks`, then executes asynchronously.
- `RTPTask runTaskTimerAsynchronously(Runnable runnable, long delayTicks, long periodTicks)`
  - **Purpose:** Periodically executes work on an asynchronous thread pool.

#### Regional Multithreading (Folia / Thread-Partitioned Platforms)
- `RTPTask runAtLocation(RTPLocation location, Runnable runnable)`
  - **Purpose:** Dispatches the runnable on the regional thread that owns the specified `(x, y, z)` block coordinate.
  - **Folia:** Delegates to `RegionScheduler.run(plugin, world, chunkX, chunkZ, task)`.
  - **Non-Folia:** Delegates directly to `runTask(runnable)`.
- `RTPTask runAtEntity(RTPPlayer player, Runnable runnable)`
  - **Purpose:** Dispatches the runnable on the regional thread currently owning the player entity.
  - **Folia:** Delegates to `EntityScheduler.run(plugin, task, null)`.
  - **Non-Folia:** Delegates directly to `runTask(runnable)`.
- `boolean isOwnedByCurrentRegion(RTPLocation location)` / `boolean isOwnedByCurrentRegion(RTPPlayer player)`
  - **Purpose:** Verifies whether the calling thread is the authority thread owning that coordinate or entity.
  - **Folia:** Calls `Bukkit.isOwnedByCurrentRegion(location)`.
  - **Non-Folia:** Checks `server.isSameThread()` or returns `true` when on the main thread.

#### Task Management & Budgeting
- `void cancelTasks()`
  - **Purpose:** Cancels all active scheduled tasks on shutdown or reload.
- `Map<Integer, RTPTask> activeTasks()`
  - **Purpose:** Returns a map of currently tracked tasks for leak diagnostic audits in `MemoryTracker`.
- **Tick Budgeting:** For platforms driving a custom tick loop (e.g. Fabric/NeoForge), delayed and repeating tasks must be executed under a bounded tick budget (such as `TickBudget.syncNanos()`) to prevent RTP maintenance tasks from stalling the server tick.

---

### 3.3 `RTPWorld<T>`
`io.github.dailystruggle.rtp.api.world.RTPWorld<T>` represents a specific dimension or world level, wrapping the underlying native level object `T` (e.g. `org.bukkit.World`, `net.minecraft.server.level.ServerLevel`).

#### Identity & Boundaries
- `String name()`
  - **Purpose:** Canonical world name (e.g. `"world"`, `"minecraft:overworld"`).
- `UUID id()` / `UUID getUID()`
  - **Purpose:** Unique world identifier.
- `T world()`
  - **Purpose:** Returns the underlying platform world object.
- `int getMinHeight()` / `int getMaxHeight()`
  - **Purpose:** Returns world vertical bounds (e.g. `-64` and `320` for modern overworld). Used by vertical adjusters.
- `long getSeed()`
  - **Purpose:** World seed, used by spiral selection math and biome synthesis.
- `String environment()`
  - **Purpose:** Dimension type hint (`"NORMAL"`, `"NETHER"`, `"THE_END"`).
- `boolean isVanilla()`
  - **Purpose:** Returns `true` if the generator is vanilla (ADR-016), enabling seed-synthesised biome pre-checking before chunk loading. Custom modded generators return `false`.

#### Chunk I/O & Lifecycle (S-005, S-002)
- `CompletableFuture<Set<Long>> getChunkAtAsync(int cx, int cz)`
  - **Purpose:** Asynchronously requests chunk generation and loading.
  - **Requirement:** Must return a `CompletableFuture` that completes with the chunk position key when loaded. MUST NEVER BLOCK the calling thread.
- `CompletableFuture<Long> getChunkAt(int cx, int cz)`
  - **Purpose:** Probe-first off-tick resolution. Resolves cached chunk or loads chunk without blocking.
- `boolean isChunkLoaded(int cx, int cz)`
  - **Purpose:** Non-blocking query to determine if the chunk is resident in memory.
- `boolean isChunkGenerated(int cx, int cz)`
  - **Purpose:** Non-blocking query to determine if the chunk already exists on disk (e.g. `World#isChunkGenerated`).
- `void keepChunkAt(int cx, int cz)` / `void forgetChunkAt(int cx, int cz)`
  - **Purpose:** Acquires or releases a transient plugin chunk ticket for `(cx, cz)`.
  - **Rule S-002:** Must use plugin tickets (`addPluginChunkTicket`), NEVER permanent force-loading.
- `void forgetChunks()`
  - **Purpose:** Releases all chunk tickets held by RTP in this world (called on shutdown/reload).
- `void releaseOrphanedTickets(Set<Long> keepAliveKeys)`
  - **Purpose:** Memory hygiene garbage collection hook called by `MemoryTracker`. Any ticket key not present in `keepAliveKeys` must be freed.

#### Off-Tick Anvil / MCA File Access ([ADR-016](ADR-016-anvil-subsystem.md))
- `Path anvilWorldFolder()`
  - **Purpose:** Path to the world save folder containing the `region/` directory. Enables off-tick Anvil prefiltering without live chunk loads.
- `String anvilDimensionSubpath()`
  - **Purpose:** Subdirectory for the dimension (e.g. `""` for overworld, `"DIM-1"` for Nether, `"DIM1"` for The End).
- `CompletableFuture<ChunkColumnProbe> probeChunkColumn(int cx, int cz, int minY, int maxY)`
  - **Purpose:** Fast off-tick column probe for pregeneration tasks.
- `Map<Long, String> readBiomesInRegionFile(int rcx, int rcz, int y)`
  - **Purpose:** Bulk off-tick biome reads directly from region files (`r.<rcx>.<rcz>.mca`) for fast survey caching.

#### Safety & Terrain Manipulation
- `void platform(RTPLocation location)`
  - **Purpose:** Places an emergency landing platform if the landing site is over water or in the air.
- `boolean restoreBlocks(List<BlockDelta> blocks)`
  - **Purpose:** Restores original terrain after emergency platform timeout ([ADR-060](ADR-060-emergency-platform-block-restoration-timeout.md)).
- `int setBlocks(List<BlockDelta> blocks)` / `restoreBlockEntities(List<PlacedBlockEntity> entities)`
  - **Purpose:** Bulk block and block-entity writes for schematic pasting ([ADR-058](ADR-058-schematic-paster-spi.md)).

---

### 3.4 `RTPChunk<T>`
`io.github.dailystruggle.rtp.api.world.RTPChunk<T>` wraps a chunk object `T` (or preformed data view) to answer safety and block queries.

#### Coordinates & Identity
- `int x()` / `int z()`
  - **Purpose:** Chunk coordinate pair.
- `RTPWorld<?> getWorld()`
  - **Purpose:** The parent `RTPWorld`.
- `T chunk()`
  - **Purpose:** Returns the underlying platform chunk handle.

#### Block State & Safety Queries (S-001)
- `String materialNameAt(int localX, int worldY, int localZ)`
  - **Purpose:** Returns upper-case namespaced block identifier (e.g. `"MINECRAFT:STONE"`, `"MINECRAFT:LAVA"`, `"MINECRAFT:AIR"`).
  - **Rule:** Must return empty string `""` on invalid coordinates or missing data.
- `boolean isAir(int localX, int worldY, int localZ)`
  - **Purpose:** Returns whether block is passable air.
- `int getSkyLight(int localX, int worldY, int localZ)`
  - **Purpose:** Returns sky light level (0–15) to detect caves or covered roofs.
- `boolean isSafe(int localX, int worldY, int localZ, Set<String> unsafeBlocks)`
  - **Purpose:** Evaluates destination safety.
  - **Rule S-001:** If the block material name is null, empty, or unresolvable, it MUST return `false` (fail-closed).
- `boolean isSafe(int localX, int worldY, int localZ, CompiledUnsafeSet unsafeBlocks)`
  - **Purpose:** Optimized compiled check ([ADR-017](ADR-017-block-tags-and-state-predicates-in-safety-lists.md)) supporting block tags and block-state properties.
- `boolean isSelfContained()`
  - **Purpose:** Returns `true` if the chunk is backed by off-tick preformed data (e.g. `AnvilChunkView`) and requires zero live chunk I/O.

#### Chunk State
- `boolean isLoaded()`
  - **Purpose:** Returns whether the chunk is currently loaded into memory.
- `boolean isGenerated()`
  - **Purpose:** Returns whether the chunk has been generated on disk.
- `void keep(boolean keep)` / `void unload()`
  - **Purpose:** Retains or unloads the chunk via the parent world's ticket system.

---

### 3.5 `RTPPlayer`
`io.github.dailystruggle.rtp.api.entity.RTPPlayer` extends `RTPCommandSender`, providing an abstraction over native connected player entities.

#### Teleportation & Positional State
- `CompletableFuture<Boolean> setLocation(RTPLocation to)`
  - **Purpose:** Dispatches the player teleport to `to`.
  - **Requirement (S-004):** Must return a `CompletableFuture<Boolean>` that completes with `true` on successful teleport, or `false` on cancellation/disconnect.
  - **Threading:** Must dispatch on the destination owning thread (main thread or Folia region thread). Must never execute blocking chunk I/O on that thread.
- `RTPLocation getLocation()`
  - **Purpose:** Returns the player's current location.
  - **Thread Context:** Calling thread must be the player's owning thread.
- `boolean isOnline()`
  - **Purpose:** Returns whether the player is currently connected. Safe to call from any thread.
- `void setRespawnLocation(RTPLocation location)`
  - **Purpose:** Sets the player's respawn anchor / bed location ([ADR-023](ADR-023-login-reserve-cache.md)).

#### Client Visuals & HUD (Optional / Enhanced Capabilities)
- `void sendClientBlockChange(RTPLocation location, String blockData)` / `sendClientBlockChanges(Map<RTPLocation, String> changes)`
  - **Purpose:** Sends fake visual block packets to the client (e.g. previewing destinations or particle bounds) without altering world blocks or triggering chunk loads (REQ-RTP-S-005).
- `String getClientBlock(RTPLocation location)`
  - **Purpose:** Reads the block state currently displayed to the player.
- `void showProgressBar(String id, String title, double progress)` / `clearProgressBar(String id)`
  - **Purpose:** Displays a native boss-bar or progress bar during teleport warmups or queue generation.
- `int getViewDistance()` / `void setViewDistance(int distance)`
  - **Purpose:** Gets or tunes the player's server tracking chunk radius ([ADR-072](ADR-072-teleport-pre-load-view-distance.md)).
- `int getSendViewDistance()` / `void setSendViewDistance(int distance)`
  - **Purpose:** Gets or tunes client render chunk distance independently of server tracking.

---

### 3.6 `RTPCommandSender`
`io.github.dailystruggle.rtp.api.entity.RTPCommandSender` represents any command initiator (player, console, command block).

- `UUID uuid()`
  - **Purpose:** Unique sender identifier (console uses constant zeroed UUID).
- `String name()`
  - **Purpose:** Display name of the sender.
- `boolean hasPermission(String permission)`
  - **Purpose:** Evaluates permission nodes. Must integrate with platform permission systems (LuckPerms, Bukkit permissions, Fabric Permissions API).
- `boolean isRtpAdmin()`
  - **Purpose:** Returns `true` if sender has wildcard `rtp.*` or operator status.
- `void sendMessage(String message)`
  - **Purpose:** Sends formatted chat feedback to the sender.
- `long cooldown()` / `long delay()`
  - **Purpose:** Sender-specific cooldown and warmup delay overrides (e.g. resolved from permission nodes).
- `Set<String> getEffectivePermissions()`
  - **Purpose:** Returns all active permissions assigned to the sender.
- `void performCommand(@Nullable RTPPlayer player, String command)`
  - **Purpose:** Executes a server command on behalf of this sender.
- `RTPCommandSender clone()`
  - **Purpose:** Clones sender context for async safety.

---

### 3.7 `PlayerLifecycleHook` & Event Bridge
The adapter must listen to native server events and translate them into RTP callbacks via `PlayerLifecycleHook`:

1. **Player Join:**
   - Call `accessor.registerPlayer(nativePlayer)`.
   - Call `playerLifecycleHook.fireJoinFromPlayer(nativePlayer)`.
   - On default world, check for Login Reserve Cache refill ([ADR-023](ADR-023-login-reserve-cache.md)).
2. **Player Quit:**
   - Call `playerLifecycleHook.fireQuitFromPlayer(nativePlayer)`.
   - Cancel any pending `TeleportPipelineTask` for that player via `MemoryTracker`.
   - Call `accessor.unregisterPlayer(nativePlayer)` to prevent reference leaks.
3. **Player Move (ADR-075):**
   - When a player with a pending warmup moves beyond the cancel threshold, cancel the teleport task and notify the player.
4. **Player Damage / Combat (ADR-055):**
   - Intercept damage events to tag players in combat or cancel warmups if configured.
5. **World Load / Unload:**
   - Bind newly loaded levels via `accessor.registerWorld(level)` and unregister unloaded levels.

---

## 4. Concurrency & Threading Architecture

Different platforms employ distinct concurrency constraints that the adapter must handle correctly:

### 4.1 Standard Single-Thread Tick (Spigot, Fabric, NeoForge)
- **Model:** One main server thread ticks all entities and worlds.
- **Rules:**
  - Chunk I/O must never occur synchronously on the tick thread.
  - Use asynchronous chunk loading APIs (`getChunkAtAsync`, `ServerLevel.loadChunks`) or off-tick Anvil reading.
  - Work scheduled via `RTPScheduler#runTask` runs on the main server thread.

### 4.2 Asynchronous Chunk Engines (Paper)
- **Model:** Main thread handles logic, while chunk generation/loading runs on internal worker pools.
- **Rules:**
  - Wrap Paper's native `World#getChunkAtAsync` which returns `CompletableFuture<Chunk>`.
  - Continuations must run on worker threads or async pools; do not call `.join()` or `.get()` on the main thread.

### 4.3 Regional Multithreading (Folia)
- **Model:** No single main thread; the world is divided into 8x8 chunk regions, each ticked independently by regional worker threads.
- **Rules:**
  - Always check `RTPScheduler#isOwnedByCurrentRegion` before touching world blocks or entity state.
  - Coordinate transitions using `runAtLocation` and `runAtEntity`.
  - Vault and global economy calls must be offloaded to `GlobalRegionScheduler` or async pools.
  - Use `CountBoundTaskPipe` ([ADR-015](ADR-015-stale-chunk-guard-countbound-pipes.md)) to bound per-pulse slice work on region threads.

---

## 5. Chunk Ticket Lifecycle & Leak Prevention (MemoryTracker)

To satisfy **S-002** and avoid memory leaks:
1. **Ticket Acquisition:**
   - When pre-generating or warming up a location, acquire a chunk ticket via `keepChunkAt(cx, cz)`.
   - Register the ticket with `MemoryTracker.registerChunkReservation(reservation)`.
2. **Ticket Release:**
   - When a location is consumed, invalidated, or cancelled, call `forgetChunkAt(cx, cz)`.
   - Never rely on server restart to clear tickets.
3. **Garbage Collection Sweep:**
   - Periodically, `MemoryTracker` runs a diagnostic pass and invokes `RTPWorld#releaseOrphanedTickets(Set<Long> keepAliveKeys)`.
   - The adapter must iterate its internal ticket map and release all tickets whose chunk key is not present in `keepAliveKeys`.

---

## 6. Command Registration & Feedback Auditing

Server adapters integrate with the unified command framework (`commands-api`):
- **Bukkit / Paper / Folia:** Register `BukkitBaseRTPCmd` into the server's `CommandMap`.
- **Fabric / NeoForge:** Register the root `/rtp` command and Brigadier syntax tree via `FabricCommandRegistrar` or NeoForge's `RegisterCommandsEvent`.
- **Command Feedback Invariant (REQ-RTP-S-004):** Any syntax error, bad parameter, or invalid command execution MUST call `RTP.log(Level.WARNING, ...)` in addition to sending user feedback, ensuring operator transparency.

---

## 7. Secondary / Optional SPI Frameworks

Adapters can enhance functionality by implementing optional SPI interfaces:

| SPI Framework | Module | Purpose | Adapter Implementation |
|---|---|---|---|
| **`server-api`** | `api/server-api` | Server identity, platform family and logging | Implement the neutral `ServerAccessor` contract through the RTP server accessor. |
| **`metrics-api`** | `api/metrics-api` | Runtime telemetry (TPS, MSPT, queue depth, memory) | Implement `MetricsBinding` to sample platform-specific tick timing and heap. |
| **`effects-api`** | `api/effects-api` | Particles, sounds, and visual effects | Implement `EffectsHandler` to spawn platform-native particle packets and play sounds. |
| **`maps-api`** | `api/maps-api` | In-game minimap / filled map rendering | Implement `MapBinding` to render RTP region bounds and biomes onto platform map canvases. |
| **`anvil-api`** | `api/anvil-api` | Off-tick region file decoding | Supply `anvilWorldFolder()` on `RTPWorld` so core can parse `.mca` / `.linear` files off-thread. |
| **`Network Mode`** | `rtp-core` | Multi-server proxy transport (Velocity/Redis) | Bind `backendStateSamplerFactory` and `networkBridgeFactory` to support cross-server RTP. |

---

## 8. Implementation Checklist for a New Server Adapter

Use this step-by-step checklist when building a new platform adapter from scratch:

- [ ] **1. Module & Repo Setup:**
  - Create `platforms/rtp-<platform>` directory.
  - Add `include 'rtp-<platform>'` (or version submodules) to `settings.gradle` and assign `project(':rtp-<platform>').projectDir = file('platforms/rtp-<platform>')`.
  - Add new platform identifier to `io.github.dailystruggle.server.api.PlatformFamily` enum in `server-api`.
  - Configure `build.gradle` targeting Java 21+ with `api project(':rtp-api')`, `implementation project(':rtp-core')`, and `compileOnly project(':commands-api')`. Ensure zero platform imports leak into `rtp-core`.
- [ ] **2. Scheduler Implementation:**
  - Implement `RTPScheduler` (synchronous tick dispatch, asynchronous thread pool, delayed/repeating tasks).
  - Implement regional scheduling if platform is partitioned (e.g. Folia `RegionScheduler`/`EntityScheduler`), otherwise delegate to sync tick thread.
  - Bound repeating maintenance tasks under a tick budget (`TickBudget.syncNanos()`).
- [ ] **3. World & Chunk Adapters:**
  - Subclass `RTPWorld<NativeLevel>`:
    - Implement non-blocking `isChunkLoaded` and `isChunkGenerated`.
    - Implement non-blocking, asynchronous `getChunkAtAsync` (S-005).
    - Implement chunk ticket retention in `keepChunkAt` and `forgetChunkAt` using transient tickets (S-002).
    - Implement `releaseOrphanedTickets` for periodic GC sweeps.
    - Wire `anvilWorldFolder` and `anvilDimensionSubpath` for off-tick Anvil pre-filtering.
  - Subclass `RTPChunk<NativeChunk>`:
    - Implement `materialNameAt(x, y, z)` returning namespaced uppercase IDs (e.g. `"MINECRAFT:STONE"`).
    - Implement fail-closed `isSafe` and `CompiledUnsafeSet` checks (S-001).
- [ ] **4. Player & Sender Wrappers:**
  - Implement `RTPPlayer` wrapping native player:
    - Implement `setLocation(RTPLocation)` returning a `CompletableFuture<Boolean>` (S-004).
    - Implement `isOnline()`, `getLocation()`, `hasPermission()`, `sendMessage()`.
  - Implement console / sender wrapper (`RTPCommandSender`).
- [ ] **5. Server Accessor & Event Bridge:**
  - Subclass `RTPServerAccessor`:
    - Wire `getRTPWorld`, `getPlayer`, `getScheduler`, `createTaskPipe`.
    - Cache wrappers and support dynamic registration/unregistration.
  - Create platform event bridge translating native events to `PlayerLifecycleHook` (join, quit, move, combat, world load/unload).
  - Unregister players on disconnect to prevent memory leaks (REQ-RTP-S-004).
- [ ] **6. Invariant-Safe Bootstrapping & Lifecycle:**
  - In plugin/mod startup lifecycle (`onEnable`, `onInitialize`, `@Mod`, or server application `main`):
    - Instantiate the accessor and scheduler.
    - Set `RTP.serverAccessor = accessor;` and `RTP.scheduler = scheduler;` **before** constructing `RTP`.
    - Call `accessor.start(pluginHandle);`.
    - Construct `new RTP();` (or retrieve `RTP.getInstance()`).
    - Schedule `ChunkUnloadProcessor` timer on non-Folia platforms: `RTP.scheduler.runTaskTimer(new ChunkUnloadProcessor(), 1, 1);` (S-002).
    - Drain `RTP.getInstance().startupTasks` synchronously and schedule a deferred drain at `tick + 1`.
    - Register `/rtp` and `/wild` commands in the platform's command dispatcher.
    - Wire telemetry bindings (`RTP.metrics.setBinding(...)`) and network sampler factories if supported.
  - In shutdown lifecycle:
    - Cancel tasks via `RTPScheduler#cancelTasks()`.
    - Call `accessor.stop()` and `RTP.stop()`.
- [ ] **7. Verification:**
  - Verify zero synchronous chunk loads occur on main/tick threads (S-005).
  - Verify chunk tickets release after teleport completion or player quit (S-002).
  - Verify invalid destination blocks fail closed (S-001).
  - Run unit and integration tests against adapter methods.

---

## 9. In-Tree Platform Adapter Repository Changes

When adding a new platform adapter directly into the LeafRTP monorepo (`platforms/rtp-<platform>`), follow this exact repository change specification.

### 9.1 Directory Layout & Naming Conventions
All server platform adapters are placed under the `platforms/` directory:

```
platforms/
├── rtp-bukkit/           # Bukkit / Spigot platform adapter
├── rtp-paper/            # Paper async chunk adapter
├── rtp-folia/            # Folia regional multithreading adapter
├── rtp-fabric/           # Fabric loader adapter
├── rtp-neoforge/         # NeoForge mod adapter
├── rtp-proxy/            # Velocity / BungeeCord network proxy adapter
└── rtp-<platform>/       # NEW platform adapter (e.g. rtp-minestom, rtp-sponge)
    ├── build.gradle
    └── src/
        ├── main/
        │   ├── java/
        │   │   └── io/github/dailystruggle/rtp/<platform>/
        │   │       ├── <Platform>RTPBootstrap.java
        │   │       ├── server/<Platform>ServerAccessor.java
        │   │       ├── scheduling/<Platform>Scheduler.java
        │   │       ├── world/<Platform>RTPWorld.java
        │   │       ├── world/<Platform>RTPChunk.java
        │   │       ├── entity/<Platform>RTPPlayer.java
        │   │       └── events/<Platform>EventBridge.java
        │   └── resources/
        └── test/
```

- **Single Module vs. Versioned Submodules:**
  - **Single Module:** Use when the platform API is unified, backwards-compatible, or runtime-deobfuscated (e.g. Minestom, Sponge, modern unified Fabric/NeoForge).
  - **Versioned Submodules (ADR-010 / ADR-103):** If compiling against multiple incompatible NMS / platform bytecode revisions, split into `<platform>-common` (version-neutral logic) and `<platform>-vXX_YY_R1` (version-specific carriers resolved at runtime via `Class.forName`).

### 9.2 `settings.gradle` Configuration
LeafRTP keeps Gradle project paths flat while grouping physical directories under `platforms/`. To register the new adapter in `settings.gradle`:

1. **Include and Relocate:**
```groovy
include 'rtp-<platform>'
project(':rtp-<platform>').projectDir = file('platforms/rtp-<platform>')
```
*(If using versioned submodules, include each submodule and assign its respective `projectDir`).*

2. **Conditional Toolchain Gating (Optional):**
If the new platform relies on specialized build plugins (e.g. Loom, ModDevGradle) or network-only maven repositories that cannot be resolved in offline sandboxes or lightweight CI, gate the inclusion with a project property:
```groovy
def exclude<Platform> = startParameter.projectProperties.containsKey('exclude<Platform>') ||
        System.getenv('EXCLUDE_<PLATFORM>') != null
if (!exclude<Platform>) {
    include 'rtp-<platform>'
    project(':rtp-<platform>').projectDir = file('platforms/rtp-<platform>')
}
```

### 9.3 `server-api` PlatformFamily Registration
Every platform adapter must have a recognized entry in `io.github.dailystruggle.server.api.PlatformFamily`:

```java
public enum PlatformFamily {
    BUKKIT,
    PAPER,
    FOLIA,
    FABRIC,
    NEOFORGE,
    VELOCITY,
    BUNGEECORD,
    MINESTOM; // New platform constant
}
```
Ensure your `RTPServerAccessor#getPlatformFamily()` returns this constant. This allows core and addons to execute platform-specific capability checks (`accessor.isCompatible(PlatformFamily.MINESTOM, min, max)`).

### 9.4 Subproject `build.gradle` Structure
The new platform module must depend on `rtp-api` and `rtp-core` while keeping platform types strictly isolated:

```groovy
plugins {
    id 'java-library'
    id 'maven-publish'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // Core engine and API dependencies
    api project(':rtp-api')
    implementation project(':rtp-core')

    // Unified command framework
    compileOnly project(':commands-api')

    // Optional SPI frameworks
    compileOnly project(':effects-api')
    compileOnly project(':metrics-api')
    compileOnly project(':anvil-api')

    // Target Platform API / Modloader dependencies (compileOnly or modCompileOnly)
    compileOnly 'net.minestom:minestom-snapshots:1.21.1-SNAPSHOT'

    // Testing
    testImplementation testFixtures(project(':rtp-core'))
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
}
```

### 9.5 Distribution & Packaging: Unified JAR vs. Standalone Artifact
LeafRTP supports two distribution paradigms for platform adapters:

1. **Unified Multi-Loader JAR (`rtp-plugin`):**
   - For server modloaders where operators expect a single release jar that boots across loaders (Bukkit, Paper, Folia, Fabric, NeoForge, Velocity per **ADR-022** and **ADR-036**).
   - In `rtp-plugin/build.gradle`, add `implementation project(':rtp-<platform>')` (or merge post-remap bytecode).
   - Include the loader descriptor file (`plugin.yml`, `fabric.mod.json`, `neoforge.mods.toml`, `velocity-plugin.json`) in `rtp-plugin/src/main/resources/`.
2. **Standalone Platform Artifact / Library:**
   - For embedded or standalone server frameworks (such as Minestom standalone servers or Sponge plugins), where bundling into a monolithic multi-loader jar creates unnecessary classloader overhead.
   - Configure a standalone shaded jar task (`shadowJar`) directly within `platforms/rtp-<platform>/build.gradle` or publish as a standalone Maven dependency.

### 9.6 Complete Bootstrap Sequence & Ordering Invariants
Platform adapters must adhere to the following sequence during startup. Deviating from this order causes `NullPointerException`s or leaks chunk tickets:

```java
public void onServerBootstrap(Object hostContext) {
    // 1. Instantiate Accessor and Scheduler
    MyPlatformServerAccessor accessor = new MyPlatformServerAccessor();
    RTPScheduler scheduler = accessor.getScheduler();

    // 2. Assign static references BEFORE constructing RTP
    // CRITICAL: RTP constructor immediately schedules background timers on RTP.scheduler!
    RTP.serverAccessor = accessor;
    RTP.scheduler = scheduler;

    // 3. Start the accessor (binds data folders, factories, and native server handles)
    accessor.start(hostContext);

    // 4. Instantiate the core engine
    RTP rtp = RTP.getInstance();
    if (rtp == null) {
        rtp = new RTP();
    }

    // 5. Start ChunkUnloadProcessor (CRITICAL for S-002 on non-Folia platforms)
    // Teleport pipeline locations reserved with keep(true) are enqueued to chunksToUnload.
    // Without this timer, reserved chunks remain locked in memory!
    scheduler.runTaskTimer(new ChunkUnloadProcessor(), 1, 1);

    // 6. Drain startupTasks synchronously and schedule deferred drain
    // The RTP() constructor queues region setup and database hydration onto startupTasks.
    rtp.startupTasks.execute(Long.MAX_VALUE);
    scheduler.runTaskLater(() -> {
        while (rtp.startupTasks.size() > 0) {
            rtp.startupTasks.execute(Long.MAX_VALUE);
        }
    }, 1);

    // 7. Register commands with platform command manager
    registerCommands(accessor);

    // 8. Register platform event listeners to drive PlayerLifecycleHook
    registerEvents(accessor);

    // 9. Install telemetry bindings (optional)
    installMetricsBinding(accessor);
}
```

---

## 10. Out-of-Tree Standalone Server Adapter (Embedded RTP Engine)

Developers who maintain private server runtimes, custom game engines, or standalone Minestom applications can build a server adapter **without modifying the LeafRTP repository**.

### 10.1 Maven / Gradle Coordinates
LeafRTP publishes its platform-agnostic core and SPI modules to Maven repositories and JitPack:

```groovy
repositories {
    mavenCentral()
    maven { url = 'https://jitpack.io' }
}

dependencies {
    // Core engine and API
    implementation 'com.github.DailyStruggle.RTP:rtp-api:v3.2.0'
    implementation 'com.github.DailyStruggle.RTP:rtp-core:v3.2.0'

    // SPI frameworks (as needed)
    compileOnly 'com.github.DailyStruggle.RTP:commands-api:v3.2.0'
    compileOnly 'com.github.DailyStruggle.RTP:yaml-api:v3.2.0'
    compileOnly 'com.github.DailyStruggle.RTP:metrics-api:v3.2.0'
}
```

### 10.2 Minimal Standalone Bootstrap Example
In a standalone server application (e.g. Minestom `main` entry point):

```java
public class MyServerApp {
    public static void main(String[] args) {
        MinecraftServer server = MinecraftServer.init();

        // Configure RTP data directory (<appDir>/config/rtp/)
        File configDir = new File("config/rtp");

        // Instantiate and wire adapter
        StandaloneServerAccessor accessor = new StandaloneServerAccessor(server, configDir);
        RTP.serverAccessor = accessor;
        RTP.scheduler = accessor.getScheduler();
        accessor.start(server);

        // Boot core RTP engine
        new RTP();

        // Schedule chunk ticket release processor
        RTP.scheduler.runTaskTimer(new ChunkUnloadProcessor(), 1, 1);

        // Drain initial startup tasks
        RTP.getInstance().startupTasks.execute(Long.MAX_VALUE);

        // Start server
        server.start("0.0.0.0", 25565);
    }
}
```

---

## 11. Addon-Based Server Adapter & Subsystem Integration (`RTPAddon`)

A frequent developer question is: *"Can I create an `RTPAddon` that contains a server adapter?"*
Understanding the architectural boundary between the **Platform Host Adapter** and an **`RTPAddon`** is essential.

### 11.1 The Host Adapter vs. Addon Architectural Boundary
- **Platform Host Adapter (`RTPServerAccessor` / `RTPScheduler`):**
  - **Role:** Bridges the core engine to the host server environment (thread scheduling, chunk loading, world registration).
  - **Lifecycle Timing:** Must run **before** `new RTP()`. Without it, `RTP` cannot construct and throws `IllegalStateException`.
  - **Discovery:** Explicitly instantiated by the server mod/plugin bootstrap.
- **`RTPAddon` (`io.github.dailystruggle.rtp.api.addon.RTPAddon`):**
  - **Role:** Extends functionality on an already booted RTP engine (per **ADR-057**).
  - **Lifecycle Timing:** Discovered and loaded by `AddonRegistry` **after** `rtp-core` has already initialized.
  - **Discovery:** Automatic via `java.util.ServiceLoader` from the host classpath or jar files placed in `<pluginDir>/addons/`.

**Key Architectural Conclusion:** An `RTPAddon` cannot act as the primary host server adapter for an un-adapted server platform because the engine cannot start without an accessor. However, an `RTPAddon` is the primary and recommended mechanism to adapt **server subsystems** (protection plugins, custom region formats, custom effects, custom shapes) on top of an existing server adapter.

### 11.2 What an `RTPAddon` Can Adapt

| Subsystem | Integration Point | Example Use Case |
|---|---|---|
| **Claim / Territory Protection** | `RTPAPI.hooks().verifiers().register(...)` | Integrating GriefPrevention, Towny, WorldGuard, FTB Chunks, or custom instance tags without editing core. |
| **Region File Formats** | `RegionFormatRegistry.register(...)` | Adding support for `.linear` region files ([ADR-077](ADR-077-multi-format-region-support.md)) or custom compressed world formats. |
| **Visual & Particle Effects** | `EffectFactory.addEffect(name, effect)` | Registering custom warmup/teleport animations (e.g. `LeafRTPRiftAddon`). |
| **GUI & Menus** | `RTPAPI.selectionAPI` | Providing platform-specific inventory menus (e.g. `LeafRTPGuiAddon`). |
| **Custom Region Shapes** | `RTP.serverAccessor.setShapeFunction(...)` | Adding non-standard region shapes (hexagons, polygons, spline curves). |
| **Custom Biome Resolvers** | `RTP.serverAccessor.setBiomeGetter(...)` | Overriding biome detection for custom terraforming engines. |
| **Declarative Actions** | `ActionManager.registerAction(...)` | Custom scripted actions on pre/post teleport ([ADR-093](ADR-093-declarative-scripted-actions.md)). |

### 11.3 Authoring an `RTPAddon`
To author a subsystem adapter addon:

1. **Implement `RTPAddon`:**
```java
package com.example.myaddon;

import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.logging.Level;

public final class MySubsystemAddon implements RTPAddon {
    @Override
    public String name() {
        return "MySubsystemAddon";
    }

    @Override
    public void onLoad() {
        // Register claim verifier
        if (RTPAPI.hooks != null) {
            RTPAPI.hooks.verifiers().register("my_claim_system", location -> {
                // Return false if location is protected/claimed (vetoes destination)
                return !MyClaimAPI.isClaimed(location.world().name(), location.x(), location.z());
            });
        }
        RTP.log(Level.INFO, "[MySubsystemAddon] Registered custom protection hook.");
    }

    @Override
    public void onUnload() {
        if (RTPAPI.hooks != null) {
            RTPAPI.hooks.verifiers().unregister("my_claim_system");
        }
    }
}
```

2. **Register Service Provider Descriptor:**
Create `src/main/resources/META-INF/services/io.github.dailystruggle.rtp.api.addon.RTPAddon` containing:
```
com.example.myaddon.MySubsystemAddon
```

3. **Packaging and Deployment:**
- Compile into a jar and drop into the `<pluginDir>/addons/` folder.
- `rtp-core` automatically extracts bundled addons, scans the directory with `URLClassLoader`, and executes `onLoad()` on startup.

---

## 12. Architectural Decision Matrix

Use this matrix to determine the correct implementation route for your project:

| Criterion | In-Tree Platform Adapter | Out-of-Tree Standalone Adapter | RTPAddon Subsystem Adapter |
|---|---|---|---|
| **Location** | `platforms/rtp-<platform>` in RTP repo | Independent external repository | `addons/` folder or separate jar |
| **Scope** | Complete host server bridge (scheduler, chunks, players) | Complete host server bridge (scheduler, chunks, players) | Subsystem extension (claims, effects, GUI, formats) |
| **Engine Lifecycle** | Executes *before* `new RTP()` | Executes *before* `new RTP()` | Executes *after* `new RTP()` |
| **Discovery** | Platform entry point (`rtp-plugin` / mod) | Platform entry point (`main()` / mod) | `ServiceLoader` via `AddonRegistry` |
| **Target Audience** | Mainstream server platforms (Minestom, Sponge) | Private server forks, custom game engines | Server admins, plugin developers, 3rd party tools |
| **Repo Maintenance** | Maintained in LeafRTP release cycle | Maintained independently by author | Maintained independently by author |
