package io.github.dailystruggle.rtp.common.selection.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.metrics.RtpOutcomeStats;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegionBacklogOutcomeStatsTest {

    private MockRTPWorld world;
    private File tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("rtp-test-backlog-stats").toFile();
        var accessor = RTPTestSetup.install(tempDir);
        accessor.setLocationGenerator(new LocationGenerator());
        world = new MockRTPWorld("test_world");
        accessor.addWorld(world);
        RtpOutcomeStats.GLOBAL.reset();
    }

    @AfterEach
    void tearDown() {
        if (RTPAPI.hooks() != null) {
            RTPAPI.hooks().anvilPrefilter().clear();
        }
    }

    @Test
    @DisplayName("L3 backlog prefilter rejection records failure and L2 promotion records success")
    void l3BacklogRecordsRejectionsAndPromotions() {
        RegionSettings settings = new RegionSettings(
                "test_region",
                world,
                new Square(),
                null,
                false, // worldBorderOverride
                false, // requirePermission
                5,     // cacheCap (L2)
                5,     // backlogCacheCap (L3)
                0L,    // networkReserveSize
                10,    // activeChunkCap
                0.0,   // price
                1L,    // spatialResolution
                "",    // override
                false  // detailedRegionInit
        );
        Region region = new Region("test_region", settings);

        // Bind Anvil prefilter provider: alternate REJECT and ACCEPT
        final int[] calls = new int[1];
        RTPAPI.hooks().anvilPrefilter().bind((w, cx, cz) -> {
            int c = calls[0]++;
            return (c % 2 == 0)
                    ? AnvilPrefilterRegistry.Provider.Decision.REJECT
                    : AnvilPrefilterRegistry.Provider.Decision.ACCEPT;
        });

        long initialFailures = RtpOutcomeStats.GLOBAL.failureCount(LocationGenerator.FailTypes.biome);
        long initialSuccesses = RtpOutcomeStats.GLOBAL.successCount();

        // Execute pulse to fill L3 and classify
        region.execute(TimeUnit.MILLISECONDS.toNanos(50));

        // Process another pulse to verify promotion after classification
        region.execute(TimeUnit.MILLISECONDS.toNanos(50));

        long deltaFailures = RtpOutcomeStats.GLOBAL.failureCount(LocationGenerator.FailTypes.biome) - initialFailures;
        long deltaSuccesses = RtpOutcomeStats.GLOBAL.successCount() - initialSuccesses;

        assertTrue(deltaFailures > 0, "failures must be recorded for Anvil rejections");
        assertTrue(deltaSuccesses > 0, "successes must be recorded for L2 promotions");
    }
}
