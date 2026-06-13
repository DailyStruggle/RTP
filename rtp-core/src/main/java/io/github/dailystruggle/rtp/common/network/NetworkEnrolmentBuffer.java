package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.common.RTP;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Dirty-write batching buffer for cross-server enrolments.
 *
 * <p>Threading: producers ({@code /rtp} command path) call
 * {@link #offer(EnrolmentRecord)} cheaply on whatever thread Bukkit gave
 * them. A single async timer started by {@link #start(long)} drains the
 * deque on a fixed interval (default {@code network.queueFlushIntervalMs =
 * 250ms}) and hands the batch to the injected {@code flushSink}. The sink
 * does the actual transport-specific write (one pipelined Redis {@code EVAL}
 * per flush; in-memory no-op in tests).</p>
 *
 * <p>S-004: if the sink throws, the failed batch is re-enqueued at the head
 * so the next pulse retries; the exception is logged. Silent drop would
 * violate S-004's "no swallowed teleport failures" rule.</p>
 */
public final class NetworkEnrolmentBuffer {

    /**
     * One pending cross-server enrolment. Carries everything the Lua
     * {@code enqueue_batch.lua} needs.
     */
    public record EnrolmentRecord(
            UUID playerId,
            UUID correlationId,
            Optional<String> regionKey,
            Optional<String> serverHint,
            long createdAtMs
    ) {
        public EnrolmentRecord {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(correlationId, "correlationId");
            if (regionKey == null) regionKey = Optional.empty();
            if (serverHint == null) serverHint = Optional.empty();
        }
    }

    private final ConcurrentLinkedDeque<EnrolmentRecord> deque = new ConcurrentLinkedDeque<>();
    private final Consumer<List<EnrolmentRecord>> flushSink;
    private final int maxBatchSize;
    private volatile Object timerTaskHandle;

    /**
     * @param flushSink    receives one List per flush pulse; must be
     *                     thread-safe (called from the scheduler's async tier)
     * @param maxBatchSize hard cap per flush pulse; {@code <= 0} means
     *                     unlimited (drain the whole deque)
     */
    public NetworkEnrolmentBuffer(Consumer<List<EnrolmentRecord>> flushSink, int maxBatchSize) {
        this.flushSink = Objects.requireNonNull(flushSink, "flushSink");
        this.maxBatchSize = maxBatchSize;
    }

    /** Producer entry. Lock-free; safe from any thread. */
    public void offer(EnrolmentRecord record) {
        if (record == null) return;
        deque.addLast(record);
    }

    /** Visible for tests / metrics. */
    public int pendingDepth() { return deque.size(); }

    /**
     * Start the periodic flush timer on {@link RTP#scheduler}'s async tier.
     * Idempotent: a second call returns the existing handle. {@code period}
     * is in server ticks (20 ticks per second) to match
     * {@link io.github.dailystruggle.rtp.api.scheduling.RTPScheduler#runTaskTimerAsynchronously}.
     */
    public synchronized void start(long periodTicks) {
        if (timerTaskHandle != null) return;
        if (RTP.scheduler == null) {
            RTP.log(Level.WARNING,
                    "[NETWORK] NetworkEnrolmentBuffer.start called before scheduler available; "
                            + "flush timer not started.");
            return;
        }
        timerTaskHandle = RTP.scheduler.runTaskTimerAsynchronously(this::flushOnce, periodTicks, periodTicks);
    }

    /**
     * Manual flush. Visible for tests so the timing of a pulse can be
     * deterministic; also called by the timer.
     */
    public void flushOnce() {
        if (deque.isEmpty()) return;
        List<EnrolmentRecord> batch = new ArrayList<>();
        int cap = maxBatchSize <= 0 ? Integer.MAX_VALUE : maxBatchSize;
        EnrolmentRecord r;
        while (batch.size() < cap && (r = deque.pollFirst()) != null) {
            batch.add(r);
        }
        if (batch.isEmpty()) return;
        try {
            flushSink.accept(batch);
        } catch (Throwable t) {
            // S-004: re-enqueue the failed batch at the head so the next
            // pulse retries. We push them back in reverse order to preserve
            // FIFO ordering of the original producers.
            for (int i = batch.size() - 1; i >= 0; i--) {
                deque.addFirst(batch.get(i));
            }
            RTP.log(Level.WARNING,
                    "[NETWORK] enrolment flush failed (" + batch.size()
                            + " records re-queued): " + t.getMessage(), t);
        }
    }

    /** Idempotent. */
    public synchronized void shutdown() {
        if (timerTaskHandle != null && RTP.scheduler != null) {
            try { RTP.scheduler.cancelTask(timerTaskHandle); } catch (Throwable ignored) { /* best-effort */ }
        }
        timerTaskHandle = null;
    }
}
