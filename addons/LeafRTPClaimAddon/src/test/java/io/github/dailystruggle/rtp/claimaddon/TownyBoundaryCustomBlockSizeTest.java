package io.github.dailystruggle.rtp.claimaddon;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.claim.ClaimBoundary;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Towny custom town block size boundary mapping")
class TownyBoundaryCustomBlockSizeTest {

  @Test
  @DisplayName("Towny boundary correctly maps coordinates and chunks when town block size is 32")
  void testTownyCustomBlockSize32() {
    int tbSize = 32;
    Set<Long> townBlockKeys = new HashSet<>();
    // Add town blocks at (0, 0) covering blocks 0..31, 0..31 (chunks 0..1, 0..1)
    // and (1, 0) covering blocks 32..63, 0..31 (chunks 2..3, 0..1)
    townBlockKeys.add(0L); // (0 << 32) | 0
    townBlockKeys.add(1L << 32); // (1 << 32) | 0

    ClaimBoundary boundary = TownyBoundaryProvider.buildBoundary(
        "test_town",
        "world",
        tbSize,
        townBlockKeys,
        32,
        16,
        0,
        0,
        3,
        1
    );

    assertEquals("test_town", boundary.id());
    assertEquals("world", boundary.world());
    assertArrayEquals(new int[] {32, 16}, boundary.centroid());
    assertEquals(0, boundary.minChunkX());
    assertEquals(3, boundary.maxChunkX());
    assertEquals(0, boundary.minChunkZ());
    assertEquals(1, boundary.maxChunkZ());

    // Coordinate containment
    assertTrue(boundary.contains(10, 10), "Point inside (0, 0) townblock should be contained");
    assertTrue(boundary.contains(31, 31), "Point at edge of (0, 0) townblock should be contained");
    assertTrue(boundary.contains(32, 0), "Point at start of (1, 0) townblock should be contained");
    assertTrue(boundary.contains(50, 15), "Point inside (1, 0) townblock should be contained");
    assertTrue(boundary.contains(63, 31), "Point at edge of (1, 0) townblock should be contained");

    // Outside coordinates
    assertFalse(boundary.contains(-1, 10), "Negative X should not be contained");
    assertFalse(boundary.contains(64, 15), "X=64 is beyond townblock 1 and should not be contained");
    assertFalse(boundary.contains(10, 32), "Z=32 is beyond townblock 0 and should not be contained");
    assertFalse(boundary.contains(10, -1), "Negative Z should not be contained");

    // Chunk containment (chunks are 16x16)
    assertTrue(boundary.containsChunk(0, 0), "Chunk 0,0 is inside townblock (0,0)");
    assertTrue(boundary.containsChunk(1, 0), "Chunk 1,0 is inside townblock (0,0)");
    assertTrue(boundary.containsChunk(2, 0), "Chunk 2,0 is inside townblock (1,0)");
    assertTrue(boundary.containsChunk(3, 0), "Chunk 3,0 is inside townblock (1,0)");

    assertFalse(boundary.containsChunk(4, 0), "Chunk 4,0 is outside");
    assertFalse(boundary.containsChunk(0, 2), "Chunk 0,2 is outside");
  }
}
