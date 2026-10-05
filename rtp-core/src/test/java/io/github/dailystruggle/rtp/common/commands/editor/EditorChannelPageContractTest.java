package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.commands.editor.channel.BytesocksTransport;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-106 §5 static contract of the page's {@code EditorChannelClient}: one socket, signed both
 * ways with the algorithm Java uses, a non-extractable browser key, and no unsigned or token-based
 * paths left for focus, walk-path or Hot-Apply.
 */
@DisplayName("ADR-106 §5: page EditorChannelClient contract")
class EditorChannelPageContractTest {

    private static String page() throws IOException {
        Path page = Paths.get("docs", "editor", "index.html");
        if (!Files.exists(page)) page = Paths.get("..", "docs", "editor", "index.html");
        return Files.readString(page, StandardCharsets.UTF_8);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    @Test
    @DisplayName("SHA256withRSA (RSASSA-PKCS1-v1_5) both ways; the private key is generated non-extractable and kept in IndexedDB")
    void cryptoContract() throws IOException {
        String html = page();
        assertTrue(html.contains("const RSA = 'RSASSA-PKCS1-v1_5';"), "same signature scheme as Java SHA256withRSA");
        assertTrue(html.contains("hash: 'SHA-256' }, false, ['sign', 'verify'])"), "browser key generated non-extractable");
        assertTrue(html.contains("indexedDB.open('rtp-editor-channel', 1)"), "key persisted in IndexedDB");
        assertTrue(html.contains("crypto.subtle.importKey('spki', spki, { name: RSA, hash: 'SHA-256' }, false, ['verify'])"),
                "plugin key imported verify-only from the snapshot");
        assertTrue(html.contains("crypto.subtle.verify(RSA, st.pluginKey"), "plugin frames verified");
        assertTrue(html.contains("crypto.subtle.sign(RSA, st.keys.privateKey"), "page frames signed");
    }

    @Test
    @DisplayName("A plugin frame is verified before its message is parsed, and checked for channel, sender, seq and address")
    void verifyBeforeUse() throws IOException {
        String html = page();
        int verify = html.indexOf("crypto.subtle.verify(RSA, st.pluginKey");
        int parse = html.indexOf("msg = JSON.parse(env.msg)");
        assertTrue(verify > 0 && parse > verify, "signature checked before the inner message is read");
        assertTrue(html.contains("msg.channel !== st.cfg.id || msg.from !== st.pluginFp"), "pinned to this channel and plugin key");
        assertTrue(html.contains("msg.seq <= st.pluginSeq"), "replays dropped");
        assertTrue(html.contains("msg.to !== undefined && msg.to !== st.myFp"), "replies for other browsers ignored");
        assertTrue(html.contains("CHANNEL_MAX_FRAME = 32 * 1024"), "frame cap matches the plugin's");
    }

    @Test
    @DisplayName("One socket: the channel client's; focus, walkpath and Hot-Apply go through it without tokens")
    void callersMovedOntoChannel() throws IOException {
        String html = page();
        assertEquals(1, count(html, "new WebSocket("), "only EditorChannelClient opens a socket");
        assertTrue(html.contains("EditorChannelClient.send('focus'"), "focus over the channel");
        assertTrue(html.contains("EditorChannelClient.send('walkpath'"), "walk-path preview over the channel");
        assertTrue(html.contains("EditorChannelClient.send('apply', body)"), "Hot-Apply over the channel");
        assertFalse(html.contains("action: 'apply'"), "no unsigned token-form apply");
        assertFalse(html.contains("searchParams.get('token')"), "the loopback token never travels in a message");
        assertFalse(html.contains("wsLoopbackUrl") || html.contains("wsClient"), "legacy socket state removed");
        assertTrue(html.contains("payload.channel && typeof payload.channel === 'object'"), "started from the snapshot's channel block");
    }

    @Test
    @DisplayName("hazard-delta versions are base/version: from/to are the channel's sender and recipient headers")
    void hazardDeltaFieldsAreNotHeaders() throws IOException {
        String html = page();
        int start = html.indexOf("async function ingestHazardDelta(msg)");
        assertTrue(start > 0, "hazard-delta handler present");
        String body = html.substring(start, html.indexOf("\n}\n", start));
        assertTrue(body.contains("msg.version") && body.contains("msg.base"), "versions read from base/version");
        assertFalse(body.contains("msg.to") || body.contains("msg.from"), "header fields never read as versions");
    }

    @Test
    @DisplayName("ADR-107 §5.3 bundle items of any well-formed push type are dispatched; control frames never ride in a bundle")
    void bundleUnpacking() throws IOException {
        String html = page();
        Matcher m = Pattern.compile("const CHANNEL_CONTROL = new Set\\(\\[([^\\]]*)\\]\\);").matcher(html);
        assertTrue(m.find(), "control type list declared");
        Set<String> control = new TreeSet<>();
        Arrays.stream(m.group(1).split(",")).map(s -> s.trim().replace("'", "")).forEach(control::add);
        assertTrue(control.containsAll(Set.of("hello-reply", "bye", "pong", EditorChannel.BUNDLE_TYPE)), control.toString());
        for (String t : EditorChannel.BUNDLED) assertFalse(control.contains(t), t + " is a bundled push, not control");
        assertTrue(html.contains("msg.type === '" + EditorChannel.BUNDLE_TYPE + "'"), "bundle handled in receive");
        int bundle = html.indexOf("msg.type === 'bundle'");
        int verify = html.indexOf("crypto.subtle.verify(RSA, st.pluginKey");
        assertTrue(bundle > verify, "items are dispatched only after the bundle's signature is checked");
        assertTrue(html.contains("|| CHANNEL_CONTROL.has(item.type)) { drop('bad bundle item'); continue; }"),
                "no hello-reply / bye / nested bundle inside a bundle");
        assertTrue(html.contains("!CHANNEL_TYPE_RE.test(item.type)"), "malformed item types dropped");

        Matcher re = Pattern.compile("const CHANNEL_TYPE_RE = /(.*)/;").matcher(html);
        assertTrue(re.find(), "type grammar declared");
        Pattern type = Pattern.compile(re.group(1));
        for (String ok : List.of("feed", "hazard-delta", "leafrtp-action.state", "demo.zones_2")) assertTrue(type.matcher(ok).matches(), ok);
        for (String bad : List.of("Feed", "a.b.c", ".x", "x.", "x y")) assertFalse(type.matcher(bad).matches(), bad);
        assertTrue(html.contains("if (!fn) { st.ignored++; return; }"), "a type without a handler is ignored, not dropped");
        assertTrue(html.contains("if (!fn && dot > 0) fn = namespaces[m.type.slice(0, dot)];"), "extension types reach their namespace");
        assertTrue(html.contains("if (type.indexOf('.') > 0 && protocolOf(st.protocol) < 2) return false;"),
                "no extension types to a protocol 1 plugin");
    }

    @Test
    @DisplayName("On a wss:// relay the page and plugin frame shares together stay within bytesocks' 30 frames per IP per 2 minutes")
    void relayFrameShares() throws IOException {
        String html = page();
        Matcher m = Pattern.compile("const CHANNEL_PAGE_FRAMES = (\\d+);").matcher(html);
        assertTrue(m.find(), "page frame share declared");
        int pageShare = Integer.parseInt(m.group(1));
        assertTrue(pageShare + BytesocksTransport.FRAMES_PER_WINDOW <= 30,
                "page " + pageShare + " + plugin " + BytesocksTransport.FRAMES_PER_WINDOW + " frames");
        assertTrue(html.contains("const CHANNEL_FRAME_WINDOW_MS = 120000;"), "same 2-minute window as the plugin");
        assertTrue(html.contains("st.cfg.relay.startsWith('wss://')"), "only relays are paced; loopback stays immediate");
        int enqueue = html.indexOf("function enqueue(obj)");
        assertTrue(enqueue > 0 && html.indexOf("st.sentAt.push(Date.now());", enqueue) - enqueue < 80,
                "every outbound frame (hello, ping, queued sends) is counted");
        assertTrue(html.contains("if (st.sentAt.length >= CHANNEL_PAGE_FRAMES) return;"), "a ping never overruns the share");
        assertTrue(html.contains("const queued = st.outQueue.find(q => q.type === 'focus');"), "focus updates coalesce");
    }

    @Test
    @DisplayName("Hot-Apply sends every staged file that differs from the server copy and advances that baseline on success")
    void hotApplySendsAllChangedFiles() throws IOException {
        String html = page();
        int start = html.indexOf("function hotApplyWebSocket()");
        String body = html.substring(start, html.indexOf("\n}\n", start));
        assertTrue(body.contains("const files = hotApplyChangedFiles();"), "files gathered from every staged config");
        assertFalse(body.contains("[regionTargetFile]"), "not just the active region's file");
        int changed = html.indexOf("function hotApplyChangedFiles()");
        String diff = html.substring(changed, html.indexOf("\n}\n", changed));
        assertTrue(diff.contains("appliedConfigs[f] !== undefined ? appliedConfigs[f] : shippedConfigs[f]"),
                "compared with the last applied text, else the session text");
        assertTrue(html.contains("if (msg.success && sent) Object.assign(appliedConfigs, sent);"), "baseline advances on apply_ack");
    }

    @Test
    @DisplayName("Relay addresses are limited to the token-gated loopback socket or a wss:// relay")
    void relayPattern() throws IOException {
        String html = page();
        Matcher m = Pattern.compile("const CHANNEL_RELAY_RE = /(.*)/;").matcher(html);
        assertTrue(m.find(), "relay pattern declared");
        Pattern relay = Pattern.compile(m.group(1).replace("\\/", "/"));
        assertTrue(relay.matcher("ws://127.0.0.1:4711/rtp-editor-ws?token=0123456789abcdef0123456789abcdef").matches());
        assertTrue(relay.matcher("wss://bytesocks.lucko.me/abc123").matches());
        assertFalse(relay.matcher("ws://example.com:4711/rtp-editor-ws?token=0123456789abcdef0123456789abcdef").matches(),
                "plain ws only to loopback");
        assertFalse(relay.matcher("javascript:alert(1)").matches());
    }

    @Test
    @DisplayName("A hosted page applies channel feed heads and marks the link stale only after the plugin's heartbeat plus a bundle gap")
    void channelFeedIngestAndStaleness() throws IOException {
        String html = page();
        assertTrue(html.contains("if (msg.data && typeof msg.data === 'object') ingestChannelFeed(msg.data);"),
                "channel feed heads go through ingestChannelFeed");
        int ingest = html.indexOf("function ingestChannelFeed(feed)");
        String body = html.substring(ingest, html.indexOf("\n}\n", ingest));
        assertTrue(body.contains("else applyFeedStats(feed);"), "without a live directory (hosted) the stats still apply");
        int stats = html.indexOf("function applyFeedStats(feed)");
        String statsBody = html.substring(stats, html.indexOf("\n}\n", stats));
        assertFalse(statsBody.contains("liveFeed.cfg"), "stats need no local live directory");
        assertTrue(statsBody.contains("updateDiagnosticsUI(currentMetrics)"), "telemetry reaches Diagnostics");

        Matcher m = Pattern.compile("const CHANNEL_FEED_STALE_MS = (\\d+);").matcher(html);
        assertTrue(m.find(), "feed-stale threshold declared");
        long stale = Long.parseLong(m.group(1));
        assertTrue(stale > EditorLiveFeed.FEED_HEARTBEAT_MILLIS + EditorChannel.BUNDLE_INTERVAL_MILLIS,
                "an idle plugin's heartbeat (" + EditorLiveFeed.FEED_HEARTBEAT_MILLIS + " ms, bundled within "
                        + EditorChannel.BUNDLE_INTERVAL_MILLIS + " ms) never reads as stale: " + stale);
        assertEquals(1, count(html, "noteFeed();"), "one dispatch path: feed heads arriving alone or bundled both reset the stale clock");
        assertTrue(html.contains("dispatch(item);") && html.contains("dispatch(msg);"), "bundled and single frames share dispatch");
        assertTrue(html.contains("'\u25cf LINK \u00b7 FEED STALE ('"), "stale state shown on the channel badge");
    }

    @Test
    @DisplayName("Status badge exposes connecting / awaiting trust / connected / reconnecting / snapshot only")
    void badgeStates() throws IOException {
        String html = page();
        assertTrue(html.contains("id=\"channel-badge\" data-status=\"off\""), "badge element");
        for (String s : new String[]{"connecting:", "'awaiting-trust':", "connected:", "reconnecting:", "snapshot:"}) {
            assertTrue(html.contains(s), "badge state " + s);
        }
        assertTrue(html.contains("el.textContent = text;"), "badge text set as text, never HTML");
    }
}
