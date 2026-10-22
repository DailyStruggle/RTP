package io.github.dailystruggle.bit;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Small headless runner skeleton; the caller supplies checks and output destination. */
public final class BitCli {
    private BitCli() {}

    public static int run(BitRegistry registry, boolean json, Consumer<String> output) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(output, "output must not be null");
        BitReport report = registry.runPbit();
        render(report, json, output);
        return report.health() == BitHealth.FAILED ? 1 : 0;
    }

    /** Runs the selected components through the shared runner and renders their aggregate report. */
    public static int run(BitRunner runner, BitSelector selector, Executor executor,
                          boolean json, Consumer<String> output) {
        Objects.requireNonNull(runner, "runner must not be null");
        Objects.requireNonNull(selector, "selector must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(output, "output must not be null");
        BitReport report = runner.aggregate(runner.run(selector, executor));
        render(report, json, output);
        boolean hasErrors = report.findings().stream()
                .anyMatch(finding -> finding.severity() == java.lang.System.Logger.Level.ERROR);
        return report.health() == BitHealth.FAILED || hasErrors ? 1 : 0;
    }

    private static void render(BitReport report, boolean json, Consumer<String> output) {
        if (json) {
            output.accept(report.toJson());
        } else {
            output.accept("BIT health: " + report.health());
            for (BitFinding finding : report.findings()) {
                String location = finding.source().isBlank() ? finding.path() : finding.source() + ":" + finding.path();
                output.accept(finding.severity() + " " + finding.code() + " " + location + " - " + finding.message());
            }
        }
    }
}
