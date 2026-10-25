package io.github.dailystruggle.helpers.stresstestrtp;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Optional integration with the spark profiler (ADR-122 Decision 1 & 7).
 */
public final class SparkHook {

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private final StressHost host;
    private final StressConfig config;
    private final Logger log;
    private volatile boolean inProfile = false;
    private volatile String currentPhaseBase = null;
    private final java.util.concurrent.atomic.AtomicInteger currentRotation = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile long lastRotationStartMs = 0L;
    private volatile Path sparkDirCached = null;
    static final long SUMMARY_POLL_MS = 1000L;

    public SparkHook(StressConfig config, Logger log) {
        this(Sched.boundHost(), config, log);
    }

    public SparkHook(StressHost host, StressConfig config, Logger log) {
        this.host = host;
        this.config = config;
        this.log = log;
    }

    public boolean enabled() {
        return config.getBoolean("spark.enabled", true);
    }

    public boolean profilePerPhase() {
        return config.getBoolean("spark.profile-per-phase", true);
    }

    public long rotateSeconds() {
        return Math.max(0L, config.getLong("spark.rotate-seconds", 0L));
    }

    private volatile boolean unavailableLogged = false;

    private boolean sparkAvailable() {
        StressHost h = effectiveHost();
        boolean available = sparkPluginInstalled(h) || (h != null && h.isCommandRegistered("spark"));
        if (!available && !unavailableLogged) {
            unavailableLogged = true;
            if (log != null) log.info("[StressTestRTP] spark not detected (no spark plugin, no /spark command);"
                    + " per-phase profiling disabled.");
        }
        return available;
    }

