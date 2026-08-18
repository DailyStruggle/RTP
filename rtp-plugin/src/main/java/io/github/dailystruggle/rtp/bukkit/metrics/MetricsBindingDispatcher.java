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
 * <p>All failures degrade gracefully - the dispatcher is best-effort
 * observability and never aborts plugin enable. Failures log at
 * {@link Level#WARNING} via {@link RTP#log(Level, String, Throwable)}.
 */
public final class MetricsBindingDispatcher {

    /** Tag used to disambiguate the Paper / Spigot path in startup logs. */
    private static final String LOG_TAG = "[RTP]";

    /** Paper-only static method probe; presence implies Paper-flavoured runtime. */
    private static final String PAPER_PROBE_METHOD = "getTPS";

    /** Folia-only class probe; presence implies threaded-regions runtime. */
    private static final String FOLIA_PROBE_FQN =
            "io.papermc.paper.threadedregions.RegionizedServer";

    /** Resolved by FQN - see class-level rationale. */
    private static final String PAPER_BINDING_FQN =
            "io.github.dailystruggle.rtp.paper.metrics.PaperMetricsBinding";
    /** Resolved by FQN - see class-level rationale. */
    private static final String FOLIA_BINDING_FQN =
            "io.github.dailystruggle.rtp.folia.metrics.FoliaMetricsBinding";
    /** Resolved by FQN - see class-level rationale. */
    private static final String SPIGOT_SAMPLER_FQN =
            "io.github.dailystruggle.rtp.bukkitplatform.metrics.BukkitTpsSampler";
    /** Resolved by FQN - see class-level rationale. */
    private static final String SPARK_BINDING_FQN =
            "io.github.dailystruggle.rtp.bukkitplatform.metrics.SparkMetricsBinding";
    /** spark public-API probe class; presence implies the spark plugin is loaded. */
    private static final String SPARK_API_PROBE_FQN = "me.lucko.spark.api.SparkProvider";

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
     * Safe to call repeatedly - only the first call has effect.
     */
    public static synchronized void install() {
        if (installed) {
            RTP.log(Level.FINER, LOG_TAG + " install() noop (already installed)");
            return;
        }
        try {
            // Folia and Paper bindings are resolved by FQN and may be absent from
            // trimmed assemblies (e.g. the rtp-lite jar excludes
            // io/github/dailystruggle/rtp/folia/**). When the preferred binding is
            // not on the classpath, fall through to the next applicable path
            // rather than aborting with a WARNING + stack trace.
            if (isFoliaRuntime()) {
                MetricsBinding folia = instantiateBinding(
                        FOLIA_BINDING_FQN, "FoliaMetricsBinding (threaded-regions detected)");
                if (folia != null) {
                    // No external sampler task: FoliaRegionProcessor drives
                    // FoliaMetricsBinding#recordRegionTick from each region's
                    // own thread (C1.2b).
                    //
                    // Per-field merge: prefer spark's TPS/MSPT over the native
                    // Folia values when spark is present. Folia's native
                    // FoliaRegionTpsSampler derives MSPT from the inter-tick
                    // interval (~50 ms on a healthy server), not the real
                    // per-tick processing time, so /rtp info and the map
                    // visualizer showed a flat/incorrect MSPT. spark exposes a
                    // true MSPT statistic that is consistent on Folia. The
                    // SparkMetricsBinding delegates foliaRegions() to the Folia
                    // binding, so per-region samples are preserved.
                    RTP.log(Level.FINE, LOG_TAG
                            + " installed FoliaMetricsBinding (threaded-regions detected)");
                    RTP.metrics.setBinding(wrapWithSparkIfPresent(folia));
                    installed = true;
                    return;
                }
            }
            if (isPaperRuntime()) {
                MetricsBinding paper = instantiateBinding(
                        PAPER_BINDING_FQN, "PaperMetricsBinding (Bukkit#getTPS detected)");
                if (paper != null) {
                    RTP.log(Level.FINE, LOG_TAG
                            + " installed PaperMetricsBinding (Bukkit#getTPS detected)");
                    // Per-field merge: prefer spark's TPS/MSPT over the native
                    // Paper values when spark is present (Folia is handled above
                    // and intentionally stays on its native per-region binding).
                    RTP.metrics.setBinding(wrapWithSparkIfPresent(paper));
                    installed = true;
                    return;
                }
            }
            {
                Class<?> samplerClass = Class.forName(SPIGOT_SAMPLER_FQN);
                Object sampler = samplerClass.getDeclaredConstructor().newInstance();
                // Merge spark TPS/MSPT over the raw-Spigot sampler when present;
                // the sampler is still driven below so its values back the
                // merge's fallback and supply playerCount / softCap.
                RTP.metrics.setBinding(wrapWithSparkIfPresent((MetricsBinding) sampler));
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
                        // enough - the binding will simply continue to
                        // report UNSAMPLED, which is the documented sentinel.
                        RTP.log(Level.WARNING,
                                LOG_TAG + " BukkitTpsSampler.tick() invocation failed", t);
                    }
                }, 1L, 1L);
                RTP.log(Level.FINE,
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

    /**
     * Attempt to instantiate and install the {@link MetricsBinding} named by
     * {@code bindingFqn}. Returns {@code true} on success. Returns
     * {@code false} (without throwing) if the binding class is absent from the
     * runtime classpath - this is expected on trimmed assemblies such as the
     * rtp-lite jar, which excludes the platform metrics bindings. In that case
     * the caller falls through to the next applicable binding (or the raw
     * Spigot sampler), so {@code /rtp info} still reports sampled data instead
     * of logging a WARNING + stack trace.
     */
    private static boolean tryInstallBinding(String bindingFqn, String description) {
        try {
            MetricsBinding binding = (MetricsBinding) Class.forName(bindingFqn)
                    .getDeclaredConstructor()
                    .newInstance();
            RTP.metrics.setBinding(binding);
            RTP.log(Level.FINE, LOG_TAG + " installed " + description);
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            RTP.log(Level.FINE, LOG_TAG + " binding " + bindingFqn
                    + " not present (trimmed assembly?); falling back to next path");
            return false;
        } catch (Throwable t) {
            RTP.log(Level.WARNING, LOG_TAG + " failed to install " + description
                    + "; falling back to next path", t);
            return false;
        }
    }

    /**
     * Instantiate the {@link MetricsBinding} named by {@code bindingFqn} without
     * installing it. Returns {@code null} (without throwing) when the class is
     * absent from the runtime classpath (trimmed assembly) or instantiation
     * fails - the caller then falls through to the next path.
     */
    private static MetricsBinding instantiateBinding(String bindingFqn, String description) {
        try {
            return (MetricsBinding) Class.forName(bindingFqn)
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            RTP.log(Level.FINE, LOG_TAG + " binding " + bindingFqn
                    + " not present (trimmed assembly?); falling back to next path");
            return null;
        } catch (Throwable t) {
            RTP.log(Level.WARNING, LOG_TAG + " failed to instantiate " + description
                    + "; falling back to next path", t);
            return null;
        }
    }

    /**
     * Wrap {@code delegate} in {@code SparkMetricsBinding} when the spark plugin
     * (and its public API) is present, so spark's richer TPS/MSPT values are
     * merged over the native binding. Returns {@code delegate} unchanged when
     * spark is absent or the wrap fails - spark is a pure soft-dependency.
     *
     * <p>The {@code SparkMetricsBinding} itself self-disables (delegating every
     * field) if the spark API cannot be linked, so the up-front probe here is
     * an optimisation that avoids an unnecessary wrapper, not a correctness
     * requirement.
     */
    private static MetricsBinding wrapWithSparkIfPresent(MetricsBinding delegate) {
        try {
            Class.forName(SPARK_API_PROBE_FQN);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return delegate; // spark not installed - keep native binding
        } catch (Throwable t) {
            return delegate;
        }
        try {
            MetricsBinding wrapped = (MetricsBinding) Class.forName(SPARK_BINDING_FQN)
                    .getConstructor(MetricsBinding.class)
                    .newInstance(delegate);
            RTP.log(Level.FINE, LOG_TAG
                    + " spark detected; merging spark TPS/MSPT over native binding");
            return wrapped;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // SparkMetricsBinding absent (trimmed assembly) - keep native binding.
            RTP.log(Level.FINE, LOG_TAG + " SparkMetricsBinding not present; using native binding");
            return delegate;
        } catch (Throwable t) {
            RTP.log(Level.WARNING, LOG_TAG
                    + " spark present but SparkMetricsBinding wrap failed; using native binding", t);
            return delegate;
        }
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
            // Unexpected probe failure - fall back to Spigot path conservatively.
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
