package io.github.dailystruggle.helpers.stresstestrtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpuSamplerTest {

    private static final long MS = 1_000_000L;

    /** Burns CPU on the calling thread until it has used {@code ns} more. */
    private static void spin(long ns) {
        ThreadMXBean b = ManagementFactory.getThreadMXBean();
        long end = b.getCurrentThreadCpuTime() + ns;
        long sink = 0L;
        while (b.getCurrentThreadCpuTime() < end) {
            for (int i = 0; i < 10_000; i++) sink += i ^ sink;
        }
        if (sink == 42L) System.out.print("");
    }

    @Test
    @DisplayName("threads are grouped by CraftBukkit, Paper, Folia and vanilla names")
    void classifiesThreadNames() {
        assertEquals(CpuSampler.Group.SERVER, CpuSampler.classify("Server thread", false));
        assertEquals(CpuSampler.Group.SERVER, CpuSampler.classify("anything", true));
        assertEquals(CpuSampler.Group.REGION, CpuSampler.classify("Folia Region Scheduler Thread #2", false));
        assertEquals(CpuSampler.Group.SCHEDULER, CpuSampler.classify("Craft Scheduler Thread - 12 - RTP", false));
        assertEquals(CpuSampler.Group.ASYNC_SCHEDULER, CpuSampler.classify("Folia Async Scheduler Thread #0", false));
        assertEquals(CpuSampler.Group.CHUNK_SYSTEM, CpuSampler.classify("Moonrise Chunk System Worker #3", false));
        assertEquals(CpuSampler.Group.CHUNK_SYSTEM, CpuSampler.classify("Paper Common Worker #1", false));
        assertEquals(CpuSampler.Group.CHUNK_SYSTEM, CpuSampler.classify("Worker-Main-4", false));
        assertEquals(CpuSampler.Group.CHUNK_SYSTEM, CpuSampler.classify("IO-Worker-9", false));
        assertEquals(CpuSampler.Group.NETWORK, CpuSampler.classify("Netty Epoll Server IO #1", false));
        assertEquals(CpuSampler.Group.OTHER, CpuSampler.classify("Paper Watchdog Thread", false));
        assertEquals(CpuSampler.Group.OTHER, CpuSampler.classify(null, false));
    }

    @Test
    @DisplayName("Paper scheduler worker names yield the plugin; bare workers are idle")
    void parsesSchedulerPlugin() {
        assertEquals("RTP", CpuSampler.schedulerPlugin("Craft Scheduler Thread - 12 - RTP"));
        assertEquals("Better RTP", CpuSampler.schedulerPlugin("Craft Scheduler Thread - 3 - Better RTP"));
        assertEquals(CpuSampler.IDLE_KEY, CpuSampler.schedulerPlugin("Craft Scheduler Thread - 12"));
        assertEquals(CpuSampler.IDLE_KEY, CpuSampler.schedulerPlugin("Craft Scheduler Thread"));
        assertEquals("Worker-Main-#", CpuSampler.normalizeName("Worker-Main-17"));
    }

    @Test
    @DisplayName("a thread born after the previous sample is billed its whole CPU")
    void newThreadBilledInFull() throws Exception {
        CpuSampler s = new CpuSampler();
        CpuSampler.Breakdown start = s.sampleBreakdown();
        CountDownLatch spun = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            spin(150 * MS);
            spun.countDown();
            try { release.await(); } catch (InterruptedException ignored) { }
        }, "Craft Scheduler Thread - 7 - TestPlugin");
        t.start();
        try {
            assertTrue(spun.await(30, TimeUnit.SECONDS));
            CpuSampler.Breakdown d = s.sampleBreakdown().minus(start);
            long plugin = d.schedulerByPluginNs.getOrDefault("TestPlugin", 0L);
            assertTrue(plugin >= 140 * MS, "plugin ns " + plugin);
            assertTrue(d.groupNs(CpuSampler.Group.SCHEDULER) >= plugin);
            assertEquals(1L, d.samples);
        } finally {
            release.countDown();
            t.join();
        }
    }

    @Test
    @DisplayName("a thread alive before the first sample is billed only CPU used after it")
    void existingThreadBilledFromPrime() throws Exception {
        CountDownLatch spun = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            spin(300 * MS);
            spun.countDown();
            try {
                go.await();
                spin(100 * MS);
                done.countDown();
                release.await();
            } catch (InterruptedException ignored) { }
        }, "Netty Server IO #77");
        t.start();
        try {
            assertTrue(spun.await(30, TimeUnit.SECONDS));
            CpuSampler s = new CpuSampler();
            CpuSampler.Breakdown start = s.sampleBreakdown();
            go.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            long net = s.sampleBreakdown().minus(start).groupNs(CpuSampler.Group.NETWORK);
            assertTrue(net >= 90 * MS && net < 250 * MS, "network ns " + net);
        } finally {
            go.countDown();
            release.countDown();
            t.join();
        }
    }

    @Test
    @DisplayName("summary lists entries by descending CPU in ms and honours the limit")
    void summaryFormat() {
        Map<String, Long> m = Map.of("RTP", 900 * MS, "Other=x", 50 * MS, "tiny", 10L);
        assertEquals("RTP=900;Other_x=50", CpuSampler.Breakdown.summary(m, 0));
        assertEquals("RTP=900", CpuSampler.Breakdown.summary(m, 1));
    }

    @Test
    @DisplayName("chunk-load cost key follows the platform family with no Spigot fallback")
    void chunkCostKeyPerPlatform() {
        assertEquals("chunk-load-cost-us-folia", StressTestRTPPlugin.chunkLoadCostKey(true, true));
        assertEquals("chunk-load-cost-us-paper", StressTestRTPPlugin.chunkLoadCostKey(false, true));
        assertEquals("chunk-load-cost-us", StressTestRTPPlugin.chunkLoadCostKey(false, false));
    }
}
