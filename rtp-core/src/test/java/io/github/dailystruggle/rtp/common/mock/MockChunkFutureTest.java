package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the mock contract that guarantees all chunk-load futures returned by
 * {@link MockRTPWorld} and {@link MockLocationGenerator} are pre-completed.
 *
 * <p>This directly supports the ARCH-EXCEPTION recorded in {@code RTPArchitectureTest}:
 * {@code LocationGenerator} calls {@code .get()} and {@code .join()} on futures that the
 * platform adapter guarantees are already resolved. These tests prove the mock honours
 * the same contract, so unit tests exercising {@code LocationGenerator} under the mock
 * never block.
 */
class MockChunkFutureTest {

    // -------------------------------------------------------------------------
    // MockRTPWorld contract
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void getChunkAtAsync_outerFutureIsAlreadyCompleted() throws ExecutionException, InterruptedException {
        MockRTPWorld world = new MockRTPWorld();
        CompletableFuture<ChunkSet> future = world.getChunkAtAsync(0, 0);

        assertTrue(future.isDone(), "outer ChunkSet future must be completed immediately");
        assertNotNull(future.get());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void getChunkAtAsync_chunkListIsNonEmpty() throws ExecutionException, InterruptedException {
        MockRTPWorld world = new MockRTPWorld();
        ChunkSet chunkSet = world.getChunkAtAsync(0, 0).get();

        assertFalse(chunkSet.chunks().isEmpty(),
                "chunks list must contain at least one entry so .getFirst() does not throw");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void getChunkAtAsync_firstChunkFutureIsAlreadyCompleted() throws ExecutionException, InterruptedException {
        MockRTPWorld world = new MockRTPWorld();
        ChunkSet chunkSet = world.getChunkAtAsync(0, 0).get();
        CompletableFuture<Long> firstChunk = chunkSet.chunks().getFirst();

        assertTrue(firstChunk.isDone(),
                "first chunk future must be pre-completed so .get() does not block");
        assertEquals(0L, firstChunk.get(),
                "chunk key must be 0L as documented in MockRTPWorld");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void getChunkAtAsync_completeFutureIsAlreadyCompleted() throws ExecutionException, InterruptedException {
        MockRTPWorld world = new MockRTPWorld();
        ChunkSet chunkSet = world.getChunkAtAsync(0, 0).get();

        assertTrue(chunkSet.complete().isDone(),
                "ChunkSet.complete must resolve synchronously via allOf in the compact constructor");
        assertTrue(chunkSet.complete().get(),
                "ChunkSet.complete must resolve to true when all chunks load successfully");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void getChunkAt_isAlreadyCompleted() {
        MockRTPWorld world = new MockRTPWorld();
        CompletableFuture<Long> future = world.getChunkAt(0, 0);

        assertTrue(future.isDone(), "synchronous getChunkAt must also return a completed future");
    }

    // -------------------------------------------------------------------------
    // MockLocationGenerator contract
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void mockLocationGenerator_buildResult_chunkListIsNonEmpty() {
        MockLocationGenerator gen = new MockLocationGenerator();
        GenerationResult result = gen.buildResult();

        assertNotNull(result.verifiedChunks(), "GenerationResult must carry a ChunkSet");
        assertFalse(result.verifiedChunks().chunks().isEmpty(),
                "chunks list must be non-empty so LocationGenerator can call .getFirst()");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void mockLocationGenerator_buildResult_firstChunkFutureIsAlreadyCompleted()
            throws ExecutionException, InterruptedException {
        MockLocationGenerator gen = new MockLocationGenerator();
        CompletableFuture<Long> firstChunk = gen.buildResult().verifiedChunks().chunks().getFirst();

        assertTrue(firstChunk.isDone(),
                "first chunk future in GenerationResult must be pre-completed");
        assertEquals(0L, firstChunk.get());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void mockLocationGenerator_getLocation_outerFutureIsAlreadyCompleted() {
        MockLocationGenerator gen = new MockLocationGenerator();
        // Use the biomeNames overload — the simplest call path that exercises buildResult()
        CompletableFuture<GenerationResult> future = gen.getLocation(new Object(), Set.of());

        assertTrue(future.isDone(),
                "getLocation must return a completed future so .join() does not block");
    }
}
