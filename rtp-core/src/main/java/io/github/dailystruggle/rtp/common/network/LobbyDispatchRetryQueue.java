package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.api.configuration.enums.NetworkMessages;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
/**
 * Lobby-side auto-retry queue for {@code /rtp} attempts that hit a
 * <em>transient</em> {@link RoutingDecision.LocalFallback} reason.
 *
 * <p>Motivation (user-confirmed design): on a lobby backend
 * ({@code network.routing.lobbyMode: true}) the local pipeline has no
 * RTP regions to fall back to, so the existing
 * {@link BukkitNetworkCommandHook} behaviour of silently mapping
 * {@code LocalFallback} -&gt; {@link io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult#local()}
 * lands the player in a "non-processing" state: no message, no
 * teleport, and the next {@code /rtp} re-runs from scratch. The
 * transient gates that produce {@code LocalFallback}
 * ({@link RoutingDecision.FallbackReason#NETWORK_DISABLED} during
 * snapshot warm-up, {@link RoutingDecision.FallbackReason#NO_LIVE_PEER}
 * during heartbeat lapse, {@link RoutingDecision.FallbackReason#QUEUE_FULL}
 * spike, {@link RoutingDecision.FallbackReason#TOKEN_BUCKET_EXHAUSTED}
 * burst) all clear on their own within seconds, so the right UX is for
 * the lobby itself to keep re-trying on the player's behalf instead of
 * making them retype the command.</p>
 *
 * <p>This queue is bounded by both <em>attempt count</em> and
 * <em>wall-clock TTL</em>: whichever fires first ends the retry and the
 * player gets a terminal "could not place you" message.
 * {@link RoutingDecision.FallbackReason#REGION_UNAVAILABLE} and
 * {@link RoutingDecision.FallbackReason#UNKNOWN} are treated as terminal
 * - retrying them just spins until the TTL because the player asked
 * for a region/server that does not exist or hard-pinned a dead peer.</p>
 *
 * <p>Threading:
 * <ul>
 *   <li>Producers ({@code /rtp} command path inside
 *       {@link BukkitNetworkCommandHook}) call
 *       {@link #enqueue(UUID, Map, RoutingDecision.FallbackReason, java.util.function.Consumer)}
 *       cheaply on whatever thread Bukkit gave them.</li>
 *   <li>A single async timer pulse iterates the entries and calls
 *       {@link NetworkRouter#route} synchronously per entry. The router
 *       is a pure-function read of the cached snapshot so the pulse
 *       stays cheap even with many parked players.</li>
 *   <li>{@link #cancel(UUID, MessagesKeys)} is called from
 *       {@code PlayerQuitEvent} and from {@link #shutdown()}; both clear
 *       {@code processingPlayers} so the player can re-attempt cleanly.</li>
 * </ul>
 *
 * <p>S-004 compliance: every exit path emits a player-visible message
 * (success template, terminal-rejection template, or hardcoded English
 * fallback) and releases {@code processingPlayers} on every non-success
 * exit. The success path retains the lock so the
 * {@code NetworkWaitlistNotifier} can take over.</p>
 *
 * <p>Scheduler usage: timer dispatched via
 * {@link io.github.dailystruggle.rtp.api.scheduling.RTPScheduler#runTaskTimerAsynchronously}
 * per the AGENTS.md "Scheduler Usage" rule (no raw {@code java.util.concurrent}
 * executors in backend JVMs).</p>
 */
public final class LobbyDispatchRetryQueue {

    /** Pulse cadence, in milliseconds; converted to ticks (1 tick = 50 ms). */
    static final long DEFAULT_PULSE_INTERVAL_MS = 500L;
    /** Hard cap on attempts per parked player. */
    static final int DEFAULT_MAX_ATTEMPTS = 30;
    /** Hard cap on wall-clock retry duration, in milliseconds. */
    static final long DEFAULT_MAX_TOTAL_MS = 30_000L;

    /** Per-parked-player retry state. Immutable except for {@link #attempts}. */
    static final class Entry {
        final UUID playerId;
        final Map<String, List<String>> args;
        final long createdAtMs;
        final java.util.function.Consumer<String> messageMethod;
        int attempts;

        Entry(UUID playerId,
              Map<String, List<String>> args,
              long createdAtMs,
              java.util.function.Consumer<String> messageMethod) {
            this.playerId = playerId;
            this.args = args;
            this.createdAtMs = createdAtMs;
            this.messageMethod = messageMethod;
            this.attempts = 0;
        }
    }

