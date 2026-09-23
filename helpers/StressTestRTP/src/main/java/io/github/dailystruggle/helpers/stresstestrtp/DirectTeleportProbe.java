package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Attributes completions from the plugin under test's <em>own</em> teleport
 * events, when it is LeafRTP.
 *
 * <p><b>Why.</b> {@link TeleportProbe} observes a landing from the outside:
 * Bukkit's {@code PlayerTeleportEvent}, or the pinned position poll. On
 * Folia both fire on the <em>destination</em> region's tick, after the
 * async chunk load and the cross-region entity handoff, so the harness sees
 * the arrival one to two ticks (50-100 ms) after the plugin issued the
 * teleport. That floor is platform scheduling, not plugin work, and it
 * swamps a cache-served teleport that the plugin completed in single-digit
 * milliseconds. LeafRTP fires {@code PreTeleportEvent} immediately before
 * and {@code PostTeleportEvent} immediately after its own teleport call, so
 * listening to those gives the plugin's completion instant directly.
 *
 * <p><b>How.</b> The event classes are looked up by name and registered
 * through {@link org.bukkit.plugin.PluginManager#registerEvent} with an
 * {@link EventExecutor}, so this JAR keeps no compile-time dependency on
 * LeafRTP (helpers/ contract). Every accessor on the event payload is
 * resolved reflectively once. If the classes are absent - a competitor arm,
 * or the lite jar with events stripped - {@link #available()} is false and
 * nothing is registered; those arms keep the external attribution paths and
 * their rows say so in {@code attribution_source}.
 *
 * <p><b>What it changes.</b> When a Post event matches an in-flight attempt,
 * the attempt is completed from here with source {@code PLUGIN_EVENT}, and
 * the later {@code PlayerTeleportEvent} / poll for the same player finds no
 * expectation and is ignored. {@code latency_ms} therefore measures dispatch
 * to the plugin's own completion instant for this arm.
 *
 * <p><b>Why the Pre timestamp is buffered separately.</b> An earlier version
 * stamped {@code pluginPreTeleportEpochMs} straight onto the in-flight attempt
 * from {@code onPre}. That produced {@code plugin_latency_ms=-1} on every row:
 * on a cache-served teleport {@code Pre} fires before the runner has even
 * registered the expectation ({@code peek} returns null), and on a cold
 * teleport the position poll used to claim and remove the attempt before
 * {@code Post} arrived. Either way one endpoint was always lost. The Pre
 * instant is now held in {@link #pendingPre}, keyed by player and independent
 * of the {@code expecting} map, and transferred onto the attempt when the
 * matching Post arrives - so both endpoints survive regardless of the
 * expectation lifecycle. Poll/{@code PlayerTeleportEvent} racing is removed
 * by {@link TeleportProbe#setDirectAuthoritative(boolean)} (this probe enables
 * it), which stops those channels from claiming the rtp arm at all.
 *
 * <p>Handlers only do map lookups and timestamp writes; they run on whatever
 * thread the plugin fires from and never touch the world.
 */
public final class DirectTeleportProbe implements Listener {

    static final String PRE_EVENT_CLASS = "io.github.dailystruggle.rtp.bukkit.events.PreTeleportEvent";
    static final String POST_EVENT_CLASS = "io.github.dailystruggle.rtp.bukkit.events.PostTeleportEvent";

    private final Plugin plugin;
    private final TeleportProbe probe;

    private final Class<? extends Event> preClass;
    private final Class<? extends Event> postClass;
    /** {@code getDoTeleport()} bound to {@link #postClass}. */
    private final Method getDoTeleport;
    /** {@code getDoTeleport()} bound to {@link #preClass}. Pre and Post are
     *  independent {@link Event} subclasses, each declaring their own accessor,
     *  so a Method resolved from the Post class throws
     *  {@link IllegalArgumentException} when invoked on a Pre instance - which
     *  {@code playerOf} swallowed, losing the Pre timestamp and leaving
     *  {@code plugin_latency_ms} at -1 on every row. */
    private final Method getDoTeleportPre;
    private final Method taskPlayer;
    private final Method playerUuid;
    private final Method taskCoords;
    private final Method coordsX;
    private final Method coordsZ;

    private volatile boolean registered = false;

    /** Pre-teleport instant per player, captured unconditionally on {@code Pre}
     *  and consumed on the matching {@code Post}. Independent of the probe's
     *  {@code expecting} map so the timestamp is not lost when {@code Pre}
     *  fires before the expectation is registered (cache-served path). A
     *  player teleports serially, so one pending entry per UUID is correct. */
    private final Map<UUID, Long> pendingPre = new ConcurrentHashMap<>();

    public DirectTeleportProbe(Plugin plugin, TeleportProbe probe) {
        this.plugin = plugin;
        this.probe = probe;
        Class<? extends Event> pre = null, post = null;
        Method doTeleport = null, doTeleportPre = null, player = null, uuid = null, coords = null, x = null, z = null;
        try {
            pre = Class.forName(PRE_EVENT_CLASS).asSubclass(Event.class);
            post = Class.forName(POST_EVENT_CLASS).asSubclass(Event.class);
            doTeleport = post.getMethod("getDoTeleport");
            // Resolve the same accessor from the Pre class so onPre can read
            // the payload; both return the same TeleportPipelineTask type, so
            // the downstream task/coords accessors are shared.
            doTeleportPre = pre.getMethod("getDoTeleport");
            Class<?> task = doTeleport.getReturnType();
            player = task.getMethod("player");
            uuid = player.getReturnType().getMethod("uuid");
            coords = task.getMethod("coords");
            x = coords.getReturnType().getMethod("x");
            z = coords.getReturnType().getMethod("z");
        } catch (ClassNotFoundException | ClassCastException e) {
            pre = null; post = null; doTeleportPre = null; // plugin under test is not LeafRTP
        } catch (NoSuchMethodException | SecurityException e) {
            plugin.getLogger().log(Level.WARNING,
                    "[StressTestRTP] LeafRTP events present but their shape changed; "
                            + "direct attribution disabled: " + e);
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
    }

    /** True iff LeafRTP's teleport events are on the classpath. */
    public boolean available() { return preClass != null && postClass != null; }

    /** Cached name of the plugin whose classloader owns the LeafRTP event
     *  classes (i.e. the "rtp" arm's plugin). Resolved lazily on first use. */
    private volatile String ownerPluginName;
    private volatile boolean ownerResolved;

    /**
     * Name of the plugin that provides the LeafRTP teleport events, or
     * {@code null} if unavailable / not resolvable. Used by {@link Runner} to
     * decide which configured arm is the direct-attribution arm: direct
     * authority must apply only while the LeafRTP arm is dispatching, never
     * during a competitor phase (where LeafRTP fires no events and the
     * external attribution channels are the only source).
     */
    public String ownerPluginName() {
        if (ownerResolved) return ownerPluginName;
        String resolved = null;
        if (available()) {
            ClassLoader cl = preClass.getClassLoader();
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                if (p.getClass().getClassLoader() == cl) { resolved = p.getName(); break; }
            }
        }
        ownerPluginName = resolved;
        ownerResolved = true;
        return resolved;
    }

    public void register() {
        if (registered || !available()) return;
        Bukkit.getPluginManager().registerEvent(preClass, this, EventPriority.MONITOR,
                (EventExecutor) (l, e) -> onPre(e), plugin, false);
        Bukkit.getPluginManager().registerEvent(postClass, this, EventPriority.MONITOR,
                (EventExecutor) (l, e) -> onPost(e), plugin, false);
        registered = true;
        plugin.getLogger().info("[StressTestRTP] LeafRTP teleport events detected; "
                + "attributing the rtp arm from PostTeleportEvent (attribution_source=PLUGIN_EVENT).");
    }

    public void unregister() {
        if (!registered) return;
        HandlerList.unregisterAll(this);
        registered = false;
    }

    private void onPre(Event event) {
        long now = System.currentTimeMillis();
        UUID id = playerOf(event, getDoTeleportPre);
        if (id == null) return;
        // Capture unconditionally, independent of the expecting map: on the
        // cache-served path Pre fires before the expectation is registered.
        // First Pre wins for a given pending window (putIfAbsent) so a retried
        // teleport inside one attempt does not shorten the measured span; the
        // entry is cleared when the matching Post consumes it.
        pendingPre.putIfAbsent(id, now);
    }

    private void onPost(Event event) {
        long now = System.currentTimeMillis();
        UUID id = playerOf(event);
        if (id == null) return;
        Long preTs = pendingPre.remove(id);
        MetricsRecorder.Attempt attempt = probe.peek(id);
        if (attempt != null) {
            if (preTs != null && attempt.pluginPreTeleportEpochMs < 0) {
                attempt.pluginPreTeleportEpochMs = preTs;
            }
            attempt.pluginPostTeleportEpochMs = now;
        }
        double[] to = coordsOf(event);
        if (to == null) {
            // No destination on the payload: leave the timeout reaper as the
            // backstop (the poll is suppressed for this arm), keeping the
            // plugin timestamps already written for plugin_latency_ms.
            return;
        }
        // attributeDirect claims the same attempt instance we just stamped, so
        // onComplete reads the pre/post timestamps we set above.
        probe.attributeDirect(id, to[0], to[1]);
    }

    private UUID playerOf(Event event) {
        return playerOf(event, getDoTeleport);
    }

    private UUID playerOf(Event event, Method doTeleportAccessor) {
        try {
            if (doTeleportAccessor == null) return null;
            Object task = doTeleportAccessor.invoke(event);
            if (task == null) return null;
            Object player = taskPlayer.invoke(task);
            if (player == null) return null; // console-initiated: not a roster attempt
            Object uuid = playerUuid.invoke(player);
            return uuid instanceof UUID u ? u : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private double[] coordsOf(Event event) {
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
