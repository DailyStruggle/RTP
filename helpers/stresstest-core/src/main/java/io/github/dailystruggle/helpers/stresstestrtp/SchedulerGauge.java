package io.github.dailystruggle.helpers.stresstestrtp;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

/**
 * Read-only gauge of the Folia RTP scheduler's regional concurrency cap
 * (ADR-122 Decision 1 & 2).
 */
public final class SchedulerGauge {

    static final long SAMPLE_MS = 100L;
    static final int SAMPLES_PER_ROW = 10;
    static final String RTP_CLASS = "io.github.dailystruggle.rtp.common.RTP";
    static final String HEADER = "epoch_ms,inflight_last,inflight_max,inflight_mean,at_cap_fraction,"
            + "max_inflight,interactive_queue_last,interactive_queue_max,samples";

    static final class Window {
        int n, inflightLast, inflightMax, cap, queueLast, queueMax, atCap;
        long inflightSum;

        void add(int inflight, int max, int queue) {
            n++;
            inflightLast = inflight;
            inflightMax = Math.max(inflightMax, inflight);
            inflightSum += inflight;
            cap = max;
            if (inflight >= max) atCap++;
            queueLast = queue;
            queueMax = Math.max(queueMax, queue);
        }

        String row(long epochMs) {
            return String.format(Locale.ROOT, "%d,%d,%d,%.2f,%.3f,%d,%d,%d,%d", epochMs, inflightLast, inflightMax,
                    n == 0 ? 0.0 : inflightSum / (double) n, n == 0 ? 0.0 : atCap / (double) n, cap, queueLast,
                    queueMax, n);
        }
    }

    private final StressHost plugin;
    private final Path csv;
    private final BooleanSupplier runActive;
    private final boolean logRows;

    private Object timer;
    private volatile Object scheduler;
    private Method getInFlight, getMax, getQueue;
    private boolean absentLogged;
    private long lastLookupMs;
    private Window window = new Window();

    public SchedulerGauge(StressHost plugin, Path csv, BooleanSupplier runActive, boolean logRows) {
        this.plugin = plugin;
        this.csv = csv;
        this.runActive = runActive;
        this.logRows = logRows;
    }

    public void start() {
        if (timer != null || !Sched.isFolia()) return;
        timer = Sched.runAsyncTimer(plugin, this::sample, SAMPLE_MS);
    }

    public void stop() {
        Sched.cancel(timer);
        timer = null;
    }

    public boolean available() { return scheduler != null; }

    private void sample() {
        if (!runActive.getAsBoolean()) {
            if (window.n > 0) window = new Window();
            return;
        }
        if (scheduler == null && !lookup()) return;
        try {
            window.add((int) getInFlight.invoke(scheduler), (int) getMax.invoke(scheduler),
                    (int) getQueue.invoke(scheduler));
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "[StressTestRTP] SchedulerGauge: read failed, gauge disabled", e);
            scheduler = null;
            lastLookupMs = Long.MAX_VALUE;
            return;
        }
        if (window.n >= SAMPLES_PER_ROW) {
            emit(window, System.currentTimeMillis());
            window = new Window();
        }
    }

    private void emit(Window w, long now) {
        String row = w.row(now);
        if (logRows) {
            plugin.getLogger().info(() -> "[StressTestRTP] sched-gauge inflight=" + w.inflightLast + " (max " + w.inflightMax
                    + ", at-cap " + Math.round(100.0 * w.atCap / Math.max(1, w.n)) + "%) cap=" + w.cap
                    + " interactive_queue=" + w.queueLast + " (max " + w.queueMax + ")");
        }
        try {
            boolean fresh = !Files.exists(csv) || Files.size(csv) == 0;
            Files.createDirectories(csv.getParent());
            String text = (fresh ? HEADER + "\n" : "") + row + "\n";
            Files.writeString(csv, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, e, () -> "[StressTestRTP] SchedulerGauge: cannot write " + csv);
        }
    }

    private boolean lookup() {
        long now = System.currentTimeMillis();
        if (now - lastLookupMs < 5000L) return false;
        lastLookupMs = now;
        Object found = null;
        try {
            for (StressHost.LoadedPlugin p : plugin.loadedPlugins()) {
                if (p == null || p.classLoader() == null) continue;
                try {
                    Class<?> rtp = Class.forName(RTP_CLASS, false, p.classLoader());
                    Field f = rtp.getField("scheduler");
                    found = f.get(null);
                    if (found != null) break;
                } catch (ClassNotFoundException | NoSuchFieldException ignored) {
                }
            }
            if (found != null) {
                found = unwrap(found);
                getInFlight = accessor(found, "getInFlightRegionalTasks");
                getMax = accessor(found, "getMaxRegionalInflight");
                getQueue = accessor(found, "getInteractiveQueueSize");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            found = null;
            if (!absentLogged) {
                absentLogged = true;
                plugin.getLogger().log(Level.INFO, "[StressTestRTP] SchedulerGauge: RTP scheduler counters unavailable", e);
            }
        }
        if (found == null) {
            if (!absentLogged) {
                absentLogged = true;
                plugin.getLogger().info("[StressTestRTP] SchedulerGauge: RTP scheduler counters not found; gauge idle (retrying every 5 s)");
            }
            return false;
        }
        scheduler = found;
        plugin.getLogger().log(Level.INFO, "[StressTestRTP] SchedulerGauge: reading {0} -> {1}",
                new Object[]{found.getClass().getName(), csv});
        return true;
    }

    static Object unwrap(Object scheduler) {
        Object cur = scheduler;
        for (int depth = 0; depth < 8 && cur != null; depth++) {
            try {
                cur.getClass().getMethod("getInFlightRegionalTasks");
                return cur;
            } catch (NoSuchMethodException ignored) {
            }
            Object next;
            try {
                Method d = cur.getClass().getMethod("delegate");
                if (!Modifier.isPublic(d.getDeclaringClass().getModifiers())) d.setAccessible(true);
                next = d.invoke(cur);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return scheduler;
            }
            if (next == null || next == cur) return scheduler;
            cur = next;
        }
        return scheduler;
    }

    private static Method accessor(Object target, String name) throws NoSuchMethodException {
        Method m = target.getClass().getMethod(name);
        if (!Modifier.isPublic(m.getDeclaringClass().getModifiers())) m.setAccessible(true);
        return m;
    }
}
