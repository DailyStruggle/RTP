package io.github.dailystruggle.rtp.common.selection.region;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.hooks.AnvilPrefilterRegistry;
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
    @DisplayName("Backlog prefilter rejection records failure and cold promotion records success")
    void backlogRecordsRejectionsAndPromotions() throws InterruptedException {
        RegionSettings settings = new RegionSettings(
                "test_region",
                world,
                new Square(),
                null,
                false, // worldBorderOverride
                false, // requirePermission
                5,     // cacheCap (cold)
                5,     // backlogCacheCap
                0L,    // networkReserveSize
                10,    // activeChunkCap
                0.0,   // price
                1L,    // spatialResolution
                "",    // override
                false  // detailedRegionInit
        );
        Region region = new Region("test_region", settings);

        // Bind Anvil prefilter provider: alternate REJECT and ACCEPT (called from AnvilIoPool threads)
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        RTPAPI.hooks().anvilPrefilter().bind((w, cx, cz) -> {
            int c = calls.getAndIncrement();
            return (c % 2 == 0)
                    ? AnvilPrefilterRegistry.Provider.Decision.REJECT
                    : AnvilPrefilterRegistry.Provider.Decision.ACCEPT;
        });

        long initialFailures = RtpOutcomeStats.GLOBAL.failureCount(LocationGenerator.FailTypes.biome);
        long initialSuccesses = RtpOutcomeStats.GLOBAL.successCount();

        // First pulse fills the backlog and submits bin batches; later pulses apply them and promote.
        long deltaFailures = 0L;
        long deltaSuccesses = 0L;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            region.execute(TimeUnit.MILLISECONDS.toNanos(50));
            deltaFailures = RtpOutcomeStats.GLOBAL.failureCount(LocationGenerator.FailTypes.biome) - initialFailures;
            deltaSuccesses = RtpOutcomeStats.GLOBAL.successCount() - initialSuccesses;
            if (deltaFailures > 0 && deltaSuccesses > 0) break;
            Thread.sleep(5L);
        } while (System.nanoTime() < deadline);

        assertTrue(deltaFailures > 0, "failures must be recorded for Anvil rejections");
        assertTrue(deltaSuccesses > 0, "successes must be recorded for cold promotions");
    }
}
