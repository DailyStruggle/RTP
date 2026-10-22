package io.github.dailystruggle.bit;

import java.lang.System.Logger.Level;
import java.util.List;

/** Codes emitted by {@code bit-api} itself, in the reserved {@code BIT} namespace (ids 1..999). */
public final class BitCodes {
    public static final String PREFIX = "BIT";
    public static final int FIRST_ID = 1;
    public static final int LAST_ID = 999;

    public static final BitCode PBIT_FAILED = new BitCode(1, "BIT_PBIT_FAILED", Level.ERROR,
            BitTier.PBIT, "bit", "", "Inspect the component diagnostic message and fix the failing check.");
    public static final BitCode PBIT_THROWN = new BitCode(2, "BIT_PBIT_THROWN", Level.ERROR,
            BitTier.PBIT, "bit", "", "The component threw instead of reporting; capture failures in its PbitResult.");
    public static final BitCode HEALTH_FAILED = new BitCode(3, "BIT_HEALTH_FAILED", Level.ERROR,
            BitTier.PBIT, "bit", "", "The component passed PBIT but reports FAILED health; check its runtime state.");
    public static final BitCode HEALTH_DEGRADED = new BitCode(4, "BIT_HEALTH_DEGRADED", Level.WARNING,
            BitTier.PBIT, "bit", "", "The component reports DEGRADED health; check its warnings.");
    public static final BitCode COMPONENT_TIMEOUT = new BitCode(5, "BIT_COMPONENT_TIMEOUT", Level.ERROR,
            BitTier.IBIT, "bit", "", "Increase the component timeout or make the check bounded.");
    public static final BitCode COMPONENT_THROWN = new BitCode(6, "BIT_COMPONENT_THROWN", Level.ERROR,
            BitTier.IBIT, "bit", "", "Fix the component failure and return a BitReport instead of throwing.");
    public static final BitCode COMPONENT_UNKNOWN = new BitCode(7, "BIT_COMPONENT_UNKNOWN", Level.ERROR,
            BitTier.IBIT, "bit", "", "Select a registered component or a matching tier/subsystem.");

    private BitCodes() {}

    public static List<BitCode> all() {
        return List.of(PBIT_FAILED, PBIT_THROWN, HEALTH_FAILED, HEALTH_DEGRADED,
                COMPONENT_TIMEOUT, COMPONENT_THROWN, COMPONENT_UNKNOWN);
    }
}
