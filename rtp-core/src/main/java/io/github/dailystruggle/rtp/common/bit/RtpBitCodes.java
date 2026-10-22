package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitCodeNamespace;
import io.github.dailystruggle.bit.BitCodeRegistry;

/**
 * Process-wide BIT code catalog for RTP and its addons (ADR-121).
 *
 * <p>Namespaces are reserved here; codes are registered by the class whose check emits them, so
 * adding a check never edits a central list. Addons reserve their own prefix and id range from
 * {@link #REGISTRY} (10000 and up, see the ADR-121 namespace table).
 */
public final class RtpBitCodes {
    public static final BitCodeRegistry REGISTRY = new BitCodeRegistry();

    /** Action-engine and scoreboard checks. */
    public static final BitCodeNamespace ACTION = REGISTRY.reserve("ACTION", 2000, 2999);

    /** On-request test probes. */
    public static final BitCodeNamespace TEST = REGISTRY.reserve("TEST", 3000, 4099);

    /** Runtime diagnostic probes. */
    public static final BitCodeNamespace PROBE = REGISTRY.reserve("PROBE", 4100, 4699);

    private RtpBitCodes() {}
}
