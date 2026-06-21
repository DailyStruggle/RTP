# External Hooks — Reflection, Soft-Depends and Behavior-Modification API

> **Audience.** Plugin/addon authors who want to *modify* RTP behavior (block locations, charge money, expose placeholders, override the world border, supply pre-filter data) and AI/human contributors who need to know every site where third-party code can change what RTP does.

> **Authoritative status.** Every reflection, soft-depend probe, or extension seam that exists *to accommodate other plugins modifying RTP behavior* shall appear in this file. Adding a new hook without a row here is a documentation defect (see [`AGENTS.md → Self-Updating Protocol`](../../.junie/AGENTS.md)).

> **Related decisions.** [ADR-026](../adr/ADR-026-external-hook-api-surface.md) (this surface), [ADR-019](../adr/ADR-019-claim-plugin-integrations-folded-into-plugin.md) (claim integrations), [ADR-016](../adr/ADR-016-anvil-subsystem.md) (anvil prefilter), [commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md) (brigadier bridge).

---

## TL;DR — preferred entry point

```java
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.hooks.RTPHooks;

RTPHooks hooks = RTPAPI.hooks(); // throws IllegalStateException if core not yet loaded (S-006)
```

From `RTPHooks` you reach every behavior-modification registry in one place. Direct calls to `rtp-core` symbols (e.g. `GlobalRegionVerifiers`) still work for source compatibility but are no longer the recommended path for new code.

---

## Hook catalog

### 1. Region verifiers — `RTPHooks#verifiers()`

| | |
|---|---|
| **API symbol** | `io.github.dailystruggle.rtp.api.hooks.RegionVerifierRegistry` |
| **Backing impl** | `io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers` (`rtp-core`) |
| **Behavior modified** | Vetoes a candidate teleport location before the player is sent there. |
| **When invoked** | Every per-attempt verification pass (`PregenTask`, `QueueTask`, `ScanTask`). |
| **Threading** | Sync verifiers run on the verification chain — non-blocking. Async verifiers return a `CompletableFuture` and may do off-thread I/O but shall not block a region/tick thread. |
| **Failure mode** | A throwing verifier is logged at WARNING and treated as `false` (location rejected). RTP does not silently swallow failures (REQ-RTP-S-004). |
| **Producers (today)** | `addons/LeafRTPClaimAddon` (`ClaimIntegrations` + the `{SaberFactions,FactionsBridge,GriefDefender,GriefPrevention,Lands,RedProtect,Residence,CrashClaim,HuskClaims,KingdomsX,TownyAdvanced,WorldGuard}Checker`s; bundled inside the RTP jar and self-extracted into `<pluginDir>/addons/` on first run, per [ADR-069](../adr/ADR-069-claim-integrations-extracted-to-bundled-addon.md), superseding ADR-019). `SaberFactionsChecker`, `FactionsBridgeChecker`, `ResidenceChecker`, `CrashClaimChecker`, `HuskClaimsChecker`, and `KingdomsXChecker` resolve their plugin APIs reflectively, so they carry no compile-only dependency. Also `addons/RTP_ExampleAddon`; `addons/RTP_Glide`. |
| **REQ / S-rule** | REQ-RTP-S-003, REQ-API-F-003. |
| **Backward compat** | Legacy static methods on `GlobalRegionVerifiers` continue to work and are bidirectional with the new registry. |

```java
RTPAPI.hooks().verifiers().register(coords -> myCheckReturnsTrueIfSafe(coords));
RTPAPI.hooks().verifiers().registerAsync(coords -> myAsyncCheck(coords));
```

### 2. Economy — `RTPHooks#economy()`

| | |
|---|---|
| **API symbol** | `io.github.dailystruggle.rtp.api.hooks.EconomyProviderRegistry` |
| **Provider type** | `io.github.dailystruggle.rtp.api.economy.RTPEconomy` |
| **Backing field** | `RTP.economy` (read path inside `rtp-core`). |
| **Behavior modified** | Charges and refunds for `/rtp` invocations. |
| **When invoked** | `BukkitBaseRTPCmd` (and platform analogues) on cooldown/cost evaluation; refund on teleport failure. |
| **Threading** | May be called from the async generation pipeline; implementations shall be thread-safe (REQ-API-ARCH-001). |
| **Producers (today)** | `rtp-plugin/.../softdepends/VaultChecker.java` (Vault + EssentialsX). |
| **Fallback when target absent** | A platform no-op `RTPEconomy` is provided by the server accessor; teleport pipeline never NPEs. |
| **Backward compat** | Direct writes to `RTP.economy` still work; the registry simply provides an API-only path. |

