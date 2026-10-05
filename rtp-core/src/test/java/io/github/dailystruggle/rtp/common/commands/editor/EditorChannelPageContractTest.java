package io.github.dailystruggle.rtp.common.commands.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
