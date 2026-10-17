package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import java.util.Map;

/**
 * Backwards-compatible facade forwarding to {@link io.github.dailystruggle.rtp.common.selection.region.RtpOutcomeStats}.
 */
public final class RtpOutcomeStats {

    public static final io.github.dailystruggle.rtp.common.selection.region.RtpOutcomeStats GLOBAL =
            io.github.dailystruggle.rtp.common.selection.region.RtpOutcomeStats.GLOBAL;

    private RtpOutcomeStats() {}

    public static void recordSuccess() {
        GLOBAL.recordSuccess();
    }

    public static void recordFailure(LocationGenerator.FailTypes cause) {
        GLOBAL.recordFailure(cause);
    }

    public static long successCount() {
        return GLOBAL.successCount();
    }

    public static long failureCount(LocationGenerator.FailTypes cause) {
        return GLOBAL.failureCount(cause);
    }

    public static long totalFailures() {
        return GLOBAL.totalFailures();
    }

    public static double successRate() {
        return GLOBAL.successRate();
    }

    public static Map<LocationGenerator.FailTypes, Long> failureBreakdown() {
        return GLOBAL.failureBreakdown();
    }

    public static void reset() {
        GLOBAL.reset();
    }
}
