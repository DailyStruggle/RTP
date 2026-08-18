package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.*;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ConfigsReloadTest {
    private RTP rtp;
    private File tempDir;

    @BeforeEach
    public void setUp() {
        tempDir = new File("target/test-configs-reload");
        if (!tempDir.exists()) tempDir.mkdirs();
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir);
        // Requires empty world list.
        accessor.clearWorlds();
        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();
        rtp = new RTP();
    }

    @Test
    public void testConcurrentReloadAndRead() throws InterruptedException {
        // 1. Construct an integration test that initializes a mock Configs instance with 10 dummy regions
        Configs configs = RTP.configs;

        // Add dummy regions to selectionAPI.permRegionLookup
        for (int i = 0; i < 10; i++) {
            String name = "region" + i;
            Region mockRegion = mock(Region.class);
            mockRegion.name = name;
            RTP.selectionAPI.permRegionLookup.put(name, mockRegion);
        }

        // 2. Simulate a rapid succession of reload() calls while a separate thread pool attempts to read properties
        int numThreads = 10;
        int numIterations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads + 1);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads + 1);
        AtomicInteger npeCount = new AtomicInteger(0);
        AtomicInteger totalReadCount = new AtomicInteger(0);

        // Reload thread
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < numIterations; i++) {
                    configs.reload();
                    Thread.yield();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        });

        // Reader threads
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < numIterations * 10; j++) {
                        totalReadCount.incrementAndGet();
                        try {
                            configs.getWorldParserValue("world", WorldKeys.region);
                        } catch (NullPointerException e) {
                            npeCount.incrementAndGet();
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(0, npeCount.get(), "Expected zero NPEs during rapid reload with atomic swapping");
    }
}