```java
RTPAPI.hooks().economy().bind(myRTPEconomy);
```

### 3. Placeholders — `RTPHooks#placeholders()`

| | |
|---|---|
| **API symbol** | `io.github.dailystruggle.rtp.api.hooks.PlaceholderProviderRegistry` |
| **Behavior modified** | Names exposed by RTP to PlaceholderAPI / chat plugins (`%rtp_<key>%`). |
| **When invoked** | On every chat/scoreboard/tab-list refresh that includes an `rtp_*` placeholder. |
| **Threading** | Resolvers may be invoked from any thread; shall not block server APIs. |
| **Consumer (today)** | `rtp-plugin/.../softdepends/PAPI_expansion.java` reads this registry and exposes each entry through PlaceholderAPI. |
| **Producers (today)** | RTP itself registers the built-in keys (`cooldown_remaining`, etc.) once the registry is in place; third-party plugins may register additional keys. |

```java
RTPAPI.hooks().placeholders().register("my_metric",
    (uuid, key) -> Integer.toString(myCounter.get(uuid)));
```

### 4. World border — `RTPHooks#worldBorder()`

| | |
|---|---|
| **API symbol** | `io.github.dailystruggle.rtp.api.hooks.WorldBorderProviderRegistry` |
| **Behavior modified** | Constrains the candidate radius and per-attempt sampling to "inside the border". |
| **When invoked** | Region setup, per-attempt `Shape` sampling. |
| **Threading** | Async-safe; `Provider#isInside` shall not block. |
| **Producers (today)** | `rtp-core/.../tools/ChunkyChecker.java` (Chunky/ChunkyBorder integration); platform-native `WorldBorder` is the fallback. |

```java
RTPAPI.hooks().worldBorder().bind((world, x, z) -> myBorder.contains(world, x, z));
```

### 5. Anvil pre-filter — `RTPHooks#anvilPrefilter()`

| | |
|---|---|
| **API symbol** | `io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry` |
| **Provider** | `Provider#classify(world, cx, cz) → ACCEPT / REJECT / UNKNOWN`. |
| **Behavior modified** | Allows region scanning to skip whole chunks without loading them, by reading anvil/NBT data directly. See [ADR-016](../adr/ADR-016-anvil-subsystem.md). |
| **When invoked** | `ScanTask` per-chunk classification (REQ-RTP-S-005 — anvil pre-filter is a primary tool to avoid main-thread chunk I/O). |
| **Producers (today)** | `rtp-anvil` module (`AnvilRegionByteCache`). The legacy reflective lookup in `ScanTask` (`Class.forName("…AnvilRegionByteCache")`) remains as a fallback for one release cycle and shall be removed once `rtp-anvil` self-registers via the registry (deferred follow-up). |
| **Threading** | Off-main-thread on Folia (REQ-RTP-S-005); implementations shall be thread-safe. |

```java
RTPAPI.hooks().anvilPrefilter().bind((world, cx, cz) -> myDecision(world, cx, cz));
```

### 6. PvP combat state - `RTPHooks#pvpCombatState()`

| | |
|---|---|
| **API symbol** | `io.github.dailystruggle.rtp.api.hooks.PvPCombatStateRegistry` |
| **Provider** | `Provider#isInCombat(UUID) -> boolean`. |
| **Behavior modified** | Replaces RTP's native PvP damage tracker as the authority for the optional `/rtp` combat gate (refuse / delay / cancel a teleport for a combat-tagged player). See [ADR-055](../adr/ADR-055-pvp-combat-gate.md) and ROADMAP Tier 2. |
| **When invoked** | `PvPGate` at the `/rtp` pre-dispatch surface (before queue enrolment) and again before the destination is applied (execution prefilter). Gate is off by default (`safety.yml#pvpCheckEnabled`). |
| **Threading** | Called from the command thread and the teleport pipeline; implementations shall be thread-safe and non-blocking. |
| **Failure mode** | A throwing provider is logged once at WARNING and treated as "not in combat" (REQ-RTP-S-004); a buggy integration never blocks teleports. |
| **Producers (today)** | `rtp-plugin/.../softdepends/pvp/{PvPManagerChecker,CombatLogXChecker,SimpleCombatLogChecker}` via `PvPIntegrations.setup(...)`, gated on `isPluginEnabled(...)` like the claim `*Checker`s. The first enabled plugin (priority: PvPManager, then CombatLogX, then Simple Combat Log) is bound through `RTPAPI.hooks().pvpCombatState().bind(...)`. PvPManager and CombatLogX have stable developer APIs and their adapters compile against the published API as `compileOnly` dependencies (provided by the plugin at runtime, declared as Bukkit `softdepend`s in `plugin.yml` so the cross-plugin classes resolve); Simple Combat Log has no published artifact and stays reflective (it probes for a conventional combat-query method and falls back to the native tracker when none is exposed). When none is bound (or `pvpSource: NATIVE`), RTP's `NativePvPCombatTracker` answers instead. Third-party combat plugins and addons do **not** need a bundled `*Checker`: they may bind their own provider directly via `RTPAPI.hooks().pvpCombatState().bind(...)` (see the worked example in [`addons/RTP_ExampleAddon/README.md`](../../addons/RTP_ExampleAddon/README.md)). A bound provider replaces any bundled adapter (single-binding, last-bind-wins). |
| **REQ / S-rule** | REQ-RTP-S-004, REQ-RTP-F-013. |

