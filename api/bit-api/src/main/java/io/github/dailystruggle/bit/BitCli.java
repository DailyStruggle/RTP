package io.github.dailystruggle.bit;

import java.util.Objects;
import java.util.function.Consumer;

/** Small headless runner skeleton; the caller supplies checks and output destination. */
public final class BitCli {
    private BitCli() {}

    public static int run(BitRegistry registry, boolean json, Consumer<String> output) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(output, "output must not be null");
        BitReport report = registry.runPbit();
        if (json) {
            output.accept(report.toJson());
        } else {
            output.accept("BIT health: " + report.health());
            for (BitFinding finding : report.findings()) {
                String location = finding.source().isBlank() ? finding.path() : finding.source() + ":" + finding.path();
                output.accept(finding.severity() + " " + finding.code() + " " + location + " - " + finding.message());
            }
        }
        return report.health() == BitHealth.FAILED ? 1 : 0;
    }
}
