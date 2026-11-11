package io.github.dailystruggle.rtp.common.metrics.bstats;

import io.github.dailystruggle.bstats.api.BStatsLog;
import io.github.dailystruggle.bstats.api.BStatsPlatform;
import io.github.dailystruggle.bstats.api.BStatsScheduler;
import io.github.dailystruggle.bstats.api.BStatsService;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.common.RTP;

import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Enumeration;
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;

/**
 * The single bStats entry point for every RTP backend platform.
 *
 * <p>All platforms report to one bStats service ({@link BStatsPlatform#BUKKIT}
 * endpoint; bStats has no modded endpoint), so dashboards aggregate the whole
 * install base; the {@code platform} chart and the software name keep loaders
 * separable. Platforms differ only in their {@link RtpBStatsCatalogue.Host}.
 *
 * <p>Threading: scheduling goes through {@link RTPRuntime#scheduler()} (rule F-001), logging
 * through {@link RTP#log}. No chunk I/O.
 */
public final class RtpBStats {

    /** Full / supporter assembly service id. */
    public static final int SERVICE_ID = 30865;
    /** Free / community assembly service id (ADR-024). */
    public static final int LITE_SERVICE_ID = 12277;

    /** Manifest attribute recording the distribution tier ({@code "free"} vs {@code "supporter"}). */
    public static final String MANIFEST_DISTRIBUTION_ATTR = "LeafRTP-Distribution";
    /** Free / community distribution value in {@link #MANIFEST_DISTRIBUTION_ATTR}. */
    public static final String DISTRIBUTION_FREE = "free";
    /** Supporter / Pro distribution value in {@link #MANIFEST_DISTRIBUTION_ATTR}. */
    public static final String DISTRIBUTION_SUPPORTER = "supporter";

    static final BStatsLog LOG = (level, message) -> RTPRuntime.log(level, "[RTP]" + message);

    private static volatile BStatsService service;

    private RtpBStats() {}

    /**
     * Resolves the bStats service ID from the jar's {@code META-INF/MANIFEST.MF}
     * {@link #MANIFEST_DISTRIBUTION_ATTR} attribute. Returns {@link #LITE_SERVICE_ID} (12277)
     * when set to {@code "free"} or {@code "lite"}, and {@link #SERVICE_ID} (30865) otherwise.
     */
    public static int resolveServiceId(Class<?> anchor) {
        return resolveServiceIdFromDistribution(readDistributionAttribute(anchor));
    }

    /**
     * Resolves the assembly variant chart label ({@code "lite"} for free distribution,
     * {@code "full"} for supporter/default) from the jar's {@code META-INF/MANIFEST.MF}.
     */
    public static String resolveAssemblyVariant(Class<?> anchor) {
        return resolveVariantFromDistribution(readDistributionAttribute(anchor));
    }

    static int resolveServiceIdFromDistribution(String distributionValue) {
        return isFreeDistribution(distributionValue) ? LITE_SERVICE_ID : SERVICE_ID;
    }

    static String resolveVariantFromDistribution(String distributionValue) {
        return isFreeDistribution(distributionValue) ? "lite" : "full";
    }

    private static boolean isFreeDistribution(String distributionValue) {
        if (distributionValue == null) return false;
        String normalized = distributionValue.trim().toLowerCase(Locale.ROOT);
        return DISTRIBUTION_FREE.equals(normalized) || "lite".equals(normalized);
    }

    static String readDistributionFromManifest(Manifest manifest) {
        if (manifest == null || manifest.getMainAttributes() == null) return null;
        String val = manifest.getMainAttributes().getValue(MANIFEST_DISTRIBUTION_ATTR);
        return (val != null && !val.trim().isEmpty()) ? val.trim() : null;
    }

    static String readDistributionFromStream(InputStream in) throws IOException {
        if (in == null) return null;
        return readDistributionFromManifest(new Manifest(in));
    }

