package io.github.dailystruggle.rtp.common.world;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression: totalChunkLoads double-count accounting tests.
 * {@code RTPWorld.getOrLoadChunk} increments {@code totalChunkLoads} at most once per logical
 * attempt (live loads only; cache hits and probe hits do not increment).
 */
class GetOrLoadChunkTotalLoadsAccountingTest {

    /**
     * Fake world where probe ({@code getChunkAt}) does not increment and
     * async live load ({@code getChunkAtAsync}) increments once.
     */
    private static class AccountingFakeWorld extends MockRTPWorld {
        volatile boolean probePopulatesCache = false;
        volatile boolean preCached = false;
        // Tracks how many getCachedChunk calls have happened so we can model
        // "before-probe" vs "after-probe" semantics deterministically.
        int cachedChunkCallCount = 0;

        AccountingFakeWorld() {
            super("accounting_fake");
        }

        @Override
        public CompletableFuture<Long> getChunkAt(int cx, int cz) {
            // Probe entry - explicitly does NOT increment totalChunkLoads.
            // Resolve to the canonical key so the post-probe getCachedChunk lookup
            // can run; the *behavior* of that lookup (cache hit vs miss) is driven
            // by the test hooks below.
            long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
            return CompletableFuture.completedFuture(key);
        }

        @Override
        public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
            // Live-load entry - the only place that increments. Mirrors the
            // post-fix accounting in BukkitRTPWorld#loadChunkFuture / FoliaRTPWorld
            // #loadLiveChunk + #getChunkAtAsync / FabricRTPWorld#getChunkAt's
            // server.submit body.
            totalChunkLoads.incrementAndGet();
            // After a live load completes, the cache will hold an entry - flip the
            // hook so getCachedChunk(key) returns non-null on the post-load lookup
            // inside RTPWorld#getOrLoadChunk.
            probePopulatesCache = true;
            long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
            ChunkSet set = new ChunkSet(this, cx, cz,
                    List.of(CompletableFuture.completedFuture(key)),
                    new CompletableFuture<>());
            return CompletableFuture.completedFuture(set);
        }

        @Override
        public RTPChunk<?> getCachedChunk(long key) {
            cachedChunkCallCount++;
            // Pre-cached scenario: short-circuit on the very first call (the
            // "is this already cached?" check inside getOrLoadChunk).
            if (preCached && cachedChunkCallCount == 1) {
                return super.getCachedChunk(key);
            }
            // Probe-cache hit scenario: the post-probe lookup (call #2 inside
            // getOrLoadChunk) returns non-null only if probePopulatesCache is set.
            if (probePopulatesCache) {
                return super.getCachedChunk(key);
            }
            // Default: cache miss.
            return null;
        }
    }

    @Test
    void preCachedHit_doesNotIncrementTotalChunkLoads() throws Exception {
        AccountingFakeWorld world = new AccountingFakeWorld();
        world.preCached = true;

        long before = world.totalChunkLoads.get();
        RTPChunk<?> chunk = world.getOrLoadChunk(7, 11).get();
        long after = world.totalChunkLoads.get();

        assertNotNull(chunk, "pre-cached chunk should resolve to a non-null RTPChunk");
        assertEquals(before, after,
                "pre-cached path must not increment totalChunkLoads — no live load occurred");
    }

    @Test
    void probeCacheHit_doesNotIncrementTotalChunkLoads() throws Exception {
        AccountingFakeWorld world = new AccountingFakeWorld();
        // Not pre-cached, but probe is configured to populate the cache.
        world.probePopulatesCache = true;

        long before = world.totalChunkLoads.get();
        RTPChunk<?> chunk = world.getOrLoadChunk(7, 11).get();
        long after = world.totalChunkLoads.get();

        assertNotNull(chunk, "probe-cache hit should resolve to a non-null RTPChunk");
        assertEquals(before, after,
                "probe-cache hit must not increment totalChunkLoads — no live load occurred");
    }

    @Test
    void probeFallthroughLiveLoad_incrementsTotalChunkLoadsExactlyOnce() throws Exception {
        AccountingFakeWorld world = new AccountingFakeWorld();
        // Not pre-cached, probe yields no cached view → fall through to live load.
        // probePopulatesCache stays false until getChunkAtAsync flips it on success.

        long before = world.totalChunkLoads.get();
        RTPChunk<?> chunk = world.getOrLoadChunk(7, 11).get();
        long after = world.totalChunkLoads.get();

        assertNotNull(chunk, "live load should resolve to a non-null RTPChunk");
        assertEquals(before + 1, after,
                "exactly one live chunk load occurred → totalChunkLoads must increment by exactly 1");
    }

    @Test
    void liveLoadReturningNullChunkSet_stillCountsAsAttempt() throws Exception {
        // Edge case: if the live load fails (the fallback in getOrLoadChunk receives a
        // null ChunkSet), the increment still happened - counting "attempts" not
        // "successes" matches operator intuition for "lifetime loads we asked the
        // chunk system to perform", and parallels how a failed Bukkit synchronous
        // generate also increments before returning null.
        AccountingFakeWorld world = new AccountingFakeWorld() {
            @Override
            public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
                totalChunkLoads.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        };

        long before = world.totalChunkLoads.get();
        RTPChunk<?> chunk = world.getOrLoadChunk(3, 5).get();
        long after = world.totalChunkLoads.get();

        assertNull(chunk, "null ChunkSet from live load surfaces as null RTPChunk");
        assertEquals(before + 1, after,
                "failed live load still represents one chunk-system request — increment by exactly 1");
    }
}
