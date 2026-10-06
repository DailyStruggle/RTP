package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.editor.channel.LoopbackTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@DisplayName("ADR-104 loopback editor WebSocket channel + Hot-Apply")
class EditorLoopbackChannelTest {

    private static final long DEADLINE_MILLIS = 10_000L;

    private final String token = EditorLoopbackChannel.newToken();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger opens = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private final AtomicReference<String> appliedPayload = new AtomicReference<>();
    private final List<WebSocket> sockets = new ArrayList<>();
    private EditorLoopbackChannel channel;
    private HttpClient http;

    @BeforeEach
    void setUp() throws IOException {
        EditorLoopbackApply apply = new EditorLoopbackApply(token, json -> {
            appliedPayload.set(json);
            return CompletableFuture.completedFuture(null);
        });
        channel = EditorLoopbackChannel.open(token, new EditorLoopbackChannel.Listener() {
            @Override
            public void onMessage(EditorLoopbackChannel.Connection c, String text) {
                received.add(text);
            }

            @Override
            public void onOpen(EditorLoopbackChannel.Connection c) {
                opens.incrementAndGet();
            }

            @Override
            public void onClose(EditorLoopbackChannel.Connection c) {
                closes.incrementAndGet();
            }
        }, apply);
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        for (WebSocket ws : sockets) ws.abort();
        channel.stop();
        http.close();
    }

    // ---- handshake + messaging ----

    @Test
    @DisplayName("ADR-104: handshake, client->server message reaches Listener, broadcast reaches client")
    void handshakeMessageAndBroadcast() {
        Client client = new Client();
        WebSocket ws = connect(token, "null", client);
        pollUntil(() -> channel.clientCount() == 1, "client registered");
        assertEquals(1, opens.get());

        String focus = "{\"type\":\"focus\",\"region\":\"default\",\"x\":12,\"z\":-4}";
        ws.sendText(focus, true);
        pollUntil(() -> received.contains(focus), "message delivered to listener");

        channel.broadcast("hello \u00e9");
        pollUntil(() -> client.messages.contains("hello \u00e9"), "broadcast delivered");

        assertTrue(channel.isOpen());
        assertFalse(channel.send(null, "x"), "send to null connection is refused");
    }

    @Test
    @DisplayName("ADR-104: localhost http Origin and absent Origin are admitted")
    void localhostOriginsAdmitted() {
        connect(token, "http://localhost:8080", new Client());
        connect(token, null, new Client());
        pollUntil(() -> channel.clientCount() == 2, "both clients registered");
    }

    @Test
    @DisplayName("ADR-104: wrong token is rejected with 401")
    void wrongTokenRejected() {
        assertEquals(401, handshakeStatus(EditorLoopbackChannel.newToken(), null));
        assertEquals(0, channel.clientCount());
    }

    @Test
    @DisplayName("ADR-104 / ADR-106: a newer local export rotates the token; the previous export's token is refused")
    void exportRotatesToken() {
        // Same sequence as EditorLiveFeed.exportAndStart: the old transport is closed, a new one opens.
        LoopbackTransport first = new LoopbackTransport(token, (t, l) -> channel);
        assertTrue(first.start(text -> { }).isDone());
        String firstUrl = first.relay();
        first.close("superseded by a newer export");
        assertFalse(first.isOpen());
        assertFalse(channel.isOpen(), "superseded export's socket is stopped");

        String rotated = EditorLoopbackChannel.newToken();
        LoopbackTransport second = new LoopbackTransport(rotated, EditorLoopbackChannel::openUnscheduled);
        assertTrue(second.start(text -> { }).isDone());
        channel = second.socket();
        assertFalse(second.relay().equals(firstUrl));
        assertTrue(second.relay().endsWith("?token=" + rotated));

        assertEquals(401, handshakeStatus(token, null), "previous export's token");
        assertEquals(0, channel.clientCount());
        connect(rotated, "null", new Client());
        pollUntil(() -> channel.clientCount() == 1, "rotated token admitted");
    }

