package io.github.dailystruggle.rtp.common.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ModdedBStatsSubmitter tests")
class ModdedBStatsSubmitterTest {

    @Test
    @DisplayName("Payload contains valid bStats v2 format and custom charts")
    void testBuildJsonPayload(@TempDir Path tempDir) {
        ModdedBStatsSubmitter submitter = new ModdedBStatsSubmitter("fabric", tempDir.toFile());
        String json = submitter.buildJsonPayload();

        assertNotNull(json);
        assertTrue(json.contains("\"serverUUID\":"), "Payload must contain serverUUID");
        assertTrue(json.contains("\"metricsVersion\":2"), "Payload must specify metricsVersion 2");
        assertTrue(json.contains("\"service\":{\"id\":30865}"), "Service ID must be 30865");
        assertTrue(json.contains("\"data\":{"), "Payload must contain data object");
        assertTrue(json.contains("\"customCharts\":["), "Payload must contain customCharts array");

        // Verify key charts
        assertTrue(json.contains("\"chartId\":\"platform\""));
        assertTrue(json.contains("\"value\":\"fabric\""));
        assertTrue(json.contains("\"chartId\":\"assembly_variant\""));
        assertTrue(json.contains("\"chartId\":\"tps_buckets\""));
        assertTrue(json.contains("\"chartId\":\"mspt_buckets\""));
        assertTrue(json.contains("\"chartId\":\"player_count_buckets\""));
        assertTrue(json.contains("\"chartId\":\"heap_pressure\""));
        assertTrue(json.contains("\"chartId\":\"region_count\""));
    }

    @Test
    @DisplayName("Opt-out via config file is respected")
    void testOptOutHandling(@TempDir Path tempDir) throws Exception {
        Path bstatsDir = tempDir.resolve("bStats");
        Files.createDirectories(bstatsDir);
        Path configFile = bstatsDir.resolve("config.txt");

        // Enabled
        Files.writeString(configFile, "enabled=true\nserverUuid=123e4567-e89b-12d3-a456-426614174000\n", StandardCharsets.UTF_8);
        ModdedBStatsSubmitter submitter = new ModdedBStatsSubmitter("fabric", tempDir.toFile());
        assertFalse(submitter.isOptedOut());

        // Disabled
        Files.writeString(configFile, "enabled=false\nserverUuid=123e4567-e89b-12d3-a456-426614174000\n", StandardCharsets.UTF_8);
        ModdedBStatsSubmitter optedOutSubmitter = new ModdedBStatsSubmitter("fabric", tempDir.toFile());
        assertTrue(optedOutSubmitter.isOptedOut());
    }

    @Test
    @DisplayName("Bucketisers provide anonymous bucket labels")
    void testBucketisers() {
        assertEquals("<10", ModdedBStatsSubmitter.tpsBucket(8.5));
        assertEquals("10-15", ModdedBStatsSubmitter.tpsBucket(12.0));
        assertEquals("15-19", ModdedBStatsSubmitter.tpsBucket(18.2));
        assertEquals("19-20+", ModdedBStatsSubmitter.tpsBucket(20.0));
        assertEquals("unknown", ModdedBStatsSubmitter.tpsBucket(Double.NaN));

        assertEquals("<25", ModdedBStatsSubmitter.msptBucket(15.0));
        assertEquals("25-50", ModdedBStatsSubmitter.msptBucket(35.0));
        assertEquals("50-100", ModdedBStatsSubmitter.msptBucket(60.0));
        assertEquals("100+", ModdedBStatsSubmitter.msptBucket(120.0));
        assertEquals("unknown", ModdedBStatsSubmitter.msptBucket(Double.NaN));

        assertEquals("0", ModdedBStatsSubmitter.playerCountBucket(0));
        assertEquals("1-5", ModdedBStatsSubmitter.playerCountBucket(4));
        assertEquals("6-20", ModdedBStatsSubmitter.playerCountBucket(15));
        assertEquals("21-50", ModdedBStatsSubmitter.playerCountBucket(40));
        assertEquals("51-100", ModdedBStatsSubmitter.playerCountBucket(75));
        assertEquals("101-250", ModdedBStatsSubmitter.playerCountBucket(150));
        assertEquals("250+", ModdedBStatsSubmitter.playerCountBucket(300));
        assertEquals("unknown", ModdedBStatsSubmitter.playerCountBucket(-1));

        assertEquals("<25", ModdedBStatsSubmitter.heapPressureBucket(200, 1000));
        assertEquals("25-50", ModdedBStatsSubmitter.heapPressureBucket(400, 1000));
        assertEquals("50-75", ModdedBStatsSubmitter.heapPressureBucket(650, 1000));
        assertEquals("75-90", ModdedBStatsSubmitter.heapPressureBucket(850, 1000));
        assertEquals("90+", ModdedBStatsSubmitter.heapPressureBucket(950, 1000));
        assertEquals("unknown", ModdedBStatsSubmitter.heapPressureBucket(100, 0));
    }

    @Test
    @DisplayName("Scaled TPS clamps to [0, 2500]")
    void testScaledTps() {
        assertEquals(2000, ModdedBStatsSubmitter.scaledTps(20.0));
        assertEquals(1985, ModdedBStatsSubmitter.scaledTps(19.85));
        assertEquals(0, ModdedBStatsSubmitter.scaledTps(Double.NaN));
        assertEquals(0, ModdedBStatsSubmitter.scaledTps(-5.0));
        assertEquals(2500, ModdedBStatsSubmitter.scaledTps(30.0));
    }
}
