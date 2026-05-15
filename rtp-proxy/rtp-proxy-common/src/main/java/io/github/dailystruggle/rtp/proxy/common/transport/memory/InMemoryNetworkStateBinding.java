package io.github.dailystruggle.rtp.proxy.common.transport.memory;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reference {@link NetworkTransport} implementation backed by in-process
 * concurrent collections. Pinned by rtp-proxy-ADR-003.
 *
 * <p><strong>Production use is unsupported.</strong> State lives in this JVM
 * only; deploying this binding on multiple hosts each gives a different view
 * of the world. Intended for: unit tests of dispatcher / selector /
 * reservation race semantics, the REQ-RTP-NET-002 no-op contract test, and
 * single-host development.</p>
 *
 * <p>Async semantics are preserved: every SPI call hops off the caller's
 * thread onto an internal pool of size 2, so a consumer that mistakenly
 * blocks on a returned future will deadlock the same way it would against a
 * real binding.</p>
 */
public final class InMemoryNetworkStateBinding implements NetworkTransport {

    private final ConcurrentHashMap<String, BackendHeartbeat> backends = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProxyHeartbeat> proxies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReservationToken> tokens = new ConcurrentHashMap<>();
    /** Players currently holding a PENDING/CLAIMED token, used to enforce per-player idempotency. */
    private final ConcurrentHashMap<UUID, String> activeByPlayer = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Sub> subscribers = new CopyOnWriteArrayList<>();
    private final ExecutorService executor =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "rtp-proxy-inmemory-binding");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Supplier<Instant> clock = Instant::now;

    /**
     * Test-only hook for deterministic TTL / staleness assertions.
     * Adapter code must not touch this in production paths.
     */
    public void setClock(Supplier<Instant> clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Test affordance: publish a backend heartbeat into the local table. */
    public void publishBackendHeartbeat(BackendHeartbeat row) {
        Objects.requireNonNull(row, "row");
        backends.put(row.serverId(), row);
        for (Sub sub : subscribers) {
            if (!sub.closed.get()) {
                try {
                    sub.sink.accept(row);
                } catch (RuntimeException ignored) {
                    // Subscribers are responsible for their own error handling
                    // per rtp-proxy-ADR-001 §Threading Contract.
                }
            }
        }
    }

    @Override
    public CompletableFuture<NetworkSnapshot> readSnapshot() {
        checkOpen();
        return CompletableFuture.supplyAsync(() ->
                new NetworkSnapshot(clock.get().toEpochMilli(),
                        new LinkedHashMap<>(backends)),
                executor);
    }

    @Override
    public CompletableFuture<ReservationToken> claim(String serverId, UUID playerId, Duration ttl) {
        checkOpen();
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(ttl, "ttl");
        return CompletableFuture.supplyAsync(() -> {
            long expires = clock.get().toEpochMilli() + ttl.toMillis();
            String tokenId = UUID.randomUUID().toString();
            ReservationToken token = new ReservationToken(
                    tokenId, serverId, playerId, expires, ReservationToken.State.PENDING);
            // Per-player idempotency: only one in-flight token per player.
            String prior = activeByPlayer.putIfAbsent(playerId, tokenId);
            if (prior != null) {
                throw new IllegalStateException(
                        "Reservation race: player " + playerId + " already holds " + prior);
            }
            tokens.put(tokenId, token);
            // Atomic PENDING→CLAIMED transition (REQ-RTP-PROXY-004).
            if (!token.transition(
                    ReservationToken.State.PENDING, ReservationToken.State.CLAIMED)) {
                activeByPlayer.remove(playerId, tokenId);
                tokens.remove(tokenId);
                throw new IllegalStateException("Claim race lost on token " + tokenId);
            }
            return token;
        }, executor);
    }

    @Override
    public CompletableFuture<Void> release(String tokenId, ReleaseReason reason) {
        checkOpen();
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(reason, "reason");
        return CompletableFuture.runAsync(() -> {
            ReservationToken token = tokens.get(tokenId);
            if (token == null) {
                return; // idempotent: release of unknown token is a no-op
            }
            ReservationToken.State next = (reason == ReleaseReason.CONSUMED)
                    ? ReservationToken.State.CONSUMED
                    : ReservationToken.State.RELEASED;
            ReservationToken.State current = token.state();
            token.transition(current, next);
            tokens.remove(tokenId);
            activeByPlayer.remove(token.playerId(), tokenId);
        }, executor);
    }

    @Override
    public CompletableFuture<Void> publishProxyHeartbeat(ProxyHeartbeat row) {
        checkOpen();
        Objects.requireNonNull(row, "row");
        return CompletableFuture.runAsync(() -> proxies.put(row.proxyId(), row), executor);
    }

    @Override
    public Subscription subscribeBackendHeartbeats(Consumer<BackendHeartbeat> sink) {
        checkOpen();
        Objects.requireNonNull(sink, "sink");
        Sub sub = new Sub(sink);
        subscribers.add(sub);
        return sub;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdown();
            subscribers.forEach(Sub::close);
            subscribers.clear();
        }
    }

    private void checkOpen() {
        if (closed.get()) {
            throw new IllegalStateException("InMemoryNetworkStateBinding is closed");
        }
    }

    // ---------- Test affordances (rtp-proxy-ADR-003 §Test Affordances) ----------

    /** Returns an immutable snapshot of the reservation table. */
    public Map<String, ReservationToken> dumpTokens() {
        return Map.copyOf(tokens);
    }

    /** Returns an immutable snapshot of known backend rows. */
    public Map<String, BackendHeartbeat> dumpBackends() {
        return Map.copyOf(backends);
    }

    /** Returns an immutable snapshot of known proxy rows. */
    public Map<String, ProxyHeartbeat> dumpProxies() {
        return Map.copyOf(proxies);
    }

    /** Returns the list of currently active subscription handles (test only). */
    public List<Subscription> activeSubscriptions() {
        return List.copyOf(subscribers);
    }

    private static final class Sub implements Subscription {
        private final Consumer<BackendHeartbeat> sink;
        private final AtomicBoolean closed = new AtomicBoolean();

        Sub(Consumer<BackendHeartbeat> sink) { this.sink = sink; }

        @Override public void close() { closed.set(true); }
        @Override public boolean isClosed() { return closed.get(); }
    }
}