```java
RTPAPI.hooks().pvpCombatState().bind(uuid -> myCombatPlugin.isTagged(uuid));
```

### 7. Bare-`/rtp` root action - `RTPHooks#rootAction()`

| | |
|---|---|
| **API symbol** | `io.github.dailystruggle.rtp.api.hooks.RootActionRegistry` |
| **Action** | `Action#run(UUID, Consumer<String>) -> boolean handled`. |
| **Behavior modified** | Replaces what a bare `/rtp` (no arguments) does, e.g. open an addon GUI instead of teleporting. Subcommands (`/rtp admin`, ...) are never affected. See [ADR-056](../adr/ADR-056-bare-rtp-root-action.md). |
| **When invoked** | `RTPCmd.onCommand` on the bare-root (`!hasSubCommand`) branch, before the teleport guards. `return true` handles the command (classic teleport suppressed, cooldown / processing bypassed); `return false` defers to the classic teleport. |
| **Threading** | Runs on the command thread; implementations shall be non-blocking and must not perform synchronous chunk I/O (REQ-RTP-S-005). |
| **Failure mode** | A throwing action is logged once at WARNING and treated as not-handled, so a bare `/rtp` falls back to the classic teleport (REQ-RTP-S-004). |
| **Producers (today)** | None in core (single-binding, addon-supplied). A GUI/menu addon binds an action that opens its picker; to reuse the classic teleport it returns `false` or calls `RTPAPI.teleport(uuid, target)` itself. |
| **REQ / S-rule** | REQ-API-F-006, REQ-RTP-S-004. |

```java
RTPAPI.hooks().rootAction().bind((uuid, feedback) -> { openMyMenu(uuid); return true; });
```

### 8. Arrival platform creator - `RTPHooks#platformCreator()`

| | |
|---|---|
| **API symbol** | `io.github.dailystruggle.rtp.api.hooks.PlatformCreatorRegistry` |
| **Provider type** | `io.github.dailystruggle.rtp.api.platform.PlatformCreator` (the bundled file-backed specialisation is `io.github.dailystruggle.rtp.api.schematic.SchematicPaster`). |
| **Action** | Two-phase: `PlatformCreator#prepare(RTPLocation) -> CompletableFuture<?>` (off-thread) then `PlatformCreator#createPlatform(RTPLocation, Object prepared) -> boolean built` (region thread). `createPlatform(RTPLocation)` is a convenience single-phase default for creators with nothing to pre-load. |
| **Behavior modified** | Replaces RTP's built-in emergency block disc with an addon-supplied arrival platform - a procedural pad, a lobby structure, a pasted schematic, etc. See [ADR-058](../adr/ADR-058-region-specific-schematic-paste.md). |
| **When invoked** | `prepare(at)` runs in `TeleportPipelineTask#runLoad`, off the region thread, so any blocking load happens there (REQ-RTP-S-005); `createPlatform(at, prepared)` runs in `TeleportPipelineTask#buildArrivalPlatform`, on the region-owning thread, immediately before the player is moved, whenever the landing column warrants a platform (`shouldBuildPlatform`). A bound `SchematicPaster` is driven through this **same** two-phase path - it is no longer special-cased at the dispatch site (its default `prepare`/`createPlatform` decline when no region source applies). |
| **Threading** | `prepare(...)` runs on a non-region (load) thread and is where blocking file/network I/O belongs; `createPlatform(...)` runs on the thread that owns the destination region (Folia region thread; Bukkit/Paper main thread) and **must not** perform synchronous file/network or chunk I/O (REQ-RTP-S-005). |
| **Failure mode** | A throwing or declining (`false`) creator - or a `prepare` future that failed or had not completed by paste time - is logged and RTP writes its default emergency platform instead (REQ-RTP-S-004); the creator never aborts the teleport. |
| **Producers (today)** | None in core (single-binding, addon-supplied). The region-specific schematic (resolved from the world/region `schematics/<region>.schem` file) is an independent path and remains the default when no addon override is bound. |
| **REQ / S-rule** | REQ-RTP-S-001, REQ-RTP-S-004, REQ-RTP-S-005. |

