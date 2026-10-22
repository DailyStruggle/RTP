package io.github.dailystruggle.rtp.common.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 teleport heatmap bins mock landings")
class BitLandingBinsTest {
    @Test
    void binsUseWorldCoordinatesAndKeepRepeatedLandings() {
        List<MockTeleportRecord> records =
                List.of(
                        landing(-10, -20),
                        landing(-10, -20),
                        landing(0, 0),
                        landing(10, 20));

        BitLandingBins.Bins bins = BitLandingBins.bin(records, 3, 3);

        assertEquals(3, bins.width());
        assertEquals(3, bins.height());
        assertEquals(4, bins.sampleCount());
        assertEquals(2.0, bins.countAt(0, 0));
        assertEquals(1.0, bins.countAt(1, 1));
        assertEquals(1.0, bins.countAt(2, 2));
    }

    @Test
    void identicalCoordinatesAreAssignedToOneStableBin() {
        BitLandingBins.Bins bins =
                BitLandingBins.bin(List.of(landing(7, -4), landing(7, -4)), 8, 6);

        assertEquals(2.0, bins.countAt(0, 0));
        assertEquals(2, bins.sampleCount());
    }

    private static MockTeleportRecord landing(int x, int z) {
        return new MockTeleportRecord(UUID.randomUUID(), "world", x, 64, z, "default", 3, 125, 1);
    }
}
