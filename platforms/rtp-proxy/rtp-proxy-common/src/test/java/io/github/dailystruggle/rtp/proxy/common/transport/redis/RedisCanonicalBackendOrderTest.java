package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the 2026-05-21 HMAC envelope fix.
 *
 * <p>The original encode/decode pair signed over a {@code LinkedHashMap}'s
 * insertion order on publish, but reconstructed the canonical bytes from
 * whatever order {@code j.hgetAll(key)} happened to return on read - in
 * Jedis 5.x that's a plain {@code HashMap} with arbitrary iteration order.
 * Every snapshot read therefore failed HMAC verification, dropping every
 * backend heartbeat from {@code rtp:net:backend:*} reads (REQ-RTP-S-004).
 * The pubsub path masked it because pubsub ships the literal encoded string,
 * which {@code parseFlat} re-parses into a {@code LinkedHashMap} preserving
 * the publisher's natural order.
 *
 * <p>This test pins the contract: {@link RedisNetworkStateBinding#canonicalBackend(Map)}
 * produces the same byte sequence regardless of the source map's iteration
 * order, so a freshly-signed payload verifies whether the bytes came from
 * the publisher's encode path or from a Jedis HGETALL HashMap.
 */
class RedisCanonicalBackendOrderTest {

    private static final byte[] SECRET = new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
    };

    private static Map<String, String> sampleFields() {
        Map<String, String> m = new HashMap<>();
        m.put("serverId", "backend-a");
        m.put("schemaVersion", "1");
        m.put("pluginState", "READY");
        m.put("acceptingRequests", "true");
        m.put("lastSeenEpochMs", "1716290000000");
        m.put("mspt", "12.5");
        m.put("queueDepth", "3");
        m.put("softCap", "20");
        m.put("heapUsedBytes", "104857600");
        m.put("heapMaxBytes", "536870912");
        m.put("playerCount", "5");
        m.put("regionsAvailable", "world,nether");
        m.put("worldsLoaded", "world,world_nether,world_the_end");
        m.put("killSwitch", "false");
        return m;
    }

    @Test
    void canonical_is_deterministic_across_insertion_orders() {
        // Publisher path: LinkedHashMap built in canonical insertion order.
        Map<String, String> publisherOrder = new LinkedHashMap<>();
        publisherOrder.put("serverId", "backend-a");
        publisherOrder.put("schemaVersion", "1");
        publisherOrder.put("pluginState", "READY");
        publisherOrder.put("acceptingRequests", "true");
        publisherOrder.put("lastSeenEpochMs", "1716290000000");
        publisherOrder.put("mspt", "12.5");
        publisherOrder.put("queueDepth", "3");
        publisherOrder.put("softCap", "20");
        publisherOrder.put("heapUsedBytes", "104857600");
        publisherOrder.put("heapMaxBytes", "536870912");
        publisherOrder.put("playerCount", "5");
        publisherOrder.put("regionsAvailable", "world,nether");
        publisherOrder.put("worldsLoaded", "world,world_nether,world_the_end");
        publisherOrder.put("killSwitch", "false");

        // Reader path: simulate Jedis HGETALL returning a plain HashMap with
        // arbitrary iteration order. HashMap order is not literally arbitrary
        // (it's hash-derived), but it's intentionally NOT the publisher's
        // insertion order, which is exactly the failure case in production.
        Map<String, String> readerOrder = sampleFields();
        assertNotEquals(publisherOrder.toString(), readerOrder.toString(),
                "HashMap iteration must differ from LinkedHashMap insertion order "
                        + "for this test to exercise the bug; if this fails on a "
                        + "future JDK, rebuild readerOrder with a different key shuffle.");

        String fromPublisher = RedisNetworkStateBinding.canonicalBackend(publisherOrder);
        String fromReader = RedisNetworkStateBinding.canonicalBackend(readerOrder);
        assertEquals(fromPublisher, fromReader,
                "canonicalBackend must produce identical bytes regardless of input map iteration order");
    }

    @Test
    void canonical_starts_with_serverId_and_ends_with_regionKeptCounts() {
        // Pins the field order so downstream wire-format changes are caught.
        // The cross-server fields (keptCount, networkReservedCount, regions,
        // regionKeptCounts) are appended after killSwitch, so the canonical now
        // ends with regionKeptCounts and carries 18 fields.
        String canonical = RedisNetworkStateBinding.canonicalBackend(sampleFields());
        assertTrue(canonical.startsWith("serverId=backend-a\n"),
                "first line must be serverId; got: " + canonical.substring(0, Math.min(40, canonical.length())));
        assertTrue(canonical.endsWith("\nregionKeptCounts="),
                "last line must be regionKeptCounts; got tail: "
                        + canonical.substring(Math.max(0, canonical.length() - 40)));
        // 18 fields = 17 newlines.
        long newlines = canonical.chars().filter(c -> c == '\n').count();
        assertEquals(17, newlines, "canonical must have exactly 17 newlines (18 fields)");
    }

    @Test
    void canonical_uses_empty_string_for_missing_fields() {
        Map<String, String> partial = new HashMap<>();
        partial.put("serverId", "backend-a");
        // schemaVersion, pluginState, ... all missing
        String canonical = RedisNetworkStateBinding.canonicalBackend(partial);
        assertTrue(canonical.startsWith("serverId=backend-a\nschemaVersion=\n"),
                "missing fields must serialize as empty strings to keep canonical length stable");
        // Still 18 fields total -> 17 newlines.
        long newlines = canonical.chars().filter(c -> c == '\n').count();
        assertEquals(17, newlines);
    }

    @Test
    void hmac_signed_by_publisher_verifies_against_reader_hashmap() {
        // End-to-end check that the canonical-helper fix actually resolves
        // the HMAC mismatch the bug surfaced in production.
        HmacVerifier verifier = HmacVerifier.forTesting(SECRET, 1, 1);
        Map<String, String> publisherOrder = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : sampleFields().entrySet()) {
            publisherOrder.put(e.getKey(), e.getValue());
        }
        // Ensure publisherOrder is the canonical sequence (not whatever
        // sampleFields()'s HashMap returned).
        publisherOrder.clear();
        publisherOrder.put("serverId", "backend-a");
        publisherOrder.put("schemaVersion", "1");
        publisherOrder.put("pluginState", "READY");
        publisherOrder.put("acceptingRequests", "true");
        publisherOrder.put("lastSeenEpochMs", "1716290000000");
        publisherOrder.put("mspt", "12.5");
        publisherOrder.put("queueDepth", "3");
        publisherOrder.put("softCap", "20");
        publisherOrder.put("heapUsedBytes", "104857600");
        publisherOrder.put("heapMaxBytes", "536870912");
        publisherOrder.put("playerCount", "5");
        publisherOrder.put("regionsAvailable", "world,nether");
        publisherOrder.put("worldsLoaded", "world,world_nether,world_the_end");
        publisherOrder.put("killSwitch", "false");

        String signed = verifier.sign(1, RedisNetworkStateBinding.canonicalBackend(publisherOrder));

        // Reader sees an arbitrary-order HashMap from Jedis HGETALL.
        Map<String, String> readerOrder = sampleFields(); // HashMap, arbitrary iteration
        assertTrue(verifier.verify(1, RedisNetworkStateBinding.canonicalBackend(readerOrder), signed),
                "publisher-signed HMAC must verify against canonicalBackend of the reader's HashMap");
    }
}
