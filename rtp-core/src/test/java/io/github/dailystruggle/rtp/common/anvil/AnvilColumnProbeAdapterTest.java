package io.github.dailystruggle.rtp.common.anvil;

import io.github.dailystruggle.rtp.anvil.ColumnProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AnvilColumnProbeAdapter delegation tests")
class AnvilColumnProbeAdapterTest {

    @Test
    void testDelegation() {
        ColumnProbe probe = new ColumnProbe(-64, 320, 75, Collections.emptyList(), Collections.emptyList());

        AnvilColumnProbeAdapter adapter = new AnvilColumnProbeAdapter(probe, 10, 20);
        assertEquals(10, adapter.chunkX());
        assertEquals(20, adapter.chunkZ());
        assertEquals(-64, adapter.minY());
        assertEquals(320, adapter.maxY());
        assertTrue(adapter.heightmapTopY().isPresent());
        assertEquals(75, adapter.heightmapTopY().getAsInt());
        assertNull(adapter.blockAt(60));
        assertNull(adapter.blockAt(2, 3, 60));
        assertNull(adapter.biomeAt(60));
        assertFalse(adapter.isAirAt(70));
        assertFalse(adapter.isAirAt(2, 3, 70));

        ColumnProbe probeNoHeightmap = new ColumnProbe(-64, 320, Integer.MIN_VALUE, Collections.emptyList(), Collections.emptyList());
        AnvilColumnProbeAdapter adapterNoHeightmap = new AnvilColumnProbeAdapter(probeNoHeightmap, 10, 20);
        assertFalse(adapterNoHeightmap.heightmapTopY().isPresent());
    }
}
