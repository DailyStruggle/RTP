package io.github.dailystruggle.rtp.bukkit.metrics;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.metrics.api.MetricsBinding;

import java.util.logging.Level;

/**
 * Phase-M1 wiring (CHECKLIST-metrics-and-multiserver.md row B9): install the
 * platform-appropriate {@link MetricsBinding} into {@link RTP#metrics} at
 * plugin enable, and tear it down at plugin disable.
 *
 * <p>Both bindings live in modules ({@code rtp-paper-common} and
 * {@code rtp-bukkit-common}) that {@code rtp-plugin} consumes only
 * transitively via the version-specific adapter jars (e.g.
 * {@code rtp-paper-v1_20_R1}, {@code rtp-bukkit-v1_20_R1}). The transitive
 * deps are declared as plain {@code implementation} (not {@code api}) in
 * those adapter modules, so {@code rtp-plugin}'s {@code compileJava} cannot
 * import the binding classes directly. We resolve them by FQN via
 * {@link Class#forName(String)}, matching the pattern already used here for
 * {@code BookMenuRenderer} (see {@code RTPCmdBukkit}).
 *
 * <p>Platform selection (per {@code METRICS_PLAN.md > Spigot TPS Fallback}):
 * <ul>
 *   <li>If Paper is present ({@code Server#getTPS()} reflectively reachable
 *       on {@code org.bukkit.Bukkit}), install {@code PaperMetricsBinding}.
 *       No per-tick sampler is needed; Paper publishes the values itself.</li>
 *   <li>Otherwise (raw Spigot 1.20.1+), install {@code BukkitTpsSampler}
 *       and schedule a 1-tick repeating task on {@link RTP#scheduler} that
 *       drives its {@code tick()} method.</li>
 * </ul>
 *
 * <p>Idempotent: {@link #install()} sets a flag so a hot reload does not
 * double-install. {@link #uninstall()} cancels the sampler task (if any)
 * and clears the binding back to NOOP.
 *
 * <p>All failures degrade gracefully — the dispatcher is best-effort
 * observability and never aborts plugin enable. Failures log at
 * {@link Level#WARNING} via {@link RTP#log(Level, String, Throwable)}.
 */
public final class MetricsBindingDispatcher {

    /** Tag used to disambiguate the Paper / Spigot path in startup logs. */
    private static final String LOG_TAG = "[METRICS]";

    /** Paper-only static method probe; presence implies Paper-flavoured runtime. */
    private static final String PAPER_PROBE_METHOD = "getTPS";

    /** Folia-only class probe; presence implies threaded-regions runtime. */
    private static final String FOLIA_PROBE_FQN =
            "io.papermc.paper.threadedregions.RegionizedServer";

    /** Resolved by FQN — see class-level rationale. */
    private static final String PAPER_BINDING_FQN =
            "io.github.dailystruggle.rtp.paper.metrics.PaperMetricsBinding";
    /** Resolved by FQN — see class-level rationale. */
    private static final String FOLIA_BINDING_FQN =
            "io.github.dailystruggle.rtp.folia.metrics.FoliaMetricsBinding";
    /** Resolved by FQN — see class-level rationale. */
    private static final String SPIGOT_SAMPLER_FQN =
            "io.github.dailystruggle.rtp.bukkitplatform.metrics.BukkitTpsSampler";

    private static volatile boolean installed = false;
    /** Non-null only on the Spigot path; cancelled in {@link #uninstall()}. */
    private static volatile Object spigotSamplerTaskHandle = null;
    /** Non-null only on the Spigot path; reference retained for diagnostics. */
    private static volatile Object spigotSamplerInstance = null;

    private MetricsBindingDispatcher() {
        // static helper
    }