    @Test
    @DisplayName("ADR-104: generated loopback tokens are unique 128-bit hex")
    void newTokenUnique() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 256; i++) {
            String t = EditorLoopbackChannel.newToken();
            assertTrue(t.matches("[0-9a-f]{32}"), t);
            assertTrue(seen.add(t), "duplicate token");
        }
    }

    @Test
    @DisplayName("ADR-104: disallowed Origin is rejected with 403")
    void disallowedOriginRejected() {
        assertEquals(403, handshakeStatus(token, "https://evil.example"));
        assertEquals(403, handshakeStatus(token, "http://localhost.evil.example"));
        assertEquals(0, channel.clientCount());
    }

    @Test
    @DisplayName("ADR-104: fifth concurrent client is rejected with 503")
    void fifthClientRejected() {
        for (int i = 0; i < EditorLoopbackChannel.MAX_CLIENTS; i++) connect(token, null, new Client());
        pollUntil(() -> channel.clientCount() == EditorLoopbackChannel.MAX_CLIENTS, "four clients registered");
        assertEquals(503, handshakeStatus(token, null));
        assertEquals(EditorLoopbackChannel.MAX_CLIENTS, channel.clientCount());
    }

    @Test
    @DisplayName("ADR-104: stop() closes connected clients and the listener")
    void stopClosesClients() {
        Client client = new Client();
        connect(token, null, client);
        pollUntil(() -> channel.clientCount() == 1, "client registered");
        channel.stop();
        assertFalse(channel.isOpen());
        assertEquals(0, channel.clientCount());
        assertEquals(1, closes.get());
        pollUntil(client.closed::isDone, "client observed close");
        assertThrows(IOException.class, () -> {
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), channel.port())) {
                s.getOutputStream().write(1);
            }
        });
    }

    // ---- raw-socket protocol violations ----

    @Test
    @DisplayName("ADR-104: unmasked client frame closes with 1002")
    void unmaskedFrameCloses1002() throws IOException {
        try (Socket s = rawHandshake()) {
            s.getOutputStream().write(new byte[]{(byte) 0x81, 0x02, 'h', 'i'});
            assertEquals(1002, readCloseCode(s));
        }
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("ADR-104: message over 256 KiB closes with 1009 before the payload is buffered")
    void oversizeMessageCloses1009() throws IOException {
        try (Socket s = rawHandshake()) {
            long len = EditorLoopbackChannel.MAX_MESSAGE_BYTES + 1L;
            byte[] header = new byte[14];
            header[0] = (byte) 0x81;
            header[1] = (byte) (0x80 | 127);
            for (int k = 0; k < 8; k++) header[2 + k] = (byte) (len >>> (8 * (7 - k)));
            s.getOutputStream().write(header);
            assertEquals(1009, readCloseCode(s));
        }
    }

    @Test
    @DisplayName("ADR-104: binary frame closes with 1003; ping is answered with pong")
    void binaryCloses1003AndPingPongs() throws IOException {
        try (Socket s = rawHandshake()) {
            s.getOutputStream().write(maskedFrame(0x9, "p".getBytes(StandardCharsets.US_ASCII)));
            byte[] pong = readFrame(s);
            assertEquals(0x8A, pong[0] & 0xFF);
            assertEquals('p', pong[2]);
            s.getOutputStream().write(maskedFrame(0x2, new byte[]{1, 2, 3}));
            assertEquals(1003, readCloseCode(s));
        }
    }

    @Test
    @DisplayName("ADR-104: oversize handshake headers are rejected with 431")
    void oversizeHandshakeRejected() throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), channel.port())) {
            OutputStream out = s.getOutputStream();
            out.write(("GET " + EditorLoopbackChannel.PATH + "?token=" + token + " HTTP/1.1\r\nX-Pad: ")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write("a".repeat(EditorLoopbackChannel.MAX_HANDSHAKE_BYTES + 16).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertTrue(readHttpHead(s).startsWith("HTTP/1.1 431"));
        }
    }

    // ---- Hot-Apply ----

    @Test
    @DisplayName("ADR-104: apply over the socket reaches the pipeline and is acked; other messages bypass it")
    void applyOverSocketIsAcked() {
        Client client = new Client();
        WebSocket ws = connect(token, null, client);
        pollUntil(() -> channel.clientCount() == 1, "client registered");
        ws.sendText("{\"action\":\"apply\",\"token\":\"" + token + "\",\"region\":\"default\",\"timestamp\":42,"
                + "\"files\":{\"regions/default.yml\":\"radius: 5\\n\"}}", true);
        pollUntil(() -> !client.messages.isEmpty(), "apply_ack received");
        Map<?, ?> ack = (Map<?, ?>) EditorLoopbackJson.parse(client.messages.get(0));
        assertEquals("apply_ack", ack.get("type"));
        assertEquals(Boolean.TRUE, ack.get("success"));
        assertEquals(42L, ack.get("timestamp"));
        assertTrue(appliedPayload.get().contains("regions/default.yml"));
        assertTrue(received.isEmpty(), "apply messages are not forwarded to the listener");
    }

    @Test
    @DisplayName("ADR-104: path traversal, wrong token and non-YAML keys are refused (success=false)")
    void applyRejectsInvalidPayloads() {
        EditorLoopbackApply apply = validatingApply();
        Map<?, ?> traversal = ack(apply, applyMessage(token, Map.of("../evil.yml", "a: 1")));
        assertEquals(Boolean.FALSE, traversal.get("success"));
        assertTrue(String.valueOf(traversal.get("error")).contains("Illegal file path"), String.valueOf(traversal.get("error")));

        Map<?, ?> badToken = ack(apply, applyMessage(EditorLoopbackChannel.newToken(), Map.of("config.yml", "a: 1")));
        assertEquals(Boolean.FALSE, badToken.get("success"));
        assertEquals("invalid session token", badToken.get("error"));

        Map<?, ?> notYaml = ack(apply, applyMessage(token, Map.of("plugin.jar", "x")));
        assertEquals(Boolean.FALSE, notYaml.get("success"));

        Map<?, ?> empty = ack(apply, applyMessage(token, Map.of()));
        assertEquals(Boolean.FALSE, empty.get("success"));
    }

    @Test
    @DisplayName("ADR-104: rebuilt payload round-trips through EditorSessionManager validation")
    void payloadRoundTripsThroughSessionManager() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("regions/default.yml", "radius: 5\nname: \"quoted \\\\ back\"\t# tab\nnote: caf\u00e9\n");
        files.put("config.yml", "a: 1\n");
        String payload = EditorLoopbackApply.payloadJson(files, 7L);
        EditorSessionManager.ParsedPayload parsed = EditorSessionManager.getInstance().parseAndValidatePayload(payload);
        assertEquals(files, parsed.files());
        assertEquals(7L, parsed.timestamp());
    }

    @Test
    @DisplayName("ADR-104: production apply fails closed before core init (S-006)")
    void productionApplyFailsClosedPreInit() {
        Assumptions.assumeTrue(RTP.configs == null && RTP.serverAccessor == null, "core initialised by another test");
        Map<?, ?> ack = ack(EditorLoopbackApply.forSessionManager(token), applyMessage(token, Map.of("config.yml", "a: 1")));
        assertEquals(Boolean.FALSE, ack.get("success"));
        assertTrue(String.valueOf(ack.get("error")).contains("S-006"));
    }

    @Test
    @DisplayName("ADR-104: start() without a scheduler throws IllegalStateException (S-006)")
    void startWithoutSchedulerFailsClosed() {
        Assumptions.assumeTrue(RTP.scheduler == null, "scheduler initialised by another test");
        assertThrows(IllegalStateException.class, () -> EditorLoopbackChannel.start(token, (c, t) -> { }));
    }

    @Test
    @DisplayName("ADR-104: minimal JSON reader is strict")
    void jsonReaderIsStrict() {
        Object v = EditorLoopbackJson.parse("{\"a\":[1,2.5,true,null,\"\\u00e9\\n\"],\"b\":{}}");
        Map<?, ?> m = assertInstanceOf(Map.class, v);
        assertEquals(java.util.Arrays.asList(1L, 2.5, true, null, "\u00e9\n"), m.get("a"));
        assertEquals(Map.of(), m.get("b"));
        assertThrows(IllegalArgumentException.class, () -> EditorLoopbackJson.parse("{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class, () -> EditorLoopbackJson.parse("{\"a\":1} x"));
        assertThrows(IllegalArgumentException.class, () -> EditorLoopbackJson.parse("{\"a\":01}"));
        assertEquals("\"a\\\"\\u0001\"", EditorLoopbackJson.quote("a\"\u0001"));
    }

    // ---- helpers ----

    private EditorLoopbackApply validatingApply() {
        return new EditorLoopbackApply(token, json -> {
            EditorSessionManager.getInstance().parseAndValidatePayload(json);
            return CompletableFuture.completedFuture(null);
        });
    }

    private static Map<?, ?> ack(EditorLoopbackApply apply, String message) {
        String ack = apply.handle((Map<?, ?>) EditorLoopbackJson.parse(message)).join();
        Map<?, ?> m = (Map<?, ?>) EditorLoopbackJson.parse(ack);
        assertEquals("apply_ack", m.get("type"));
        return m;
    }

    private static String applyMessage(String token, Map<String, String> files) {
        StringBuilder sb = new StringBuilder("{\"action\":\"apply\",\"token\":").append(EditorLoopbackJson.quote(token))
                .append(",\"region\":\"default\",\"timestamp\":1,\"files\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(EditorLoopbackJson.quote(e.getKey())).append(':').append(EditorLoopbackJson.quote(e.getValue()));
        }
        return sb.append("}}").toString();
    }

    private URI uri(String tok) {
        return URI.create("ws://127.0.0.1:" + channel.port() + EditorLoopbackChannel.PATH + "?token=" + tok);
    }

    private CompletableFuture<WebSocket> connectAsync(String tok, String origin, Client client) {
        WebSocket.Builder b = http.newWebSocketBuilder();
        if (origin != null) b.header("Origin", origin);
        return b.buildAsync(uri(tok), client);
    }

    private WebSocket connect(String tok, String origin, Client client) {
        CompletableFuture<WebSocket> f = connectAsync(tok, origin, client);
        pollUntil(f::isDone, "handshake");
        WebSocket ws = f.join();
        sockets.add(ws);
        return ws;
    }

    private int handshakeStatus(String tok, String origin) {
        CompletableFuture<WebSocket> f = connectAsync(tok, origin, new Client());
        pollUntil(f::isDone, "handshake");
        CompletionException e = assertThrows(CompletionException.class, f::join);
        WebSocketHandshakeException hs = assertInstanceOf(WebSocketHandshakeException.class, e.getCause());
        return hs.getResponse().statusCode();
    }

    private void pollUntil(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + DEADLINE_MILLIS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for " + what);
            channel.poll();
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted waiting for " + what);
            }
        }
    }

    private Socket rawHandshake() throws IOException {
        Socket s = new Socket(InetAddress.getLoopbackAddress(), channel.port());
        String key = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
        String req = "GET " + EditorLoopbackChannel.PATH + "?token=" + token + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + channel.port() + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\nOrigin: null\r\n\r\n";
        s.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
        String head = readHttpHead(s);
        assertTrue(head.startsWith("HTTP/1.1 101"), head);
        assertTrue(head.contains("Sec-WebSocket-Accept: " + EditorLoopbackChannel.acceptKey(key)), head);
        return s;
    }

    private String readHttpHead(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        pollUntil(() -> {
            try {
                while (in.available() > 0) buf.write(in.read());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return buf.toString(StandardCharsets.ISO_8859_1).contains("\r\n\r\n");
        }, "HTTP response head");
        return buf.toString(StandardCharsets.ISO_8859_1);
    }

    /** Reads one short unmasked server frame (payload < 126). */
    private byte[] readFrame(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        pollUntil(() -> {
            try {
                while (in.available() > 0) buf.write(in.read());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            byte[] b = buf.toByteArray();
            return b.length >= 2 && b.length >= 2 + (b[1] & 0x7F);
        }, "server frame");
        return buf.toByteArray();
    }

    private int readCloseCode(Socket s) throws IOException {
        byte[] f = readFrame(s);
        assertEquals(0x88, f[0] & 0xFF, "close frame expected");
        int code = ((f[2] & 0xFF) << 8) | (f[3] & 0xFF);
        pollUntil(() -> {
            try {
                s.setSoTimeout(5);
                return s.getInputStream().read() < 0;
            } catch (java.net.SocketTimeoutException e) {
                return false;
            } catch (IOException e) {
                return true; // reset by server is also a close
            }
        }, "server closed TCP");
        return code;
    }

    private static byte[] maskedFrame(int opcode, byte[] payload) {
        byte[] mask = {0x11, 0x22, 0x33, 0x44};
        byte[] f = new byte[2 + 4 + payload.length];
        f[0] = (byte) (0x80 | opcode);
        f[1] = (byte) (0x80 | payload.length);
        System.arraycopy(mask, 0, f, 2, 4);
        for (int k = 0; k < payload.length; k++) f[6 + k] = (byte) (payload[k] ^ mask[k & 3]);
        return f;
    }

    private static final class Client implements WebSocket.Listener {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            closed.completeExceptionally(error);
        }
    }
}
