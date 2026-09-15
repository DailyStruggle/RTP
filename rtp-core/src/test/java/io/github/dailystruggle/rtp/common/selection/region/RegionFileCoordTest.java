package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("REQ-RTP-C-001: RegionFileCoord Unit Tests")
class RegionFileCoordTest {

    @Test
    @DisplayName("Derives region-file coordinates from block coords via of()")
    void testOfBlockCoords() {
        // 512 blocks per region file (rx = blockX >> 9, rz = blockZ >> 9)
        // block at (1000, 64, 2000) in world "world_nether"
        // 1000 >> 9 = 1, 2000 >> 9 = 3
        RTPCoords coords = new RTPCoords("world_nether", 1000, 64, 2000);
        RegionFileCoord regionCoord = RegionFileCoord.of(coords);

        assertNotNull(regionCoord);
        assertEquals("world_nether", regionCoord.worldName());
        assertEquals(1, regionCoord.rx());
        assertEquals(3, regionCoord.rz());
    }

    @Test
    @DisplayName("Derives correct negative region-file coordinates")
    void testOfNegativeCoordinates() {
        // -1 >> 9 = -1, -512 >> 9 = -1, -513 >> 9 = -2
        RTPCoords coords1 = new RTPCoords("world", -1, 64, -512);
        RegionFileCoord regionCoord1 = RegionFileCoord.of(coords1);
        assertEquals(-1, regionCoord1.rx());
        assertEquals(-1, regionCoord1.rz());

        RTPCoords coords2 = new RTPCoords("world", -513, 64, -1025);
        RegionFileCoord regionCoord2 = RegionFileCoord.of(coords2);
        assertEquals(-2, regionCoord2.rx());
        assertEquals(-3, regionCoord2.rz());
    }

    @Test
    @DisplayName("Validates record equality and hash code")
    void testRecordEqualityAndHashCode() {
        RegionFileCoord coordA = new RegionFileCoord("world", 5, -2);
        RegionFileCoord coordB = new RegionFileCoord("world", 5, -2);
        RegionFileCoord coordC = new RegionFileCoord("world", 5, -1);
        RegionFileCoord coordDiffWorld = new RegionFileCoord("world_nether", 5, -2);

        assertEquals(coordA, coordB);
        assertEquals(coordA.hashCode(), coordB.hashCode());
        assertNotEquals(coordA, coordC);
        assertNotEquals(coordA, coordDiffWorld);
        assertEquals("RegionFileCoord[worldName=world, rx=5, rz=-2]", coordA.toString());
    }
}
