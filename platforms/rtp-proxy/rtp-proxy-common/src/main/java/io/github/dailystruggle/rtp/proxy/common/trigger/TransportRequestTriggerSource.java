package io.github.dailystruggle.rtp.proxy.common.trigger;

import io.github.dailystruggle.rtp.proxy.common.spi.DispatchOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpDispatcher;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;
import io.github.dailystruggle.rtp.proxy.common.spi.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The proxy-side trigger
 * source that drives {@code /rtp} dispatch off the cross-server wait queue.
 *
 * <p>This replaces {@code CommandTriggerSource} on the proxy: the proxy no
 * longer hosts a {@code /rtp} Brigadier command. Instead, backends enrol
 * locally and ship batches into the shared {@link NetworkRequestQueue};
 * this trigger source runs one or more BLPOP-style workers that
 * {@code dequeueReady} envelopes and hand them to the
 * {@link RtpDispatcher}.</p>
 *
 * <p><b>Threading.</b> Each worker is a daemon thread that loops on
 * {@link NetworkRequestQueue#dequeueReady(Duration)}. The blocking wait
 * lives inside the queue implementation's executor; we await the resulting
 * future with a small upper bound (1s past {@code pollTimeout}) so
 * {@link #stop()} can interrupt promptly. {@code dispatcher.dispatch} is
 * invoked from the worker thread, but the dispatcher itself is async and
 * returns immediately, so we do not hold the worker for the full teleport
 * pipeline.</p>
 *
 * <p><b>S-004 contract.</b> Every failure mode logs a WARNING and continues
 * the loop. The worker never silently swallows a dequeue or dispatch
 * exception. The only "no-op" path is the timeout where {@code dequeueReady}
 * resolves to {@link Optional#empty()}.</p>
 *
 * <p><b>Lifecycle.</b> {@link #start()} is single-shot per instance;
 * {@link #stop()} is idempotent and bounded by {@link #SHUTDOWN_TIMEOUT_MS}
 * (mirrors {@code RtpVelocityPlugin}'s transport-close deadline).</p>
 */
public final class TransportRequestTriggerSource {

    /** Bounded join window for stopping workers (CHECKLIST row E3). */
    public static final long SHUTDOWN_TIMEOUT_MS = 2_000L;
    private static final String NONE_LITERAL = "<none>";

    private final NetworkRequestQueue queue;
    private final RtpDispatcher dispatcher;
    private final int workerThreads;
    private final Duration pollTimeout;
    private final Logger logger;
    /**
     * Stable id of this proxy node; routed through to ownership-aware
     * {@link NetworkRequestQueue#dequeueReady(Duration, String)}. When
     * {@code null} or empty, the worker falls back to the legacy
     * {@link NetworkRequestQueue#dequeueReady(Duration)} (single-proxy /
     * in-memory transports). See {@code rtp-proxy-ADR-016}.
     */
    private final String thisProxyId;

    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;
    private volatile boolean started;
    private final AtomicLong dispatchedCount = new AtomicLong();
    /**
     * Count of consecutive {@code dequeueReady} timeouts (reset on any
     * successful await, empty or not). Surfaced in the timeout WARNING so a
     * persistent stall is distinguishable from a one-off hiccup - the former
     * points at queue-executor starvation (see the timeout catch block).
     */
    private final AtomicLong consecutiveTimeouts = new AtomicLong();

    /**
     * Build a transport-driven trigger source.
     *
     * @param queue         cross-server wait queue (shared SPI handle)
     * @param dispatcher    proxy dispatcher fed each popped envelope
     * @param workerThreads how many BLPOP workers to spin (clamped to {@code >= 1})
     * @param pollTimeout   max time a single dequeue waits before returning
     *                      empty (clamped to {@code >= 100ms} / {@code <= 30s})
     * @param logger        SLF4J logger (defaults to this class' logger when {@code null})
     */
    public TransportRequestTriggerSource(NetworkRequestQueue queue,
                                         RtpDispatcher dispatcher,
                                         int workerThreads,
                                         Duration pollTimeout,
                                         Logger logger) {
        this(queue, dispatcher, workerThreads, pollTimeout, logger, null);
    }

    /**
     * Ownership-aware constructor (rtp-proxy-ADR-016). When {@code thisProxyId}
     * is non-null and non-empty the worker uses
     * {@link NetworkRequestQueue#dequeueReady(Duration, String)} so foreign-
     * owned envelopes are skipped without losing FIFO order.
     */
    public TransportRequestTriggerSource(NetworkRequestQueue queue,
                                         RtpDispatcher dispatcher,
                                         int workerThreads,
                                         Duration pollTimeout,
                                         Logger logger,
                                         String thisProxyId) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.workerThreads = Math.max(1, workerThreads);
        Duration pt = Objects.requireNonNullElse(pollTimeout, Duration.ofSeconds(2));
        if (pt.toMillis() < 100L) pt = Duration.ofMillis(100L);
        if (pt.toMillis() > 30_000L) pt = Duration.ofSeconds(30);
        this.pollTimeout = pt;
        this.logger = (logger != null) ? logger
                : LoggerFactory.getLogger(TransportRequestTriggerSource.class);
        this.thisProxyId = (thisProxyId == null || thisProxyId.isEmpty()) ? null : thisProxyId;
    }

    /**
     * Spawn the worker threads and begin draining the queue. Single-shot:
     * re-invocation throws {@link IllegalStateException} (matches the
     * {@code CommandTriggerSource} contract).
     */
    public void start() {
        synchronized (this) {
            if (started) {
                throw new IllegalStateException(
                        "TransportRequestTriggerSource already started; create a new instance.");
            }
            started = true;
            running = true;
            for (int i = 0; i < workerThreads; i++) {
                Thread t = new Thread(this::workerLoop,
                        "rtp-proxy-request-worker-" + i);
                t.setDaemon(true);
                workers.add(t);
                t.start();
            }
            logger.info("RTP TransportRequestTriggerSource started: workers={}, pollTimeout={}ms.",
                    workerThreads, pollTimeout.toMillis());
        }
    }

    /**
     * Signal workers to stop, interrupt them, and join each within
     * {@link #SHUTDOWN_TIMEOUT_MS}. Idempotent. Logs a WARNING for any
     * worker that fails to exit within the deadline; in that case the
     * thread is abandoned (daemon, so JVM exit is unaffected).
     */
    public void stop() {
        synchronized (this) {
            if (!started) return;
            if (!running) return;
            running = false;
            long deadline = System.currentTimeMillis() + SHUTDOWN_TIMEOUT_MS;
            for (Thread t : workers) {
                t.interrupt();
            }
            for (Thread t : workers) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) remaining = 1;
                try {
                    t.join(remaining);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (t.isAlive()) {
                    logger.warn("RTP TransportRequestTriggerSource worker {} did not exit within {}ms; abandoning.",
                            t.getName(), SHUTDOWN_TIMEOUT_MS);
                }
            }
        }
    }

    /** @return true between {@link #start()} and the end of {@link #stop()}. */
    public boolean isRunning() {
        return running;
    }

    /** @return number of envelopes successfully handed to the dispatcher. */
    public long dispatchedCount() {
        return dispatchedCount.get();
    }

    /** Visible for tests: configured worker count. */
    public int workerThreads() {
        return workerThreads;
    }

    /** Visible for tests: configured poll timeout. */
    public Duration pollTimeout() {
        return pollTimeout;
    }

    private void workerLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            Optional<NetworkRequestQueue.QueueEnvelope> popped;
            try {
                CompletableFuture<Optional<NetworkRequestQueue.QueueEnvelope>> fut =
                        (thisProxyId != null)
                                ? queue.dequeueReady(pollTimeout, thisProxyId)
                                : queue.dequeueReady(pollTimeout);
                // Bound the await slightly past the poll timeout so stop()
                // can interrupt quickly even if the queue impl is slow to
                // honour the deadline.
                long awaitMs = pollTimeout.toMillis() + 1_000L;
                popped = fut.get(awaitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.TimeoutException te) {
                // The dequeueReady future did not resolve within pollTimeout+1s.
                // A bare TimeoutException carries NO cause, so the old one-line
                // warning could not explain the 'why'. The dominant real cause
                // is queue-executor starvation: the Redis/SQL queue impls run
                // every dequeue on a SINGLE-THREAD executor, so when more than
                // one worker blocks it for the full pollTimeout the queued
                // worker's task cannot even start (let alone finish) inside the
                // await bound. Other causes: Redis unreachable/slow, or a
                // GC/host stall. Surface all the context needed to tell these
                // apart instead of a bare 'exceeded Nms'.
                long streak = consecutiveTimeouts.incrementAndGet();
                long awaitMs = pollTimeout.toMillis() + 1_000L;
                logger.warn("RTP TransportRequestTriggerSource: dequeueReady on worker '{}' did not complete within {}ms "
                                + "(pollTimeout={}ms + 1000ms grace); consecutiveTimeouts={}, workerThreads={}, thisProxyId={}. "
                                + "Likely cause: the queue's async executor is saturated - the Redis/SQL NetworkRequestQueue "
                                + "services dequeues on a single thread, so workerThreads>1 starves it; reduce network worker "
                                + "threads to 1 or verify Redis reachability/latency. Continuing.",
                        Thread.currentThread().getName(), awaitMs, pollTimeout.toMillis(),
                        streak, workerThreads, thisProxyId == null ? NONE_LITERAL : thisProxyId);
                continue;
            } catch (java.util.concurrent.ExecutionException ee) {
                // Log the FULL cause chain (with stack) - the previous
                // getMessage()-only line printed 'null' for the many Jedis /
                // NPE style failures whose message is null, hiding the real
                // fault (S-004: never silently discard).
                consecutiveTimeouts.set(0L);
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                logger.warn("RTP TransportRequestTriggerSource: dequeueReady failed on worker '{}' ({}); continuing.",
                        Thread.currentThread().getName(), cause.getClass().getName(), cause);
                continue;
            } catch (RuntimeException re) {
                consecutiveTimeouts.set(0L);
                logger.warn("RTP TransportRequestTriggerSource: dequeueReady threw on worker '{}' ({}); continuing.",
                        Thread.currentThread().getName(), re.getClass().getName(), re);
                continue;
            }
            // Any resolved await (empty or not) clears the stall streak.
            consecutiveTimeouts.set(0L);
            if (popped == null || popped.isEmpty()) {
                // Timeout / empty queue. Loop.
                continue;
            }
            try {
                dispatchEnvelope(popped.get());
            } catch (RuntimeException re) {
                logger.warn("RTP TransportRequestTriggerSource: dispatchEnvelope threw for correlationId={}: {}",
                        popped.get().correlationId(), re.getMessage());
            }
        }
    }

    private void dispatchEnvelope(NetworkRequestQueue.QueueEnvelope env) {
        UUID playerId = env.playerId();
        UUID correlationId = env.correlationId();
        // Phase B trace (2026-05-23): worker has popped an envelope from the
        // queue and is about to hand it to the dispatcher. Logged at INFO so
        // devstack repros can confirm the proxy actually received the
        // envelope (vs. it never being enqueued, or being claimed by a
        // sibling proxy worker). Remove or downgrade once root cause known.
        logger.info(
                "[NETWORK][trace] TransportRequestTriggerSource.dispatchEnvelope: received envelope correlationId={} player={} serverHint={} regionKey={} thisProxyId={}",
                correlationId, playerId,
                env.serverHint().orElse(NONE_LITERAL),
                env.regionKey().orElse(NONE_LITERAL),
                thisProxyId == null ? NONE_LITERAL : thisProxyId);
        // env.serverHint() must reach the dispatcher in the dedicated
        // serverHint slot (added 2026-05-23) - previously it was smuggled
        // into the originServerId slot, which the BackendSelector does
        // not read, so every cross-server /rtp was load-balanced even
        // when the player typed `region=<server>:<region>`. The trigger
        // source is not the request's origin, so originServerId is empty.
        RtpRequest req = new RtpRequest(
                playerId,
                TriggerType.COMMAND,
                env.regionKey(),
                /*worldKey=*/Optional.empty(),
                /*serverHint=*/env.serverHint(),
                /*originServerId=*/Optional.empty(),
                correlationId
        );
        CompletableFuture<DispatchOutcome> fut;
        try {
            fut = dispatcher.dispatch(req);
        } catch (RuntimeException re) {
            logger.warn("RTP dispatcher.dispatch threw for correlationId={} player={}: {}",
                    correlationId, playerId, re.getMessage());
            return;
        }
        dispatchedCount.incrementAndGet();
        if (fut == null) {
            logger.warn("RTP dispatcher.dispatch returned null future for correlationId={} player={}.",
                    correlationId, playerId);
            return;
        }
        fut.whenComplete((outcome, err) -> {
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                logger.warn("RTP dispatcher failed for correlationId={} player={}: {}",
                        correlationId, playerId, cause.getMessage());
            }
            // Non-fatal outcomes are already emitted by the dispatcher's
            // StatusSink; we deliberately do not double-log.
        });
    }
}