    private static boolean sparkPluginInstalled(StressHost h) {
        if (h == null) return false;
        try {
            for (StressHost.LoadedPlugin p : h.loadedPlugins()) {
                if (p != null && p.enabled() && "spark".equalsIgnoreCase(p.name())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public void startPhase(String label) {
        if (!enabled() || !profilePerPhase()) return;
        if (!sparkAvailable()) return;
        if (inProfile) {
            stopPhase(currentPhaseBase != null ? currentPhaseBase : label);
        }
        currentPhaseBase = label;
        currentRotation.set(0);
        lastRotationStartMs = System.currentTimeMillis();
        startSliceInternal(label);
    }

    private void startSliceInternal(String sliceLabel) {
        long timeout = Math.max(5L, config.getLong("spark.timeout-seconds", 60L));
        long onlyTicksOver = Math.max(0L, config.getLong("spark.only-ticks-over-ms", 0L));
        long intervalMs = Math.max(0L, config.getLong("spark.interval", config.getLong("spark.interval-ms", 0L)));
        String threads = config.getString("spark.threads", DEFAULT_THREADS);
        dispatch(buildStartCommand(timeout, onlyTicksOver, threads, intervalMs));
        inProfile = true;
        if (log != null) log.info("[StressTestRTP] spark profiler started for phase: " + sliceLabel);
    }

    static String buildStartCommand(long timeoutSeconds, long onlyTicksOverMs, String threads) {
        return buildStartCommand(timeoutSeconds, onlyTicksOverMs, threads, 0L);
    }

    static final String DEFAULT_THREADS = "Server thread,RTP-Anvil-IO-*";

    /**
     * {@code spark profiler start} command line (POTENTIAL_BUGS.md fix preserved:
     * {@code --regex} emitted before {@code --thread} flags, and spaces inside
     * thread names are not wrapped in literal quote characters).
     */
    static String buildStartCommand(long timeoutSeconds, long onlyTicksOverMs, String threads, long intervalMs) {
        StringBuilder sb = new StringBuilder("spark profiler start --timeout ").append(timeoutSeconds);
        if (onlyTicksOverMs > 0L) {
            sb.append(" --only-ticks-over ").append(onlyTicksOverMs);
        }
        if (intervalMs > 0L) {
            sb.append(" --interval ").append(intervalMs);
        }
        List<String> names = new ArrayList<>();
        if (threads != null) {
            for (String part : threads.split(",")) {
                String n = WHITESPACE_RUN.matcher(part.trim()).replaceAll(" ");
                if (!n.isEmpty() && !names.contains(n)) names.add(n);
            }
        }
        if (names.contains("*")) {
            return sb.append(" --thread *").toString();
        }
        boolean regex = false;
        for (String n : names) {
            if (n.indexOf('*') >= 0) {
                regex = true;
                break;
            }
        }
        if (regex) sb.append(" --regex");
        for (String n : names) {
            sb.append(" --thread ").append(regex ? globToRegex(n) : n);
        }
        return sb.toString();
    }

    static String globToRegex(String glob) {
        StringBuilder out = new StringBuilder(glob.length() + 8);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                out.append(".*");
            } else {
                if ("\\.[]{}()+?^$|".indexOf(c) >= 0) out.append('\\');
                out.append(c);
            }
        }
        return out.toString();
    }

    public void stopPhase(String label) {
        if (!enabled() || !profilePerPhase()) return;
        if (!sparkAvailable()) return;
        if (!inProfile) {
            currentPhaseBase = null;
            currentRotation.set(0);
            return;
        }
        int rot = currentRotation.get();
        String slice = (rot > 0)
                ? (label == null || label.isEmpty() ? "stresstestrtp" : label) + "-r" + rot
                : (label == null || label.isEmpty() ? "stresstestrtp" : label);
        String comment = sanitize(slice);
        boolean saveToFile = config.getBoolean("spark.save-to-file", true);
        StringBuilder sb = new StringBuilder("spark profiler stop --comment ").append(comment);
        if (saveToFile) {
            sb.append(" --save-to-file");
        }
        long stopEpochMs = System.currentTimeMillis();
        dispatch(sb.toString());
        inProfile = false;
        currentPhaseBase = null;
        currentRotation.set(0);
        if (log != null) {
            log.info("[StressTestRTP] spark profiler stopped for phase: " + slice
                + (saveToFile ? " (saved to " + sparkDirLabel() + ")" : " (uploaded to bytebin)"));
        }
        if (saveToFile) {
            scheduleSummarise(slice, stopEpochMs);
        }
    }

    public boolean rotateIfDue(long nowMs) {
        if (!enabled() || !profilePerPhase()) return false;
        if (!inProfile || currentPhaseBase == null) return false;
        long rotateMs = rotateSeconds() * 1000L;
        if (rotateMs <= 0L) return false;
        if (nowMs - lastRotationStartMs < rotateMs) return false;
        if (!sparkAvailable()) return false;

        String base = currentPhaseBase;
        int rot = currentRotation.get();
        String stoppingSlice = (rot > 0) ? base + "-r" + rot : base;
        String stoppingComment = sanitize(stoppingSlice);
        boolean saveToFile = config.getBoolean("spark.save-to-file", true);
        StringBuilder sb = new StringBuilder("spark profiler stop --comment ").append(stoppingComment);
        if (saveToFile) sb.append(" --save-to-file");
        long stopEpochMs = System.currentTimeMillis();
        dispatch(sb.toString());
        if (log != null) {
            log.info("[StressTestRTP] spark profiler rotated slice: " + stoppingSlice
                + (saveToFile ? " (saved to " + sparkDirLabel() + ")" : " (uploaded to bytebin)"));
        }
        if (saveToFile) {
            scheduleSummarise(stoppingSlice, stopEpochMs);
        }

        int nextRot = currentRotation.incrementAndGet();
        lastRotationStartMs = nowMs;
        String nextSlice = base + "-r" + nextRot;
        startSliceInternal(nextSlice);
        return true;
    }

    private void scheduleSummarise(String label, long stopEpochMs) {
        if (!config.getBoolean("spark.auto-summary", true)) return;
        long delayMs = Math.max(1L, config.getLong("spark.auto-summary-delay-ticks", 60L)) * 50L;
        long timeoutMs = Math.max(1L, config.getLong("spark.auto-summary-timeout-seconds", 30L)) * 1000L;
        StressHost owner = effectiveHost();
        try {
            Sched.runAsync(owner, () -> awaitAndSummarise(label, stopEpochMs, delayMs, timeoutMs));
        } catch (Throwable t) {
            if (log != null) {
                log.log(Level.FINE, () -> "[StressTestRTP] spark auto-summary: scheduler unavailable, skipping (label="
                        + label + ").");
            }
        }
    }

    private void awaitAndSummarise(String label, long stopEpochMs, long delayMs, long timeoutMs) {
        long deadline = stopEpochMs + Math.max(timeoutMs, delayMs);
        ProfileWatch watch = null;
        Path sparkDir = null;
        try {
            Thread.sleep(delayMs);
            while (true) {
                if (watch == null) {
                    sparkDir = locateSparkDir();
                    if (sparkDir != null) watch = new ProfileWatch(sparkDir, stopEpochMs);
                }
                Path saved = watch != null ? watch.poll() : null;
                if (saved != null) {
                    summarise(saved, label);
                    return;
                }
                if (System.currentTimeMillis() >= deadline) break;
                Thread.sleep(SUMMARY_POLL_MS);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }
        if (log != null) log.warning(
                "[StressTestRTP] spark auto-summary: no newly saved .sparkprofile in "
                        + (sparkDir != null ? sparkDir : sparkDirLabel()) + " within "
                        + (timeoutMs / 1000L) + " s of stop (label=" + label + ").");
    }

    private void summarise(Path profile, String label) {
        try {
            Path out = SparkProfileSummariser.summariseToSidecar(profile, label);
            if (log != null) log.info("[StressTestRTP] spark auto-summary written: " + out);
        } catch (Throwable t) {
            if (log != null) log.log(Level.WARNING,
                    "[StressTestRTP] spark auto-summary failed for " + profile, t);
        }
    }

    static final class ProfileWatch {
        private final Path dir;
        private final long stopEpochMs;
        private final Set<Path> before;
        private Path candidate = null;
        private long candidateSize = -1L;

        ProfileWatch(Path dir, long stopEpochMs) {
            this.dir = dir;
            this.stopEpochMs = stopEpochMs;
            this.before = listProfiles(dir);
        }

        Path poll() {
            Path newest = null;
            long newestMs = Long.MIN_VALUE;
            for (Path p : listProfiles(dir)) {
                long m;
                try {
                    m = Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    continue;
                }
                if (before.contains(p) && m < stopEpochMs) continue;
                if (m > newestMs) {
                    newestMs = m;
                    newest = p;
                }
            }
            if (newest == null) {
                candidate = null;
                candidateSize = -1L;
                return null;
            }
            long size;
            try {
                size = Files.size(newest);
            } catch (IOException e) {
                size = -1L;
            }
            if (size > 0L && newest.equals(candidate) && size == candidateSize) return newest;
            candidate = newest;
            candidateSize = size;
            return null;
        }

        private static Set<Path> listProfiles(Path dir) {
            Set<Path> out = new HashSet<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.sparkprofile")) {
                for (Path p : ds) out.add(p);
            } catch (IOException ignored) { }
            return out;
        }
    }

    private String sparkDirLabel() {
        Path dir = locateSparkDir();
        return dir != null ? dir.toString() : "plugins/spark/";
    }

    private Path locateSparkDir() {
        Path cached = sparkDirCached;
        if (cached != null) return cached;
        Path found = findSparkDir();
        if (found != null) sparkDirCached = found;
        return found;
    }

    private Path findSparkDir() {
        String override = config.getString("spark.plugin-folder", "");
        if (override != null && !override.isEmpty()) {
            Path p = Paths.get(override);
            return Files.isDirectory(p) ? p : null;
        }
        StressHost h = effectiveHost();
        if (h != null) {
            try {
                for (StressHost.LoadedPlugin p : h.loadedPlugins()) {
                    if (p != null && "spark".equalsIgnoreCase(p.name()) && p.dataFolder() != null
                            && Files.isDirectory(p.dataFolder())) {
                        return p.dataFolder();
                    }
                }
                if (h.dataFolder() != null && h.dataFolder().getParent() != null) {
                    Path plugins = h.dataFolder().getParent();
                    for (String name : new String[] { "spark", "Spark" }) {
                        Path p = plugins.resolve(name);
                        if (Files.isDirectory(p)) return p;
                    }
                }
            } catch (Throwable ignored) { }
        }
        for (String name : new String[] { "plugins/spark", "plugins/Spark", "config/spark" }) {
            Path p = Paths.get(name);
            if (Files.isDirectory(p)) return p;
        }
        return null;
    }

    private void dispatch(String cmd) {
        StressHost owner = effectiveHost();
        if (owner == null) return;
        try {
            Sched.runGlobal(owner, () -> {
                try {
                    owner.dispatchConsoleCommand(cmd);
                } catch (Throwable t) {
                    if (log != null) log.log(Level.WARNING, "[StressTestRTP] spark dispatch failed: " + cmd, t);
                }
            });
        } catch (Throwable t) {
            try {
                owner.dispatchConsoleCommand(cmd);
            } catch (Throwable ignored) { }
        }
    }

    private StressHost effectiveHost() {
        return host != null ? host : Sched.boundHost();
    }

    private static String sanitize(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ':') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.length() == 0 ? "stresstestrtp" : out.toString();
    }
}
