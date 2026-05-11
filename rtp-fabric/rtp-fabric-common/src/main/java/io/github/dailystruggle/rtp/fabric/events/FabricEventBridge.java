package io.github.dailystruggle.rtp.fabric.events;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.fabric.database.FabricDatabaseHandler;
import io.github.dailystruggle.rtp.fabric.scheduling.FabricScheduler;
import io.github.dailystruggle.rtp.fabric.server.FabricServerAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
        // Reflection-guarded: ServerWorldEvents is supplied by fabric-lifecycle-events-v1,
        // which is not always present (e.g. trimmed runtimes / certain MC 26.1 fabric-api
        // builds where the class fails to resolve at link time). A hard reference here
        // aborts onInitialize with NoClassDefFoundError before any useful work happens.
        // Fall back to registering worlds on SERVER_STARTED only; per-world LOAD/UNLOAD
        // refresh is then unavailable but the server still boots and /rtp works for the
        // worlds present at start.
        registerWorldEventsReflective();

        // ── Player session ──────────────────────────────────────────────
        // Reflection-guarded for the same reason as world events: the JOIN/DISCONNECT
        // callback signatures reference ServerGamePacketListenerImpl (class_3244),
        // which can fail to resolve at link time on 26.1's deobfuscated runtime,
        // aborting onInitialize before any useful work happens.
        registerPlayConnectionEventsReflective();
    }

    /**
     * Extract the {@code ServerPlayer} from a {@code ServerGamePacketListenerImpl} (mojmap)
     * / {@code class_3244} (intermediary) handler by delegating to the active
     * {@link io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter}, which
     * provides a typed implementation appropriate to the runtime mapping
     * (mojmap {@code getPlayer()} on 26.x; intermediary {@code .player} field on 1.20.x → 1.21.x).
     *
     * <p>Returns {@code null} on adapter miss / unexpected error per S-006
     * fail-loud-but-survive policy. Callers must null-check.
     */
    // package-private for FabricEventBridgeAdapterDispatchTest
    static Object extractPlayerFromHandler(Object handler) {
        if (handler == null) return null;
        try {
            io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
                    io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
            if (adapter == null) {
                RTP.log(Level.WARNING,
                        "[RTP] FabricEventBridge: no FabricVersionAdapter registered; cannot extract player from "
                                + handler.getClass().getName());
                return null;
            }
            return adapter.extractPlayerFromConnection(handler);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP] FabricEventBridge: FabricVersionAdapter.extractPlayerFromConnection threw on "
                            + handler.getClass().getName(), t);
            return null;
        }
    }

    private void registerPlayConnectionEventsReflective() {
        try {
            Class<?> spce = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents");
            Class<?> eventCls = Class.forName("net.fabricmc.fabric.api.event.Event");
            java.lang.reflect.Method register = eventCls.getMethod("register", Object.class);

            Class<?> joinCallback = Class.forName(
                    "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents$Join");
            Class<?> disconnectCallback = Class.forName(
                    "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents$Disconnect");

            Object joinEvent = spce.getField("JOIN").get(null);
            Object disconnectEvent = spce.getField("DISCONNECT").get(null);

            Object joinProxy = java.lang.reflect.Proxy.newProxyInstance(
                    joinCallback.getClassLoader(),
                    new Class<?>[]{joinCallback},
                    (proxy, method, args) -> {
                        try {
                            if (args != null && args.length >= 1 && args[0] != null) {
                                Object handler = args[0];
                                Object player = extractPlayerFromHandler(handler);
                                // Dispatch through the Object-typed accessor entry point so this
                                // proxy's synthetic class does not reference ServerPlayer
                                // (intermediary class_3222), which is absent on MC 26.1.2's
                                // deobfuscated runtime and would fail JVM verify on instanceof.
                                if (player != null) accessor.registerPlayerObject(player);
                            }
                        } catch (Throwable t) {
                            RTP.log(Level.WARNING, "[RTP] FabricEventBridge JOIN handler failed", t);
                        }
                        return null;
                    });
            Object disconnectProxy = java.lang.reflect.Proxy.newProxyInstance(
                    disconnectCallback.getClassLoader(),
                    new Class<?>[]{disconnectCallback},
                    (proxy, method, args) -> {
                        try {
                            if (args != null && args.length >= 1 && args[0] != null) {
                                Object handler = args[0];
                                Object player = extractPlayerFromHandler(handler);
                                if (player != null) accessor.unregisterPlayerObject(player);
                            }
                        } catch (Throwable t) {
                            RTP.log(Level.WARNING, "[RTP] FabricEventBridge DISCONNECT handler failed", t);
                        }
                        return null;
                    });

            register.invoke(joinEvent, joinProxy);
            register.invoke(disconnectEvent, disconnectProxy);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric] ServerPlayConnectionEvents not available (" + t.getClass().getSimpleName()
                            + "): player join/disconnect tracking disabled."
                            + " Players will be registered lazily on first command use.");
        }
    }

    /**
     * Best-effort registration of {@code ServerWorldEvents.LOAD/UNLOAD} via reflection.
     * Any failure (class missing, field missing, signature drift) is logged and swallowed
     * so {@code onInitialize} can still complete. Worlds present at server start are
     * still picked up via {@link #onServerStarted(MinecraftServer)}; only mid-run
     * dimension hot-add/hot-remove is degraded when this path is unavailable.
     */
    private void registerWorldEventsReflective() {
        try {
            Class<?> swe = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents");
            Class<?> eventCls = Class.forName("net.fabricmc.fabric.api.event.Event");
            java.lang.reflect.Method register = eventCls.getMethod("register", Object.class);

            Class<?> loadCallback = Class.forName(
                    "net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents$Load");
            Class<?> unloadCallback = Class.forName(
                    "net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents$Unload");

            Object loadEvent = swe.getField("LOAD").get(null);
            Object unloadEvent = swe.getField("UNLOAD").get(null);

            Object loadProxy = java.lang.reflect.Proxy.newProxyInstance(
                    loadCallback.getClassLoader(),
                    new Class<?>[]{loadCallback},
                    (proxy, method, args) -> {
                        if (args != null && args.length >= 2
                                && args[0] instanceof MinecraftServer s
                                && args[1] instanceof ServerLevel lvl) {
                            onWorldLoad(s, lvl);
                        }
                        return null;
                    });
            Object unloadProxy = java.lang.reflect.Proxy.newProxyInstance(
                    unloadCallback.getClassLoader(),
                    new Class<?>[]{unloadCallback},
                    (proxy, method, args) -> {
                        if (args != null && args.length >= 2
                                && args[0] instanceof MinecraftServer s
                                && args[1] instanceof ServerLevel lvl) {
                            onWorldUnload(s, lvl);
                        }
                        return null;
                    });

            register.invoke(loadEvent, loadProxy);
            register.invoke(unloadEvent, unloadProxy);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric] ServerWorldEvents not available (" + t.getClass().getSimpleName()
                            + "): per-world LOAD/UNLOAD callbacks disabled."
                            + " Worlds loaded at server start are still registered via SERVER_STARTED.");
        }
    }

    /**
     * Resolve {@code MinecraftServer}'s collection of loaded {@code ServerLevel}s reflectively.
     * Method names drift across MC versions (e.g. {@code getAllLevels()}, {@code levels()},
     * {@code getWorlds()}, intermediary {@code method_3738}). Returns {@code null} if no
     * candidate resolved; callers must tolerate that and continue.
     */
    private static Iterable<?> resolveAllLevels(MinecraftServer server) {
        if (server == null) return null;
        String[] candidates = { "getAllLevels", "levels", "getWorlds", "getLevels", "method_3738" };
        for (String name : candidates) {
            try {
                java.lang.reflect.Method m = server.getClass().getMethod(name);
                Object result = m.invoke(server);
                if (result instanceof Iterable<?> it) return it;
            } catch (NoSuchMethodException ignored) {
                // try next
            } catch (Throwable t) {
                RTP.log(Level.WARNING, "[RTP][Fabric] resolveAllLevels: " + name + " threw "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        return null;
    }

    private void onServerStarted(MinecraftServer server) {
        try {
            accessor.bindServer(server);
            // Register every already-loaded ServerLevel — overworld + nether + end
            // (and any datapack dimensions) come up before SERVER_STARTED fires.
            // Method name has drifted across MC versions (getAllLevels / levels / getWorlds /
            // method_3738 / etc.), so resolve reflectively and tolerate failure.
            Iterable<?> levels = resolveAllLevels(server);
            if (levels != null) {
                // Dispatch through the Object-typed accessor entry point so this method's
                // bytecode does not reference net.minecraft.server.level.ServerLevel
                // (intermediary class_3218), which is not resolvable on MC 26.1's
                // deobfuscated runtime and would fail JVM verify here.
                for (Object level : levels) {
                    if (level == null) continue;
                    try {
                        accessor.registerWorldObject(level);
                    } catch (Throwable t) {
                        RTP.log(Level.WARNING, "[RTP][Fabric] registerWorldObject failed: "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                }
            } else {
                RTP.log(Level.WARNING, "[RTP][Fabric] Could not enumerate ServerLevels via any known accessor; "
                        + "per-world registration at SERVER_STARTED skipped. World load events will pick them up if available.");
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
