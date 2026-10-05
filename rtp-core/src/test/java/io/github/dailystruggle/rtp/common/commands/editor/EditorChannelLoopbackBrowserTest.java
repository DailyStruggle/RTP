package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorChannel;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorKeys;
import io.github.dailystruggle.rtp.common.commands.editor.channel.LoopbackTransport;
import io.github.dailystruggle.rtp.common.commands.editor.channel.TrustedEditors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-106 §5: the real page's {@code EditorChannelClient} (WebCrypto, IndexedDB key) against the
 * real {@link EditorLoopbackChannel} through {@link LoopbackTransport} - the local export path, run
 * in headless Chrome / Edge from {@code file://}. Covers the handshake, in-game trust, Java/WebCrypto
 * signature interop in both directions, a walk-path reply, Hot-Apply acknowledged, and a forged plugin
 * frame dropped by the page. Skipped when no browser is installed or {@code RTP_BROWSER_TESTS=0}.
 */
@DisplayName("ADR-106 §5: page EditorChannelClient <-> loopback EditorChannel end to end (headless browser)")
class EditorChannelLoopbackBrowserTest {

    private static final List<String> BROWSERS = List.of(
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
            "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser",
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");

    /** Drives the page through the protocol and reports back over the channel itself. */
    private static final String PROBE = """
            <script>
            (async () => {
              const res = { states: [] };
              const wait = (pred, ms) => new Promise((ok) => {
                const t0 = Date.now();
                const iv = setInterval(() => { if (pred()) { clearInterval(iv); ok(true); } else if (Date.now() - t0 > ms) { clearInterval(iv); ok(false); } }, 50);
              });
              EditorChannelClient.onStatus((s) => res.states.push(s));
              res.connected = await wait(() => EditorChannelClient.isConnected(), 30000);
              res.badge = (document.getElementById('channel-badge') || {}).dataset ? document.getElementById('channel-badge').dataset.status : '';
              let wp = null, ack = null, ping = null, forged = false;
              EditorChannelClient.on('walkpath', (m) => { wp = m; });
              EditorChannelClient.on('apply_ack', (m) => { ack = m; });
              EditorChannelClient.on('probe-ping', (m) => { ping = m; });
              EditorChannelClient.on('forged', () => { forged = true; });
              EditorChannelClient.send('walkpath', { region: 'default', sig: 'probe-sig', shape: { name: 'CIRCLE' } });
              await wait(() => wp, 8000);
              res.walkpath = wp ? { sig: wp.sig, hasError: !!wp.error } : null;
              EditorChannelClient.send('apply', { region: 'default', timestamp: 7, files: { 'regions/default.yml': 'shape:\\n  name: CIRCLE\\n' } });
              await wait(() => ack, 8000);
              res.ack = ack ? { success: ack.success, to: ack.to } : null;
              EditorChannelClient.send('probe-ready', {});
              await wait(() => ping, 8000);
              res.ping = ping ? ping.n : null;
              res.forgedHandled = forged;
              res.info = EditorChannelClient.info();
              EditorChannelClient.send('probe-result', { result: JSON.stringify(res) });
            })().catch((e) => { try { EditorChannelClient.send('probe-result', { result: JSON.stringify({ error: String(e) }) }); } catch (x) {} });
            </script>
            """;

    private static String browser() {
        if ("0".equals(System.getenv("RTP_BROWSER_TESTS"))) return null;
        for (String b : BROWSERS) if (Files.isRegularFile(Paths.get(b))) return b;
        return null;
    }

    /** The browser and every helper process it spawned, bounded. */
    private static void killTree(Process proc) throws InterruptedException {
        List<ProcessHandle> tree = new ArrayList<>(proc.descendants().toList());
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try {
                Process tk = new ProcessBuilder("taskkill", "/PID", String.valueOf(proc.pid()), "/T", "/F")
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                tk.waitFor(20, TimeUnit.SECONDS);
            } catch (IOException e) {
                // fall through to destroyForcibly
            }
        }
        tree.add(proc.toHandle());
        for (ProcessHandle h : tree) h.destroyForcibly();
        for (ProcessHandle h : tree) {
            try {
                h.onExit().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                // still exiting; the profile directory delete below is best effort
            }
        }
    }

    private static void deleteQuietly(Path dir) {
        for (int attempt = 0; attempt < 5 && Files.exists(dir); attempt++) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        // locked for a moment longer; retried
                    }
                });
            } catch (IOException | java.io.UncheckedIOException e) {
                // retried
            }
            if (Files.exists(dir)) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static String template() throws IOException {
        Path page = Paths.get("docs", "editor", "index.html");
        if (!Files.exists(page)) page = Paths.get("..", "docs", "editor", "index.html");
        return Files.readString(page, StandardCharsets.UTF_8);
    }

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    @DisplayName("handshake -> awaiting trust -> trusted; walkpath + Hot-Apply answered; forged plugin frame dropped")
    void pageTalksToLoopbackChannel() throws Exception {
        String browser = browser();
        Assumptions.assumeTrue(browser != null, "no Chrome / Edge installed (or RTP_BROWSER_TESTS=0)");

        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
        EditorKeys keys = EditorKeys.generate();
        TrustedEditors trusted = TrustedEditors.inMemory();
        LoopbackTransport transport = new LoopbackTransport(EditorLoopbackChannel.newToken(), EditorLoopbackChannel::openUnscheduled);
        List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        List<String> applied = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<String> result = new CompletableFuture<>();
        EditorChannel[] holder = new EditorChannel[1];
        EditorLoopbackApply apply = new EditorLoopbackApply(null, json -> {
            applied.add(json);
            return CompletableFuture.completedFuture(null);
        });
        EditorChannel channel = new EditorChannel(EditorChannel.newChannelId(), keys, trusted, transport, (nonce, fp) -> {
            prompts.add(nonce);
            // The operator reads the code and runs /rtp editor trust nonce=<code> a moment later
            poller.schedule(() -> holder[0].trust(nonce), 300, TimeUnit.MILLISECONDS);
        }, System::currentTimeMillis);
        holder[0] = channel;
        EditorChannelWiring.register(channel, () -> null, apply);
        channel.register("probe-ready", in -> {
            // A frame signed by another key, then a genuine one: the page must drop the first
            EditorKeys forger = EditorKeys.generate();
            String msg = "{\"type\":\"forged\",\"channel\":\"" + channel.id() + "\",\"seq\":999999,\"from\":\""
                    + channel.pluginFingerprint() + "\"}";
            transport.send("{\"msg\":" + EditorLoopbackJson.quote(msg) + ",\"signature\":\""
                    + Base64.getEncoder().encodeToString(forger.sign(msg.getBytes(StandardCharsets.UTF_8))) + "\"}");
            in.reply("{\"type\":\"probe-ping\",\"n\":42}");
        });
        channel.register("probe-result", in -> result.complete(String.valueOf(in.body().get("result"))));
        channel.start();
        poller.scheduleWithFixedDelay(() -> {
            try {
                transport.socket().poll();
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        }, 0, 10, TimeUnit.MILLISECONDS);

        // Not @TempDir: the browser may hold profile files a moment after it is killed (Windows)
        Path work = Files.createTempDirectory("rtp-channel-e2e");
        Path page = work.resolve("editor").resolve("index.html");
        Files.createDirectories(page.getParent());
        String payload = "{\"channel\":" + EditorSessionManager.mapToJson(channel.snapshotBlock()) + "}";
        String html = EditorSessionManager.embedPayload(template(), payload);
        int end = html.lastIndexOf("</body>");
        Files.writeString(page, html.substring(0, end) + PROBE + html.substring(end), StandardCharsets.UTF_8);

        Process proc = new ProcessBuilder(browser, "--headless=new", "--disable-gpu", "--no-first-run",
                "--no-default-browser-check", "--disable-extensions", "--user-data-dir=" + work.resolve("profile"),
                page.toUri().toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        String json;
        try {
            json = result.get(120, TimeUnit.SECONDS);
        } finally {
            killTree(proc);
            channel.close("test done");
            poller.shutdownNow();
            deleteQuietly(work);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) EditorLoopbackJson.parse(json);
        assertNull(res.get("error"), json);
        assertEquals(Boolean.TRUE, res.get("connected"), "page reached 'connected': " + json);
        List<?> states = (List<?>) res.get("states");
        assertTrue(states.contains("awaiting-trust") && states.indexOf("connected") > states.indexOf("awaiting-trust"),
                "untrusted first, trusted after the in-game trust: " + states);
        assertEquals(1, prompts.size(), "one operator prompt for the browser key");
        Map<?, ?> info = (Map<?, ?>) res.get("info");
        String pageFp = String.valueOf(info.get("fingerprint"));
        assertTrue(trusted.isTrusted(pageFp), "the WebCrypto key's SPKI fingerprint matches Java's");
        assertEquals(channel.pluginFingerprint(), info.get("pluginFingerprint"), "page fingerprints the snapshot key like Java");
        assertEquals("connected", res.get("badge"), "status badge shows the channel state");

        Map<?, ?> wp = (Map<?, ?>) res.get("walkpath");
        assertNotNull(wp, "walkpath reply reached the page: " + json);
        assertEquals("probe-sig", wp.get("sig"));
        Map<?, ?> ack = (Map<?, ?>) res.get("ack");
        assertNotNull(ack, "apply_ack reached the page: " + json);
        assertEquals(Boolean.TRUE, ack.get("success"));
        assertEquals(pageFp, ack.get("to"));
        assertEquals(1, applied.size(), "Hot-Apply went through the apply pipeline once");
        assertTrue(applied.get(0).contains("regions/default.yml"));

        assertEquals(42L, res.get("ping"), "genuine plugin frame after the forged one was accepted");
        assertEquals(Boolean.FALSE, res.get("forgedHandled"), "frame signed by another key never reached a handler");
        assertTrue(((Number) info.get("dropped")).longValue() >= 1, "the page counted the forged frame as dropped");
        assertEquals("bad signature", info.get("lastDrop"));
    }
}