    /**
     * Install the platform-appropriate binding into {@link RTP#metrics}.
     * Safe to call repeatedly — only the first call has effect.
     */
    public static synchronized void install() {
        if (installed) {
            RTP.log(Level.FINER, LOG_TAG + " install() noop (already installed)");
            return;
        }
        try {
            if (isFoliaRuntime()) {
                MetricsBinding folia = (MetricsBinding) Class.forName(FOLIA_BINDING_FQN)
                        .getDeclaredConstructor()
                        .newInstance();
                RTP.metrics.setBinding(folia);
                // No external sampler task: FoliaRegionProcessor drives
                // FoliaMetricsBinding#recordRegionTick from each region's
                // own thread (C1.2b).
                RTP.log(Level.INFO, LOG_TAG + " installed FoliaMetricsBinding (threaded-regions detected)");
            } else if (isPaperRuntime()) {
                MetricsBinding paper = (MetricsBinding) Class.forName(PAPER_BINDING_FQN)
                        .getDeclaredConstructor()
                        .newInstance();
                RTP.metrics.setBinding(paper);
                RTP.log(Level.INFO, LOG_TAG + " installed PaperMetricsBinding (Bukkit#getTPS detected)");
            } else {
                Class<?> samplerClass = Class.forName(SPIGOT_SAMPLER_FQN);
                Object sampler = samplerClass.getDeclaredConstructor().newInstance();
                RTP.metrics.setBinding((MetricsBinding) sampler);
                spigotSamplerInstance = sampler;
                // Drive sampler.tick() once per server tick. The sampler is
                // documented as single-tick-thread-only, which matches
                // RTP.scheduler.runTaskTimer (global region scheduler on Folia,
                // main thread on Spigot/Paper).
                java.lang.reflect.Method tick = samplerClass.getMethod("tick");
                spigotSamplerTaskHandle = RTP.scheduler.runTaskTimer(() -> {
                    try {
                        tick.invoke(sampler);
                    } catch (Throwable t) {
                        // Don't spam: a single warning on first failure is
                        // enough — the binding will simply continue to
                        // report UNSAMPLED, which is the documented sentinel.
                        RTP.log(Level.WARNING,
                                LOG_TAG + " BukkitTpsSampler.tick() invocation failed", t);
                    }
                }, 1L, 1L);
                RTP.log(Level.INFO,
                        LOG_TAG + " installed BukkitTpsSampler (raw Spigot fallback, 1-tick sampler)");
            }
            installed = true;
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    LOG_TAG + " failed to install MetricsBinding; /rtp info will report UNSAMPLED", t);
        }
    }

    /**
     * Tear down the binding and cancel the sampler task (if any).
     * Safe to call repeatedly.
     */
    public static synchronized void uninstall() {
        if (!installed) return;
        Object handle = spigotSamplerTaskHandle;
        spigotSamplerTaskHandle = null;
        spigotSamplerInstance = null;
        if (handle != null) {
            try {
                // The scheduler returns Object; the underlying type exposes
                // cancel() on every supported platform (BukkitTask /
                // ScheduledTask / our Folia wrapper). Reflectively invoke to
                // avoid binding rtp-plugin to a platform-specific task type.
                handle.getClass().getMethod("cancel").invoke(handle);
            } catch (Throwable t) {
                RTP.log(Level.FINER,
                        LOG_TAG + " sampler task cancel() failed (ignored): "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        try {
            RTP.metrics.setBinding(null);
        } catch (Throwable t) {
            RTP.log(Level.FINER,
                    LOG_TAG + " setBinding(null) failed (ignored): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        installed = false;
        RTP.log(Level.FINE, LOG_TAG + " uninstalled MetricsBinding");
    }

    /** Visible for tests. */
    public static boolean isInstalled() {
        return installed;
    }

    /**
     * Reflective probe for Paper. Paper (since 1.16) declares
     * {@code double[] getTPS()} as a static method on {@link org.bukkit.Bukkit};
     * raw Spigot does not. Reflection is used here rather than
     * {@code Class.forName("io.papermc...")} because it tests the actual
     * runtime capability the binding depends on, not just the presence of
     * a Paper class.
     */
    private static boolean isPaperRuntime() {
        try {
            Class.forName("org.bukkit.Bukkit").getMethod(PAPER_PROBE_METHOD);
            return true;
        } catch (NoSuchMethodException | ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            // Unexpected probe failure — fall back to Spigot path conservatively.
            RTP.log(Level.FINER,
                    LOG_TAG + " Paper probe failed (" + t.getClass().getSimpleName()
                            + "); assuming raw Spigot");
            return false;
        }
    }

    /**
     * Reflective probe for Folia. Folia ships
     * {@code io.papermc.paper.threadedregions.RegionizedServer}; raw Paper
     * and raw Spigot do not. This must be checked *before* the Paper probe
     * because Folia is also a Paper-flavoured runtime.
     */
    private static boolean isFoliaRuntime() {
        try {
            Class.forName(FOLIA_PROBE_FQN);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            RTP.log(Level.FINER,
                    LOG_TAG + " Folia probe failed (" + t.getClass().getSimpleName()
                            + "); assuming non-Folia");
            return false;
        }
    }
}
