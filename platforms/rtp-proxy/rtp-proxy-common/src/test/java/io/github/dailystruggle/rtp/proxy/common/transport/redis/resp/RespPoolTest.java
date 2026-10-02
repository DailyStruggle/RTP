package io.github.dailystruggle.rtp.proxy.common.transport.redis.resp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RespPoolTest {

    private ServerSocket serverSocket;
    private int port;
    private ExecutorService serverExecutor;
    private final AtomicBoolean running = new AtomicBoolean(true);

    @BeforeEach
    void setupMockServer() throws IOException {
        serverSocket = new ServerSocket(0);
        port = serverSocket.getLocalPort();
        serverExecutor = Executors.newCachedThreadPool();

        serverExecutor.submit(() -> {
            while (running.get() && !serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    serverExecutor.submit(() -> handleClient(client));
                } catch (IOException ignored) {}
            }
        });
    }

    private void handleClient(Socket client) {
        try (InputStream in = client.getInputStream(); OutputStream out = client.getOutputStream()) {
            while (running.get() && !client.isClosed()) {
                Object cmd = RespProtocol.readReply(in);
                if (cmd instanceof java.util.List<?> list && !list.isEmpty()) {
                    String op = RespProtocol.toUtf8((byte[]) list.get(0)).toUpperCase();
                    if ("PING".equals(op)) {
                        out.write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else if ("GET".equals(op)) {
                        String key = RespProtocol.toUtf8((byte[]) list.get(1));
                        if ("foo".equals(key)) {
                            out.write("$3\r\nbar\r\n".getBytes(StandardCharsets.UTF_8));
                        } else {
                            out.write("$-1\r\n".getBytes(StandardCharsets.UTF_8));
                        }
                        out.flush();
                    } else if ("SET".equals(op)) {
                        out.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else if ("DEL".equals(op)) {
                        out.write(":1\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else if ("TTL".equals(op) || "PTTL".equals(op) || "EXPIRE".equals(op) || "LLEN".equals(op) || "PUBLISH".equals(op)) {
                        out.write(":10\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else if ("HGET".equals(op)) {
                        out.write("$3\r\nval\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else if ("HGETALL".equals(op)) {
                        out.write("*2\r\n$1\r\nk\r\n$1\r\nv\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else if ("SCAN".equals(op)) {
                        out.write("*2\r\n$1\r\n0\r\n*1\r\n$4\r\nkey1\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else if ("SCRIPT".equals(op)) {
                        out.write("$4\r\nsha1\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else if ("EVAL".equals(op) || "EVALSHA".equals(op)) {
                        out.write(":1\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else if ("SUBSCRIBE".equals(op)) {
                        out.write("*3\r\n$9\r\nsubscribe\r\n$4\r\nchan\r\n:1\r\n".getBytes(StandardCharsets.UTF_8));
                        out.write("*3\r\n$7\r\nmessage\r\n$4\r\nchan\r\n$5\r\nhello\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else if ("UNSUBSCRIBE".equals(op)) {
                        out.write("*3\r\n$11\r\nunsubscribe\r\n$4\r\nchan\r\n:0\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else {
                        out.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    @AfterEach
    void tearDown() throws IOException {
        running.set(false);
        if (serverSocket != null) serverSocket.close();
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @Test
    @DisplayName("RespPool borrows, executes, and returns connection to pool")
    void testPoolBorrowAndReturn() throws IOException {
        try (RespPool pool = new RespPool("127.0.0.1", port, 1000, null, 2)) {
            try (RespConnection conn = pool.getResource()) {
                assertEquals("PONG", conn.ping());
                assertEquals("bar", conn.get("foo"));
                conn.set("key", "val");
            }

            // Borrow again to ensure connection was returned and reused
            try (RespConnection conn2 = pool.getResource()) {
                assertEquals("PONG", conn2.ping());
                assertEquals("OK", conn2.set("k", "v", "NX", "PX", 1000));
                conn2.setex("k", 10, "v");
                assertEquals(1L, conn2.del("k"));
                assertEquals(10L, conn2.ttl("k"));
                assertEquals(10L, conn2.pttl("k"));
                assertEquals(10L, conn2.expire("k", 10));
                assertEquals(10L, conn2.publish("c", "m"));
                assertEquals("val", conn2.hget("k", "f"));
                conn2.hset("k", "f", "v");
                conn2.hset("k", java.util.Map.of("f1", "v1"));
                assertEquals(1, conn2.hgetAll("k").size());
                assertEquals(10L, conn2.llen("k"));
                assertEquals("sha1", conn2.scriptLoad("return 1"));
                assertEquals(1L, conn2.eval("return 1", java.util.List.of(), java.util.List.of()));
                assertEquals(1L, conn2.evalsha("sha1", java.util.List.of(), java.util.List.of()));
                RespConnection.ScanResult sr = conn2.scan("0", "k*", 10);
                assertEquals("0", sr.getCursor());
                assertEquals(1, sr.getResult().size());
            }

            assertFalse(pool.isClosed());
        }
    }

    @Test
    @DisplayName("RespPubSub subscription processes messages and unsubscribes")
    void testPubSub() throws Exception {
        try (RespConnection conn = new RespConnection("127.0.0.1", port, 2000, null)) {
            java.util.concurrent.atomic.AtomicReference<String> received = new java.util.concurrent.atomic.AtomicReference<>();
            RespPubSub pubSub = new RespPubSub() {
                @Override
                public void onMessage(String channel, String message) {
                    received.set(message);
                    unsubscribe();
                }
            };
            pubSub.proceed(conn, "chan");
            assertEquals("hello", received.get());
            assertFalse(pubSub.isSubscribed());
        }
    }
}
