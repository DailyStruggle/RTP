out b# Multi-Platform Support Roadmap

This document outlines the plan for RTP's multi-platform expansion. Fabric is **in scope** as of 2026-04-30 (see [rtp-fabric-ADR-002](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md)). Forge and other mod loaders remain out of scope.

For the supersession history: legacy Minecraft and Java versions are out of scope per [ADR-021](../adr/ADR-021-legacy-mc-and-java-support-scope.md). Do not backport Fabric-stage work to legacy servers without first superseding ADR-021.

## Out of Scope

- Legacy Minecraft versions (older than the shipped `v*_R*` adapter submodules) and legacy Java runtimes (older than Java 21). See [ADR-021](../adr/ADR-021-legacy-mc-and-java-support-scope.md).
- Forge, NeoForge, and other non-Fabric mod loaders. Re-evaluation deferred until Fabric is stable (Phase 4 below).

## Remaining Work Checklist *(updated 2026-05-01)*

Quick scan of what's done and what's left across Phases 0–3. Each Phase 2 step links to its detailed status block below. Tick boxes are authoritative — sub-bullets are scope reminders.

### ✅ Done

- [x] **Phase 0** — scope unlock (ADR-022, REQUIREMENTS.md §0, AGENTS.md, INDEX.md).
- [x] **Phase 1** — module skeleton, single-JAR multi-loader bootstrap, Loom 1.11-SNAPSHOT integration, shadowJar bloat fix (102 MB → 3.87 MB).
- [x] **Step A** — `FabricRTPWorld.getChunkAt` async via `MinecraftServer#submit` (S-005).
- [x] **Step B** — `FabricServerAccessor.getLocationGenerator()` real (S-006 fail-loud).
- [x] **Step C** — `FabricScheduler` full 12-method impl (async/sync/tick-driven cancellation).
- [x] **Step D** — `FabricDatabaseHandler.setupDatabase(rtp)` mirrors `BukkitDatabaseHandler`.
- [x] **Step E2** — `FabricEventBridge` + `FabricRTPPlayer` + real `RTPFabricMod.onInitialize()` body + accessor map population.
- [x] **Step G (structural)** — `BrigadierCommandAdapter` + `BrigadierBridgeContext` in `commands-api`; `RTPCmdFabric` shim landed.
- [x] **Step G G1 (wiring)** — `RTPCmdFabricRoot` (bare `/rtp`, no params/subcommands), `FabricServerAccessor.getSender`/`sendMessage` minimal, `FabricConsoleSender` inner sender, `RTPFabricMod.onInitialize()` registers via `CommandRegistrationCallback.EVENT` with permissive predicate (Step F deferred).
- [x] `rtp-fabric/REQUIREMENTS.md` authored.
- [x] **Metrics axis — Fabric parity complete** *(2026-05-17, `CHECKLIST-metrics-and-multiserver.md` C2)* — `FabricMetricsBinding` (`rtp-fabric-common/.../fabric/metrics/`) implements `MetricsBinding`, installed by `RTPFabricMod` via `CoreMetrics.setBinding(...)`, sampler driven by `FabricEventBridge` server-tick callback. Covered by `FabricMetricsBindingTest` and enumerated in `MetricsConsolidationArchTest` as a recognized binding-boundary entry point. Open follow-up `C6` (cross-platform metrics consolidation) is not Fabric-specific.

### 🚧 In Progress / Next Up

- [ ] **Step E3 — Scheduled-task processor parity *(NEW, 2026-05-01)*** — `/rtp` is currently non-functional on Fabric because the recurring tasks that pump `rtp-core`'s teleport pipeline are never started. Bukkit `onEnable` wires them; `RTPFabricMod.onInitialize()` does not. See *Step E3* detail block below for the full comparison and gap list. Critical sub-items:
    - [x] **`RTP.scheduler = accessor.getScheduler()` in `RTPFabricMod.onInitialize()` BEFORE `RTP.getInstance()`** *(landed 2026-05-01)* — `FabricScheduler.scheduleTimer` queues into a tick-drained map and is safe to call before SERVER_STARTED binds the `MinecraftServer`; the `RTP()` constructor's `runTaskTimer*` calls now succeed. Build green (`:rtp-fabric:rtp-fabric-common:compileJava :rtp-plugin:compileJava`).
    - [x] **Configuration & Database setup wired** *(landed 2026-05-01)* — `FabricDatabaseHandler.setupDatabase(rtp)` invoked from `RTPFabricMod.onInitialize()` immediately after `RTP.getInstance()`, mirroring `RTPBukkitPlugin.onEnable` ordering. `FabricServerAccessor.getPluginDirectory()` mkdirs `<fabric-config>/rtp/` so both `Configs` ctor and `setupDatabase` find it on first run. `FileSystemException` is caught + logged at SEVERE so a DB failure doesn't abort mod init (RTP runs without persistence rather than failing to load). Build green.
    - [ ] `DatabaseProcessing.start(...)` Fabric equivalent — `rtp-core`'s `RTP` constructor already schedules `flushDirtyCache` (every 6000t) and `AbstractSQLDatabaseAccessor.flush` (every 60t) via `RTP.scheduler`, so once the scheduler item above lands these run automatically. Verify under Step H smoke test; only a separate `FabricDatabaseProcessing` shim is needed if a Fabric-specific cadence proves necessary.
    - [ ] `ChunkUnloadProcessor` timer — Bukkit schedules `RTP.scheduler.runTaskTimer(new ChunkUnloadProcessor(), 1, 1)` for non-Folia. `ChunkUnloadProcessor` is platform-agnostic (`rtp-core`); just needs a `RTP.scheduler.runTaskTimer(new ChunkUnloadProcessor(), 1, 1)` call from `RTPFabricMod.onInitialize()` after the scheduler is wired.
    - [ ] `JarUtils.extractDocs(...)` Fabric equivalent — Bukkit seeds the data-folder with `docs/` from the jar at startup. Fabric needs the same against `FabricLoader.getInstance().getConfigDir().resolve("rtp")`. New helper `FabricJarUtils.extractDocs(File, String)` mirroring the Bukkit version (no `JavaPlugin` dep).
    - [ ] **Login Reserve Cache (ADR-023)** — Bukkit calls `initLoginReserveCache()` reading `Bukkit.getMaxPlayers()` + `Bukkit.getOnlinePlayers().size()`. Fabric equivalent uses `MinecraftServer#getMaxPlayers()` + `MinecraftServer#getPlayerList().getPlayerCount()`. Default-world resolution: `MinecraftServer#overworld()` → dimension key → `RTP.serverAccessor.getRTPWorld(...)`.
    - [ ] **`startupTasks` drain** — Bukkit drains `RTP.getInstance().startupTasks` synchronously, then again one tick later, then a third time. Same drain order needed in `RTPFabricMod.onInitialize()` post-event-bridge-registration.
    - [ ] **Acceptance:** `/rtp` actually teleports a player on Fabric (verified at Step H smoke test).
- [ ] **Step E-tail** — defer-able items from E2:
    - [ ] Teleport-cancel callback (requires Mixin against `Entity#teleportTo`).
    - [ ] Biome / material listing on `FabricServerAccessor` (`setBiomeGetter`, `getMaterials`, etc.).
    - [ ] World-border + shape function plumbing on `FabricServerAccessor`.
    - [ ] `getConsolePlayer()` real implementation (currently stub-throws).
    - [ ] Message routing helpers (`announce`, formatted `log`) wired to Fabric console + player chat.
