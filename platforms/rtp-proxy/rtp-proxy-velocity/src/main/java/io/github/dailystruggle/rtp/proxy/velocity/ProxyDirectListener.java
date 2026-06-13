package io.github.dailystruggle.rtp.proxy.velocity;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.RedeemOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectWire;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Proxy-side TCP listener for the {@code proxy-direct} transport tier
 * (rtp-proxy-ADR-017, refined by PROPOSAL-proxy-direct-as-remote-store).
 *
 * <p>This is the RPC <em>server</em> half of "{@code proxy-direct} = a simple
 * data-management transport like {@code redis}": backends open their OWN
 * outbound connections here (not a player's Minecraft session) and invoke
 * {@code NetworkTransport} / {@code NetworkRequestQueue} methods against the
 * proxy's own in-memory store. Each connection is one RPC keyed by the leading
 * {@link ProxyDirectWire} opcode. The proxy's existing dispatcher, trigger
 * source, reaper, and pre-connect redeem then operate on a store that backends
 * actually populate.</p>
 *
 * <p>Raw {@link ServerSocket} + thread pool are used deliberately: this is a
 * proxy JVM, which has no {@code RTP.scheduler} and is an explicit carve-out
 * from the backend "no raw threads" rule (transport bindings own their own
 * executors on the proxy by design - see {@code .junie/AGENTS.md} "Scheduler
 * Usage"). Each RPC handler may block on the transport/queue future on its own
 * worker thread.</p>
 *
 * <p>A frame that fails HMAC verification is dropped with a WARNING; the handler
 * still returns a benign empty response so the backend's read does not hang
 * (S-004: never silently swallow, never abort the whole listener).</p>
 */
public final class ProxyDirectListener {

    /** Bounded wait for a transport/queue future inside an RPC handler. */
    private static final long RPC_AWAIT_MS = 2_000L;

    private final String bindHost;
    private final int port;
    private final HmacVerifier verifier;
    private final int schemaVersion;
    private final Consumer<String> onPush;
    private final Supplier<List<String>> snapshotRows;
    private final NetworkTransport transport;
    private final NetworkRequestQueue requestQueue;
    private final Logger logger;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile ExecutorService workers;

    /**
     * Discovery-only ctor (heartbeat/snapshot only; no reservation or queue
     * RPC). Retained for tests and deployments that only want region-name
     * convergence. Every non-heartbeat opcode answers an empty/benign result.
     */
    public ProxyDirectListener(String bindHost, int port,
                               HmacVerifier verifier, int schemaVersion,
                               Consumer<String> onPush,
                               Supplier<List<String>> snapshotRows,
                               Logger logger) {
        this(bindHost, port, verifier, schemaVersion, onPush, snapshotRows,
                null, null, logger);
    }

    /**
     * Full ctor: heartbeat/snapshot plus RPC dispatch against the proxy's
     * {@code transport} and {@code requestQueue}. Either may be {@code null}
     * (the corresponding RPCs degrade to a benign empty result).
     */
    public ProxyDirectListener(String bindHost, int port,
                               HmacVerifier verifier, int schemaVersion,
                               Consumer<String> onPush,
                               Supplier<List<String>> snapshotRows,
                               NetworkTransport transport,
                               NetworkRequestQueue requestQueue,
                               Logger logger) {
        this.bindHost = (bindHost == null || bindHost.isBlank()) ? "0.0.0.0" : bindHost.trim();
        this.port = port;
        this.verifier = verifier;
        this.schemaVersion = schemaVersion;
        this.onPush = Objects.requireNonNull(onPush, "onPush");
        this.snapshotRows = Objects.requireNonNull(snapshotRows, "snapshotRows");
        this.transport = transport;
        this.requestQueue = requestQueue;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Bind the socket and start accepting. Throws on bind failure (caller logs + degrades). */
    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) return;
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(bindHost, port));
        this.serverSocket = ss;
        this.workers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "rtp-proxy-direct-worker");
            t.setDaemon(true);
            return t;
        });
        Thread accept = new Thread(this::acceptLoop, "rtp-proxy-direct-accept");
        accept.setDaemon(true);
        this.acceptThread = accept;
        accept.start();
        logger.info("RTP proxy-direct listener bound on {}:{} ({}).",
                bindHost, port, verifier != null ? "HMAC-signed" : "unsigned");
    }

    private void acceptLoop() {
        ServerSocket ss = this.serverSocket;
        while (running.get() && ss != null && !ss.isClosed()) {
            final Socket socket;
            try {
                socket = ss.accept();
            } catch (Throwable t) {
                if (running.get()) {
                    logger.warn("RTP proxy-direct accept failed: {}", t.getMessage());
                }
                continue;
            }
            ExecutorService pool = this.workers;
            if (pool != null) {
                pool.submit(() -> handle(socket));
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(3000);
            java.net.SocketAddress remote = s.getRemoteSocketAddress();
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            byte opcode = ProxyDirectWire.readOpcode(in);
            switch (opcode) {
                case ProxyDirectWire.OP_FLUSH_PENDING:
                    handleFlushPending(in, out, remote);
                    return;
                case ProxyDirectWire.OP_POLL_STATUS:
                    handlePollStatus(in, out, remote);
                    return;
                case ProxyDirectWire.OP_CANCEL:
                    handleCancel(in, out, remote);
                    return;
                case ProxyDirectWire.OP_FIND_RESERVATION:
                    handleFindReservation(in, out, remote);
                    return;
                case ProxyDirectWire.OP_REDEEM:
                    handleRedeem(in, out, remote);
                    return;
                case ProxyDirectWire.OP_LIST_ACTIVE:
                    handleListActive(in, out, remote);
                    return;
                case ProxyDirectWire.OP_HEARTBEAT:
                default:
                    handleHeartbeat(in, out, remote);
            }
        } catch (Throwable t) {
            logger.debug("RTP proxy-direct connection handling failed: {}", t.getMessage());
        }
    }

    /** OP_HEARTBEAT: ingest a heartbeat (region discovery) and reply with the merged snapshot. */
    private void handleHeartbeat(DataInputStream in, DataOutputStream out,
                                 java.net.SocketAddress remote) throws Exception {
        String payload = ProxyDirectWire.readSignedPayload(in, verifier);
        if (payload == null) {
            logger.warn("RTP proxy-direct: rejecting HMAC-invalid heartbeat from {}.", remote);
        } else {
            try {
                onPush.accept(payload);
                logger.info("RTP proxy-direct: received backend heartbeat from {} ({}).",
                        remote, summarisePayload(payload));
                // Also feed the proxy store so reservations/snapshot RPCs see it.
                if (transport != null) {
                    var hb = io.github.dailystruggle.rtp.proxy.common.transport.codec
                            .BackendHeartbeatCodec.decode(payload);
                    if (hb != null) transport.publishBackendHeartbeat(hb);
                }
            } catch (RuntimeException ex) {
                logger.warn("RTP proxy-direct: heartbeat ingest failed: {}", ex.getMessage());
            }
        }
        List<String> rows = snapshotRows.get();
        ProxyDirectWire.writeList(out, rows, verifier, schemaVersion);
        logger.info("RTP proxy-direct: replied snapshot of {} server row(s) to {}.",
                rows.size(), remote);
    }

    /** OP_FLUSH_PENDING: enqueue a batch of enrolments into the proxy's queue. */
    private void handleFlushPending(DataInputStream in, DataOutputStream out,
                                    java.net.SocketAddress remote) throws Exception {
        List<String> rows = ProxyDirectWire.readList(in, verifier);
        NetworkRequestQueue.EnrolOutcome outcome = NetworkRequestQueue.EnrolOutcome.REJECTED;
        if (requestQueue == null) {
            logger.warn("RTP proxy-direct: flushPending from {} but no request queue on proxy.", remote);
        } else {
            List<NetworkRequestQueue.EnrolmentEnvelope> envelopes = new ArrayList<>(rows.size());
            for (String row : rows) {
                NetworkRequestQueue.EnrolmentEnvelope e = ProxyDirectWire.decodeEnvelope(row);
                if (e != null) envelopes.add(e);
            }
            try {
                outcome = requestQueue.flushPending(envelopes).get(RPC_AWAIT_MS, TimeUnit.MILLISECONDS);
                logger.info("RTP proxy-direct: flushPending from {} enqueued {} request(s) -> {}.",
                        remote, envelopes.size(), outcome);
            } catch (Throwable t) {
                logger.warn("RTP proxy-direct: flushPending failed: {}", t.getMessage());
            }
        }
        ProxyDirectWire.writeSignedPayload(out, outcome.name(), verifier, schemaVersion);
        out.flush();
    }

    /** OP_POLL_STATUS: return per-player queue status rows. */
    private void handlePollStatus(DataInputStream in, DataOutputStream out,
                                  java.net.SocketAddress remote) throws Exception {
        List<String> idStrings = ProxyDirectWire.readList(in, verifier);
        List<String> reply = new ArrayList<>();
        if (requestQueue != null && !idStrings.isEmpty()) {
            List<UUID> ids = new ArrayList<>(idStrings.size());
            for (String s : idStrings) {
                try { ids.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
            }
            try {
                List<NetworkRequestQueue.QueueStatus> rows =
                        requestQueue.pollStatus(ids).get(RPC_AWAIT_MS, TimeUnit.MILLISECONDS);
                for (NetworkRequestQueue.QueueStatus s : rows) {
                    reply.add(ProxyDirectWire.encodeStatus(s));
                }
            } catch (Throwable t) {
                logger.warn("RTP proxy-direct: pollStatus failed: {}", t.getMessage());
            }
        }
        ProxyDirectWire.writeList(out, reply, verifier, schemaVersion);
    }

    /** OP_CANCEL: cancel a player's pending entry. */
    private void handleCancel(DataInputStream in, DataOutputStream out,
                              java.net.SocketAddress remote) throws Exception {
        String payload = ProxyDirectWire.readSignedPayload(in, verifier);
        if (payload != null && requestQueue != null) {
            String[] f = payload.split(String.valueOf(ProxyDirectWire.FS), -1);
            try {
                UUID id = UUID.fromString(f[0]);
                NetworkRequestQueue.CancelReason reason = NetworkRequestQueue.CancelReason.EXPLICIT_REQUEST;
                if (f.length > 1 && !f[1].isEmpty()) {
                    try { reason = NetworkRequestQueue.CancelReason.valueOf(f[1]); }
                    catch (IllegalArgumentException ignored) { }
                }
                requestQueue.cancel(id, reason).get(RPC_AWAIT_MS, TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                logger.warn("RTP proxy-direct: cancel failed: {}", t.getMessage());
            }
        }
        ProxyDirectWire.writeSignedPayload(out, "OK", verifier, schemaVersion);
        out.flush();
    }

    /** OP_FIND_RESERVATION: return the active reservation token for a player (or empty). */
    private void handleFindReservation(DataInputStream in, DataOutputStream out,
                                       java.net.SocketAddress remote) throws Exception {
        String payload = ProxyDirectWire.readSignedPayload(in, verifier);
        String reply = "";
        if (payload != null && transport != null) {
            try {
                UUID id = UUID.fromString(payload.trim());
                Optional<ReservationToken> token =
                        transport.findReservation(id).get(RPC_AWAIT_MS, TimeUnit.MILLISECONDS);
                if (token != null && token.isPresent()) {
                    reply = ProxyDirectWire.encodeToken(token.get());
                }
            } catch (Throwable t) {
                logger.warn("RTP proxy-direct: findReservation failed: {}", t.getMessage());
            }
        }
        ProxyDirectWire.writeSignedPayload(out, reply, verifier, schemaVersion);
        out.flush();
    }

    /** OP_REDEEM: transition a CLAIMED reservation to CONSUMED on arrival. */
    private void handleRedeem(DataInputStream in, DataOutputStream out,
                              java.net.SocketAddress remote) throws Exception {
        String payload = ProxyDirectWire.readSignedPayload(in, verifier);
        RedeemOutcome outcome = RedeemOutcome.NOT_FOUND;
        if (payload != null && transport != null) {
            String[] f = payload.split(String.valueOf(ProxyDirectWire.FS), -1);
            if (f.length >= 3) {
                try {
                    UUID id = UUID.fromString(f[1]);
                    outcome = transport.redeem(f[0], id, f[2]).get(RPC_AWAIT_MS, TimeUnit.MILLISECONDS);
                    logger.info("RTP proxy-direct: redeem token={} player={} server={} -> {}.",
                            f[0], f[1], f[2], outcome);
                } catch (Throwable t) {
                    logger.warn("RTP proxy-direct: redeem failed: {}", t.getMessage());
                }
            }
        }
        ProxyDirectWire.writeSignedPayload(out, outcome.name(), verifier, schemaVersion);
        out.flush();
    }

    /** OP_LIST_ACTIVE: list active reservation tokens for a backend (boot reconcile). */
    private void handleListActive(DataInputStream in, DataOutputStream out,
                                  java.net.SocketAddress remote) throws Exception {
        String serverId = ProxyDirectWire.readSignedPayload(in, verifier);
        List<String> reply = new ArrayList<>();
        if (serverId != null && !serverId.isEmpty() && transport != null) {
            try {
                List<ReservationToken> tokens =
                        transport.listActiveForServer(serverId).get(RPC_AWAIT_MS, TimeUnit.MILLISECONDS);
                for (ReservationToken t : tokens) {
                    reply.add(ProxyDirectWire.encodeToken(t));
                }
            } catch (Throwable t) {
                logger.warn("RTP proxy-direct: listActiveForServer failed: {}", t.getMessage());
            }
        }
        ProxyDirectWire.writeList(out, reply, verifier, schemaVersion);
    }

    /**
     * Best-effort one-line summary of a pushed heartbeat payload for the
     * interaction log: the codec form is a delimited string beginning with the
     * server id, so we surface its leading token without taking a hard
     * dependency on the codec's full grammar here.
     */
    private static String summarisePayload(String payload) {
        if (payload == null || payload.isBlank()) return "empty payload";
        String firstLine = payload.split("\\R", 2)[0];
        if (firstLine.length() > 200) firstLine = firstLine.substring(0, 200) + "...";
        return "payload=" + firstLine;
    }

    /** Stop accepting, close the socket, and shut the worker pool down. Idempotent. */
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        ServerSocket ss = this.serverSocket;
        this.serverSocket = null;
        if (ss != null) {
            try { ss.close(); } catch (Throwable ignored) { }
        }
        Thread at = this.acceptThread;
        if (at != null) at.interrupt();
        ExecutorService pool = this.workers;
        this.workers = null;
        if (pool != null) pool.shutdownNow();
    }

    /** Visible for tests: the actual bound port (useful when {@code port==0}). */
    public int boundPort() {
        ServerSocket ss = this.serverSocket;
        return ss != null ? ss.getLocalPort() : port;
    }
}
