package io.github.dailystruggle.bit;

import java.util.concurrent.atomic.LongAdder;

/** Counts gate evaluations and classifies gates that never or always pass after a sample threshold. */
public final class GateOutcomeCounter {
    public enum Outcome { INSUFFICIENT, NEVER_PASSED, ALWAYS_PASSED, MIXED }

    private final LongAdder evaluated = new LongAdder();
    private final LongAdder passed = new LongAdder();

    public void recordOutcome(boolean didPass) {
        evaluated.increment();
        if (didPass) passed.increment();
    }

    public long evaluated() {
        return evaluated.sum();
    }

    public long passed() {
        return passed.sum();
    }

    public Outcome outcome(long minimumSamples) {
        if (minimumSamples < 1) throw new IllegalArgumentException("minimumSamples must be positive");
        long total = evaluated();
        long successes = passed();
        if (total < minimumSamples) return Outcome.INSUFFICIENT;
        if (successes == 0) return Outcome.NEVER_PASSED;
        if (successes == total) return Outcome.ALWAYS_PASSED;
        return Outcome.MIXED;
    }
}
