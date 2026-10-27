package io.github.dailystruggle.helpers.stresstestrtp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Attributes teleport occurrences back to in-flight {@link Attempt}s
 * (ADR-122 Decision 1 & 2).
 */
public final class TeleportProbe {

    private final StressHost plugin;
    private final MetricsRecorder recorder;
    private final Map<UUID, Attempt> expecting = new ConcurrentHashMap<>();
    private volatile Consumer<UUID> onAttributed = id -> {};
    private volatile boolean directAuthoritative = false;
    private final Map<UUID, Attempt> awaitingExternal = new ConcurrentHashMap<>();
    static final double EXTERNAL_MATCH_BLOCKS = 4.0;
    private Runnable unsubscribe;

    public void setOnAttributed(Consumer<UUID> cb) {
        this.onAttributed = (cb == null) ? id -> {} : cb;
    }

    public void setDirectAuthoritative(boolean value) {
        this.directAuthoritative = value;
    }

    public TeleportProbe(StressHost plugin, MetricsRecorder recorder) {
        this.plugin = plugin;
        this.recorder = recorder;
    }

    public void register() {
        if (plugin != null && unsubscribe == null) {
            unsubscribe = plugin.onPlayerTeleport(this::onTeleport);
        }
    }

    public void unregister() {
        if (unsubscribe != null) {
            unsubscribe.run();
            unsubscribe = null;
        }
    }

    public Attempt expect(Attempt attempt, UUID playerId) {
        return expecting.put(playerId, attempt);
    }

    public Attempt forget(UUID playerId) {
        return expecting.remove(playerId);
    }

    public Attempt awaitingExternal(UUID playerId) {
        return awaitingExternal.get(playerId);
    }

    boolean noteExternal(UUID playerId, StressLocation to) {
        long now = System.currentTimeMillis();
        Attempt held = awaitingExternal.get(playerId);
        if (held != null && !recorder.isDeferred(held)) {
            awaitingExternal.remove(playerId, held);
            held = null;
        }
        if (held != null && to != null && matches(held, to.x(), to.z())
                && awaitingExternal.remove(playerId, held)) {
            if (held.externalSeenEpochMs < 0) held.externalSeenEpochMs = now;
            if (recorder.releaseDeferred(held)) return true;
        }
        Attempt live = expecting.get(playerId);
        if (live != null && live.externalSeenEpochMs < 0) live.externalSeenEpochMs = now;
        return false;
    }

    private static boolean matches(Attempt a, double x, double z) {
        double dx = a.toX - x, dz = a.toZ - z;
        return dx * dx + dz * dz <= EXTERNAL_MATCH_BLOCKS * EXTERNAL_MATCH_BLOCKS;
    }

    public java.util.Set<UUID> expectingIds() {
        return new java.util.HashSet<>(expecting.keySet());
    }

    public Attempt peek(UUID playerId) {
        return expecting.get(playerId);
    }

    public boolean attributeByPosition(UUID playerId, double toX, double toZ) {
        return attributeByPosition(playerId, toX, toZ, null);
    }

    public boolean attributeByPosition(UUID playerId, StressLocation loc) {
        if (loc == null) return false;
        return attributeByPosition(playerId, loc.x(), loc.z(), loc);
    }

    private boolean attributeByPosition(UUID playerId, double toX, double toZ, StressLocation loc) {
        if (noteExternal(playerId, loc)) return false;
        if (directAuthoritative) return false;
        Attempt attempt = expecting.remove(playerId);
        if (attempt == null) return false;
        if (loc != null) attempt.landing = LandingInspector.inspect(plugin, loc);
        recorder.onComplete(attempt, true, "", toX, toZ,
                AttributionSource.POSITION_POLL);
        onAttributed.accept(playerId);
        return true;
    }

    public boolean attributeDirect(UUID playerId, double toX, double toZ) {
        return attributeDirect(playerId, toX, toZ, true, "", null);
    }

    public boolean attributeDirect(UUID playerId, double toX, double toZ,
                                   boolean success, String failReason,
                                   LandingInspector.Landing landing) {
        Attempt attempt = expecting.remove(playerId);
        if (attempt == null) return false;
        attempt.landing = landing;
        boolean hold = success && attempt.externalSeenEpochMs < 0;
        attempt.toX = toX;
        attempt.toZ = toZ;
        recorder.onComplete(attempt, success, failReason, toX, toZ,
                AttributionSource.PLUGIN_EVENT, hold);
        if (hold) awaitingExternal.put(playerId, attempt);
        onAttributed.accept(playerId);
        return true;
    }

    public void onTeleport(StressHost.TeleportEvent event) {
        if (event == null || event.player() == null) return;
        UUID playerId = event.player().uuid();
        if (noteExternal(playerId, event.to())) return;
        if (directAuthoritative) return;
        Attempt attempt = expecting.remove(playerId);
        if (attempt == null) return;

        String cause = event.cause();
        if (cause != null) {
            switch (cause.toUpperCase(java.util.Locale.ROOT)) {
                case "ENDER_PEARL", "CHORUS_FRUIT", "SPECTATE":
                    expecting.put(playerId, attempt);
                    return;
                default:
                    break;
            }
        }

        StressLocation to = event.to();
        if (to == null) {
            recorder.onComplete(attempt, false, "NULL_TO", 0, 0,
                    AttributionSource.TELEPORT_EVENT);
            onAttributed.accept(playerId);
            return;
        }
        attempt.landing = LandingInspector.inspect(plugin, to);
        recorder.onComplete(attempt, true, "", to.x(), to.z(),
                AttributionSource.TELEPORT_EVENT);
        onAttributed.accept(playerId);
    }
}
