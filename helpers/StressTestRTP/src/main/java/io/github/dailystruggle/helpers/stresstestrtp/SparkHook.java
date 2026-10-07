package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional integration with the <a href="https://spark.lucko.me/">spark</a>
 * profiler. When enabled and spark is installed on the server, the hook
 * dispatches {@code /spark profiler start --timeout N [--only-ticks-over T]
 * [--thread S]} at the beginning of each measurement phase (TIMED run, BURST run, or
 * each SEQUENCE target phase) and {@code /spark profiler stop} at the
 * end, tagging the upload with the active target label via
 * {@code --comment <label>}.
 *
 * <p>Black-box timings (StressTestRTP CSV) and white-box timings (spark
 * profile) are correlated downstream by matching {@code target_label}
 * against the spark comment.
 *
 * <p>No hard dependency on spark - detects both a spark plugin and the
 * server-bundled spark (Paper 1.21+); when neither is present the hook logs
 * once and no-ops, mirroring {@link ConsoleWatcher}'s posture.
 */
public final class SparkHook {

    private final FileConfiguration config;
    private final Logger log;
    private volatile boolean inProfile = false;
    /** Base label of the currently-active phase (without rotation suffix). */
    private volatile String currentPhaseBase = null;
    /** Rotation index within the current phase. 0 = first slice. */
    private final java.util.concurrent.atomic.AtomicInteger currentRotation = new java.util.concurrent.atomic.AtomicInteger(0);
    /** Epoch ms when the current rotation slice started, for {@link #rotateIfDue(long)}. */
    private volatile long lastRotationStartMs = 0L;
    /** Located spark folder; cached only once found, since spark creates it lazily. */
    private volatile Path sparkDirCached = null;
    /** Interval between saved-profile polls. */
    static final long SUMMARY_POLL_MS = 1000L;

    public SparkHook(FileConfiguration config, Logger log) {
        this.config = config;
        this.log = log;
    }

    public boolean enabled() {
        return config.getBoolean("spark.enabled", true);
    }

    public boolean profilePerPhase() {
        return config.getBoolean("spark.profile-per-phase", true);
    }

    /** Rotation interval in seconds. While a phase is active and rotation is
     *  enabled, the harness calls {@link #rotateIfDue(long)} from its tick
     *  loop; once {@code rotate-seconds} have elapsed since the last
     *  start/rotation, the current spark profile is stopped (which writes a
     *  {@code .sparkprofile} file to disk via {@code --save-to-file}) and a
     *  fresh profile is started for the same phase, suffixed with a
     *  monotonically-increasing rotation index ({@code -r0}, {@code -r1}, ...).
     *
     *  <p>Rationale: a single phase can run for tens of minutes (a full
     *  stress run is 4×50 min). Spark only flushes to disk on stop; if the
     *  server crashes mid-phase (OOM, watchdog kill, host reboot) all
     *  profiling data for that phase is lost - exactly the failure mode
     *  observed in the 20260502-023640 run where the EssentialsX phase
     *  produced no spark output.
     *
     *  <p>Default: 0 (disabled - preserves prior behaviour). Set to e.g.
     *  300 to rotate every 5 minutes.
     */
    public long rotateSeconds() {
        // Default 60s: pairs with the default `spark.timeout-seconds: 90`
        // (rotation must fire before spark's own auto-stop, otherwise the
        // slice is uploaded to bytebin instead of saved to disk). Set to 0
        // in config.yml to opt out.
        return Math.max(0L, config.getLong("spark.rotate-seconds", 60L));
    }

    /** Logged once per hook so a missing spark is visible instead of silent. */
    private volatile boolean unavailableLogged = false;

    /** True when spark is installed as a plugin or bundled by the server.
     *  Paper 1.21+ bundles spark without registering a Bukkit plugin, so a
     *  plugin lookup alone misses it; its {@code /spark} command is still in
     *  the command map and accepts console dispatch. */
    private boolean sparkAvailable() {
        boolean available = sparkPluginInstalled() || sparkCommandRegistered();
        if (!available && !unavailableLogged) {
            unavailableLogged = true;
            if (log != null) log.info("[StressTestRTP] spark not detected (no spark plugin, no /spark command);"
                    + " per-phase profiling disabled.");
        }
        return available;
    }

