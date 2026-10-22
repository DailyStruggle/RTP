package io.github.dailystruggle.bit;

import java.lang.System.Logger.Level;
import java.util.Objects;

/**
 * Severity rules for findings, which use the JDK's {@link System.Logger.Level} rather than a BIT enum.
 *
 * <p>Invariant: a finding or code severity is a concrete level ({@code TRACE..ERROR}); {@code ALL} and
 * {@code OFF} are filters, not severities, and are rejected. {@code OFF} stays available to plugins as a
 * per-code suppression override.
 */
public final class BitLevels {
    private BitLevels() {}

    /** Returns {@code level} or throws if it is null, {@code ALL} or {@code OFF}. */
    public static Level requireConcrete(Level level, String field) {
        Objects.requireNonNull(level, field + " must not be null");
        if (level == Level.ALL || level == Level.OFF) {
            throw new IllegalArgumentException(field + " must be TRACE, DEBUG, INFO, WARNING or ERROR: " + level);
        }
        return level;
    }

    /** {@code ERROR} -> {@code FAILED}, {@code WARNING} -> {@code DEGRADED}, lower levels -> {@code HEALTHY}. */
    public static BitHealth health(Level level) {
        if (level.getSeverity() >= Level.ERROR.getSeverity()) return BitHealth.FAILED;
        if (level.getSeverity() >= Level.WARNING.getSeverity()) return BitHealth.DEGRADED;
        return BitHealth.HEALTHY;
    }

    /** Bridges to {@code java.util.logging} for hosts whose logger takes JUL levels. */
    public static java.util.logging.Level toJul(Level level) {
        return switch (requireConcrete(level, "level")) {
            case ERROR -> java.util.logging.Level.SEVERE;
            case WARNING -> java.util.logging.Level.WARNING;
            case INFO -> java.util.logging.Level.INFO;
            case DEBUG -> java.util.logging.Level.FINE;
            default -> java.util.logging.Level.FINER;
        };
    }
}
