package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PBIT step of the ADR-037 config transaction (ADR-121 section 7): a reload or Hot-Apply
 * candidate is parsed into a read-only {@link ConfigSnapshot}, checked by every registered
 * {@link ConfigOwnerCheck}, and rejected on any effective ERROR before anything is written, swapped
 * or cancelled, so the running config is kept.
 *
 * <p>Action definitions are not part of the gate: ADR-121 section 7 rejects them one by one at
 * registration. Runs on the caller's thread; callers are the async reload/apply paths.
 */
public final class ConfigCandidateGate {
    private ConfigCandidateGate() {}

    /** Outcome of one gate evaluation; {@code errors} lists the effective ERROR findings in order. */
    public record Result(BitReport report, List<BitFinding> errors) {
        public Result {
            Objects.requireNonNull(report, "report");
            errors = List.copyOf(errors);
        }

        public boolean rejected() {
            return !errors.isEmpty();
        }

        public BitFinding firstError() {
            return errors.isEmpty() ? null : errors.get(0);
        }
    }

    /** Thrown by apply paths whose candidate was rejected; the message is the rendered operator text. */
    public static final class RejectedException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final transient Result result;

        public RejectedException(Result result, String message) {
            super(message);
            this.result = Objects.requireNonNull(result, "result");
        }

        public Result result() {
            return result;
        }
    }

    /**
     * Fills {@code [count]}, {@code [code]}, {@code [source]} and {@code [path]} in a configurable
     * rejection message (S-007, REQ-RTP-F-013) from the first error of {@code result}.
     */
    public static String format(String template, Result result) {
        if (template == null) return "";
        BitFinding first = result == null ? null : result.firstError();
        return template
                .replace("[count]", String.valueOf(result == null ? 0 : result.errors().size()))
                .replace("[code]", first == null ? "" : first.code())
                .replace("[source]", first == null ? "" : first.source())
                .replace("[path]", first == null || first.path().isEmpty() ? "-" : first.path());
    }

    /** Evaluates the files under {@code root} with {@code overlay} texts applied on top. */
    public static Result evaluate(Path root, Map<String, String> overlay) {
        return evaluate(ConfigSnapshot.read(root).withOverlay(overlay), ConfigEnvironment.live());
    }

    /** Evaluates an already-built candidate and logs every finding with its path (REQ-RTP-S-004). */
    public static Result evaluate(ConfigSnapshot candidate, ConfigEnvironment environment) {
        BitReport report = ConfigPbit.run(candidate, environment);
        List<BitFinding> errors = report.findings().stream()
                .filter(finding -> finding.severity() == Level.ERROR)
                .toList();
        for (BitFinding finding : report.findings()) {
            RTPRuntime.log(BitSeverityPolicy.toJul(finding.severity()), "[RTP bit] config candidate "
                    + finding.code() + " " + finding.source()
                    + (finding.path().isEmpty() ? "" : " at " + finding.path()) + ": " + finding.message()
                    + (finding.suggestion().isEmpty() ? "" : " (did you mean " + finding.suggestion() + "?)"));
        }
        if (!errors.isEmpty()) {
            RTPRuntime.log(java.util.logging.Level.WARNING, "[RTP bit] config candidate rejected: "
                    + errors.size() + " error(s); the running config is kept");
        }
        return new Result(report, errors);
    }
}
