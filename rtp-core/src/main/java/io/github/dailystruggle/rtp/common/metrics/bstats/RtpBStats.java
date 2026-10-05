package io.github.dailystruggle.rtp.common.metrics.bstats;

import io.github.dailystruggle.bstats.api.BStatsLog;
import io.github.dailystruggle.bstats.api.BStatsPlatform;
import io.github.dailystruggle.bstats.api.BStatsScheduler;
import io.github.dailystruggle.bstats.api.BStatsService;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.common.RTP;

import java.io.File;
import java.util.logging.Level;

/**
 * The single bStats entry point for every RTP backend platform.
 *
 * <p>All platforms report to one bStats service ({@link BStatsPlatform#BUKKIT}
 * endpoint; bStats has no modded endpoint), so dashboards aggregate the whole
 * install base; the {@code platform} chart and the software name keep loaders
 * separable. Platforms differ only in their {@link RtpBStatsCatalogue.Host}.
 *
 * <p>Threading: scheduling goes through {@link RTP#scheduler} (rule F-001), logging
 * through {@link RTP#log}. No chunk I/O.
 */
public final class RtpBStats {

    /** Full assembly service id. */
    public static final int SERVICE_ID = 30865;
    /** Lite assembly service id (ADR-024). */
    public static final int LITE_SERVICE_ID = 12277;

    static final BStatsLog LOG = (level, message) -> RTP.log(level, "[RTP]" + message);

    private static volatile BStatsService service;

    private RtpBStats() {}

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
        if (RTP.scheduler == null) {
            RTP.log(Level.FINE, "[RTP][bStats] no scheduler wired; bStats not started");
            return null;
        }
        try {
            BStatsService s = create(host, serviceId, assemblyVariant, pluginDirectory, RTP.scheduler);
            if (s.start()) {
                RTP.log(Level.INFO, "[RTP][bStats] Enabled bStats telemetry for " + s.charts().size()
                        + " charts (" + host.platform() + ", id=" + serviceId + ").");
            }
            service = s;
            return s;
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][bStats] setup failed; continuing without metrics", t);
            return null;
        }
    }

    /** Stops submissions and allows a later {@link #start}. */
    public static synchronized void shutdown() {
        BStatsService s = service;
        service = null;
        if (s != null) s.shutdown();
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
