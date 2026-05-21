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
 * Slice E (CHECKLIST-cross-server-rtp-L6.md row E2): the proxy-side trigger
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

    private final NetworkRequestQueue queue;
    private final RtpDispatcher dispatcher;
    private final int workerThreads;
    private final Duration pollTimeout;
    private final Logger logger;

    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;
    private volatile boolean started;
    private final AtomicLong dispatchedCount = new AtomicLong();

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
        this.queue = Objects.requireNonNull(queue, "queue");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.workerThreads = Math.max(1, workerThreads);
        Duration pt = Objects.requireNonNullElse(pollTimeout, Duration.ofSeconds(2));
        if (pt.toMillis() < 100L) pt = Duration.ofMillis(100L);
        if (pt.toMillis() > 30_000L) pt = Duration.ofSeconds(30);
        this.pollTimeout = pt;
        this.logger = (logger != null) ? logger
                : LoggerFactory.getLogger(TransportRequestTriggerSource.class);
    }

    /**
     * Spawn the worker threads and begin draining the queue. Single-shot:
     * re-invocation throws {@link IllegalStateException} (matches the
     * {@code CommandTriggerSource} contract Slice E retires).
     */
    public synchronized void start() {
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

    /**
     * Signal workers to stop, interrupt them, and join each within
     * {@link #SHUTDOWN_TIMEOUT_MS}. Idempotent. Logs a WARNING for any
     * worker that fails to exit within the deadline; in that case the
     * thread is abandoned (daemon, so JVM exit is unaffected).
     */
    public synchronized void stop() {
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
                        queue.dequeueReady(pollTimeout);
                // Bound the await slightly past the poll timeout so stop()
                // can interrupt quickly even if the queue impl is slow to
                // honour the deadline.
                long awaitMs = pollTimeout.toMillis() + 1_000L;
                popped = fut.get(awaitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.TimeoutException te) {
                // Queue impl exceeded the contract; log and retry.
                logger.warn("RTP TransportRequestTriggerSource: dequeueReady exceeded {}ms wall-clock; continuing.",
                        pollTimeout.toMillis() + 1_000L);
                continue;
            } catch (java.util.concurrent.ExecutionException ee) {
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                logger.warn("RTP TransportRequestTriggerSource: dequeueReady failed: {}; continuing.",
                        cause.getMessage());
                continue;
            } catch (RuntimeException re) {
                logger.warn("RTP TransportRequestTriggerSource: dequeueReady threw: {}; continuing.",
                        re.getMessage());
                continue;
            }
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
        RtpRequest req = new RtpRequest(
                playerId,
                TriggerType.COMMAND,
                env.regionKey(),
                Optional.empty(),
                env.serverHint(),
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
            // StatusSink (Slice D6); we deliberately do not double-log.
        });
    }
}
