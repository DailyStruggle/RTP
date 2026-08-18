package io.github.dailystruggle.rtp.common.network.direct;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.RedeemOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import io.github.dailystruggle.rtp.proxy.common.transport.codec.BackendHeartbeatCodec;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectWire;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Backend-side {@link NetworkTransport} for the {@code proxy-direct} tier (ADR-036).
 * Opens outbound TCP connections to configured proxies during heartbeat ticks to exchange state.
 * Folia-safe RPC client without raw background threads.
 */
public final class ProxyDirectNetworkBinding implements NetworkTransport {

    private static final Logger LOG = Logger.getLogger("rtp");

    private final List<InetSocketAddress> proxies;
    private final HmacVerifier verifier;
    private final int schemaVersion;
    private final long staleTimeoutMillis;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final LongSupplier clock;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private final Map<String, Entry> lastSeen = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Sub> subscribers = new CopyOnWriteArrayList<>();

    private record Entry(BackendHeartbeat heartbeat, long seenAtMs) {
    }

    public ProxyDirectNetworkBinding(List<InetSocketAddress> proxies,
                                     HmacVerifier verifier,
                                     int schemaVersion,
                                     long staleTimeoutMillis,
                                     int connectTimeoutMs,
                                     int readTimeoutMs,
                                     LongSupplier clock) {
        this.proxies = List.copyOf(java.util.Objects.requireNonNull(proxies, "proxies"));
        if (this.proxies.isEmpty()) {
            throw new IllegalArgumentException("proxy-direct requires at least one proxy address");
        }
        this.verifier = verifier;
        this.schemaVersion = schemaVersion;
        this.staleTimeoutMillis = staleTimeoutMillis > 0 ? staleTimeoutMillis : 5000L;
        this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 1000;
        this.readTimeoutMs = readTimeoutMs > 0 ? readTimeoutMs : 2000;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    // ---- heartbeat publish + snapshot refresh ----------------------------

    @Override
    public CompletableFuture<Void> publishBackendHeartbeat(BackendHeartbeat row) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("ProxyDirectNetworkBinding is closed"));
        }
        if (row == null) return CompletableFuture.completedFuture(null);
        String payload = BackendHeartbeatCodec.encode(row);
        // Dial every configured proxy: push our row, read its merged snapshot.
        // Per-proxy failure is isolated (S-004) - the next tick retries.
        boolean anyOk = false;
        for (InetSocketAddress addr : proxies) {
            try {
                List<String> rows = exchange(addr, payload);
                anyOk = true;
                LOG.log(Level.FINE, "[RTP] proxy-direct: pushed heartbeat (server='"
                        + row.serverId() + "', regions=" + row.regions() + ") to " + addr
                        + "; received snapshot of " + rows.size() + " row(s).");
                ingest(rows);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "[RTP] proxy-direct exchange with " + addr
                        + " failed: " + t.getMessage());
            }
        }
        if (!anyOk) {
            LOG.log(Level.FINE, "[RTP] proxy-direct: no proxy reachable this tick ("
                    + proxies.size() + " configured).");
        }
        return CompletableFuture.completedFuture(null);
    }

    /** One request/response round-trip with a single proxy. */
    private List<String> exchange(InetSocketAddress addr, String payload) throws Exception {
        try (Socket socket = new Socket()) {
            // Resolve at connect time: the configured addresses are stored
            // unresolved (the proxy host may not have been resolvable at boot),
            // and Socket.connect rejects an unresolved InetSocketAddress.
            InetSocketAddress target = addr.isUnresolved()
                    ? new InetSocketAddress(addr.getHostString(), addr.getPort())
                    : addr;
            socket.connect(target, connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            ProxyDirectWire.writeOpcode(out, ProxyDirectWire.OP_HEARTBEAT);
            ProxyDirectWire.writeSignedPayload(out, payload, verifier, schemaVersion);
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            return ProxyDirectWire.readList(in, verifier);
        }
    }

    // ---- generic RPC helpers (one short-lived socket per call) ------------

    private InetSocketAddress resolve(InetSocketAddress addr) {
        return addr.isUnresolved()
                ? new InetSocketAddress(addr.getHostString(), addr.getPort())
                : addr;
    }

    /**
     * Single-request / single-response RPC. Tries each configured proxy in
     * order and returns the first non-null (HMAC-valid) response payload, or
     * {@code null} when no proxy answered. Failure-isolated per proxy (S-004).
     */
    String rpcCall(byte op, String request) {
        if (!open.get()) return null;
        for (InetSocketAddress addr : proxies) {
            try (Socket socket = new Socket()) {
                socket.connect(resolve(addr), connectTimeoutMs);
                socket.setSoTimeout(readTimeoutMs);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                ProxyDirectWire.writeOpcode(out, op);
                ProxyDirectWire.writeSignedPayload(out, request, verifier, schemaVersion);
                out.flush();
                DataInputStream in = new DataInputStream(socket.getInputStream());
                String resp = ProxyDirectWire.readSignedPayload(in, verifier);
                if (resp != null) return resp;
            } catch (Throwable t) {
                LOG.log(Level.FINE, "[RTP] proxy-direct rpc op=" + op + " to " + addr
                        + " failed: " + t.getMessage());
            }
        }
        return null;
    }

    /**
     * List-request / single-response RPC (e.g. flushPending). Sent to the first
     * reachable proxy only - a batch must be enqueued on exactly one proxy, not
     * fanned out (which would double-dispatch). Returns the response payload or
     * {@code null}.
     */
    String rpcCallListReq(byte op, List<String> requests) {
        if (!open.get()) return null;
        for (InetSocketAddress addr : proxies) {
            try (Socket socket = new Socket()) {
                socket.connect(resolve(addr), connectTimeoutMs);
                socket.setSoTimeout(readTimeoutMs);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                ProxyDirectWire.writeOpcode(out, op);
                ProxyDirectWire.writeList(out, requests, verifier, schemaVersion);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                String resp = ProxyDirectWire.readSignedPayload(in, verifier);
                if (resp != null) return resp;
            } catch (Throwable t) {
                LOG.log(Level.FINE, "[RTP] proxy-direct rpc(list) op=" + op + " to " + addr
                        + " failed: " + t.getMessage());
            }
        }
        return null;
    }

    /**
     * List-request / list-response RPC (e.g. pollStatus). First reachable proxy
     * wins. Returns an empty list when no proxy answered.
     */
    List<String> rpcCallListBoth(byte op, List<String> requests) {
        if (!open.get()) return List.of();
        for (InetSocketAddress addr : proxies) {
            try (Socket socket = new Socket()) {
                socket.connect(resolve(addr), connectTimeoutMs);
                socket.setSoTimeout(readTimeoutMs);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                ProxyDirectWire.writeOpcode(out, op);
                ProxyDirectWire.writeList(out, requests, verifier, schemaVersion);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                return ProxyDirectWire.readList(in, verifier);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "[RTP] proxy-direct rpc(list2) op=" + op + " to " + addr
                        + " failed: " + t.getMessage());
            }
        }
        return List.of();
    }

    /**
     * Single-request / list-response RPC (e.g. listActiveForServer). First
     * reachable proxy wins. Returns an empty list when no proxy answered.
     */
    List<String> rpcCallListResp(byte op, String request) {
        if (!open.get()) return List.of();
        for (InetSocketAddress addr : proxies) {
            try (Socket socket = new Socket()) {
                socket.connect(resolve(addr), connectTimeoutMs);
                socket.setSoTimeout(readTimeoutMs);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                ProxyDirectWire.writeOpcode(out, op);
                ProxyDirectWire.writeSignedPayload(out, request, verifier, schemaVersion);
                out.flush();
                DataInputStream in = new DataInputStream(socket.getInputStream());
                return ProxyDirectWire.readList(in, verifier);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "[RTP] proxy-direct rpc(resp-list) op=" + op + " to " + addr
                        + " failed: " + t.getMessage());
            }
        }
        return List.of();
    }


    /**
     * Regions a peer actually advertised, for logging and change detection.
     * Prefers the typed {@code regions()} Set when present, else the legacy
     * {@code regionsAvailable()} list - matching what {@code PeerRegionRegistry}
     * surfaces for tab-completion. The {@code BackendHeartbeatCodec} now carries
     * both, so {@code regions()} survives a wire round-trip; the list remains a
     * fallback for older/leaner producers that only populate {@code regionsAvailable()}.
     */
    private static Collection<String> advertisedRegions(BackendHeartbeat hb) {
        if (hb == null) return List.of();
        if (hb.regions() != null && !hb.regions().isEmpty()) return hb.regions();
        return hb.regionsAvailable() == null ? List.of() : hb.regionsAvailable();
    }

    /** Merge fetched snapshot rows into the local view and fan out to subscribers. */
    private void ingest(List<String> rows) {
        long now = clock.getAsLong();
        for (String encoded : rows) {
            BackendHeartbeat hb;
            try {
                hb = BackendHeartbeatCodec.decode(encoded);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "[RTP] proxy-direct dropping malformed row: " + t.getMessage());
                continue;
            }
            if (hb == null || hb.serverId() == null || hb.serverId().isEmpty()) continue;
            Entry prev = lastSeen.put(hb.serverId(), new Entry(hb, now));
            // Log at FINE only when the network view actually changes (a new
            // peer appears or its region list differs) so the interaction is
            // visible during testing without per-tick spam. Report the regions
            // actually carried on the wire, preferring the typed regions() Set
            // (richer peers) and falling back to the regionsAvailable() list -
            // matching PeerRegionRegistry.peerEntries().
            Collection<String> advertised = advertisedRegions(hb);
            boolean changed = prev == null
                    || !java.util.Objects.equals(advertisedRegions(prev.heartbeat()), advertised);
            if (changed) {
                LOG.log(Level.FINE, "[RTP] proxy-direct: snapshot updated peer '"
                        + hb.serverId() + "' regions=" + advertised
                        + (prev == null ? " (new peer)." : " (changed)."));
            } else {
                LOG.log(Level.FINE, "[RTP] proxy-direct: snapshot row for '"
                        + hb.serverId() + "' unchanged.");
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
    }

    @Override
    public CompletableFuture<NetworkSnapshot> readSnapshot() {
        long now = clock.getAsLong();
        Map<String, BackendHeartbeat> live = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : lastSeen.entrySet()) {
            Entry entry = e.getValue();
            if (now - entry.seenAtMs() <= staleTimeoutMillis) {
                live.put(e.getKey(), entry.heartbeat());
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

    // ---- reservations: RPC to the proxy store (claim stays proxy-local) ---

    @Override
    public CompletableFuture<ReservationToken> claim(String serverId, UUID playerId, Duration ttl) {
        // A backend never claims: the proxy dispatcher allocates the
        // reservation against its own store. Calling claim from a backend is a
        // wiring error, so fail loudly rather than silently no-op.
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "ProxyDirectNetworkBinding is a backend-side RPC client; claim() runs on the "
                        + "proxy. Use the proxy's transport to claim."));
    }

    @Override
    public CompletableFuture<Optional<ReservationToken>> findReservation(UUID playerId) {
        if (playerId == null) return CompletableFuture.completedFuture(Optional.empty());
        return CompletableFuture.supplyAsync(() -> {
            String resp = rpcCall(ProxyDirectWire.OP_FIND_RESERVATION, playerId.toString());
            ReservationToken token = ProxyDirectWire.decodeToken(resp);
            return Optional.ofNullable(token);
        });
    }

    @Override
    public CompletableFuture<RedeemOutcome> redeem(String tokenId, UUID playerId, String expectedServerId) {
        if (tokenId == null || playerId == null || expectedServerId == null) {
            return CompletableFuture.completedFuture(RedeemOutcome.NOT_FOUND);
        }
        String request = tokenId + ProxyDirectWire.FS + playerId + ProxyDirectWire.FS + expectedServerId;
        return CompletableFuture.supplyAsync(() -> {
            String resp = rpcCall(ProxyDirectWire.OP_REDEEM, request);
            if (resp == null || resp.isEmpty()) return RedeemOutcome.NOT_FOUND;
            try {
                return RedeemOutcome.valueOf(resp);
            } catch (IllegalArgumentException ex) {
                return RedeemOutcome.NOT_FOUND;
            }
        });
    }

    @Override
    public CompletableFuture<List<ReservationToken>> listActiveForServer(String serverId) {
        if (serverId == null || serverId.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> {
            List<String> rows = rpcCallListResp(ProxyDirectWire.OP_LIST_ACTIVE, serverId);
            List<ReservationToken> tokens = new ArrayList<>(rows.size());
            for (String row : rows) {
                ReservationToken t = ProxyDirectWire.decodeToken(row);
                if (t != null) tokens.add(t);
            }
            return tokens;
        });
    }

    @Override
    public CompletableFuture<Void> release(String tokenId, ReleaseReason reason) {
        // Release is a proxy-local concern (the reaper / dispatcher own it
        // against the proxy store); a backend RPC client has nothing to do.
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
            if (now - e.seenAtMs() <= staleTimeoutMillis) n++;
        }
        return n;
    }

    /**
     * Parse {@code host:port} strings (default port {@code defaultPort} when
     * omitted) into socket addresses. Malformed entries are skipped with a
     * WARNING. Returns an empty list when nothing parses.
     */
    public static List<InetSocketAddress> parseProxies(List<String> raw, int defaultPort) {
        List<InetSocketAddress> out = new ArrayList<>();
        if (raw == null) return out;
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            String entry = s.trim();
            String host;
            int port = defaultPort;
            int colon = entry.lastIndexOf(':');
            if (colon > 0 && colon < entry.length() - 1 && entry.indexOf(':') == colon) {
                host = entry.substring(0, colon);
                try {
                    port = Integer.parseInt(entry.substring(colon + 1).trim());
                } catch (NumberFormatException nfe) {
                    LOG.log(Level.WARNING, "[RTP] proxy-direct: bad port in '" + entry
                            + "', using default " + defaultPort + ".");
                    port = defaultPort;
                }
            } else {
                host = entry;
            }
            try {
                out.add(InetSocketAddress.createUnresolved(host, port));
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "[RTP] proxy-direct: skipping malformed proxy address '"
                        + entry + "' (" + ex.getMessage() + ").");
            }
        }
        return out;
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
