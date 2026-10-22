package io.github.dailystruggle.bit;

import java.util.concurrent.atomic.LongAdder;

/** Low-contention monotonically increasing continuous-test counter. */
public final class CbitCounter {
    private final LongAdder count = new LongAdder();

    public void increment() {
        count.increment();
    }

    public void add(long delta) {
        if (delta < 0) throw new IllegalArgumentException("delta must not be negative");
        count.add(delta);
    }

    public long count() {
        return count.sum();
    }

    public void reset() {
        count.reset();
    }
}
