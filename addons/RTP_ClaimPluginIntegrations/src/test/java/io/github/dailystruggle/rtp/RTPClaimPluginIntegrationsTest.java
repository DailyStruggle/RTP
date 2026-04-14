package io.github.dailystruggle.rtp;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.*;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class RTPClaimPluginIntegrationsTest {
    @Test
    public void testGlobalVerifierDoesNotBlock() {

        try (MockedStatic<RTP> rtpStatic = mockStatic(RTP.class)) {
            // 1. Setup Mock Environment
            RTPServerAccessor serverAccessor = mock(RTPServerAccessor.class);
            RTPScheduler scheduler = mock(RTPScheduler.class);
            RTPTaskPipe miscAsyncTasks = mock(RTPTaskPipe.class);

            RTP.serverAccessor = serverAccessor;
            RTP.scheduler = scheduler;
            RTP.configs = mock(Configs.class);
            @SuppressWarnings("unchecked")
            ConfigParser<PerformanceKeys> perfParser = mock(ConfigParser.class);
            when(perfParser.getNumber(any(), any())).thenReturn(0L);
            when(RTP.configs.getParser(PerformanceKeys.class)).thenReturn(perfParser);

            // Mock SafetyKeys
            @SuppressWarnings("unchecked")
            ConfigParser<SafetyKeys> safetyParser = mock(ConfigParser.class);
            when(safetyParser.getConfigValue(any(), any())).thenReturn(false);
            when(safetyParser.getNumber(any(), any())).thenReturn(0);
            when(RTP.configs.getParser(SafetyKeys.class)).thenReturn(safetyParser);

            // Mock LoggingKeys
            @SuppressWarnings("unchecked")
            ConfigParser<LoggingKeys> loggingParser = mock(ConfigParser.class);
            when(loggingParser.getConfigValue(any(), any())).thenReturn(false);
            when(RTP.configs.getParser(LoggingKeys.class)).thenReturn(loggingParser);

            RTP rtp = mock(RTP.class);
            rtp.miscAsyncTasks = miscAsyncTasks;
            rtpStatic.when(RTP::getInstance).thenReturn(rtp);

            // 2. Mock an async verifier with an artificial sleep
            GlobalRegionVerifiers.clearGlobalRegionVerifiers();
            GlobalRegionVerifiers.addGlobalRegionVerifierAsync(loc -> {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    return true;
                });
            });

            // 3. Prepare Region and Task
            Region region = mock(Region.class);
            RTPWorld world = mock(RTPWorld.class);
            when(region.getWorld()).thenReturn(world);
            RegionSettings settings = mock(RegionSettings.class);
            when(settings.activeChunkCap()).thenReturn(1);
            when(region.getSettings()).thenReturn(settings);
            region.queueManager = new RegionQueueManager(region);
            region.inFlightCalculations = new java.util.concurrent.atomic.AtomicInteger(0);

            RegionCacheTask task = new RegionCacheTask(region, null, Long.MAX_VALUE);

            // 4. Measure time of task execution
            AtomicLong duration = new AtomicLong();
            long start = System.currentTimeMillis();

            // This should return immediately because LocationGenerator.getLocation is called via CompletableFuture.supplyAsync
            task.run();

            long end = System.currentTimeMillis();
            duration.set(end - start);

            // 5. Assert it didn't block for 500ms
            assertTrue(duration.get() < 200, "Calling thread was blocked for " + duration.get() + "ms");

            // Also verify that it was added to the async task pipe
//            verify(miscAsyncTasks, atLeastOnce()).add(any(Runnable.class));
        }
    }
}