    private final NetworkRouter router;
    private final NetworkEnrolmentBuffer enrolmentBuffer;
    private final ConcurrentHashMap<UUID, Entry> parked = new ConcurrentHashMap<>();
    /**
     * Players who successfully enrolled cross-server and whose original
     * {@code (args, messageMethod)} we remember so that if the proxy
     * never produces a terminal transition (sticky-TTL expiry in
     * {@link NetworkStatusCache}), {@link #onTerminalFailure(UUID)} can
     * re-park them into {@link #parked} for a fresh transient retry
     * rather than forcing the player to retype {@code /rtp}. Cleared
     * on {@link #remove}, {@link #cancel}, supplier-confirmed terminal,
     * and successful re-enrolment.
     */
    private final ConcurrentHashMap<UUID, Entry> enrolled = new ConcurrentHashMap<>();
    private final long pulseIntervalMs;
    private final int maxAttempts;
    private final long maxTotalMs;
    private volatile Object timerHandle;

    public LobbyDispatchRetryQueue(NetworkRouter router,
                                   NetworkEnrolmentBuffer enrolmentBuffer) {
        this(router, enrolmentBuffer,
                DEFAULT_PULSE_INTERVAL_MS, DEFAULT_MAX_ATTEMPTS, DEFAULT_MAX_TOTAL_MS);
    }

    public LobbyDispatchRetryQueue(NetworkRouter router,
                                   NetworkEnrolmentBuffer enrolmentBuffer,
                                   long pulseIntervalMs,
                                   int maxAttempts,
                                   long maxTotalMs) {
        this.router = Objects.requireNonNull(router, "router");
        this.enrolmentBuffer = Objects.requireNonNull(enrolmentBuffer, "enrolmentBuffer");
        this.pulseIntervalMs = Math.max(50L, pulseIntervalMs);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.maxTotalMs = Math.max(1_000L, maxTotalMs);
    }

