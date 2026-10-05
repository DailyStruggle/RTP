package io.github.dailystruggle.rtp.common.network.pluginmessage;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;

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

/**
 * Common base for plugin-messaging based {@link NetworkTransport} implementations.
 * Handles heartbeat reception, in-memory snapshot aggregation, and non-durable tier stubs.
 *
 * <p>Inbound payloads are untrusted (a modded client can inject plugin
 * messages on a player connection). With an {@link HmacVerifier} every row
 * must be a valid signed {@link PluginMessageEnvelope}; unsigned / tampered /
 * oversized rows are dropped with a throttled WARNING (REQ-RTP-S-004).
 * {@link #lastSeen} is capped at {@link #MAX_TRACKED_SERVERS}.</p>
 */
public abstract class AbstractPluginMessageNetworkBinding implements NetworkTransport {

    /** Upper bound on distinct server ids held in {@link #lastSeen}. */
    public static final int MAX_TRACKED_SERVERS = 1024;

    protected final NetworkBridge bridge;
    protected final long staleTimeoutMillis;
    protected final LongSupplier clock;
    protected final HmacVerifier verifier;
    protected final AtomicBoolean open = new AtomicBoolean(true);

    protected final Map<String, Entry> lastSeen = new ConcurrentHashMap<>();
    protected final CopyOnWriteArrayList<Sub> subscribers = new CopyOnWriteArrayList<>();

    private final Object admitLock = new Object();
    private final ThrottledWarning rejectedWarning;
    private final ThrottledWarning capWarning;
    private final ThrottledWarning outboundWarning;

    protected AbstractPluginMessageNetworkBinding(NetworkBridge bridge, long staleTimeoutMillis, LongSupplier clock) {
        this(bridge, staleTimeoutMillis, clock, null);
    }

    protected AbstractPluginMessageNetworkBinding(NetworkBridge bridge, long staleTimeoutMillis,
                                                  LongSupplier clock, HmacVerifier verifier) {
        this.bridge = java.util.Objects.requireNonNull(bridge, "bridge");
        this.staleTimeoutMillis = staleTimeoutMillis > 0 ? staleTimeoutMillis : 1_500L;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.verifier = verifier;
        this.rejectedWarning = new ThrottledWarning(
                m -> RTP.log(Level.WARNING, m), ThrottledWarning.DEFAULT_INTERVAL_MS, clock);
        this.capWarning = new ThrottledWarning(
                m -> RTP.log(Level.WARNING, m), ThrottledWarning.DEFAULT_INTERVAL_MS, clock);
        this.outboundWarning = new ThrottledWarning(
                m -> RTP.log(Level.WARNING, m), ThrottledWarning.DEFAULT_INTERVAL_MS, clock);
        if (verifier == null) {
            RTP.log(Level.WARNING, "[RTP] " + getClass().getSimpleName()
                    + " running UNSIGNED: inbound plugin-message heartbeats are not authenticated"
                    + " and can be forged by clients. Set network.secretEnv (RTP_NET_SECRET) on"
                    + " every backend and the proxy.");
        }
        bridge.registerInbound(this::onInbound);
    }

    /** Encode {@code row} for the wire (signed when a verifier is configured); {@code null} if oversized. */
    protected byte[] encodeOutbound(BackendHeartbeat row) {
        byte[] payload = PluginMessageEnvelope.seal(row, verifier);
        if (payload == null) {
            outboundWarning.report("[RTP] outbound heartbeat for '" + row.serverId()
                    + "' exceeds " + PluginMessageEnvelope.MAX_PAYLOAD_BYTES
                    + " bytes; not sent (trim regions / metadata).");
        }
        return payload;
    }

    protected void onInbound(byte[] payload) {
        if (payload == null || payload.length == 0) return;
        PluginMessageEnvelope.Result result;
        try {
            result = PluginMessageEnvelope.open(payload, verifier);
        } catch (Throwable t) {
            result = new PluginMessageEnvelope.Result(null, PluginMessageEnvelope.Rejection.MALFORMED);
        }
        if (!result.accepted()) {
            rejectedWarning.report("[RTP] dropped inbound plugin-message heartbeat ("
                    + result.rejection() + ", " + payload.length + " bytes)"
                    + (verifier != null ? "; HMAC required" : "") + " (REQ-RTP-S-004).");
            return;
        }
        BackendHeartbeat hb = result.heartbeat();
        if (!admit(hb, clock.getAsLong())) {
            capWarning.report("[RTP] plugin-message peer table full (" + MAX_TRACKED_SERVERS
                    + " live servers); refusing new server id.");
            return;
        }
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

    @Override
    public CompletableFuture<ReservationToken> claim(String serverId, UUID playerId, Duration ttl) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                getClass().getSimpleName() + " is a non-durable tier and does not mint "
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

    /**
     * Store {@code hb}; existing ids refresh in place, new ids are admitted
     * only below {@link #MAX_TRACKED_SERVERS} after evicting stale entries.
     */
    private boolean admit(BackendHeartbeat hb, long now) {
        String id = hb.serverId();
        Entry fresh = new Entry(hb, now);
        if (lastSeen.computeIfPresent(id, (k, v) -> fresh) != null) return true;
        synchronized (admitLock) {
            if (!lastSeen.containsKey(id) && lastSeen.size() >= MAX_TRACKED_SERVERS) {
                lastSeen.entrySet().removeIf(e -> now - e.getValue().seenAtMs > staleTimeoutMillis);
                if (lastSeen.size() >= MAX_TRACKED_SERVERS) return false;
            }
            lastSeen.put(id, fresh);
            return true;
        }
    }

    /** Visible for tests: total tracked ids (live + not-yet-evicted stale). */
    public int trackedPeerCount() {
        return lastSeen.size();
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

    protected record Entry(BackendHeartbeat heartbeat, long seenAtMs) {
    }

    protected final class Sub implements Subscription {
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
