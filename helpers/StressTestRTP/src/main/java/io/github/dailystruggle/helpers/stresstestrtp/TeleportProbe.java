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

    public void setOnAttributed(Consumer<UUID> cb) {
        this.onAttributed = (cb == null) ? id -> {} : cb;
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
        MetricsRecorder.Attempt attempt = expecting.remove(playerId);
        if (attempt == null) return false;
        recorder.onComplete(attempt, true, "", toX, toZ);
        onAttributed.accept(playerId);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
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
            recorder.onComplete(attempt, false, "NULL_TO", 0, 0);
            onAttributed.accept(player.getUniqueId());
            return;
        }
        recorder.onComplete(attempt, true, "", to.getX(), to.getZ());
        onAttributed.accept(player.getUniqueId());
    }
}
