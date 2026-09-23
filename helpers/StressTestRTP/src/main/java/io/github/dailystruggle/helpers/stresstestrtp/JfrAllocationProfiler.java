package io.github.dailystruggle.helpers.stresstestrtp;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-phase, per-plugin allocation profiler built on Flight Recorder.
 *
 * <p>Why this exists: the heap-used series and {@link GcSampler} answer "how
 * much did the JVM churn" and "how often did GC run", but on a shared server
 * JVM neither can say <em>which plugin's</em> allocation forced those
 * collections - and a GC pause is a global stop-the-world event that interrupts
 * every plugin equally regardless of who produced the garbage. The only honest
 * way to attribute allocation to a plugin is to sample allocation events and
 * charge each sample to the first stack frame that belongs to a known plugin
 * package. That is exactly what {@code jdk.ObjectAllocationSample} provides:
 * throttled, low-overhead, and equal across every arm, so the overhead does not
 * bias the head-to-head.
 *
 * <p>One {@link Recording} is opened per measurement phase and stopped and
 * parsed at the phase boundary (never mid-phase, so the periodic partial-phase
 * flush never disturbs it). The parsed {@link Result} is cached for the phase
 * row and the full per-package breakdown is written to a {@code -jfr-alloc.csv}
 * sidecar so an unfamiliar plugin's package still surfaces for discovery.
 *
 * <p>Degrades closed: if Flight Recorder is unavailable (disabled at the JVM,
 * an old runtime, or a locked commercial build) {@link #available()} is false
 * and every JFR column writes the {@code -1} / empty not-measured sentinel,
 * exactly like the other optional samplers.
 */
public final class JfrAllocationProfiler {

    /** Result of parsing one phase's recording. All byte figures are the sum
     *  of {@code jdk.ObjectAllocationSample} weights, i.e. an <em>estimate</em>
     *  of allocated bytes, not an exact total. */
    public record Result(boolean available,
                         long samples,
                         long totalBytes,
                         long targetBytes,
                         String targetLabel,
                         String targetPackage,
                         Map<String, long[]> trackedBytes,   // label -> {bytes, samples}
                         Map<String, long[]> topPackages) {  // 3-seg pkg -> {bytes, samples}
        static Result unavailable() {
            return new Result(false, -1L, -1L, -1L, "", "",
                    new LinkedHashMap<>(), new LinkedHashMap<>());
        }
    }

    private final Logger log;
    private final String throttle;
    private final long maxSizeBytes;
    /** label -> package prefix, e.g. {@code rtp -> io.github.dailystruggle.rtp}. */
    private final Map<String, String> trackedPrefixes;
    private final Path tempDir;

    /** JFR usable at all (probed once). */
    private final boolean capable;

    private volatile Recording recording;
    private volatile Path currentDump;
    private volatile String currentLabel = "";

    public JfrAllocationProfiler(Logger log,
                                 boolean enabled,
                                 String throttle,
                                 long maxSizeBytes,
                                 Map<String, String> trackedPrefixes) {
        this(log, enabled, throttle, maxSizeBytes, trackedPrefixes, null);
    }

    public JfrAllocationProfiler(Logger log,
                                 boolean enabled,
                                 String throttle,
                                 long maxSizeBytes,
                                 Map<String, String> trackedPrefixes,
                                 Path tempDir) {
        this.log = log;
        this.throttle = (throttle == null || throttle.isBlank()) ? "300/s" : throttle;
        this.maxSizeBytes = maxSizeBytes > 0 ? maxSizeBytes : 256L * 1024 * 1024;
        this.tempDir = tempDir;
        this.trackedPrefixes = new LinkedHashMap<>();
        if (trackedPrefixes != null) {
            for (Map.Entry<String, String> e : trackedPrefixes.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isBlank()) {
                    this.trackedPrefixes.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
                }
            }
        }
        this.capable = enabled && probeCapability();
    }

    /** Attempts to open and immediately close a throttled recording to confirm
     *  Flight Recorder and the allocation event are usable in this JVM. */
    private boolean probeCapability() {
        try (Recording probe = new Recording()) {
            probe.enable("jdk.ObjectAllocationSample").with("throttle", throttle);
            return true;
        } catch (Throwable t) {
            if (log != null) {
                log.log(Level.INFO, "[StressTestRTP] JFR allocation profiling unavailable; "
                        + "per-plugin allocation columns will be the not-measured sentinel: " + t);
            }
            return false;
        }
    }

    public boolean available() {
        return capable;
    }

    /** Scope label for the JFR columns; empty when the profiler is inactive. */
    public String scope() {
        return capable ? "JFR_OBJECT_ALLOCATION_SAMPLE" : "";
    }

    /** Package prefix configured for a phase label, or empty when unmapped. */
    public String targetPackageFor(String label) {
        if (label == null) return "";
        String p = trackedPrefixes.get(label.toLowerCase(Locale.ROOT));
        return p == null ? "" : p;
    }

    /** Starts a fresh recording for one phase. A recording already open (e.g. a
     *  mode switch that re-entered {@code beginPhase}) is discarded first. */
    public synchronized void beginPhase(String label) {
        if (!capable) return;
        stopQuietly();
        currentLabel = label == null ? "" : label;
        Path dump = null;
        Recording rec = null;
        try {
            Path dir = this.tempDir;
            if (dir == null) {
                dir = Path.of("temp");
            }
            Files.createDirectories(dir);
            dump = Files.createTempFile(dir, "stressrtp-jfr-", ".jfr");
            rec = new Recording();
            rec.enable("jdk.ObjectAllocationSample").with("throttle", throttle);
            rec.setToDisk(true);
            rec.setMaxSize(maxSizeBytes);
            rec.setDestination(dump);
            rec.start();
            this.currentDump = dump;
            this.recording = rec;
        } catch (Throwable t) {
            if (rec != null) {
                try { rec.close(); } catch (Throwable ignored) { /* best effort */ }
            }
            if (dump != null) {
                try { Files.deleteIfExists(dump); } catch (Throwable ignored) { /* best effort */ }
            }
            if (log != null) {
                log.log(Level.WARNING, "[StressTestRTP] JFR recording failed to start for phase '"
                        + currentLabel + "'; JFR columns not measured this phase: " + t);
            }
            this.recording = null;
            this.currentDump = null;
        }
    }

    /**
     * Stops the current phase's recording and parses it into a {@link Result}.
     * Called once at the phase boundary. Never invoked by the periodic
     * partial-phase flush, so a 600 s recording is parsed at most once.
     */
    public synchronized Result endPhaseAndParse() {
        if (!capable || recording == null) {
            return Result.unavailable();
        }
        Recording rec = this.recording;
        Path dump = this.currentDump;
        String label = this.currentLabel;
        this.recording = null;
        this.currentDump = null;
        try {
            rec.stop();
        } catch (Throwable t) {
            if (log != null) log.log(Level.WARNING, "[StressTestRTP] JFR stop failed: " + t);
        } finally {
            try { rec.close(); } catch (Throwable ignored) { /* best effort */ }
        }
        try {
            Result r = parse(dump, label);
            return r;
        } catch (Throwable t) {
            if (log != null) log.log(Level.WARNING, "[StressTestRTP] JFR parse failed for phase '"
                    + label + "': " + t);
            return Result.unavailable();
        } finally {
            if (dump != null) {
                try { Files.deleteIfExists(dump); } catch (Throwable ignored) { /* best effort */ }
            }
        }
    }

    private Result parse(Path dump, String label) throws Exception {
        Map<String, long[]> tracked = new LinkedHashMap<>();
        for (String k : trackedPrefixes.keySet()) tracked.put(k, new long[]{0L, 0L});
        Map<String, long[]> top = new LinkedHashMap<>();
        long samples = 0L;
        long totalBytes = 0L;
        if (dump == null || !Files.exists(dump)) {
            return Result.unavailable();
        }
        List<RecordedEvent> events = RecordingFile.readAllEvents(dump);
        for (RecordedEvent e : events) {
            String type = e.getEventType().getName();
            if (!"jdk.ObjectAllocationSample".equals(type)) continue;
            long weight = eventWeight(e);
            if (weight <= 0) weight = 0;
            samples++;
            totalBytes += weight;
            RecordedStackTrace st = e.getStackTrace();
            String trackedLabel = classifyTracked(st);
            if (trackedLabel != null) {
                long[] acc = tracked.get(trackedLabel);
                acc[0] += weight;
                acc[1] += 1;
            }
            String pkg = classifyTopPackage(st);
            long[] acc = top.computeIfAbsent(pkg, k -> new long[]{0L, 0L});
            acc[0] += weight;
            acc[1] += 1;
        }
        String targetLabel = label == null ? "" : label.toLowerCase(Locale.ROOT);
        long targetBytes = -1L;
        String targetPackage = "";
        if (trackedPrefixes.containsKey(targetLabel)) {
            targetPackage = trackedPrefixes.get(targetLabel);
            long[] acc = tracked.get(targetLabel);
            targetBytes = acc != null ? acc[0] : -1L;
        }
        return new Result(true, samples, totalBytes, targetBytes,
                trackedPrefixes.containsKey(targetLabel) ? targetLabel : "",
                targetPackage, tracked, top);
    }

    /** Allocation weight of a sample event, tolerant of field-name changes
     *  across JDK versions. {@code jdk.ObjectAllocationSample} carries an
     *  estimated {@code weight}; older allocation events used
     *  {@code allocationSize}. */
    private static long eventWeight(RecordedEvent e) {
        try {
            if (e.hasField("weight")) return e.getLong("weight");
        } catch (Throwable ignored) { /* fall through */ }
        try {
            if (e.hasField("allocationSize")) return e.getLong("allocationSize");
        } catch (Throwable ignored) { /* fall through */ }
        return 0L;
    }

    /** First tracked plugin label whose package owns a frame, top of stack
     *  first (the allocating call site's nearest plugin owner), or null. */
    private String classifyTracked(RecordedStackTrace st) {
        if (st == null) return null;
        List<RecordedFrame> frames = st.getFrames();
        for (RecordedFrame f : frames) {
            String cls = className(f);
            if (cls == null) continue;
            for (Map.Entry<String, String> e : trackedPrefixes.entrySet()) {
                if (cls.startsWith(e.getValue())) return e.getKey();
            }
        }
        return null;
    }

    /** Top-of-stack 3-segment package for discovery of unmapped plugins. */
    private static String classifyTopPackage(RecordedStackTrace st) {
        if (st == null) return "<no-stack>";
        List<RecordedFrame> frames = st.getFrames();
        for (RecordedFrame f : frames) {
            String cls = className(f);
            if (cls == null) continue;
            return threeSegments(cls);
        }
        return "<no-stack>";
    }

    private static String className(RecordedFrame f) {
        try {
            if (f == null || f.getMethod() == null || f.getMethod().getType() == null) return null;
            return f.getMethod().getType().getName();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String threeSegments(String className) {
        int a = className.indexOf('.');
        if (a < 0) return className;
        int b = className.indexOf('.', a + 1);
        if (b < 0) return className.substring(0, a);
        int c = className.indexOf('.', b + 1);
        if (c < 0) return className.substring(0, b);
        return className.substring(0, c);
    }

    private void stopQuietly() {
        Recording rec = this.recording;
        Path dump = this.currentDump;
        this.recording = null;
        this.currentDump = null;
        if (rec != null) {
            try { rec.stop(); } catch (Throwable ignored) { /* best effort */ }
            try { rec.close(); } catch (Throwable ignored) { /* best effort */ }
        }
        if (dump != null) {
            try { Files.deleteIfExists(dump); } catch (Throwable ignored) { /* best effort */ }
        }
    }

    /** Renders the per-package breakdown for one phase as sidecar lines. The
     *  tracked labels come first (stable), then the top raw packages by bytes
     *  so an unmapped plugin (e.g. one whose package is not yet in config) is
     *  still visible and can be added to the tracked set later. */
    public List<String> sidecarLines(String phaseLabel, Result r) {
        List<String> out = new ArrayList<>();
        if (r == null || !r.available()) return out;
        out.add("# phase=" + phaseLabel + " scope=" + scope()
                + " samples=" + r.samples() + " total_bytes=" + r.totalBytes());
        for (Map.Entry<String, long[]> e : r.trackedBytes().entrySet()) {
            String prefix = trackedPrefixes.getOrDefault(e.getKey(), "");
            out.add(csv(phaseLabel) + ",tracked," + csv(e.getKey()) + "," + csv(prefix)
                    + "," + e.getValue()[0] + "," + e.getValue()[1]);
        }
        r.topPackages().entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]))
                .limit(15)
                .forEach(e -> out.add(csv(phaseLabel) + ",top," + csv(e.getKey()) + ",,"
                        + e.getValue()[0] + "," + e.getValue()[1]));
        return out;
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** Default tracked package set for the RTP-family + known competitors.
     *  Merged with (and overridable by) config. JustRTP's package is left for
     *  config to supply since it was not verifiable from the shipped jar here. */
    public static Map<String, String> defaultTrackedPrefixes() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("rtp", "io.github.dailystruggle.rtp");
        m.put("ezrtp", "com.skyblockexp.ezrtp");
        m.put("betterrtp", "me.SuperRonanCraft.BetterRTP");
        m.put("huskhomes", "net.william278.huskhomes");
        return m;
    }
}
