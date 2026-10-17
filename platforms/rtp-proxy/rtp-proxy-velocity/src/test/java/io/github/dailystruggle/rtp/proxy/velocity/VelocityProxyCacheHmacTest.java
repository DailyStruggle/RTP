package io.github.dailystruggle.rtp.proxy.velocity;

import io.github.dailystruggle.rtp.common.network.pluginmessage.PluginMessageEnvelope;
import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.transport.codec.BackendHeartbeatCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proxy-side half of the plugin-message HMAC envelope: pushes are verified
 * before caching, snapshot replies are signed so a backend verifier accepts
 * them, and the live table is capped.
 */
@DisplayName("REQ-RTP-PROXY-007 / REQ-RTP-S-004: proxy-cache companion HMAC + bounds")
class VelocityProxyCacheHmacTest {

    private static HmacVerifier verifier(byte fill) {
        byte[] s = new byte[32];
        Arrays.fill(s, fill);
        return HmacVerifier.forTesting(s, 1, 1);
    }

    private static BackendHeartbeat hb(String id) {
        return new BackendHeartbeat(id, 1, PluginState.READY, true, 1000L,
                0.0, 0, 0, 0L, 0L, 0, List.of("default", "nether"), List.of());
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: signed push is cached; unsigned / tampered / wrong-key pushes are rejected")
    void pushVerified() {
        HmacVerifier v = verifier((byte) 3);
        VelocityProxyAvailabilityCache cache = new VelocityProxyAvailabilityCache(Map.of(), 5000L, () -> 1000L);

        assertNull(cache.onPushPayload(PluginMessageEnvelope.seal(hb("good"), v), v));
        assertEquals(PluginMessageEnvelope.Rejection.UNSIGNED, cache.onPushPayload(
                BackendHeartbeatCodec.encode(hb("legacy")).getBytes(StandardCharsets.UTF_8), v));
        assertEquals(PluginMessageEnvelope.Rejection.UNSIGNED,
                cache.onPushPayload(PluginMessageEnvelope.seal(hb("nosig"), null), v));
        assertEquals(PluginMessageEnvelope.Rejection.BAD_SIGNATURE,
                cache.onPushPayload(PluginMessageEnvelope.seal(hb("other"), verifier((byte) 4)), v));
        String tampered = new String(PluginMessageEnvelope.seal(hb("tamper"), v), StandardCharsets.UTF_8)
                .replace("serverId=tamper", "serverId=victim");
        assertEquals(PluginMessageEnvelope.Rejection.BAD_SIGNATURE,
                cache.onPushPayload(tampered.getBytes(StandardCharsets.UTF_8), v));

        List<BackendHeartbeat> snap = cache.snapshot();
        assertEquals(1, snap.size());
        assertEquals("good", snap.get(0).serverId());
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: signed snapshot rows (live + synthetic) verify on the backend; another key does not")
    void snapshotRowsSigned() {
        HmacVerifier v = verifier((byte) 3);
        VelocityProxyAvailabilityCache cache = new VelocityProxyAvailabilityCache(
                Map.of("configured", List.of("arena")), 5000L, () -> 1000L);
        cache.onPush(hb("live"));

        List<byte[]> rows = cache.snapshotPayloads(v);
        assertEquals(2, rows.size());
        for (byte[] row : rows) {
            PluginMessageEnvelope.Result r = PluginMessageEnvelope.open(row, v);
            assertTrue(r.accepted(), "backend verifier must accept proxy-signed row: " + r.rejection());
            assertEquals(PluginMessageEnvelope.Rejection.BAD_SIGNATURE,
                    PluginMessageEnvelope.open(row, verifier((byte) 5)).rejection());
        }
        // Unsigned replies are rejected by a backend that holds a secret (fail closed).
        for (byte[] row : cache.snapshotPayloads()) {
            assertEquals(PluginMessageEnvelope.Rejection.UNSIGNED, PluginMessageEnvelope.open(row, v).rejection());
        }
    }

    @Test
    @DisplayName("REQ-RTP-S-004: live table caps new server ids and evicts stale rows first")
    void liveCapEnforced() {
        AtomicLong now = new AtomicLong(0L);
        VelocityProxyAvailabilityCache cache = new VelocityProxyAvailabilityCache(Map.of(), 1000L, now::get);
        for (int i = 0; i < VelocityProxyAvailabilityCache.MAX_LIVE_SERVERS; i++) {
            assertTrue(cache.onPush(hb("s" + i)));
        }
        assertFalse(cache.onPush(hb("overflow")), "new id beyond cap refused");
        assertEquals(PluginMessageEnvelope.Rejection.LIMITS,
                cache.onPushPayload(PluginMessageEnvelope.seal(hb("overflow2"), null), null));
        assertTrue(cache.onPush(hb("s0")), "known id still refreshes at cap");
        assertEquals(VelocityProxyAvailabilityCache.MAX_LIVE_SERVERS, cache.liveCount());

        now.set(1500L);
        assertTrue(cache.onPush(hb("late")), "stale rows evicted to admit a newcomer");
        assertEquals(1, cache.liveCount());
    }

    @Test
    @DisplayName("REQ-RTP-S-004: oversized pushed payload is rejected before decoding")
    void oversizedPushRejected() {
        VelocityProxyAvailabilityCache cache = new VelocityProxyAvailabilityCache(Map.of(), 5000L, () -> 0L);
        byte[] huge = new byte[PluginMessageEnvelope.MAX_PAYLOAD_BYTES + 1];
        Arrays.fill(huge, (byte) 'z');
        assertEquals(PluginMessageEnvelope.Rejection.OVERSIZED, cache.onPushPayload(huge, null));
        assertTrue(cache.snapshot().isEmpty());
    }

    @Test
    @DisplayName("RTP-18: replayed and stale pushes to velocity cache are rejected")
    void replayAndStalePushRejected() {
        HmacVerifier v = verifier((byte) 3);
        AtomicLong now = new AtomicLong(10000L);
        VelocityProxyAvailabilityCache cache = new VelocityProxyAvailabilityCache(Map.of(), 5000L, now::get);

        // 1. Initial push at T=10000, seq=1
        byte[] p1 = PluginMessageEnvelope.seal(hb("srv-1"), v, 10000L, 1L);
        assertNull(cache.onPushPayload(p1, v));
        assertEquals(1, cache.liveCount());

        // 2. Replayed push (identical payload) -> rejected with REPLAY
        assertEquals(PluginMessageEnvelope.Rejection.REPLAY, cache.onPushPayload(p1, v));

        // 3. Stale push (|10000 - 0| = 10000 = boundary, 0L is delta 10000 <= 10000, let's use -1000L delta 11000)
        byte[] pStale = PluginMessageEnvelope.seal(hb("srv-stale"), v, -1000L, 1L);
        assertEquals(PluginMessageEnvelope.Rejection.STALE, cache.onPushPayload(pStale, v));

        // 4. Futuristic push (|10000 - 30000| = 20000 > 10000) -> rejected with STALE
        byte[] pFuture = PluginMessageEnvelope.seal(hb("srv-future"), v, 30000L, 1L);
        assertEquals(PluginMessageEnvelope.Rejection.STALE, cache.onPushPayload(pFuture, v));

        // 5. Monotonic progression: seq=2 at T=11000 -> accepted
        now.set(11000L);
        byte[] p2 = PluginMessageEnvelope.seal(hb("srv-1"), v, 11000L, 2L);
        assertNull(cache.onPushPayload(p2, v));

        // 6. Non-monotonic push: seq=1 at T=11000 -> rejected with REPLAY
        byte[] pOldSeq = PluginMessageEnvelope.seal(hb("srv-1"), v, 11000L, 1L);
        assertEquals(PluginMessageEnvelope.Rejection.REPLAY, cache.onPushPayload(pOldSeq, v));

        // 7. Reboot progression: seq resets to 1, but sentAtMs advances to T=12000 -> accepted
        now.set(12000L);
        byte[] pReboot = PluginMessageEnvelope.seal(hb("srv-1"), v, 12000L, 1L);
        assertNull(cache.onPushPayload(pReboot, v));
    }
}