    static String readDistributionAttribute(Class<?> anchor) {
        if (anchor == null) return null;
        // 1. Check the class URL's own archive manifest first (works for URLClassLoader, Paper, Fabric).
        try {
            URL classUrl = anchor.getResource(anchor.getSimpleName() + ".class");
            if (classUrl != null) {
                URLConnection conn = classUrl.openConnection();
                if (conn instanceof JarURLConnection) {
                    String val = readDistributionFromManifest(((JarURLConnection) conn).getManifest());
                    if (val != null) return val;
                }
                String spec = classUrl.toString();
                int bang = spec.indexOf("!/");
                if (bang > 0) {
                    URL manifestUrl = new URL(classUrl, spec.substring(0, bang + 2) + "META-INF/MANIFEST.MF");
                    try (InputStream in = manifestUrl.openStream()) {
                        String val = readDistributionFromStream(in);
                        if (val != null) return val;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 2. Check the ProtectionDomain CodeSource location (jar file or unpacked directory).
        try {
            ProtectionDomain pd = anchor.getProtectionDomain();
            CodeSource cs = pd != null ? pd.getCodeSource() : null;
            URL loc = cs != null ? cs.getLocation() : null;
            if (loc != null && "file".equalsIgnoreCase(loc.getProtocol())) {
                File file = new File(loc.toURI());
                if (file.isFile()) {
                    try (JarFile jar = new JarFile(file)) {
                        String val = readDistributionFromManifest(jar.getManifest());
                        if (val != null) return val;
                    }
                } else if (file.isDirectory()) {
                    File mf = new File(file, "META-INF/MANIFEST.MF");
                    if (mf.isFile()) {
                        try (InputStream in = new FileInputStream(mf)) {
                            String val = readDistributionFromStream(in);
                            if (val != null) return val;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 3. Fallback: scan classloader manifests for one carrying LeafRTP-Distribution.
        try {
            ClassLoader cl = anchor.getClassLoader();
            if (cl != null) {
                Enumeration<URL> urls = cl.getResources("META-INF/MANIFEST.MF");
                while (urls.hasMoreElements()) {
                    try (InputStream in = urls.nextElement().openStream()) {
                        String val = readDistributionFromStream(in);
                        if (val != null) return val;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Builds, registers and schedules the service once per JVM; later calls return
     * the running instance. Never throws.
     *
     * @param serviceId       {@link #SERVICE_ID} or {@link #LITE_SERVICE_ID}
     * @param assemblyVariant {@code "full"} or {@code "lite"}
     * @param pluginDirectory RTP's data folder; the shared config is its sibling {@code bStats/}
     * @return the service, or {@code null} when no scheduler is wired or setup failed
     */
    public static synchronized BStatsService start(RtpBStatsCatalogue.Host host, int serviceId,
                                                   String assemblyVariant, File pluginDirectory) {
        if (service != null) return service;
        if (RTPRuntime.scheduler() == null) {
            RTPRuntime.log(Level.FINE, "[RTP][bStats] no scheduler wired; bStats not started");
            return null;
        }
        try {
            BStatsService s = create(host, serviceId, assemblyVariant, pluginDirectory, RTPRuntime.scheduler());
            if (s.start()) {
                RTPRuntime.log(Level.INFO, "[RTP][bStats] Enabled bStats telemetry for " + s.charts().size()
                        + " charts (" + host.platform() + ", id=" + serviceId + ").");
            }
            service = s;
            return s;
        } catch (Throwable t) {
            RTPRuntime.log(Level.WARNING, "[RTP][bStats] setup failed; continuing without metrics", t);
            return null;
        }
    }

    /** Stops submissions and allows a later {@link #start}. */
    public static synchronized void shutdown() {
        BStatsService s = service;
        service = null;
        if (s != null) s.shutdown();
        RtpBStatsCatalogue.stopRtpCostSampler();
    }

    /** The running service, or {@code null}. */
    public static BStatsService service() {
        return service;
    }

    /** Builds and registers the catalogue without scheduling it. */
    static BStatsService create(RtpBStatsCatalogue.Host host, int serviceId, String assemblyVariant,
                                File pluginDirectory, RTPScheduler scheduler) {
        BStatsService s = BStatsService.builder(BStatsPlatform.BUKKIT, serviceId)
                .pluginDirectory(pluginDirectory)
                .configFormat(host.configFormat())
                .pluginVersion(host::pluginVersion)
                .serverInfo(host::serverInfo)
                .collectOnMainThread(host.collectOnMainThread())
                .scheduler(scheduler(scheduler))
                .log(LOG)
                .build();
        RtpBStatsCatalogue.register(s, host, assemblyVariant);
        return s;
    }

    /** {@link RTPScheduler} as a {@link BStatsScheduler}. */
    static BStatsScheduler scheduler(RTPScheduler rtp) {
        return new BStatsScheduler() {
            @Override
            public void runOnMainThread(Runnable task) {
                rtp.runTask(task);
            }

            @Override
            public void runAsync(Runnable task) {
                rtp.runTaskAsynchronously(task);
            }

            @Override
            public void runLater(Runnable task, long delayTicks) {
                rtp.runTaskLater(task, delayTicks);
            }

            @Override
            public Object runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
                return rtp.runTaskTimerAsynchronously(task, delayTicks, periodTicks);
            }

            @Override
            public void cancel(Object handle) {
                rtp.cancelTask(handle);
            }
        };
    }
}
