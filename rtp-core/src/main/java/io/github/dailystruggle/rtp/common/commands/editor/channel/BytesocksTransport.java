package io.github.dailystruggle.rtp.common.commands.editor.channel;

import io.github.dailystruggle.rtp.common.RTP;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hosted transport (ADR-106 §5.4): a channel on a bytesocks WebSocket relay, joined outbound.
 *
 * <p>{@link #createChannel} asks the relay for a channel ({@code GET <relay>/create}; the key comes
 * back in the {@code Location} header or as {@code {"key":..}}); {@link #start} joins
 * {@code ws(s)://<relay host>/<key>} through the JDK {@link WebSocket} of the shared
 * {@link HttpClient} - no new dependency, no own threads, no inbound listener. A lost connection is
 * retried with backoff ({@link #INITIAL_BACKOFF_MILLIS}, doubling, at most {@link #MAX_BACKOFF_MILLIS});
 * the transport expires {@code ttlMillis} after it started. Reconnects and expiry are driven by one
 * async {@code RTP.scheduler} timer calling {@link #tick()}. Every create / connect failure, loss,
 * reconnect and the expiry is logged with its reason (S-004).
 *
 * <p>bytesocks counts sent frames per IP over fixed 2-minute windows (30 by default, all channels
 * together) and closes a socket over it with 1008; a channel with no client left is deleted, so a
 * rejoin then gets HTTP 400. This side therefore declares {@link #FRAMES_PER_WINDOW} (the page keeps
 * to its own smaller share, the two often sharing one IP) and closes for good on a 400 / 404 rejoin.
 */
public final class BytesocksTransport implements ChannelTransport {

    public static final long DEFAULT_TTL_MILLIS = 30L * 60L * 1000L;
    static final long INITIAL_BACKOFF_MILLIS = 1_000L;
    static final long MAX_BACKOFF_MILLIS = 60_000L;
    static final long CONNECT_TIMEOUT_MILLIS = 10_000L;
    public static final long CREATE_TIMEOUT_MILLIS = 15_000L;
    /** Frames queued behind a slow socket before sends are refused. */
    static final int MAX_PENDING_SENDS = 256;
    /** Timer period: 20 ticks = 1 s, the backoff resolution. */
    static final long TICK_PERIOD_TICKS = 20L;
    /** Plugin share of bytesocks' 30 frames per IP per 2 minutes; the page keeps to 10. */
    public static final int FRAMES_PER_WINDOW = 18;
    /**
     * WebSocket ping control frame this often: the relay's proxy (Cloudflare) drops a socket idle for
     * about a minute, and bytesocks counts only text frames, so the keepalive costs no frame budget.
     */
    static final long PING_MILLIS = 20_000L;
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_\\-]{4,64}");
    private static final Pattern JSON_KEY = Pattern.compile("\"key\"\\s*:\\s*\"([^\"]{1,64})\"");
    private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");
    private static final Pattern QUERY_OR_FRAGMENT = Pattern.compile("[?#].*$");
    private static final Pattern NOT_FOUND_STATUS = Pattern.compile("(?s).*status code 40[04]\\b.*");
    private static final String USER_AGENT = "RTP-Plugin-Editor/1.0";

    /** Periodic async timer; production uses {@link #schedulerTimer()}. */
    public interface Timer {
        Object every(Runnable task, long periodTicks);

        void cancel(Object handle);
    }

    private final HttpClient http;
    private final String id;
    private final URI socketUri;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final Timer timer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lock = new Object();
    private volatile Listener listener;
    private volatile WebSocket socket;
    private Object timerHandle;
    private long expiresAt;
    private long nextAttemptAt;
    private long backoff = INITIAL_BACKOFF_MILLIS;
    private boolean connecting;
    private int failures;
    private CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);
    private int pendingSends;
    private long connects;
    private long lastPingAt;

    /**
     * @param relayUrl {@code http(s)://host[:port]} of the relay ({@code https} joins over {@code wss})
     * @param id       channel key from {@link #createChannel}
     */
    public BytesocksTransport(HttpClient http, String relayUrl, String id, long ttlMillis, LongSupplier clock, Timer timer) {
        this.http = Objects.requireNonNull(http, "http");
        if (id == null || !KEY.matcher(id).matches()) throw new IllegalArgumentException("bad relay channel key");
        this.id = id;
        this.socketUri = socketUri(relayUrl, id);
        this.ttlMillis = ttlMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    /** {@code https://h[:p]} to {@code wss://h[:p]/<id>}, {@code http} to {@code ws}. */
    static URI socketUri(String relayUrl, String id) {
        URI base = URI.create(TRAILING_SLASHES.matcher(Objects.requireNonNull(relayUrl, "relayUrl")).replaceAll(""));
        String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(java.util.Locale.ROOT);
        String ws = switch (scheme) {
            case "https", "wss" -> "wss";
            case "http", "ws" -> "ws";
            default -> throw new IllegalArgumentException("relay URL must be http(s): " + relayUrl);
        };
        String path = base.getRawPath() == null ? "" : base.getRawPath();
        return URI.create(ws + "://" + base.getRawAuthority() + path + "/" + id);
    }

    /**
     * Creates a relay channel. Completes with its key, or exceptionally with an {@link IOException}
     * naming the reason (the caller logs it and continues snapshot-only).
     */
    public static CompletableFuture<String> createChannel(HttpClient http, String relayUrl) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder()
                    .uri(URI.create(TRAILING_SLASHES.matcher(relayUrl).replaceAll("") + "/create"))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofMillis(CREATE_TIMEOUT_MILLIS))
                    .GET()
                    .build();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(new IOException("bad relay URL '" + relayUrl + "': " + e.getMessage(), e));
        }
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).handle((res, t) -> {
            if (t != null) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                throw new java.util.concurrent.CompletionException(new IOException("relay " + relayUrl + " unreachable: " + c, c));
            }
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new java.util.concurrent.CompletionException(new IOException("relay " + relayUrl + " refused channel create: HTTP "
                        + res.statusCode()));
            }
            String key = res.headers().firstValue("Location").map(BytesocksTransport::lastSegment).orElse(null);
            if (key == null || !KEY.matcher(key).matches()) {
                Matcher m = JSON_KEY.matcher(res.body() == null ? "" : res.body());
                key = m.find() ? m.group(1) : null;
            }
            if (key == null || !KEY.matcher(key).matches()) {
                throw new java.util.concurrent.CompletionException(new IOException("relay " + relayUrl + " returned no usable channel key"));
            }
            return key;
        });
    }

    private static String lastSegment(String location) {
        String s = TRAILING_SLASHES.matcher(QUERY_OR_FRAGMENT.matcher(location.trim()).replaceAll("")).replaceAll("");
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /** Async {@code RTP.scheduler} timer (S-005: the socket work never touches the main thread). */
    public static Timer schedulerTimer() {
        return new Timer() {
            @Override
            public Object every(Runnable task, long periodTicks) {
                if (RTP.scheduler == null) throw new IllegalStateException("RTP scheduler not initialized yet (S-006 fail-closed)");
                return RTP.scheduler.runTaskTimerAsynchronously(task, periodTicks, periodTicks);
            }

            @Override
            public void cancel(Object handle) {
                if (handle != null && RTP.scheduler != null) RTP.scheduler.cancelTask(handle);
            }
        };
    }

    /**
     * Joins the channel without blocking (S-005): completes once the first connection is up and the
     * reconnect / expiry timer runs, at most {@link #CONNECT_TIMEOUT_MILLIS} (plus slack) later.
     *
     * @return completes exceptionally with an {@link IOException} when the first join fails or times
     * out; the transport is then closed for good and the session continues snapshot-only
     */
    @Override
    public CompletableFuture<Void> start(Listener l) {
        this.listener = Objects.requireNonNull(l, "listener");
        synchronized (lock) {
            expiresAt = clock.getAsLong() + ttlMillis;
            connecting = true;
        }
        return connect()
                .orTimeout(CONNECT_TIMEOUT_MILLIS + 2_000L, TimeUnit.MILLISECONDS)
                .handle((ws, t) -> {
                    if (t != null) {
                        String c = rootMessage(t);
                        abandon("relay connect failed: " + c);
                        throw new CompletionException(new IOException("could not join relay channel "
                                + socketUri.getHost() + ": " + c, t));
                    }
                    try {
                        Object handle = timer.every(this::tick, TICK_PERIOD_TICKS);
                        synchronized (lock) {
                            timerHandle = handle;
                        }
                    } catch (RuntimeException e) {
                        close("relay timer unavailable: " + e.getMessage());
                        throw new CompletionException(new IOException("relay timer unavailable: " + e.getMessage(), e));
                    }
                    RTP.log(Level.INFO, "[editor] relay channel " + id + " joined at " + socketUri.getHost());
                    return null;
                });
    }

    private void abandon(String reason) {
        closed.set(true);
        WebSocket s = socket;
        socket = null;
        if (s != null) s.abort();
        RTP.log(Level.WARNING, "[editor] " + reason);
    }

    private CompletableFuture<WebSocket> connect() {
        CompletableFuture<WebSocket> f;
        try {
            f = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MILLIS))
                    .header("User-Agent", USER_AGENT)
                    .buildAsync(socketUri, new SocketListener());
        } catch (RuntimeException e) {
            f = CompletableFuture.failedFuture(e);
        }
        return f.whenComplete((ws, t) -> {
            boolean reconnect;
            String gone = null;
            synchronized (lock) {
                connecting = false;
                if (t != null || closed.get()) {
                    if (ws != null) ws.abort();
                    // A failed first join is reported by start(); later ones retry unless the channel is gone
                    if (t != null && !closed.get() && connects > 0) {
                        if (channelGone(t)) gone = rootMessage(t);
                        else scheduleRetry("reconnect failed: " + rootMessage(t));
                    }
                    if (gone == null) return;
                }
            }
            if (gone != null) {
                // Outside the lock: close() notifies the channel and its operator
                close("the relay no longer has this channel (" + gone + "); run /rtp editor again for a new session");
                return;
            }
            synchronized (lock) {
                socket = ws;
                lastPingAt = clock.getAsLong();
                reconnect = connects++ > 0;
                backoff = INITIAL_BACKOFF_MILLIS;
                failures = 0;
                nextAttemptAt = 0;
            }
            if (reconnect) RTP.log(Level.INFO, "[editor] relay channel " + id + " reconnected");
        });
    }

    /** Under {@link #lock}: next attempt after the current backoff, which then doubles. */
    private void scheduleRetry(String reason) {
        failures++;
        nextAttemptAt = clock.getAsLong() + backoff;
        RTP.log(Level.WARNING, "[editor] relay channel " + id + ": " + reason + "; retrying in " + (backoff / 1000L) + " s");
        backoff = Math.min(MAX_BACKOFF_MILLIS, backoff * 2);
    }

    private void lost(WebSocket from, String reason) {
        synchronized (lock) {
            if (closed.get() || socket != from) return;
            socket = null;
            scheduleRetry("connection lost (" + reason + ")");
        }
    }

    /** Timer step: expiry and due reconnects. Public for deterministic tests. */
    public void tick() {
        if (closed.get()) return;
        long now = clock.getAsLong();
        boolean expire;
        boolean attempt = false;
        synchronized (lock) {
            expire = now >= expiresAt;
            if (!expire && socket == null && !connecting && nextAttemptAt > 0 && now >= nextAttemptAt) {
                connecting = true;
                attempt = true;
            }
            if (!expire && socket != null && now - lastPingAt >= PING_MILLIS) {
                lastPingAt = now;
                WebSocket s = socket;
                // Behind queued text frames: one outstanding send at a time
                sendChain = sendChain.thenCompose(v -> s.sendPing(ByteBuffer.allocate(0))).handle((ws, t) -> {
                    if (t != null) RTP.log(Level.FINE, "[editor] relay channel " + id + ": ping failed: " + rootMessage(t));
                    return null;
                });
            }
        }
        if (expire) {
            String reason = "expired after " + (ttlMillis / 60_000L) + " minutes";
            RTP.log(Level.INFO, "[editor] relay channel " + id + " " + reason);
            Listener l = listener;
            try {
                if (l != null) l.onExpired(reason);
            } finally {
                close(reason);
            }
            return;
        }
        if (attempt) connect();
    }

    @Override
    public boolean send(String text) {
        Objects.requireNonNull(text, "text");
        synchronized (lock) {
            WebSocket s = socket;
            if (closed.get() || s == null) return false;
            if (pendingSends >= MAX_PENDING_SENDS) {
                RTP.log(Level.FINE, "[editor] relay channel " + id + ": send queue full; frame refused");
                return false;
            }
            pendingSends++;
            // WebSocket allows one outstanding send: chain them, a failure never blocks later frames
            sendChain = sendChain.thenCompose(v -> s.sendText(text, true)).handle((ws, t) -> {
                synchronized (lock) {
                    pendingSends--;
                }
                if (t != null) RTP.log(Level.FINE, "[editor] relay channel " + id + ": send failed: " + rootMessage(t));
                return null;
            });
            return true;
        }
    }

    @Override
    public String relay() {
        return socketUri.toString();
    }

    /** Not closed for good; may be between reconnects ({@link #isConnected()}). */
    @Override
    public boolean isOpen() {
        return !closed.get();
    }

    public boolean isConnected() {
        return !closed.get() && socket != null;
    }

    public String id() {
        return id;
    }

    /** Successful connections so far (first join plus reconnects). */
    public long connectCount() {
        synchronized (lock) {
            return connects;
        }
    }

    /** Consecutive failed attempts since the last successful connection. */
    public int failureCount() {
        synchronized (lock) {
            return failures;
        }
    }

    @Override
    public void close(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        WebSocket s;
        Object handle;
        CompletableFuture<Void> pending;
        synchronized (lock) {
            s = socket;
            socket = null;
            handle = timerHandle;
            timerHandle = null;
            pending = sendChain;
        }
        try {
            timer.cancel(handle);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING, "[editor] failed to cancel relay timer: " + e.getMessage(), e);
        }
        if (s != null) {
            // Queued frames (a signed bye) go out before the close frame
            pending.thenCompose(v -> s.sendClose(WebSocket.NORMAL_CLOSURE, "closed"))
                    .orTimeout(5, TimeUnit.SECONDS)
                    .whenComplete((ws, t) -> {
                        if (t != null) s.abort();
                    });
        }
        RTP.log(Level.INFO, "[editor] relay channel " + id + " closed: " + reason);
        Listener l = listener;
        if (l != null) l.onClosed(reason);
    }

    /** A rejoin refused with 400 / 404: bytesocks deleted the channel once its last client left. */
    static boolean channelGone(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof java.net.http.WebSocketHandshakeException h && h.getResponse() != null) {
                int code = h.getResponse().statusCode();
                if (code == 400 || code == 404) return true;
            }
            String m = c.getMessage();
            if (m != null && NOT_FOUND_STATUS.matcher(m).matches()) return true;
        }
        return false;
    }

    @Override
    public int framesPerWindow() {
        return FRAMES_PER_WINDOW;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
    }

    /** One per connection: reassembles fragmented text frames, bounded by the frame cap. */
    private final class SocketListener implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();
        private boolean oversized;

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (!oversized) {
                if (partial.length() + data.length() > EditorChannel.MAX_FRAME_BYTES) {
                    oversized = true;
                    partial.setLength(0);
                } else {
                    partial.append(data);
                }
            }
            if (last) {
                String text = oversized ? null : partial.toString();
                partial.setLength(0);
                if (oversized) {
                    RTP.log(Level.FINE, "[editor] relay channel " + id + ": inbound frame over the frame cap dropped");
                }
                oversized = false;
                Listener l = listener;
                if (text != null && l != null && !closed.get()) {
                    try {
                        l.onFrame(text);
                    } catch (RuntimeException e) {
                        RTP.log(Level.WARNING, "[editor] relay frame handler failed: " + e.getMessage(), e);
                    }
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            lost(ws, "closed by relay: " + statusCode + (reason == null || reason.isBlank() ? "" : " " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            lost(ws, "error: " + rootMessage(error));
        }
    }
}
