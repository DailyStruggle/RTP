package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorChannel;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorKeys;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorLoopbackChannel;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorLoopbackJson;
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
 * signature interop in both directions, Hot-Apply acknowledged, and a forged plugin
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
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/usr/bin/firefox");

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
              let ack = null, ping = null, forged = false;
              EditorChannelClient.on('apply_ack', (m) => { ack = m; });
              EditorChannelClient.on('probe-ping', (m) => { ping = m; });
              EditorChannelClient.on('forged', () => { forged = true; });
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

    private static Process launchBrowser(String browser, Path profileDir, String url) throws IOException {
        Files.createDirectories(profileDir);
        if (browser.toLowerCase().contains("firefox")) {
            return new ProcessBuilder(browser, "--headless", "--no-remote", "--profile", profileDir.toString(), url)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        }
        return new ProcessBuilder(browser, "--headless=new", "--disable-gpu", "--no-first-run",
                "--no-default-browser-check", "--disable-extensions", "--user-data-dir=" + profileDir, url)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
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
    @DisplayName("handshake -> awaiting trust -> trusted; Hot-Apply answered; forged plugin frame dropped")
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

        Process proc = launchBrowser(browser, work.resolve("profile"), page.toUri().toString());
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

    /** Reports the page's states and whether the link secret left the address bar. */
    private static final String LINK_PROBE = """
            <script>
            (async () => {
              const res = { states: [] };
              EditorChannelClient.onStatus((s) => res.states.push(s));
              const t0 = Date.now();
              while (!EditorChannelClient.isConnected() && Date.now() - t0 < 30000) await new Promise((ok) => setTimeout(ok, 50));
              res.connected = EditorChannelClient.isConnected();
              res.hash = window.location.hash;
              res.info = EditorChannelClient.info();
              EditorChannelClient.send('probe-result', { result: JSON.stringify(res) });
            })().catch((e) => { try { EditorChannelClient.send('probe-result', { result: JSON.stringify({ error: String(e) }) }); } catch (x) {} });
            </script>
            """;

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    @DisplayName("ADR-106 §5.10: a page opened with #k=<link secret> is trusted with no prompt (WebCrypto HMAC = Java HMAC)")
    void linkSecretTrustsWithoutPrompt() throws Exception {
        String browser = browser();
        Assumptions.assumeTrue(browser != null, "no Chrome / Edge installed (or RTP_BROWSER_TESTS=0)");

        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
        EditorKeys keys = EditorKeys.generate();
        TrustedEditors trusted = TrustedEditors.inMemory();
        LoopbackTransport transport = new LoopbackTransport(EditorLoopbackChannel.newToken(), EditorLoopbackChannel::openUnscheduled);
        List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<String> result = new CompletableFuture<>();
        EditorChannel channel = new EditorChannel(EditorChannel.newChannelId(), keys, trusted, transport,
                (nonce, fp) -> prompts.add(nonce), System::currentTimeMillis);
        channel.register("probe-result", in -> result.complete(String.valueOf(in.body().get("result"))));
        channel.start();
        poller.scheduleWithFixedDelay(() -> {
            try {
                transport.socket().poll();
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        }, 0, 10, TimeUnit.MILLISECONDS);

        Path work = Files.createTempDirectory("rtp-channel-link");
        Path page = work.resolve("editor").resolve("index.html");
        Files.createDirectories(page.getParent());
        String payload = "{\"channel\":" + EditorSessionManager.mapToJson(channel.snapshotBlock()) + "}";
        String html = EditorSessionManager.embedPayload(template(), payload);
        int end = html.lastIndexOf("</body>");
        Files.writeString(page, html.substring(0, end) + LINK_PROBE + html.substring(end), StandardCharsets.UTF_8);

        Process proc = launchBrowser(browser, work.resolve("profile"), page.toUri() + "#k=" + channel.linkSecret());
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
        assertEquals(Boolean.TRUE, res.get("connected"), "trusted by the link: " + json);
        assertFalse(((List<?>) res.get("states")).contains("awaiting-trust"), "never asked for a code: " + json);
        assertTrue(prompts.isEmpty(), "no operator prompt");
        assertEquals("", res.get("hash"), "the secret left the address bar");
        String pageFp = String.valueOf(((Map<?, ?>) res.get("info")).get("fingerprint"));
        assertFalse(trusted.isTrusted(pageFp), "session trust is not persisted");
    }

    private static Path resolveRepoFile(String first, String... more) {
        Path p = Paths.get(first, more);
        if (!Files.exists(p)) {
            String[] parentMore = new String[more.length + 1];
            parentMore[0] = first;
            System.arraycopy(more, 0, parentMore, 1, more.length);
            p = Paths.get("..", parentMore);
        }
        return p;
    }

    /**
     * Drives the hosted official site + Web Editor DOM over HTTP (matching GitHub Pages /RTP/ layout):
     * 1. Hydrates static ./editor-data.json over HTTP when snapshot omits inline shipped/docTracker/synonyms;
     * 2. Exercises Quick Find (#palette-query-input -> #palette-results-list -> selectSearchHit) for
     *    3.3.1 keys (maxSlope), Java AST classes (JumpAdjustor), addon BIT codes (ACTADDON-0001), and Spanish (dinero);
     * 3. Exercises Official Site doc pulling (#region-doc-drawer -> fetchOfficialSitePage -> OFFICIAL SITE badge);
     * 4. Exercises the floating Log & Config Doctor widget (rtp-support-doctor.js: #rtp-doctor-fab ->
     *    #rtp-doctor-input -> #rtp-doctor-run -> #rtp-doctor-output & #rtp-doctor-copy);
     * 5. Exercises YAML staging diff (#staging-diff) and signed Hot-Apply over the channel.
     */
    private static final String OFFICIAL_DOM_PROBE = """
            <script src="/RTP/assets/javascripts/rtp-support-doctor.js"></script>
            <script>
            (async () => {
              const res = {};
              const wait = (pred, ms) => new Promise((ok) => {
                const t0 = Date.now();
                const iv = setInterval(() => { if (pred()) { clearInterval(iv); ok(true); } else if (Date.now() - t0 > ms) { clearInterval(iv); ok(false); } }, 50);
              });

              // 1. Wait for hosted byte-store snapshot + static ./editor-data.json hydration + channel trust
              res.connected = await wait(() => EditorChannelClient.isConnected()
                && document.querySelectorAll('#cfg-file-list .cfg-file-btn').length > 10, 30000);
              res.connBadge = (document.getElementById('conn-badge') || {}).textContent || '';
              res.cfgButtonCount = document.querySelectorAll('#cfg-file-list .cfg-file-btn').length;
              res.docButtonCount = document.querySelectorAll('#doc-sidebar-list .doc-item-btn').length;
              res.urlScrubbed = !window.location.search.includes('token=') && window.location.hash === '';

              // 2. Drive the Quick Find search modal through the DOM
              openSearchModal();
              const pInput = document.getElementById('palette-query-input');
              const pList = document.getElementById('palette-results-list');
              const runDomSearch = (q) => {
                pInput.value = q;
                handleSearchInput(q);
                return Array.from(pList.querySelectorAll('.search-hit-item')).map(el => el.textContent.replace(/\\s+/g, ' ').trim()).slice(0, 3);
              };
              res.searchMaxSlope = runDomSearch('maxSlope').join(' | ');
              res.searchJumpAdjustor = runDomSearch('JumpAdjustor').join(' | ');
              res.searchActAddonBit = runDomSearch('ACTADDON-0001').join(' | ');
              res.searchDinero = runDomSearch('dinero').join(' | ');
              selectSearchHit(0);
              await wait(() => (document.getElementById('cfg-editor') || {}).value.includes('price'), 5000);
              res.selectedConfigHasPrice = ((document.getElementById('cfg-editor') || {}).value || '').includes('price');

              // 3. Drive Official Site doc pulling through #region-doc-drawer
              toggleRegionDocDrawer();
              await wait(() => ((document.getElementById('region-doc-source-pill') || {}).textContent || '') === 'OFFICIAL SITE', 8000);
              res.regionDocBadge = (document.getElementById('region-doc-source-pill') || {}).textContent || '';
              res.regionDocText = ((document.getElementById('region-doc-content') || {}).textContent || '').replace(/\\s+/g, ' ').trim();

              // 4. Drive the floating Log & Config Doctor widget (rtp-support-doctor.js) through its DOM buttons
              const fab = document.getElementById('rtp-doctor-fab');
              if (fab) fab.click();
              const docModal = document.getElementById('rtp-doctor-modal');
              res.doctorModalOpened = docModal ? docModal.style.display === 'flex' : false;
              const docInput = document.getElementById('rtp-doctor-input');
              const docRun = document.getElementById('rtp-doctor-run');
              const docCopy = document.getElementById('rtp-doctor-copy');
              const docOut = document.getElementById('rtp-doctor-output');
              if (docInput && docRun) {
                docInput.value = ['ACTADDON-0001 CLAIM-0004 at io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor.adjust(JumpAdjustor.java:88)', 'slopeRadius: 999', 'maxSlope: -10', 'dinero', 'password: "mySuperSecret123"'].join('\\n');
                docRun.click();
                await wait(() => docOut && docOut.textContent.includes('ACTADDON_NOT_REGISTERED'), 8000);
                res.doctorOutputText = docOut ? docOut.textContent.replace(/\\s+/g, ' ').trim() : '';
                if (docCopy) {
                  docCopy.click();
                  await wait(() => typeof window.__lastCopiedDoctorSummary === 'string', 5000);
                  res.doctorCopiedSummary = window.__lastCopiedDoctorSummary || '';
                }
              }

              // 5. Stage a region radius edit in DOM and verify #staging-diff + signed Hot-Apply
              changeRegionProfile('default');
              regionState.settings.radius = '4321';
              updateStagingDiff();
              res.stagingDiffHtml = (document.getElementById('staging-diff') || {}).innerHTML || '';
              let ack = null;
              EditorChannelClient.on('apply_ack', (m) => { ack = m; });
              hotApplyWebSocket();
              await wait(() => ack !== null, 8000);
              res.applyAck = ack ? Boolean(ack.success) : false;

              EditorChannelClient.send('probe-result', { result: JSON.stringify(res) });
            })().catch((e) => { try { EditorChannelClient.send('probe-result', { result: JSON.stringify({ error: String(e && e.stack || e) }) }); } catch (x) {} });
            </script>
            """;

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    @DisplayName("Official hosted /RTP/ site + /RTP/editor/ DOM e2e: static editor-data.json hydration, AST search modal, official doc pull, Doctor drawer, and Hot-Apply")
    void hostedOfficialSiteDocsDoctorAndEditorDomEndToEnd() throws Exception {
        String browser = browser();
        Assumptions.assumeTrue(browser != null, "no Chrome / Edge / Firefox installed (or RTP_BROWSER_TESTS=0)");

        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
        EditorKeys keys = EditorKeys.generate();
        TrustedEditors trusted = TrustedEditors.inMemory();
        LoopbackTransport transport = new LoopbackTransport(EditorLoopbackChannel.newToken(), EditorLoopbackChannel::openUnscheduled);
        List<String> applied = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<String> result = new CompletableFuture<>();
        EditorLoopbackApply apply = new EditorLoopbackApply(null, json -> {
            applied.add(json);
            return CompletableFuture.completedFuture(null);
        });
        EditorChannel channel = new EditorChannel(EditorChannel.newChannelId(), keys, trusted, transport,
                (nonce, fp) -> {}, System::currentTimeMillis);
        EditorChannelWiring.register(channel, () -> null, apply);
        channel.register("probe-result", in -> result.complete(String.valueOf(in.body().get("result"))));
        channel.start();
        poller.scheduleWithFixedDelay(() -> {
            try {
                transport.socket().poll();
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        }, 0, 10, TimeUnit.MILLISECONDS);

        Map<String, String> serverFiles = Map.of(
                "config.yml", "teleportDelay: 3\n",
                "definitions/regions/default.yml", "world: \"[0]\"\nshape:\n  name: CIRCLE\n  radius: 256\n  centerRadius: 64\nvert:\n  name: JUMP\n  minY: 0\n  maxY: 256\n"
        );
        String hostedPayloadJson = EditorSessionManager.getInstance().createPayloadJson(serverFiles, channel.snapshotBlock());
        assertFalse(hostedPayloadJson.contains("\"docTracker\":"), "hosted snapshot must omit bulky inline editor-data.json");
        assertTrue(hostedPayloadJson.contains("\"editorDataRef\":\"./editor-data.json\""));

        byte[] editorDataBytes = Files.readAllBytes(resolveRepoFile("docs", "editor", "editor-data.json"));
        byte[] doctorJsBytes = Files.readAllBytes(resolveRepoFile("docs", "assets", "javascripts", "rtp-support-doctor.js"));
        byte[] corpusJsonBytes = Files.readAllBytes(resolveRepoFile("docs", "assets", "support-corpus.json"));
        String rawEditorHtml = template();
        int bodyEnd = rawEditorHtml.lastIndexOf("</body>");
        byte[] editorHtmlBytes = (rawEditorHtml.substring(0, bodyEnd) + OFFICIAL_DOM_PROBE + rawEditorHtml.substring(bodyEnd))
                .getBytes(StandardCharsets.UTF_8);
        byte[] officialRegionsDocHtml = """
                <!DOCTYPE html><html><body>
                <article class="md-content__inner">
                  <h1>Region Configuration (Official Site)</h1>
                  <p>Configure <code>vert.maxSlope</code>, <code>vert.slopeRadius</code>, and <code>vert.maxHillAngle</code> to prevent steep cliff landings.</p>
                </article>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8);

        com.sun.net.httpserver.HttpServer httpServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        String sessionToken = "hostedTok987654";
        httpServer.createContext("/RTP/editor/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] body;
            String ct;
            if (path.endsWith("/editor-data.json")) {
                body = editorDataBytes;
                ct = "application/json; charset=utf-8";
            } else {
                body = editorHtmlBytes;
                ct = "text/html; charset=utf-8";
            }
            ex.getResponseHeaders().set("Content-Type", ct);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        httpServer.createContext("/RTP/assets/javascripts/rtp-support-doctor.js", ex -> {
            ex.getResponseHeaders().set("Content-Type", "application/javascript; charset=utf-8");
            ex.sendResponseHeaders(200, doctorJsBytes.length);
            ex.getResponseBody().write(doctorJsBytes);
            ex.close();
        });
        httpServer.createContext("/RTP/assets/support-corpus.json", ex -> {
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, corpusJsonBytes.length);
            ex.getResponseBody().write(corpusJsonBytes);
            ex.close();
        });
        httpServer.createContext("/RTP/admin/configuration/REGIONS/", ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, officialRegionsDocHtml.length);
            ex.getResponseBody().write(officialRegionsDocHtml);
            ex.close();
        });
        httpServer.createContext("/bytebin/" + sessionToken, ex -> {
            byte[] b = hostedPayloadJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        httpServer.start();
        int httpPort = httpServer.getAddress().getPort();

        Path work = Files.createTempDirectory("rtp-official-dom-e2e");
        String url = "http://127.0.0.1:" + httpPort + "/RTP/editor/?token=" + sessionToken
                + "&bytebin=" + java.net.URLEncoder.encode("http://127.0.0.1:" + httpPort + "/bytebin", StandardCharsets.UTF_8)
                + "#k=" + channel.linkSecret();
        Process proc = launchBrowser(browser, work.resolve("profile"), url);
        String json;
        try {
            json = result.get(120, TimeUnit.SECONDS);
        } finally {
            killTree(proc);
            httpServer.stop(0);
            channel.close("test done");
            poller.shutdownNow();
            deleteQuietly(work);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) EditorLoopbackJson.parse(json);
        assertNull(res.get("error"), json);
        assertEquals(Boolean.TRUE, res.get("connected"), "Hosted editor connected and hydrated editor-data.json: " + json);
        assertTrue(String.valueOf(res.get("connBadge")).contains("BYTE-STORE"), "conn-badge shows BYTE-STORE: " + json);
        assertTrue(((Number) res.get("cfgButtonCount")).intValue() > 15, "Config sidebar populated from static editor-data.json: " + json);
        assertEquals(Boolean.TRUE, res.get("urlScrubbed"), "Token and #k= link secret scrubbed from address bar: " + json);

        // Search modal DOM assertions
        assertTrue(String.valueOf(res.get("searchMaxSlope")).contains("maxSlope"), "DOM search for maxSlope: " + res.get("searchMaxSlope"));
        assertTrue(String.valueOf(res.get("searchJumpAdjustor")).contains("maxY"), "DOM AST search for JumpAdjustor: " + res.get("searchJumpAdjustor"));
        assertTrue(String.valueOf(res.get("searchActAddonBit")).contains("strictActions"), "DOM BIT search for ACTADDON-0001: " + res.get("searchActAddonBit"));
        assertTrue(String.valueOf(res.get("searchDinero")).contains("price"), "DOM Spanish search for dinero: " + res.get("searchDinero"));
        assertEquals(Boolean.TRUE, res.get("selectedConfigHasPrice"), "Clicking search hit opened economy.yml in #cfg-editor: " + json);

        // Official site doc pulling assertions
        assertEquals("OFFICIAL SITE", res.get("regionDocBadge"), "Region doc drawer fetched official site HTML: " + json);
        assertTrue(String.valueOf(res.get("regionDocText")).contains("vert.maxSlope"), "Official site article rendered in drawer: " + json);

        // Doctor widget DOM assertions
        assertEquals(Boolean.TRUE, res.get("doctorModalOpened"), "Clicking #rtp-doctor-fab opened #rtp-doctor-modal: " + json);
        String docOut = String.valueOf(res.get("doctorOutputText"));
        assertTrue(docOut.contains("Automatically redacted 1 secret"), "Doctor DOM shows secret redaction warning: " + docOut);
        assertTrue(docOut.contains("YAML Schema & Range Issues") && docOut.contains("vert.maxSlope") && docOut.contains("below minimum"),
                "Doctor DOM shows YAML @range violation: " + docOut);
        assertTrue(docOut.contains("ACTADDON_NOT_REGISTERED") && docOut.contains("CLAIM_PLUGIN_ABSENT"), "Doctor DOM shows BIT findings: " + docOut);
        assertTrue(docOut.contains("JumpAdjustor"), "Doctor DOM shows Java AST class hit: " + docOut);
        String copied = String.valueOf(res.get("doctorCopiedSummary"));
        assertTrue(copied.contains("***REDACTED***") && !copied.contains("mySuperSecret123"), "Doctor copy button redacted secret: " + copied);

        // Staging diff & Hot-Apply assertions
        assertTrue(String.valueOf(res.get("stagingDiffHtml")).contains("4321"), "DOM staging diff shows modified radius: " + json);
        assertEquals(Boolean.TRUE, res.get("applyAck"), "Hot-Apply acknowledged over signed WebSocket: " + json);
        assertFalse(applied.isEmpty(), "Plugin received applied config payload");
    }
}
