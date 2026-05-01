package io.github.dailystruggle.rtp.fabric.events;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.fabric.database.FabricDatabaseHandler;
import io.github.dailystruggle.rtp.fabric.scheduling.FabricScheduler;
import io.github.dailystruggle.rtp.fabric.server.FabricServerAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.logging.Level;

/**
 * Wires Fabric server-side events into the {@link FabricServerAccessor} maps,
 * the {@link FabricScheduler} tick loop, and the {@link FabricDatabaseHandler}
 * lifecycle. Replaces what {@code PluginEventListener} (Bukkit-family) does.
 *
 * <p><b>Step E2 scope.</b> Lifecycle (server start/stop), per-world load/unload,
 * tick driver, and player join/disconnect. Permissions stay in Step F; world-
 * border / shape function plumbing in later sub-steps.
 *
 * <p><b>Memory hygiene (REQ-RTP-S-004).</b> Player disconnect drops the
 * wrapper from the accessor map and unbinds the underlying handle so the
 * teleport pipeline cannot leak entity references past session end. World
 * unload removes the {@code FabricRTPWorld} entry; chunk-ticket release
 * lands with Step C's {@code setForceLoadedImpl}.
 *
 * <p><b>No Bukkit imports.</b> ADR-022 §4 invariant.
 */
public final class FabricEventBridge {

    private final FabricServerAccessor accessor;

    public FabricEventBridge(FabricServerAccessor accessor) {
        this.accessor = accessor;
    }

    /** Register all callbacks. Called from {@code RTPFabricMod.onInitialize()}. */
    public void register() {
        // ── Server lifecycle ────────────────────────────────────────────
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        // ── Per-tick driver for FabricScheduler ─────────────────────────
        ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);

        // ── World cache ─────────────────────────────────────────────────
        ServerWorldEvents.LOAD.register(this::onWorldLoad);
        ServerWorldEvents.UNLOAD.register(this::onWorldUnload);

        // ── Player session ──────────────────────────────────────────────
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                accessor.registerPlayer(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                accessor.unregisterPlayer(handler.getPlayer().getUUID()));
    }

    private void onServerStarted(MinecraftServer server) {
        try {
            accessor.bindServer(server);
            // Register every already-loaded ServerLevel — overworld + nether + end
            // (and any datapack dimensions) come up before SERVER_STARTED fires.
            for (ServerLevel level : server.getAllLevels()) {
                accessor.registerWorld(level);
            }
            // Initialize the database now that the server is up; mirrors
            // BukkitDatabaseHandler being kicked from onEnable().
            FabricDatabaseHandler.setupDatabase(RTP.getInstance());
        } catch (Throwable t) {
            // Fail-loud per REQ-RTP-S-004; never silently swallow.
            RTP.log(Level.SEVERE, "[RTP] FabricEventBridge.onServerStarted failed", t);
        }
    }

    private void onServerStopping(MinecraftServer server) {
        try {
            // Flush + close DB before clearing accessor state so any pending
            // writes complete (LESSONS_LEARNED.md 2026-04-18 — shutdown flush).
            if (RTP.getInstance() != null) {
                RTP.stop();
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] FabricEventBridge.onServerStopping: RTP.stop() raised", t);
        } finally {
            accessor.unbindServer();
        }
    }

    private void onEndServerTick(MinecraftServer server) {
        // Drives FabricScheduler's delayed/repeating queue. Throwables inside
        // tasks are caught by the scheduler itself; a throw here would crash
        // the tick loop.
        try {
            ((FabricScheduler) accessor.getScheduler()).tick(server);
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] FabricEventBridge tick raised", t);
        }
    }

    private void onWorldLoad(MinecraftServer server, ServerLevel level) {
        try {
            accessor.registerWorld(level);
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] FabricEventBridge.onWorldLoad failed for "
                    + level.dimension().location(), t);
        }
    }

    private void onWorldUnload(MinecraftServer server, ServerLevel level) {
        try {
            accessor.unregisterWorld(level);
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] FabricEventBridge.onWorldUnload failed for "
                    + level.dimension().location(), t);
        }
    }
}
