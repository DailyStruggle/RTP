package io.github.dailystruggle.rtp.proxy.common.transport.direct;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProxyDirectReplayCacheTest {

    @Test
    @DisplayName("REQ-RTP-PROXY-007: a nonce is accepted once and refused while still retained")
    void repeatedNonceRefused() {
        AtomicLong now = new AtomicLong(1_000L);
        ProxyDirectReplayCache cache = new ProxyDirectReplayCache(now::get);
        assertEquals(ProxyDirectReplayCache.Result.ACCEPTED, cache.checkAndRecord("a"));
        assertEquals(ProxyDirectReplayCache.Result.REPLAYED, cache.checkAndRecord("a"));
        assertEquals(ProxyDirectReplayCache.Result.ACCEPTED, cache.checkAndRecord("b"));

        // Retention covers the whole freshness window on both sides.
        now.addAndGet(2 * ProxyDirectWire.MAX_CLOCK_SKEW_MS - 1);
        assertEquals(ProxyDirectReplayCache.Result.REPLAYED, cache.checkAndRecord("a"));
        assertEquals(2 * ProxyDirectWire.MAX_CLOCK_SKEW_MS, ProxyDirectReplayCache.DEFAULT_TTL_MS);
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: an expired nonce entry is forgotten")
    void expiredEntryForgotten() {
        AtomicLong now = new AtomicLong(0L);
        ProxyDirectReplayCache cache = new ProxyDirectReplayCache(4, 100L, now::get);
        assertEquals(ProxyDirectReplayCache.Result.ACCEPTED, cache.checkAndRecord("a"));
        now.set(100L);
        assertEquals(ProxyDirectReplayCache.Result.ACCEPTED, cache.checkAndRecord("a"));
        assertEquals(1, cache.size());
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: a full cache evicts expired entries, else fails closed")
    void boundedAndFailsClosed() {
        AtomicLong now = new AtomicLong(0L);
        ProxyDirectReplayCache cache = new ProxyDirectReplayCache(3, 100L, now::get);
        assertEquals(ProxyDirectReplayCache.Result.ACCEPTED, cache.checkAndRecord("a"));
        assertEquals(ProxyDirectReplayCache.Result.ACCEPTED, cache.checkAndRecord("b"));
        now.set(50L);
        assertEquals(ProxyDirectReplayCache.Result.ACCEPTED, cache.checkAndRecord("c"));

        // Full and nothing expired: refuse new nonces, still detect live repeats.
        assertEquals(ProxyDirectReplayCache.Result.FULL, cache.checkAndRecord("d"));
        assertEquals(ProxyDirectReplayCache.Result.REPLAYED, cache.checkAndRecord("a"));
        assertEquals(3, cache.size());

        // a and b expire at 100; c (150) stays.
        now.set(100L);
        assertEquals(ProxyDirectReplayCache.Result.ACCEPTED, cache.checkAndRecord("d"));
        assertEquals(2, cache.size());
        assertEquals(ProxyDirectReplayCache.Result.REPLAYED, cache.checkAndRecord("c"));
        assertEquals(ProxyDirectReplayCache.DEFAULT_MAX_ENTRIES, 65_536);
    }

    @Test
    void constructorValidation() {
        assertThrows(IllegalArgumentException.class, () -> new ProxyDirectReplayCache(0, 1L, () -> 0L));
        assertThrows(IllegalArgumentException.class, () -> new ProxyDirectReplayCache(1, 0L, () -> 0L));
        assertThrows(NullPointerException.class, () -> new ProxyDirectReplayCache(null));
    }
}
