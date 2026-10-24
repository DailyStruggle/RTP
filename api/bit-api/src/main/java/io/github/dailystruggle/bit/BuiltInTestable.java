package io.github.dailystruggle.bit;

/** A component that can report the result and health of its power-on built-in test. */
public interface BuiltInTestable {
    /**
     * Executes a fast diagnostic check. Implementations shall capture failures in the result
     * instead of throwing them to the caller.
     */
    PbitResult executePbit();

    /** Returns the current health derived from built-in test results. */
    BitHealth getHealthStatus();
}
