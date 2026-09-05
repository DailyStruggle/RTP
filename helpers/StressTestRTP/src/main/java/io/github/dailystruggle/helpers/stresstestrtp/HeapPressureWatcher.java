package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Records heap-pressure control-loop evidence emitted by the plugin under test.
 *
 * <p>Some RTP-style plugins run their own heap governor: under a configured
 * heap limit they reduce max attempts per request and fall back to serving
 * only from cache. That is a behavioural change which invalidates any
 * comparison drawn across the boundary, so the harness needs to know the
 * boundary was crossed and at what heap level.
 *
 * <p>This class <strong>records the trigger only</strong>. It does not model
 * the response, does not infer what the plugin did next, and does not attribute
 * a latency or memory change to a match. A match is one row of evidence: the
 * wall clock, the heap-used level read from the existing sampler at that
 * instant, and the (abbreviated) log line itself, so a reader can decide what
 * it means later.
 *
 * <p>Mechanism mirrors {@link ConsoleWatcher}: a {@link java.util.logging.Handler}
 * on the root logger, configurable regex patterns, self-records filtered out.
 * The handler runs on whichever thread emitted the record - possibly a tick
 * thread - so the hot path is a pattern match over primitive counters; the
 * sidecar write happens only on a match, which is a rare event by nature.
 */
public final class HeapPressureWatcher {

    /** Sidecar header. Sentinels are documented in the run's schema sidecar. */
    public static final String TRIGGERS_CSV_HEADER =
            "epoch_ms,phase_label,heap_used_mb_at_trigger,tps_at_trigger,mspt_at_trigger,"
                    + "logger_name,trigger_line";

    private final Plugin plugin;
    private final FileConfiguration config;
    private final TpsMsptHeapSampler sampler;

    private final List<Pattern> patterns = new ArrayList<>();
    private java.util.logging.Handler handler;

    /** Phase-scoped evidence. All primitive; {@code -1} = no event observed. */
    private final AtomicLong phaseEvents = new AtomicLong();
    private volatile long phaseFirstHeapMb = -1L;
    private volatile String phaseFirstTrigger = "";

    /** Active phase label, echoed into the sidecar so a trigger is locatable. */
    private volatile String phaseLabel = "";

    private final Object sidecarLock = new Object();
    private BufferedWriter sidecarWriter = null;

    public HeapPressureWatcher(Plugin plugin, FileConfiguration config, TpsMsptHeapSampler sampler) {
        this.plugin = plugin;
        this.config = config;
        this.sampler = sampler;
        loadPatterns();
    }

    private void loadPatterns() {
        patterns.clear();
        List<String> raw = config.getStringList("heap-pressure-patterns");
        if (raw.isEmpty()) {
            // Defaults target the observable vocabulary of a heap governor.
            // Deliberately narrow: a false positive here becomes a claimed
            // behavioural change that never happened.
            raw = List.of(
                    "heap limit",
                    "memory limit",
                    "low memory",
                    "out of memory",
                    "high memory",
                    "memory usage",
                    "heap usage",
                    "reducing max attempts",
                    "reduced max attempts",
                    "cache only",
                    "cache-only",
                    "queue only",
                    "not enough memory",
                    "gc pressure"
            );
        }
        for (String s : raw) {
            try {
                patterns.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE));
            } catch (Exception ignored) {
                plugin.getLogger().warning(
                        "[StressTestRTP] ignoring invalid heap-pressure-pattern: " + s);
            }
        }
    }

    public void start() {
        if (handler != null) return;
        if (!config.getBoolean("heap-pressure-watch-enabled", true)) return;
        handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                if (record == null) return;
                String msg = record.getMessage();
                if (msg == null || msg.isEmpty()) return;
                // Self-feedback guard, same failure mode ConsoleWatcher hit:
                // our own trigger echo must not re-match its own pattern.
                String src = record.getLoggerName();
                if (src != null && (src.contains("StressTestRTP")
                        || src.equals(plugin.getLogger().getName()))) {
                    return;
                }
                if (msg.contains("[StressTestRTP]")) return;
                for (Pattern p : patterns) {
                    if (p.matcher(msg).find()) {
                        onTrigger(src, msg);
                        return;
                    }
                }
            }
            @Override public void flush() {}
            @Override public void close() throws SecurityException {}
        };
        java.util.logging.Logger.getLogger("").addHandler(handler);
    }

    public void stop() {
        if (handler != null) {
            try {
                java.util.logging.Logger.getLogger("").removeHandler(handler);
            } catch (Throwable ignored) { /* best-effort */ }
            handler = null;
        }
        stopSidecar();
    }

    /** Opens the per-run trigger sidecar. Closes any previous one first. */
    public void startSidecar(Path csvPath) throws IOException {
        synchronized (sidecarLock) {
            closeSidecarWriter();
            Files.createDirectories(csvPath.getParent());
            Files.writeString(csvPath, TRIGGERS_CSV_HEADER + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            sidecarWriter = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        }
    }

    public void stopSidecar() {
        synchronized (sidecarLock) {
            closeSidecarWriter();
        }
    }

    private void closeSidecarWriter() {
        if (sidecarWriter != null) {
            try {
                sidecarWriter.flush();
                sidecarWriter.close();
            } catch (IOException e) {
                plugin.getLogger().warning(
                        "[StressTestRTP] failed to close heap-trigger sidecar: " + e.getMessage());
            }
            sidecarWriter = null;
        }
    }

    private void onTrigger(String loggerName, String line) {
        String abbreviated = abbreviate(line);
        TpsMsptHeapSampler.Snapshot snap = sampler != null ? sampler.latest() : null;
        long heapMb = snap != null ? snap.heapUsedMb() : -1L;
        if (phaseEvents.getAndIncrement() == 0L) {
            phaseFirstHeapMb = heapMb;
            phaseFirstTrigger = abbreviated;
        }
        synchronized (sidecarLock) {
            BufferedWriter w = sidecarWriter;
            if (w == null) return;
            String row = String.join(",",
                    Long.toString(System.currentTimeMillis()),
                    csv(phaseLabel),
                    heapMb >= 0 ? Long.toString(heapMb) : "-1",
                    snap != null && snap.tps() >= 0
                            ? String.format(java.util.Locale.ROOT, "%.3f", snap.tps()) : "",
                    snap != null && snap.mspt() >= 0
                            ? String.format(java.util.Locale.ROOT, "%.3f", snap.mspt()) : "",
                    csv(loggerName == null ? "" : loggerName),
                    csv(abbreviated));
            try {
                w.write(row);
                w.newLine();
                w.flush();
            } catch (IOException e) {
                plugin.getLogger().warning(
                        "[StressTestRTP] heap-trigger append failed: " + e.getMessage());
            }
        }
    }

    /** Resets the phase-scoped evidence and records the new phase label. */
    public void beginPhase(String label) {
        phaseLabel = label == null ? "" : label;
        phaseEvents.set(0L);
        phaseFirstHeapMb = -1L;
        phaseFirstTrigger = "";
    }

    /** Matches observed in the current phase. Zero is a measured zero here:
     *  the watcher is always active while a run is live. */
    public long phaseEventCount() { return phaseEvents.get(); }

    /** Heap-used (MB) when the phase's first trigger fired, or {@code -1}. */
    public long phaseFirstHeapUsedMb() { return phaseFirstHeapMb; }

    /** Abbreviated first trigger line of the phase, or empty. */
    public String phaseFirstTrigger() { return phaseFirstTrigger; }

    private static String abbreviate(String s) {
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() > 120) t = t.substring(0, 120);
        return t;
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
