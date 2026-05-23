package io.github.dailystruggle.rtp.proxy.common.transport.memory;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reference {@link NetworkWaitlist} implementation backed by in-process
 * concurrent collections. Slice 1 of `CHECKLIST-network-waitlist.md`.
 *
 * <p><strong>Production use is unsupported.</strong> State lives in this JVM
 * only; a multi-proxy deployment would diverge immediately. Intended for:
 * unit tests of the drainer / dispatcher, the in-memory devstack path, and
 * the no-op fallback path of network bindings when
 * {@code transport.kind = memory}.</p>
 *
 * <p>Async semantics mirror {@link InMemoryNetworkRequestQueue}: every SPI
 * call hops off the caller's thread onto an internal single-thread pool, so
 * a consumer that blocks on a returned future will deadlock the same way
 * it would against a real binding.</p>
 *
 * <p>Capacity: bounded by the {@code maxSize} constructor arg. The
 * {@link NetworkWaitlist#enrol(NetworkWaitlist.WaitEnvelope) enrol} method
 * returns {@link EnrolOutcome#REJECTED_FULL} when the waitlist is at
 * capacity, per S-004.</p>
 */
public final class InMemoryNetworkWaitlist implements NetworkWaitlist {

    /** Default {@code maxSize} when the no-arg constructor is used. */
    public static final int DEFAULT_MAX_SIZE = 1024;

    /**
     * FIFO of live entries. Linked map keyed by {@code playerId} so:
     * <ul>
     *   <li>iteration order is enrolment order ({@code position(UUID)} is
     *       a single linear scan);</li>
     *   <li>duplicate-playerId rejection is O(1);</li>
     *   <li>point-removal by playerId is O(1).</li>
     * </ul>
     * All access is guarded by {@code synchronized(entries)} to keep
     * {@code drainBatch}, {@code remove}, and {@code enrol} mutually
     * exclusive on a single instance.
     */
    private final LinkedHashMap<UUID, WaitEnvelope> entries = new LinkedHashMap<>();

    /** Correlation ids already accepted (idempotency guard). */
    private final Set<UUID> seenCorrelations = ConcurrentHashMap.newKeySet();

    private final int maxSize;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public InMemoryNetworkWaitlist() {
        this(DEFAULT_MAX_SIZE);
    }

    public InMemoryNetworkWaitlist(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be > 0, got " + maxSize);
        }
        this.maxSize = maxSize;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rtp-inmem-network-waitlist");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public CompletableFuture<EnrolOutcome> enrol(WaitEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        return runAsync(() -> {
            if (closed.get()) return EnrolOutcome.REJECTED_CLOSED;
            // Idempotent replay - same correlationId resolves ACCEPTED without
            // a second insert, even if the player's prior entry has been removed.
            if (!seenCorrelations.add(envelope.correlationId())) {
                return EnrolOutcome.ACCEPTED;
            }
            synchronized (entries) {
                if (entries.containsKey(envelope.playerId())) {
                    return EnrolOutcome.REJECTED_DUPLICATE;
                }
                if (entries.size() >= maxSize) {
                    return EnrolOutcome.REJECTED_FULL;
                }
                entries.put(envelope.playerId(), envelope);
                return EnrolOutcome.ACCEPTED;
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, List<WaitEnvelope>>> drainBatch(
            Map<String, Integer> perBackendCaps, int globalCap) {
        Objects.requireNonNull(perBackendCaps, "perBackendCaps");
        if (globalCap <= 0 || perBackendCaps.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }
        return runAsync(() -> {
            if (closed.get()) return Collections.<String, List<WaitEnvelope>>emptyMap();
            Map<String, Integer> remaining = new HashMap<>();
            for (Map.Entry<String, Integer> e : perBackendCaps.entrySet()) {
                Integer cap = e.getValue();
                if (cap != null && cap > 0) {
                    remaining.put(e.getKey(), cap);
                }
            }
            if (remaining.isEmpty()) {
                return Collections.<String, List<WaitEnvelope>>emptyMap();
            }
            Map<String, List<WaitEnvelope>> out = new LinkedHashMap<>();
            int taken = 0;
            synchronized (entries) {
                Iterator<Map.Entry<UUID, WaitEnvelope>> it = entries.entrySet().iterator();
                while (it.hasNext() && taken < globalCap) {
                    WaitEnvelope env = it.next().getValue();
                    // Round-robin-ish: take any backend that still has cap.
                    // Region-aware selection is a caller concern (Slice 2);
                    // here we simply pick the first remaining backend with
                    // capacity, preserving FIFO order across the waitlist.
                    String chosen = pickBackend(remaining);
                    if (chosen == null) break;
                    out.computeIfAbsent(chosen, k -> new ArrayList<>()).add(env);
                    int after = remaining.get(chosen) - 1;
                    if (after <= 0) {
                        remaining.remove(chosen);
                    } else {
                        remaining.put(chosen, after);
                    }
                    it.remove();
                    taken++;
                }
            }
            return out;
        });
    }

    private static String pickBackend(Map<String, Integer> remaining) {
        // Deterministic for tests: smallest-keyed serverId with cap > 0.
        String pick = null;
        for (Map.Entry<String, Integer> e : remaining.entrySet()) {
            if (e.getValue() <= 0) continue;
            if (pick == null || e.getKey().compareTo(pick) < 0) {
                pick = e.getKey();
            }
        }
        return pick;
    }

    @Override
    public CompletableFuture<Optional<Integer>> position(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return runAsync(() -> {
            synchronized (entries) {
                int rank = 1;
                for (UUID id : entries.keySet()) {
                    if (id.equals(playerId)) return Optional.of(rank);
                    rank++;
                }
                return Optional.<Integer>empty();
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> remove(UUID playerId, CancelReason reason) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(reason, "reason");
        return runAsync(() -> {
            synchronized (entries) {
                return entries.remove(playerId) != null;
            }
        });
    }

    @Override
    public CompletableFuture<Integer> reap(Duration maxAge) {
        Objects.requireNonNull(maxAge, "maxAge");
        if (maxAge.isNegative() || maxAge.isZero()) {
            return CompletableFuture.completedFuture(0);
        }
        return runAsync(() -> {
            long cutoff = System.currentTimeMillis() - maxAge.toMillis();
            int reaped = 0;
            synchronized (entries) {
                Iterator<Map.Entry<UUID, WaitEnvelope>> it = entries.entrySet().iterator();
                while (it.hasNext()) {
                    WaitEnvelope env = it.next().getValue();
                    if (env.enrolledAtMs() <= cutoff) {
                        it.remove();
                        reaped++;
                    } else {
                        // entries are FIFO-ordered by enrolment; the first
                        // non-stale entry terminates the sweep.
                        break;
                    }
                }
            }
            return reaped;
        });
    }

    @Override
    public CompletableFuture<Integer> size() {
        return runAsync(() -> {
            synchronized (entries) {
                return entries.size();
            }
        });
    }

    @Override
    public CompletableFuture<Integer> refreshAllTtl() {
        return runAsync(() -> {
            if (closed.get()) return 0;
            long now = System.currentTimeMillis();
            int refreshed = 0;
            synchronized (entries) {
                // Replace each value with a copy whose enrolledAtMs == now.
                // put-on-existing-key preserves LinkedHashMap insertion order,
                // so FIFO discipline (and the reap() break-on-first-non-stale
                // optimisation) survives the refresh.
                for (Map.Entry<UUID, WaitEnvelope> e : entries.entrySet()) {
                    WaitEnvelope old = e.getValue();
                    e.setValue(new WaitEnvelope(
                            old.playerId(),
                            old.correlationId(),
                            old.regionKey(),
                            old.serverHint(),
                            old.originServerId(),
                            now));
                    refreshed++;
                }
            }
            return refreshed;
        });
    }

    /** Shut down the executor. After this, every future call resolves to a closed-outcome or empty. */
    public void shutdown() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    /** Test helper: live entry count without going through {@link #size()}'s future. */
    public int sizeNow() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /** Test helper: total distinct correlationIds ever accepted (idempotency guard inspection). */
    public int seenCorrelationCount() {
        return seenCorrelations.size();
    }

    private <T> CompletableFuture<T> runAsync(Callable<T> body) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            executor.submit(() -> {
                try {
                    f.complete(body.call());
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            f.completeExceptionally(t);
        }
        return f;
    }
}
