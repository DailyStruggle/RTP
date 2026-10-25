package io.github.dailystruggle.helpers.stresstestrtp;

import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerGaugeTest {

    @Test
    @DisplayName("window row reports last/max/mean in-flight, at-cap fraction and queue sizes")
    void windowRow() {
        SchedulerGauge.Window w = new SchedulerGauge.Window();
        w.add(2, 8, 0);
        w.add(8, 8, 3);
        w.add(8, 8, 5);
        w.add(6, 8, 1);
        assertEquals("1000,6,8,6.00,0.500,8,1,5,4", w.row(1000L));
        assertEquals(SchedulerGauge.HEADER.split(",").length, w.row(1L).split(",").length);
    }

    @Test
    @DisplayName("empty window does not divide by zero")
    void emptyWindow() {
        assertEquals("5,0,0,0.00,0.000,0,0,0,0", new SchedulerGauge.Window().row(5L));
    }

    public static final class Inner {
        public int getInFlightRegionalTasks() { return 1; }
    }

    public static final class Decorator {
        private final Object d;
        Decorator(Object d) { this.d = d; }
        public Object delegate() { return d; }
    }

    @Test
    @DisplayName("unwrap peels decorators to the object exposing the in-flight getter")
    void unwrapDecorators() {
        Inner inner = new Inner();
        assertSame(inner, SchedulerGauge.unwrap(new Decorator(new Decorator(inner))));
        assertSame(inner, SchedulerGauge.unwrap(inner));
        Object plain = new Object();
        assertSame(plain, SchedulerGauge.unwrap(plain));
    }

    public static final class FakeScheduler {
        public int getInFlightRegionalTasks() { return 3; }
        public int getMaxRegionalInflight() { return 8; }
        public int getInteractiveQueueSize() { return 2; }
    }

    @Test
    @DisplayName("lookup resolves RTPRuntime.scheduler() method and writes CSV after a full window")
    void lookupResolvesSchedulerViaRtpRuntimeMethod(@TempDir Path tmp) throws Exception {
        RTPRuntime.setScheduler(new Decorator(new FakeScheduler()));
        try {
            StressHost host = new StressHost() {
                @Override public Logger getLogger() { return Logger.getAnonymousLogger(); }
                @Override public StressConfig getStressConfig() { return new StressConfig(); }
                @Override public Path dataFolder() { return tmp; }
                @Override public void dispatchConsoleCommand(String commandLine) {}
                @Override public Collection<StressPlayer> onlinePlayers() { return List.of(); }
                @Override public StressPlayer player(UUID uuid) { return null; }
                @Override public StressPlayer playerExact(String name) { return null; }
                @Override public List<StressWorld> worlds() { return List.of(); }
                @Override public StressWorld world(String name) { return null; }
                @Override public void runGlobal(Runnable task) { task.run(); }
                @Override public Object runGlobalLater(Runnable task, long delayTicks) { return null; }
                @Override public Object runGlobalTimer(Runnable task, long delayTicks, long periodTicks) { return null; }
                @Override public void runAsync(Runnable task) { task.run(); }
                @Override public boolean cancelTask(Object handle) { return false; }
                @Override public boolean isTickThread() { return true; }
                @Override
                public List<LoadedPlugin> loadedPlugins() {
                    return List.of(new LoadedPlugin(
                            "LeafRTP", "3.3.1", getClass().getClassLoader(), null, tmp, true, ""));
                }
                @Override public Runner runner() { return null; }
                @Override public MetricsRecorder recorder() { return null; }
                @Override public TpsMsptHeapSampler sampler() { return null; }
                @Override public void beginRun() {}
            };

            Path csv = tmp.resolve("sched_gauge.csv");
            SchedulerGauge gauge = new SchedulerGauge(host, csv, () -> true, false);
            Method sample = SchedulerGauge.class.getDeclaredMethod("sample");
            sample.setAccessible(true);
            for (int i = 0; i < SchedulerGauge.SAMPLES_PER_ROW; i++) {
                sample.invoke(gauge);
            }

            assertTrue(gauge.available(), "SchedulerGauge should resolve scheduler via RTPRuntime.scheduler()");
            assertTrue(Files.exists(csv), "SchedulerGauge should write CSV after SAMPLES_PER_ROW samples");
            List<String> lines = Files.readAllLines(csv);
            assertEquals(2, lines.size());
            assertEquals(SchedulerGauge.HEADER, lines.get(0));
            assertTrue(lines.get(1).endsWith(",3,3,3.00,0.000,8,2,2,10"), () -> "Unexpected row: " + lines.get(1));
        } finally {
            RTPRuntime.setScheduler(null);
        }
    }
}
