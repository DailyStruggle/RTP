package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("REQ-RTP-S-001: SafetyScan Unit Tests")
class SafetyScanTest {

    @TempDir
    File tempDir;

    private RTPWorld<?> world;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        world = accessor.getRTPWorld("world");
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
        RTP.scheduler = null;
    }

    @Test
    @DisplayName("isColumnSafe returns true when all chunks and blocks are safe with safe=0")
    void testSafeRadiusZero_Safe() {
        int safe = 0;
        int L = 2 * safe + 1; // 1
        RTPChunk<?>[] localChunks = new RTPChunk<?>[L * L];
        RTPChunk<?> chunk = mock(RTPChunk.class);
        when(chunk.isSafe(anyInt(), anyInt(), anyInt(), anySet())).thenReturn(true);
        localChunks[0] = chunk;

        RTPCoords coords = new RTPCoords("world", 10, 64, 10);
        boolean result = SafetyScan.isColumnSafe(coords, world, localChunks, L, 0, 0, safe, Collections.emptySet());
        assertTrue(result);
    }

    @Test
    @DisplayName("isColumnSafe returns false when any chunk in localChunks is null")
    void testMissingChunkFails() {
        int safe = 1;
        int L = 2 * safe + 1; // 3
        RTPChunk<?>[] localChunks = new RTPChunk<?>[L * L]; // all null

        RTPCoords coords = new RTPCoords("world", 10, 64, 10);
        boolean result = SafetyScan.isColumnSafe(coords, world, localChunks, L, 0, 0, safe, Collections.emptySet());
        assertFalse(result);
    }

    @Test
    @DisplayName("isColumnSafe returns false when block in safety cube is unsafe")
    void testCubeBlockUnsafe() {
        int safe = 1;
        int L = 2 * safe + 1; // 3
        RTPChunk<?>[] localChunks = new RTPChunk<?>[L * L];
        for (int i = 0; i < localChunks.length; i++) {
            RTPChunk<?> c = mock(RTPChunk.class);
            when(c.isSafe(anyInt(), anyInt(), anyInt(), anySet())).thenReturn(true);
            localChunks[i] = c;
        }

        // Make the center chunk report unsafe at a specific block
        RTPChunk<?> centerChunk = localChunks[safe * L + safe];
        when(centerChunk.isSafe(10 & 15, 64, 10 & 15, Collections.emptySet())).thenReturn(false);

        RTPCoords coords = new RTPCoords("world", 10, 64, 10);
        boolean result = SafetyScan.isColumnSafe(coords, world, localChunks, L, 0, 0, safe, Collections.emptySet());
        assertFalse(result);
    }

    @Test
    @DisplayName("isColumnSafe skips Y coordinates outside world min and max height during cube scan")
    void testCubeScanSkipsOutOfBoundsY() {
        RTPWorld<?> boundedWorld = mock(RTPWorld.class);
        when(boundedWorld.getMinHeight()).thenReturn(60);
        when(boundedWorld.getMaxHeight()).thenReturn(70);

        int safe = 1;
        int L = 2 * safe + 1;
        RTPChunk<?>[] localChunks = new RTPChunk<?>[L * L];
        for (int i = 0; i < localChunks.length; i++) {
            RTPChunk<?> c = mock(RTPChunk.class);
            when(c.isSafe(anyInt(), anyInt(), anyInt(), anySet())).thenReturn(true);
            localChunks[i] = c;
        }

        // Candidate at Y=60 (minHeight). Cube scan goes from Y=59 to Y=61.
        // Y=59 is out of bounds and should be skipped.
        RTPCoords coords = new RTPCoords("world", 10, 60, 10);
        boolean result = SafetyScan.isColumnSafe(coords, boundedWorld, localChunks, L, 0, 0, safe, Collections.emptySet());
        assertTrue(result);
    }

    @Test
    @DisplayName("isColumnSafe returns false when center head position is unsafe")
    void testCenterHeadUnsafe() {
        int safe = 0;
        int L = 1;
        RTPChunk<?>[] localChunks = new RTPChunk<?>[1];
        RTPChunk<?> chunk = mock(RTPChunk.class);
        when(chunk.isSafe(anyInt(), anyInt(), anyInt(), anySet())).thenReturn(true);
        // Head is feetY + 1 = 65
        when(chunk.isSafe(10 & 15, 65, 10 & 15, Collections.emptySet())).thenReturn(false);
        localChunks[0] = chunk;

        RTPCoords coords = new RTPCoords("world", 10, 64, 10);
        boolean result = SafetyScan.isColumnSafe(coords, world, localChunks, L, 0, 0, safe, Collections.emptySet());
        assertFalse(result);
    }

    @Test
    @DisplayName("isColumnSafe returns false when ground check below feet is unsafe")
    void testCenterGroundUnsafe() {
        int safe = 0;
        int L = 1;
        RTPChunk<?>[] localChunks = new RTPChunk<?>[1];
        RTPChunk<?> chunk = mock(RTPChunk.class);
        when(chunk.isSafe(anyInt(), anyInt(), anyInt(), anySet())).thenReturn(true);
        // Feet is 64, ground depth check at 64 - 1 = 63
        when(chunk.isSafe(10 & 15, 63, 10 & 15, Collections.emptySet())).thenReturn(false);
        localChunks[0] = chunk;

        RTPCoords coords = new RTPCoords("world", 10, 64, 10);
        boolean result = SafetyScan.isColumnSafe(coords, world, localChunks, L, 0, 0, safe, Collections.emptySet());
        assertFalse(result);
    }

    @Test
    @DisplayName("isColumnSafe returns false if chunk throws exception")
    void testExceptionFailsClosed() {
        int safe = 0;
        int L = 1;
        RTPChunk<?>[] localChunks = new RTPChunk<?>[1];
        RTPChunk<?> chunk = mock(RTPChunk.class);
        when(chunk.isSafe(anyInt(), anyInt(), anyInt(), anySet())).thenThrow(new RuntimeException("Simulated chunk read failure"));
        localChunks[0] = chunk;

        RTPCoords coords = new RTPCoords("world", 10, 64, 10);
        boolean result = SafetyScan.isColumnSafe(coords, world, localChunks, L, 0, 0, safe, Collections.emptySet());
        assertFalse(result);
    }

    @Test
    @DisplayName("isColumnSafe handles negative center coordinates and cross-chunk boundaries")
    void testNegativeChunkCoordinatesCrossBoundary() {
        int safe = 1;
        int L = 3;
        RTPChunk<?>[] localChunks = new RTPChunk<?>[9];
        for (int i = 0; i < 9; i++) {
            RTPChunk<?> c = mock(RTPChunk.class);
            when(c.isSafe(anyInt(), anyInt(), anyInt(), anySet())).thenReturn(true);
            localChunks[i] = c;
        }

        // Block at X = -16 (chunkX = -1), Z = -16 (chunkZ = -1)
        // safe = 1 checks X from -17 to -15 (crosses chunk boundary between chunk -2 and -1)
        int centerChunkX = -1;
        int centerChunkZ = -1;
        RTPCoords coords = new RTPCoords("world", -16, 64, -16);
        boolean result = SafetyScan.isColumnSafe(coords, world, localChunks, L, centerChunkX, centerChunkZ, safe, Collections.emptySet());
        assertTrue(result);
    }
}
