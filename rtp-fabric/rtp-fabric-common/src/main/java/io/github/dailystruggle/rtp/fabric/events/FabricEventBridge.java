package io.github.dailystruggle.rtp.fabric.events;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.pvp.PvPGate;
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

        // ── PvP combat tag (ADR-055) ─────────────────────────────────
        // Feed the native PvP combat tracker from ServerLivingEntityEvents.
        // Reflection-guarded for the same link-safety reason as above; the
        // whole handler is a no-op unless the operator enabled the gate.
        registerLivingEntityDamageEventsReflective();
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
                                if (player != null) {
                                    accessor.registerPlayerObject(player);
                                    // ADR-023 — drive the join-time RTP path
                                    // (cooldown / permission gate +
                                    // primeFromLoginCache + teleportAction).
                                    // Routed through an Object-typed bridge
                                    // so this proxy's synthetic class does
                                    // not pin ServerPlayer (intermediary
                                    // class_3222) — see the JOIN proxy
                                    // comment above.
                                    dispatchJoinRtp(player);
                                    // ADR-049 — fan a UUID join event out to
                                    // platform-agnostic subscribers (e.g. the
                                    // network-mode reservation-redeem path).
                                    accessor.getFabricPlayerLifecycleHook()
                                            .fireJoinFromPlayer(player);
                                }
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
                                if (player != null) {
                                    // ADR-049 — fan UUID quit out before the
                                    // accessor forgets the player wrapper, so
                                    // subscribers (network-mode waitlist
                                    // cleanup, etc.) can still resolve the
                                    // UUID off the live ServerPlayer.
                                    accessor.getFabricPlayerLifecycleHook()
                                            .fireQuitFromPlayer(player);
                                    // ADR-055 — forget any native PvP combat tag
                                    // so the tracker does not leak across sessions.
                                    clearPvpCombatTag(player);
                                    accessor.unregisterPlayerObject(player);
                                }
                            }
                            // ADR-023 — Login Reserve Cache refill on quit.
                            // Mirror of OnPlayerQuit (Bukkit): for every region with a
                            // login buffer, dispatch one async promotion to top it up.
                            // Fail-soft: never throw from the disconnect handler.
                            refillLoginReserveOnQuit();
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

            // ADR-023 — Login Reserve Cache: allocate the buffer on the
            // default-world region and dispatch the startup burst. Mirrors
            // RTPBukkitPlugin.initLoginReserveCache(); decoupled from
            // Region.execute() per ADR-023.
            initLoginReserveCache(server);
        } catch (Throwable t) {
            // Fail-loud per REQ-RTP-S-004; never silently swallow.
            RTP.log(Level.SEVERE, "[RTP] FabricEventBridge.onServerStarted failed", t);
        }
    }

    /**
     * ADR-023 — initialise the Login Reserve Cache on the default-world
     * (overworld) region when {@code PerformanceKeys.loginCacheEnabled=true}.
     * Sized to {@code loginCacheCap} (or {@code MinecraftServer.getMaxPlayers()}
     * when {@code loginCacheCap=0}). Mirror of
     * {@code RTPBukkitPlugin.initLoginReserveCache()}; fail-soft on every
     * failure path because join-time RTP must keep working even if the reserve
     * cannot be allocated.
     *
     * <p>Default-world identification: uses the overworld dimension's resource
     * location ({@code minecraft:overworld} on vanilla; modpacks may rename).
     * The method name has drifted across MC versions ({@code overworld},
     * intermediary {@code method_30002}) so the call is reflective.
     *
     * <p>Player count: {@code MinecraftServer.getMaxPlayers()} (mojmap) /
     * intermediary {@code method_3802}. Resolved reflectively.
     */
    @SuppressWarnings("unchecked")
    private void initLoginReserveCache(MinecraftServer server) {
        try {
            io.github.dailystruggle.rtp.common.configuration.ConfigParser<
                    io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys> perf =
                    (io.github.dailystruggle.rtp.common.configuration.ConfigParser<
                            io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys>)
                            RTP.configs.getParser(
                                    io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.class);
            if (perf == null) return;
            Object enabledObj = perf.getConfigValue(
                    io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.loginCacheEnabled,
                    false);
            boolean enabled = (enabledObj instanceof Boolean)
                    ? (Boolean) enabledObj
                    : Boolean.parseBoolean(String.valueOf(enabledObj));
            if (!enabled) return;

            long configuredCap = perf.getNumber(
                    io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.loginCacheCap,
                    0L).longValue();
            int cap;
            if (configuredCap > 0) {
                cap = (int) Math.min(configuredCap, Integer.MAX_VALUE);
            } else {
                // C6 (Section C of CHECKLIST-metrics-and-multiserver) — prefer
                // the M2 snapshot when a FabricMetricsBinding is installed; fall
                // back to the reflective Mojang/intermediary probe (which the
                // binding itself already uses internally) on the M0 NOOP path.
                int snapCap = 0;
                try {
                    snapCap = RTP.metrics.snapshot().softCap;
                } catch (Throwable ignored) {
                    // snapshot() is non-throwing by contract; defensive.
                }
                cap = (snapCap > 0) ? snapCap : resolveMaxPlayers(server);
            }
            if (cap <= 0) {
                RTP.log(Level.WARNING,
                        "[ADR-023] login cache enabled but resolved cap <= 0; skipping");
                return;
            }

            String defaultWorldName = resolveOverworldName(server);
            if (defaultWorldName == null) {
                RTP.log(Level.WARNING,
                        "[ADR-023] login cache enabled but overworld dimension could not"
                                + " be resolved on this MC runtime; skipping");
                return;
            }

            io.github.dailystruggle.rtp.common.selection.region.Region region =
                    RTP.selectionAPI.permRegionLookup.values().stream()
                            .filter(r -> r.getWorld() != null
                                    && defaultWorldName.equals(r.getWorld().name()))
                            .findFirst()
                            .orElse(null);
            if (region == null) {
                RTP.log(Level.WARNING,
                        "[ADR-023] login cache enabled but no region attached to default"
                                + " world '" + defaultWorldName + "'; skipping");
                return;
            }

            region.queueManager.enableLoginCache(cap);
            RTP.log(Level.INFO,
                    "[ADR-023] login reserve cache enabled on region '" + region.name
                            + "' cap=" + cap + " (default world '" + defaultWorldName + "')");

            // Startup burst: dispatch up to (cap - currentOnline) async promotions.
            // C6 — prefer the M2 snapshot when bound; fall back to the
            // reflective intermediary lookup when the binding is the NOOP
            // (snapshot.playerCount == 0 also matches the empty-server case
            // a freshly-started Fabric runtime returns, so the two paths
            // agree at bootstrap).
            int online;
            try {
                int snapOnline = RTP.metrics.snapshot().playerCount;
                online = (snapOnline > 0) ? snapOnline : resolveOnlinePlayerCount(server);
            } catch (Throwable ignored) {
                online = resolveOnlinePlayerCount(server);
            }
            int target = Math.max(0, cap - online);
            if (target > 0) {
                new io.github.dailystruggle.rtp.common.selection.region.LoginCacheTask(region)
                        .promoteUpTo(target);
                RTP.log(Level.FINE,
                        "[ADR-023] startup burst dispatched count=" + target);
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[ADR-023] login reserve cache init failed: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    /**
     * ADR-023 — Object-typed bridge into {@link FabricOnEventTeleports#onJoin}.
     * Centralised here (rather than directly in the JOIN proxy) so the
     * proxy's synthetic class does not pin {@code ServerPlayer} (intermediary
     * {@code class_3222}) into its bytecode constant pool — same pattern as
     * {@code FabricServerAccessor#registerPlayerObject}, see comment on the
     * JOIN proxy. The {@code ServerPlayer} cast lives here, where
     * {@code ServerPlayer} is already on the resolved-class set.
     *
     * <p>Fail-soft: any failure (cast miss, accessor not bound, perms-api
     * error) must not prevent the player from completing JOIN.
     */
    private void dispatchJoinRtp(Object player) {
        try {
            if (player == null) return;
            MinecraftServer server = accessor.getServer();
            if (server == null) return;
            // Pass the player as Object — FabricOnEventTeleports.onJoin resolves
            // UUID via the FabricVersionAdapter SPI and display name reflectively.
            // Naming ServerPlayer here would pin intermediary class_3222 into the
            // obf-carrier bytecode and fail to link on MC 26.1 deobf runtimes.
            FabricOnEventTeleports.onJoin(server, player);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP] FabricEventBridge.dispatchJoinRtp failed: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    /**
     * ADR-023 — refill the login reserve cache on player disconnect.
     * Iterates {@code RTP.selectionAPI.permRegionLookup} and dispatches one
     * async {@code LoginCacheTask.promoteUpTo(1)} per region whose
     * {@code loginLocations} buffer is non-null. Mirror of the Bukkit
     * {@code OnPlayerQuit} handler. Fail-soft per-region: a throw from one
     * region must not stop refill of the others.
     */
    private static void refillLoginReserveOnQuit() {
        try {
            if (RTP.getInstance() == null || RTP.selectionAPI == null) return;
            for (io.github.dailystruggle.rtp.common.selection.region.Region region
                    : RTP.selectionAPI.permRegionLookup.values()) {
                try {
                    if (region == null) continue;
                    if (region.queueManager == null) continue;
                    if (region.queueManager.loginLocations == null) continue;
                    new io.github.dailystruggle.rtp.common.selection.region.LoginCacheTask(region)
                            .promoteUpTo(1);
                } catch (Throwable t) {
                    RTP.log(Level.WARNING,
                            "[ADR-023] login reserve refill failed for region '"
                                    + (region == null ? "null" : region.name) + "': "
                                    + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[ADR-023] login reserve refill: outer failure "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Resolve the overworld dimension's resource location (e.g.
     * {@code minecraft:overworld}) reflectively to tolerate mapping drift
     * across MC versions ({@code overworld} mojmap vs. intermediary
     * {@code method_30002}). Returns {@code null} if no candidate resolves.
     */
    private static String resolveOverworldName(MinecraftServer server) {
        if (server == null) return null;
        String[] candidates = { "overworld", "method_30002" };
        for (String name : candidates) {
            try {
                java.lang.reflect.Method m = server.getClass().getMethod(name);
                Object level = m.invoke(server);
                if (level instanceof ServerLevel sl) {
                    return sl.dimension().location().toString();
                }
                if (level != null) {
                    // Some runtimes (26.1 deobf) may not link ServerLevel here.
                    // Reflectively walk: level.dimension().location().toString().
                    Object dim = level.getClass().getMethod("dimension").invoke(level);
                    if (dim == null) continue;
                    Object loc = dim.getClass().getMethod("location").invoke(dim);
                    if (loc == null) continue;
                    return loc.toString();
                }
            } catch (NoSuchMethodException ignored) {
                // try next
            } catch (Throwable t) {
                RTP.log(Level.WARNING, "[ADR-023] resolveOverworldName: " + name + " threw "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        return null;
    }

    /**
     * Resolve {@code MinecraftServer.getMaxPlayers()} reflectively
     * (mojmap {@code getMaxPlayers}, intermediary {@code method_3802}).
     * Returns {@code 0} when neither candidate resolves; the caller then
     * skips bootstrap with a WARNING.
     */
    private static int resolveMaxPlayers(MinecraftServer server) {
        if (server == null) return 0;
        String[] candidates = { "getMaxPlayers", "method_3802" };
        for (String name : candidates) {
            try {
                java.lang.reflect.Method m = server.getClass().getMethod(name);
                Object result = m.invoke(server);
                if (result instanceof Number n) return n.intValue();
            } catch (NoSuchMethodException ignored) {
                // try next
            } catch (Throwable t) {
                RTP.log(Level.WARNING, "[ADR-023] resolveMaxPlayers: " + name + " threw "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        return 0;
    }

    /**
     * Resolve the current online player count reflectively
     * (mojmap {@code getPlayerCount}, intermediary {@code method_3788}).
     * Returns {@code 0} when neither candidate resolves; the caller treats
     * the cap as the burst target.
     */
    private static int resolveOnlinePlayerCount(MinecraftServer server) {
        if (server == null) return 0;
        String[] candidates = { "getPlayerCount", "method_3788" };
        for (String name : candidates) {
            try {
                java.lang.reflect.Method m = server.getClass().getMethod(name);
                Object result = m.invoke(server);
                if (result instanceof Number n) return n.intValue();
            } catch (NoSuchMethodException ignored) {
                // try next
            } catch (Throwable ignored) {
                // best-effort
            }
        }
        return 0;
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
        // Section C, C2 — drive the metrics sampler if a FabricMetricsBinding
        // is installed. Best-effort; failures must not break the tick loop
        // (observability is never allowed to take down the pipeline). The
        // type probe is reflective so this module compiles without a hard
        // dependency on the binding class name beyond its package.
        try {
            if (RTP.getInstance() != null && RTP.metrics != null) {
                Object binding = RTP.metrics.getBinding();
                if (binding instanceof io.github.dailystruggle.rtp.fabric.metrics.FabricMetricsBinding fmb) {
                    fmb.tick();
                }
            }
        } catch (Throwable t) {
            RTP.log(Level.FINER,
                    "[RTP] FabricEventBridge metrics tick raised "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
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

    // -------------------------------------------------------------------------
    // ADR-055 — native PvP combat tag plumbing
    // -------------------------------------------------------------------------

    /**
     * Best-effort registration of {@code ServerLivingEntityEvents.AFTER_DAMAGE}
     * via reflection. Any failure (class missing, field missing, signature
     * drift across fabric-api / MC versions) is logged and swallowed so
     * {@code onInitialize} can still complete; the combat gate then has no
     * native authority on this runtime (an external combat-tag provider, if
     * bound, still works). Mirrors {@code OnPlayerCombatTag} on Bukkit.
     */
    private void registerLivingEntityDamageEventsReflective() {
        try {
            Class<?> sleeCls = Class.forName(
                    "net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents");
            Class<?> eventCls = Class.forName("net.fabricmc.fabric.api.event.Event");
            java.lang.reflect.Method register = eventCls.getMethod("register", Object.class);

            Class<?> afterDamageCallback = Class.forName(
                    "net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents$AfterDamage");
            Object afterDamageEvent = sleeCls.getField("AFTER_DAMAGE").get(null);

            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    afterDamageCallback.getClassLoader(),
                    new Class<?>[]{afterDamageCallback},
                    (p, method, args) -> {
                        // args[0] = LivingEntity victim, args[1] = DamageSource source.
                        try {
                            if (PvPGate.isEnabled()
                                    && args != null && args.length >= 2) {
                                onPvpAfterDamage(args[0], args[1]);
                            }
                        } catch (Throwable t) {
                            RTP.log(Level.FINER,
                                    "[RTP] FabricEventBridge AFTER_DAMAGE handler raised "
                                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                        }
                        return null;
                    });

            register.invoke(afterDamageEvent, proxy);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP][Fabric] ServerLivingEntityEvents.AFTER_DAMAGE not available ("
                            + t.getClass().getSimpleName() + "): native PvP combat tracking disabled."
                            + " The combat gate still works if an external provider is bound.");
        }
    }

    /**
     * Stamp the native PvP combat tracker for a player-vs-player damage event.
     * Resolves the responsible attacker (the source's owning entity, e.g. the
     * shooter of a projectile) reflectively and stamps victim / aggressor per
     * the {@code pvpTagVictim} / {@code pvpTagAggressor} knobs. Both objects are
     * NM-typed ({@code LivingEntity}, {@code DamageSource}), so this stays
     * reflective to keep {@code rtp-fabric-common} NM-free.
     *
     * @param victim the damaged {@code LivingEntity} (NM-typed)
     * @param source the {@code DamageSource} (NM-typed)
     */
    private void onPvpAfterDamage(Object victim, Object source) {
        java.util.UUID victimId = playerUuidOrNull(victim);
        if (victimId == null) return; // victim is not a player -> not PvP-relevant
        Object attacker = resolveDamageSourceEntity(source);
        java.util.UUID attackerId = playerUuidOrNull(attacker);
        if (attackerId == null) return; // environmental / mob damage
        if (attackerId.equals(victimId)) return; // self-damage

        long now = System.currentTimeMillis();
        if (PvPGate.tagVictim()) PvPGate.nativeTracker().stamp(victimId, now);
        if (PvPGate.tagAggressor()) PvPGate.nativeTracker().stamp(attackerId, now);
    }

    /**
     * Reflectively resolve the responsible entity behind a {@code DamageSource}
     * (mojmap {@code getEntity()} / intermediary {@code method_5529}). Returns
     * the projectile shooter / TNT igniter for indirect damage, the direct
     * attacker otherwise, or {@code null} when no entity is responsible.
     */
    private static Object resolveDamageSourceEntity(Object source) {
        if (source == null) return null;
        String[] candidates = { "getEntity", "method_5529" };
        for (String name : candidates) {
            try {
                java.lang.reflect.Method m = source.getClass().getMethod(name);
                return m.invoke(source);
            } catch (NoSuchMethodException ignored) {
                // try next
            } catch (Throwable ignored) {
                // best-effort; never throw into the event bus
            }
        }
        return null;
    }

    /**
     * Return the player UUID for {@code entity} when it is a {@code ServerPlayer},
     * else {@code null}. Class-name guards before the typed adapter call so a
     * non-player entity never trips a cast-failure log inside the adapter.
     */
    private static java.util.UUID playerUuidOrNull(Object entity) {
        if (entity == null) return null;
        if (!isServerPlayer(entity)) return null;
        try {
            io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
                    io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
            if (adapter == null) return null;
            return adapter.getPlayerUUID(entity);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Heuristic, mapping-agnostic "is this a server player?" check by walking the
     * class hierarchy for the mojmap ({@code ServerPlayer}) or intermediary
     * ({@code class_3222}) simple name. Avoids referencing the NM type directly.
     */
    private static boolean isServerPlayer(Object entity) {
        if (entity == null) return false;
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String simple = c.getSimpleName();
            if ("ServerPlayer".equals(simple) || "class_3222".equals(simple)) return true;
        }
        return false;
    }

    /** ADR-055 — clear a player's native combat tag on disconnect. */
    private static void clearPvpCombatTag(Object player) {
        try {
            java.util.UUID uuid = playerUuidOrNull(player);
            if (uuid != null) PvPGate.nativeTracker().clear(uuid);
        } catch (Throwable ignored) {
            // never throw from the disconnect handler
        }
    }
}
