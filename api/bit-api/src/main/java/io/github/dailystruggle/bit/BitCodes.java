package io.github.dailystruggle.bit;

import java.lang.System.Logger.Level;
import java.util.List;

/** Codes emitted by {@code bit-api} itself, in the reserved {@code BIT} namespace. */
public final class BitCodes {
    public static final String PREFIX = "BIT";
    /** Owner recorded for the {@code BIT} namespace. */
    public static final String OWNER = "bit-api";

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
    public static final BitCode OPTIONAL_UNAVAILABLE = new BitCode(8, "BIT_OPTIONAL_UNAVAILABLE", Level.INFO,
            BitTier.PBIT, "bit", "", "Install zstd-jni to enable Zstandard-backed region-file support.");
    public static final BitCode NAMESPACE_CONFLICT = new BitCode(9, "BIT_NAMESPACE_CONFLICT", Level.ERROR,
            BitTier.PBIT, "bit", "",
            "Two plugins reserved the same BIT code prefix; change one plugin's prefix or remove one of them.");
    public static final BitCode COVERAGE_AGENT_UNAVAILABLE = new BitCode(10, "BIT_COVERAGE_AGENT_UNAVAILABLE", Level.WARNING,
            BitTier.PBIT, "bit", "",
            "Attach -javaagent:jacocoagent.jar at JVM startup or set coverage: surface in advanced/bit.yml.");
    public static final BitCode CBIT_UNOBSERVED = new BitCode(11, "BIT_CBIT_UNOBSERVED", Level.INFO,
            BitTier.CBIT, "bit", "",
            "Exercise the subsystem or run /rtp test to populate continuous test observations.");
    public static final BitCode BIT_COVERAGE_AGENT_UNAVAILABLE = COVERAGE_AGENT_UNAVAILABLE;
    public static final BitCode BIT_CBIT_UNOBSERVED = CBIT_UNOBSERVED;

    private BitCodes() {}

    public static List<BitCode> all() {
        return List.of(PBIT_FAILED, PBIT_THROWN, HEALTH_FAILED, HEALTH_DEGRADED,
                COMPONENT_TIMEOUT, COMPONENT_THROWN, COMPONENT_UNKNOWN, OPTIONAL_UNAVAILABLE,
                NAMESPACE_CONFLICT, COVERAGE_AGENT_UNAVAILABLE, CBIT_UNOBSERVED);
    }
}
