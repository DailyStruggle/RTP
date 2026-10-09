package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Attributes {@link PlayerTeleportEvent} occurrences back to in-flight
 * {@link MetricsRecorder.Attempt}s.
 *
 * <p>Strategy: keep a UUID→Attempt map of "currently expecting a teleport for
 * this player". On dispatch, {@link #expect(MetricsRecorder.Attempt, UUID)}
 * registers the attempt. The first {@link PlayerTeleportEvent} with cause
 * {@code COMMAND}, {@code PLUGIN}, {@code UNKNOWN} (Folia) for that player
 * within the timeout window is treated as completion.
 *
 * <p>The listener runs at {@link EventPriority#MONITOR} and is read-only -
 * it never cancels or modifies the event.
 */
public final class TeleportProbe implements Listener {

    private final Plugin plugin;
    private final MetricsRecorder recorder;
    private final Map<UUID, MetricsRecorder.Attempt> expecting = new ConcurrentHashMap<>();
    /** Notified with the player's UUID when an attempt is attributed (success
     *  or recorded failure) so {@link Runner} can release the in-flight slot
     *  without waiting for the per-attempt timeout. */
    private volatile Consumer<UUID> onAttributed = id -> {};
    /** When true, only {@link DirectTeleportProbe} (and the timeout reaper)
     *  may claim an expectation: the position poll and {@code PlayerTeleportEvent}
     *  become no-ops for attribution. Enabled by {@link DirectTeleportProbe}
     *  when the plugin under test fires LeafRTP's own teleport events, so a
     *  cold teleport is attributed at the plugin's completion instant instead
     *  of losing the race to the poll (which re-adds the ~100 ms Folia
     *  destination-tick floor). */
    private volatile boolean directAuthoritative = false;
    /** Direct-arm attempts already completed by {@link DirectTeleportProbe}
     *  whose CSV row is held until the external channel (the one every arm
     *  shares) sees the same teleport. Keyed by player; one per player. */
    private final Map<UUID, MetricsRecorder.Attempt> awaitingExternal = new ConcurrentHashMap<>();
    /** Max XZ distance between an external sighting and a held attempt's
     *  destination for the sighting to be that attempt's (blocks). Guards a
     *  late event of attempt N against being stamped on attempt N+1. */
    static final double EXTERNAL_MATCH_BLOCKS = 4.0;

    public void setOnAttributed(Consumer<UUID> cb) {
        this.onAttributed = (cb == null) ? id -> {} : cb;
    }

    /** See {@link #directAuthoritative}. */
    public void setDirectAuthoritative(boolean value) {
        this.directAuthoritative = value;
    }

    public TeleportProbe(Plugin plugin, MetricsRecorder recorder) {
        this.plugin = plugin;
        this.recorder = recorder;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    /** Called by {@link Runner} just before dispatching the command.
     *  Returns the previously-registered attempt for that player (if any) so
     *  the caller can record it as a missed-attribution TIMEOUT - this matters
     *  when a target plugin never fires {@code PlayerTeleportEvent} (or fires
     *  it with a cause we don't recognise), because otherwise the prior
     *  expectation would linger until the per-attempt timeout reaper runs. */
    public MetricsRecorder.Attempt expect(MetricsRecorder.Attempt attempt, UUID playerId) {
        return expecting.put(playerId, attempt);
    }

    /** Called by {@link Runner} when an attempt times out, to free the slot. */
    public MetricsRecorder.Attempt forget(UUID playerId) {
        return expecting.remove(playerId);
    }

    /** Held direct-arm attempt for {@code playerId}, or null. Lets the
     *  pinned watcher keep observing after the plugin's own event fired. */
    public MetricsRecorder.Attempt awaitingExternal(UUID playerId) {
        return awaitingExternal.get(playerId);
    }

    /**
     * Records an external sighting of {@code playerId} at {@code to}. A held
     * direct-arm attempt whose destination matches takes it (and its row is
     * written); otherwise the in-flight attempt is stamped, first sighting
     * wins. Never completes an attempt by itself.
     *
     * @return true iff a held attempt consumed the sighting
     */
    boolean noteExternal(UUID playerId, Location to) {
        long now = System.currentTimeMillis();
        MetricsRecorder.Attempt held = awaitingExternal.get(playerId);
        if (held != null && !recorder.isDeferred(held)) {
            // Its wait elapsed and the row was written without a sighting.
            awaitingExternal.remove(playerId, held);
            held = null;
        }
        if (held != null && to != null && matches(held, to.getX(), to.getZ())
                && awaitingExternal.remove(playerId, held)) {
            if (held.externalSeenEpochMs < 0) held.externalSeenEpochMs = now;
            if (recorder.releaseDeferred(held)) return true;
        }
        MetricsRecorder.Attempt live = expecting.get(playerId);
        if (live != null && live.externalSeenEpochMs < 0) live.externalSeenEpochMs = now;
        return false;
    }

    private static boolean matches(MetricsRecorder.Attempt a, double x, double z) {
        double dx = a.toX - x, dz = a.toZ - z;
        return dx * dx + dz * dz <= EXTERNAL_MATCH_BLOCKS * EXTERNAL_MATCH_BLOCKS;
    }

    /** Snapshot of UUIDs currently expecting a teleport, for poll fallbacks. */
    public java.util.Set<UUID> expectingIds() {
        return new java.util.HashSet<>(expecting.keySet());
    }

    /** Look up an in-flight attempt for {@code playerId} without removing it. */
    public MetricsRecorder.Attempt peek(UUID playerId) {
        return expecting.get(playerId);
    }

    /**
     * Position-poll fallback used on platforms where {@code PlayerTeleportEvent}
     * is unreliable (notably Folia: see HuskHomes #824). Atomically removes the
     * expectation for {@code playerId} and, if found, records the attempt as a
     * success at {@code (toX, toZ)} and notifies the {@code onAttributed}
     * callback (so {@link Runner} releases the in-flight slot).
     *
     * <p>Returns {@code true} iff an expectation was claimed by this call.
     */
    public boolean attributeByPosition(UUID playerId, double toX, double toZ) {
        return attributeByPosition(playerId, toX, toZ, null);
    }

    /** Poll variant carrying the observed location. Call on the thread that
     *  owns the player so the landing column can be read. */
    public boolean attributeByPosition(UUID playerId, Location loc) {
        if (loc == null) return false;
        return attributeByPosition(playerId, loc.getX(), loc.getZ(), loc);
    }

    private boolean attributeByPosition(UUID playerId, double toX, double toZ, Location loc) {
        // The poll is an external channel: record it on every arm.
        if (noteExternal(playerId, loc)) return false;
        // With direct attribution authoritative, the poll must not claim the
        // rtp arm: the plugin's PostTeleportEvent is the source of truth and
        // the timeout reaper is the only other backstop.
        if (directAuthoritative) return false;
        MetricsRecorder.Attempt attempt = expecting.remove(playerId);
        if (attempt == null) return false;
        if (loc != null) attempt.landing = LandingInspector.inspect(loc);
        recorder.onComplete(attempt, true, "", toX, toZ,
                MetricsRecorder.AttributionSource.POSITION_POLL);
        onAttributed.accept(playerId);
        return true;
    }

    /**
     * Direct attribution from the plugin under test's own completion event
     * (see {@link DirectTeleportProbe}). Same contract as
     * {@link #attributeByPosition}: claims the expectation atomically, so a
     * later {@code PlayerTeleportEvent} or poll for the same player finds
     * nothing and is ignored.
     */
    public boolean attributeDirect(UUID playerId, double toX, double toZ) {
        return attributeDirect(playerId, toX, toZ, true, "", null);
    }

    /**
     * Direct completion with the plugin's outcome verified by the caller.
     * A successful row is held for the external channel (see
     * {@link #awaitingExternal}) unless that channel already saw it; a
     * failed row is written at once, since no external sighting will come.
     */
    public boolean attributeDirect(UUID playerId, double toX, double toZ,
                                   boolean success, String failReason,
                                   LandingInspector.Landing landing) {
        MetricsRecorder.Attempt attempt = expecting.remove(playerId);
        if (attempt == null) return false;
        attempt.landing = landing;
        boolean hold = success && attempt.externalSeenEpochMs < 0;
        // Destination is needed by noteExternal's match before the row exists.
        attempt.toX = toX;
        attempt.toZ = toZ;
        recorder.onComplete(attempt, success, failReason, toX, toZ,
                MetricsRecorder.AttributionSource.PLUGIN_EVENT, hold);
        // After onComplete, so noteExternal never sees it before it is held.
        if (hold) awaitingExternal.put(playerId, attempt);
        onAttributed.accept(playerId);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        // Shared channel: stamped on every arm, so external_latency_ms is
        // comparable across plugins even where latency_ms is not.
        if (noteExternal(player.getUniqueId(), event.getTo())) return;
        // With direct attribution authoritative, leave the expectation for
        // DirectTeleportProbe; claiming it here would restore the Folia
        // destination-tick floor this event fires on.
        if (directAuthoritative) return;
        MetricsRecorder.Attempt attempt = expecting.remove(player.getUniqueId());
        if (attempt == null) return; // not ours

        // Filter: we only attribute teleports plausibly caused by /rtp.
        // Some plugins use COMMAND, others use PLUGIN, Folia sometimes
        // emits UNKNOWN. Reject EnderPearl/ChorusFruit/Spectate/etc.
        // We're permissive on cause: once we've registered an expectation for
        // this player, the very next teleport event is overwhelmingly likely
        // to be the /rtp we triggered. Being strict here (only COMMAND/PLUGIN/
        // UNKNOWN) caused misses with competitor plugins that emit other
        // causes, leaving the slot pinned until the timeout reaper fired and
        // producing the symptom "a teleport will not always be attributed,
        // particularly if a person is already mid flight". The only cause we
        // actively reject is ENDER_PEARL / CHORUS_FRUIT-style player actions -
        // and even those are unlikely to coincide with an /rtp window.
        switch (event.getCause()) {
            case ENDER_PEARL:
            case CHORUS_FRUIT:
            case SPECTATE:
                // Player-initiated movement that is not our /rtp - re-arm.
                expecting.put(player.getUniqueId(), attempt);
                return;
            default:
                break;
        }

        Location to = event.getTo();
        if (to == null) {
            recorder.onComplete(attempt, false, "NULL_TO", 0, 0,
                    MetricsRecorder.AttributionSource.TELEPORT_EVENT);
            onAttributed.accept(player.getUniqueId());
            return;
        }
        // Read before the move completes: the event fires on the thread that
        // owns the destination (Paper main thread; Folia destination region),
        // which is where the inspector is allowed to read it.
        attempt.landing = LandingInspector.inspect(to);
        recorder.onComplete(attempt, true, "", to.getX(), to.getZ(),
                MetricsRecorder.AttributionSource.TELEPORT_EVENT);
        onAttributed.accept(player.getUniqueId());
    }
}
