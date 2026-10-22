package io.github.dailystruggle.rtp.anvil.bit;

/**
 * Operational health status of a component derived from Built-In Tests.
 */
@Deprecated(forRemoval = true)
public enum BitHealth {
    /**
     * Component passed startup Power-On Built-In Test (PBIT) and all continuous tripwires (CBIT).
     */
    HEALTHY,

    /**
     * Component is functional in fallback/degraded mode (e.g. optional host runtime missing).
     */
    DEGRADED,

    /**
     * Invariant breach or fatal initialization failure; component disabled.
     */
    FAULTED;

    /** Converts this legacy status to the standalone BIT API status. */
    public io.github.dailystruggle.bit.BitHealth asBit() {
        return switch (this) {
            case HEALTHY -> io.github.dailystruggle.bit.BitHealth.HEALTHY;
            case DEGRADED -> io.github.dailystruggle.bit.BitHealth.DEGRADED;
            case FAULTED -> io.github.dailystruggle.bit.BitHealth.FAILED;
        };
    }
}
