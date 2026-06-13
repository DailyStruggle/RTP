package io.github.dailystruggle.rtp.fabric;

import io.github.dailystruggle.commandsapi.brigadier.BrigadierBridgeContext;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap;
import io.github.dailystruggle.rtp.fabric.commands.RTPCmdFabricRoot;
import io.github.dailystruggle.rtp.fabric.database.FabricDatabaseHandler;
import io.github.dailystruggle.rtp.fabric.events.FabricEventBridge;
import io.github.dailystruggle.rtp.fabric.server.FabricServerAccessor;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Fabric entry point — single-JAR multi-loader bootstrap (rtp-fabric-ADR-002 §2, formerly ADR-022/ADR-031).
 *
 * <p>Counterpart to {@code io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin}. Referenced from
 * {@code fabric.mod.json} as the {@code main} entrypoint. Per rtp-fabric-ADR-002 §4 (Architectural
 * Invariants) this class:
 *
 * <ul>
 *   <li>Shall not import {@code org.bukkit.*}.</li>
 *   <li>Shall not be reached from the {@code io.github.dailystruggle.rtp.bukkit} package
 *       and shall not reach into it. Each runtime's classloader resolves only its own
 *       transitive closure.</li>
 *   <li>Shares state with the Bukkit entry point only through {@code rtp-core} /
 *       {@code rtp-api} / {@code commands-api} / {@code effects-api}.</li>
 * </ul>
 *
 * <p><strong>Current state:</strong> structural skeleton — the wiring body is intentionally
 * minimal so the build structure (Loom + remapJar + multi-loader manifest) can be brought up
 * and verified end-to-end before Phase 2 Steps A–H land. The real wiring (FabricServerAccessor,
 * FabricEventBridge, Brigadier-bridged command tree per commands-api-ADR-001) is deferred to those steps.
 *
 * @see io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin
 */
public final class RTPFabricMod implements ModInitializer {

    /**
     * ADR-049 / rtp-fabric-ADR-013: backend-side network-mode bootstrap.
     * Platform-neutral (lives in rtp-core); the Fabric entrypoint owns its
     * lifecycle exactly as {@code RTPBukkitPlugin} owns the Bukkit one.
     * No-op when {@code network.yml} is absent or {@code network.enabled=false}.
     */
    private final NetworkModeBootstrap networkBootstrap = new NetworkModeBootstrap();

