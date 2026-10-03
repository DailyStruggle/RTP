package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RegionCandidateValidator Tests")
class RegionCandidateValidatorTest {

    @TempDir
    File tempDir;

    private MockRTPWorld world;
    private VerticalAdjustor<?> mockVert;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        world = new MockRTPWorld("world");
        accessor.addWorld(world);
        mockVert = mock(VerticalAdjustor.class);

        @SuppressWarnings("unchecked")
        ConfigParser<SafetyKeys> safetyParser = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        if (safetyParser != null) {
            safetyParser.set(SafetyKeys.safetyRadius, 0);
            safetyParser.set(SafetyKeys.platformRadius, 0);
        }
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
        RTP.scheduler = null;
    }

    @Test
    @DisplayName("Constructor throws NPE when region is null")
    void constructorNullRegion() {
        assertThrows(NullPointerException.class, () -> new RegionCandidateValidator(null));
    }

    @Test
    @DisplayName("validate returns null when world is null")
    void validateNullWorld() {
        Region mockRegion = mock(Region.class);
        doReturn(null).when(mockRegion).getWorld();
        doReturn(mockVert).when(mockRegion).getVert();

        RegionCandidateValidator validator = new RegionCandidateValidator(mockRegion);
        assertNull(validator.validate(0, 0));
    }

    @Test
    @DisplayName("validate returns null when vert adjustor is null")
    void validateNullVert() {
        Region mockRegion = mock(Region.class);
        doReturn(world).when(mockRegion).getWorld();
        doReturn(null).when(mockRegion).getVert();

        RegionCandidateValidator validator = new RegionCandidateValidator(mockRegion);
        assertNull(validator.validate(0, 0));
    }

    @Test
    @DisplayName("validate returns null when center chunk is not cached (fail-closed)")
    void validateCenterChunkNotCached() {
        RTPWorld<?> mockWorld = mock(RTPWorld.class);
        when(mockWorld.getCachedChunk(anyLong())).thenReturn(null);

        Region mockRegion = mock(Region.class);
        doReturn(mockWorld).when(mockRegion).getWorld();
        doReturn(mockVert).when(mockRegion).getVert();

        RegionCandidateValidator validator = new RegionCandidateValidator(mockRegion);
        assertNull(validator.validate(100, 200));
    }

    @Test
    @DisplayName("validate returns null when vertical adjustor adjustColumn returns null")
    void validateAdjustColumnFails() {
        RTPWorld<?> mockWorld = mock(RTPWorld.class);
        RTPChunk<?> mockChunk = mock(RTPChunk.class);
        when(mockChunk.x()).thenReturn(0);
        when(mockChunk.z()).thenReturn(0);
        when(mockWorld.getCachedChunk(anyLong())).thenReturn((RTPChunk) mockChunk);

        when(mockVert.adjustColumn(any(), anyInt(), anyInt())).thenReturn(null);

        Region mockRegion = mock(Region.class);
        doReturn(mockWorld).when(mockRegion).getWorld();
        doReturn(mockVert).when(mockRegion).getVert();

        RegionCandidateValidator validator = new RegionCandidateValidator(mockRegion);
        assertNull(validator.validate(5, 5));
    }

    @Test
    @DisplayName("validate returns null when required neighbour chunk is not cached")
    void validateNeighbourChunkMissing() {
        RTPWorld<?> mockWorld = mock(RTPWorld.class);
        RTPChunk<?> centerChunk = mock(RTPChunk.class);
        when(centerChunk.x()).thenReturn(0);
        when(centerChunk.z()).thenReturn(0);

        // Center chunk exists, but neighbour chunks return null
        long centerKey = 0L;
        when(mockWorld.getCachedChunk(centerKey)).thenReturn((RTPChunk) centerChunk);

        // Configure a safety radius > 0 so neighbour chunks are queried
        @SuppressWarnings("unchecked")
        ConfigParser<SafetyKeys> safetyParser = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        if (safetyParser != null) {
            safetyParser.set(SafetyKeys.safetyRadius, 2);
        }

        // Position at chunk edge (15, 15) so safety radius spills into neighbor chunk (1, 1)
        when(mockVert.adjustColumn(any(), eq(15), eq(15))).thenReturn(new RTPCoords("world", 15, 64, 15));

        Region mockRegion = mock(Region.class);
        doReturn(mockWorld).when(mockRegion).getWorld();
        doReturn(mockVert).when(mockRegion).getVert();

        RegionCandidateValidator validator = new RegionCandidateValidator(mockRegion);
        assertNull(validator.validate(15, 15));
    }

    @Test
    @DisplayName("validate returns null when SafetyScan.isColumnSafe returns false")
    void validateUnsafeColumn() {
        RTPWorld<?> mockWorld = mock(RTPWorld.class);
        when(mockWorld.name()).thenReturn("world");
        when(mockWorld.getMinHeight()).thenReturn(-64);
        when(mockWorld.getMaxHeight()).thenReturn(320);

        RTPChunk<?> mockChunk = mock(RTPChunk.class);
        when(mockChunk.x()).thenReturn(0);
        when(mockChunk.z()).thenReturn(0);
        when(mockChunk.getWorld()).thenReturn((RTPWorld) mockWorld);
        when(mockWorld.getCachedChunk(anyLong())).thenReturn((RTPChunk) mockChunk);

        // Substanding block is unsafe -> chunk.isSafe returns false
        doReturn(false).when(mockChunk).isSafe(anyInt(), anyInt(), anyInt(), any(Set.class));

        when(mockVert.adjustColumn(any(), anyInt(), anyInt())).thenReturn(new RTPCoords("world", 5, 64, 5));

        // Configure unsafeBlocks to include LAVA
        @SuppressWarnings("unchecked")
        ConfigParser<BlocksKeys> blocksParser = (ConfigParser<BlocksKeys>) RTP.configs.getParser(BlocksKeys.class);
        if (blocksParser != null) {
            blocksParser.set(BlocksKeys.unsafeBlocks, List.of("LAVA"));
        }

        Region mockRegion = mock(Region.class);
        doReturn(mockWorld).when(mockRegion).getWorld();
        doReturn(mockVert).when(mockRegion).getVert();

        RegionCandidateValidator validator = new RegionCandidateValidator(mockRegion);
        assertNull(validator.validate(5, 5));
    }

    @Test
    @DisplayName("validate succeeds and returns location when column and neighbours are safe")
    void validateSuccessfulCandidate() {
        RTPWorld<?> mockWorld = mock(RTPWorld.class);
        when(mockWorld.name()).thenReturn("world");
        when(mockWorld.getMinHeight()).thenReturn(-64);
        when(mockWorld.getMaxHeight()).thenReturn(320);

        RTPChunk<?> mockChunk = mock(RTPChunk.class);
        when(mockChunk.x()).thenReturn(0);
        when(mockChunk.z()).thenReturn(0);
        when(mockChunk.getWorld()).thenReturn((RTPWorld) mockWorld);
        doReturn(true).when(mockChunk).isSafe(anyInt(), anyInt(), anyInt(), any(Set.class));
        when(mockWorld.getCachedChunk(anyLong())).thenReturn((RTPChunk) mockChunk);

        when(mockVert.adjustColumn(any(), anyInt(), anyInt())).thenReturn(new RTPCoords("world", 5, 64, 5));

        // Safety radius 0 for minimal footprint
        @SuppressWarnings("unchecked")
        ConfigParser<SafetyKeys> safetyParser = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        if (safetyParser != null) {
            safetyParser.set(SafetyKeys.safetyRadius, 0);
        }

        Region mockRegion = mock(Region.class);
        doReturn(mockWorld).when(mockRegion).getWorld();
        doReturn(mockVert).when(mockRegion).getVert();

        RegionCandidateValidator validator = new RegionCandidateValidator(mockRegion);
        RTPLocation result = validator.validate(5, 5);

        assertNotNull(result);
        assertEquals(5, result.coords().x());
        assertEquals(64, result.coords().y());
        assertEquals(5, result.coords().z());
    }

    @Test
    @DisplayName("validate returns null fail-closed when an unexpected exception is thrown")
    void validateExceptionFailClosed() {
        Region mockRegion = mock(Region.class);
        when(mockRegion.getWorld()).thenThrow(new RuntimeException("Simulated error"));

        RegionCandidateValidator validator = new RegionCandidateValidator(mockRegion);
        assertNull(validator.validate(0, 0));
    }
    @Test
    @DisplayName("packChunkKey encodes and decodes correctly")
    void packChunkKeyEncodesCorrectly() throws Exception {
        java.lang.reflect.Method m = RegionCandidateValidator.class.getDeclaredMethod("packChunkKey", int.class, int.class);
        m.setAccessible(true);
        long key1 = (long) m.invoke(null, 5, -10);
        assertEquals(5, (int) (key1 & 0xffffffffL));
        assertEquals(-10, (int) (key1 >> 32));
    }

    @Test
    @DisplayName("readSafetyRadius and readUnsafeBlocks boundary handling")
    void readConfigMethods() throws Exception {
        Region mockRegion = mock(Region.class);
        RegionCandidateValidator validator = new RegionCandidateValidator(mockRegion);

        java.lang.reflect.Method mSafe = RegionCandidateValidator.class.getDeclaredMethod("readSafetyRadius");
        mSafe.setAccessible(true);
        int safe = (int) mSafe.invoke(validator);
        assertTrue(safe >= 0);

        java.lang.reflect.Method mUnsafe = RegionCandidateValidator.class.getDeclaredMethod("readUnsafeBlocks");
        mUnsafe.setAccessible(true);
        Set<?> unsafe = (Set<?>) mUnsafe.invoke(validator);
        assertNotNull(unsafe);
    }

    @Test
    @DisplayName("validate falls back from obstructed center column to safe quadrant column")
    void validateFallbackFromBlockedCenterToSafeQuadrant() {
        RTPWorld<?> mockWorld = mock(RTPWorld.class);
        when(mockWorld.name()).thenReturn("world");
        when(mockWorld.getMinHeight()).thenReturn(-64);
        when(mockWorld.getMaxHeight()).thenReturn(320);

        RTPChunk<?> mockChunk = mock(RTPChunk.class);
        when(mockChunk.x()).thenReturn(0);
        when(mockChunk.z()).thenReturn(0);
        when(mockChunk.getWorld()).thenReturn((RTPWorld) mockWorld);
        doReturn(true).when(mockChunk).isSafe(anyInt(), anyInt(), anyInt(), any(Set.class));
        when(mockWorld.getCachedChunk(anyLong())).thenReturn((RTPChunk) mockChunk);

        // Center column (7, 7) is obstructed (e.g. tree leaves / water / ledge) -> adjustColumn returns null
        when(mockVert.adjustColumn(any(), eq(7), eq(7))).thenReturn(null);

        // First fallback quadrant column (2, 2) is safe -> returns resolved coords
        when(mockVert.adjustColumn(any(), eq(2), eq(2))).thenReturn(new RTPCoords("world", 2, 64, 2));

        Region mockRegion = mock(Region.class);
        doReturn(mockWorld).when(mockRegion).getWorld();
        doReturn(mockVert).when(mockRegion).getVert();

        RegionCandidateValidator validator = new RegionCandidateValidator(mockRegion);
        RTPLocation result = validator.validate(7, 7);

        assertNotNull(result);
        assertEquals(2, result.coords().x());
        assertEquals(64, result.coords().y());
        assertEquals(2, result.coords().z());
    }

    @Test
    @DisplayName("validate falls back through multiple obstructed quadrants until finding a safe column")
    void validateFallbackMultipleQuadrants() {
        RTPWorld<?> mockWorld = mock(RTPWorld.class);
        when(mockWorld.name()).thenReturn("world");
        when(mockWorld.getMinHeight()).thenReturn(-64);
        when(mockWorld.getMaxHeight()).thenReturn(320);

        RTPChunk<?> mockChunk = mock(RTPChunk.class);
        when(mockChunk.x()).thenReturn(1);
        when(mockChunk.z()).thenReturn(2);
        when(mockChunk.getWorld()).thenReturn((RTPWorld) mockWorld);
        doReturn(true).when(mockChunk).isSafe(anyInt(), anyInt(), anyInt(), any(Set.class));
        when(mockWorld.getCachedChunk(anyLong())).thenReturn((RTPChunk) mockChunk);

        // Chunk coords cx=1, cz=2 -> world base X=16, Z=32
        // Test coords are (7,7), (2,2), (12,12), (2,12), (12,2)
        // (7,7) obstructed
        when(mockVert.adjustColumn(any(), eq(7), eq(7))).thenReturn(null);
        // (2,2) obstructed
        when(mockVert.adjustColumn(any(), eq(2), eq(2))).thenReturn(null);
        // (12,12) safe
        when(mockVert.adjustColumn(any(), eq(12), eq(12))).thenReturn(new RTPCoords("world", 16 + 12, 70, 32 + 12));

        Region mockRegion = mock(Region.class);
        doReturn(mockWorld).when(mockRegion).getWorld();
        doReturn(mockVert).when(mockRegion).getVert();

        RegionCandidateValidator validator = new RegionCandidateValidator(mockRegion);
        // Request center column in chunk (1, 2) -> world (16 + 7, 32 + 7) = (23, 39)
        RTPLocation result = validator.validate(23, 39);

        assertNotNull(result);
        assertEquals(28, result.coords().x());
        assertEquals(70, result.coords().y());
        assertEquals(44, result.coords().z());
    }

    @Test
    @DisplayName("validate returns null when nominal and all quadrant columns are obstructed")
    void validateAllQuadrantsObstructedReturnsNull() {
        RTPWorld<?> mockWorld = mock(RTPWorld.class);
        RTPChunk<?> mockChunk = mock(RTPChunk.class);
        when(mockChunk.x()).thenReturn(0);
        when(mockChunk.z()).thenReturn(0);
        when(mockWorld.getCachedChunk(anyLong())).thenReturn((RTPChunk) mockChunk);

        // All columns obstructed
        when(mockVert.adjustColumn(any(), anyInt(), anyInt())).thenReturn(null);

        Region mockRegion = mock(Region.class);
        doReturn(mockWorld).when(mockRegion).getWorld();
        doReturn(mockVert).when(mockRegion).getVert();

        RegionCandidateValidator validator = new RegionCandidateValidator(mockRegion);
        RTPLocation result = validator.validate(7, 7);

        assertNull(result);
    }

    @Test
    @DisplayName("validate falls back to quadrant when center column fails SafetyScan clearance")
    void validateFallbackWhenCenterFailsSafetyScan() {
        RTPWorld<?> mockWorld = mock(RTPWorld.class);
        when(mockWorld.name()).thenReturn("world");
        when(mockWorld.getMinHeight()).thenReturn(-64);
        when(mockWorld.getMaxHeight()).thenReturn(320);

        RTPChunk<?> mockChunk = mock(RTPChunk.class);
        when(mockChunk.x()).thenReturn(0);
        when(mockChunk.z()).thenReturn(0);
        when(mockChunk.getWorld()).thenReturn((RTPWorld) mockWorld);
        when(mockWorld.getCachedChunk(anyLong())).thenReturn((RTPChunk) mockChunk);

        // Center column (7, 7) resolves Y but fails safety (e.g. water / lava)
        when(mockVert.adjustColumn(any(), eq(7), eq(7))).thenReturn(new RTPCoords("world", 7, 64, 7));
        doReturn(false).when(mockChunk).isSafe(eq(7), anyInt(), eq(7), any(Set.class));

        // Quadrant column (2, 2) resolves Y and passes safety
        when(mockVert.adjustColumn(any(), eq(2), eq(2))).thenReturn(new RTPCoords("world", 2, 64, 2));
        doReturn(true).when(mockChunk).isSafe(eq(2), anyInt(), eq(2), any(Set.class));

        @SuppressWarnings("unchecked")
        ConfigParser<BlocksKeys> blocksParser = (ConfigParser<BlocksKeys>) RTP.configs.getParser(BlocksKeys.class);
        if (blocksParser != null) {
            blocksParser.set(BlocksKeys.unsafeBlocks, List.of("WATER", "LAVA"));
        }

        Region mockRegion = mock(Region.class);
        doReturn(mockWorld).when(mockRegion).getWorld();
        doReturn(mockVert).when(mockRegion).getVert();

        RegionCandidateValidator validator = new RegionCandidateValidator(mockRegion);
        RTPLocation result = validator.validate(7, 7);

        assertNotNull(result);
        assertEquals(2, result.coords().x());
        assertEquals(64, result.coords().y());
        assertEquals(2, result.coords().z());
    }

    @Test
    @DisplayName("validateAsync falls back to quadrant column")
    void validateAsyncFallbackToQuadrant() {
        RTPWorld<?> mockWorld = mock(RTPWorld.class);
        when(mockWorld.name()).thenReturn("world");
        when(mockWorld.getMinHeight()).thenReturn(-64);
        when(mockWorld.getMaxHeight()).thenReturn(320);

        RTPChunk<?> mockChunk = mock(RTPChunk.class);
        when(mockChunk.x()).thenReturn(0);
        when(mockChunk.z()).thenReturn(0);
        when(mockChunk.getWorld()).thenReturn((RTPWorld) mockWorld);
        doReturn(true).when(mockChunk).isSafe(anyInt(), anyInt(), anyInt(), any(Set.class));
        when(mockWorld.getCachedChunk(anyLong())).thenReturn((RTPChunk) mockChunk);

        // Center column (7, 7) obstructed
        when(mockVert.adjustColumn(any(), eq(7), eq(7))).thenReturn(null);
        // Quadrant (2, 2) safe
        when(mockVert.adjustColumn(any(), eq(2), eq(2))).thenReturn(new RTPCoords("world", 2, 64, 2));

        Region mockRegion = mock(Region.class);
        doReturn(mockWorld).when(mockRegion).getWorld();
        doReturn(mockVert).when(mockRegion).getVert();

        RegionCandidateValidator validator = new RegionCandidateValidator(mockRegion);
        RTPLocation result = validator.validateAsync(7, 7).join();

        assertNotNull(result);
        assertEquals(2, result.coords().x());
        assertEquals(64, result.coords().y());
        assertEquals(2, result.coords().z());
    }
}
