package io.github.dailystruggle.rtp.common.network.pluginmessage;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import io.github.dailystruggle.rtp.proxy.common.transport.codec.BackendHeartbeatCodec;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Database-free {@link NetworkTransport} backed by proxy plugin-messaging via {@link NetworkBridge}.
 * Gossips {@link BackendHeartbeat} records across backends without Redis or SQL requirements.
 */
public final class PluginMessageNetworkBinding implements NetworkTransport {

    private static final Logger LOG = Logger.getLogger(PluginMessageNetworkBinding.class.getName());

    /** Default age beyond which a cached peer heartbeat is dropped from the snapshot. */
    public static final long DEFAULT_STALE_TIMEOUT_MILLIS = 1_500L;

    private final NetworkBridge bridge;
    private final long staleTimeoutMillis;
    private final LongSupplier clock;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private final Map<String, Entry> lastSeen = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Sub> subscribers = new CopyOnWriteArrayList<>();

    public PluginMessageNetworkBinding(NetworkBridge bridge) {
        this(bridge, DEFAULT_STALE_TIMEOUT_MILLIS, System::currentTimeMillis);
    }

    public PluginMessageNetworkBinding(NetworkBridge bridge, long staleTimeoutMillis, LongSupplier clock) {
        this.bridge = java.util.Objects.requireNonNull(bridge, "bridge");
        this.staleTimeoutMillis = staleTimeoutMillis > 0 ? staleTimeoutMillis : DEFAULT_STALE_TIMEOUT_MILLIS;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        bridge.registerInbound(this::onInbound);
    }

    // ---- heartbeat gossip ------------------------------------------------

    @Override
    public CompletableFuture<Void> publishBackendHeartbeat(BackendHeartbeat row) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("PluginMessageNetworkBinding is closed"));
        }
        try {
            byte[] payload = BackendHeartbeatCodec.encode(row).getBytes(StandardCharsets.UTF_8);
            bridge.broadcastHeartbeat(payload);
        } catch (Throwable t) {
            // S-004: a transmit failure (no carrier player, channel hiccup) is
            // logged, not swallowed silently and not propagated as a fatal
            // error - the next heartbeat tick retries.
            LOG.log(Level.FINE,
                    "[RTP] plugin-message heartbeat broadcast skipped: " + t.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    private void onInbound(byte[] payload) {
        if (payload == null || payload.length == 0) return;
        BackendHeartbeat hb;
        try {
            hb = BackendHeartbeatCodec.decode(new String(payload, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            LOG.log(Level.FINE, "[RTP] dropping malformed inbound heartbeat: " + t.getMessage());
            return;
        }
        if (hb == null) return;
        lastSeen.put(hb.serverId(), new Entry(hb, clock.getAsLong()));
        for (Sub s : subscribers) {
            if (!s.closed.get()) {
                try {
                    s.sink.accept(hb);
                } catch (RuntimeException ignored) {
                    // Subscriber-owned failure must not break fan-out.
                }
            }
        }
    }

    @Override
    public CompletableFuture<NetworkSnapshot> readSnapshot() {
        long now = clock.getAsLong();
        Map<String, BackendHeartbeat> live = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : lastSeen.entrySet()) {
            Entry entry = e.getValue();
            if (now - entry.seenAtMs <= staleTimeoutMillis) {
                live.put(e.getKey(), entry.heartbeat);
            }
        }
        return CompletableFuture.completedFuture(new NetworkSnapshot(now, live));
    }

    @Override
    public Subscription subscribeBackendHeartbeats(Consumer<BackendHeartbeat> sink) {
        Sub sub = new Sub(java.util.Objects.requireNonNull(sink, "sink"));
        subscribers.add(sub);
        return sub;
    }

    // ---- best-effort, non-durable reservation tier ----------------------

    @Override
    public CompletableFuture<ReservationToken> claim(String serverId, UUID playerId, Duration ttl) {
        // Non-durable tier: no atomic claim. The router short-circuits to the
        // local-fallback / connect path before reaching claim; if a caller
        // does reach here, fail loudly rather than mint a phantom token.
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "PluginMessageNetworkBinding is a non-durable tier and does not mint "
                        + "reservation tokens; use the SQL/Redis tier for durable claims."));
    }

    @Override
    public CompletableFuture<Void> release(String tokenId, ReleaseReason reason) {
        // No tokens are ever minted, so release is an idempotent no-op.
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> publishProxyHeartbeat(ProxyHeartbeat row) {
        // Proxy heartbeats are a durable-tier concern; no-op here.
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) return;
        subscribers.clear();
        lastSeen.clear();
    }

    /** Visible for tests: live (non-stale) peer count at call time. */
    public int livePeerCount() {
        long now = clock.getAsLong();
        int n = 0;
        for (Entry e : lastSeen.values()) {
            if (now - e.seenAtMs <= staleTimeoutMillis) n++;
        }
        return n;
    }

    private record Entry(BackendHeartbeat heartbeat, long seenAtMs) {
    }

    private final class Sub implements Subscription {
        final Consumer<BackendHeartbeat> sink;
        final AtomicBoolean closed = new AtomicBoolean(false);

        Sub(Consumer<BackendHeartbeat> sink) {
            this.sink = sink;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) subscribers.remove(this);
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }
    }
}
