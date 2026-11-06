package io.github.dailystruggle.rtp.common.metrics.bstats;

import io.github.dailystruggle.bstats.api.BStatsPlatform;
import io.github.dailystruggle.bstats.api.BStatsScheduler;
import io.github.dailystruggle.bstats.api.BStatsService;
import io.github.dailystruggle.bstats.api.CustomChart;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single RTP bStats entry point: every platform reports to the one service
 * with the shared catalogue; only the {@link RtpBStatsCatalogue.Host} differs.
 */
class RtpBStatsTest {

    /** Records every scheduler call; nothing is executed. */
    private static final class RecordingScheduler implements RTPScheduler {
        final List<String> calls = new ArrayList<>();
        final Object timerHandle = new Object();
        Object cancelled;

        @Override public TrackedRTPTask runTaskAsynchronously(Runnable task) { calls.add("async"); return null; }
        @Override public void runTask(Runnable task) { calls.add("main"); }
        @Override public void runTaskLater(Runnable task, long delay) { calls.add("later:" + delay); }
        @Override public Object runTaskTimer(Runnable task, long delay, long period) { return new Object(); }
        @Override public Object runTaskTimerAsynchronously(Runnable task, long delay, long period) {
            calls.add("timer:" + period);
            return timerHandle;
        }
        @Override public void cancelTask(Object task) { cancelled = task; }
        @Override public void runTaskForPlayer(RTPPlayer player, RTPRunnable task, long delayTicks) { }
        @Override public void runTask(RTPLocation location, Runnable task) { }
        @Override public void runTask(RTPWorld<?> world, int cx, int cz, Runnable task) { }
        @Override public Object runTaskTimer(RTPWorld<?> world, int cx, int cz, Runnable task, long delay, long period) { return null; }
        @Override public void runTaskLater(RTPWorld<?> world, int cx, int cz, Runnable task, long delay) { }
    }

    @TempDir
    Path tmp;

    private RTPScheduler savedScheduler;
    private File pluginDir;

    @BeforeEach
    void setUp() {
        savedScheduler = RTPRuntime.scheduler();
        RtpBStats.shutdown();
        pluginDir = tmp.resolve("config").resolve("RTP").toFile();
    }

    @AfterEach
    void tearDown() {
        RtpBStats.shutdown();
        RTPRuntime.setScheduler(savedScheduler);
    }

    @Test
    @DisplayName("Loader host reports to the shared service with the full catalogue")
    void loaderPayload() {
        BStatsService service = RtpBStats.create(RtpBStatsCatalogue.Host.of("Fabric"), RtpBStats.SERVICE_ID,
                "full", pluginDir, new RecordingScheduler());

        assertEquals(BStatsPlatform.BUKKIT, service.platform());
        assertEquals(30865, service.serviceId());
        assertTrue(Files.isRegularFile(tmp.resolve("config").resolve("bStats").resolve("config.txt")),
                "default host creates the config.txt format");

        String json = service.buildPayload();
        assertTrue(json.contains("\"bukkitName\":\"Fabric\""), json);
        assertTrue(json.contains("\"bukkitVersion\":\"Fabric"), json);
        assertTrue(json.contains("\"playerAmount\":"), json);
        assertFalse(json.contains("\"onlineMode\""), "unknown onlineMode is omitted, not guessed");
        assertTrue(json.contains("\"id\":30865"), json);
        assertTrue(json.contains("{\"chartId\":\"platform\",\"data\":{\"value\":\"fabric\"}}"), json);
        assertTrue(json.contains("{\"chartId\":\"assembly_variant\",\"data\":{\"value\":\"full\"}}"), json);

        Set<String> ids = service.charts().stream().map(CustomChart::getChartId).collect(Collectors.toSet());
        assertTrue(ids.contains(BStatsChartIds.RTP_SYNC_COST_PER_RTP));
        assertTrue(ids.contains(BStatsChartIds.MSPT_P99_BY_PLATFORM));
        assertFalse(ids.contains(BStatsChartIds.ADDONS_LOADED), "default host has no addon whitelist");
    }

    @Test
    @DisplayName("Display names and blank platform fallback")
    void displayNames() {
        assertEquals("NeoForge", RtpBStatsCatalogue.displayName("neoforge"));
        assertEquals("Fabric", RtpBStatsCatalogue.displayName("fabric"));
        assertEquals("unknown", RtpBStatsCatalogue.displayName(" "));
        assertEquals("unknown", RtpBStatsCatalogue.Host.of(null).platform());
        assertEquals("NeoForge", RtpBStatsCatalogue.Host.of("neoforge").serverInfo().name());
        assertFalse(RtpBStatsCatalogue.Host.of("neoforge").collectOnMainThread());
    }

