package io.github.dailystruggle.rtp.neoforge.server;

import io.github.dailystruggle.rtp.common.mock.MockPlayerRegistry;
import io.github.dailystruggle.rtp.common.mock.RuntimeMockRTPPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link NeoForgeServerAccessor} resolves registered BIT mocks before native lookup (ADR-121). */
class NeoForgeServerAccessorMockLookupTest {

    private static final String MOCK_NAME = "rtp-bit-0a1b2c3d";

    @BeforeEach
    void setUp() {
        MockPlayerRegistry.clear();
    }

    @AfterEach
    void tearDown() {
        MockPlayerRegistry.clear();
    }

    @Test
    @DisplayName("ADR-121: registered BIT mock resolves before native lookup and is absent from online names")
    void registeredMockResolvesBeforeNativeLookup() {
        NeoForgeServerAccessor accessor = new NeoForgeServerAccessor();
        UUID mockId = UUID.randomUUID();
        RuntimeMockRTPPlayer mock = new RuntimeMockRTPPlayer(mockId, MOCK_NAME, null);
        MockPlayerRegistry.register(mock);

        assertSame(mock, accessor.getPlayer(mockId));
        assertSame(mock, accessor.getPlayer("RTP-BIT-0A1B2C3D"));
        assertSame(mock, accessor.getSender(mockId));
        assertFalse(accessor.getOnlinePlayerNames().contains(MOCK_NAME));
        assertTrue(accessor.getOnlinePlayers().stream().noneMatch(p -> mockId.equals(p.uuid())));
    }

    @Test
    @DisplayName("ADR-121: after unregister, lookups fall back to native behaviour")
    void unregisteredMockFallsBackToNative() {
        NeoForgeServerAccessor accessor = new NeoForgeServerAccessor();
        UUID mockId = UUID.randomUUID();
        MockPlayerRegistry.register(new RuntimeMockRTPPlayer(mockId, MOCK_NAME, null));
        MockPlayerRegistry.unregister(mockId);

        // No server bound: native lookup yields null.
        assertNull(accessor.getPlayer(mockId));
        assertNull(accessor.getPlayer(MOCK_NAME));
        assertNull(accessor.getSender(mockId));
    }

    @Test
    @DisplayName("RTP-96: NeoForgeServerAccessor.log strips ANSI and section color codes from Log4j output")
    void logStripsAnsiEscapeSequences() throws Exception {
        NeoForgeServerAccessor accessor = new NeoForgeServerAccessor();
        String[] samples = new String[] {
                "<rainbow>[RTP]</rainbow> &a[RTP bit] csv,row,#ff8800value",
                "&eWarn message with \u00A7ccolor",
                "&cSevere #112233hex <red>tag</red>"
        };
        for (String sample : samples) {
            String stripped = io.github.dailystruggle.rtp.neoforge.tools.NeoForgeAnsiText.stripColor(sample);
            assertFalse(stripped.contains("\u001b["),
                    "RTP-96: log output must not contain ANSI escape sequences, got: " + stripped);
            assertFalse(stripped.contains("\u00A7"),
                    "RTP-96: log output must not contain section color codes, got: " + stripped);

            String formattedNoColor = accessor.formatNoColor(null, sample);
            assertFalse(formattedNoColor.contains("\u001b["),
                    "RTP-96: formatNoColor must not contain ANSI escape sequences, got: " + formattedNoColor);
            assertFalse(formattedNoColor.contains("\u00A7"),
                    "RTP-96: formatNoColor must not contain section color codes, got: " + formattedNoColor);
        }

        // Verify compiled NeoForgeServerAccessor and NeoForgeConsoleSender bytecode routes
        // log output through stripColor rather than toAnsiString (RTP-96).
        String resource = NeoForgeServerAccessor.class.getName().replace('.', '/') + ".class";
        java.net.URL url = NeoForgeServerAccessor.class.getClassLoader().getResource(resource);
        if (url != null && "file".equals(url.getProtocol())) {
            java.nio.file.Path classFile = java.nio.file.Paths.get(url.toURI());
            String classBytes = new String(
                    java.nio.file.Files.readAllBytes(classFile),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(classBytes.contains("stripColor"),
                    "NeoForgeServerAccessor must strip color codes before logging");
            assertFalse(classBytes.contains("toAnsiString"),
                    "RTP-96: NeoForgeServerAccessor must not convert log lines to raw ANSI escapes");
        }
    }
}
