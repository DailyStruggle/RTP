package io.github.dailystruggle.rtp.anvil.bit;

/**
 * Operational health status of a component derived from Built-In Tests.
 */
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
    FAULTED
}
