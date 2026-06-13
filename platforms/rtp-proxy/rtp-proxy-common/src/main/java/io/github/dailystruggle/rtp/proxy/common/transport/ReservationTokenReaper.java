package io.github.dailystruggle.rtp.proxy.common.transport;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseSink;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodic sweep that bulk-transitions every expired reservation token to
 * {@code RELEASED} via {@link NetworkTransport#reapExpired(Instant)} and then
 * dispatches a per-token {@link NetworkTransport#release(String, ReleaseReason)}
 * call with {@link ReleaseReason#TTL_EXPIRED} so the originating backend can
 * return the earmarked coordinate to its buffer (REQ-RTP-NET-011).
 *
 * <p>Why both calls. {@code reapExpired} is the atomic-state-transition slice
 * (row-count atomicity, REQ-RTP-PROXY-004) and is what stops a stale token
 * from being mistakenly redeemed at connect time. The per-token
 * {@code release(TTL_EXPIRED)} call is what propagates the event to whatever
 * listener the backend has wired against the release path - the canonical
 * channel for "return this coordinate to its buffer". Splitting the two keeps
 * the bulk operation atomic and the side-effect dispatchable through the same
 * release plumbing every other terminal transition uses.</p>
 *
 * <p>Lifecycle. The caller (typically the Velocity / BungeeCord adapter, or
 * a backend in proxy-aware mode) constructs a single reaper alongside the
 * {@link NetworkTransport}, calls {@link #start()} once the transport is
 * usable, and {@link #close()} during shutdown. Construction itself does
 * <em>not</em> schedule anything.</p>
 *
 * <p>Threading. The reaper owns a single-thread daemon scheduler. The
 * {@code reapExpired} and {@code release} futures complete on the transport's
 * own executor, never on the scheduler thread. The scheduler thread only
 * fires the periodic task and lets the future chain do the work, so the
 * transport's threading contract (REQ-RTP-PROXY-COMMON-001) is preserved.</p>
 *
 * <p>Failure handling. A transport error during reap is logged at WARNING
 * and the next interval retries. The reaper never throws past the scheduler
 * boundary - swallowed exceptions would silently kill the
 * {@link ScheduledExecutorService}'s periodic task.</p>
 */
public final class ReservationTokenReaper implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ReservationTokenReaper.class.getName());

    private final NetworkTransport transport;
    private final Duration interval;
    private final Supplier<Instant> clock;
    private final ReleaseSink releaseSink;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong reapedCount = new AtomicLong();
    private volatile ScheduledFuture<?> task;

    /**
     * Construct a reaper bound to {@code transport}, sweeping every
     * {@code interval}.
     *
     * @param transport the binding to sweep; must outlive the reaper
     * @param interval  positive sweep cadence (typically
     *                  {@code NetworkConfig.reapIntervalMs()})
     */
    public ReservationTokenReaper(NetworkTransport transport, Duration interval) {
        this(transport, interval, Instant::now, ReleaseSink.NO_OP);
    }

    /**
     * Construct a reaper with a test-only clock override. Production code
     * should use {@link #ReservationTokenReaper(NetworkTransport, Duration)}.
     */
    public ReservationTokenReaper(NetworkTransport transport, Duration interval, Supplier<Instant> clock) {
        this(transport, interval, clock, ReleaseSink.NO_OP);
    }

    /**
     * Construct a reaper that additionally fires {@code releaseSink} for every
     * token transitioned to a released state. Used by backends co-located with
     * the reaper (single-process / dev / test) to drive
     * {@code RegionQueueManager.releaseToNetworkKept(UUID)} without a
     * cross-process channel (REQ-RTP-NET-011).
     */
    public ReservationTokenReaper(NetworkTransport transport,
                                  Duration interval,
                                  Supplier<Instant> clock,
                                  ReleaseSink releaseSink) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.releaseSink = Objects.requireNonNull(releaseSink, "releaseSink");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive: " + interval);
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rtp-proxy-reservation-reaper");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the periodic sweep. Idempotent: a second {@code start} is a no-op.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        long ms = interval.toMillis();
        task = scheduler.scheduleWithFixedDelay(this::tick, ms, ms, TimeUnit.MILLISECONDS);
    }

    /**
     * Drive one sweep synchronously (test-only hook). Production code uses
     * {@link #start()} and lets the scheduler fire. Returns the number of
     * tokens reaped on this call.
     */
    public int reapNow() {
        try {
            List<String> ids = transport.reapExpired(clock.get()).join();
            dispatchReleases(ids);
            reapedCount.addAndGet(ids.size());
            return ids.size();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "ReservationTokenReaper.reapNow: transport call failed", e);
            return 0;
        }
    }

    private void tick() {
        try {
            transport.reapExpired(clock.get())
                    .thenAccept(ids -> {
                        dispatchReleases(ids);
                        reapedCount.addAndGet(ids.size());
                    })
                    .exceptionally(t -> {
                        LOG.log(Level.WARNING,
                                "ReservationTokenReaper.tick: reapExpired failed; will retry next interval", t);
                        return null;
                    });
        } catch (RuntimeException e) {
            // Defensive: the scheduler silently kills repeating tasks that throw.
            LOG.log(Level.WARNING,
                    "ReservationTokenReaper.tick: synchronous failure dispatching reap", e);
        }
    }

    private void dispatchReleases(List<String> tokenIds) {
        for (String tokenId : tokenIds) {
            transport.release(tokenId, ReleaseReason.TTL_EXPIRED)
                    .exceptionally(t -> {
                        LOG.log(Level.WARNING,
                                "ReservationTokenReaper: release(TTL_EXPIRED) failed for token "
                                        + tokenId + "; backend buffer may not have been notified", t);
                        return null;
                    });
            // S-004: fire the in-process sink regardless of transport release outcome.
            // A faulty sink must not mask the release event or kill the scheduler.
            try {
                releaseSink.onRelease(tokenId, ReleaseReason.TTL_EXPIRED);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING,
                        "ReservationTokenReaper: ReleaseSink threw for token " + tokenId
                                + " (" + ReleaseReason.TTL_EXPIRED + "); continuing sweep", e);
            }
        }
    }

    /** Total number of tokens reaped over the reaper's lifetime; test/metrics hook. */
    public long reapedCount() {
        return reapedCount.get();
    }

    /** Whether the periodic sweep is currently scheduled. */
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false) && task == null) {
            // Never started; still shut the scheduler down so we do not leak the thread.
            scheduler.shutdownNow();
            return;
        }
        ScheduledFuture<?> t = task;
        if (t != null) t.cancel(false);
        scheduler.shutdownNow();
    }
}