    @Test
    @DisplayName("start needs a scheduler, is idempotent, and shutdown cancels and allows restart")
    void lifecycle() {
        RTPRuntime.setScheduler(null);
        assertNull(RtpBStats.start(RtpBStatsCatalogue.Host.of("fabric"), RtpBStats.SERVICE_ID, "full", pluginDir));
        assertNull(RtpBStats.service());

        RecordingScheduler sched = new RecordingScheduler();
        RTPRuntime.setScheduler(sched);
        BStatsService first = RtpBStats.start(RtpBStatsCatalogue.Host.of("fabric"), RtpBStats.SERVICE_ID, "full", pluginDir);
        assertTrue(first.isRunning());
        assertSame(first, RtpBStats.service());
        assertSame(first, RtpBStats.start(RtpBStatsCatalogue.Host.of("neoforge"), RtpBStats.LITE_SERVICE_ID, "lite", pluginDir));
        assertTrue(sched.calls.contains("timer:36000"), sched.calls.toString());

        RtpBStats.shutdown();
        assertFalse(first.isRunning());
        assertSame(sched.timerHandle, sched.cancelled);
        assertNull(RtpBStats.service());
    }

    @Test
    @DisplayName("Scheduler adapter delegates to RTPScheduler")
    void schedulerAdapter() {
        RecordingScheduler rtp = new RecordingScheduler();
        BStatsScheduler s = RtpBStats.scheduler(rtp);
        s.runOnMainThread(() -> { });
        s.runAsync(() -> { });
        s.runLater(() -> { }, 5L);
        assertSame(rtp.timerHandle, s.runAsyncTimer(() -> { }, 1L, 2L));
        s.cancel("h");
        assertEquals(List.of("main", "async", "later:5", "timer:2"), rtp.calls);
        assertEquals("h", rtp.cancelled);
    }

    @Test
    @DisplayName("Manifest distribution value maps free/lite to 12277 and supporter/default to 30865")
    void distributionValueMapping() {
        assertEquals(RtpBStats.LITE_SERVICE_ID, RtpBStats.resolveServiceIdFromDistribution("free"));
        assertEquals(RtpBStats.LITE_SERVICE_ID, RtpBStats.resolveServiceIdFromDistribution(" FREE "));
        assertEquals(RtpBStats.LITE_SERVICE_ID, RtpBStats.resolveServiceIdFromDistribution("lite"));
        assertEquals("lite", RtpBStats.resolveVariantFromDistribution("free"));
        assertEquals("lite", RtpBStats.resolveVariantFromDistribution("lite"));

        assertEquals(RtpBStats.SERVICE_ID, RtpBStats.resolveServiceIdFromDistribution("supporter"));
        assertEquals(RtpBStats.SERVICE_ID, RtpBStats.resolveServiceIdFromDistribution("full"));
        assertEquals(RtpBStats.SERVICE_ID, RtpBStats.resolveServiceIdFromDistribution(null));
        assertEquals(RtpBStats.SERVICE_ID, RtpBStats.resolveServiceIdFromDistribution(""));
        assertEquals("full", RtpBStats.resolveVariantFromDistribution("supporter"));
        assertEquals("full", RtpBStats.resolveVariantFromDistribution(null));

        // Unpacked test classes without LeafRTP-Distribution default to supporter / 30865
        assertEquals(RtpBStats.SERVICE_ID, RtpBStats.resolveServiceId(RtpBStatsTest.class));
        assertEquals("full", RtpBStats.resolveAssemblyVariant(RtpBStatsTest.class));
    }

    @Test
    @DisplayName("resolveServiceId reads LeafRTP-Distribution from anchor class jar manifest")
    void resolvesDistributionFromAnchorJarManifest() throws Exception {
        String entryPath = RtpBStatsTest.class.getName().replace('.', '/') + ".class";
        byte[] classBytes;
        try (java.io.InputStream in = RtpBStatsTest.class.getClassLoader().getResourceAsStream(entryPath)) {
            classBytes = in.readAllBytes();
        }

        File freeJar = writeJarWithManifestDistribution("free", entryPath, classBytes);
        try (java.net.URLClassLoader cl = new java.net.URLClassLoader(
                new java.net.URL[]{freeJar.toURI().toURL()}, null)) {
            Class<?> anchor = Class.forName(RtpBStatsTest.class.getName(), false, cl);
            assertEquals(RtpBStats.LITE_SERVICE_ID, RtpBStats.resolveServiceId(anchor));
            assertEquals("lite", RtpBStats.resolveAssemblyVariant(anchor));
        }

        File supporterJar = writeJarWithManifestDistribution("supporter", entryPath, classBytes);
        try (java.net.URLClassLoader cl = new java.net.URLClassLoader(
                new java.net.URL[]{supporterJar.toURI().toURL()}, null)) {
            Class<?> anchor = Class.forName(RtpBStatsTest.class.getName(), false, cl);
            assertEquals(RtpBStats.SERVICE_ID, RtpBStats.resolveServiceId(anchor));
            assertEquals("full", RtpBStats.resolveAssemblyVariant(anchor));
        }
    }

    private File writeJarWithManifestDistribution(String distribution, String classEntry, byte[] classBytes)
            throws Exception {
        pluginDir.mkdirs();
        File jarFile = new File(pluginDir, "test-" + distribution + ".jar");
        java.util.jar.Manifest mf = new java.util.jar.Manifest();
        mf.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue(RtpBStats.MANIFEST_DISTRIBUTION_ATTR, distribution);
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
                new java.io.FileOutputStream(jarFile), mf)) {
            jos.putNextEntry(new java.util.jar.JarEntry(classEntry));
            jos.write(classBytes);
            jos.closeEntry();
        }
        return jarFile;
    }
}
