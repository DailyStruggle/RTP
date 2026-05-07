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
                            // G1: permission gating disabled for initial smoke test.
                            // Step F replaces with fabric-permissions-api lookup.
                            (src, perm) -> true,
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
            FabricVersionAdapterRegistry.install((FabricVersionAdapter) instance);
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
