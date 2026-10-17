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
import io.github.dailystruggle.rtp.common.RTP;
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
        savedScheduler = RTP.scheduler;
        RtpBStats.shutdown();
        pluginDir = tmp.resolve("config").resolve("RTP").toFile();
    }

    @AfterEach
    void tearDown() {
        RtpBStats.shutdown();
        RTP.scheduler = savedScheduler;
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
        RTP.scheduler = null;
        assertNull(RtpBStats.start(RtpBStatsCatalogue.Host.of("fabric"), RtpBStats.SERVICE_ID, "full", pluginDir));
        assertNull(RtpBStats.service());

        RecordingScheduler sched = new RecordingScheduler();
        RTP.scheduler = sched;
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
}
