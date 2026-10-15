package io.github.dailystruggle.rtp.fabric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("REQ-RTP-S-006: Fabric version adapter resolution")
public class FabricVersionResolutionTest {

    @Test
    @DisplayName("26.4 versions resolve to V26_4_R1FabricVersionAdapter")
    public void test264Resolution() {
        assertEquals("io.github.dailystruggle.rtp.fabric.v26_4_R1.V26_4_R1FabricVersionAdapter",
                RTPFabricMod.adapterFqnFor("26.4"));
        assertEquals("io.github.dailystruggle.rtp.fabric.v26_4_R1.V26_4_R1FabricVersionAdapter",
                RTPFabricMod.adapterFqnFor("26.4.1"));
        assertEquals("io.github.dailystruggle.rtp.fabric.v26_4_R1.V26_4_R1FabricVersionAdapter",
                RTPFabricMod.adapterFqnFor("26.4-pre1"));
    }

    @Test
    @DisplayName("Prior supported lines resolve to their respective adapters")
    public void testPriorSupportedLines() {
        assertEquals("io.github.dailystruggle.rtp.fabric.v26_3_R1.V26_3_R1FabricVersionAdapter",
                RTPFabricMod.adapterFqnFor("26.3"));
        assertEquals("io.github.dailystruggle.rtp.fabric.v26_2_R1.V26_2_R1FabricVersionAdapter",
                RTPFabricMod.adapterFqnFor("26.2"));
        assertEquals("io.github.dailystruggle.rtp.fabric.v26_1_R1.V26_1_R1FabricVersionAdapter",
                RTPFabricMod.adapterFqnFor("26.1"));
        assertEquals("io.github.dailystruggle.rtp.fabric.v1_21_R11.V1_21_R11FabricVersionAdapter",
                RTPFabricMod.adapterFqnFor("1.21.11"));
        assertEquals("io.github.dailystruggle.rtp.fabric.v1_21_R5.V1_21_R5FabricVersionAdapter",
                RTPFabricMod.adapterFqnFor("1.21.5"));
        assertEquals("io.github.dailystruggle.rtp.fabric.v1_21_R1.V1_21_R1FabricVersionAdapter",
                RTPFabricMod.adapterFqnFor("1.21.1"));
        assertEquals("io.github.dailystruggle.rtp.fabric.v1_20_R1.V1_20_R1FabricVersionAdapter",
                RTPFabricMod.adapterFqnFor("1.20.4"));
    }

    @Test
    @DisplayName("Unsupported versions return null")
    public void testUnsupportedVersions() {
        assertNull(RTPFabricMod.adapterFqnFor(null));
        assertNull(RTPFabricMod.adapterFqnFor("1.19.4"));
        assertNull(RTPFabricMod.adapterFqnFor("25.0"));
    }
}
