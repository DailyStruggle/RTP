# server-api

Platform-neutral server identity, version compatibility, logging, and regionalized/async task scheduling SPI (`io.github.dailystruggle.server.api`). Pure Java 17 (`java.base` and `java.logging` only; zero Bukkit, Paper, Folia, Fabric, NeoForge, or `rtp-core` dependencies), so any standalone plugin or mod (e.g. `OnePlayerSleep`, `TreeGen`, `WorldGuardLite`) can gate platform-specific hooks, log through the host runtime, and schedule region-safe tasks across single-thread and regionalized Folia servers.

## Coordinates

- **Maven Central:** `io.github.dailystruggle:server-api:<version>`
- **JitPack:** `com.github.DailyStruggle.RTP:server-api:<tag>`

```gradle
dependencies {
    implementation 'io.github.dailystruggle:server-api:3.3.1'
    // or via JitPack:
    // implementation 'com.github.DailyStruggle.RTP:server-api:3.3.1'
}
```

## Surface

- `ServerAccessor` - host-runtime bridge reporting:
  - Server & platform identity: `getServerVersion()`, `getPlatform()`, `getServerIntVersion()`, `getPlatformFamily()`, `isPlatformFamily(PlatformFamily)`, `isServerVersionAtLeast(int)`, `isServerVersionAtMost(int)`, `isCompatible(requiredFamily, minServerVersion, maxServerVersion)`, `getServerName()`, `getPluginDirectory()`.
  - Threading, tick health & scheduling: `isPrimaryThread()`, `overTime()`, `getTPS(int)`, `scheduler()`, `log(Level, String[, Throwable])`.
  - Online roster, lifecycle & player probes (ADR-048, ADR-049): `getOnlinePlayerNames()`, `getPlayerLifecycleHook()`, `clearProgressBars()`, `menuPermissionProbe(UUID)`, `menuEffectivePermissions(UUID)`, `menuLocale(UUID)`, `menuRegionDescriptor(UUID)`.
  - Command registration & headless capture: `registerCommands(Object, String...)`, `executeCommand(UUID, String)`, `executeCommandWithCapture(String, Consumer<String>)`, `executeCommandWithCapture(String)`.
  - Scoreboard tags & objectives: `getScoreboardTags(UUID)`, `addScoreboardTag(UUID, String)`, `removeScoreboardTag(UUID, String)`, `getScoreboardScore(UUID, String)`, `setScoreboardScore(UUID, String, int)`, `resetScoreboardScore(UUID, String)`, `hasScoreboardObjective(String)`, `ensureScoreboardObjective(String, String)`.
  - Per-player world border & health (ADR-093): `sendWorldBorder(...)` (3 overloads), `resetWorldBorder(UUID)`, `damagePlayer(UUID, double)`.
  - Registry & palette snapshot surface (ADR-017): `materials()`, `blockTagSnapshot()`, `rebuildBlockTagSnapshot()`, `reconcilePaletteIdentifier(String)`, `reconcilePaletteIdentifiers(Collection<String>)`, `matchesPaletteIdentifier(String, Set<String>)`.
- `ServerAccessors` - process-wide volatile holder (`get()`, `set(ServerAccessor)`, `setScheduler(TaskScheduler)`, `scheduler()`, `log(Level, String[, Throwable])`) falling back to a standard `java.util.logging.Logger` before initialization.
- `PlatformFamily` - coarse runtime classification enum: `BUKKIT` (Spigot, Paper, Folia, Purpur), `FABRIC`, `NEOFORGE`, and `UNKNOWN`.
- `TaskScheduler` - platform-neutral scheduling and region-ownership contract (`runAsync`, `runSync`, `runSyncDelayed`, `runSyncTimer`, `runAsyncTimer`, `runAtLocation(worldName, chunkX, chunkZ, task)`, `isOwnedByCurrentRegion(worldName, chunkX, chunkZ)`, `isPrimaryThread()`).
- `TaskHandle` - cancellable task handle (`cancel()`, `isCancelled()`) with idempotent factory `TaskHandle.of(Runnable cancelAction)`.
- `ProgressBar` - platform-neutral value object describing an on-screen progress bar (`title()`, `progress()`, `viewerPermission()`).
- `PlayerLifecycleHook`, `NoopPlayerLifecycleHook`, `DispatchingPlayerLifecycleHook` - platform-neutral join/quit event subscription SPI and thread-safe fan-out base with per-subscriber exception isolation.

## Usage

```java
import io.github.dailystruggle.server.api.PlatformFamily;
import io.github.dailystruggle.server.api.ServerAccessor;
import io.github.dailystruggle.server.api.ServerAccessors;
import io.github.dailystruggle.server.api.TaskHandle;
import java.util.logging.Level;

// Standalone plugin (e.g. OnePlayerSleep) checking runtime compatibility and scheduling region-safe work:
ServerAccessor server = ServerAccessors.get();
if (server != null && server.isCompatible(PlatformFamily.BUKKIT, 20, Integer.MAX_VALUE)) {
    ServerAccessors.log(Level.INFO, "Running on Bukkit-family 1.20+: " + server.getServerVersion());
}

// Schedule a chunk-bound task on the owning Folia region (or main thread on single-loop servers)
TaskHandle handle = ServerAccessors.scheduler()
        .map(sched -> sched.runAtLocation("world", 4, -2, () -> {
            // Safely mutate blocks or entities in chunk (4, -2)
        }))
        .orElseGet(() -> TaskHandle.of(() -> {}));
```

## Build

```
./gradlew :server-api:build
```

## Docs

- ADR-116: [`../../docs/adr/ADR-116-effects-through-neutral-server-accessor.md`](../../docs/adr/ADR-116-effects-through-neutral-server-accessor.md)
