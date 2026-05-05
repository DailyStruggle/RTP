package io.github.dailystruggle.rtp.fabric;

import io.github.dailystruggle.commandsapi.brigadier.BrigadierBridgeContext;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.fabric.commands.RTPCmdFabric;
import io.github.dailystruggle.rtp.fabric.commands.RTPCmdFabricRoot;
import io.github.dailystruggle.rtp.fabric.database.FabricDatabaseHandler;
import io.github.dailystruggle.rtp.fabric.events.FabricEventBridge;
import io.github.dailystruggle.rtp.fabric.server.FabricServerAccessor;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter;
import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Fabric entry point — single-JAR multi-loader bootstrap (ADR-022 §2).
 *
 * <p>Counterpart to {@code io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin}. Referenced from
 * {@code fabric.mod.json} as the {@code main} entrypoint. Per ADR-022 §4 (Architectural
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
 * FabricEventBridge, Brigadier-bridged command tree per ADR-014) is deferred to those steps.
 *
 * @see io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin
 */
public final class RTPFabricMod implements ModInitializer {

    @Override
    public void onInitialize() {
        // Phase 2 Step E2 + Step G G1 wiring. Body kept minimal per
        // REQ-RTP-NF-003 (applied per-entry-point under ADR-022 §2). Heavy
        // lifting is in FabricServerAccessor / FabricEventBridge / the
        // commands-api Brigadier adapter.
        try {
            // ----------------------------------------------------------------
            // ADR-027 — Fabric multiversion: select the per-MC version adapter
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
                                src.sendSystemMessage(Component.literal(msg));
                            });

            CommandRegistrationCallback.EVENT.register(
                    (dispatcher, registry, env) ->
                            RTPCmdFabric.register(dispatcher, root, bridgeCtx));

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
     * ADR-027 — classify the running MC version and reflectively instantiate
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
                                    + "cannot select Fabric version adapter (ADR-027)."));
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "Unable to determine running Minecraft version via FabricLoader — "
                            + "cannot select Fabric version adapter (ADR-027).", t);
        }

        String adapterFqn = adapterFqnFor(mcVersion);
        if (adapterFqn == null) {
            throw new IllegalStateException(
                    "No Fabric version adapter is mapped for running MC version '" + mcVersion
                            + "'. Supported lines: 1.20.x, 1.21.x, 26.1.x. See ADR-027.");
        }

        try {
            Class<?> cls = Class.forName(adapterFqn);
            Object instance = cls.getDeclaredConstructor().newInstance();
            FabricVersionAdapterRegistry.install((FabricVersionAdapter) instance);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Fabric version adapter class '" + adapterFqn + "' not on classpath. "
                            + "The matching rtp-fabric-vXX_YY_R1 submodule must be included "
                            + "in the shaded jar for MC " + mcVersion + " (ADR-027).", e);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalStateException(
                    "Failed to instantiate Fabric version adapter '" + adapterFqn
                            + "' for MC " + mcVersion + " (ADR-027).", e);
        }
    }

    /**
     * Maps a Mojang MC version name (as reported by
     * {@code SharedConstants.getCurrentVersion().getName()}) to the FQN of
     * the {@link FabricVersionAdapter} implementation that handles that
     * MC line. Returns {@code null} if the version is unsupported.
     *
     * <p>Classification is by major-minor prefix; patch versions within a
     * line share the same adapter (e.g. 1.21.1 / 1.21.3 / 1.21.5 all route
     * to {@code v1_21_R1}). When a future MC line lands that needs its own
     * adapter (e.g. {@code v26_2_R1}), add a row here and create the
     * matching submodule.</p>
     */
    private static String adapterFqnFor(String mcVersion) {
        if (mcVersion == null) return null;
        if (mcVersion.startsWith("1.20")) {
            return "io.github.dailystruggle.rtp.fabric.v1_20_R1.V1_20_R1FabricVersionAdapter";
        }
        if (mcVersion.startsWith("1.21")) {
            return "io.github.dailystruggle.rtp.fabric.v1_21_R1.V1_21_R1FabricVersionAdapter";
        }
        if (mcVersion.startsWith("26.1")) {
            // Java 25 bytecode — never named on a Java 21 JVM, so never resolved.
            return "io.github.dailystruggle.rtp.fabric.v26_1_R1.V26_1_R1FabricVersionAdapter";
        }
        return null;
    }
}
