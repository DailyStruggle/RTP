package io.github.dailystruggle.helpers.stresstestrtp;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Attributes completions from the plugin under test's <em>own</em> teleport
 * events, when it is LeafRTP (ADR-122 Decision 1 & 2).
 */
public final class DirectTeleportProbe {

    static final String PRE_EVENT_CLASS = "io.github.dailystruggle.rtp.bukkit.events.PreTeleportEvent";
    static final String POST_EVENT_CLASS = "io.github.dailystruggle.rtp.bukkit.events.PostTeleportEvent";
    static final double ARRIVAL_TOLERANCE_BLOCKS = 3.0;

    private final StressHost plugin;
    private final TeleportProbe probe;

    private final Class<?> preClass;
    private final Class<?> postClass;
    private final Method getDoTeleport;
    private final Method getDoTeleportPre;
    private final Method taskPlayer;
    private final Method playerUuid;
    private final Method taskCoords;
    private final Method coordsX;
    private final Method coordsZ;
    private final Method coordsWorld;

    private volatile boolean registered = false;
    private volatile boolean mismatchLogged = false;
    private Runnable unsubscribe;

    private final Map<UUID, Long> pendingPre = new ConcurrentHashMap<>();

    public DirectTeleportProbe(StressHost plugin, TeleportProbe probe) {
        this.plugin = plugin;
        this.probe = probe;
        Class<?> pre = null, post = null;
        Method doTeleport = null, doTeleportPre = null, player = null, uuid = null, coords = null, x = null, z = null,
                worldName = null;
        try {
            pre = findEventClass(plugin, PRE_EVENT_CLASS);
            post = findEventClass(plugin, POST_EVENT_CLASS);
            if (pre != null && post != null) {
                doTeleport = post.getMethod("getDoTeleport");
                doTeleportPre = pre.getMethod("getDoTeleport");
                Class<?> task = doTeleport.getReturnType();
                player = task.getMethod("player");
                uuid = player.getReturnType().getMethod("uuid");
                coords = task.getMethod("coords");
                x = coords.getReturnType().getMethod("x");
                z = coords.getReturnType().getMethod("z");
                try {
                    worldName = coords.getReturnType().getMethod("worldName");
                } catch (NoSuchMethodException ignored) {
                    worldName = null;
                }
            }
        } catch (NoSuchMethodException | SecurityException e) {
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().log(Level.WARNING,
                        "[StressTestRTP] LeafRTP events present but their shape changed; "
                                + "direct attribution disabled: " + e);
            }
            pre = null; post = null; doTeleportPre = null;
        }
        this.preClass = pre;
        this.postClass = post;
        this.getDoTeleport = doTeleport;
        this.getDoTeleportPre = doTeleportPre;
        this.taskPlayer = player;
        this.playerUuid = uuid;
        this.taskCoords = coords;
        this.coordsX = x;
        this.coordsZ = z;
        this.coordsWorld = worldName;
    }

    private static Class<?> findEventClass(StressHost host, String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ignored) {
        }
        if (host != null) {
            for (StressHost.LoadedPlugin p : host.loadedPlugins()) {
                if (p != null && p.classLoader() != null) {
                    try {
                        return Class.forName(className, false, p.classLoader());
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
        }
        return null;
    }

    public boolean available() { return preClass != null && postClass != null; }

    private volatile String ownerPluginName;
    private volatile boolean ownerResolved;

    public String ownerPluginName() {
        if (ownerResolved) return ownerPluginName;
        String resolved = null;
        if (available() && plugin != null) {
            ClassLoader cl = preClass.getClassLoader();
            for (StressHost.LoadedPlugin p : plugin.loadedPlugins()) {
                if (p != null && p.classLoader() == cl) { resolved = p.name(); break; }
            }
        }
        ownerPluginName = resolved;
        ownerResolved = true;
        return resolved;
    }

    public void register() {
        if (registered || !available() || plugin == null) return;
        unsubscribe = plugin.registerDirectTeleportEvents(preClass, this::onPre, postClass, this::onPost);
        if (unsubscribe == null) return;
        registered = true;
        plugin.getLogger().info("[StressTestRTP] LeafRTP teleport events detected; "
                + "attributing the rtp arm from PostTeleportEvent (attribution_source=PLUGIN_EVENT).");
    }

    public void unregister() {
        if (!registered) return;
        if (unsubscribe != null) {
            unsubscribe.run();
            unsubscribe = null;
        }
        registered = false;
    }

    private void onPre(Object event) {
        long now = System.currentTimeMillis();
        UUID id = playerOf(event, getDoTeleportPre);
        if (id == null) return;
        pendingPre.putIfAbsent(id, now);
    }

    private void onPost(Object event) {
        long now = System.currentTimeMillis();
        UUID id = playerOf(event);
        if (id == null) return;
        Long preTs = pendingPre.remove(id);
        Attempt attempt = probe.peek(id);
        if (attempt != null) {
            if (preTs != null && attempt.pluginPreTeleportEpochMs < 0) {
                attempt.pluginPreTeleportEpochMs = preTs;
            }
            attempt.pluginPostTeleportEpochMs = now;
        }
        double[] to = coordsOf(event);
        if (to == null) {
            return;
        }
        StressPlayer p = plugin != null ? plugin.player(id) : null;
        StressLocation at = p != null ? p.location() : null;
        String expectedWorld = worldOf(event);
        String fail = arrivalFailure(at, expectedWorld, to[0], to[1]);
        if (!fail.isEmpty() && !mismatchLogged) {
            mismatchLogged = true;
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().warning(String.format(java.util.Locale.ROOT,
                        "[StressTestRTP] first NOT_AT_DESTINATION on the rtp arm: expected %s (%.1f, %.1f), "
                                + "player at %s", expectedWorld, to[0], to[1], at));
            }
        }
        LandingInspector.Landing landing = fail.isEmpty() ? LandingInspector.inspect(plugin, at) : null;
        probe.attributeDirect(id, to[0], to[1], fail.isEmpty(), fail, landing);
    }

    /** Empty when the player is at the destination, else the fail reason. */
    static String arrivalFailure(StressLocation at, String expectedWorld, double toX, double toZ) {
        if (at == null || at.world().isEmpty()) return "NOT_AT_DESTINATION";
        if (expectedWorld != null && !expectedWorld.isEmpty()
                && !expectedWorld.equalsIgnoreCase(at.world())) {
            return "NOT_AT_DESTINATION";
        }
        double dx = at.x() - toX, dz = at.z() - toZ;
        return (dx * dx + dz * dz) <= ARRIVAL_TOLERANCE_BLOCKS * ARRIVAL_TOLERANCE_BLOCKS
                ? "" : "NOT_AT_DESTINATION";
    }

    private String worldOf(Object event) {
        if (coordsWorld == null) return null;
        try {
            Object task = getDoTeleport.invoke(event);
            Object coords = task != null ? taskCoords.invoke(task) : null;
            Object w = coords != null ? coordsWorld.invoke(coords) : null;
            return w instanceof String s ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private UUID playerOf(Object event) {
        return playerOf(event, getDoTeleport);
    }

    private UUID playerOf(Object event, Method doTeleportAccessor) {
        try {
            if (doTeleportAccessor == null) return null;
            Object task = doTeleportAccessor.invoke(event);
            if (task == null) return null;
            Object player = taskPlayer.invoke(task);
            if (player == null) return null;
            Object uuid = playerUuid.invoke(player);
            return uuid instanceof UUID u ? u : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private double[] coordsOf(Object event) {
        try {
            Object task = getDoTeleport.invoke(event);
            if (task == null) return null;
            Object coords = taskCoords.invoke(task);
            if (coords == null) return null;
            Object x = coordsX.invoke(coords);
            Object z = coordsZ.invoke(coords);
            if (x instanceof Number nx && z instanceof Number nz) {
                return new double[] { nx.doubleValue(), nz.doubleValue() };
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