    @Override
    public void onInitialize() {
        // Event bridge + command wiring. Body kept minimal per
        // REQ-RTP-NF-003 (applied per-entry-point under rtp-fabric-ADR-002 §2). Heavy
        // lifting is in FabricServerAccessor / FabricEventBridge / the
        // commands-api Brigadier adapter.
        try {
            // ----------------------------------------------------------------
            // effects-api LogSink wiring. effects-api lives below rtp-core in
            // the module graph and cannot import RTP.log directly, so it
            // exposes a static LogSink SPI; we adapt it here so all
            // FINER-level diagnostics from FabricEffectRuntime / FabricSoundEffect
            // / FabricParticleEffect / FabricRegistryCompat / FabricValueCoercer
            // route through RTP.log instead of System.err. This eliminates the
            // raw "[STDERR]" lines (and their Windows-console em-dash mojibake)
            // that previously appeared at INFO regardless of log-level config.
            // ----------------------------------------------------------------
            try {
                io.github.dailystruggle.effectsapi.fabric.FabricEffectRuntime
                        .setLogSink(RTP::log);
            } catch (NoClassDefFoundError ignored) {
                // effects-api stripped (rtp-lite assembly) — nothing to wire.
            }

            // ----------------------------------------------------------------
            // rtp-fabric-ADR-001 — Fabric multiversion: select the per-MC version adapter
            // FIRST, before anything in rtp-fabric-common touches a
            // version-volatile call site. Reflective instantiation ensures a
            // Java-21 server never resolves the Java-25 v26_1_R1 class
            // (UnsupportedClassVersionError) — class loading is lazy, so an
            // unnamed class is never resolved.
            // ----------------------------------------------------------------
            installVersionAdapter();

            FabricServerAccessor accessor = new FabricServerAccessor();
            RTP.serverAccessor = accessor;
            // Step E3 — assign RTP.scheduler BEFORE RTP.getInstance(). The
            // rtp-core RTP() constructor self-schedules SyncTaskProcessing /
            // AsyncTaskProcessing / DB-flush timers via runTaskTimer*; without
            // this assignment the constructor NPEs on the first scheduler
            // call. FabricScheduler queues timers into its tick map and is
            // safe to use before SERVER_STARTED binds the MinecraftServer
            // (the tick drain begins once setServer() + tick() wire in via
            // the event bridge).
            RTP.scheduler = accessor.getScheduler();
            // Construct RTP explicitly — mirrors RTPBukkitPlugin.onEnable
            // (`new RTP()`). RTP.getInstance() is a plain field accessor and
            // does NOT lazy-construct; the constructor itself sets the static
            // `instance` field. The constructor builds Configs from
            // accessor.getPluginDirectory(), so default configs land in
            // <fabric-config>/rtp/ on first run.
            RTP rtp = RTP.getInstance();
            if (rtp == null) {
                rtp = new RTP();
            }

            // ----------------------------------------------------------------
            // ADR-049 step 4 — install the platform sampler factory so
            // NetworkModeBootstrap.boot(...) can construct a backend heartbeat
            // sampler that reads live Fabric host state (TPS / MSPT / player
            // count / loaded worlds). Mirrors AbstractServerAccessor.start's
            // RTP.backendStateSamplerFactory = BukkitBackendStateSampler::new.
            // ----------------------------------------------------------------
            RTP.backendStateSamplerFactory =
                    io.github.dailystruggle.rtp.fabric.network.FabricBackendStateSampler::new;

            // Read routing.lobbyMode from network.yml early (before
            // the startupTasks drain constructs Region instances) so a pure
            // cross-server lobby skips local region prefill. Defensive: any
            // failure resolves to lobbyMode=false, preserving non-lobby
            // behaviour. Mirrors RTPBukkitPlugin.onEnable's early read.
            try {
                java.io.File earlyNetworkYml = NetworkModeBootstrap.ensureNetworkYml(
                        accessor.getPluginDirectory(), RTPFabricMod.class);
                RTP.lobbyMode = NetworkModeBootstrap.readLobbyModeEarly(earlyNetworkYml);
                if (RTP.lobbyMode) {
                    RTP.log(Level.INFO,
                            "[RTP][fabric] routing.lobbyMode=true -- local region processing"
                                    + " will be skipped; this backend acts as a pure"
                                    + " cross-server dispatcher.");
                }
            } catch (Throwable t) {
                RTP.lobbyMode = false;
                RTP.log(Level.FINE,
                        "[RTP][fabric] lobbyMode early-read failed; defaulting to false: "
                                + t.getMessage());
            }

            // Configuration & Database setup. Mirrors
            // BukkitDatabaseHandler.setupDatabase invoked from
            // RTPBukkitPlugin.onEnable. Selects the DatabaseAccessor from
            // configs (sqlite default, h2/mysql/postgresql/yaml supported)
            // and schedules databaseAccessor.startup() via RTP.scheduler.
            //
            // Deferred to SERVER_STARTED: setupDatabase() invokes
            // Configs.reloadConfigs() → SafetyTokenExpander, which walks
            // BuiltInRegistries.BLOCK to flatten #tag tokens in
            // safety.airBlocks/unsafeBlocks. On MC 26.1+ (and arguably any
            // version) BuiltInRegistries is not fully populated during
            // onInitialize, so doing this work pre-start either yields an
            // empty tag snapshot or — on deobfuscated runtimes — fails JVM
            // verification on intermediary aliases. Running it from
            // SERVER_STARTED guarantees the registry is live, so #tag
            // expansion succeeds on the first pass without retry storms.
            // Bukkit doesn't have this problem because onEnable() fires
            // after the server is fully constructed and registries are
            // populated.

            // ----------------------------------------------------------------
            // Section C, C2 — install the Fabric metrics binding (single-region
            // EMA sampler). The binding is ticked from FabricEventBridge's
            // END_SERVER_TICK callback via an instanceof probe (so the bridge
            // module need not hard-depend on this install path). Suppliers
            // resolve playerCount / softCap reflectively against the bound
            // MinecraftServer so the binding has no net.minecraft imports.
            //
            // Safe to call before SERVER_STARTED: suppliers return 0 until the
            // accessor.getServer() != null, and the first END_SERVER_TICK that
            // fires after server start seeds the EMA. Idempotent — overwriting
            // a prior binding (if any) is the documented dispatcher behaviour.
            // ----------------------------------------------------------------
            try {
                io.github.dailystruggle.rtp.fabric.metrics.FabricMetricsBinding fabricMetrics =
                        new io.github.dailystruggle.rtp.fabric.metrics.FabricMetricsBinding(
                                () -> reflectIntZeroArg(accessor.getServer(),
                                        new String[] { "getPlayerCount", "method_3788" }),
                                () -> reflectIntZeroArg(accessor.getServer(),
                                        new String[] { "getMaxPlayers", "method_3802" }));
                io.github.dailystruggle.rtp.common.RTP.metrics.setBinding(fabricMetrics);
                io.github.dailystruggle.rtp.common.RTP.log(Level.INFO,
                        "[RTP] Fabric metrics binding installed (FabricMetricsBinding).");
            } catch (Throwable t) {
                io.github.dailystruggle.rtp.common.RTP.log(Level.WARNING,
                        "[RTP] FabricMetricsBinding install failed: "
                                + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            }

            new FabricEventBridge(accessor).register();

            // ----------------------------------------------------------------
            // MULTI_PLATFORM_PLAN Step K / rtp-fabric-ADR-014 — install the
            // Fabric map binding so /rtp visualization charts render to a
            // vanilla filled-map instead of bottoming out on NoopMapBinding
            // ("no concrete MapBinding installed"). Mirrors RTPBukkitPlugin's
            // BukkitMapBinding install. Only install when the active version
            // adapter actually implements the renderMapChart seam
            // (supportsMapCharts); otherwise leave MapDispatch on the
            // NoopMapBinding sentinel so the configurable mapBindingMissing
            // message still surfaces on un-ported MC lines (1.20.x, etc.).
            // FabricMapBinding is net.minecraft-free (it routes through the
            // adapter SPI), so referencing it here keeps the entrypoint clean
            // per rtp-fabric-ADR-002 / ADR-007.
            // ----------------------------------------------------------------
            try {
                FabricVersionAdapter mapAdapter = FabricVersionAdapterRegistry.peek();
                if (mapAdapter != null && mapAdapter.supportsMapCharts()) {
                    io.github.dailystruggle.rtp.fabric.maps.FabricMapBinding mapBinding =
                            new io.github.dailystruggle.rtp.fabric.maps.FabricMapBinding();
                    io.github.dailystruggle.rtp.common.commands.maps.MapDispatch
                            .setMapBinding(mapBinding);
                    // REQ-RTP-MAP-003 — release per-viewer map state on disconnect
                    // (parity with Bukkit's OnPlayerQuit -> MapDispatch.firePlayerQuit).
                    accessor.getFabricPlayerLifecycleHook().onPlayerQuit(uuid ->
                            io.github.dailystruggle.rtp.common.commands.maps.MapDispatch
                                    .firePlayerQuit(uuid));
                    RTP.log(Level.INFO,
                            "[RTP] Fabric map binding installed (FabricMapBinding, carrier="
                                    + mapAdapter.mcVersion() + ").");
                } else {
                    RTP.log(Level.INFO,
                            "[RTP] Fabric map binding NOT installed: version adapter "
                                    + (mapAdapter == null ? "<none>" : mapAdapter.mcVersion())
                                    + " does not support map charts; /rtp charts will report"
                                    + " mapBindingMissing (NoopMapBinding active).");
                }
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[RTP] onInitialize MapBinding install failed; MapDispatch will fall back"
                                + " to NoopMapBinding: " + t.getClass().getSimpleName() + ": "
                                + t.getMessage(), t);
            }

            // ----------------------------------------------------------------
            // effects-api-ADR-003 — wire the Fabric effects layer.
            // FabricEffectsHandler.setupEffects:
            //   1. Binds the MinecraftServer into FabricEffectRuntime so
            //      runtime.schedule() has a server to execute() onto.
            //   2. Calls FabricEffectsInitializer.registerAll() — binds the
            //      FabricValueCoercer (ADR-004) and registers SOUND/PARTICLE/
            //      TITLE/POTION effect prototypes (Phase-1 scope per ADR-003).
            //   3. Attaches the rtp.effect.* lifecycle hooks (presetup,
            //      postsetup, preload, postload, preteleport, postteleport,
            //      cancel, queuepush, queuepop) onto TeleportPipelineTask /
            //      RTPTeleportCancel / Region — mirroring BukkitEffectsHandler.
            //
            // Deferred to SERVER_STARTED because BuiltInRegistries (used by
            // FabricValueCoercer + the four concrete effects' default values)
            // is only fully populated after server start; calling registerAll()
            // earlier risks resolving sounds/particles/potions to null at
            // construction time on some 1.21+ MC versions.
            //
            // Also: also bind the runtime when the integrated server stops
            // (ServerLifecycleEvents.SERVER_STOPPED) so a subsequent restart
            // in the same JVM (singleplayer world reload) doesn't keep a
            // dangling reference to a torn-down server.
            // ----------------------------------------------------------------
            final RTP rtpForStart = rtp;
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
                    .SERVER_STARTED.register(server -> {
                        // Database + Configs (incl. SafetyTokenExpander #tag
                        // flattening) deferred from onInitialize so it runs
                        // against a fully-populated BuiltInRegistries. See
                        // the comment block at the setupDatabase call site
                        // earlier in onInitialize for rationale.
                        try {
                            FabricDatabaseHandler.setupDatabase(rtpForStart);
                        } catch (java.nio.file.FileSystemException fse) {
                            RTP.log(Level.SEVERE,
                                    "[RTP] Fabric database setup failed — RTP will run without persistence.", fse);
                        } catch (Throwable t) {
                            RTP.log(Level.SEVERE,
                                    "[RTP] Fabric database setup failed: "
                                            + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
                        }
                        // ------------------------------------------------
                        // Step E3 — start the periodic async drain of
                        // databaseAccessor.processQueries. Required for
                        // SQL-transport network mode: writes produced on
                        // this backend (cooldown rows, reservation
                        // tokens, player-state rows) only become visible
                        // to peer backends after this timer drains them.
                        // Mirrors RTPBukkitPlugin's DatabaseProcessing
                        // .start(this) call. The Bukkit and Fabric paths
                        // share the same platform-agnostic class in
                        // rtp-core (the Bukkit-package class is a
                        // deprecated shim around it).
                        // ------------------------------------------------
                        try {
                            io.github.dailystruggle.rtp.common.server
                                    .DatabaseProcessing.start();
                        } catch (Throwable t) {
                            RTP.log(Level.WARNING,
                                    "[RTP] DatabaseProcessing.start failed: "
                                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                        }

                        // ------------------------------------------------
                        // ADR-049 / rtp-fabric-ADR-013 — boot backend-side
                        // network mode AFTER the DB is up (the SQL transport
                        // reuses the same accessor's DataSource). Strict
                        // REQ-RTP-NET-002 parity with RTPBukkitPlugin: no-op
                        // when network.yml is absent or network.enabled=false.
                        // Failure here is logged but never aborts startup
                        // (network mode is strictly optional). After boot,
                        // register the join-time reservation-token redeem
                        // listener + the cross-server waitlist quit listener
                        // onto the FabricPlayerLifecycleHook so a Velocity-
                        // routed player arriving on this Fabric backend
                        // redeems its reservation token exactly as on Paper.
                        // ------------------------------------------------
                        try {
                            java.io.File networkYml = NetworkModeBootstrap.ensureNetworkYml(
                                    RTP.serverAccessor.getPluginDirectory(), RTPFabricMod.class);
                            networkBootstrap.boot(networkYml);
                            networkBootstrap.registerJoinTriggerSource();
                            networkBootstrap.registerWaitlistQuitListener();
                        } catch (Throwable t) {
                            RTP.log(Level.WARNING,
                                    "[RTP][fabric] network-mode boot failed; continuing without it: "
                                            + t.getMessage(), t);
                        }

                        try {
                            // Give the per-version adapter first crack at effects
                            // wiring — on deobfuscated 26.1.x the default obf path
                            // (FabricEffectsHandler.setupEffects → FabricEffectRuntime
                            // .bindServer) raises NoClassDefFoundError on link due
                            // to intermediary aliases (class_3222, class_2596, ...)
                            // in its constant pool, so effects never set up. The
                            // 26.1 adapter routes the same setup through the unobf
                            // carrier (FabricEffectsHandlerUnobf), whose bytecode
                            // names Mojang names natively. See V26_1_R1FabricVersion-
                            // Adapter#installEffectsWiring and effects-api-ADR-006.
                            // Pre-26 adapters return false (default) and we fall
                            // through to the obf carrier unchanged.
                            boolean handled = false;
                            try {
                                io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
                                        io.github.dailystruggle.rtp.fabric.version
                                                .FabricVersionAdapterRegistry.peek();
                                if (adapter != null) {
                                    handled = adapter.installEffectsWiring(server);
                                }
                            } catch (Throwable t) {
                                RTP.log(Level.WARNING,
                                        "[RTP] Fabric effects wiring (adapter override) failed: "
                                                + t.getClass().getSimpleName() + ": " + t.getMessage());
                            }
                            if (!handled) {
                                io.github.dailystruggle.rtp.fabric.effects.FabricEffectsHandler
                                        .setupEffects(server);
                            }
                        } catch (Throwable t) {
                            RTP.log(Level.WARNING,
                                    "[RTP] Fabric effects wiring failed: "
                                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                        }
                    });
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
                    .SERVER_STOPPED.register(server ->
                            io.github.dailystruggle.effectsapi.fabric.FabricEffectRuntime.unbindServer());

            // ----------------------------------------------------------------
            // Stop the periodic databaseAccessor.processQueries drain BEFORE
            // the server tears down its tick loop, mirroring
            // RTPBukkitPlugin.onDisable's DatabaseProcessing.kill() call. We
            // hook SERVER_STOPPING (not SERVER_STOPPED) so the kill lands
            // while the scheduler is still alive and can cancel the task
            // cleanly. The final drain of pending mutations is handled by
            // RTP-core's shutdown path (databaseAccessor.shutdown via the
            // RTPRunnable drain) — this hook only stops the periodic timer.
            // ----------------------------------------------------------------
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
                    .SERVER_STOPPING.register(server -> {
                        // ADR-049 / rtp-fabric-ADR-013 — reverse-order teardown
                        // of network mode BEFORE the DB drain stops, mirroring
                        // RTPBukkitPlugin.onDisable (stop publisher, close
                        // transport, unregister the lifecycle-hook listeners).
                        // Idempotent; safe if network mode was never enabled.
                        try {
                            networkBootstrap.shutdown();
                        } catch (Throwable t) {
                            RTP.log(Level.WARNING,
                                    "[RTP][fabric] network-mode shutdown failed (continuing): "
                                            + t.getMessage(), t);
                        }
                        try {
                            io.github.dailystruggle.rtp.common.server
                                    .DatabaseProcessing.kill();
                        } catch (Throwable t) {
                            RTP.log(Level.WARNING,
                                    "[RTP] DatabaseProcessing.kill failed: "
                                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                        }
                    });

            // ----------------------------------------------------------------
            // Post-teleport title / subtitle / actionbar — `messages.yml`
            // parity with BukkitEffectsHandler. Without this hook the
            // `title`, `subtitle`, `fadeIn`, `stay`, `fadeOut`, and
            // `actionbar` keys are silent no-ops on Fabric, so admins who
            // configure a "successfully teleported" splash on Bukkit see
            // nothing on Fabric.
            //
            // Resolution model:
            //   - Resolve config values up-front (cheap, thread-safe).
            //   - Hop to RTP.scheduler.runTask before sending packets so
            //     the player connection is touched on the server tick
            //     thread (mirrors Bukkit's runTask hop, which is itself
            //     a Folia AsyncCatcher requirement).
            //   - sendTitle / sendActionbar handle empty values internally
            //     so a partially-configured messages.yml (e.g. only
            //     subtitle set) still works.
            // ----------------------------------------------------------------
            io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask
                    .teleportPostActions.add(task -> {
                try {
                    if (task == null || task.player() == null) return;
                    final io.github.dailystruggle.rtp.api.entity.RTPPlayer rtpPlayer = task.player();

                    // Duck-type on sendTitle/sendActionbar rather than instanceof
                    // FabricRTPPlayer. Reason: per-MC adapter modules (e.g.
                    // V26_1_R1FabricRTPPlayer compiled under unobf Loom for the
                    // deobf MC 26.1.x runtime) implement RTPPlayer directly and
                    // do NOT extend the intermediary FabricRTPPlayer — so a
                    // FabricRTPPlayer instanceof check silently drops every
                    // post-teleport title/subtitle/actionbar on 26.1.x.
                    // Reflective dispatch keeps this hook adapter-agnostic for
                    // future per-version players too.
                    final java.lang.reflect.Method sendTitleM;
                    final java.lang.reflect.Method sendActionbarM;
                    try {
                        sendTitleM = rtpPlayer.getClass().getMethod(
                                "sendTitle", String.class, String.class,
                                int.class, int.class, int.class);
                        sendActionbarM = rtpPlayer.getClass().getMethod(
                                "sendActionbar", String.class);
                    } catch (NoSuchMethodException nsme) {
                        // RTPPlayer implementation doesn't expose the Fabric
                        // title/actionbar sinks — silently skip (matches the
                        // pre-2026-05 behaviour for non-Fabric players, e.g.
                        // a console-driven /rtp where task.player() is a
                        // DetachedClone).
                        return;
                    }

                    @SuppressWarnings("unchecked")
                    io.github.dailystruggle.rtp.common.configuration.ConfigParser<
                            io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys> lang =
                            (io.github.dailystruggle.rtp.common.configuration.ConfigParser<
                                    io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys>)
                                    RTP.configs.getParser(
                                            io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys.class);
                    if (lang == null) return;

                    final String title = lang.getConfigValue(
                            io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys.title, "").toString();
                    final String subtitle = lang.getConfigValue(
                            io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys.subtitle, "").toString();
                    final int fadeIn = lang.getNumber(
                            io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys.fadeIn, 0).intValue();
                    final int stay = lang.getNumber(
                            io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys.stay, 0).intValue();
                    final int fadeOut = lang.getNumber(
                            io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys.fadeOut, 0).intValue();
                    final String actionbar = lang.getConfigValue(
                            io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys.actionbar, "").toString();

                    if ((title == null || title.isEmpty())
                            && (subtitle == null || subtitle.isEmpty())
                            && (actionbar == null || actionbar.isEmpty())) {
                        return;
                    }

                    RTP.scheduler.runTask(() -> {
                        try {
                            sendTitleM.invoke(rtpPlayer, title, subtitle, fadeIn, stay, fadeOut);
                            sendActionbarM.invoke(rtpPlayer, actionbar);
                        } catch (ReflectiveOperationException roe) {
                            RTP.log(Level.WARNING,
                                    "[RTP] Fabric post-teleport title dispatch (reflective invoke) failed: "
                                            + roe.getClass().getSimpleName() + ": " + roe.getMessage());
                        }
                    });
                } catch (Throwable t) {
                    RTP.log(Level.WARNING,
                            "[RTP] Fabric post-teleport title dispatch failed: "
                                    + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            });

            // ----------------------------------------------------------------
            // Post-teleport command dispatch — config.yml `consoleCommands` /
            // `playerCommands` parity with BukkitEffectsHandler. Without this
            // hook the two config lists are silent no-ops on Fabric, leaving
            // operators no way to run e.g. `give [player] map` or `effect give
            // [player] resistance` after a successful /rtp on a Fabric server.
            //
            // Both lists tolerate a leading slash, substitute the literal
            // `[player]` token with the teleported player's name, and skip
            // blank entries. Console commands run as the server console
            // (op-level CommandSourceStack); player commands run as the
            // player (player.createCommandSourceStack via FabricRTPPlayer).
            // Dispatch is deferred to the main thread via RTP.scheduler.runTask
            // because Brigadier dispatch on Fabric must run on the server tick
            // thread (CommandSourceStack#performPrefixedCommand mutates server
            // state and is not thread-safe). Mirrors the Folia-aware Bukkit
            // path which routes commands to the global region scheduler for
            // the same reason.
            // ----------------------------------------------------------------
            io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask
                    .teleportPostActions.add(task -> {
                try {
                    if (task == null || task.player() == null) return;
                    io.github.dailystruggle.rtp.api.entity.RTPPlayer rtpPlayer = task.player();
                    String playerName = rtpPlayer.name();
                    if (playerName == null || playerName.isBlank()) return;

                    @SuppressWarnings("unchecked")
                    io.github.dailystruggle.rtp.common.configuration.ConfigParser<
                            io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys> configParser =
                            (io.github.dailystruggle.rtp.common.configuration.ConfigParser<
                                    io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys>)
                                    RTP.configs.getParser(
                                            io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.class);
                    if (configParser == null) return;

                    java.util.List<String> consoleCommandsToRun = new java.util.ArrayList<>();
                    Object consoleObj = configParser.getConfigValue(
                            io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.consoleCommands,
                            new java.util.ArrayList<>());
                    if (consoleObj instanceof java.util.List<?> consoleList) {
                        for (Object cmd : consoleList) {
                            if (cmd == null) continue;
                            String c = cmd.toString().replace("[player]", playerName);
                            if (c.isBlank()) continue;
                            // performPrefixedCommand tolerates a leading slash
                            // but normalize anyway for log-clarity parity with Bukkit.
                            if (c.startsWith("/")) c = c.substring(1);
                            consoleCommandsToRun.add(c);
                        }
                    }

                    java.util.List<String> playerCommandsToRun = new java.util.ArrayList<>();
                    Object playerObj = configParser.getConfigValue(
                            io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.playerCommands,
                            new java.util.ArrayList<>());
                    if (playerObj instanceof java.util.List<?> playerList) {
                        for (Object cmd : playerList) {
                            if (cmd == null) continue;
                            String c = cmd.toString().replace("[player]", playerName);
                            if (c.isBlank()) continue;
                            if (c.startsWith("/")) c = c.substring(1);
                            playerCommandsToRun.add(c);
                        }
                    }

                    if (consoleCommandsToRun.isEmpty() && playerCommandsToRun.isEmpty()) return;

                    final java.util.List<String> consoleFinal = consoleCommandsToRun;
                    final java.util.List<String> playerFinal = playerCommandsToRun;
                    final io.github.dailystruggle.rtp.api.entity.RTPPlayer playerRef = rtpPlayer;
                    RTP.scheduler.runTask(() -> {
                        if (!consoleFinal.isEmpty()) {
                            io.github.dailystruggle.rtp.api.entity.RTPCommandSender console =
                                    RTP.serverAccessor.getSender(
                                            io.github.dailystruggle.rtp.api.RTPAPI.serverId);
                            if (console != null) {
                                for (String c : consoleFinal) {
                                    try {
                                        console.performCommand(playerRef, c);
                                    } catch (Throwable t) {
                                        RTP.log(Level.WARNING,
                                                "[RTP] consoleCommand dispatch failed ('" + c + "'): "
                                                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                                    }
                                }
                            }
                        }
                        for (String c : playerFinal) {
                            try {
                                playerRef.performCommand(playerRef, c);
                            } catch (Throwable t) {
                                RTP.log(Level.WARNING,
                                        "[RTP] playerCommand dispatch failed ('" + c + "'): "
                                                + t.getClass().getSimpleName() + ": " + t.getMessage());
                            }
                        }
                    });
                } catch (Throwable t) {
                    RTP.log(Level.WARNING,
                            "[RTP] Fabric post-teleport command dispatch failed: "
                                    + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            });

            // ----------------------------------------------------------------
            // Brigadier custom-ArgumentType registration: NOT performed.
            //
            // commands-api-ADR-001 addendum 2026-05-06e: the bridge no longer
            // declares any custom Brigadier ArgumentType. The previous
            // WhitespaceTerminatedArgumentType (registered here under namespace
            // `rtp:wsword`) made the server's command tree contain a non-vanilla
            // type, which caused vanilla clients to be kicked on join with
            // "This server requires Fabric Loader and Fabric API installed on
            // your client!". The bridge now uses only vanilla
            // StringArgumentType.greedyString() and tokenises the captured
            // string server-side, so vanilla clients can join Fabric servers
            // running RTP without any client-side mod.
            //
            // Do NOT re-introduce ArgumentTypeRegistry.registerArgumentType
            // here without first checking that the registered class is one
            // vanilla clients already know — anything under the `rtp:` (or any
            // non-`brigadier:` / non-`minecraft:`) namespace will re-trigger
            // the kick.
            // ----------------------------------------------------------------

            // ----------------------------------------------------------------
            // Brigadier registration of the /rtp root.
            // Permissions use fabric-permissions-api when available (always-true predicate here so
            // any player can invoke /rtp during initial smoke testing).
            // Full subcommand/parameter parity is Step G2 follow-up — see
            // RTPCmdFabricRoot Javadoc for the parity TODO checklist.
            // ----------------------------------------------------------------
            RTPCmdFabricRoot root = new RTPCmdFabricRoot();
            // Register the `rtp test` runtime self-test subtree on Fabric.
            // We use the platform-neutral TestCmd directly (NOT BukkitTestCmd)
            // because four of its subcommands hard-import org.bukkit.* /
            // commandsapi.bukkit.* / rtp.bukkitplatform.* and would NoClassDefFoundError
            // on a Fabric runtime. See TestCmd Javadoc for the constructor
            // split rationale; this path covers the Step G2 follow-up flagged
            // in MULTI_PLATFORM_PLAN.md and the RTPCmdFabricRoot Javadoc.
            try {
                root.addSubCommand(
                        new io.github.dailystruggle.rtp.bukkit.commands.test.TestCmd(root));
            } catch (Throwable t) {
                // S-004: never silently swallow. Log and continue — losing
                // `rtp test` on Fabric must not abort the whole mod init.
                RTP.log(Level.WARNING,
                        "[RTP][fabric] failed to register `rtp test` subtree: "
                                + t.getMessage(), t);
            }
            RTP.baseCommand = root;

            BrigadierBridgeContext<Object> bridgeCtx =
                    new BrigadierBridgeContext<>(
                            io.github.dailystruggle.rtp.fabric.tools.FabricBrigadierSourceBridge::resolveSenderUuid,
                            // Permission gating: defer to RTP.serverAccessor.getSender(uuid)
                            // .hasPermission(perm), which on Fabric routes through
                            // FabricRTPPlayer.hasPermission — that consults
                            // fabric-permissions-api first (LuckPerms-Fabric, etc.) and
                            // falls back to the vanilla op-level check by reading
                            // ops.json via stable APIs (see FabricRTPPlayer Javadoc).
                            // Plugin.yml is the source of truth for which nodes default
                            // to op vs. true vs. false (rtp.use=true, rtp.reload=op,
                            // rtp.scan=op, rtp.config=op, rtp.other=op, rtp.world=op,
                            // rtp.region=op, rtp.biome=op, rtp.params=op, ...).
                            // Non-player sources (console / command blocks / serverId
                            // sentinel) are treated as fully privileged — same as Bukkit
                            // ConsoleCommandSender.hasPermission() returning true.
                            io.github.dailystruggle.rtp.fabric.tools.FabricBrigadierSourceBridge::checkPermission,
                            (src, msg) -> {
                                if (msg == null) return;
                                // Delegate to FabricServerAccessor.sendMessage, which routes
                                // through FabricRTPPlayer.sendMessage — the canonical
                                // NM-touching chat path in rtp-fabric-common. Keeping the
                                // entrypoint class free of net.minecraft.network.chat /
                                // net.minecraft.network.protocol references avoids JVM
                                // class-verification failures on MC versions where the
                                // intermediary names for those types (e.g. class_2596 =
                                // Packet) are not exposed on the runtime classpath
                                // (rtp-fabric-ADR-002, rtp-fabric-ADR-007). The 26.1
                                // deobfuscated release was the first to surface this as
                                // a hard NoClassDefFoundError on entrypoint load.
                                try {
                                    UUID uuid = io.github.dailystruggle.rtp.fabric.tools.FabricBrigadierSourceBridge.resolveSenderUuid(src);
                                    if (uuid != null && !uuid.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
                                        // Player source — formats placeholders + legacy
                                        // colour codes and dispatches through the player's
                                        // RTPCommandSender. tag is reserved for future
                                        // styled-tag wrapping; null is the documented
                                        // "no tag" value (see FabricServerAccessor#sendMessage).
                                        RTP.serverAccessor.sendMessage(uuid, msg, null);
                                    } else {
                                        // Console / command-block / serverId sentinel:
                                        // route through RTP.log so the message lands in
                                        // the server console with colour codes preserved
                                        // (RTPServerAccessor.log path), matching the prior
                                        // CommandSourceStack#sendSuccess behaviour.
                                        RTP.log(Level.INFO, msg);
                                    }
                                } catch (Throwable t) {
                                    RTP.log(Level.WARNING,
                                            "[RTP][trace] Brigadier sendMessage failed: " + t.getMessage());
                                }
                                RTP.log(Level.FINE,
                                        "[RTP][trace] Brigadier sendMessage delivered: " + msg);
                            });

            // Delegate the CommandRegistrationCallback registration to
            // FabricCommandRegistrar (rtp-fabric-common). This keeps
            // CommandBuildContext (class_7157), CommandSelection, and
            // CommandSourceStack (class_2168) out of this entrypoint's
            // constant pool — those intermediary names are not exposed on
            // MC 26.1's deobfuscated runtime and would otherwise fail JVM
            // verify on entrypoint load (rtp-fabric-ADR-002 / ADR-007).
            io.github.dailystruggle.rtp.fabric.commands.FabricCommandRegistrar
                    .registerRtpCommand(root, bridgeCtx);

            // ----------------------------------------------------------------
            // Step E3-3 — non-Folia ChunkUnloadProcessor timer.
            // Mirrors RTPBukkitPlugin.onEnable's `if (!isFolia()) ...` branch.
            // Fabric has no Folia-style region threading, so the non-Folia
            // branch always applies. Without this, chunks loaded by the
            // teleport pipeline with keep(true) are never released and
            // MemoryTracker tickets accumulate (see ADR-022 §4 / S-002).
            // ----------------------------------------------------------------
            RTP.scheduler.runTaskTimer(
                    new io.github.dailystruggle.rtp.common.tasks.ChunkUnloadProcessor(),
                    1, 1);

            // ----------------------------------------------------------------
            // Per-adapter periodic ticket refresh — only adapters that use
            // auto-expiring tickets (1.21.5+, see rtp-fabric-ADR-004) override
            // tickRefresh(); for all others this is a no-op. Period 100 ticks
            // (5 s) is comfortably below the R5 adapter's REFRESH_TICKS_LEFT
            // (200 = 10 s), so each held chunk always has ≥ 5 s remaining
            // lifetime even if a refresh is delayed by a tick or two.
            // ----------------------------------------------------------------
            RTP.scheduler.runTaskTimer(() -> {
                FabricVersionAdapter adapter = FabricVersionAdapterRegistry.peek();
                if (adapter != null) {
                    try {
                        adapter.tickRefresh();
                    } catch (Throwable t) {
                        RTP.log(Level.WARNING,
                                "[RTP] FabricVersionAdapter.tickRefresh failed: "
                                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                }
            }, 100, 100);

            // ----------------------------------------------------------------
            // Step E3-6 — drain RTP.startupTasks the same way Bukkit does.
            // The RTP() constructor enqueues region parsing, world rebind,
            // scan-task seeding, and softdepend probes onto startupTasks
            // rather than running them inline. Without these drains the
            // region prefill never starts, keptLocations / unkeptLocations
            // stay empty, and /rtp falls through to "no location available"
            // (or hangs in the per-player polling loop).
            //
            //   drain #1: synchronous, immediately
            //   drain #2: scheduled +1 tick (deferred work needing live server)
            //   drain #3: synchronous post-banner
            //
            // Mirrors RTPBukkitPlugin.onEnable lines ~130 / ~141 / ~184 and
            // BootstrapSupport.drainStartupTasks. Empty drains are no-ops, so
            // matching Bukkit's three-drain pattern is cheap insurance.
            // ----------------------------------------------------------------
            if (rtp != null) {
                while (rtp.startupTasks.size() > 0) {
                    rtp.startupTasks.execute(Long.MAX_VALUE);
                }
                RTP.scheduler.runTaskLater(() -> {
                    RTP r2 = RTP.getInstance();
                    if (r2 != null) {
                        while (r2.startupTasks.size() > 0) {
                            r2.startupTasks.execute(Long.MAX_VALUE);
                        }
                    }
                }, 1);
                while (rtp.startupTasks.size() > 0) {
                    rtp.startupTasks.execute(Long.MAX_VALUE);
                }
            }

            // ----------------------------------------------------------------
            // Step E3-4 — seed <configDir>/rtp/docs/ from the bundled docs/
            // tree inside the running mod jar. Mirrors RTPBukkitPlugin's
            // `JarUtils.extractDocs(getDataFolder(), version)` call so the
            // admin-facing reference material (architecture diagrams,
            // requirements, ADRs) ships alongside the config tree on Fabric.
            // Idempotent + fail-soft — see FabricJarUtils Javadoc.
            // ----------------------------------------------------------------
            try {
                String modVersion = FabricLoader.getInstance()
                        .getModContainer("rtp")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString())
                        .orElse("unknown");
                io.github.dailystruggle.rtp.fabric.utils.FabricJarUtils
                        .extractDocs(accessor.getPluginDirectory(), modVersion);
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[RTP] FabricJarUtils.extractDocs dispatch failed: "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
            }

            RTP.log(Level.INFO,
                    "[RTP] Fabric entry point initialized — event bridge + /rtp Brigadier root registered.");
        } catch (Throwable t) {
            // Loud failure; ModInitializer swallowing exceptions would leave
            // the mod silently non-functional, violating REQ-RTP-S-004.
            System.err.println("[RTP] Fabric onInitialize failed:");
            t.printStackTrace();
            throw new RuntimeException("RTP Fabric initialization failed", t);
        }
    }



    /**
     * rtp-fabric-ADR-001 — classify the running MC version and reflectively instantiate
     * the matching {@link FabricVersionAdapter} from the appropriate
     * {@code rtp-fabric-vXX_YY_R1} submodule, then install it into
     * {@link FabricVersionAdapterRegistry}.
     *
     * <p><b>Why reflection (and not a direct {@code new}):</b> the v26_1_R1
     * adapter is compiled to Java 25 bytecode (MC 26.1's mandated minimum).
     * Servers running on a Java 21 JVM cannot resolve Java 25 classes —
     * they would throw {@code UnsupportedClassVersionError} the moment the
     * JVM tried to verify the class. Class loading in the JVM is lazy: a
     * class that is never named directly is never resolved. Looking up by
     * string via {@link Class#forName} after the version check guarantees
     * the JVM only ever resolves the class for its own runtime.</p>
     *
     * <p>If the running MC version doesn't match any of the supported
     * v-submodule lines, this logs a warning per S-006 (no silent no-op)
     * and re-throws — without an installed adapter, downstream version-
     * sensitive call sites would all fail with
     * {@link IllegalStateException} from {@code FabricVersionAdapterRegistry.require()}.
     * Failing here at bootstrap is louder and easier to diagnose.</p>
     */
    private static void installVersionAdapter() {
        // Resolve MC version via FabricLoader rather than SharedConstants.
        // SharedConstants.getCurrentVersion().getName() goes through Minecraft
        // bytecode whose intermediary mapping (e.g. class_6489.method_48019)
        // shifts between MC releases, causing NoSuchMethodError on adjacent
        // patch versions. FabricLoader's mod-container metadata is stable
        // across MC versions and is the recommended source per the Fabric
        // wiki ("Mappings" — intermediary names for MC internals are not
        // guaranteed stable; loader API is).
        String mcVersion;
        try {
            mcVersion = FabricLoader.getInstance()
                    .getModContainer("minecraft")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElseThrow(() -> new IllegalStateException(
                            "FabricLoader reports no 'minecraft' mod container — "
                                    + "cannot select Fabric version adapter (rtp-fabric-ADR-001)."));
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "Unable to determine running Minecraft version via FabricLoader — "
                            + "cannot select Fabric version adapter (rtp-fabric-ADR-001).", t);
        }

        String adapterFqn = adapterFqnFor(mcVersion);
        if (adapterFqn == null) {
            throw new IllegalStateException(
                    "No Fabric version adapter is mapped for running MC version '" + mcVersion
                            + "'. Supported lines: 1.20.x, 1.21.x, 26.1.x, 26.2.x. See rtp-fabric-ADR-001.");
        }

        try {
            Class<?> cls = Class.forName(adapterFqn);
            Object instance = cls.getDeclaredConstructor().newInstance();
            FabricVersionAdapter adapter = (FabricVersionAdapter) instance;
            FabricVersionAdapterRegistry.install(adapter);
            // Per-version Loom-compiled effect dispatchers — replaces the
            // fragile reflective resolvers in effects-api on this MC version.
            // Default impl is no-op; only adapters that ship dispatchers
            // (currently v1_21_R11) actually do anything here. Failures must
            // not abort bootstrap — sound/particle are cosmetic and fall
            // back to the reflective path on error.
            try {
                adapter.installEffectsDispatchers();
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[RTP][Fabric] installEffectsDispatchers failed for adapter "
                                + adapter.getClass().getName() + " (mcVersion=" + adapter.mcVersion()
                                + "); effects-api will fall back to reflective dispatch: "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Fabric version adapter class '" + adapterFqn + "' not on classpath. "
                            + "The matching rtp-fabric-vXX_YY_R1 submodule must be included "
                            + "in the shaded jar for MC " + mcVersion + " (rtp-fabric-ADR-001).", e);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalStateException(
                    "Failed to instantiate Fabric version adapter '" + adapterFqn
                            + "' for MC " + mcVersion + " (rtp-fabric-ADR-001).", e);
        }
    }

    /**
     * Maps a Mojang MC version name (as reported by
     * {@code SharedConstants.getCurrentVersion().getName()}) to the FQN of
     * the {@link FabricVersionAdapter} implementation that handles that
     * MC line. Returns {@code null} if the version is unsupported.
     *
     * <p>Classification is by major-minor prefix; patch versions within a
     * line usually share the same adapter, but the 1.21 line is split at
     * patch 5 because Mojang refactored {@code DistanceManager} there
     * (4-arg {@code addRegionTicket} → {@code addTicket(long, Ticket)} —
     * see {@code rtp-fabric-ADR-004}). 1.21.0–1.21.4 → {@code v1_21_R1};
     * 1.21.5 onward → {@code v1_21_R5}. When a future MC line lands that
     * needs its own adapter (e.g. {@code v26_2_R1}), add a row here and
     * create the matching submodule.</p>
     */
    private static String adapterFqnFor(String mcVersion) {
        if (mcVersion == null) return null;
        if (mcVersion.startsWith("1.20")) {
            return "io.github.dailystruggle.rtp.fabric.v1_20_R1.V1_20_R1FabricVersionAdapter";
        }
        if (mcVersion.startsWith("1.21")) {
            // The DistanceManager / TicketType API broke twice in this line:
            //   1.21.0–1.21.4 → 4-arg `addRegionTicket(TicketType, ChunkPos, int, T)` (R1).
            //   1.21.5–1.21.10 → record `TicketType(long timeout, boolean persist, TicketUse use)`
            //                    + `ServerChunkCache#addTicketWithRadius` (R5; see ADR-004).
            //   1.21.11+ → record `TicketType(long expiryTicks, int flags)` with the
            //              `TicketUse` inner enum removed entirely (R11; see ADR-007 for
            //              the Mojmap-name-decoupling SPI refactor that unblocked this
            //              submodule's inclusion in the default build).
            int patch = patchOf121(mcVersion);
            if (patch >= 11) {
                return "io.github.dailystruggle.rtp.fabric.v1_21_R11.V1_21_R11FabricVersionAdapter";
            }
            if (patch >= 5) {
                return "io.github.dailystruggle.rtp.fabric.v1_21_R5.V1_21_R5FabricVersionAdapter";
            }
            return "io.github.dailystruggle.rtp.fabric.v1_21_R1.V1_21_R1FabricVersionAdapter";
        }
        if (mcVersion.startsWith("26.1")) {
            // Java 25 bytecode — never named on a Java 21 JVM, so never resolved.
            return "io.github.dailystruggle.rtp.fabric.v26_1_R1.V26_1_R1FabricVersionAdapter";
        }
        if (mcVersion.startsWith("26.2")) {
            // EARLY/EXPERIMENTAL MC 26.2 line. FabricLoader's friendly string
            // for the pre-release (e.g. "26.2-pre-3") begins with "26.2", so
            // this prefix match catches both the pre-release builds and the
            // eventual 26.2.x finals. Re-verify the pin and this matcher when
            // 26.2 ships final (rtp-fabric-ADR-014). Java 25 bytecode — never
            // named on a Java 21 JVM, so never resolved there.
            return "io.github.dailystruggle.rtp.fabric.v26_2_R1.V26_2_R1FabricVersionAdapter";
        }
        return null;
    }

    /**
     * Section C, C2 helper — invoke a zero-arg {@code int}-returning method
     * on {@code target} by trying each candidate name in order. Used by the
     * {@code FabricMetricsBinding} suppliers to resolve mojmap /
     * intermediary aliases for {@code MinecraftServer.getPlayerCount()} and
     * {@code getMaxPlayers()} without a hard compile-time pin on either
     * mapping. Returns {@code 0} when {@code target} is {@code null} (the
     * server has not yet bound — pre-SERVER_STARTED state) or when no
     * candidate resolves; throws are swallowed and the next candidate is
     * tried. Callers (the binding) clamp negative results to {@code 0}.
     */
    static int reflectIntZeroArg(Object target, String[] candidates) {
        if (target == null) return 0;
        for (String name : candidates) {
            try {
                java.lang.reflect.Method m = target.getClass().getMethod(name);
                Object v = m.invoke(target);
                if (v instanceof Number n) return n.intValue();
            } catch (NoSuchMethodException ignored) {
                // try next candidate
            } catch (Throwable ignored) {
                // best-effort: a single failing candidate must not block the binding
            }
        }
        return 0;
    }

    /**
     * Parse the patch component of a {@code 1.21.x} version string.
     * Returns {@code 0} for {@code "1.21"} (no patch component) and the
     * integer value of the third dot-separated component otherwise.
     * Unparseable suffixes (e.g. {@code "1.21.5-rc1"}) are tolerated by
     * stripping non-digits before parsing. Returns {@code 0} on any failure
     * — callers default to the R1 adapter in that case.
     */
    private static int patchOf121(String mcVersion) {
        // mcVersion guaranteed to startWith("1.21") by callers.
        if (mcVersion.length() < 5) return 0; // exactly "1.21"
        if (mcVersion.charAt(4) != '.') return 0; // e.g. "1.210" — not actually 1.21.x
        String tail = mcVersion.substring(5);
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < tail.length(); i++) {
            char ch = tail.charAt(i);
            if (ch < '0' || ch > '9') break;
            digits.append(ch);
        }
        if (digits.length() == 0) return 0;
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }
}
