package io.github.dailystruggle.rtp.common.network.pluginmessage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Aggregating WARNING emitter for untrusted-input rejections: at most one line
 * per {@code intervalMs}, carrying the count of occurrences folded into it, so
 * a message spammer cannot flood the server log (REQ-RTP-S-004 audit stays on).
 */
public final class ThrottledWarning {

    /** Default aggregation window. */
    public static final long DEFAULT_INTERVAL_MS = 60_000L;

    private final Consumer<String> sink;
    private final long intervalMs;
    private final LongSupplier clock;
    private final AtomicLong lastEmitMs = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong total = new AtomicLong();

    public ThrottledWarning(Consumer<String> sink, long intervalMs, LongSupplier clock) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.intervalMs = intervalMs > 0 ? intervalMs : DEFAULT_INTERVAL_MS;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Record one occurrence; emits {@code message} (with the aggregated count)
     * when the window has elapsed, otherwise only counts it.
     *
     * @return {@code true} when a line was emitted
     */
    public boolean report(String message) {
        total.incrementAndGet();
        long n = pending.incrementAndGet();
        long now = clock.getAsLong();
        long last = lastEmitMs.get();
        if (last != Long.MIN_VALUE && now - last < intervalMs) return false;
        if (!lastEmitMs.compareAndSet(last, now)) return false;
        long folded = pending.getAndSet(0);
        if (folded <= 0) folded = n;
        try {
            sink.accept(message + (folded > 1
                    ? " [" + folded + " occurrence(s) in the last " + (intervalMs / 1000L) + "s]"
                    : ""));
        } catch (RuntimeException ignored) {
            // Logging sink failure must not break the inbound path.
        }
        return true;
    }

    /** Total occurrences reported since construction (emitted or folded). */
    public long total() {
        return total.get();
    }
}
