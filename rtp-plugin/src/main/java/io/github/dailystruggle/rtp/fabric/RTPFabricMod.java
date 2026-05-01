package io.github.dailystruggle.rtp.fabric;

import io.github.dailystruggle.commandsapi.brigadier.BrigadierBridgeContext;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.fabric.commands.RTPCmdFabric;
import io.github.dailystruggle.rtp.fabric.commands.RTPCmdFabricRoot;
import io.github.dailystruggle.rtp.fabric.database.FabricDatabaseHandler;
import io.github.dailystruggle.rtp.fabric.events.FabricEventBridge;
import io.github.dailystruggle.rtp.fabric.server.FabricServerAccessor;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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
            // Trigger lazy RTP construction so subsequent event-bridge calls
            // see a non-null instance. The constructor builds Configs from
            // accessor.getPluginDirectory(), so default configs land in
            // <fabric-config>/rtp/ on first run.
            RTP rtp = RTP.getInstance();

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
}
