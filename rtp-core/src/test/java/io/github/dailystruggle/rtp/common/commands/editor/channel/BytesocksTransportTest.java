package io.github.dailystruggle.rtp.common.commands.editor.channel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BytesocksTransport} against a local stub relay (ADR-106 §5.4 / §5.6, S-004): the bytesocks
 * {@code GET /create} contract, a failed create, frame order both ways, reconnect with backoff after
 * the relay drops the socket, the session expiry, and a failed first join. Time is a fake clock and
 * the timer is driven by hand: no test waits for a real backoff or the 30-minute lifetime.
 */
@DisplayName("REQ-RTP-S-004 ADR-106 hosted relay transport against a stub bytesocks relay")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class BytesocksTransportTest {

    private static final String KEY = "abcd1234";

    private StubRelay relay;
    private HttpClient http;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final ManualTimer timer = new ManualTimer();

    @BeforeEach
    void setUp() throws IOException {
        relay = new StubRelay();
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void tearDown() {
        relay.close();
    }

    @Test
    @DisplayName("create: GET <relay>/create, channel key from the Location header")
    void createUsesGetAndLocation() throws Exception {
        String key = BytesocksTransport.createChannel(http, relay.url() + "/").get(10, TimeUnit.SECONDS);
        assertEquals(KEY, key);
        assertEquals(List.of("GET /create"), relay.httpRequests);
    }

    @Test
    @DisplayName("create failure: refused create completes exceptionally with a named IOException")
    void createFailureIsReported() {
        relay.failCreate = true;
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> BytesocksTransport.createChannel(http, relay.url()).get(10, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, e.getCause());
        assertTrue(e.getCause().getMessage().contains("HTTP 500"), e.getCause().getMessage());
    }

    @Test
    @DisplayName("connect: frames reach the relay in send order, relay frames reach the listener")
    void connectAndOrder() throws Exception {
        Recorder rec = new Recorder();
        BytesocksTransport t = transport();
        t.start(rec).get(10, TimeUnit.SECONDS);
        assertEquals("ws://127.0.0.1:" + relay.port() + "/" + KEY, t.relay());
        assertTrue(t.isConnected());
        assertEquals(1, timer.tasks.size(), "one async timer drives reconnects and expiry");
        for (int i = 0; i < 20; i++) assertTrue(t.send("frame-" + i));
        await(() -> relay.received.size() >= 20);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 20; i++) expected.add("frame-" + i);
        assertEquals(expected, relay.received);
        relay.push("from-page");
        await(() -> rec.frames.contains("from-page"));
        t.close("test done");
        assertFalse(t.isOpen());
        assertEquals(List.of("test done"), rec.closed);
        assertTrue(timer.cancelled.contains(timer.tasks.get(0)), "timer cancelled on close");
    }

    @Test
    @DisplayName("relay drop: logged loss, reconnect after 1 s backoff, sends resume")
    void reconnectsWithBackoff() throws Exception {
        Recorder rec = new Recorder();
        BytesocksTransport t = transport();
        t.start(rec).get(10, TimeUnit.SECONDS);
        relay.dropAll();
        await(() -> !t.isConnected());
        assertTrue(t.isOpen(), "a lost connection is not the end of the session");
        assertEquals(1, t.failureCount());
        assertFalse(t.send("while-down"), "nothing is queued while disconnected");

        now.addAndGet(500);
        timer.fire();
        assertEquals(1, t.connectCount(), "no attempt before the backoff elapsed");
        now.addAndGet(500);
        timer.fire();
        await(() -> t.connectCount() == 2 && t.isConnected());
        assertEquals(0, t.failureCount());
        assertTrue(t.send("after-reconnect"));
        await(() -> relay.received.contains("after-reconnect"));
        assertTrue(rec.closed.isEmpty());
        t.close("done");
    }

    @Test
    @DisplayName("expiry: 30 minutes after start the owner is told, then the transport closes")
    void expires() throws Exception {
        Recorder rec = new Recorder();
        BytesocksTransport t = transport();
        t.start(rec).get(10, TimeUnit.SECONDS);
        now.addAndGet(BytesocksTransport.DEFAULT_TTL_MILLIS - 1);
        timer.fire();
        assertTrue(t.isOpen());
        now.addAndGet(1);
        timer.fire();
        assertFalse(t.isOpen());
        assertEquals(1, rec.expired.size());
        assertTrue(rec.expired.get(0).startsWith("expired"), rec.expired.get(0));
        assertEquals(1, rec.closed.size());
        assertFalse(t.send("late"));
    }

    @Test
    @DisplayName("first join failure: start completes exceptionally without blocking, the session goes on snapshot-only")
    void firstJoinFailureThrows() throws Exception {
        int port = relay.port();
        relay.close();
        BytesocksTransport t = new BytesocksTransport(http, "http://127.0.0.1:" + port, KEY,
                BytesocksTransport.DEFAULT_TTL_MILLIS, now::get, timer);
        var started = t.start(new Recorder());
        ExecutionException e = assertThrows(ExecutionException.class, () -> started.get(15, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, e.getCause());
        assertTrue(e.getCause().getMessage().contains("could not join"), e.getCause().getMessage());
        assertFalse(t.isOpen());
        assertTrue(timer.tasks.isEmpty(), "no timer for a transport that never joined");
    }

    @Test
    @DisplayName("relay URL: https joins over wss, bad keys and schemes are refused")
    void socketUriRules() {
        assertEquals("wss://bytesocks.lucko.me/" + KEY,
                BytesocksTransport.socketUri("https://bytesocks.lucko.me/", KEY).toString());
        assertEquals("ws://127.0.0.1:9/" + KEY, BytesocksTransport.socketUri("http://127.0.0.1:9", KEY).toString());
        assertThrows(IllegalArgumentException.class, () -> BytesocksTransport.socketUri("ftp://x", KEY));
        assertThrows(IllegalArgumentException.class,
                () -> new BytesocksTransport(http, relay.url(), "../x", 1000L, now::get, timer));
    }

    private BytesocksTransport transport() {
        return new BytesocksTransport(http, relay.url(), KEY, BytesocksTransport.DEFAULT_TTL_MILLIS, now::get, timer);
    }

    private static void await(BooleanSupplier cond) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("condition not reached within 10 s");
            Thread.sleep(10);
        }
    }

    private static final class Recorder implements ChannelTransport.Listener {
        final List<String> frames = new CopyOnWriteArrayList<>();
        final List<String> closed = new CopyOnWriteArrayList<>();
        final List<String> expired = new CopyOnWriteArrayList<>();

        @Override
        public void onFrame(String text) {
            frames.add(text);
        }

        @Override
        public void onClosed(String reason) {
            closed.add(reason);
        }

        @Override
        public void onExpired(String reason) {
            expired.add(reason);
        }
    }

    /** Hand-driven stand-in for the async {@code RTP.scheduler} timer. */
    private static final class ManualTimer implements BytesocksTransport.Timer {
        final List<Runnable> tasks = new CopyOnWriteArrayList<>();
        final List<Object> cancelled = new CopyOnWriteArrayList<>();

        @Override
        public Object every(Runnable task, long periodTicks) {
            tasks.add(task);
            return task;
        }

        @Override
        public void cancel(Object handle) {
            cancelled.add(handle);
        }

        void fire() {
            for (Runnable r : tasks) if (!cancelled.contains(r)) r.run();
        }
    }

    /**
     * Minimal bytesocks stand-in on 127.0.0.1: {@code GET /create} answers 201 with {@code Location},
     * any other path upgrades to a WebSocket (RFC 6455 text, close and ping frames only).
     */
    private static final class StubRelay implements AutoCloseable {
        final List<String> httpRequests = new CopyOnWriteArrayList<>();
        final List<String> received = new CopyOnWriteArrayList<>();
        final List<Socket> sockets = new CopyOnWriteArrayList<>();
        volatile boolean failCreate;
        private final ServerSocket server;

        StubRelay() throws IOException {
            server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            Thread accept = new Thread(this::acceptLoop, "stub-relay-accept");
            accept.setDaemon(true);
            accept.start();
        }

        String url() {
            return "http://127.0.0.1:" + port();
        }

        int port() {
            return server.getLocalPort();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    Thread th = new Thread(() -> serve(s), "stub-relay-conn");
                    th.setDaemon(true);
                    th.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void serve(Socket s) {
            try (s) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                List<String> head = readHead(in);
                if (head.isEmpty()) return;
                String[] line = head.get(0).split(" ");
                String wsKey = null;
                for (String h : head) {
                    int c = h.indexOf(':');
                    if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("Sec-WebSocket-Key")) wsKey = h.substring(c + 1).trim();
                }
                if (wsKey == null) {
                    httpRequests.add(line[0] + " " + line[1]);
                    String res = failCreate || !"GET".equals(line[0]) || !"/create".equals(line[1])
                            ? "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            : "HTTP/1.1 201 Created\r\nLocation: /" + KEY + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                    out.write(res.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return;
                }
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                sockets.add(s);
                DataInputStream din = new DataInputStream(in);
                while (true) {
                    int b0 = din.read();
                    if (b0 < 0) return;
                    int b1 = din.readUnsignedByte();
                    long len = b1 & 0x7F;
                    if (len == 126) len = din.readUnsignedShort();
                    else if (len == 127) len = din.readLong();
                    byte[] mask = new byte[4];
                    if ((b1 & 0x80) != 0) din.readFully(mask);
                    byte[] payload = new byte[(int) len];
                    din.readFully(payload);
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                    int op = b0 & 0x0F;
                    if (op == 1) received.add(new String(payload, StandardCharsets.UTF_8));
                    else if (op == 8) {
                        writeFrame(s, 0x88, new byte[0]);
                        return;
                    } else if (op == 9) writeFrame(s, 0x8A, payload);
                }
            } catch (Exception e) {
                // connection ended
            } finally {
                sockets.remove(s);
            }
        }

        private static List<String> readHead(InputStream in) throws IOException {
            List<String> lines = new ArrayList<>();
            ByteArrayOutputStream cur = new ByteArrayOutputStream();
            int prev = -1;
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n' && prev == '\r') {
                    byte[] b = cur.toByteArray();
                    String l = new String(b, 0, Math.max(0, b.length - 1), StandardCharsets.US_ASCII);
                    if (l.isEmpty()) return lines;
                    lines.add(l);
                    cur.reset();
                } else {
                    cur.write(c);
                }
                prev = c;
            }
            return lines;
        }

        private static synchronized void writeFrame(Socket s, int b0, byte[] payload) throws IOException {
            OutputStream out = s.getOutputStream();
            out.write(b0);
            if (payload.length < 126) {
                out.write(payload.length);
            } else {
                out.write(126);
                out.write(payload.length >>> 8);
                out.write(payload.length & 0xFF);
            }
            out.write(payload);
            out.flush();
        }

        void push(String text) throws IOException {
            for (Socket s : sockets) writeFrame(s, 0x81, text.getBytes(StandardCharsets.UTF_8));
        }

        /** The relay drops every socket with a close frame (as on a relay restart). */
        void dropAll() throws IOException {
            for (Socket s : sockets) {
                writeFrame(s, 0x88, new byte[]{0x03, (byte) 0xE9});
                s.close();
            }
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            for (Socket s : sockets) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