    private static boolean sparkPluginInstalled() {
        try {
            return Bukkit.getPluginManager().getPlugin("spark") != null
                || Bukkit.getPluginManager().getPlugin("Spark") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code getCommandMap()} is public on CraftServer but not on the Spigot
     *  {@code Server} interface this module compiles against. */
    private static boolean sparkCommandRegistered() {
        try {
            Object map = Bukkit.getServer().getClass().getMethod("getCommandMap").invoke(Bukkit.getServer());
            return map instanceof org.bukkit.command.CommandMap
                && ((org.bukkit.command.CommandMap) map).getCommand("spark") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Start a spark profile tagged with the given label. No-op if disabled
     *  or spark is not installed. Safe to call from any thread. */
    public void startPhase(String label) {
        if (!enabled() || !profilePerPhase()) return;
        if (!sparkAvailable()) return;
        if (inProfile) {
            // A previous phase didn't stop cleanly; force-stop before restart.
            stopPhase(currentPhaseBase != null ? currentPhaseBase : label);
        }
        currentPhaseBase = label;
        currentRotation.set(0);
        lastRotationStartMs = System.currentTimeMillis();
        startSliceInternal(label);
    }

    /** Internal: dispatch the {@code spark profiler start} command for a
     *  single slice. Does not touch {@link #currentPhaseBase} or
     *  {@link #currentRotation}; callers manage those. */
    private void startSliceInternal(String sliceLabel) {
        long timeout = Math.max(5L, config.getLong("spark.timeout-seconds", 60L));
        // Default 0 = no filter (capture every sample). Spark's
        // `--only-ticks-over` makes a slice's saved profile effectively empty
        // when no tick in that slice exceeds the threshold - exactly the
        // "no data on the website" symptom seen on idle/light phases. The
        // baseline overhead of running `/rtp` is still clearly visible
        // against an idle server, so unfiltered profiling is the safer
        // default. Override in config.yml for spike-isolation runs.
        long onlyTicksOver = Math.max(0L, config.getLong("spark.only-ticks-over-ms", 0L));
        // Without a selector spark samples the server thread only, missing
        // chunk-system, scheduler and region threads.
        String threads = config.getString("spark.threads", "*");
        dispatch(buildStartCommand(timeout, onlyTicksOver, threads));
        inProfile = true;
        if (log != null) log.info("[StressTestRTP] spark profiler started for phase: " + sliceLabel);
    }

    /** {@code spark profiler start} command line. {@code threads} null/blank
     *  omits {@code --thread} (spark then samples the server thread only). */
    static String buildStartCommand(long timeoutSeconds, long onlyTicksOverMs, String threads) {
        StringBuilder sb = new StringBuilder("spark profiler start --timeout ").append(timeoutSeconds);
        if (onlyTicksOverMs > 0L) {
            sb.append(" --only-ticks-over ").append(onlyTicksOverMs);
        }
        String t = threads == null ? "" : threads.trim();
        if (!t.isEmpty()) {
            sb.append(" --thread ").append(t);
        }
        return sb.toString();
    }

    /** Stop the current spark profile, tagging the upload with the label.
     *
     *  <p>If {@code spark.save-to-file} is true (default), the profile is
     *  written to {@code plugins/spark/profile-<stamp>.sparkprofile}
     *  via spark's {@code --save-to-file} flag - no bytebin upload, no live
     *  webpage required. The file can be opened later with
     *  {@code /spark profiler --open <path>} or uploaded manually with
     *  {@code spark profiler --upload <path>}. Useful for overnight runs
     *  where bytebin uploads are undesirable (rate limits, disk-only
     *  policy, offline test rigs, etc.).
     *
     *  <p>If false, falls back to the default bytebin upload behaviour.
     */
    public void stopPhase(String label) {
        if (!enabled() || !profilePerPhase()) return;
        if (!sparkAvailable()) return;
        if (!inProfile) {
            currentPhaseBase = null;
            currentRotation.set(0);
            return;
        }
        // If rotation has been active, the on-disk filename must reflect both
        // the phase and the rotation index so successive slices don't
        // collide. Bare phase-label calls (rotation disabled) are unaffected.
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

    /** Rotate the in-progress profile if {@link #rotateSeconds()} has elapsed
     *  since the current slice started. Stops the current slice (writing a
     *  {@code .sparkprofile} file via {@code --save-to-file}) and immediately
     *  starts a fresh slice for the same phase, with the rotation index
     *  bumped. No-op when rotation is disabled, no phase is active, or
     *  spark is unavailable.
     *
     *  <p>Safe to call from the runner's tick loop (every tick); the time
     *  comparison is cheap and rotation only fires when due.
     *
     *  @param nowMs current epoch ms (passed in to avoid repeated calls to
     *               {@link System#currentTimeMillis()} from the tick loop).
     *  @return {@code true} if a rotation was performed this call.
     */
    public boolean rotateIfDue(long nowMs) {
        if (!enabled() || !profilePerPhase()) return false;
        if (!inProfile || currentPhaseBase == null) return false;
        long rotateMs = rotateSeconds() * 1000L;
        if (rotateMs <= 0L) return false;
        if (nowMs - lastRotationStartMs < rotateMs) return false;
        if (!sparkAvailable()) return false;

        // Emit a stop with the current slice's label (suffixed with -rN if
        // we're past the first slice), then bump the rotation index and
        // start a fresh slice. The next stop - whether triggered by a
        // subsequent rotation or by stopPhase() at end of phase - will use
        // the new (incremented) rotation index, so each .sparkprofile on
        // disk maps 1:1 with a distinct slice.
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

    /** Schedule an async task that waits for spark to finish saving the
     *  profile, then writes a {@code .summary.json} sidecar next to it via
     *  {@link SparkProfileSummariser}. The sidecar contains TPS / MSPT /
     *  per-thread CPU aggregates suitable for direct AI consumption -
     *  replacing the spark.lucko.me website round-trip.
     *
     *  <p>Spark saves asynchronously and can take several seconds (observed
     *  ~6 s on Windows), so the task polls every {@link #SUMMARY_POLL_MS}
     *  after {@code auto-summary-delay-ticks} until
     *  {@code auto-summary-timeout-seconds} after the stop. */
    private void scheduleSummarise(String label, long stopEpochMs) {
        if (!config.getBoolean("spark.auto-summary", true)) return;
        long delayMs = Math.max(1L, config.getLong("spark.auto-summary-delay-ticks", 60L)) * 50L;
        long timeoutMs = Math.max(1L, config.getLong("spark.auto-summary-timeout-seconds", 30L)) * 1000L;
        org.bukkit.plugin.Plugin owner = Bukkit.getPluginManager().getPlugin("StressTestRTP");
        try {
            // Pure file I/O with sleeps: async only, never on a tick thread.
            Sched.runAsync(owner, () -> awaitAndSummarise(label, stopEpochMs, delayMs, timeoutMs));
        } catch (Throwable t) {
            // Shutdown path: polling inline would block the caller thread.
            if (log != null) log.fine("[StressTestRTP] spark auto-summary: scheduler unavailable, skipping (label="
                    + label + ").");
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
                    // Spark may create its folder on first save; files written
                    // before this snapshot still qualify via mtime >= stop.
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

    /** Detects the profile spark saves after a given stop. A file qualifies if
     *  it was absent at construction or its mtime is at/after the stop (spark
     *  may have started writing before the snapshot). It is reported only once
     *  its size is non-zero and unchanged across two consecutive polls, since
     *  spark may still be writing. Not thread-safe; one poller per watch. */
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

        /** The saved profile once stable, else {@code null}. */
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

    /** Spark folder for log text; {@code plugins/spark/} when not yet located. */
    private String sparkDirLabel() {
        Path dir = locateSparkDir();
        return dir != null ? dir.toString() : "plugins/spark/";
    }

    /** Cached {@link #findSparkDir()}; a miss is not cached because spark
     *  creates its folder lazily. */
    private Path locateSparkDir() {
        Path cached = sparkDirCached;
        if (cached != null) return cached;
        Path found = findSparkDir();
        if (found != null) sparkDirCached = found;
        return found;
    }

    /** Locate spark's plugin folder. Defaults to {@code plugins/spark} (or
     *  {@code plugins/Spark}); operators may override via
     *  {@code spark.plugin-folder} in {@code config.yml}. */
    private Path findSparkDir() {
        String override = config.getString("spark.plugin-folder", "");
        if (override != null && !override.isEmpty()) {
            Path p = Paths.get(override);
            return Files.isDirectory(p) ? p : null;
        }
        try {
            org.bukkit.plugin.Plugin spark = Bukkit.getPluginManager().getPlugin("spark");
            if (spark == null) spark = Bukkit.getPluginManager().getPlugin("Spark");
            if (spark != null) return spark.getDataFolder().toPath();
            // Bundled spark (Paper) has no plugin object but keeps the same
            // folder beside the other plugin data folders.
            org.bukkit.plugin.Plugin owner = Bukkit.getPluginManager().getPlugin("StressTestRTP");
            if (owner != null && owner.getDataFolder().getParentFile() != null) {
                Path plugins = owner.getDataFolder().getParentFile().toPath();
                for (String name : new String[] { "spark", "Spark" }) {
                    Path p = plugins.resolve(name);
                    if (Files.isDirectory(p)) return p;
                }
            }
        } catch (Throwable ignored) { }
        // Best-effort fallback: relative to CWD.
        for (String name : new String[] { "plugins/spark", "plugins/Spark" }) {
            Path p = Paths.get(name);
            if (Files.isDirectory(p)) return p;
        }
        return null;
    }

    private void dispatch(String cmd) {
        org.bukkit.plugin.Plugin owner = Bukkit.getPluginManager().getPlugin("StressTestRTP");
        try {
            // spark commands are safe to invoke from console; spark itself
            // hops to its own thread for sampling. Route through Sched.runGlobal
            // so on Folia we land on the GlobalRegionScheduler (where console
            // command dispatch is allowed); on Spigot/Paper this falls through
            // to the main thread.
            Sched.runGlobal(owner, () -> {
                try {
                    Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), cmd);
                } catch (Throwable t) {
                    if (log != null) log.log(Level.WARNING, "[StressTestRTP] spark dispatch failed: " + cmd, t);
                }
            });
        } catch (Throwable t) {
            // Shutdown path: fall back to direct dispatch.
            try {
                Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Throwable ignored) { }
        }
    }

    private static String sanitize(String s) {
        // spark's --comment takes a single token; replace whitespace and
        // strip anything that would break the parser.
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
