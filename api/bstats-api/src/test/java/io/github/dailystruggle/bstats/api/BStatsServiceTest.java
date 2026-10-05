package io.github.dailystruggle.bstats.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire contract and lifecycle of the bStats client: payload shape and endpoint
 * match the upstream {@code MetricsBase}, cadence uses the upstream jitter, the
 * opt-out is honoured at start and before every send, and all work is routed
 * through the host {@link BStatsScheduler}.
 */
class BStatsServiceTest {

    /** Immediate tasks run inline; delayed / timer tasks are captured. */
    private static class RecordingScheduler implements BStatsScheduler {
        final List<String> calls = new ArrayList<>();
        Runnable later;
        long laterDelay = -1L;
        Runnable timer;
        long timerDelay = -1L;
        long timerPeriod = -1L;
        final Object timerHandle = new Object();
        Object cancelled;
        boolean failCancel;

        @Override public void runOnMainThread(Runnable task) { calls.add("main"); task.run(); }
        @Override public void runAsync(Runnable task) { calls.add("async"); task.run(); }
        @Override public void runLater(Runnable task, long delay) { later = task; laterDelay = delay; }
        @Override public Object runAsyncTimer(Runnable task, long delay, long period) {
            timer = task;
            timerDelay = delay;
            timerPeriod = period;
            return timerHandle;
        }
        @Override public void cancel(Object task) {
            cancelled = task;
            if (failCancel) throw new IllegalStateException("cancel");
        }
    }

    /** Captures posts and returns a fixed status. */
    private static final class CapturingTransport implements BStatsService.Transport {
        final List<String> urls = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        int status = 200;