    /**
     * Park a player whose {@code /rtp} hit a transient
     * {@link RoutingDecision.LocalFallback}. The player keeps their
     * {@code processingPlayers} lock; the queue is now responsible for
     * either enrolling them (lock-retain path) or terminating with a
     * rejection message (lock-release path).
     *
     * <p>Idempotent: a second {@link #enqueue} for the same UUID is a
     * no-op. This is safe because the outer {@code processingPlayers}
     * guard at {@code RTPCmd.onCommand} already short-circuits repeated
     * commands with {@code alreadyTeleporting}, so we should never see
     * a second enqueue under normal conditions; the no-op is defensive.
     *
     * @param messageMethod the player-facing message sink captured from
     *                      {@code RTPCmd.compute}. Allowed to be
     *                      {@code null}; when null we route messages via
     *                      {@link RTP#serverAccessor}{@code .sendMessage(UUID,String)}.
     */
    public void enqueue(UUID playerId,
                        Map<String, List<String>> args,
                        RoutingDecision.FallbackReason firstReason,
                        java.util.function.Consumer<String> messageMethod) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(firstReason, "firstReason");
        long now = System.currentTimeMillis();
        Entry entry = new Entry(playerId, args, now, messageMethod);
        Entry existing = parked.putIfAbsent(playerId, entry);
        if (existing != null) {
            RTP.log(Level.FINER,
                    "[RTP][lobbyRetry] duplicate enqueue ignored: playerId=" + playerId);
            return;
        }
        RTP.log(Level.FINE,
                "[RTP][state] lobbyRetry parked: playerId=" + playerId
                        + " firstReason=" + firstReason
                        + " maxAttempts=" + maxAttempts
                        + " maxTotalMs=" + maxTotalMs);
    }

    /** Idempotent. Removes the player without emitting a message. */
    public void remove(UUID playerId) {
        if (playerId == null) return;
        parked.remove(playerId);
        enrolled.remove(playerId);
    }

    /**
     * Record a successful cross-server enrolment so that if the proxy
     * never reports a terminal transition (sticky-TTL expiry), we can
     * automatically re-park the player for a fresh retry pulse instead
     * of forcing them to retype {@code /rtp}. Idempotent.
     *
     * <p>Called from {@code BukkitNetworkCommandHook} on the real
     * {@link RoutingDecision.CrossServer} branch (not the synthetic
     * CrossServer return used after {@link #enqueue}).</p>
     */
    public void recordEnrolment(UUID playerId,
                                Map<String, List<String>> args,
                                java.util.function.Consumer<String> messageMethod) {
        if (playerId == null || args == null) return;
        long now = System.currentTimeMillis();
        boolean firstRecord = enrolled.put(playerId, new Entry(playerId, args, now, messageMethod)) == null;
        if (firstRecord) {
            RTP.log(Level.FINE,
                    "[RTP][state] lobbyRetry recorded enrolment: playerId=" + playerId);
        }
    }

    /**
     * Called by the lobby's {@code NetworkStatusCache} terminal listener
     * when a previously-enrolled player transitions to FAILED via the
     * sticky-TTL timeout (i.e. the proxy never reported a terminal
     * state). If we remembered the player's original
     * {@code (args, messageMethod)} via {@link #recordEnrolment}, we
     * re-park them for a fresh transient retry pulse and return
     * {@code true} so the caller knows NOT to release the
     * {@code processingPlayers} lock. Returns {@code false} when no
     * recorded enrolment exists, in which case the caller should
     * release the lock as before.
     */
    public boolean onTerminalFailure(UUID playerId) {
        if (playerId == null) return false;
        Entry prev = enrolled.remove(playerId);
        if (prev == null) return false;
        // Skip if the player is already parked (e.g. the retry pulse
        // re-enrolled them and a stale terminal listener race fired).
        if (parked.containsKey(playerId)) {
            RTP.log(Level.FINER,
                    "[RTP][lobbyRetry] onTerminalFailure: already parked, skip re-park: playerId="
                            + playerId);
            return true;
        }
        long now = System.currentTimeMillis();
        Entry entry = new Entry(playerId, prev.args, now, prev.messageMethod);
        parked.put(playerId, entry);
        RTP.log(Level.FINE,
                "[RTP][state] lobbyRetry re-parked after enrolment timeout: playerId="
                        + playerId);
        return true;
    }

    /**
     * Cancel a parked retry with a localized message and release the
     * {@code processingPlayers} lock. Used by the {@code PlayerQuitEvent}
     * listener and by {@link #shutdown()}.
     */
    public void cancel(UUID playerId, Enum<?> messageKey) {
        if (playerId == null) return;
        enrolled.remove(playerId);
        Entry entry = parked.remove(playerId);
        if (entry == null) return;
        if (messageKey != null) {
            sendLocalized(entry, messageKey, "");
        }
        releaseLock(playerId);
    }

    /** Visible for tests. */
    public int size() { return parked.size(); }

    /** Visible for tests. */
    public boolean isParked(UUID playerId) {
        return playerId != null && parked.containsKey(playerId);
    }

    /**
     * Start the periodic retry pulse on {@link RTP#scheduler}'s async
     * tier. Idempotent: a second call is a no-op.
     */
    public synchronized void start() {
        if (timerHandle != null) return;
        if (RTP.scheduler == null) {
            RTP.log(Level.WARNING,
                    "[RTP][lobbyRetry] start called before scheduler available; "
                            + "retry pulse not started.");
            return;
        }
        long periodTicks = Math.max(1L, pulseIntervalMs / 50L);
        timerHandle = RTP.scheduler.runTaskTimerAsynchronously(
                this::pulse, periodTicks, periodTicks);
    }

    /** Idempotent. Cancels every parked retry with a terminal message. */
    public synchronized void shutdown() {
        if (timerHandle != null && RTP.scheduler != null) {
            try { RTP.scheduler.cancelTask(timerHandle); } catch (Throwable ignored) { /* best-effort */ }
        }
        timerHandle = null;
        // Drain: send a terminal message to anyone still parked. We use
        // networkRegionUnavailable here as the closest existing key; a
        // dedicated networkNotReady key is a deferred follow-up.
        for (UUID id : parked.keySet()) {
            cancel(id, NetworkMessages.networkRegionUnavailable);
        }
    }

    /**
     * One pulse: iterate parked entries and try to advance each. Visible
     * for tests so timing of a pulse can be deterministic; also called by
     * the timer.
     */
    public void pulse() {
        if (parked.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Entry> e : parked.entrySet()) {
            Entry entry = e.getValue();
            try {
                advance(entry, now);
            } catch (Throwable t) {
                // S-004: never silently swallow. Log and keep the entry
                // parked so the next pulse retries. If the next pulse
                // also throws past the TTL, the TTL check in advance()
                // will eventually time it out and release the lock.
                RTP.log(Level.WARNING,
                        "[RTP][lobbyRetry] advance threw for playerId="
                                + entry.playerId + ": " + t.getMessage(), t);
            }
        }
    }

    /** Single-entry advance. Caller holds no lock. */
    private void advance(Entry entry, long now) {
        // TTL gate (wall-clock).
        if (now - entry.createdAtMs >= maxTotalMs) {
            terminate(entry, NetworkMessages.networkRegionUnavailable,
                    "timeout after " + (now - entry.createdAtMs) + "ms");
            return;
        }
        entry.attempts++;
        // Attempt cap.
        if (entry.attempts > maxAttempts) {
            terminate(entry, NetworkMessages.networkRegionUnavailable,
                    "exhausted " + maxAttempts + " attempts");
            return;
        }

        // Re-evaluate routing. Pull the same region= arg the original
        // command used so hard-pin semantics are preserved across retries.
        String regionArg = null;
        List<String> regionList = entry.args.get("region");
        if (regionList != null && !regionList.isEmpty()) {
            regionArg = regionList.get(0);
        }
        String regionKey;
        String serverHint;
        try {
            NetworkRouter.ParsedRegion parsed = NetworkRouter.parseRegionArgQualified(regionArg);
            regionKey = (parsed == null) ? null : parsed.regionKey();
            serverHint = (parsed == null) ? null : parsed.serverHint();
        } catch (IllegalArgumentException malformed) {
            // The player typed malformed `server:region`. Terminal -
            // retrying will not help.
            terminate(entry, NetworkMessages.networkRegionUnavailable,
                    "malformed region arg: " + regionArg);
            return;
        }

        RoutingDecision decision = router.route(entry.playerId, regionKey, serverHint);

        if (decision instanceof RoutingDecision.CrossServer cs) {
            // Success path: enrol on the cross-server queue. The
            // notifier takes over from here. Lock is RETAINED so the
            // player cannot re-issue /rtp until the proxy resolves.
            UUID correlationId = UUID.randomUUID();
            enrolmentBuffer.offer(new NetworkEnrolmentBuffer.EnrolmentRecord(
                    entry.playerId,
                    correlationId,
                    cs.regionKey(),
                    cs.serverHint(),
                    now));
            parked.remove(entry.playerId);
            RTP.log(Level.FINE,
                    "[RTP][state] lobbyRetry enrolled after " + entry.attempts
                            + " attempts: playerId=" + entry.playerId
                            + " correlationId=" + correlationId
                            + " region=" + cs.regionKey().orElse("")
                            + " server=" + cs.serverHint().orElse(""));
            // The lobby already sent the initial networkQueued message
            // at enqueue time (synthetic-CrossServer return from the
            // hook). No follow-up needed here; the waitlist notifier
            // will pick up the position updates from the status cache.
            return;
        }
        if (decision instanceof RoutingDecision.LocalFallback fallback) {
            RoutingDecision.FallbackReason reason = fallback.reason();
            if (reason == RoutingDecision.FallbackReason.REGION_UNAVAILABLE
                    || reason == RoutingDecision.FallbackReason.UNKNOWN) {
                // Terminal: retrying does not help.
                terminate(entry, NetworkMessages.networkRegionUnavailable, reason.name());
                return;
            }
            // Transient gate still tripped. Leave the entry parked; the
            // next pulse will retry. attempts++ already happened above.
            RTP.log(Level.FINEST,
                    "[RTP][lobbyRetry] still transient (" + reason
                            + ") for playerId=" + entry.playerId
                            + " attempt=" + entry.attempts);
            return;
        }
        if (decision instanceof RoutingDecision.Local) {
            // Cannot happen on a lobby (no local regions), but defensive:
            // treat as terminal - the lobby cannot serve locally.
            terminate(entry, NetworkMessages.networkRegionUnavailable,
                    "router returned Local on a lobby");
            return;
        }
        // Defensive: unknown subtype.
        terminate(entry, NetworkMessages.networkRegionUnavailable,
                "unhandled routing decision: " + decision.getClass().getName());
    }

    /** Drop the entry, send a terminal message, release the lock. */
    private void terminate(Entry entry, Enum<?> key, String reasonDetail) {
        parked.remove(entry.playerId);
        sendLocalized(entry, key, reasonDetail);
        releaseLock(entry.playerId);
        RTP.log(Level.FINE,
                "[RTP][state] lobbyRetry terminated: playerId=" + entry.playerId
                        + " reason=" + reasonDetail
                        + " attempts=" + entry.attempts);
    }

    @SuppressWarnings("unchecked")
    private static void sendLocalized(Entry entry, Enum<?> key, String placeholder) {
        String tmpl;
        try {
            tmpl = (String) RTP.configs.getConfigValue(key, "");
        } catch (Throwable t) {
            tmpl = "";
        }
        if (tmpl == null || tmpl.isEmpty()) {
            // Hardcoded English fallback - mirrors the network-queued pattern
            // used by RTPCmd's CrossServer branch and by
            // NetworkWaitlistGuard.
            tmpl = "&c[RTP] Could not place you on a cross-server destination ([region]).";
        }
        String msg = tmpl
                .replace("[region]", placeholder == null ? "" : placeholder)
                .replace("[reason]", placeholder == null ? "" : placeholder);
        if (entry.messageMethod != null) {
            try { entry.messageMethod.accept(msg); }
            catch (Throwable t) { /* best-effort */ }
        } else if (RTP.serverAccessor != null) {
            try { RTP.serverAccessor.sendMessage(entry.playerId, msg); }
            catch (Throwable t) { /* best-effort */ }
        }
    }

    private static void releaseLock(UUID playerId) {
        try {
            RTP.getInstance().processingPlayers.remove(playerId);
        } catch (Throwable t) {
            // Best-effort: RTP may be mid-shutdown.
        }
    }
}
