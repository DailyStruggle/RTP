package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitSelector;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.List;
import java.util.logging.Level;

/** Schedules the shared SAFE PBIT sweep after platform startup (ADR-121). */
public final class RtpBitStartup {
    private RtpBitStartup() {}

    /** Queues PBIT for tick +2 so the tick +1 database-accessor startup completes first. */
    public static void schedule() {
        RtpBitComponents.registerDefaults();
        RTPScheduler scheduler = RTPRuntime.scheduler();
        if (scheduler == null) {
            RTPRuntime.log(Level.WARNING, "[RTP bit] startup PBIT was not scheduled: RTP.scheduler is unavailable.");
            return;
        }
        try {
            scheduler.runTaskLater(() -> scheduler.runTaskAsynchronously(RtpBitStartup::runNow), 2);
        } catch (Throwable failure) {
            RTPRuntime.log(Level.WARNING, "[RTP bit] startup PBIT scheduling failed.", failure);
        }
    }

    static List<BitResult> runNow() {
        BitSeverityPolicy.reportUnresolvedOverrides();
        List<BitResult> results = RtpBit.RUNNER.run(BitSelector.parse("tier:pbit"), Runnable::run);
        BitReport report = RtpBit.RUNNER.aggregate(results);
        RTPRuntime.log(report.health() == BitHealth.HEALTHY ? Level.INFO : Level.WARNING, summary(results));
        return results;
    }

    static String summary(List<BitResult> results) {
        BitReport report = RtpBit.RUNNER.aggregate(results);
        long errors = report.findings().stream()
                .filter(finding -> finding.severity() == java.lang.System.Logger.Level.ERROR).count();
        long warnings = report.findings().stream()
                .filter(finding -> finding.severity() == java.lang.System.Logger.Level.WARNING).count();
        String nonHealthy = results.stream()
                .filter(result -> result.report().health() != BitHealth.HEALTHY)
                .map(result -> result.component().id())
                .sorted()
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return "[RTP bit] startup PBIT health=" + report.health()
                + " components=" + results.size()
                + " errors=" + errors
                + " warnings=" + warnings
                + " nonHealthy=" + nonHealthy;
    }
}
