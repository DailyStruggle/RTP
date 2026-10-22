package io.github.dailystruggle.bit;

import java.util.concurrent.atomic.AtomicLong;

/** Deterministic monotonic clock for initiated tests. */
public final class SimulatedClock {
    private final AtomicLong nanos;

    public SimulatedClock() {
        this(0L);
    }

    public SimulatedClock(long initialNanos) {
        if (initialNanos < 0) throw new IllegalArgumentException("initialNanos must not be negative");
        nanos = new AtomicLong(initialNanos);
    }

    public long nanoTime() {
        return nanos.get();
    }

    public long advanceNanos(long delta) {
        if (delta < 0) throw new IllegalArgumentException("delta must not be negative");
        return nanos.addAndGet(delta);
    }
}