```java
// Single-phase: a creator with nothing to pre-load just overrides createPlatform(at).
// It runs on the region thread; return true once the platform is written, false to let
// RTP build its default emergency disc instead.
RTPAPI.hooks().platformCreator().bind(new PlatformCreator() {
  @Override public String creatorName() { return "MyLobbyPad"; }
  @Override public boolean createPlatform(RTPLocation at) {
    return myPadBuilder.build(at); // no blocking I/O here
  }
});

// Two-phase: a creator that must load/fetch/generate first does the blocking work in
// prepare() and consumes its handle on the region thread. RTP already invokes prepare()
// on the pipeline's load (non-region) thread, so do the blocking work INLINE and return a
// completed future - do NOT spin up your own executor or CompletableFuture.supplyAsync
// (all async work on a backend JVM must go through RTP.scheduler, never a raw thread pool).
RTPAPI.hooks().platformCreator().bind(new PlatformCreator() {
  @Override public String creatorName() { return "MyStructurePad"; }
  @Override public CompletableFuture<?> prepare(RTPLocation at) {
    return CompletableFuture.completedFuture(loadStructure(at)); // blocking I/O is fine here
  }
  @Override public boolean createPlatform(RTPLocation at, Object prepared) {
    if (prepared == null) return false;              // declined -> default platform
    return ((Structure) prepared).writeAt(at);       // region thread: block writes only
  }
});
```

`SchematicPaster` (which `extends PlatformCreator`) is the bundled file-backed example of the same two-phase shape, adding typed `load(...)` / `paste(...)` / `supports(...)` methods for the region-specific `schematics/<region>.schem` path; bind it the same way.

---

## Hooks not (yet) routed through `RTPHooks`

The following sites also accommodate third-party plugins but are **not** routed through `RTPHooks` for the reasons listed. They are documented here for completeness.

