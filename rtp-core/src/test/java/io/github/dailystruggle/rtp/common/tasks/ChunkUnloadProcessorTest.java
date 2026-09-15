package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChunkUnloadProcessorTest {

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        RTP.getInstance().chunksToUnload.clear();
    }

    @AfterEach
    void tearDown() {
        if (RTP.getInstance() != null) {
            RTP.getInstance().chunksToUnload.clear();
        }
    }

    @Test
    void run_withEmptyQueue_doesNothing() {
        ChunkUnloadProcessor processor = new ChunkUnloadProcessor();
        assertDoesNotThrow(processor::run);
    }

    @Test
    void run_unloadsLoadedChunks() {
        ChunkUnloadProcessor processor = new ChunkUnloadProcessor();
        RTPChunk<?> chunk1 = mock(RTPChunk.class);
        RTPChunk<?> chunk2 = mock(RTPChunk.class);

        when(chunk1.isLoaded()).thenReturn(true);
        when(chunk2.isLoaded()).thenReturn(false);

        RTP.getInstance().chunksToUnload.add(chunk1);
        RTP.getInstance().chunksToUnload.add(chunk2);

        processor.run();

        verify(chunk1, times(1)).unload();
        verify(chunk2, never()).unload();
        assertTrue(RTP.getInstance().chunksToUnload.isEmpty());
    }

    @Test
    void run_capsAtFiftyChunksPerRun() {
        ChunkUnloadProcessor processor = new ChunkUnloadProcessor();
        for (int i = 0; i < 75; i++) {
            RTPChunk<?> chunk = mock(RTPChunk.class);
            when(chunk.isLoaded()).thenReturn(false);
            RTP.getInstance().chunksToUnload.add(chunk);
        }

        assertEquals(75, RTP.getInstance().chunksToUnload.size());
        processor.run();
        assertEquals(25, RTP.getInstance().chunksToUnload.size());
    }

    @Test
    void run_handlesExceptionGracefully() {
        ChunkUnloadProcessor processor = new ChunkUnloadProcessor();
        RTPChunk<?> throwingChunk = mock(RTPChunk.class);
        when(throwingChunk.isLoaded()).thenThrow(new RuntimeException("forced test failure"));

        RTP.getInstance().chunksToUnload.add(throwingChunk);
        assertDoesNotThrow(processor::run);
    }
}
