package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * ADR-015 / REQ-RTP-NET-015: periodic player-facing notifier for
 * the cross-server waitlist.
 *
 * <p>On every pulse the notifier walks {@link NetworkStatusCache#snapshot()},
 * picks the entries whose state is
 * {@link NetworkStatusCache.QueueStatus.State#WAITLISTED}, and emits a
 * {@code msgNetworkQueued}-style message with the current position. To
 * avoid chat spam the notifier remembers the last message body emitted per
 * UUID and only re-emits when either:
 * <ol>
 *   <li>the rendered body changed (position moved up), or</li>
 *   <li>the notify interval has elapsed.</li>
 * </ol>
 * Players who fall out of the cache (terminal state reached, evicted) are
 * removed from the dedup table.
 *
 * <p>Scheduling: owned by {@code RTP.scheduler.runTaskTimerAsynchronously}.
 * No raw executor (per AGENTS.md "Scheduler Usage"). Idempotent
 * {@link #start(long)} / {@link #shutdown()}.</p>
 *
 * <p>The rendered body is sourced from
 * {@link MessagesKeys#networkQueued} in {@code messages.yml} per
 * REQ-RTP-F-013. Placeholder {@code [position]} is substituted client-side
 * with the FIFO position when known.</p>
 */
public class NetworkWaitlistNotifier {

    /**
     * Hardcoded fallback used only when {@code RTP.configs} has not been
     * initialised (test contexts, very early bootstrap). Production
     * rendering reads {@link MessagesKeys#networkQueued} from
     * {@code messages.yml} per REQ-RTP-F-013.
     */
    private static final String DEFAULT_NETWORK_QUEUED =
            "&7[P0] queued for a cross-server destination (position [position])...";

    private final NetworkStatusCache statusCache;
    /** Last-emitted body per player, keyed for dedup. */
    private final Map<UUID, String> lastEmitted = new HashMap<>();
    /** Last-emission timestamp (millis) per player, keyed for interval. */
    private final Map<UUID, Long> lastEmittedAtMs = new HashMap<>();
    /** Min millis between re-notifies of identical body for the same UUID. */
    private final long reNotifyIntervalMs;
    private volatile Object timerTaskHandle;

    /**
     * @param statusCache the lobby-side cache.
     * @param reNotifyIntervalMs minimum millis between identical-body
     *     re-notifies for the same UUID. The pulse rate itself is set via
     *     {@link #start(long)}.
     */
    public NetworkWaitlistNotifier(NetworkStatusCache statusCache, long reNotifyIntervalMs) {
        this.statusCache = Objects.requireNonNull(statusCache, "statusCache");
        this.reNotifyIntervalMs = Math.max(0L, reNotifyIntervalMs);
    }

    /** Idempotent. */
    public synchronized void start(long periodTicks) {
        if (timerTaskHandle != null) return;
        if (RTP.scheduler == null) {
            RTP.log(Level.WARNING,
                    "[NETWORK] NetworkWaitlistNotifier.start called before scheduler available; "
                            + "notifier not started.");
            return;
        }
        long ticks = Math.max(1L, periodTicks);
        timerTaskHandle = RTP.scheduler.runTaskTimerAsynchronously(this::pulse, ticks, ticks);
    }

    /**
     * One pulse. Visible for tests; also called by the timer. Reads the
     * cache snapshot, emits messages where appropriate, evicts dedup
     * entries for players no longer in the cache.
     */
    public void pulse() {
        try {
            doPulse(System.currentTimeMillis());
        } catch (Throwable t) {
            // S-004: never silently swallow.
            RTP.log(Level.WARNING,
                    "[NETWORK] NetworkWaitlistNotifier pulse failed: " + t.getMessage(), t);
        }
    }

    /** Package-visible pulse with injectable clock for tests. */
    synchronized void doPulse(long nowMs) {
        Map<UUID, NetworkStatusCache.QueueStatus> snap = statusCache.snapshot();
        // Evict dedup entries for players that fell out of the cache.
        lastEmitted.keySet().retainAll(snap.keySet());
        lastEmittedAtMs.keySet().retainAll(snap.keySet());

        for (Map.Entry<UUID, NetworkStatusCache.QueueStatus> entry : snap.entrySet()) {
            UUID uuid = entry.getKey();
            NetworkStatusCache.QueueStatus status = entry.getValue();
            if (status.state() != NetworkStatusCache.QueueStatus.State.WAITLISTED) {
                // Player left WAITLISTED (e.g. drained to ROUTING). Reset
                // dedup so a future WAITLISTED re-entry emits immediately.
                lastEmitted.remove(uuid);
                lastEmittedAtMs.remove(uuid);
                continue;
            }
            String body = renderBody(status);
            String prev = lastEmitted.get(uuid);
            Long lastAt = lastEmittedAtMs.get(uuid);
            boolean bodyChanged = !body.equals(prev);
            boolean intervalElapsed = lastAt == null
                    || (nowMs - lastAt) >= reNotifyIntervalMs;
            if (!bodyChanged && !intervalElapsed) continue;

            if (emit(uuid, body)) {
                lastEmitted.put(uuid, body);
                lastEmittedAtMs.put(uuid, nowMs);
            }
        }
    }

    /** Visible for tests. */
    static String renderBody(NetworkStatusCache.QueueStatus status) {
        int pos = status.positionInQueue();
        String template = resolveTemplate();
        return template.replace("[position]", pos > 0 ? Integer.toString(pos) : "");
    }

    @SuppressWarnings("unchecked")
    private static String resolveTemplate() {
        try {
            if (RTP.configs != null) {
                ConfigParser<MessagesKeys> parser =
                        (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
                if (parser != null) {
                    Object v = parser.getConfigValue(
                            MessagesKeys.networkQueued, DEFAULT_NETWORK_QUEUED);
                    if (v != null) return v.toString();
                }
            }
        } catch (Throwable ignored) {
            // fall through to default
        }
        return DEFAULT_NETWORK_QUEUED;
    }

    /**
     * Send the message to the online player (no-op when offline). Returns
     * {@code true} on dispatch (so dedup remembers the body); {@code false}
     * when the player isn't online (so we re-try on the next pulse without
     * advancing the dedup state).
     */
    boolean emit(UUID uuid, String body) {
        io.github.dailystruggle.rtp.api.entity.RTPPlayer p =
                RTP.serverAccessor == null ? null : RTP.serverAccessor.getPlayer(uuid);
        if (p == null || !p.isOnline()) return false;
        try {
            p.sendMessage(body);
            return true;
        } catch (Throwable t) {
            // S-004: never silently swallow.
            RTP.log(Level.WARNING,
                    "[NETWORK] NetworkWaitlistNotifier emit failed for "
                            + uuid + ": " + t.getMessage(), t);
            return false;
        }
    }

    /** Idempotent. */
    public synchronized void shutdown() {
        if (timerTaskHandle != null && RTP.scheduler != null) {
            try { RTP.scheduler.cancelTask(timerTaskHandle); } catch (Throwable ignored) { /* best-effort */ }
        }
        timerTaskHandle = null;
        lastEmitted.clear();
        lastEmittedAtMs.clear();
    }

    /** Visible for tests. */
    synchronized int trackedCount() { return lastEmitted.size(); }
}
