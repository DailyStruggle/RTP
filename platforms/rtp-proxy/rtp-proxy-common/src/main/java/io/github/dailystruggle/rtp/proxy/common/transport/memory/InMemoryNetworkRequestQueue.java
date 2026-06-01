package io.github.dailystruggle.rtp.proxy.common.transport.memory;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reference {@link NetworkRequestQueue} implementation backed by in-process
 * concurrent collections. Slice D row D2 of `CHECKLIST-cross-server-rtp-L6.md`.
 *
 * <p><strong>Production use is unsupported.</strong> State lives in this JVM
 * only. Intended for: unit tests of the dispatcher / status sink, the
 * Slice C buffer/cache adapter wiring, and the no-op fallback path of
 * {@code NetworkBindings} when {@code transport.kind = memory}.</p>
 *
 * <p>Async semantics mirror {@link InMemoryNetworkStateBinding}: every SPI
 * call hops off the caller's thread onto an internal single-thread pool, so
 * a consumer that blocks on a returned future will deadlock the same way it
 * would against a real binding.</p>
 *
 * <p>Capacity: unbounded. Operators wiring this in production paths should
 * front it with the backend's own {@code queueMaxDepth} guard.</p>
 */
public final class InMemoryNetworkRequestQueue implements NetworkRequestQueue {

    /** FIFO of envelopes awaiting proxy dequeue. */
    private final ConcurrentLinkedDeque<QueueEnvelope> ready = new ConcurrentLinkedDeque<>();

    /**
     * Per-player live row. Linked map only so {@link #pollStatus} returns
     * a deterministic order; concurrent reads are guarded by synchronized
     * blocks on {@code statuses} for transition CAS-equivalence.
     */
    private final Map<UUID, QueueStatus> statuses = new LinkedHashMap<>();

    /** Correlation ids we've already accepted (idempotency guard). */
    private final Set<UUID> seenCorrelations = ConcurrentHashMap.newKeySet();

    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public InMemoryNetworkRequestQueue() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rtp-inmem-network-request-queue");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public CompletableFuture<EnrolOutcome> enrol(EnrolmentEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        return runAsync(() -> {
            if (closed.get()) return EnrolOutcome.REJECTED;
            if (!seenCorrelations.add(envelope.correlationId())) {
                // idempotent replay
                return EnrolOutcome.ACCEPTED;
            }
            long now = System.currentTimeMillis();
            QueueEnvelope qe = new QueueEnvelope(
                    envelope.playerId(),
                    envelope.correlationId(),
                    envelope.regionKey(),
                    envelope.serverHint(),
                    envelope.createdAtMs(),
                    0L);
            ready.add(qe);
            putStatus(new QueueStatus(
                    envelope.playerId(),
                    QueueState.QUEUED,
                    0,
                    Optional.empty(),
                    envelope.regionKey(),
                    now));
            return EnrolOutcome.ACCEPTED;
        });
    }

    @Override
    public CompletableFuture<EnrolOutcome> flushPending(List<EnrolmentEnvelope> batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.isEmpty()) return CompletableFuture.completedFuture(EnrolOutcome.ACCEPTED);
        return runAsync(() -> {
            if (closed.get()) return EnrolOutcome.REJECTED;
            long now = System.currentTimeMillis();
            for (EnrolmentEnvelope env : batch) {
                if (env == null) continue;
                if (!seenCorrelations.add(env.correlationId())) continue;
                ready.add(new QueueEnvelope(
                        env.playerId(),
                        env.correlationId(),
                        env.regionKey(),
                        env.serverHint(),
                        env.createdAtMs(),
                        0L));
                putStatus(new QueueStatus(
                        env.playerId(),
                        QueueState.QUEUED,
                        0,
                        Optional.empty(),
                        env.regionKey(),
                        now));
            }
            return EnrolOutcome.ACCEPTED;
        });
    }

    @Override
    public CompletableFuture<List<QueueStatus>> pollStatus(List<UUID> playerIds) {
        Objects.requireNonNull(playerIds, "playerIds");
        return runAsync(() -> {
            List<QueueStatus> out = new ArrayList<>(playerIds.size());
            synchronized (statuses) {
                for (UUID id : playerIds) {
                    if (id == null) continue;
                    QueueStatus s = statuses.get(id);
                    if (s != null) out.add(s);
                }
            }
            return Collections.unmodifiableList(out);
        });
    }

    @Override
    public CompletableFuture<Optional<QueueEnvelope>> dequeueReady(Duration blockFor) {
        Objects.requireNonNull(blockFor, "blockFor");
        return CompletableFuture.supplyAsync(() -> {
            long deadlineNs = System.nanoTime() + Math.max(0L, blockFor.toNanos());
            while (true) {
                if (closed.get()) return Optional.<QueueEnvelope>empty();
                QueueEnvelope head = ready.pollFirst();
                if (head != null) {
                    long now = System.currentTimeMillis();
                    QueueEnvelope stamped = new QueueEnvelope(
                            head.playerId(), head.correlationId(),
                            head.regionKey(), head.serverHint(),
                            head.enrolledAtMs(), now);
                    return Optional.of(stamped);
                }
                long remaining = deadlineNs - System.nanoTime();
                if (remaining <= 0L) return Optional.<QueueEnvelope>empty();
                try {
                    // simple poll cadence; production binding uses real BLPOP
                    Thread.sleep(Math.min(25L, Math.max(1L, remaining / 1_000_000L)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Optional.<QueueEnvelope>empty();
                }
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<QueueStatus>> transition(
            UUID playerId, QueueState next, Optional<String> reason) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(reason, "reason");
        return runAsync(() -> {
            synchronized (statuses) {
                QueueStatus prev = statuses.get(playerId);
                if (prev == null) return Optional.<QueueStatus>empty();
                QueueStatus updated = new QueueStatus(
                        prev.playerId(),
                        next,
                        prev.positionInQueue(),
                        prev.serverId(),
                        prev.regionKey(),
                        System.currentTimeMillis());
                if (isTerminal(next)) {
                    statuses.remove(playerId);
                } else {
                    statuses.put(playerId, updated);
                }
                return Optional.of(updated);
            }
        });
    }

    @Override
    public CompletableFuture<Void> cancel(UUID playerId, CancelReason reason) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(reason, "reason");
        return runAsync(() -> {
            synchronized (statuses) {
                statuses.remove(playerId);
            }
            // best-effort scrub of any unpopped ready entry
            ready.removeIf(q -> playerId.equals(q.playerId()));
            return null;
        });
    }

    /** Visible for tests. */
    public int readyDepth() { return ready.size(); }

    /** Visible for tests. */
    public int statusCount() {
        synchronized (statuses) { return statuses.size(); }
    }

    /** Visible for tests. Snapshot copy. */
    public Set<UUID> trackedPlayers() {
        synchronized (statuses) { return new HashSet<>(statuses.keySet()); }
    }

    /** Idempotent. */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
        ready.clear();
        synchronized (statuses) { statuses.clear(); }
        seenCorrelations.clear();
    }

    private void putStatus(QueueStatus s) {
        synchronized (statuses) {
            statuses.put(s.playerId(), s);
        }
    }

    private <T> CompletableFuture<T> runAsync(java.util.concurrent.Callable<T> body) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return body.call();
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    private static boolean isTerminal(QueueState s) {
        return s == QueueState.COMPLETED
                || s == QueueState.FAILED
                || s == QueueState.CANCELLED;
    }
}