        @Override
        public int post(String url, byte[] gzippedJson) throws IOException {
            urls.add(url);
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzippedJson))) {
                bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            return status;
        }
    }

    @TempDir
    Path tmp;

    private File pluginDir;
    private CapturingTransport transport;
    private RecordingScheduler sched;
    private final List<String> logged = new ArrayList<>();

    @BeforeEach
    void setUp() {
        pluginDir = tmp.resolve("plugins").resolve("RTP").toFile();
        assertTrue(pluginDir.mkdirs());
        transport = new CapturingTransport();
        sched = new RecordingScheduler();
    }

    private BStatsService.Builder builder() {
        ServerInfo info = ServerInfo.of(7, "Paper", "git-Paper-1 (MC: 1.21.4)").withOnlineMode(true);
        return BStatsService.builder(BStatsPlatform.BUKKIT, 30865)
                .pluginDirectory(pluginDir)
                .pluginVersion(() -> "3.4.0")
                .serverInfo(() -> info)
                .scheduler(sched)
                .log((level, msg) -> logged.add(level + " " + msg))
                .transport(transport)
                .random(() -> 0.5);
    }

    private void writeYaml(String body) throws IOException {
        Path dir = tmp.resolve("plugins").resolve("bStats");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yml"), body, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Payload matches the upstream MetricsBase shape")
    void payloadShape() throws IOException {
        writeYaml("enabled: true\nserverUuid: \"11111111-2222-3333-4444-555555555555\"\n");
        BStatsService s = builder().build();
        s.addCustomChart(new SimplePie("platform", () -> "paper"));
        s.addCustomChart(new SingleLineChart("region_count", () -> 3));

        String json = s.buildPayload();
        assertTrue(json.startsWith("{\"playerAmount\":7,\"onlineMode\":1,\"bukkitVersion\":\"git-Paper-1 (MC: 1.21.4)\",\"bukkitName\":\"Paper\","), json);
        assertTrue(json.contains("\"javaVersion\":"));
        assertTrue(json.contains("\"osName\":"));
        assertTrue(json.contains("\"osArch\":"));
        assertTrue(json.contains("\"osVersion\":"));
        assertTrue(json.contains("\"coreCount\":"));
        assertTrue(json.contains("\"service\":{\"pluginVersion\":\"3.4.0\",\"id\":30865,\"customCharts\":["
                + "{\"chartId\":\"platform\",\"data\":{\"value\":\"paper\"}},"
                + "{\"chartId\":\"region_count\",\"data\":{\"value\":3}}]}"), json);
        assertTrue(json.endsWith(",\"serverUUID\":\"11111111-2222-3333-4444-555555555555\",\"metricsVersion\":\""
                + BStatsService.METRICS_VERSION + "\"}"), json);
        assertFalse(json.contains("\"data\":{\"osName\""), "no legacy data wrapper");
        assertEquals(BStatsPlatform.BUKKIT, s.platform());
        assertEquals(30865, s.serviceId());
        assertEquals(2, s.charts().size());
    }

    @Test
    @DisplayName("Failing, empty and throwing suppliers drop only their own chart")
    void failSoftCharts() {
        BStatsService s = builder()
                .serverInfo(() -> { throw new IllegalStateException("boom"); })
                .pluginVersion(() -> { throw new IllegalStateException("boom"); })
                .build();
        s.addCustomChart(new SimplePie("throws", () -> { throw new IllegalStateException("x"); }));
        s.addCustomChart(new SimplePie("empty", () -> ""));
        s.addCustomChart(new SimplePie("ok", () -> "v"));
        String json = s.buildPayload();
        assertFalse(json.contains("throws"));
        assertFalse(json.contains("\"empty\""));
        assertFalse(json.contains("playerAmount"), "failed server info omits platform fields");
        assertTrue(json.contains("\"customCharts\":[{\"chartId\":\"ok\",\"data\":{\"value\":\"v\"}}]"), json);
        assertTrue(json.contains("\"pluginVersion\":\"unknown\""), json);
        assertTrue(logged.stream().anyMatch(l -> l.contains("chart 'throws' skipped")), logged.toString());
        assertThrows(NullPointerException.class, () -> s.addCustomChart(null));
    }

    @Test
    @DisplayName("send posts gzipped payload to /api/v2/data/<platform>")
    void sendEndpoint() {
        BStatsService s = builder().build();
        String payload = s.buildPayload();
        s.send(payload);
        assertEquals(List.of("https://bStats.org/api/v2/data/bukkit"), transport.urls);
        assertEquals(payload, transport.bodies.get(0));
    }

    @Test
    @DisplayName("send never throws on non-2xx or transport failure; logFailedRequests raises the level")
    void sendFailSoft() throws IOException {
        transport.status = 500;
        BStatsService s = builder().build();
        assertDoesNotThrow(() -> s.send("{}"));
        assertTrue(logged.stream().anyMatch(l -> l.startsWith(Level.FINE + " ") && l.contains("HTTP 500")), logged.toString());
        BStatsService broken = builder().transport((u, b) -> { throw new IOException("offline"); }).build();
        assertDoesNotThrow(() -> broken.send("{}"));

        writeYaml("enabled: true\nserverUuid: \"" + s.config().serverUuid() + "\"\nlogFailedRequests: true\n");
        logged.clear();
        builder().build().send("{}");
        assertTrue(logged.stream().anyMatch(l -> l.startsWith(Level.WARNING + " ")), logged.toString());
    }

    @Test
    @DisplayName("Opt-out blocks start and every send")
    void optOut() throws IOException {
        BStatsService s = builder().build();
        assertTrue(s.config().enabled());

        writeYaml("enabled: false\nserverUuid: \"" + s.config().serverUuid() + "\"\n");
        s.send("{}");
        assertTrue(transport.urls.isEmpty(), "opt-out written after start must stop sends");

        BStatsService disabled = builder().build();
        assertFalse(disabled.config().enabled());
        assertFalse(disabled.start());
        assertFalse(disabled.isRunning());
        assertEquals(-1L, sched.laterDelay, "nothing scheduled when opted out");
    }

    @Test
    @DisplayName("start uses upstream jitter and a 30-minute async timer; shutdown cancels")
    void lifecycle() {
        BStatsService s = builder().build();
        s.addCustomChart(new SimplePie("p", () -> "v"));

        assertTrue(s.start());
        assertTrue(s.start(), "idempotent");
        assertTrue(s.isRunning());
        long initial = BStatsService.initialDelayTicks(0.5);
        assertEquals(initial, sched.laterDelay);
        assertEquals(initial + BStatsService.secondDelayTicks(0.5), sched.timerDelay);
        assertEquals(36_000L, sched.timerPeriod);

        sched.later.run();
        assertEquals(List.of("async"), sched.calls, "default collects and sends off-thread");
        assertEquals(1, transport.urls.size());

        sched.timer.run();
        assertEquals(2, transport.urls.size());

        s.shutdown();
        assertFalse(s.isRunning());
        assertEquals(sched.timerHandle, sched.cancelled);
        sched.timer.run();
        assertEquals(2, transport.urls.size(), "no sends after shutdown");
        assertDoesNotThrow(s::shutdown);
    }

    @Test
    @DisplayName("collectOnMainThread gathers on the main thread, then sends async")
    void mainThreadCollection() {
        BStatsService s = builder().collectOnMainThread(true).build();
        assertTrue(s.start());
        sched.later.run();
        assertEquals(List.of("main", "async"), sched.calls);
        assertEquals(1, transport.urls.size());
        s.shutdown();
    }

    @Test
    @DisplayName("Scheduler and cancel failures never escape")
    void schedulerFailSoft() {
        BStatsScheduler throwing = new RecordingScheduler() {
            @Override public void runAsync(Runnable task) { throw new IllegalStateException("pool down"); }
        };
        BStatsService s = builder().scheduler(throwing).build();
        assertTrue(s.start());
        assertDoesNotThrow(() -> ((RecordingScheduler) throwing).later.run());

        sched.failCancel = true;
        BStatsService c = builder().build();
        assertTrue(c.start());
        assertDoesNotThrow(c::shutdown);
        assertFalse(c.isRunning());
    }

    @Test
    @DisplayName("Jitter bounds equal upstream: 3-6 min initial, 0-30 min offset")
    void jitterBounds() {
        assertEquals(3_600L, BStatsService.initialDelayTicks(0.0));
        assertEquals(7_200L, BStatsService.initialDelayTicks(1.0));
        assertEquals(0L, BStatsService.secondDelayTicks(0.0));
        assertEquals(36_000L, BStatsService.secondDelayTicks(1.0));
    }

    @Test
    @DisplayName("Builder rejects invalid arguments; scheduler is required")
    void builderValidation() {
        assertThrows(IllegalArgumentException.class, () -> BStatsService.builder(BStatsPlatform.BUKKIT, 0));
        assertThrows(NullPointerException.class, () -> BStatsService.builder(null, 1));
        assertThrows(NullPointerException.class, () -> BStatsService.builder(BStatsPlatform.BUKKIT, 1).transport(null));
        assertThrows(NullPointerException.class, () -> BStatsService.builder(BStatsPlatform.BUKKIT, 1).scheduler(null));
        assertThrows(IllegalStateException.class,
                () -> BStatsService.builder(BStatsPlatform.BUKKIT, 1).pluginDirectory(pluginDir).build());
    }

    @Test
    @DisplayName("Config format defaults to the platform's and can be overridden")
    void configFormatSelection() {
        File bukkitDir = tmp.resolve("bukkit").resolve("plugins").resolve("rtp").toFile();
        BStatsService.builder(BStatsPlatform.BUKKIT, 1).pluginDirectory(bukkitDir).scheduler(sched).build();
        assertTrue(Files.isRegularFile(bukkitDir.toPath().getParent().resolve("bStats").resolve("config.yml")));

        File modded = tmp.resolve("modded").resolve("config").resolve("RTP").toFile();
        BStatsService.builder(BStatsPlatform.BUKKIT, 1).pluginDirectory(modded)
                .configFormat(BStatsConfig.Format.TEXT).scheduler(sched).build();
        assertTrue(Files.isRegularFile(modded.toPath().getParent().resolve("bStats").resolve("config.txt")));
    }

    @Test
    @DisplayName("gzip round-trips UTF-8")
    void gzipRoundTrip() throws IOException {
        byte[] gz = BStatsService.gzip("{\"k\":\"\u00e9\"}");
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            assertEquals("{\"k\":\"\u00e9\"}", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
