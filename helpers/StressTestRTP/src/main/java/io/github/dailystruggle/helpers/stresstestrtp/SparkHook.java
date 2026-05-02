package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional integration with the <a href="https://spark.lucko.me/">spark</a>
 * profiler. When enabled and spark is installed on the server, the hook
 * dispatches {@code /spark profiler start --timeout N --only-ticks-over T}
 * at the beginning of each measurement phase (TIMED run, BURST run, or
 * each SEQUENCE target phase) and {@code /spark profiler stop} at the
 * end, tagging the upload with the active target label via
 * {@code --comment <label>}.
 *
 * <p>Black-box timings (StressTestRTP CSV) and white-box timings (spark
 * profile) are correlated downstream by matching {@code target_label}
 * against the spark comment.
 *
 * <p>No hard dependency on spark — when the plugin is missing the hook
 * silently no-ops, mirroring {@link ConsoleWatcher}'s posture.
 */
public final class SparkHook {

    private final FileConfiguration config;
    private final Logger log;
    private volatile boolean inProfile = false;
    /** Base label of the currently-active phase (without rotation suffix). */
    private volatile String currentPhaseBase = null;
    /** Rotation index within the current phase. 0 = first slice. */
    private volatile int currentRotation = 0;
    /** Epoch ms when the current rotation slice started, for {@link #rotateIfDue(long)}. */
    private volatile long lastRotationStartMs = 0L;

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
     *  profiling data for that phase is lost — exactly the failure mode
     *  observed in the 20260502-023640 run where the EssentialsX phase
     *  produced no spark output.
     *
     *  <p>Default: 0 (disabled — preserves prior behaviour). Set to e.g.
     *  300 to rotate every 5 minutes.
     */
    public long rotateSeconds() {
        // Default 60s: pairs with the default `spark.timeout-seconds: 90`
        // (rotation must fire before spark's own auto-stop, otherwise the
        // slice is uploaded to bytebin instead of saved to disk). Set to 0
        // in config.yml to opt out.
        return Math.max(0L, config.getLong("spark.rotate-seconds", 60L));
    }

    private boolean sparkAvailable() {
        try {
            return Bukkit.getPluginManager().getPlugin("spark") != null
                || Bukkit.getPluginManager().getPlugin("Spark") != null;
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
        currentRotation = 0;
        lastRotationStartMs = System.currentTimeMillis();
        startSliceInternal(label);
    }

    /** Internal: dispatch the {@code spark profiler start} command for a
     *  single slice. Does not touch {@link #currentPhaseBase} or
     *  {@link #currentRotation}; callers manage those. */
    private void startSliceInternal(String sliceLabel) {
        long timeout = Math.max(5L, config.getLong("spark.timeout-seconds", 60L));
        long onlyTicksOver = Math.max(0L, config.getLong("spark.only-ticks-over-ms", 50L));
        StringBuilder sb = new StringBuilder("spark profiler start --timeout ").append(timeout);
        if (onlyTicksOver > 0L) {
            sb.append(" --only-ticks-over ").append(onlyTicksOver);
        }
        dispatch(sb.toString());
        inProfile = true;
        if (log != null) log.info("[StressTestRTP] spark profiler started for phase: " + sliceLabel);
    }

    /** Stop the current spark profile, tagging the upload with the label.
     *
     *  <p>If {@code spark.save-to-file} is true (default), the profile is
     *  written to {@code plugins/spark/profiles/<stamp>-<label>.sparkprofile}
     *  via spark's {@code --save-to-file} flag — no bytebin upload, no live
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
            currentRotation = 0;
            return;
        }
        // If rotation has been active, the on-disk filename must reflect both
        // the phase and the rotation index so successive slices don't
        // collide. Bare phase-label calls (rotation disabled) are unaffected.
        String slice = (currentRotation > 0)
                ? (label == null || label.isEmpty() ? "stresstestrtp" : label) + "-r" + currentRotation
                : (label == null || label.isEmpty() ? "stresstestrtp" : label);
        String comment = sanitize(slice);
        boolean saveToFile = config.getBoolean("spark.save-to-file", true);
        StringBuilder sb = new StringBuilder("spark profiler stop --comment ").append(comment);
        if (saveToFile) {
            sb.append(" --save-to-file");
        }
        dispatch(sb.toString());
        inProfile = false;
        currentPhaseBase = null;
        currentRotation = 0;
        if (log != null) {
            log.info("[StressTestRTP] spark profiler stopped for phase: " + slice
                + (saveToFile ? " (saved to plugins/spark/profiles/)" : " (uploaded to bytebin)"));
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
        // start a fresh slice. The next stop — whether triggered by a
        // subsequent rotation or by stopPhase() at end of phase — will use
        // the new (incremented) rotation index, so each .sparkprofile on
        // disk maps 1:1 with a distinct slice.
        String base = currentPhaseBase;
        String stoppingSlice = (currentRotation > 0) ? base + "-r" + currentRotation : base;
        String stoppingComment = sanitize(stoppingSlice);
        boolean saveToFile = config.getBoolean("spark.save-to-file", true);
        StringBuilder sb = new StringBuilder("spark profiler stop --comment ").append(stoppingComment);
        if (saveToFile) sb.append(" --save-to-file");
        dispatch(sb.toString());
        if (log != null) {
            log.info("[StressTestRTP] spark profiler rotated slice: " + stoppingSlice
                + (saveToFile ? " (saved to plugins/spark/profiles/)" : " (uploaded to bytebin)"));
        }

        currentRotation++;
        lastRotationStartMs = nowMs;
        String nextSlice = base + "-r" + currentRotation;
        startSliceInternal(nextSlice);
        return true;
    }

    private void dispatch(String cmd) {
        try {
            // spark commands are safe to invoke from console; spark itself
            // hops to its own thread for sampling.
            Bukkit.getScheduler().runTask(
                    Bukkit.getPluginManager().getPlugin("StressTestRTP"),
                    () -> {
                        try {
                            Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), cmd);
                        } catch (Throwable t) {
                            if (log != null) log.log(Level.WARNING, "[StressTestRTP] spark dispatch failed: " + cmd, t);
                        }
                    });
        } catch (Throwable t) {
            // Folia or shutdown path: fall back to direct dispatch.
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
