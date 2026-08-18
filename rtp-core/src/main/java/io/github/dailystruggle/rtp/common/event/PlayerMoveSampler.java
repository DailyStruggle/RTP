package io.github.dailystruggle.rtp.common.event;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.event.PlayerMoveDispatcher;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Tick-sampled producer for platform-neutral {@link PlayerMoveEvent} (ADR-075).
 * Diffs block positions per tick for watched players on platforms lacking native move events.
 *
 * @since 3.1.4
 */
public final class PlayerMoveSampler {

    /** Last observed block position per watched player, keyed by UUID. */
    private final Map<UUID, Sample> lastSeen = new ConcurrentHashMap<>();

    private record Sample(String worldName, int x, int y, int z) {}

    /**
     * Sample every currently-watched player's block position and fire a
     * {@link PlayerMoveEvent} for each that has crossed into a new block within
     * the same world since the previous sample. Safe to call every tick; a
     * server with no watchers returns immediately.
     */
    public void sample() {
        PlayerMoveDispatcher dispatcher = RTPAPI.playerMoveEvents;
        if (dispatcher == null || !dispatcher.hasWatchers()) {
            // Nothing watched: drop any stale baselines so they cannot leak.
            if (!lastSeen.isEmpty()) lastSeen.clear();
            return;
        }
        if (RTP.serverAccessor == null) return;

        Set<UUID> watched = dispatcher.watchedPlayers();

        // Prune baselines for players no longer watched (disconnect / disarm).
        for (Iterator<UUID> it = lastSeen.keySet().iterator(); it.hasNext(); ) {
            if (!watched.contains(it.next())) it.remove();
        }

        for (UUID id : watched) {
            try {
                sampleOne(id, dispatcher);
            } catch (Throwable t) {
                // A single player's sampling failure must not break the loop
                // (S-004: never silently swallow -> log at FINER).
                RTP.log(Level.FINER,
                        "[RTP] PlayerMoveSampler: sampling " + id + " raised "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    private void sampleOne(UUID id, PlayerMoveDispatcher dispatcher) {
        RTPPlayer player = RTP.serverAccessor.getPlayer(id);
        if (player == null || !player.isOnline()) {
            lastSeen.remove(id);
            return;
        }
        RTPLocation loc = player.getLocation();
        if (loc == null) return;
        RTPWorld<?> world = loc.world();
        if (world == null) return;
        String worldName = world.name();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        Sample prev = lastSeen.get(id);
        Sample now = new Sample(worldName, x, y, z);
        if (prev == null || !prev.worldName().equals(worldName)) {
            // First observation, or a world change: (re)establish the baseline
            // without firing. A cross-world jump is not a same-world move.
            lastSeen.put(id, now);
            return;
        }
        if (prev.x() == x && prev.y() == y && prev.z() == z) {
            // No block change since last tick: nothing to report.
            return;
        }
        lastSeen.put(id, now);
        dispatcher.fire(new PlayerMoveEvent(
                id, worldName,
                prev.x(), prev.y(), prev.z(),
                x, y, z));
    }

    /** Drop all baseline state (e.g. on server stop). */
    public void clear() {
        lastSeen.clear();
    }
}
