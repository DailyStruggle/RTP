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
 * Tier-1 (DB-free) {@link NetworkTransport} that uses the proxy companion as
 * the availability store (PROPOSAL-proxy-as-availability-store). It is the
 * peer of {@link PluginMessageNetworkBinding}, and shares its snapshot / TTL /
 * subscriber machinery, but differs in <em>where availability lives</em>:
 *
 * <ul>
 *   <li><strong>Publish:</strong> {@link #publishBackendHeartbeat(BackendHeartbeat)}
 *       pushes the row to the proxy companion's cache
 *       ({@link NetworkBridge#pushHeartbeatToProxy(byte[])}) rather than
 *       gossiping it to peer backends. It also asks the companion to send back
 *       the current cached snapshot ({@link NetworkBridge#requestSnapshot()}),
 *       so a lobby's view refreshes on the same cadence with no second
 *       scheduler.</li>
 *   <li><strong>Inbound:</strong> the companion replays each cached backend
 *       heartbeat through the same inbound sink the gossip tier uses, so
 *       {@link #readSnapshot()} returns every entry seen within
 *       {@code staleTimeoutMillis}.</li>
 * </ul>
 *
 * <p>Because the proxy holds the cache independently of player presence, a
 * lobby gets real per-region availability even when the contributing backend
 * currently has no players online (within the stale window) - the convergence
 * the pure backend-to-backend gossip tier could not achieve.</p>
 *
 * <p><strong>Non-durable reservation tier.</strong> Identical posture to
 * {@link PluginMessageNetworkBinding}: {@link #claim} never mints a token,
 * {@link #release}/{@link #publishProxyHeartbeat} are no-ops, and the inherited
 * reservation-listing defaults return empties. Durable atomic claims require
 * the Pro SQL/Redis tiers.</p>
 */
public final class ProxyCacheNetworkBinding implements NetworkTransport {

    private static final Logger LOG = Logger.getLogger(ProxyCacheNetworkBinding.class.getName());

    /** Default age beyond which a cached peer heartbeat is dropped from the snapshot. */
    public static final long DEFAULT_STALE_TIMEOUT_MILLIS = 5_000L;

    private final NetworkBridge bridge;
    private final long staleTimeoutMillis;
    private final LongSupplier clock;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private final Map<String, Entry> lastSeen = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Sub> subscribers = new CopyOnWriteArrayList<>();

    public ProxyCacheNetworkBinding(NetworkBridge bridge) {
        this(bridge, DEFAULT_STALE_TIMEOUT_MILLIS, System::currentTimeMillis);
    }

    public ProxyCacheNetworkBinding(NetworkBridge bridge, long staleTimeoutMillis, LongSupplier clock) {
        this.bridge = java.util.Objects.requireNonNull(bridge, "bridge");
        this.staleTimeoutMillis = staleTimeoutMillis > 0 ? staleTimeoutMillis : DEFAULT_STALE_TIMEOUT_MILLIS;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        bridge.registerInbound(this::onInbound);
    }

    // ---- heartbeat publish + snapshot refresh ----------------------------

    @Override
    public CompletableFuture<Void> publishBackendHeartbeat(BackendHeartbeat row) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("ProxyCacheNetworkBinding is closed"));
        }
        try {
            byte[] payload = BackendHeartbeatCodec.encode(row).getBytes(StandardCharsets.UTF_8);
            bridge.pushHeartbeatToProxy(payload);
        } catch (Throwable t) {
            // S-004: a push failure (no carrier player, channel hiccup) is
            // logged, not swallowed silently and not propagated - the next
            // heartbeat tick retries.
            LOG.log(Level.FINE,
                    "[RTP] proxy-cache heartbeat push skipped: " + t.getMessage());
        }
        try {
            // Refresh this server's view of the network from the companion on
            // the same cadence (the publisher pumps publishBackendHeartbeat).
            bridge.requestSnapshot();
        } catch (Throwable t) {
            LOG.log(Level.FINE,
                    "[RTP] proxy-cache snapshot request skipped: " + t.getMessage());
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
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "ProxyCacheNetworkBinding is a non-durable tier and does not mint "
                        + "reservation tokens; use the SQL/Redis tier for durable claims."));
    }

    @Override
    public CompletableFuture<Void> release(String tokenId, ReleaseReason reason) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> publishProxyHeartbeat(ProxyHeartbeat row) {
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
