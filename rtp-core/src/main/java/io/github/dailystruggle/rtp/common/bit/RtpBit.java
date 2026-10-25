package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitCoverageDiscovery;
import io.github.dailystruggle.bit.BitCoverageProvider;
import io.github.dailystruggle.bit.BitCoverageSnapshot;
import io.github.dailystruggle.bit.BitRunner;

/** Process-wide BIT component registry and on-request runner. */
public final class RtpBit {
    public static final BitRunner RUNNER = new BitRunner();

    private RtpBit() {}

    public static void configureCoverage(String configuredId) {
        RUNNER.setCoverageProvider(BitCoverageDiscovery.discover(configuredId));
    }

    public static void setCoverageProvider(BitCoverageProvider provider) {
        RUNNER.setCoverageProvider(provider);
    }

    public static BitCoverageSnapshot coverageSnapshot() {
        return RUNNER.coverageSnapshot();
    }
}
