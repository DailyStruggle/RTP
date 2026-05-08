package io.github.dailystruggle.rtp.fabric;

import io.github.dailystruggle.commandsapi.brigadier.BrigadierBridgeContext;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.fabric.commands.RTPCmdFabric;
import io.github.dailystruggle.rtp.fabric.commands.RTPCmdFabricRoot;
import io.github.dailystruggle.rtp.fabric.database.FabricDatabaseHandler;
import io.github.dailystruggle.rtp.fabric.events.FabricEventBridge;
import io.github.dailystruggle.rtp.fabric.server.FabricServerAccessor;
import io.github.dailystruggle.rtp.fabric.tools.FabricLegacyText;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;

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

    @Override
    public void onInitialize() {
        // Phase 2 Step E2 + Step G G1 wiring. Body kept minimal per
        // REQ-RTP-NF-003 (applied per-entry-point under rtp-fabric-ADR-002 §2). Heavy
        // lifting is in FabricServerAccessor / FabricEventBridge / the
        // commands-api Brigadier adapter.
        try {
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

            // Step D wiring — Configuration & Database setup. Mirrors
            // BukkitDatabaseHandler.setupDatabase invoked from
            // RTPBukkitPlugin.onEnable. Selects the DatabaseAccessor from
            // configs (sqlite default, h2/mysql/postgresql/yaml supported)
            // and schedules databaseAccessor.startup() via RTP.scheduler.
            try {
                FabricDatabaseHandler.setupDatabase(rtp);
            } catch (java.nio.file.FileSystemException fse) {
                RTP.log(Level.SEVERE,
                        "[RTP] Fabric database setup failed — RTP will run without persistence.", fse);
            }

            new FabricEventBridge(accessor).register();

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
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
                    .SERVER_STARTED.register(server -> {
                        try {
                            io.github.dailystruggle.rtp.fabric.effects.FabricEffectsHandler
                                    .setupEffects(server);
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
                    if (!(task.player() instanceof io.github.dailystruggle.rtp.fabric.player.FabricRTPPlayer fp)) {
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
                        fp.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
                        fp.sendActionbar(actionbar);
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
            // Step G G1 — Brigadier registration of the bare /rtp root.
            // Permissions deferred to Step F (always-true predicate here so
            // any player can invoke /rtp during initial smoke testing).
            // Full subcommand/parameter parity is Step G2 follow-up — see
            // RTPCmdFabricRoot Javadoc for the parity TODO checklist.
            // ----------------------------------------------------------------
            RTPCmdFabricRoot root = new RTPCmdFabricRoot();
            RTP.baseCommand = root;

            BrigadierBridgeContext<CommandSourceStack> bridgeCtx =
                    new BrigadierBridgeContext<>(
                            RTPFabricMod::resolveSenderUuid,
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
                            RTPFabricMod::checkBrigadierPermission,
                            (src, msg) -> {
                                if (msg == null) return;
                                // Route through FabricLegacyText so &-codes / #RRGGBB
                                // hex / placeholders pre-resolved upstream render with
                                // colour and don't show as raw "&c[...]" to the player
                                // (parity with FabricRTPPlayer.sendMessage path).
                                //
                                // NOTE: We deliberately avoid CommandSourceStack#sendSystemMessage —
                                // ServerPlayer#sendSystemMessage(Component) (intermediary
                                // class_3222.method_43496) drifts across MC patch releases
                                // (NoSuchMethodError on 1.21.11, same family as the
                                // hasPermission(int) drift). Send the system chat packet
                                // directly through the player's network connection where
                                // we have one; for non-player sources fall back to the
                                // CommandSourceStack#sendSuccess path which goes through
                                // a different, stable mapping.
                                Component component = FabricLegacyText.parse(msg);
                                try {
                                    if (src.getEntity() instanceof ServerPlayer p && p.connection != null) {
                                        p.connection.send(new ClientboundSystemChatPacket(component, false));
                                    } else {
                                        // Console / command-block source — sendSuccess is
                                        // stable and shows in the server log.
                                        src.sendSuccess(() -> component, false);
                                    }
                                } catch (Throwable t) {
                                    RTP.log(Level.WARNING,
                                            "[RTP][trace] Brigadier sendMessage failed: " + t.getMessage());
                                }
                                RTP.log(Level.FINE,
                                        "[RTP][trace] Brigadier sendMessage delivered: " + msg);
                            });

            CommandRegistrationCallback.EVENT.register(
                    (dispatcher, registry, env) -> {
                        RTP.log(Level.INFO,
                                "[RTP] Registering /rtp Brigadier root with dispatcher (env=" + env + ").");
                        RTPCmdFabric.register(dispatcher, root, bridgeCtx);
                    });

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
     * Bridge a Brigadier {@link CommandSourceStack} to the canonical caller UUID
     * used by {@code commands-api} / {@code rtp-core}. Players resolve to their
     * own UUID; non-player sources (console, command blocks) collapse to the
     * sentinel {@link RTPAPI#serverId} — same convention as the Bukkit side.
     */
    private static UUID resolveSenderUuid(CommandSourceStack src) {
        if (src.getEntity() instanceof ServerPlayer player) {
            return player.getUUID();
        }
        return RTPAPI.serverId;
    }

    /**
     * Permission predicate for the Brigadier bridge. Mirrors the Bukkit-side
     * {@code CommandSender.hasPermission(node)} contract:
     *
     * <ul>
     *   <li>Non-player sources (console, command blocks, the {@code serverId}
     *       sentinel) bypass permission checks — Bukkit's
     *       {@code ConsoleCommandSender.hasPermission()} is unconditionally
     *       {@code true}, and plugin.yml's defaults are written assuming that.
     *   </li>
     *   <li>Player sources route through
     *       {@code RTP.serverAccessor.getSender(uuid).hasPermission(perm)},
     *       which on Fabric is implemented by
     *       {@link io.github.dailystruggle.rtp.fabric.player.FabricRTPPlayer#hasPermission(String)}.
     *       That implementation consults {@code fabric-permissions-api}
     *       (LuckPerms-Fabric / Cyan / Ledger) first and falls back to the
     *       vanilla op-level check (reading {@code ops.json} via stable
     *       APIs) when no permissions implementer is registered.
     *   </li>
     *   <li>A {@code null} or empty {@code permission} string is treated as
     *       "no permission required" ({@code true}), matching Bukkit's
     *       documented behaviour for {@code commands-api} parameters that
     *       elect not to declare a node.</li>
     * </ul>
     *
     * <p>The actual default for each node (op vs. true vs. false) is owned
     * by {@code rtp-plugin/src/main/resources/plugin.yml}; the Fabric side
     * inherits those defaults via the op-level fallback in
     * {@code FabricRTPPlayer.hasPermission}, so e.g. {@code rtp.reload},
     * {@code rtp.scan}, {@code rtp.config}, {@code rtp.other}, and the
     * {@code rtp.world*} / {@code rtp.region*} / {@code rtp.biome*} /
     * {@code rtp.params} families default to op-only on both platforms
     * without duplicating the table here.
     */
    private static boolean checkBrigadierPermission(CommandSourceStack src, String permission) {
        if (permission == null || permission.isEmpty()) return true;
        UUID uuid = resolveSenderUuid(src);
        // Non-player sources collapse to RTPAPI.serverId — treat as console
        // (full access). This also covers the early-init window where the
        // accessor may not yet have a sender entry for the sentinel.
        if (RTPAPI.serverId.equals(uuid)) return true;
        try {
            io.github.dailystruggle.rtp.api.entity.RTPCommandSender sender =
                    RTP.serverAccessor.getSender(uuid);
            if (sender == null) {
                // Player source whose sender entry has not yet been bound
                // (e.g., command issued before FabricEventBridge.onJoin
                // wired the player). Deny rather than silently allow —
                // matches the strict reading of plugin.yml op-only defaults.
                return false;
            }
            return sender.hasPermission(permission);
        } catch (Throwable t) {
            // Defensive: never let a permission lookup crash command parsing.
            // Failing closed (deny) is the safer choice for op-only nodes.
            RTP.log(Level.WARNING,
                    "[RTP] Brigadier permission check failed for '" + permission
                            + "' (uuid=" + uuid + "): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
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
                            + "'. Supported lines: 1.20.x, 1.21.x, 26.1.x. See rtp-fabric-ADR-001.");
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
        return null;
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
