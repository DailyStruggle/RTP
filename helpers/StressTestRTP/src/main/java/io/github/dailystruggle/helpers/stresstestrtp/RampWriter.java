package io.github.dailystruggle.helpers.stresstestrtp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Writes {@code <stamp>-ramp.csv} (one row per ramp stage) and
 * {@code <stamp>-ramp-summary.txt} beside the per-attempt CSV. Each row is
 * appended with an open-write-close so it is on disk before the next stage
 * starts: a server crash mid-ramp keeps every completed stage.
 */
public final class RampWriter {

    public static final String HEADER =
            "target,stage_index,offered_tps,stage_seconds,attempts,successes,timeouts,errors,"
                    + "busy_rejections,harness_shed,achieved_tps,achieved_fraction,fail_fraction,"
                    + "latency_p50_ms,latency_p95_ms,latency_p99_ms,mspt_p50,mspt_p95,mspt_samples,"
                    + "tps_min,heap_peak_mb,roster_size,peak_in_flight,aborted,complete,verdict,fail_criteria,"
                    + "start_epoch_ms,end_epoch_ms";

    /** Per-stage values not carried by {@link RampEvaluator.StageResult}. -1 = NOT MEASURED.
     *  {@code complete} false = cut short by an operator stop; such a row is
     *  kept for the record but never feeds the stress point. */
    public record Extras(long latencyP50Ms, long latencyP95Ms, long latencyP99Ms,
                         double msptP50, int msptSamples, double tpsMin, long heapPeakMb,
                         int rosterSize, int peakInFlight, boolean complete,
                         long startEpochMs, long endEpochMs) {}

    private final Path csvPath;
    private final Path summaryPath;

    /** Derives both paths from the run's per-attempt CSV ({@code <stamp>.csv}). */
    public RampWriter(Path runCsv) throws IOException {
        String name = runCsv.getFileName().toString();
        String base = name.endsWith(".csv") ? name.substring(0, name.length() - 4) : name;
        this.csvPath = runCsv.resolveSibling(base + "-ramp.csv");
        this.summaryPath = runCsv.resolveSibling(base + "-ramp-summary.txt");
        Files.createDirectories(csvPath.toAbsolutePath().getParent());
        Files.writeString(csvPath, HEADER + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public Path csvPath() { return csvPath; }
    public Path summaryPath() { return summaryPath; }

    /** Formats one stage row (no trailing newline). */
    public static String row(String target, RampEvaluator.Evaluation e, Extras x) {
        RampEvaluator.StageResult s = e.stage();
        return String.join(",",
                csv(target),
                Integer.toString(s.index()),
                num(s.offeredTps()),
                num(s.stageSeconds()),
                Integer.toString(s.attempts()),
                Integer.toString(s.successes()),
                Integer.toString(s.timeouts()),
                Integer.toString(s.errors()),
                Integer.toString(s.busyRejections()),
                Integer.toString(s.harnessShed()),
                num(s.achievedTps()),
                frac(s.achievedFraction()),
                frac(s.failFraction()),
                Long.toString(x.latencyP50Ms()),
                Long.toString(x.latencyP95Ms()),
                Long.toString(x.latencyP99Ms()),
                num(x.msptP50()),
                num(s.msptP95()),
                Integer.toString(x.msptSamples()),
                num(x.tpsMin()),
                Long.toString(x.heapPeakMb()),
                Integer.toString(x.rosterSize()),
                Integer.toString(x.peakInFlight()),
                Boolean.toString(s.aborted()),
                Boolean.toString(x.complete()),
                !x.complete() ? "PARTIAL" : (e.passed() ? "PASS" : "FAIL"),
                e.criteriaString(),
                Long.toString(x.startEpochMs()),
                Long.toString(x.endEpochMs()));
    }

    /** Appends and closes; the row is durable when this returns. */
    public void appendStage(String target, RampEvaluator.Evaluation e, Extras x) throws IOException {
        Files.writeString(csvPath, row(target, e, x) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Overwrites the summary sidecar. */
    public void writeSummary(String text) throws IOException {
        Files.writeString(summaryPath, text.endsWith("\n") ? text : text + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** -1 sentinel for not-measured; never blank, so DictReader sees a number. */
    private static String num(double d) {
        if (Double.isNaN(d) || d < 0) return "-1";
        return String.format(Locale.ROOT, "%.3f", d);
    }

    private static String frac(double d) {
        if (Double.isNaN(d) || d < 0) return "-1";
        return String.format(Locale.ROOT, "%.4f", d);
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
