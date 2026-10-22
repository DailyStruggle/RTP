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
}
