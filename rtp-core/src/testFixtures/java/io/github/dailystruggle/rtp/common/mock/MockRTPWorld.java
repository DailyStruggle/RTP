package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;

/**
 * Minimal in-memory implementation of {@link RTPWorld} for use in unit tests.
 *
 * <p>All chunk-loading futures complete immediately and synchronously, removing
 * the need for a real platform (Spigot/Paper/Folia) to resolve them.
 */
public class MockRTPWorld extends RTPWorld<String> {

    private final String name;
    private final UUID id;

    public MockRTPWorld(String name) {
        super(name);
        this.name = name;
        this.id = UUID.nameUUIDFromBytes(name.getBytes());
    }

    public MockRTPWorld() {
        this("mock_world");
    }

    /**
     * Test hook: predicate controlling the return value of {@link #isChunkLoaded(int,int)}.
     * Input is the encoded chunk key (see {@link #encodeKey(int,int)}). Default returns {@code true}.
     * Set a custom predicate to simulate Folia native chunk GC (e.g. {@code k -> false} after
     * the first block-evaluation attempt) for REQ-RTP-S-005 stale-chunk guard tests.
     */
    public volatile LongPredicate isChunkLoadedPredicate = key -> true;

    /** Test hook: number of times {@link #getChunkAtAsync(int,int)} has been invoked. */
    public final AtomicInteger chunkAsyncLoadCount = new AtomicInteger();

    /**
     * Test hook: when this predicate returns {@code true} for a given encoded chunk key,
     * both {@link #getChunkAt(int,int)} and {@link #getChunkAtAsync(int,int)} complete
     * their long-key future with {@code null}. This simulates the ADR-016
     * Anvil pre-filter {@code REJECT} path on vanilla Spigot (where
     * {@code BukkitRTPWorld.getChunkAt} completes with {@code null} by design) and lets
     * the REQ-RTP-S-004 nullChunk attribution tests exercise
     * {@link io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes#nullChunk}.
     * Default: never returns null.
     */
    public volatile LongPredicate nullChunkKeyPredicate = key -> false;

    /**
     * Test hook: number of times any {@link MockRTPChunk#isSafe(int,int,int,java.util.Set)}
     * on a chunk belonging to this world has been invoked. Used by the stale-chunk guard
     * test (ADR-015 / REQ-RTP-S-005) to assert that block evaluation is entirely bypassed
     * when the guard trips.
     */
    public final AtomicInteger isSafeCallCount = new AtomicInteger();

    @Override
    public String name() {
        return name;
    }

    @Override
    public UUID id() {
        return id;
    }

    /**
     * Encodes chunk coordinates into a single long key so that
     * {@link #getCachedChunk(long)} can reconstruct the exact chunk.
     */
    private static long encodeKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** Returns a future that completes immediately with the encoded chunk key, or {@code null} if {@link #nullChunkKeyPredicate} signals rejection. */
    @Override
    public CompletableFuture<Long> getChunkAt(int chunkX, int chunkZ) {
        long key = encodeKey(chunkX, chunkZ);
        if (nullChunkKeyPredicate.test(key)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.completedFuture(key);
    }

    /**
     * Returns a {@link ChunkSet} whose {@code complete} future and every chunk future
     * resolve immediately and synchronously.
     *
     * <p>The single entry in the {@code chunks} list is pre-completed with the encoded
     * chunk key so that {@link #getCachedChunk(long)} can reconstruct a
     * {@link MockRTPChunk} with the correct chunk coordinates.  {@code allOf} on that
     * already-completed future fires the compact-constructor's {@code whenComplete}
     * callback synchronously, completing the {@code complete} future to {@code true}
     * before this method returns.
     */
    @Override
    public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
        chunkAsyncLoadCount.incrementAndGet();
        long key = encodeKey(cx, cz);
        CompletableFuture<Long> chunkFuture =
                nullChunkKeyPredicate.test(key)
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.completedFuture(key);
        ChunkSet chunkSet = new ChunkSet(
                this, cx, cz,
                List.of(chunkFuture),
                new CompletableFuture<>());
        return CompletableFuture.completedFuture(chunkSet);
    }

    /**
     * Test-controllable implementation of the stale-chunk guard contract
     * (ADR-015 / REQ-RTP-S-005). Delegates to {@link #isChunkLoadedPredicate};
     * defaults to {@code true}.
     */
    /** Test hook: number of times {@link #isChunkLoaded(int,int)} has been invoked. */
    public final AtomicInteger isChunkLoadedCallCount = new AtomicInteger();

    @Override
    public boolean isChunkLoaded(int cx, int cz) {
        isChunkLoadedCallCount.incrementAndGet();
        return isChunkLoadedPredicate.test(encodeKey(cx, cz));
    }

    @Override
    protected CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
        // no-op in tests — synchronous apply, completed future.
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Integer> getServerForceLoadedCount() {
        return CompletableFuture.completedFuture(0);
    }

    /**
     * Decodes the chunk key produced by {@link #getChunkAt} / {@link #getChunkAtAsync}
     * and returns a {@link MockRTPChunk} at those coordinates.
     */
    @Override
    public RTPChunk<?> getCachedChunk(long key) {
        // Honour the nullChunkKeyPredicate on the cache path too so that
        // ADR-016 §13.1 getOrLoadChunk() (which queries getCachedChunk before
        // falling through to getChunkAtAsync) correctly simulates the
        // "all null" scenarios used by the REQ-RTP-S-004 attribution tests.
        if (nullChunkKeyPredicate.test(key)) {
            return null;
        }
        int cx = (int) (key >> 32);
        int cz = (int) (key & 0xFFFFFFFFL);
        return new MockRTPChunk(cx, cz, this);
    }

    @Override
    public void keepChunkAt(int chunkX, int chunkZ) {
        // no-op in tests
    }

    @Override
    public void forgetChunkAt(int chunkX, int chunkZ) {
        // no-op in tests
    }

    @Override
    public void forgetChunks() {
        // no-op in tests
    }

    @Override
    public String getBiome(int x, int y, int z) {
        return "PLAINS";
    }

    @Override
    public void platform(RTPLocation location) {
        // no-op in tests — no platform to apply
    }

    @Override
    public boolean isInactive() {
        return false;
    }

    @Override
    public void save() {
        // no-op in tests
    }

    @Override
    public int getMaxHeight() {
        return 256;
    }

    @Override
    public int getMinHeight() {
        return 0;
    }

    @Override
    public int getCacheSize() {
        return 0;
    }

    @Override
    public long getSeed() {
        return 0L;
    }
}