- [x] **Step E-perf — Anvil pre-filter parity (ADR-016)** *(landed 2026-05-06, [rtp-fabric-ADR-005](../../rtp-fabric/docs/adr/rtp-fabric-ADR-005-anvil-prefilter-parity.md))* — without this override every `ScanTask` refill candidate fell through `RTPWorld#probeChunkColumn`'s default `null` (UNKNOWN) into `runFullLoadPath` → `FabricRTPWorld.getChunkAt` → synchronous `cache.getChunk(..., FULL, /*load=*/true)` on the server tick. Operators reported `/rtp` cost rising from ~1 ms (Bukkit baseline) to ~14 ms on Fabric under steady-state queue refill — exactly that tick-thread chunk-generation cost surfacing as command latency. Resolution:
    - [x] `api project(':rtp-anvil')` added to `:rtp-fabric:rtp-fabric-common`.
    - [x] `FabricRTPWorld#probeChunkColumn` overrides the default — gates on `SafetyKeys.anvilPrefilterEnabled` + `isChunkLoaded`, resolves world folder via `MinecraftServer#getWorldPath(LevelResource.ROOT)` and dimension subpath via `ServerLevel#dimension().location()` (`overworld → ""`, `the_nether → "DIM-1"`, `the_end → "DIM1"`, custom → `"dimensions/<ns>/<path>"`). Dispatches onto `AnvilIoPool.get()`; all failures resolve to UNKNOWN so the live-path fallback remains authoritative (ADR-016).
    - [x] `FabricAnvilColumnProbeAdapter` added (mirrors `rtp-bukkit-common`'s `AnvilColumnProbeAdapter`; uses platform-neutral `PaletteIdentifierNormalizer` instead of Spigot's `Material`-aware `PaletteNormalizer`).
    - [x] **Follow-up:** anvil-backed `FabricRTPChunk` for the safety stage (parity with `BukkitRTPChunk`'s anvil mode). *(landed 2026-05-06, [rtp-fabric-ADR-005 Addendum 2026-05-06](../../rtp-fabric/docs/adr/rtp-fabric-ADR-005-anvil-prefilter-parity.md#addendum-2026-05-06--full-anvil-backed-fabricrtpchunk-deferral-lifted))* — `FabricRTPChunk` is now dual-mode (live `ChunkAccess` or anvil `AnvilChunkView`); `FabricRTPWorld.getChunkAt` wires `AnvilProbeSupport.probeAndPublish` so accepted prefilter candidates evaluate entirely off-tick against the decoded view, with `getCachedChunk` falling through to a fresh anvil-backed `FabricRTPChunk` when the live caches miss. New `FabricPaletteNormalizer` + `ReqRtpS005FabricAnvilChunkTest` + `FabricPaletteNormalizerTest` cover the regression.
    - [ ] **Follow-up:** hoist `*AnvilColumnProbeAdapter` into `rtp-anvil` (requires moving `PaletteNormalizer`'s Material coupling out first).
- [ ] **Step F - Permissions** *(5/5 sub-items landed 2026-05-22. `getEffectivePermissions()` landed via ADR-011. `/rtp` is gated on real perms via the perms-api fallback chain and the Brigadier bridge predicate.)*:
    - [x] Add `me.lucko:fabric-permissions-api` as `modCompileOnly` *(landed pre-2026-05-22)* - wired in all six fabric Gradle modules: `rtp-fabric-common/build.gradle:52`, `rtp-fabric-common-unobf/build.gradle:46` (`compileOnly`), `rtp-fabric-v1_20_R1/build.gradle:34`, `rtp-fabric-v1_21_R1/build.gradle:35`, `rtp-fabric-v1_21_R5/build.gradle:32`, `rtp-fabric-v1_21_R11/build.gradle:45`, `rtp-fabric-v26_1_R1/build.gradle:50` (`compileOnly`).
    - [x] `FabricRTPPlayer.hasPermission(node)` via perms-api *(landed pre-2026-05-22)* - all three platform variants (`FabricRTPPlayer.java:93-216`, `FabricRTPPlayerUnobf.java:99-233`, `V26_1_R1FabricRTPPlayer.java:82-137`) implement a three-tier fallback chain: (1) `Permissions.getPermissionValue(uuid, node).getNow(TriState.DEFAULT)` (LuckPerms-Fabric / Cyan / Ledger), (2) `FabricDefaultPermissions.resolve(node)` mirroring `plugin.yml`'s default table (`rtp.see=true`, `rtp.use=true`, `rtp.onevent.*=false`, ...), (3) on-disk `ops.json` UUID scan via `PlayerList.getOps().getFile()`. Direct `ServerPlayer#hasPermissions(int)` and `PlayerList#isOp(GameProfile)` are deliberately avoided because their intermediary mappings (`method_5687`, `method_14569`) drifted on MC 1.21.11 and trigger `NoSuchMethodError` / `ClassCastException` under Loom remapping.
    - [x] `getEffectivePermissions()` real impl *(landed 2026-05-22 via [rtp-fabric-ADR-011](../../rtp-fabric/docs/adr/rtp-fabric-ADR-011-effective-permissions-enumeration.md) Accepted)* - was `Collections.emptySet()` in all three variants - `FabricRTPPlayer.java:218`, `FabricRTPPlayerUnobf.java:235`, `V26_1_R1FabricRTPPlayer.java:139`, and the console sender at `FabricServerAccessor.java:1968`). **Blocker:** `fabric-permissions-api` is a check-only interface; it exposes no enumeration analogous to Bukkit's `Player#getEffectivePermissions()`. Impact: `EffectFactory.buildEffects(prefix, player.getEffectivePermissions())` consumes this set, so the empty stub means **zero effects fire on Fabric** (documented in `FabricEffectsHandlerUnobf.java:43`, [effects-api-ADR-005](../../effects-api/docs/adr/effects-api-ADR-005-effects-yml-config-and-translations.md) §"empty getEffectivePermissions()", `FabricEffectsHandler.java:45`). `ParsePermissions.java:53/88` also consumes it for permission-tree resolution. A subproject ADR (candidate paths: enumerate from the `FabricDefaultPermissions` known-node table unioned with op-implies-all; or have `FabricEffectsHandler` probe each registered effect node via repeated `hasPermission` calls; or defer until LuckPerms-Fabric exposes an enumeration surface) - **resolved and implemented 2026-05-22** in [rtp-fabric-ADR-011](../../rtp-fabric/docs/adr/rtp-fabric-ADR-011-effective-permissions-enumeration.md) (Accepted; revised same day after consumer audit widened scope to `rtp.onevent.*` and numeric tails `rtp.delay.<n>` / `rtp.cooldown.<n>`). Adopts Option E: LuckPerms-Fabric primary path (the only enumerable Fabric perms foundation) with closed-namespace registry probe (`EffectFactory.registeredNames()` + 6-event `rtp.onevent.*` list) as fallback. Implementation landed 2026-05-22: new `FabricEffectivePermissionsResolver` + `LuckPermsFabricEnumerator` + `FabricOnEventPermissions` in `rtp-fabric-common`; wired in all three player variants and the console sender; covered by `FabricEffectivePermissionsTest` (22 tests).
    - [x] Replace `BrigadierBridgeContext` permissive predicate (always-true) with real perms-api lookup *(landed pre-2026-05-22)* - `FabricBrigadierSourceBridge.checkPermission` (`rtp-fabric-common/.../tools/FabricBrigadierSourceBridge.java:159-174`) resolves the source UUID, treats the `RTPAPI.serverId` sentinel and console as fully privileged (matching Bukkit `ConsoleCommandSender.hasPermission()`), and otherwise routes through `RTP.serverAccessor.getSender(uuid).hasPermission(permission)` - which on Fabric dispatches into `FabricRTPPlayer.hasPermission`'s three-tier perms-api / `FabricDefaultPermissions` / `ops.json` chain. `RTPFabricMod.onInitialize()` (L495) wires this as the `BrigadierBridgeContext` permission predicate (commented `// Permission gating: defer to RTP.serverAccessor.getSender(uuid).hasPermission(perm)` at L482-494). The "always-true" placeholder noted in the `RTPCmdFabricRoot` Javadoc and at `RTPFabricMod.java:454` referred to the early G1 wiring and is stale.
    - [x] Permission node parity test vs. Bukkit adapter *(2026-05-22)* - `FabricDefaultPermissionsParityTest` (`rtp-fabric-common/src/test/.../player/`) pins `FabricDefaultPermissions.resolve()` against the `plugin.yml` declared defaults for the full canonical node set (~26 nodes covering `rtp.see`/`rtp.use` default-true, the `rtp.onevent.*` default-false family, and the default-op majority). Scoped to the middle tier of the three-tier chain because that is where regression risk concentrates - perms-api and `ops.json` are stable surfaces and would require a live `MinecraftServer` to exercise (out of scope for a unit test). Additionally pins Fabric-side tightenings of `rtp.delay` / `rtp.cooldown` (placeholder base-keys, denied so numeric-suffix overrides drive value) and `rtp.personalqueue` (ADR-043 opt-in, denied so op does not implicitly carry the bucket lifecycle). Traces candidate `REQ-RTP-F-???` (permission semantics; row to be added under `REQUIREMENTS.md §F`).
- [ ] **Step G2 — Brigadier wiring (full Bukkit parity)** *(G1 minimal landed 2026-05-01; parameter + subcommand parity landed 2026-05-06)*:
    - [x] Port `RTPCmdBukkit`'s 5 parameters (`region`/`biome`/`player`/`world`/`toggletargetperms`) to Fabric, routing world/player lookups via `RTP.serverAccessor.*` (no `Bukkit.*`). *(landed 2026-05-06 — `RTPCmdFabricRoot` ctor; `Locale.ROOT` biome casing and `rtp.notme` opt-out preserved; `player` parameter uses an inline `CommandParameter` subclass with empty `values()` until an online-player listing helper is added to `FabricServerAccessor` under Step E-tail.)*
    - [x] Port 5 of 6 subcommands (`reload`/`help`/`config`/`scan`/`info`). *(landed 2026-05-06.)* `test` deferred — `TestCmd` lives in `rtp-plugin/.../bukkit/commands/test/` and is Bukkit-only; a platform-neutral lift is a separate Step G2 follow-up.
    - [ ] `TestCmd` — port or lift to platform-neutral.
    - [ ] `successEvent`/`failEvent` hooks on `RTPCmdFabricRoot` (currently no-op — Bukkit fires `TeleportCommandSuccessEvent`).
    - [ ] End-to-end `commands-live` smoke under `rtp test full` on Fabric.
    - [x] Tab-completion smoke test. *(2026-05-06: Brigadier-tree audit
      landed — `BrigadierCommandAdapter.attachChildren` now recurses through
      `CommandParameter.subParams` and chains sibling parameters with a
      cycle guard; `CommandParameter.isSuggestionRelevant` decoupled from
      `isRelevant` so non-op Fabric players get a populated suggestion
      list pre-`fabric-permissions-api`; `FabricServerAccessor.getOnlinePlayerNames`
      backs the `player` parameter's `values()`. See
      [commands-api-ADR-001 §Addendum 2026-05-06](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md#addendum--2026-05-06-recursion-contract-sibling-chaining-and-suggestion-relevance-split)
      and `BrigadierTreeShapeTest`. End-to-end live tab-completion in a
      running Fabric server is still gated on a fresh build of
      `rtp-fabric-common` + the platform's `commands-api` jar.)*
- [ ] **Step H — Stabilization & Dual-Runtime Smoke Test**:
    - [ ] Memory-leak audit — chunk tickets + `MemoryTracker` register/release on all Fabric exit paths.
    - [ ] Concurrency review — no Folia-isms in Fabric code paths.
    - [ ] **Dual-runtime smoke test** — one JAR loads on Paper *and* Fabric, `/rtp` works end-to-end on both. *Phase 2 acceptance gate.*
    - [x] ArchUnit guard for disjoint `bukkit/` + `fabric/` packages in `rtp-plugin` *(2026-05-22)* — `PluginPlatformPackageBoundaryArchTest` (4 rules: bukkit↛fabric, fabric↛bukkit, bukkit↛`net.minecraft`, fabric↛`org.bukkit`). Scoped to `rtp-plugin/build/classes` so cross-module Fabric types under `rtp-fabric-common`'s `io.github.dailystruggle.rtp.fabric.*` are out of scope. Narrow FQN-keyed exception for the legacy platform-neutral `bukkit.commands.test.TestCmd` that `RTPFabricMod` constructs (documented in-file ~L460-469); relocation tracked as Step G2 follow-up.
    - [ ] `TRACEABILITY.md` rows for `REQ-RTP-S-005` (Fabric), `REQ-RTP-S-006` (Fabric), Step F perm test, Step G `commands-live`.
- [ ] **Step I — Menu Framework Parity *(NEW, 2026-05-22)*** — the menu rollout under [ADR-035](../adr/ADR-035-interactive-menus-book-first.md) and [ADR-044](../adr/ADR-044-command-tree-menu-reflector.md) shipped its `rtp-api` model and `rtp-core` reflectors (`CommandTreeMenuBuilder`, `LocalMenuTokenRegistry`, `MenuRedeemSubcommand`, `FrontPageBuilder`, `AdminPanelBuilder`, config-view / param-picker / shape-vert / list-editor builders) as platform-agnostic, but the renderer layer is Paper-only (`rtp-paper-common/.../menu/BookMenuRenderer.java`). Fabric has no renderer, no `MenuRendererRegistry` install, and `RTPCmdFabricRoot` does not wire the token registry / `menuPermissionProbe` / `MenuRedeemSubcommand`. Until this lands, `/rtp menu`, the curated front page (Stage B), `/rtp config <file>` book view, the param-picker, the shape/vert two-step picker, and the config staging cart are no-ops on Fabric. Sub-items:
    - [x] **Renderer decision ADR** *(drafted 2026-05-22 as [rtp-fabric-ADR-012](../../rtp-fabric/docs/adr/rtp-fabric-ADR-012-menu-renderer-parity.md) Proposed; chose chat-first via the obf/unobf carrier split from [rtp-fabric-ADR-009](../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md), book renderer deferred, `PromptAnvilInput` substituted by a chat-prompt callback). Implementation pending ADR acceptance.*
    - [ ] `FabricMenuRenderer` impl in `rtp-fabric-common` against the ADR decision.
    - [ ] `MenuRendererRegistry` install in `RTPFabricMod.onInitialize()` (mirroring the Paper install site).
    - [ ] Wire menu in `RTPCmdFabricRoot` — construct a `LocalMenuTokenRegistry`, install a `menuPermissionProbe` (gated on Step F so non-op players see permission-filtered rows once `fabric-permissions-api` lands), attach `MenuRedeemSubcommand`, mirror `RTPCmdBukkit` lines 213, 239, 246, 259, 303.
    - [ ] `AdminPanelBuilder` wired into `RTPCmdFabricRoot` (no `/rtp adminpanel` equivalent today on Fabric).
    - [ ] Renderer integration test parity — `MenuStageTwoTest`, `MenuNavigationStageATest`, `MenuConfigSubtreeBuildersTest`, `MenuShapeVertExpansionTest`, `MenuParamPickerStageA2Test` already cover the platform-agnostic side; add a Fabric renderer integration test analogous to `BookMenuRendererTest`.
- [ ] **Step J — Network Mode Backend Parity *(NEW, 2026-05-22)*** — `MULTI_SERVER_PLAN.md` Phase 1 SPI lives in `rtp-proxy-common` (platform-agnostic, reachable from Fabric), but the Phase 2 backend integration is wired in Bukkit-only classes: `rtp-plugin/.../bukkit/network/NetworkModeBootstrap.java` (installs `NetworkStateBinding`, transports (`InMemoryNetworkStateBinding` / `RedisNetworkStateBinding` / `SqlNetworkStateBinding`), publisher, `ReservationTokenReaper`, `JoinTriggerSource`) and `rtp-plugin/.../bukkit/network/JoinTriggerSource.java` (`implements org.bukkit.event.Listener`, hooks `PlayerJoinEvent` to drain cross-network reservation tokens against the backend pool). A Velocity-routed player arriving on a Fabric backend with a reservation token has no listener to redeem it. Gated on Step E3 (must teleport locally first) and Step F (reservation-token authorization leans on `hasPermission`). Sub-items:
    - [x] [rtp-fabric-ADR-013](../../rtp-fabric/docs/adr/rtp-fabric-ADR-013-network-mode-bootstrap-parity.md) *(drafted 2026-05-22 Proposed; chose reimplementation as `FabricNetworkModeBootstrap` + `FabricJoinTriggerSource` over base-class refactor, with pull-up of pure-I/O `readLobbyModeEarly` / `ensureNetworkYml` to a shared utility in `rtp-core`; reservation-token authorization reuses [rtp-fabric-ADR-011](../../rtp-fabric/docs/adr/rtp-fabric-ADR-011-effective-permissions-enumeration.md)'s resolver). Implementation pending ADR acceptance.*
    - [ ] `FabricNetworkModeBootstrap` mirroring the Bukkit class — transport selection, publisher install, `ReservationTokenReaper` install.
    - [ ] `FabricJoinTriggerSource` via `ServerPlayConnectionEvents.JOIN` (or extending `FabricEventBridge`) — redeems reservation tokens against `keptLocations` / `unkeptLocations`.
    - [ ] `RtpTriggerSource` install on Fabric — the backend-side trigger marker that lets a proxy say "this backend speaks RTP network mode" is registered through `NetworkModeBootstrap` only today.
    - [ ] Shutdown drain on `ServerLifecycleEvents.SERVER_STOPPING` (parallel to the Bukkit `onDisable` drain path).
    - [ ] `network.yml` extraction on Fabric — depends on `FabricJarUtils.extractDocs` from Step E3; without it the operator never gets a seed config.
    - [ ] Fabric backend lane in `rtp-proxy/devstack/` (e.g. `backend-fabric-a` / `backend-fabric-b` compose services) + an acceptance round-trip in the 2-proxy + 2-backend smoke matrix.
- [ ] **Step K — Maps API Parity (beta.5 gate) *(NEW, 2026-05-22)*** — `maps-api` (`MapBinding`, `MapBindingLifecycle`, `MapHandle`, `MapCanvas`, `MapAllocationRequest`, models `Heatmap2D` / `ChartModel` / `MermaidChart` / `RegionCoverage` / `TimeSeries` / `CategoryDistribution`, renderers `HeatmapRenderer` / `ChartRenderer`) is platform-neutral and has `BukkitMapBinding` + `FoliaMapBinding` + `NoopMapBinding` impls; there is no `FabricMapBinding` and no `MapDispatch.setMapBinding(...)` call in `RTPFabricMod`. The consumer surface is unused until beta.5 — this is scheduled work, **not currently blocking** the Phase 2 acceptance gate or the first public Fabric beta release. Required to ship beta.5 with feature parity vs. Bukkit. Sub-items:
    - [ ] `rtp-fabric-ADR-014-maps-binding-parity.md` (subproject ADR) — choose between a vanilla filled-map item path (`MapItemSavedData` / `MapId` + custom palette + per-tick `MapItem.update` canvas writes), a chat-rendered ASCII heatmap fallback, and a hybrid. Cross-reference `REQ-RTP-MAP-001` / `REQ-RTP-MAP-002` (S-005 no-chunk-I/O guard) and `CHECKLIST-maps-api.md`.
    - [ ] `FabricMapBinding` impl in `rtp-fabric-common` against the `MapBinding` SPI.
    - [ ] `MapBindingLifecycle` viewer-release hook on `ServerPlayConnectionEvents.DISCONNECT` (parallel to `rtp-plugin/.../bukkit/bukkitListeners/OnPlayerQuit.java` line 40 release path).
    - [ ] Install in `RTPFabricMod` mirroring `RTPBukkitPlugin` lines 216–233 (try `FabricMapBinding`; fall through to `NoopMapBinding` on failure — `mapBindingMissing` message already localized in all shipped locales).
    - [ ] S-005 re-verification: extend `ReqRtpMap001RequireByContractTest` + `ReqRtpMap002NoChunkIoTest` (or add Fabric-flavored siblings) to exercise the Fabric binding once it lands.
    - [ ] Audit `MapDispatch` (`rtp-core/.../commands/maps/`) for any Bukkit-only branch before beta.5 — the SPI itself is clean and the only call site today is Bukkit, so the dispatcher's internal selector is dormant but unaudited for Fabric.
    - [ ] If the beta.5 consumer surface is invoked from menu rows (admin-panel heatmap viewer etc.) rather than `/rtp info` only, Step K becomes co-gated with Step I.
- [ ] **Phase 3 — Documentation & Release**:
    - [ ] `docs/admin/` Fabric install/config notes.
    - [ ] `docs/dev/` multi-platform architecture + Fabric contribution guide.
    - [ ] `CHANGELOG.md` — one entry per phase under *Unreleased*.
    - [ ] `COVERAGE_PLAN.md` — add Fabric column.
    - [ ] `LESSONS_LEARNED.md` — Loom 1.11 + JDK 21 daemon requirement; Loom-vs-Shadow bloat fix.
    - [ ] First public Fabric beta release (gated on Step H green).

### ⏸ Deferred to Phase 4

- [ ] Forge / NeoForge evaluation.
- [ ] Architectury re-evaluation for multi-loader maintenance.

---

## Phase 0: Scope Unlock — COMPLETED 2026-04-30

- [x] **ADR-022 accepted** — Fabric promoted from "experimental frontier" to a first-class supported platform.
- [x] **`REQUIREMENTS.md §0`** updated to add Fabric to *In Scope* and remove it from the *Non-Bukkit platforms* exclusion.
- [x] **`REQ-RTP-SYS-002`** updated to include Fabric.
- [x] **`AGENTS.md` *Current Development Focus*** — promoted from "out of scope per §0" wording to first-class platform; ADR-022 linked; `rtp-fabric` added to safe-to-modify modules; "do not backport" guardrail preserved.
- [x] **`docs/dev/INDEX.md`** — added ADR-022 ("Why Fabric is in scope") and ADR-021 ("Why legacy MC / Java are out of scope") rows to the task router.

## Phase 1: Infrastructure & Build System

The foundation for the `rtp-fabric` module.

- [x] **Consolidate APIs**: `CommandsAPI` and `EffectsAPI` pulled in as sub-modules.
- [x] **Refactor Dependencies**: `rtp-core` and `rtp-plugin` use local project dependencies for APIs.
- [x] **April 2026 gap analysis**: confirmed `rtp-api` and `rtp-core` abstractions are sufficient for Fabric — no new interfaces needed (see *What Does NOT Need to Change* below).
- [x] **Bootstrap `rtp-fabric/` module tree** *(skeleton landed 2026-04-30; plain `java-library`, no Loom yet)* — layout (Fabric-platform glue lives here; the entry-point class lives in `rtp-plugin`, see *Single-JAR Multi-Loader Bootstrap* below):

      rtp-fabric/
      └── rtp-fabric-common/                 # version-agnostic Fabric adapter (library, not entry point)
          └── src/main/java/io/github/dailystruggle/rtp/fabric/
              ├── server/FabricServerAccessor.java        # extends AbstractServerAccessor
              ├── world/FabricRTPWorld.java
              ├── world/FabricRTPChunk.java
              ├── player/FabricRTPPlayer.java
              ├── scheduler/FabricScheduler.java
              ├── database/FabricDatabaseHandler.java     # delegates to rtp-core
              ├── permissions/FabricPermissionResolver.java
              ├── events/FabricEventBridge.java
              └── commands/RTPCmdFabric.java              # registers commands-api Brigadier adapter

  Decisions: one common module first; defer `rtp-fabric-v<MC>/` shim until a real version-specific need appears (Yarn-mapped Fabric rarely needs NMS-style version splits). No Bukkit imports under `rtp-fabric/**`. The `ModInitializer` entry point (`RTPFabricMod`) lives in `rtp-plugin` per ADR-022's single-JAR multi-loader packaging — not in `rtp-fabric-common` — so both `plugin.yml` and `fabric.mod.json` ship from one bootstrap module.

- [x] **Single-JAR Multi-Loader Bootstrap in `rtp-plugin`** *(landed 2026-04-30; `RTPFabricMod implements ModInitializer`, `fabric.mod.json` declares the entrypoint, Loom 1.11-SNAPSHOT applied to `:rtp-fabric:rtp-fabric-common` and `:rtp-plugin`; `:rtp-plugin:shadowJar` green. Dual-runtime smoke test on a Paper + Fabric dev server is the next debug-phase task — runtime verification is intentionally separated from structural landing per the user's "structure first, debug after" directive)* (per [rtp-fabric-ADR-002](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md)):

      rtp-plugin/
      └── src/main/
          ├── java/io/github/dailystruggle/rtp/
          │   ├── bukkit/RTPBukkitPlugin.java     # extends JavaPlugin (existing entry, renamed)
          │   └── fabric/RTPFabricMod.java        # implements ModInitializer (new)
          └── resources/
              ├── plugin.yml                       # main: ...rtp.bukkit.RTPBukkitPlugin
              └── fabric.mod.json                  # entrypoints.main: [...rtp.fabric.RTPFabricMod]

  Both entry-point classes shall remain disjoint: neither imports the other, neither transitively reaches the other platform's classes. Shared code lives only in `rtp-core` / `rtp-api` / `commands-api` / `effects-api`. An ArchUnit rule shall enforce the disjoint-package invariant. `RTPFabricMod` consumes `:rtp-fabric:rtp-fabric-common` as a project dependency and contains no business logic — it dispatches to the Fabric adapter the same way `RTPBukkitPlugin` dispatches to `rtp-bukkit` / `rtp-paper` / `rtp-folia`.

- [x] **Loom integration** *(landed 2026-04-30 with `fabric-loom 1.11-SNAPSHOT` — `1.7` rejected by Gradle 9.4 with `NoSuchMethodError` on the Problems API; `1.11` is the current stable line that supports Gradle 9. Pin documented in `:rtp-fabric:rtp-fabric-common/build.gradle` and `:rtp-plugin/build.gradle`. `pluginManagement` block in `settings.gradle` registers FabricMC's Maven so the plugin resolves)*:
  1. Pin `fabric-loom 1.11-SNAPSHOT` (Java 21 + Gradle 9.4 compatible).
  2. **Apply Loom in `rtp-fabric/**/build.gradle` AND in `rtp-plugin/build.gradle`** — never at the root, never in `rtp-core`, `rtp-api`, `commands-api`, `effects-api`, or any Bukkit-family adapter (`rtp-bukkit`, `rtp-paper`, `rtp-folia`). Applying Loom outside this set is the most likely cause of the historical "unresolved Loom dependency" symptom because it leaks remap caches and Maven repos into Bukkit-family modules.
  3. **Remap scoping** — Loom's `remapJar` task in `rtp-plugin` shall include only `io/github/dailystruggle/rtp/fabric/**` and the `rtp-fabric/rtp-fabric-common` classpath contribution. Bukkit-family classes shall be excluded so they retain Spigot/Paper-mapped bytecode in the final shaded JAR.
  4. Add Fabric Maven repos in a `subprojects` block guarded by `if (project.path.startsWith(':rtp-fabric') || project.path == ':rtp-plugin')`.
  5. `settings.gradle` includes `:rtp-fabric:rtp-fabric-common`. Do not include version submodules until they exist.
  6. Mappings: `loom.officialMojangMappings()` (revisit if community prefers Yarn).
  7. Dependencies: `fabric-loader`, `fabric-api`, `fabric-permissions-api` (`modCompileOnly`), and project deps `:rtp-core`, `:rtp-api`, `:commands-api`, `:effects-api`. `rtp-plugin` additionally depends on `:rtp-fabric:rtp-fabric-common`.
  8. Jenkinsfile: add `:rtp-fabric:rtp-fabric-common:build` and `:rtp-plugin:remapJar` as non-blocking stages initially; promote to blocking once Phase 2 gates are green.

- **Phase 1 acceptance gates (structural only):**
  - `.\gradlew :rtp-fabric:rtp-fabric-common:assemble` green on a clean clone with no daemon-context surprises and no impact on Bukkit-family module builds.
  - `.\gradlew :rtp-plugin:shadowJar` (or the equivalent Loom-aware single-JAR task) produces **one JAR** containing both `plugin.yml` and `fabric.mod.json`, with Bukkit classes left un-remapped and Fabric classes remapped to intermediary mappings.
  - `fabric.mod.json` parses against Fabric Loader's schema (offline lint sufficient at this stage).

  **Note — runtime / dual-loader end-to-end smoke testing is intentionally NOT a Phase 1 gate.** Until Phase 2 Steps A–G land, `RTPFabricMod.onInitialize()` is a placeholder and there is no Fabric functionality to validate end-to-end. "Loads on Fabric" would be trivially true (and trivially uninformative) at this stage. The dual-runtime end-to-end smoke test has been moved to **Phase 2 Step H** where the featureset is sufficient to make it meaningful. Bukkit-side regression risk from Loom is covered by the existing Bukkit-family test suites (`:rtp-plugin:test`, etc.) — these must remain green at all phases.

### Phase 1 Amendment — Multiversion Submodule Layout *(2026-05-01, [rtp-fabric-ADR-001](../../rtp-fabric/docs/adr/rtp-fabric-ADR-001-multiversion-submodule-layout.md))*

The original Phase 1 deferred `rtp-fabric-v<MC>/` shims until a real version-specific need appeared (line 109 above). That need has now arrived ahead of any single trigger: cross-version mojmap drift between 1.20.x and 1.21.x (e.g. `ChunkStatus` package move at 1.21.3), and — more decisively — MC 26.1's deobfuscation, which mandates Loom 1.15+, Java 25, Gradle 9.4+, and a different build-script shape (no `mappings` line, plain `implementation`/`compileOnly` instead of `modImplementation`/`modCompileOnly`, plugin id `net.fabricmc.fabric-loom`). None of those can coexist with the 1.20/1.21 build script in a single common module.

rtp-fabric-ADR-001 supersedes lines 109 ("defer `rtp-fabric-v<MC>/` shim") and 129 ("Do not include version submodules until they exist") with the following layout:

| Module | MC | Mappings | Loom | Java | Fabric API |
|---|---|---|---|---|---|
| `:rtp-fabric:rtp-fabric-common` | (compileOnly) 1.21.1 mojmap | mojmap | 1.11+ | 21 | (compileOnly) 0.115.0+1.21.1 |
| `:rtp-fabric:rtp-fabric-v1_20_R1` | 1.20.1 | mojmap | 1.11+ | 21 | 0.92.x+1.20.1 |
| `:rtp-fabric:rtp-fabric-v1_21_R1` | 1.21.1 | mojmap | 1.11+ | 21 | 0.115.0+1.21.1 |
| `:rtp-fabric:rtp-fabric-v26_1_R1` | 26.1.2 | (deobfuscated) | 1.15+ | 25 | 0.143.5+26.1 |

`rtp-fabric-common` switches MC + fabric-api to `compileOnly` / `modCompileOnly` so it ships no MC classes; v-submodules each supply their own runtime jar. Common defines a small `FabricVersionAdapter` SPI carrying only the version-volatile call sites (registry access, `ChunkStatus` location, chunk-loading entrypoint normalisation, biome key lookup at `BlockPos`, permissions API surface). `RTPFabricMod` (`rtp-plugin`) reads `SharedConstants.getCurrentVersion().getName()` at server-start and reflectively instantiates the matching v-submodule's adapter — direct symbol reference is forbidden so a Java 21 server never resolves v26_1_R1 (Java 25) bytecode.

**Initial deliverable:** v1_21_R1 ships with the full adapter implementation (relocated from common); v1_20_R1 and v26_1_R1 ship with build-correct stubs throwing `UnsupportedOperationException` carrying the `// TODO(rtp-fabric-ADR-001)` marker. Real porting bodies for the latter two land as follow-up Phase 2.5 tasks driven by the smoke gates per MC line.

## Phase 2: Fabric Feature Parity (acceptance-gated A → H)

The goal of this phase is feature parity with the Bukkit/Paper/Folia adapters. Each step's acceptance gate must be green before the next step begins.

### Abstraction Gap Summary

The table tracks the current implementation status of each cross-platform abstraction. "Critical" gaps block the teleport pipeline from functioning at all; "High" gaps cause data loss or incorrect behaviour at runtime.

| Abstraction | Bukkit Status | Fabric Status | Gap Severity |
|---|---|---|---|
| `RTPServerAccessor` | Full (`AbstractServerAccessor`) | `FabricServerAccessor` now backed by `ConcurrentHashMap`s populated by `FabricEventBridge`: `getRTPWorld(name/id)`, `getRTPWorlds()`, `getPlayer(uuid/name)`, `getPluginDirectory`, `getServerVersion`, `getPluginVersion`, `getServerIntVersion`, `isPrimaryThread`, `getScheduler`, `getPlugin` all real (Step E2 landed 2026-05-01). `getConsolePlayer`, biome/material, world-border/shape, message routing still stub-throw pending Steps E-tail/F | Resolved (S-006 + lifecycle); message + perms routing pending |
| `RTPWorld` (async chunk load) | Full (`BukkitRTPWorld` / Paper override) | `FabricRTPWorld.getChunkAt` async via `MinecraftServer#submit` (Step A landed 2026-05-01); other `RTPWorld` methods stubbed pending Steps C/E | Resolved (S-005); other coverage pending |
| `RTPPlayer` | Full (`BukkitRTPPlayer`) | `FabricRTPPlayer` landed (Step E2): real `uuid`/`name`/`isOnline`/`getLocation`/`setLocation` (async via `server.submit`)/`sendMessage`/`performCommand`; `hasPermission` op-level fallback; `getEffectivePermissions()` empty pending Step F | High (perms only) |
| `RTPScheduler` | Full (`BukkitSchedulerImpl`) | `FabricScheduler` landed (Step C, 2026-05-01): async via `Util.backgroundExecutor()`, sync via `MinecraftServer#execute`, tick-driven `runTaskLater`/`runTaskTimer` with `ConcurrentHashMap` cancellation; region-aware overloads delegate (no Folia-style regions on Fabric) | Resolved (S-005 sync/async dispatch); lifecycle wiring pending Step E |
| Database / Persistence | Full (`DatabaseProcessing`) | `FabricDatabaseHandler.setupDatabase(rtp)` mirrors `BukkitDatabaseHandler` (Step D landed 2026-05-01); accessor selection delegates to `rtp-core` options (`SQLite`/`H2`/`MySQL`/`PostgreSQL`/`Yaml`); config dir via `FabricLoader.getInstance().getConfigDir().resolve("rtp")` | Resolved (handler factory); lifecycle wiring pending Step E |
| Event mapping | Full (Bukkit listeners) | `FabricEventBridge` registers SERVER_STARTED/STOPPING, END_SERVER_TICK, ServerWorldEvents.LOAD/UNLOAD, ServerPlayConnectionEvents.JOIN/DISCONNECT (Step E2 landed 2026-05-01); teleport-cancel callback / world-border events deferred | Resolved (lifecycle + session); teleport-cancel pending |
| Command system | Full Bukkit tree | Brigadier adapter landed; not yet wired to `CommandRegistrationCallback` | Medium |
| Permissions | Bukkit permissions API | Hardcoded op-check | Medium |

### Step A — S-005 Fix in `FabricRTPWorld.getChunkAt` *(safety-critical, must come first)*

Replace `getChunkFutureSyncOnMainThread` with a truly async dispatch. Mirror `rtp-paper-common`'s `getChunkAtAsync` override, using the server tick thread (`MinecraftServer#submit`) to safely touch `ServerChunkCache`, which is single-threaded on Fabric.

- **Status (landed 2026-05-01 — minimal slice, A1 scope):**
    - [x] `FabricRTPWorld extends RTPWorld<ServerLevel>` in `rtp-fabric/rtp-fabric-common/.../world/FabricRTPWorld.java`. `getChunkAt(int,int)` dispatches via `world.getServer().submit(() -> chunkSource.getChunk(cx, cz, ChunkStatus.FULL, true))`, returning a `CompletableFuture<Long>` that resolves on the server tick thread with the canonical packed chunk key. Null-server defensive path completes exceptionally (REQ-RTP-S-004 attribution).
    - [x] `name()` returns the dimension `ResourceLocation` string; `id()` is a deterministic UUID derived from the dimension id (Fabric has no per-world UUID). All other abstract `RTPWorld` methods (`getChunkAtAsync`, `setForceLoadedImpl`, `getServerForceLoadedCount`, `getCachedChunk`, `keepChunkAt`/`forgetChunkAt`/`forgetChunks`, `getBiome`, `platform`, `isInactive`, `save`, `getMaxHeight`, `getMinHeight`, `getCacheSize`, `getSeed`) throw `UnsupportedOperationException` with per-step routing notes — fail-loud per REQ-RTP-S-006.
    - [x] `:rtp-fabric:rtp-fabric-common:compileJava` BUILD SUCCESSFUL.
    - [ ] **Test deferred** — per the approved approach (option c), end-to-end exercise of the S-005 path lands in **Step H's dual-runtime smoke test**, which actually boots a Fabric server. Mocking `ServerLevel`/`ServerChunkCache` from a unit test would require bytecode tricks for negligible value, given Step H is the gate for genuine Fabric runtime verification anyway. `TRACEABILITY.md` row for REQ-RTP-S-005 (Fabric) will be added when the smoke test lands.
- **Acceptance gate:** Step H dual-runtime smoke test exercises the S-005 path end-to-end on a Fabric dev server; no `ServerLevel#getChunk` on any caller-side main-thread path (the implementation hops onto the tick thread internally via `server.submit`, which is the documented thread-safe entry).

### Step B — `FabricServerAccessor.getLocationGenerator()`

Return a fresh `LocationGenerator` (matching `AbstractServerAccessor`'s pattern — Bukkit constructs per-call, not via a singleton field). Throw `IllegalStateException` if called before `rtp-core` is loaded (REQ-RTP-S-006). Unblocks the teleport pipeline end-to-end.

- **Status (landed 2026-05-01 — minimal slice):**
    - [x] `FabricServerAccessor implements RTPServerAccessor` in `rtp-fabric/rtp-fabric-common/.../server/FabricServerAccessor.java`. `getLocationGenerator()` returns `new LocationGenerator()` after a `RTP.getInstance() != null` gate that throws `IllegalStateException` (REQ-RTP-S-006).
    - [x] `getPlatform()` returns `"fabric"`. `getTPS(int)` returns the nominal `20.0` until Step C wires real measurement (callers gating on TPS won't block the pipeline before Step C). `format`/`formatNoColor` pass-through; `log(...)` falls back to JUL until Step E. `stop()` is a documented no-op.
    - [x] All other ~45 abstract methods throw `UnsupportedOperationException` carrying the owning step letter (C/D/E/F) — fail-loud per REQ-RTP-S-006, mirrors the Step A approach in `FabricRTPWorld`.
    - [x] `:rtp-fabric:rtp-fabric-common:compileJava` BUILD SUCCESSFUL.
    - [ ] **Test deferred** — same rationale as Step A: `ReqRtpS006FabricEarlyApiTest` requires a wired `RTPFabricMod.onInitialize()` (Step E) plus dual-runtime exercise; it lands with the Step H smoke test. `TRACEABILITY.md` row for REQ-RTP-S-006 (Fabric) added when the test lands.
- **Acceptance gate:** Step H dual-runtime smoke test exercises the early-API contract on a Fabric dev server; Bukkit-family build remains green throughout.

### Step C — `FabricScheduler` Full Implementation

- `scheduleAsync` → `Util.backgroundExecutor()` (canonical mojmap accessor on MC 1.21.1; `getMainWorkerExecutor` was the pre-1.21 name).
- `scheduleSync` → `MinecraftServer#execute(Runnable)` for one-shot main-thread dispatches; `ServerTickEvents.END_SERVER_TICK` callback drains the delayed/repeating queue.
- `cancelTask` → backed by `ConcurrentHashMap<Integer, ScheduledEntry>`; cancellation flips a `volatile boolean` checked at next tick drain.

- **Status (landed 2026-05-01 — minimal slice):**
    - [x] `FabricScheduler implements RTPScheduler` in `rtp-fabric/rtp-fabric-common/.../scheduling/FabricScheduler.java`. Full implementation of all 12 contract methods.
    - [x] Async path: `runTaskAsynchronously` and `runTaskTimerAsynchronously` dispatch via `Util.backgroundExecutor()`. Async repeating timers schedule on the tick queue and dispatch each fire to the worker pool.
    - [x] Sync path: `runTask` runs inline if already on the server thread (`Thread.currentThread() == server.getRunningThread()`), else `server.execute(task)`. Pre-server-start calls throw `IllegalStateException` (REQ-RTP-S-006 fail-loud).
    - [x] Delayed/repeating: tick-counted `ScheduledEntry` map drained by `tick(MinecraftServer)`; one-shot entries removed after fire, periodic entries reset to `periodTicks`. Throwables caught and logged via `RTP.log`.
    - [x] Region-aware overloads (`runTask(RTPLocation,...)`, `runTask(RTPWorld,cx,cz,...)`, etc.) delegate to non-region equivalents — Fabric has no Folia-style region threading; matches `BukkitSchedulerImpl`'s convention on Spigot/Paper.
    - [x] `setServer(MinecraftServer)` / `clearServer()` lifecycle hooks; `tick(MinecraftServer)` callback hook — to be wired from `ServerLifecycleEvents.SERVER_STARTED` / `SERVER_STOPPING` / `ServerTickEvents.END_SERVER_TICK` in Step E (`RTPFabricMod.onInitialize()`).
    - [x] `:rtp-fabric:rtp-fabric-common:compileJava` BUILD SUCCESSFUL.
    - [ ] **Test deferred** — same rationale as Steps A/B: meaningful exercise requires a wired `RTPFabricMod.onInitialize()` (Step E) to register the lifecycle/tick callbacks, plus dual-runtime exercise. Lands with the Step H smoke test. Existing `MockRTPScheduler` and Bukkit/Folia scheduler contract tests cover the cross-platform contract; `FabricScheduler` follows the same shape.
- **Acceptance gate:** existing scheduler contract behaviour holds on Fabric — verified end-to-end via the Step H dual-runtime smoke test (lifecycle hooks called once each, tick callback drains the queue, `cancelTask` prevents subsequent fires).

### Step D — `FabricDatabaseHandler`

Locate the config dir via `FabricLoader.getInstance().getConfigDir().resolve("rtp")`, then delegate to `rtp-core`'s platform-agnostic `DatabaseHandler`. **No new `rtp-api` abstraction.**

- **Status (landed 2026-05-01 — minimal slice, D2 scope):**
    - [x] `FabricDatabaseHandler` in `rtp-fabric/rtp-fabric-common/.../database/FabricDatabaseHandler.java`. Static `setupDatabase(RTP)` mirrors `BukkitDatabaseHandler` semantics: reads `database` config map, picks accessor (`yaml`/`h2`/`mysql`/`postgresql`/`sqlite` default), writes `.db_state`, calls `RTP.handleMigration`, schedules `databaseAccessor.startup()` one tick later.
    - [x] Config dir resolution via `resolveConfigDirectory()` → `FabricLoader.getInstance().getConfigDir().resolve("rtp")`; creates the directory if absent. No `org.bukkit.*` imports (ADR-022 §4 invariant).
    - [x] REQ-RTP-S-006 fail-loud: throws `IllegalStateException` if invoked with a `null` `RTP` instance. `printStackTrace` replaced with `RTP.log(Level.WARNING, ..., e)` per AGENTS.md *Logging & Feedback*.
    - [x] `:rtp-fabric:rtp-fabric-common:compileJava` BUILD SUCCESSFUL.
    - [ ] **Test deferred** — same rationale as Steps A/B/C: meaningful exercise requires `RTPFabricMod.onInitialize()` (Step E) to invoke `setupDatabase` at the right lifecycle point. Existing `CachedLocationRoundTripTest` already exercises the underlying accessor contract; Fabric-specific exercise lands with the Step H smoke test.
- **Acceptance gate:** existing `CachedLocationRoundTripTest` reused against the Fabric handler. Shutdown-flush rule from `LESSONS_LEARNED.md` (2026-04-18) honoured: `databaseAccessor.processQueries(Long.MAX_VALUE)` runs **after** `flushDirtyCache()` and **before** `stop.set(true)` on `ServerLifecycleEvents.SERVER_STOPPING` (wired in Step E).

### Step E — Event Bridge

Map all critical Bukkit events to Fabric's hooks in `FabricEventBridge`, registered from `RTPFabricMod.onInitialize()`:

- `PlayerQuitEvent` → `ServerPlayConnectionEvents.DISCONNECT` (queue cleanup; release any `MemoryTracker`-tracked tickets owned by the player).
- `WorldLoadEvent` → `ServerWorldEvents.LOAD`.
- `WorldUnloadEvent` → `ServerWorldEvents.UNLOAD`.
- Server lifecycle → `ServerLifecycleEvents.SERVER_STOPPING` drives `RTP.stop()` shutdown-flush.
- Cancelable `PlayerTeleportEvent` → `EntityTeleportCallback` or a mixin to allow RTP to intercept teleports when necessary.

- **Status (landed 2026-05-01 — E2 scope: lifecycle + tick + world + player session):**
    - [x] `FabricEventBridge` in `rtp-fabric/rtp-fabric-common/.../events/FabricEventBridge.java` registers `ServerLifecycleEvents.SERVER_STARTED` (binds `MinecraftServer` into `FabricServerAccessor` + `FabricScheduler`, registers all already-loaded `ServerLevel`s, kicks `FabricDatabaseHandler.setupDatabase`), `SERVER_STOPPING` (calls `RTP.stop()` for shutdown flush, then `accessor.unbindServer()`), `END_SERVER_TICK` (drives `FabricScheduler.tick`), `ServerWorldEvents.LOAD/UNLOAD` (world cache maintenance), `ServerPlayConnectionEvents.JOIN/DISCONNECT` (`FabricRTPPlayer` lifecycle).
    - [x] `FabricRTPPlayer` in `rtp-fabric/rtp-fabric-common/.../player/FabricRTPPlayer.java`. Real `uuid()`, `name()`, `isOnline()`, `getLocation()` (resolves world via `RTP.serverAccessor.getRTPWorld(dimension)`), `setLocation()` (hops to server thread via `server.submit`, calls `ServerPlayer#teleportTo`), `sendMessage()` via `Component.literal`, `performCommand()` via `MinecraftServer.getCommands().performPrefixedCommand`. `hasPermission()` falls back to op-level (`hasPermissions(2)`) pending Step F. `unbind()` called by the bridge on disconnect to drop the native handle (REQ-RTP-S-004 / REQ-FABRIC-ARCH-006 memory hygiene).
    - [x] `RTPFabricMod.onInitialize()` body now real: instantiates `FabricServerAccessor`, sets `RTP.serverAccessor`, triggers `RTP.getInstance()`, registers `FabricEventBridge`. Failures throw out of `onInitialize` (REQ-RTP-S-004 — no silent mod-load).
    - [x] `FabricServerAccessor` `getRTPWorld`/`getPlayer`/`getRTPWorlds`/`getPluginDirectory`/`getServerVersion`/`getPluginVersion`/`getServerIntVersion`/`isPrimaryThread`/`getScheduler`/`getPlugin`/`stop`/`start` all real; backed by the bridge-populated maps.
    - [x] `rtp-fabric/REQUIREMENTS.md` authored (REQ-FABRIC-F-001…010 + REQ-FABRIC-ARCH-001…010); resolves the previously-404 link from top-level `REQUIREMENTS.md`.
    - [x] `:rtp-fabric:rtp-fabric-common:compileJava :rtp-plugin:compileJava` BUILD SUCCESSFUL.
    - [ ] **Deferred to E-tail / E3 / Step F / Step H:** teleport-cancel callback (would need a Mixin against `Entity#teleportTo`); biome/material listings; world-border + shape function plumbing; full perms via `fabric-permissions-api` (Step F); end-to-end runtime exercise (Step H smoke gate); **scheduled-task processor parity (Step E3 — see below)**.
- **Acceptance gate:** Step H dual-runtime smoke test — server boots, `FabricEventBridge` callbacks fire in order, players join/leave cleanly, DB flushes on shutdown.

### Step E3 — Scheduled-Task Processor Parity *(added 2026-05-01)*

**Discovered during Step G G1 review.** A direct comparison of `RTPBukkitPlugin.onEnable` against `RTPFabricMod.onInitialize` shows Fabric is missing the recurring-task wiring that makes `/rtp` actually function. The teleport pipeline (`AsyncTaskProcessing` / `SyncTaskProcessing`) is what turns queued teleport requests into real teleports — without something pumping it, `/rtp` queues work that nothing executes.

**The good news:** `rtp-core`'s `RTP` constructor (`rtp-core/.../common/RTP.java` ~line 193–220) **already schedules** the core pipeline timers itself:

- `SyncTaskProcessing` via `RTP.scheduler.runTaskTimer(...)` every tick.
- `AsyncTaskProcessing` via `RTP.scheduler.runTaskTimerAsynchronously(...)` every tick.
- `databaseAccessor.rebuildCachedLocationsFromMemory()` + `flushDirtyCache()` every 6000 ticks.
- `databaseAccessor.flush()` (SQL) every 60 ticks.
- `PerformanceTracker.start(scheduler)` heartbeat.

This means **most of the parity gap closes for free** as soon as `RTP.scheduler` is set before `new RTP()` runs. The remaining gap is the platform-specific wiring that lives in the Bukkit plugin's `onEnable` body.

#### Bukkit vs. Fabric comparison

| What Bukkit `onEnable` does | Fabric `onInitialize` status | Action |
|---|---|---|
| `RTP.serverAccessor = new BukkitServerAccessor()` | ✅ done (Step E2) | — |
| **`RTP.scheduler = new BukkitSchedulerImpl(this)`** *(reflective)* | ❌ **missing — silently NPEs in `RTP` ctor** | **E3-1: assign `RTP.scheduler = accessor.getScheduler()` BEFORE `RTP.getInstance()`** |
| `RTP.serverAccessor.start(plugin)` | ✅ implicit via `bindServer` (Step E2) | — |
| `new RTP()` (constructor schedules pipeline timers via `RTP.scheduler`) | ⚠️ runs but its scheduling calls fail because `RTP.scheduler == null` | Fixed by E3-1. |
| `BukkitDatabaseHandler.setupDatabase(rtp)` | ✅ wired 2026-05-01 — `FabricDatabaseHandler.setupDatabase(rtp)` invoked from `RTPFabricMod.onInitialize()` immediately after `RTP.getInstance()` (mirrors Bukkit ordering); `FabricServerAccessor.getPluginDirectory()` mkdirs the config dir so `Configs` ctor + DB init both find it. | Verify selected accessor at Step H smoke test. |
| `ChunkyBorderChecker.loadChunky()` | N/A (Bukkit-only soft-depend) | — |
| `RTP.getInstance().startupTasks.execute(Long.MAX_VALUE)` (drain #1, sync) | ❌ missing | **E3-6: drain `startupTasks` after event-bridge registration.** |
| `RTP.scheduler.runTaskLater(... drain startupTasks ..., 1)` (drain #2, deferred) | ❌ missing | **E3-6 (continued)** — schedule a 1-tick-later drain. |
| `setupBukkitEvents()` registers ~9 listeners | ✅ partial via `FabricEventBridge` (Step E2: lifecycle + tick + world + player join/disconnect). Damage/move/respawn/teleport/changeworld listeners pending | Tracked under E-tail / E3-7 (pure event work, not scheduled-task work — listed here only for symmetry). |
| `RTP.scheduler.runTaskLater(this::setupIntegrations, 1)` (Vault/claims) | N/A (Bukkit-only) | — |
| `RTP.scheduler.runTaskLater(BukkitEffectsHandler::setupEffects, 1)` | Deferred — no Fabric effects layer yet | Track separately; not blocking `/rtp`. |
| `if (!isFolia()) RTP.scheduler.runTaskTimer(new ChunkUnloadProcessor(), 1, 1)` | ❌ missing | **E3-3: schedule `ChunkUnloadProcessor` once `RTP.scheduler` is wired.** Fabric has no Folia-style region threading, so the non-Folia branch applies. |
| `DatabaseProcessing.start(this)` *(periodic flush wrapper, runs `RTP.scheduler.runTaskTimerAsynchronously` at 16ms)* | ⚠️ functionally redundant with the rtp-core constructor's flush timers; `BukkitDatabaseHandler` schedules an additional `databaseAccessor.processQueries(MAX_VALUE)` heartbeat | **E3-2: verify the rtp-core timers are sufficient on Fabric; if not, add a `FabricDatabaseProcessing` shim mirroring `DatabaseProcessing`.** |
| `RTP.getInstance().startupTasks.execute(Long.MAX_VALUE)` (drain #3, sync, after console banner) | ❌ missing | **E3-6 (continued)** — third drain. |
| PAPI registration | N/A (Bukkit-only) | — |
| `JarUtils.extractDocs(getDataFolder(), version)` | ❌ missing | **E3-4: port `JarUtils.extractDocs` to a `FabricJarUtils` (no `JavaPlugin` dep), seed `<configDir>/rtp/docs/`.** |
| `initLoginReserveCache()` (ADR-023) | ✅ landed 2026-05-11 — `FabricEventBridge.initLoginReserveCache(server)` + `refillLoginReserveOnQuit()` + `FabricOnEventTeleports.onJoin` (see ADR-023 *Fabric port*). | E3-5 closed. |
| `metrics = new Metrics(this, 30865)` *(bStats)* | N/A (Bukkit-only — bStats has a separate Fabric module if pursued later) | Track separately; not blocking `/rtp`. |

#### Required `RTPFabricMod.onInitialize()` changes (in order)

```java
// 1. Wire accessor + scheduler BEFORE constructing RTP — mirrors RTPBukkitPlugin order.
FabricServerAccessor accessor = new FabricServerAccessor();
RTP.serverAccessor = accessor;
RTP.scheduler = accessor.getScheduler();              // <-- E3-1, currently missing

// 2. Trigger lazy RTP construction; constructor self-schedules
//    SyncTaskProcessing / AsyncTaskProcessing / DB-flush timers via RTP.scheduler.
RTP.getInstance();

// 3. Register the event bridge so SERVER_STARTED can call setupDatabase() + drains.
new FabricEventBridge(accessor).register();

// 4. Brigadier registration (Step G G1 — already done).
// ...

// 5. Schedule platform-specific recurring tasks once the bridge is in place.
//    Note: Fabric is non-Folia equivalent — the Folia guard does not apply.
RTP.scheduler.runTaskTimer(new ChunkUnloadProcessor(), 1, 1);   // <-- E3-3

// 6. Seed data folder with bundled docs (E3-4).
FabricJarUtils.extractDocs(accessor.getPluginDirectory(), MOD_VERSION);

// 7. Drain startupTasks (E3-6 — three drains, mirroring Bukkit).
//    Drain #1 + #3 are synchronous; drain #2 is RTP.scheduler.runTaskLater(..., 1).
RTP rtp = RTP.getInstance();
while (rtp.startupTasks.size() > 0) rtp.startupTasks.execute(Long.MAX_VALUE);
RTP.scheduler.runTaskLater(() -> {
    while (rtp.startupTasks.size() > 0) rtp.startupTasks.execute(Long.MAX_VALUE);
}, 1);
// (third drain after any other deferred init, matching Bukkit's banner-then-drain order)

// 8. ADR-023 login reserve cache (E3-5).
initLoginReserveCacheFabric(accessor);
```

Some of this naturally moves to `FabricEventBridge.SERVER_STARTED` instead of `onInitialize()` — specifically anything that needs a live `MinecraftServer` (e.g. login cache uses `MinecraftServer#getMaxPlayers()`). The split is the same as Bukkit's "what runs on plugin enable vs. what runs on first tick / SERVER_STARTED equivalent". Final placement is implementation detail.

#### Status (landed 2026-05-05)

- [x] **E3-1** — `RTP.scheduler = accessor.getScheduler()` is set before `new RTP()` in `RTPFabricMod.onInitialize()`. The `RTP` constructor's self-scheduled `SyncTaskProcessing` / `AsyncTaskProcessing` / DB-flush timers (`rtp-core/.../common/RTP.java` lines ~211–243) register into `FabricScheduler` and are pumped by `FabricEventBridge`'s `END_SERVER_TICK` callback.
- [x] **E3-2** — `FabricDatabaseHandler.setupDatabase(rtp)` is invoked from `onInitialize` immediately after `RTP.getInstance()`. The rtp-core constructor's 60-tick SQL flush timer + 6000-tick cached-locations rebuild are sufficient on Fabric — no `FabricDatabaseProcessing` shim required.
- [x] **E3-3** — `RTP.scheduler.runTaskTimer(new ChunkUnloadProcessor(), 1, 1)` scheduled in `RTPFabricMod.onInitialize()` after Brigadier registration (non-Folia branch always applies on Fabric).
- [ ] **E3-4** — `JarUtils.extractDocs` not yet ported to a `FabricJarUtils` (cosmetic; not blocking `/rtp`).
- [x] **E3-5** — `initLoginReserveCache()` (ADR-023) ported in `FabricEventBridge` (`initLoginReserveCache` bootstrap at `SERVER_STARTED`; `refillLoginReserveOnQuit` on the `Disconnect` proxy) and `FabricOnEventTeleports.onJoin` (perm gate via existing `FabricRTPPlayer.hasPermission` → `fabric-permissions-api` + `ops.json` fallback; first-join via `<worldRoot>/playerdata/<uuid>.dat` probe). Covered by `ReqFabricAdr023HasPlayedBeforeTest` (6/6 green). See ADR-023 *Fabric port* and `TODO.md` §3.
- [x] **E3-6** — Three `startupTasks.execute(Long.MAX_VALUE)` drains added (sync, +1-tick deferred, sync post-banner) mirroring `RTPBukkitPlugin.onEnable` / `BootstrapSupport.drainStartupTasks`. Without this the region prefill never started, leaving `keptLocations`/`unkeptLocations` empty so `/rtp` could not produce a destination.
- [ ] **E3-7** — Damage / move / respawn / teleport / changeworld listener parity in `FabricEventBridge` — tracked under E-tail; not blocking `/rtp` itself.
- [x] `:rtp-plugin:compileJava` BUILD SUCCESSFUL after the E3-3 + E3-6 changes (2026-05-05).

#### Runtime mitigation (landed 2026-05-05) — adaptive promotion cap + dropped periodic ticket sweep

The first end-to-end Fabric `/rtp` smoke after E3-3/E3-6 produced a 60-second watchdog crash (60s tick on the server thread, ~6 seconds after `Done!`). Triage trail in chat history; root cause: `Region.execute()` previously dispatched up to `activeChunkCap` concurrent `getChunkAtAsync` calls on each tick when `currentHot=0` and `inFlight=0` — fine on Bukkit's async chunk loader but crash-inducing on Fabric where `FabricRTPWorld.getChunkAt` round-trips through `server.submit` + `cache.getChunk(..., FULL, true)` and effectively serialises on the tick thread. Compounding: `RegionQueueManager.validateTickets` was called unconditionally on the first `Region.execute()` after rebind (`lastValidationTime = 0`), dispatching another N concurrent `getChunkAtAsync` on every kept entry.

Code change in `rtp-core/.../selection/region/Region.java`:
- Removed the periodic `queueManager.validateTickets(getWorld())` sweep from `Region.execute()` and the `lastValidationTime` field. Tickets are freshly applied at the L2→L1 promotion site below; the `ChunkReservation` owns the ticket lifecycle, so a periodic re-validate duplicated work and was the boot-time chunk-load storm trigger. If a sanity sweep is needed in future for tickets stripped by external commands (`/forceload remove`), it should be a low-frequency async timer outside `Region.execute()`.
- Added an EMA-based adaptive per-tick promotion cap: `chunkLoadEmaNanos` (volatile long, alpha = 1/8) tracks observed promotion duration, sampled at every terminal path (success, unsafe-drop, cache-full, load-failure). The deficit loop caps iterations at `max(1, 25_000_000ns / emaNs)`. Bootstrap: zero EMA → 1 promotion/tick on the first tick, relaxes upward as samples accumulate. Cheap pre-genned chunks → high cap → fast warm-up; expensive ungenerated chunks → low cap → sustainable warm-up.

#### S-002 fix (landed 2026-05-05) — non-persistent chunk tickets

Follow-up #1 from the prior session's submit ("S-002 audit of `FabricRTPWorld.getOrLoadChunk` ticket-release on `TimeoutException`") was rescoped after investigation: the actual hazard was not in `getOrLoadChunk` (which does not apply tickets) but in `FabricRTPWorld.setForceLoadedImpl`, which used vanilla `ServerLevel#setChunkForced(...)`. That call **persists to `level.dat#ForcedChunks`**, so a watchdog crash mid-pipeline (which the user hit on the 2026-05-05 smoke test, log line `13 force loaded chunks were found in minecraft:overworld at: [...]`) leaks RTP-owned forced chunks to disk and re-applies them on the next world load. Bukkit's `addPluginChunkTicket` is non-persistent and Folia inherits the Bukkit semantics, so this hazard is Fabric-specific.

Code change:

- `FabricVersionAdapter` SPI: added `applyTicket(ServerLevel, cx, cz)` and `releaseTicket(ServerLevel, cx, cz)` returning `CompletableFuture<Void>`. Default implementations return failed futures (`UnsupportedOperationException`) per S-006 (no silent no-ops).
- `V1_21_R1FabricVersionAdapter` (the active target): implements both via `DistanceManager#addRegionTicket` / `#removeRegionTicket` with a process-wide `TicketType<ChunkPos>` registered as `TicketType.create("rtp", Comparator.comparingLong(ChunkPos::toLong), 0)` — non-persistent, no auto-expiry. Reflective access to the package-private `DistanceManager` methods (cached `Method` handles); switching to an access-widener is deferred to the Loom-stable phase.
- `FabricRTPWorld.setForceLoadedImpl` rewritten to delegate to the active `FabricVersionAdapter` instead of calling `world.setChunkForced(...)`. Class- and method-level Javadocs updated to document why `setChunkForced` is forbidden on this path.

See [`rtp-fabric/docs/adr/rtp-fabric-ADR-003-non-persistent-chunk-tickets.md`](../../rtp-fabric/docs/adr/rtp-fabric-ADR-003-non-persistent-chunk-tickets.md) for the full decision record. `V1_20_R1FabricVersionAdapter` and `V26_1_R1FabricVersionAdapter` inherit the SPI default and will throw on `keep(true)` until ported — tracked under E-tail. Pre-existing leaked entries in `level.dat#ForcedChunks` from earlier Fabric builds require a one-time admin `/forceload remove` cleanup (no automatic migration).

#### Operational recommendation — pre-generate the world (Fabric)

Even with the adaptive cap, on a *fresh* unexplored Fabric world the per-chunk generation cost can be 200–2000 ms. The cap absorbs that (at the cost of slower kept-cache warm-up: ~1–10 seconds typical, longer on extreme terrain). For production Fabric servers it is **strongly recommended** to pre-generate the relevant region radii using Chunky or an equivalent before enabling RTP, so `cache.getChunk` resolves from disk (~5 ms typical) rather than triggering generation. This is **not enforced** at runtime — RTP boots fine without pre-gen, and the cap ensures no watchdog crash — but unprepared servers will see slower first-`/rtp` responses for the first ~10–30 seconds of warm-up and slow per-`/rtp` chunk-tickets when the spiral lands outside any pre-genned area. (No equivalent recommendation is made for Spigot/Paper, where the platform's async chunk loader handles concurrent generation efficiently.)

#### Acceptance

- `RTP.scheduler` is non-null by the time `RTP.getInstance()` runs.
- Joining the Fabric server, running `/rtp`, and observing an actual teleport in chat + position change (verified manually under Step H smoke test).
- No `NullPointerException` from `RTP` constructor in the Fabric server log on startup.
- DB flush observable in the configured backend after a teleport (file-modification timestamp on YAML, or row in SQL `cached_locations`).

#### Why this wasn't caught earlier

Steps A–G all compile cleanly in isolation and the Fabric mod loads without throwing. The pipeline-pump gap only manifests at *runtime* when a player executes `/rtp` — and runtime end-to-end was deliberately deferred to Step H per the gate-restructure decision (see Phase 1 acceptance gate note). Recording this finding now under a dedicated Step E3 keeps the per-step gate model honest and surfaces the gap before the Step H smoke test rather than during it.

### Step F — Permissions

Add `me.lucko:fabric-permissions-api` as `modCompileOnly` (soft-depend). Implement `FabricRTPPlayer.hasPermission(node)` via `Permissions.check(source, node, opFallback)` with an op-level fallback. LuckPerms-Fabric satisfies the API automatically when present.

- **Acceptance gate:** permission node test parity with the Bukkit adapter.

### Step G — Brigadier Bridge in `commands-api` (per [commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md))

Implement `BrigadierCommandAdapter` inside `commands-api` that converts the `commands-api` tree into Brigadier `LiteralArgumentBuilder` nodes. `RTPCmdFabric` registers the adapted tree via `CommandRegistrationCallback.EVENT` — **no platform-specific command logic duplication**. Implement advanced tab completion leveraging Brigadier's client-side capabilities. Ensure RTP messages and command feedback route correctly to Fabric players/console.

- **Status (landed 2026-04-30 — partial, structural):**
    - [x] `BrigadierCommandAdapter` + `BrigadierBridgeContext<S>` in `commands-api/.../brigadier/` (Brigadier as `compileOnly`; never loaded on Bukkit-family runtimes). Walks `TreeCommand`, emits literal nodes for sub-commands, typed argument nodes for `IntegerParameter` / `FloatParameter` / `BooleanParameter`, and string-with-suggestions for `EnumParameter` / `CoordinateParameter` / unknowns. Permission gating is wired via `requires(...)`.
    - [x] `RTPCmdFabric.register(CommandDispatcher<S>, CommandsAPICommand, BrigadierBridgeContext<S>)` shim in `rtp-fabric-common` (generic `<S>`; no `net.minecraft.*` imports — keeps the module buildable without a hot Loom toolchain).
    - [x] REQ-traceable test: `ReqApiArch005BrigadierBridgeTest` (3 tests) — node structure, dispatch round-trip, permission predicate gating. `TRACEABILITY.md` row `REQ-API-ARCH-005` updated.
    - [ ] **Not yet wired** — `CommandRegistrationCallback.EVENT` registration from a real Fabric entrypoint, `BrigadierBridgeContext<ServerCommandSource>` builders backed by Fabric permission API, end-to-end `commands-live` smoke against `rtp test full`. Bukkit dispatcher (`BukkitTreeCommand`) remains the production path on all Bukkit-family platforms — purely additive change.
- **Acceptance gate:** the `commands-live` portion of `rtp test full` produces the REQ-RTP-S-004 warnings on Fabric (intentional malformed-input WARN logs are evidence of compliance, not failures — see `LESSONS_LEARNED.md`). Tab completion smoke test passes.

### Step H — Stabilization, Dual-Runtime Smoke Test & Testing

- **Memory leak audit** — every allocator of a chunk ticket or `TeleportPipelineTask` registers with `MemoryTracker` and releases on all exit paths (normal, exception, disconnect) on Fabric, matching the Bukkit-family contract.
- **Concurrency review** — verify region-based task scheduling is safe on Fabric's threading model. No Folia-isms (`Bukkit.isOwnedByCurrentRegion`, region/global schedulers) leak into Fabric code paths.
- **Fabric test suite** — adapt existing unit and integration tests where they exercise platform abstractions.
- **Dual-runtime end-to-end smoke test** *(moved here from Phase 1)* — the single produced JAR loads cleanly on a Paper test server *and* on a Fabric test server from the same artifact, and `/rtp` (or the equivalent command tree) executes end-to-end on each. This guards against Loom remap-scoping regressions (the only single-point-of-failure introduced by single-JAR packaging) and validates that Steps A–G actually compose into a working Fabric mod. This is the gate that proves ADR-022's single-JAR multi-loader packaging works end-to-end; it cannot meaningfully run earlier because no prior phase delivers enough featureset to teleport on Fabric.

- **Acceptance gate:** all existing S-00x regression guards (`ReqRtpS005ChunkLoadingTest`, `ReqRtpS004NullChunkAttributionTest`, etc.) green when run against Fabric implementations of the relevant abstractions, AND the dual-runtime end-to-end smoke test passes on both Paper and Fabric from the same JAR.

## Phase 3: Documentation & Release

- [ ] **Admin documentation** — update `docs/admin/` with Fabric-specific installation and configuration instructions.
- [ ] **Developer documentation** — update `docs/dev/` to reflect the multi-platform architecture and Fabric contribution guidelines.
- [ ] **TRACEABILITY.md** — add rows for every new REQ-traceable Fabric test (Steps A, B, F, etc.).
- [ ] **CHANGELOG.md** — one entry per phase under "Unreleased".
- [ ] **COVERAGE_PLAN.md** — add a Fabric column to the platform-coverage matrix.
- [ ] **Beta release** — first public beta of RTP for Fabric once Phase 2 Step H gate is green and Phase 3 docs are merged.

## Phase 4: Future — Forge / NeoForge Evaluation

Deferred until Fabric is stable.

- [ ] **Evaluation** — once Fabric is stable, evaluate the effort to add `rtp-forge` (or `rtp-neoforge`) using the established abstraction patterns.
- [ ] **Architectury?** — re-evaluate moving to a common abstraction layer like Architectury for long-term maintenance of multiple mod-loader platforms.

## What Does NOT Need to Change in `rtp-api` or `rtp-core`

The April 2026 gap analysis (referenced in [rtp-fabric-ADR-002](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md)) confirmed the existing abstractions are sufficient for full Fabric support:

- `RTPServerAccessor`, `RTPWorld`, `RTPPlayer`, `RTPScheduler` interfaces require no new methods.
- `DatabaseHandler` in `rtp-core` is already platform-agnostic.
- `LocationGenerator`, `TeleportPipelineTask`, and `MemoryTracker` are untouched — Fabric wires into them via `RTP.getInstance()`.

The only potential future `rtp-api` addition is an `RTPPermissionProvider` interface to formalize the soft-depend pattern, but this is **deferred** until the Step F permissions work is complete and the pattern is proven in production.

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Loom plugin pollutes other modules' classpaths | Apply Loom only under `rtp-fabric/**`; gate Maven repos by `project.path.startsWith(':rtp-fabric')`. |
| Hidden S-005 violation re-introduced via an unrecognized Mojang `getChunk` call | Step A regression test plus a stretch-goal arch guard banning direct `ServerLevel#getChunk` from `rtp-fabric/**`. |
| Fabric tick threading vs. Folia region threading subtle drift | `FabricScheduler` documented behaviour matches the `RTPScheduler` contract; no Folia-isms leak into core. |
| Brigadier tree drift from the `commands-api` tree | Single adapter in `commands-api` (commands-api-ADR-001); no per-platform branching. |
| `MemoryTracker` leaks on disconnect mid-pipeline | `ServerPlayConnectionEvents.DISCONNECT` releases all tickets owned by the player, mirroring Bukkit `PlayerQuitEvent` cleanup. Step H audit. |
| Scope creep into Forge | Explicitly out of scope until Phase 4. |

## Ownership

Per ADR-022, a named maintainer owns Fabric end-to-end (build, mappings, CI toolchain, S-00x proofs, ongoing maintenance). **Owner: @leaf_26** (project lead; recorded 2026-04-30). Phase 3 (public beta release) gate is satisfied; Phase 1 and Phase 2 work proceeds against this ownership.
