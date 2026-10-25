package io.github.dailystruggle.rtp.common.runtime;

/**
 * Minimal test stub of {@code RTPRuntime} for {@link io.github.dailystruggle.helpers.stresstestrtp.SchedulerGaugeTest}.
 * Exposes {@link #scheduler()} as a static method (no public {@code scheduler} field).
 */
public final class RTPRuntime {

    private static volatile Object scheduler;

    private RTPRuntime() {
    }

    public static Object scheduler() {
        return scheduler;
    }

    public static void setScheduler(Object s) {
        scheduler = s;
    }
}
