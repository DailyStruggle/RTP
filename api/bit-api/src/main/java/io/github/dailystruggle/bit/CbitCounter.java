package io.github.dailystruggle.bit;

import java.util.concurrent.atomic.LongAdder;

/** Low-contention monotonically increasing continuous-test counter. */
public final class CbitCounter {
    private final LongAdder count = new LongAdder();
    private final LongAdder observations = new LongAdder();

    public void increment() {
        count.increment();
        observations.increment();
    }

    public void add(long delta) {
        if (delta < 0) throw new IllegalArgumentException("delta must not be negative");
        count.add(delta);
        observations.add(delta);
    }

    public void observe() {
        observations.increment();
    }

    public void observe(long samples) {
        if (samples < 0) throw new IllegalArgumentException("samples must not be negative");
        observations.add(samples);
    }

    public long count() {
        return count.sum();
    }

    public long observations() {
        return Math.max(count.sum(), observations.sum());
    }

    public boolean isObserved() {
        return observations() > 0;
    }

    public void reset() {
        count.reset();
        observations.reset();
    }
}