> **spark metrics source (TPS / MSPT).** `io.github.dailystruggle.rtp.bukkitplatform.metrics.SparkMetricsBinding` (+ reflective `ReflectiveSparkStats`), installed by `MetricsBindingDispatcher#wrapWithSparkIfPresent`, reaches the spark public API (`me.lucko.spark.api.SparkProvider#get()` -> `Spark#tps()` / `#mspt()`) purely by reflection (no compile dependency on `me.lucko:spark-api`); `softdepend: [spark]` in `plugin.yml` only constrains load order. It is not a behavior-modification seam: RTP *consumes* spark's statistics to enrich `/rtp info` / `CoreMetrics`, and spark cannot change what RTP does. It wraps the native platform `MetricsBinding` and merges spark's richer TPS/MSPT over it per-field (player count / soft cap / chunk backlog / db latency / Folia regions still come from the native binding). Applied on Paper / Spigot **and Folia**: on Folia it wraps `FoliaMetricsBinding`, supplying the scalar TPS/MSPT (Folia's native per-region sampler can only derive MSPT from the inter-tick interval, not real per-tick processing time) while `foliaRegions()` delegates so per-region samples are preserved. Absent-target behavior: when spark is missing the wrap returns the native binding unchanged; even if wrapped, `SparkMetricsBinding` self-disables (delegating every field) when the spark API cannot be linked, and on a transient pre-init / not-yet-sampled state each getter returns the native binding's value, so the merge self-heals once spark warms up. See [metrics-api-ADR-001](../../metrics-api/docs/adr/metrics-api-ADR-001-module-extraction.md) and `METRICS_PLAN.md`.

| Hook | Symbol / file | Why outside the facade | Reference |
|---|---|---|---|
| **Effects pipeline** (particles / potions / sounds during teleport) | `effects-api` module | Has its own evolving SPI; folding two evolving subsystems into one facade was rejected in ADR-026. | `effects-api/src/main/java/io/github/dailystruggle/effectsapi/` |
| **Brigadier bridge** | `BrigadierCommandAdapter` + `BrigadierBridgeContext` in `commands-api/` | Used by Paper/Folia/Velocity to attach RTP commands to native Brigadier. Not a behavior-modification seam — addons do not extend it. | [commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md) |
| **Bukkit event listeners** for join / firstjoin / respawn / void RTP | `OnEventTeleports.java` (rtp-plugin) | These are RTP's *consumers* of upstream events, not extension points. Behavior is configured in `events.yml`, not by other plugins. | `docs/admin/EVENTS_AND_EFFECTS.md` |
| **`Shape` and `VerticalAdjustor` factories** | `RTP.addShape(Shape)` / `RTP.addVerticalAdjustor(VerticalAdjustor)` (`rtp-core`) | Implementation-tier extension (two-tier API model, [ADR-051](../adr/ADR-051-two-tier-api-extension-model.md)): deriving a custom shape requires the concrete `rtp-core` base classes, so registration lives on `RTP`, not the thin `rtp-api` contract. Not in `RTPHooks` because they are construction-time registrations rather than "behavior modification" seams. | `RTP.java`, REQ-API-F-001/F-002 |
| **Fabric permissions** (`fabric-permissions-api`) | `me.lucko.fabric.api.permissions.v0.Permissions.check(player, node, 2)` invoked from `FabricRTPPlayer#hasPermission` (and transitively `FabricServerAccessor#announce`, `RTPCmdFabricRoot` suggestion gating). | Per-platform soft-depend (modCompileOnly `me.lucko:fabric-permissions-api`); analogous to Vault on Bukkit. Not a behavior-modification seam — implementations (LuckPerms-Fabric, Cyan, Ledger, …) provide the same permission contract addons already use through `RTPCommandSender#hasPermission`. | `platforms/rtp-fabric/rtp-fabric-common/build.gradle`, `FabricRTPPlayer.java` |
| **NeoForge permissions** (LuckPerms) | `net.luckperms.api.LuckPermsProvider#get()` reached **reflectively** from `LuckPermsNeoForgeEnumerator` (`grantedNodes(uuid)` / `checkPermission(uuid, node)`), consulted by `NeoForgeRTPPlayer#hasPermission` / `#getEffectivePermissions` and the `NeoForgeEffectivePermissionsResolver`. | Per-platform soft-depend reached purely by reflection (no compile-time dependency on `net.luckperms:api`); analogous to the Fabric permissions row. Not a behavior-modification seam — LuckPerms provides the same permission contract addons already use through `RTPCommandSender#hasPermission`. | `platforms/rtp-neoforge/rtp-neoforge-common/.../player/LuckPermsNeoForgeEnumerator.java`, `NeoForgeRTPPlayer.java` |
| **Map binding** (cartography chart delivery) | `io.github.dailystruggle.mapsapi.MapBinding` + `MapBindingLifecycle`, slot owned by `MapDispatch.setMapBinding(...)` in `rtp-core`. Concrete impls: `BukkitMapBinding` (Paper/Spigot), `FoliaMapBinding` (extends Bukkit, adds `dispatchToViewerRegion` hook for Stage 3 live charts), `NoopMapBinding` (Lite assembly / unbound). | Not routed through `RTPHooks` because `MapDispatch` already centralises the slot via `AtomicReference<MapBinding>` and ADR-047 owns the orchestration contract; adding a parallel `RTPHooks#mapBinding()` accessor would split the source of truth. Third-party plugins may still override by calling `MapDispatch.setMapBinding(...)` after `RTPBukkitPlugin#onEnable` -- the dispatcher auto-registers any `MapBindingLifecycle` peer and fans `PlayerQuitEvent` / plugin disable through `firePlayerQuit` / `fireDisable`. | [ADR-046](../adr/ADR-046-maps-api-module.md), [ADR-047](../adr/ADR-047-declarative-chart-composition-bridge.md), `CHECKLIST-maps-api.md` Stage 2.1 / 2.3 / 2.6 |

---

## Pre-existing reflection sites — not behavior modification

These `Class.forName` / `getMethod` sites exist for **platform compatibility detection**, not for accommodating other plugins. They are listed for auditability; do not route them through `RTPHooks`.

| Site | Purpose |
|---|---|
| `RTPBukkitPlugin#onLoad` — `Class.forName("io.papermc.paper.configuration.PaperConfigurations")`, `RegionizedServer`, `org.sqlite.JDBC` | Detect Paper / Folia / SQLite at runtime. |
| `RTPBukkitPlugin#onEnable` — `Class.forName(serverModel.accessorClassName/schedulerClassName)` | Pick the platform-version-specific `RTPServerAccessor`/`RTPScheduler`. |
| `BukkitServerProvider#resolveServerModel`, `AbstractServerAccessor` | Same purpose, factored for the lite assembly. |
| `BukkitRTPWorld#... World.class.getMethod("getChunkAtAsync", int, int)` | Detect availability of Paper's async chunk API at link time. |
| `FoliaOwnershipTestJob`, `TestAsyncChunkLoadCmd` | Diagnostic commands that probe Folia/Paper-only methods. |
| `effects-api/Effect.java` — `getMethod("valueOf"|"getByName"|"clone")` | Reflective enum/value adaptation for cross-version particle/potion identifiers. |
| `ScanTask` — `Class.forName("io.github.dailystruggle.rtp.anvil.AnvilPrefilterMetrics"|"AnvilRegionByteCache")` | The metrics lookup is platform-introspection; the `AnvilRegionByteCache` lookup is the legacy seam that ADR-026 will replace with the registry above. |

---

## How RTP responds when a target plugin is absent

| Hook | Target plugin missing → behavior |
|---|---|
| Region verifiers (claim plugins) | Each `*Checker` is gated on `Bukkit.getPluginManager().isPluginEnabled(...)`; verifier is simply not registered. |
| Economy | `RTP.economy` stays as the platform no-op; `/rtp` cost configuration is silently treated as "free". |
| Placeholders | `PlaceholderAPI` not present → `PAPI_expansion` is not constructed; placeholders are not exported. |
| World border | No bound provider → fall back to platform `World#getWorldBorder()` and config radius. |
| Anvil pre-filter | No bound provider → `ScanTask` falls back to per-attempt chunk loads (slower but correct). |
| Arrival platform creator | No bound creator (or one whose `prepare`/`createPlatform` declines) → `buildArrivalPlatform` falls back to the region-specific schematic path (if any) and ultimately to `RTPWorld#platform(RTPLocation)` (the emergency block disc). |
| PvP combat state (PvPManager / CombatLogX / Simple Combat Log) | Each adapter is gated on `Bukkit.getPluginManager().isPluginEnabled(...)`; `PvPIntegrations.setup` binds nothing when none is enabled, so `PvPGate` falls back to `NativePvPCombatTracker`. A reflective failure (API/version drift) disables that adapter for the session (logged once) and the player is treated as not-in-combat. |
| Fabric permissions (`fabric-permissions-api`) | No implementer registered → `Permissions.check(player, node, 2)` returns the vanilla op-level verdict (op level ≥ 2 grants). On `LinkageError` (perms-api jar genuinely absent at runtime) `FabricRTPPlayer#hasPermission` falls back to `PlayerList#isOp(GameProfile)`, preserving the previous op-only behaviour. |
| NeoForge permissions (LuckPerms) | LuckPerms absent (reflective probe returns a `null` verdict) → `NeoForgeRTPPlayer#hasPermission` falls back to the `plugin.yml` default table (`NeoForgeDefaultPermissions`) and then the on-disk `ops.json` op-level scan, preserving baseline `rtp.see` / `rtp.use` grants and op-only behaviour. |

In every case, RTP shall not silently swallow a failure (REQ-RTP-S-004); fall-back paths log a single line at INFO/WARNING and continue.

---

## Adding a new hook (checklist)

1. Define the SPI as a functional interface in `rtp-api/.../hooks/`.
2. Add a registry interface (`bind`/`current`/`clear` for single-binding, `register`/`unregister` for multi-binding) and an accessor on `RTPHooks`.
3. Implement it in `DefaultRTPHooks` (`rtp-core/.../common/hooks/`); keep the impl small and thread-safe.
4. Wire any consumer sites in `rtp-core` to read from the registry, with a clear fallback when no provider is bound.
5. Add a row in this file (catalog or "not yet routed" if the seam predates the facade).
6. Add a test under `rtp-core/.../common/hooks/` covering register, unregister, throwing-implementation safety, and the absent-target fallback. Tag the class name with the appropriate `REQ-*` ID and add a row to `TRACEABILITY.md`.
