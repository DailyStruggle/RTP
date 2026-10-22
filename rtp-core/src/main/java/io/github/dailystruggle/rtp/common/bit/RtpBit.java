package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitRunner;

/** Process-wide BIT component registry and on-request runner. */
public final class RtpBit {
    public static final BitRunner RUNNER = new BitRunner();

    private RtpBit() {}
}
