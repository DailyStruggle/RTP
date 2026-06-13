package io.github.dailystruggle.rtp.proxy.common.transport.codec;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip guard for the shared {@link BackendHeartbeatCodec} extracted from
 * the Redis/SQL transports. Pins encode -> decode identity across the 14 wire
 * fields and the malformed-payload contract.
 */
class BackendHeartbeatCodecRoundTripTest {

    private static BackendHeartbeat sample() {
        return new BackendHeartbeat(
                "backend-a", 3, PluginState.READY, true,
                1_700_000_000_000L, 12.5, 7, 100,
                512L * 1024 * 1024, 2048L * 1024 * 1024, 3,
                List.of("regionA", "regionB"), List.of("world", "world_nether"),
                false);
    }

    @Test
    @DisplayName("encode then decode preserves all wire fields")
    void roundTripIdentity() {
        BackendHeartbeat in = sample();
        BackendHeartbeat out = BackendHeartbeatCodec.decode(BackendHeartbeatCodec.encode(in));
        assertEquals(in.serverId(), out.serverId());
        assertEquals(in.schemaVersion(), out.schemaVersion());
        assertEquals(in.pluginState(), out.pluginState());
        assertEquals(in.acceptingRequests(), out.acceptingRequests());
        assertEquals(in.lastSeenEpochMs(), out.lastSeenEpochMs());
        assertEquals(in.mspt(), out.mspt());
        assertEquals(in.queueDepth(), out.queueDepth());
        assertEquals(in.softCap(), out.softCap());
        assertEquals(in.heapUsedBytes(), out.heapUsedBytes());
        assertEquals(in.heapMaxBytes(), out.heapMaxBytes());
        assertEquals(in.playerCount(), out.playerCount());
        assertEquals(in.regionsAvailable(), out.regionsAvailable());
        assertEquals(in.worldsLoaded(), out.worldsLoaded());
        assertEquals(in.killSwitch(), out.killSwitch());
    }

    @Test
    @DisplayName("empty optional list fields round-trip to empty lists")
    void emptyListsRoundTrip() {
        BackendHeartbeat in = new BackendHeartbeat(
                "b", 1, PluginState.STARTING, false,
                0L, 0.0, 0, 0, 0L, 0L, 0,
                List.of(), List.of(), true);
        BackendHeartbeat out = BackendHeartbeatCodec.decode(BackendHeartbeatCodec.encode(in));
        assertTrue(out.regionsAvailable().isEmpty());
        assertTrue(out.worldsLoaded().isEmpty());
        assertTrue(out.killSwitch());
    }

    @Test
    @DisplayName("encode is canonical regardless of trailing hmac line on decode")
    void decodeIgnoresHmacLine() {
        String encoded = BackendHeartbeatCodec.encode(sample()) + "\nhmac=deadbeef";
        BackendHeartbeat out = BackendHeartbeatCodec.decode(encoded);
        assertEquals("backend-a", out.serverId());
    }

    @Test
    @DisplayName("Cross-server fields (regions Set, kept counts, region kept-count map) survive the round-trip")
    void l6FieldsRoundTrip() {
        BackendHeartbeat in = new BackendHeartbeat(
                "backend-a", 3, PluginState.READY, true,
                1_700_000_000_000L, 12.5, 7, 100,
                512L * 1024 * 1024, 2048L * 1024 * 1024, 3,
                List.of("regionA", "regionB"), List.of("world", "world_nether"),
                false,
                42, 5,
                Set.of("regionA", "regionB", "regionC"),
                Map.of("regionA", 10, "regionB", 0, "regionC", 7));
        BackendHeartbeat out = BackendHeartbeatCodec.decode(BackendHeartbeatCodec.encode(in));
        assertEquals(42, out.keptCount());
        assertEquals(5, out.networkReservedCount());
        assertEquals(in.regions(), out.regions());
        assertEquals(in.regionKeptCounts(), out.regionKeptCounts());
    }

    @Test
    @DisplayName("a legacy row (no cross-server fields) decodes to zero/empty defaults")
    void preL6RowDecodesToDefaults() {
        // Encode a legacy heartbeat that predates the cross-server field set; the canonical
        // prefix is unchanged so an old row simply lacks the trailing lines.
        BackendHeartbeat legacyHb = sample();
        String enc = BackendHeartbeatCodec.encode(legacyHb);
        // Strip the trailing cross-server lines to simulate an older producer.
        String legacyEnc = enc.substring(0, enc.indexOf("\nkeptCount="));
        BackendHeartbeat out = BackendHeartbeatCodec.decode(legacyEnc);
        assertEquals(0, out.keptCount());
        assertEquals(0, out.networkReservedCount());
        assertTrue(out.regions().isEmpty());
        assertTrue(out.regionKeptCounts().isEmpty());
    }

    @Test
    @DisplayName("null/empty payloads decode to null")
    void nullAndEmptyDecodeToNull() {
        assertNull(BackendHeartbeatCodec.decode(null));
        assertNull(BackendHeartbeatCodec.decode(""));
    }
}
