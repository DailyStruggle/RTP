package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.RTP;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Loopback-only, token-gated WebSocket channel for the {@code file://} web editor (ADR-104).
 *
 * <p>Non-blocking RFC 6455 server on {@code 127.0.0.1:<ephemeral>}; no threads of its own. An async
 * {@code RTP.scheduler} timer calls {@link #poll()} every {@link #POLL_PERIOD_TICKS}, which does a
 * {@code selectNow()} and a count-bound slice of accept/read/frame/write work (F-002). Admission:
 * {@code GET /rtp-editor-ws?token=<hex>}, loopback peer, loopback {@code Host}, {@code Origin}
 * absent / {@code null} / http(s) localhost, constant-time token compare. Production sockets carry
 * the signed {@code EditorChannel} protocol and pass every text frame to the {@link Listener}
 * untouched; only a channel opened with an {@link EditorLoopbackApply} handles token-form
 * {@code apply} messages itself.
 * {@link #send} / {@link #broadcast} are thread-safe: they enqueue, the next poll flushes.
 */
public final class EditorLoopbackChannel implements AutoCloseable {

    public static final String PATH = "/rtp-editor-ws";
    public static final long POLL_PERIOD_TICKS = 2L;
    static final int MAX_CLIENTS = 4;
    /** Sockets still handshaking on top of {@link #MAX_CLIENTS}; beyond this, refused at accept. */
    static final int MAX_PENDING = 4;
    static final int MAX_HANDSHAKE_BYTES = 8 * 1024;
    static final long HANDSHAKE_TIMEOUT_MILLIS = 10_000L;
    /** Upper bound on flushing a close frame / error response before the socket is torn down. */
    static final long CLOSE_LINGER_MILLIS = 2_000L;
    static final int MAX_MESSAGE_BYTES = 256 * 1024;
    static final long MAX_OUTBOUND_BYTES = 2L * 1024 * 1024;
    static final int MAX_EVENTS_PER_POLL = 64;
    static final int MAX_ACCEPTS_PER_POLL = 8;
    static final int MAX_FRAMES_PER_POLL = 32;
    static final int READ_BYTES_PER_POLL = 512 * 1024;
    static final int WRITE_BYTES_PER_POLL = 512 * 1024;
    /** One max-size frame plus its header, with slack for a trailing control frame. */
    private static final int INBOUND_CAP = MAX_MESSAGE_BYTES + 1024;
    private static final int READ_CHUNK = 64 * 1024;
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Pattern CRLF = Pattern.compile("\r\n");
    private static final Pattern LOOPBACK_AUTHORITY =
            Pattern.compile("(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d{1,5})?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALLOWED_ORIGIN =
            Pattern.compile("https?://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d{1,5})?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile("[0-9a-fA-F]{32,128}");
    /** Repeats of these headers are rejected rather than merged (smuggling / ambiguity). */
    private static final Set<String> SINGLE_HEADERS =
            Set.of("host", "origin", "upgrade", "sec-websocket-key", "sec-websocket-version");
    private static final byte[] HEADER_END = {'\r', '\n', '\r', '\n'};
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Callbacks run on the polling thread; keep them short and non-blocking. */
    public interface Listener {
        void onMessage(Connection c, String text);

        default void onOpen(Connection c) {
        }

        default void onClose(Connection c) {
        }
    }

    enum State { HANDSHAKE, OPEN, CLOSING, CLOSED }

    /** One client socket. Inbound state is touched only under the poll lock. */
    public static final class Connection {
        private final long id;
        private final SocketChannel channel;
        private final String remote;
        private final long acceptedAt;
        private final ConcurrentLinkedQueue<ByteBuffer> out = new ConcurrentLinkedQueue<>();
        private final AtomicLong outBytes = new AtomicLong();
        private final ByteArrayOutputStream message = new ByteArrayOutputStream();
        private SelectionKey key;
        private volatile State state = State.HANDSHAKE;
        private volatile boolean overflowed;
        private byte[] in = new byte[4096];
        private int inLen;
        private boolean fragmented;
        private boolean opened;
        private long closeDeadline;

        Connection(long id, SocketChannel channel, String remote, long acceptedAt) {
            this.id = id;
            this.channel = channel;
            this.remote = remote;
            this.acceptedAt = acceptedAt;
        }

        public long id() {
            return id;
        }

        public String remoteAddress() {
            return remote;
        }

        public boolean isOpen() {
            return state == State.OPEN;
        }

        State state() {
            return state;
        }
    }

    private final byte[] token;
    private final String tokenText;
    private final Listener listener;
    private final EditorLoopbackApply apply;
    private final LongSupplier clock;
    private final Selector selector;
    private final ServerSocketChannel server;
    private final int port;
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final ReentrantLock ioLock = new ReentrantLock();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicLong ids = new AtomicLong();
    private final ByteBuffer readBuf = ByteBuffer.allocate(READ_CHUNK);
    private volatile Object taskHandle;

    EditorLoopbackChannel(String token, Listener listener, EditorLoopbackApply apply, LongSupplier clock)
            throws IOException {
        Objects.requireNonNull(token, "token");
        if (!TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("Loopback token must be 32-128 hex characters");
        }
        this.tokenText = token;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.listener = Objects.requireNonNull(listener, "listener");
        this.apply = apply;
        this.clock = Objects.requireNonNull(clock, "clock");
        Selector sel = Selector.open();
        ServerSocketChannel srv = null;
        try {
            srv = ServerSocketChannel.open();
            srv.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), MAX_CLIENTS + MAX_PENDING);
            srv.configureBlocking(false);
            srv.register(sel, SelectionKey.OP_ACCEPT);
        } catch (IOException | RuntimeException e) {
            if (srv != null) closeQuietly(srv, "server socket");
            closeQuietly(sel, "selector");
            throw e;
        }
        this.selector = sel;
        this.server = srv;
        this.port = ((InetSocketAddress) srv.getLocalAddress()).getPort();
    }

    /**
     * Opens the listener and drives it from an async {@code RTP.scheduler} timer.
     *
     * @param token 32-128 hex characters; the page connects with {@code ?token=}
     * @param listener receives every text message (Hot-Apply arrives signed, through {@code EditorChannel})
     * @throws IllegalStateException when the scheduler is not initialised (S-006)
     * @throws IOException when the loopback socket cannot be bound
     */
    public static EditorLoopbackChannel start(String token, Listener listener) throws IOException {
        if (RTP.scheduler == null) {
            throw new IllegalStateException("RTP scheduler not initialized yet (S-006 fail-closed)");
        }
        EditorLoopbackChannel ch = new EditorLoopbackChannel(token, listener, null, System::currentTimeMillis);
        try {
            ch.taskHandle = RTP.scheduler.runTaskTimerAsynchronously(ch::poll, 0L, POLL_PERIOD_TICKS);
        } catch (RuntimeException e) {
            ch.stop("scheduling failed: " + e.getMessage());
            throw e;
        }
        RTP.log(Level.INFO, "[editor] loopback channel listening on " + ch.hostLiteral() + ":" + ch.port);
        return ch;
    }

    /** Test / manual-drive entry: no timer; the caller invokes {@link #poll()}. */
    static EditorLoopbackChannel open(String token, Listener listener, EditorLoopbackApply apply) throws IOException {
        return new EditorLoopbackChannel(token, listener, apply, System::currentTimeMillis);
    }

    /** Manual-drive entry without token-form apply: no timer; the caller invokes {@link #poll()}. */
    public static EditorLoopbackChannel openUnscheduled(String token, Listener listener) throws IOException {
        return new EditorLoopbackChannel(token, listener, null, System::currentTimeMillis);
    }

    /** 128-bit hex token from {@link SecureRandom}. */
    public static String newToken() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static boolean tokenEquals(byte[] expected, String provided) {
        return provided != null && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8));
    }

    public int port() {
        return port;
    }

    /** Connect URL for the page; carries the token. */
    public String url() {
        return "ws://" + hostLiteral() + ":" + port + PATH + "?token=" + tokenText;
    }

    private String hostLiteral() {
        InetAddress a = InetAddress.getLoopbackAddress();
        return a instanceof Inet6Address ? "[" + a.getHostAddress() + "]" : a.getHostAddress();
    }

    public boolean isOpen() {
        return !stopped.get();
    }

    /** Clients past the handshake. */
    public int clientCount() {
        int n = 0;
        for (Connection c : connections) if (c.state == State.OPEN) n++;
        return n;
    }

    /**
     * Queues one text frame; any thread.
     *
     * @return {@code false} if the client is not open; on queue overflow the client is dropped next poll
     */
    public boolean send(Connection c, String text) {
        Objects.requireNonNull(text, "text");
        if (c == null || c.state != State.OPEN || stopped.get()) return false;
        return enqueue(c, frame(0x1, text.getBytes(StandardCharsets.UTF_8)));
    }

    /** Queues one text frame to every open client; any thread. Encoded once, shared read-only. */
    public void broadcast(String text) {
        Objects.requireNonNull(text, "text");
        if (stopped.get()) return;
        ByteBuffer f = frame(0x1, text.getBytes(StandardCharsets.UTF_8));
        for (Connection c : connections) {
            if (c.state == State.OPEN) enqueue(c, f.duplicate());
        }
    }

    private static boolean enqueue(Connection c, ByteBuffer buf) {
        int len = buf.remaining();
        if (c.outBytes.addAndGet(len) > MAX_OUTBOUND_BYTES) {
            c.outBytes.addAndGet(-len);
            c.overflowed = true;
            return false;
        }
        c.out.add(buf);
        return true;
    }

    /**
     * One bounded I/O slice: at most {@link #MAX_EVENTS_PER_POLL} selector events, then per client
     * at most {@link #MAX_FRAMES_PER_POLL} frames and {@link #WRITE_BYTES_PER_POLL} written.
     * Re-entrant / concurrent calls are skipped.
     */
    public void poll() {
        if (stopped.get() || !ioLock.tryLock()) return;
        try {
            if (stopped.get()) return;
            long now = clock.getAsLong();
            selector.selectNow();
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            int events = 0;
            while (it.hasNext() && events++ < MAX_EVENTS_PER_POLL) {
                SelectionKey key = it.next();
                it.remove();
                if (!key.isValid()) continue;
                if (key.isAcceptable()) {
                    acceptPending(now);
                } else if (key.isReadable() && key.attachment() instanceof Connection c) {
                    readFrom(c);
                }
            }
            for (Connection c : connections) service(c, now);
        } catch (IOException | RuntimeException e) {
            RTP.log(Level.WARNING, "[editor] loopback channel poll failed; stopping: " + e.getMessage(), e);
            stop("poll failure: " + e.getMessage());
        } finally {
            ioLock.unlock();
        }
    }

    /** Cancels the timer and closes every socket. Idempotent; any thread. */
    public void stop() {
        stop("stopped");
    }

    public void stop(String reason) {
        if (!stopped.compareAndSet(false, true)) return;
        Object handle = taskHandle;
        if (handle != null && RTP.scheduler != null) {
            try {
                RTP.scheduler.cancelTask(handle);
            } catch (RuntimeException e) {
                RTP.log(Level.WARNING, "[editor] failed to cancel loopback channel task: " + e.getMessage(), e);
            }
        }
        ioLock.lock();
        try {
            for (Connection c : connections) {
                if (c.state == State.OPEN) {
                    try {
                        // best effort: the socket is closed right after, no flush wait
                        c.channel.write(closeFrame(1001, "server stopping"));
                    } catch (IOException e) {
                        RTP.log(Level.FINE, "[editor] loopback close frame to #" + c.id + " not sent: " + e.getMessage());
                    }
                }
                closeConnection(c, reason);
            }
            closeQuietly(server, "server socket");
            closeQuietly(selector, "selector");
        } finally {
            ioLock.unlock();
        }
        RTP.log(Level.INFO, "[editor] loopback channel stopped: " + reason);
    }

    @Override
    public void close() {
        stop();
    }

    // ---- accept / read / write ----

    private void acceptPending(long now) throws IOException {
        for (int n = 0; n < MAX_ACCEPTS_PER_POLL; n++) {
            SocketChannel sc = server.accept();
            if (sc == null) return;
            SocketAddress addr = sc.getRemoteAddress();
            String remote = String.valueOf(addr);
            if (!(addr instanceof InetSocketAddress isa) || !isa.getAddress().isLoopbackAddress()) {
                RTP.log(Level.WARNING, "[editor] loopback channel refused non-loopback peer " + remote);
                closeQuietly(sc, "refused socket");
                continue;
            }
            if (connections.size() >= MAX_CLIENTS + MAX_PENDING) {
                RTP.log(Level.WARNING, "[editor] loopback channel refused " + remote + ": too many sockets");
                try {
                    sc.configureBlocking(false);
                    sc.write(httpError(503, "Service Unavailable", ""));
                } catch (IOException e) {
                    RTP.log(Level.FINE, "[editor] loopback 503 to " + remote + " not sent: " + e.getMessage());
                }
                closeQuietly(sc, "refused socket");
                continue;
            }
            try {
                sc.configureBlocking(false);
                sc.setOption(StandardSocketOptions.TCP_NODELAY, true);
                Connection c = new Connection(ids.incrementAndGet(), sc, remote, now);
                c.key = sc.register(selector, SelectionKey.OP_READ, c);
                connections.add(c);
            } catch (IOException e) {
                RTP.log(Level.WARNING, "[editor] loopback channel failed to register " + remote + ": " + e.getMessage(), e);
                closeQuietly(sc, "socket");
            }
        }
    }

    private void readFrom(Connection c) {
        if (c.state == State.CLOSED) return;
        try {
            int budget = READ_BYTES_PER_POLL;
            while (budget > 0) {
                int cap = c.state == State.HANDSHAKE ? MAX_HANDSHAKE_BYTES + 1 : INBOUND_CAP;
                if (c.inLen >= cap) return;
                readBuf.clear();
                readBuf.limit(Math.min(readBuf.capacity(), Math.min(budget, cap - c.inLen)));
                int n = c.channel.read(readBuf);
                if (n < 0) {
                    closeConnection(c, "peer closed the connection");
                    return;
                }
                if (n == 0) return;
                budget -= n;
                if (c.state == State.CLOSING) continue; // drain and discard
                ensureCapacity(c, c.inLen + n);
                System.arraycopy(readBuf.array(), 0, c.in, c.inLen, n);
                c.inLen += n;
            }
        } catch (IOException e) {
            drop(c, "read failed: " + e.getMessage());
        }
    }

    private void service(Connection c, long now) {
        if (c.state == State.CLOSED) return;
        if (c.overflowed) {
            drop(c, "outbound queue exceeded " + (MAX_OUTBOUND_BYTES >> 20) + " MiB");
            return;
        }
        try {
            if (c.state == State.HANDSHAKE) {
                if (now - c.acceptedAt > HANDSHAKE_TIMEOUT_MILLIS) {
                    reject(c, 408, "Request Timeout", "handshake timed out", "", now);
                } else {
                    tryHandshake(c, now);
                }
            }
            if (c.state == State.OPEN) processFrames(c, now);
            flush(c);
            if (c.state == State.CLOSING && (c.out.isEmpty() || now >= c.closeDeadline)) {
                closeConnection(c, "closed");
            }
        } catch (IOException e) {
            drop(c, "I/O failed: " + e.getMessage());
        }
    }

    private void flush(Connection c) throws IOException {
        int budget = WRITE_BYTES_PER_POLL;
        ByteBuffer b;
        while (budget > 0 && (b = c.out.peek()) != null) {
            int n = c.channel.write(b);
            c.outBytes.addAndGet(-n);
            budget -= n;
            if (b.hasRemaining()) return; // socket buffer full; resume next poll
            c.out.poll();
        }
    }

    // ---- handshake ----

    private void tryHandshake(Connection c, long now) {
        int end = indexOf(c.in, c.inLen, HEADER_END);
        if (end < 0 || end + HEADER_END.length > MAX_HANDSHAKE_BYTES) {
            if (end >= 0 || c.inLen > MAX_HANDSHAKE_BYTES) {
                reject(c, 431, "Request Header Fields Too Large", "handshake headers exceed 8 KiB", "", now);
            }
            return;
        }
        String head = new String(c.in, 0, end, StandardCharsets.ISO_8859_1);
        consume(c, end + HEADER_END.length);
        String[] lines = CRLF.split(head, -1);
        String[] request = lines[0].split(" ", -1);
        if (request.length != 3 || !"HTTP/1.1".equals(request[2])) {
            reject(c, 400, "Bad Request", "malformed request line", "", now);
            return;
        }
        if (!"GET".equals(request[0])) {
            reject(c, 405, "Method Not Allowed", "method " + request[0], "", now);
            return;
        }
        String target = request[1];
        int q = target.indexOf('?');
        String path = q < 0 ? target : target.substring(0, q);
        if (!PATH.equals(path)) {
            reject(c, 404, "Not Found", "path " + path, "", now);
            return;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int k = 1; k < lines.length; k++) {
            int colon = lines[k].indexOf(':');
            if (colon <= 0) {
                reject(c, 400, "Bad Request", "malformed header line", "", now);
                return;
            }
            String name = lines[k].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[k].substring(colon + 1).trim();
            String prior = headers.get(name);
            if (prior != null && SINGLE_HEADERS.contains(name)) {
                reject(c, 400, "Bad Request", "repeated header " + name, "", now);
                return;
            }
            headers.put(name, prior == null ? value : prior + ", " + value);
        }
        if (!hasToken(headers.get("upgrade"), "websocket") || !hasToken(headers.get("connection"), "upgrade")) {
            reject(c, 400, "Bad Request", "not a websocket upgrade", "", now);
            return;
        }
        if (!"13".equals(headers.get("sec-websocket-version"))) {
            reject(c, 426, "Upgrade Required", "unsupported websocket version", "Sec-WebSocket-Version: 13\r\n", now);
            return;
        }
        String key = headers.get("sec-websocket-key");
        if (!validKey(key)) {
            reject(c, 400, "Bad Request", "bad Sec-WebSocket-Key", "", now);
            return;
        }
        String host = headers.get("host");
        if (host == null || !LOOPBACK_AUTHORITY.matcher(host).matches()) {
            reject(c, 403, "Forbidden", "non-loopback Host '" + host + "'", "", now);
            return;
        }
        String origin = headers.get("origin");
        if (origin != null && !"null".equals(origin) && !ALLOWED_ORIGIN.matcher(origin).matches()) {
            reject(c, 403, "Forbidden", "disallowed Origin '" + origin + "'", "", now);
            return;
        }
        String provided = queryParam(q < 0 ? "" : target.substring(q + 1), "token");
        if (!tokenEquals(token, provided)) {
            reject(c, 401, "Unauthorized", "bad or missing token", "", now);
            return;
        }
        if (clientCount() >= MAX_CLIENTS) {
            reject(c, 503, "Service Unavailable", "client limit " + MAX_CLIENTS + " reached", "", now);
            return;
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptKey(key) + "\r\n\r\n";
        enqueue(c, ByteBuffer.wrap(response.getBytes(StandardCharsets.ISO_8859_1)));
        c.state = State.OPEN;
        c.opened = true;
        RTP.log(Level.INFO, "[editor] loopback client #" + c.id + " connected from " + c.remote);
        try {
            listener.onOpen(c);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING, "[editor] loopback listener onOpen failed: " + e.getMessage(), e);
        }
    }

    private void reject(Connection c, int code, String reason, String detail, String extraHeaders, long now) {
        Level level = code == 401 || code == 403 ? Level.WARNING : Level.INFO;
        RTP.log(level, "[editor] loopback handshake from " + c.remote + " rejected (" + code + "): " + detail);
        c.out.clear();
        c.outBytes.set(0);
        enqueue(c, httpError(code, reason, extraHeaders));
        beginClosing(c, now);
    }

    private static ByteBuffer httpError(int code, String reason, String extraHeaders) {
        String body = code + " " + reason + "\n";
        String r = "HTTP/1.1 " + code + " " + reason + "\r\nConnection: close\r\nContent-Type: text/plain\r\n"
                + extraHeaders + "Content-Length: " + body.length() + "\r\n\r\n" + body;
        return ByteBuffer.wrap(r.getBytes(StandardCharsets.ISO_8859_1));
    }

    @SuppressWarnings("java:S4790") // RFC 6455 Section 4.2.2 mandates SHA-1 for Sec-WebSocket-Accept handshake
    static String acceptKey(String key) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(sha);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private static boolean validKey(String key) {
        if (key == null) return false;
        try {
            return Base64.getDecoder().decode(key).length == 16;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean hasToken(String header, String token) {
        if (header == null) return false;
        for (String part : header.split(",")) {
            if (part.trim().equalsIgnoreCase(token)) return true;
        }
        return false;
    }

    /** Exactly one occurrence, else {@code null} (fail-closed on repeats). */
    private static String queryParam(String query, String name) {
        String found = null;
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) {
                if (found != null) return null;
                found = pair.substring(name.length() + 1);
            }
        }
        return found;
    }

    // ---- frames ----

    private void processFrames(Connection c, long now) {
        for (int frames = 0; c.state == State.OPEN && frames < MAX_FRAMES_PER_POLL; frames++) {
            if (c.inLen < 2) return;
            int b0 = c.in[0] & 0xFF;
            int b1 = c.in[1] & 0xFF;
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            if ((b0 & 0x70) != 0) {
                fail(c, 1002, "reserved bits set", now);
                return;
            }
            if ((b1 & 0x80) == 0) {
                fail(c, 1002, "client frames must be masked", now);
                return;
            }
            long len = b1 & 0x7F;
            int header = 2;
            if (len == 126) {
                if (c.inLen < 4) return;
                len = ((c.in[2] & 0xFF) << 8) | (c.in[3] & 0xFF);
                header = 4;
            } else if (len == 127) {
                if (c.inLen < 10) return;
                len = 0;
                for (int k = 2; k < 10; k++) len = (len << 8) | (c.in[k] & 0xFF);
                header = 10;
                if (len < 0) {
                    fail(c, 1002, "bad frame length", now);
                    return;
                }
            }
            if (opcode >= 0x8) {
                if (opcode > 0xA || !fin || len > 125) {
                    fail(c, 1002, "malformed control frame", now);
                    return;
                }
            } else if (opcode == 0x2) {
                fail(c, 1003, "binary frames are not supported", now);
                return;
            } else if (opcode != 0x0 && opcode != 0x1) {
                fail(c, 1002, "unknown opcode " + opcode, now);
                return;
            } else if ((opcode == 0x1) == c.fragmented) {
                fail(c, 1002, c.fragmented ? "text frame inside fragmented message" : "unexpected continuation", now);
                return;
            } else if (c.message.size() + len > MAX_MESSAGE_BYTES) {
                fail(c, 1009, "message exceeds " + (MAX_MESSAGE_BYTES >> 10) + " KiB", now);
                return;
            }
            int total = header + 4 + (int) len;
            if (c.inLen < total) {
                ensureCapacity(c, total);
                return;
            }
            byte[] payload = new byte[(int) len];
            for (int k = 0; k < payload.length; k++) {
                payload[k] = (byte) (c.in[header + 4 + k] ^ c.in[header + (k & 3)]);
            }
            consume(c, total);
            switch (opcode) {
                case 0x8 -> {
                    if (payload.length == 1) {
                        fail(c, 1002, "bad close payload", now);
                    } else {
                        int code = payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1000;
                        enqueue(c, closeFrame(code == 1005 || code == 1006 || code == 1015 ? 1000 : code, ""));
                        beginClosing(c, now);
                    }
                    return;
                }
                case 0x9 -> enqueue(c, frame(0xA, payload));
                case 0xA -> {
                    // unsolicited pong: ignore
                }
                default -> {
                    c.message.write(payload, 0, payload.length);
                    c.fragmented = !fin;
                    if (fin) {
                        byte[] bytes = c.message.toByteArray();
                        c.message.reset();
                        String text = decodeUtf8(bytes);
                        if (text == null) {
                            fail(c, 1007, "invalid UTF-8", now);
                            return;
                        }
                        deliver(c, text);
                    }
                }
            }
        }
    }

    private void deliver(Connection c, String text) {
        if (apply != null && text.contains("\"action\"")) {
            Object parsed = null;
            try {
                parsed = EditorLoopbackJson.parse(text);
            } catch (IllegalArgumentException notJson) {
                // not an apply request; falls through to the listener unchanged
                RTP.log(Level.FINE, "[editor] loopback message is not JSON: " + notJson.getMessage());
            }
            if (EditorLoopbackApply.isApply(parsed)) {
                apply.handle((Map<?, ?>) parsed).whenComplete((ack, t) -> {
                    if (ack != null) {
                        if (!send(c, ack)) {
                            RTP.log(Level.WARNING, "[editor] loopback apply_ack undeliverable to client #" + c.id
                                    + ": " + ack);
                        }
                    } else {
                        RTP.log(Level.WARNING, "[editor] loopback apply produced no ack", t);
                    }
                });
                return;
            }
        }
        try {
            listener.onMessage(c, text);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING, "[editor] loopback listener failed on client #" + c.id + ": " + e.getMessage(), e);
        }
    }

    private void fail(Connection c, int code, String reason, long now) {
        RTP.log(Level.INFO, "[editor] loopback client #" + c.id + " protocol close " + code + ": " + reason);
        enqueue(c, closeFrame(code, reason));
        beginClosing(c, now);
    }

    private static void beginClosing(Connection c, long now) {
        c.state = State.CLOSING;
        c.closeDeadline = now + CLOSE_LINGER_MILLIS;
        c.inLen = 0;
        c.message.reset();
    }

    static ByteBuffer frame(int opcode, byte[] payload) {
        int len = payload.length;
        int header = len < 126 ? 2 : len <= 0xFFFF ? 4 : 10;
        ByteBuffer b = ByteBuffer.allocate(header + len);
        b.put((byte) (0x80 | opcode));
        if (len < 126) {
            b.put((byte) len);
        } else if (len <= 0xFFFF) {
            b.put((byte) 126).putShort((short) len);
        } else {
            b.put((byte) 127).putLong(len);
        }
        return b.put(payload).flip();
    }

    /** ASCII reasons only; truncated to the 123-byte control-frame budget. */
    static ByteBuffer closeFrame(int code, String reason) {
        byte[] r = reason.getBytes(StandardCharsets.US_ASCII);
        byte[] p = new byte[2 + Math.min(r.length, 123)];
        p[0] = (byte) (code >> 8);
        p[1] = (byte) code;
        System.arraycopy(r, 0, p, 2, p.length - 2);
        return frame(0x8, p);
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            CharBuffer cb = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return cb.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    // ---- bookkeeping ----

    private void drop(Connection c, String reason) {
        RTP.log(Level.WARNING, "[editor] loopback client #" + c.id + " dropped: " + reason);
        closeConnection(c, reason);
    }

    private void closeConnection(Connection c, String reason) {
        if (c.state == State.CLOSED) return;
        c.state = State.CLOSED;
        if (c.key != null) c.key.cancel();
        closeQuietly(c.channel, "client socket");
        connections.remove(c);
        c.out.clear();
        c.outBytes.set(0);
        RTP.log(Level.FINE, "[editor] loopback client #" + c.id + " closed: " + reason);
        if (c.opened) {
            try {
                listener.onClose(c);
            } catch (RuntimeException e) {
                RTP.log(Level.WARNING, "[editor] loopback listener onClose failed: " + e.getMessage(), e);
            }
        }
    }

    private static void ensureCapacity(Connection c, int needed) {
        if (needed > c.in.length) c.in = Arrays.copyOf(c.in, Math.max(needed, Math.min(c.in.length * 2, INBOUND_CAP)));
    }

    private static void consume(Connection c, int n) {
        System.arraycopy(c.in, n, c.in, 0, c.inLen - n);
        c.inLen -= n;
    }

    private static int indexOf(byte[] hay, int len, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= len; i++) {
            for (int k = 0; k < needle.length; k++) {
                if (hay[i + k] != needle[k]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static void closeQuietly(Closeable c, String what) {
        try {
            c.close();
        } catch (IOException e) {
            RTP.log(Level.FINE, "[editor] loopback channel failed to close " + what + ": " + e.getMessage());
        }
    }
}
