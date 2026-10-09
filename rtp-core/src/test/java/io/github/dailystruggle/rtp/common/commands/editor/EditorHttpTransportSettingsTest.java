package io.github.dailystruggle.rtp.common.commands.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code advanced/network.yml editor} block: blank or missing values use the built-in bytebin and
 * relay, valid http(s) URLs replace them, anything else is logged and falls back (S-004).
 */
@DisplayName("REQ-RTP-S-004 editor bytebin / relay URL settings")
class EditorHttpTransportSettingsTest {

    private static final String[] DEFAULTS = {EditorHttpTransport.DEFAULT_BYTEBIN_URL, EditorHttpTransport.DEFAULT_RELAY_URL};

    private static Map<String, Object> block(Object bytebin, Object relay) {
        Map<String, Object> m = new HashMap<>();
        m.put("bytebinUrl", bytebin);
        m.put("relayUrl", relay);
        return m;
    }

    @Test
    @DisplayName("no section, blank values: built-in defaults (a resolving relay, not bytesocks.lucko.me)")
    void defaults() {
        assertArrayEquals(DEFAULTS, EditorHttpTransport.settingsUrls(null));
        assertArrayEquals(DEFAULTS, EditorHttpTransport.settingsUrls(block("", "  ")));
        assertArrayEquals(DEFAULTS, EditorHttpTransport.settingsUrls(block(null, null)));
        assertEquals("https://usersockets.luckperms.net", EditorHttpTransport.DEFAULT_RELAY_URL);
    }

    @Test
    @DisplayName("configured URLs replace the defaults, trailing slashes trimmed")
    void configured() {
        assertArrayEquals(new String[]{"https://bin.example.org", "http://127.0.0.1:3000"},
                EditorHttpTransport.settingsUrls(block("https://bin.example.org/", " http://127.0.0.1:3000// ")));
        EditorHttpTransport t = EditorHttpTransport.fromSettings(block(null, "https://sockets.example.org"));
        assertEquals(EditorHttpTransport.DEFAULT_BYTEBIN_URL, t.getBytebinUrl());
        assertEquals("https://sockets.example.org", t.getRelayUrl());
    }

    @Test
    @DisplayName("non-http(s) or malformed values fall back to the default instead of failing the session")
    void invalidFallsBack() {
        assertArrayEquals(DEFAULTS, EditorHttpTransport.settingsUrls(block("ftp://bin.example.org", "wss://x y")));
        assertArrayEquals(DEFAULTS, EditorHttpTransport.settingsUrls(block("bytebin.lucko.me", 42)));
    }
}
