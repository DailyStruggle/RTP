package io.github.dailystruggle.bit;

import java.lang.System.Logger.Level;
import java.util.Objects;

/**
 * A stable, location-aware diagnostic produced by a built-in test.
 *
 * <p>{@code codeId} is the registered {@link BitCode#id()}; {@code 0} marks a finding built from a
 * bare string key. Plugins should build findings with {@link #of(BitCode, String, String, String,
 * String, String)} so the id and key always come from one registered code.
 */
public record BitFinding(
        Level severity,
        int codeId,
        String code,
        String source,
        String path,
        String message,
        String expected,
        String suggestion) {

    public BitFinding {
        BitLevels.requireConcrete(severity, "severity");
        if (codeId < 0) throw new IllegalArgumentException("codeId must not be negative");
        code = required(code, "code");
        source = emptyIfNull(source);
        path = emptyIfNull(path);
        message = emptyIfNull(message);
        expected = emptyIfNull(expected);
        suggestion = emptyIfNull(suggestion);
    }

    /** Namespace prefix derived from this finding's registered code, or empty for a legacy code. */
    public String namespace() {
        if (codeId == 0) return "";
        int separator = code.indexOf('_');
        return separator < 1 ? "" : code.substring(0, separator);
    }

    /** Finding with an unregistered string code ({@code codeId == 0}). */
    public BitFinding(Level severity, String code, String source, String path,
                      String message, String expected, String suggestion) {
        this(severity, 0, code, source, path, message, expected, suggestion);
    }

    /** Finding for a registered code at its default severity. */
    public static BitFinding of(BitCode code, String source, String path,
                                String message, String expected, String suggestion) {
        Objects.requireNonNull(code, "code must not be null");
        return of(code, code.defaultSeverity(), source, path, message, expected, suggestion);
    }

    /** Finding for a registered code with an overridden severity. */
    public static BitFinding of(BitCode code, Level severity, String source, String path,
                                String message, String expected, String suggestion) {
        Objects.requireNonNull(code, "code must not be null");
        return new BitFinding(severity, code.id(), code.key(), source, path, message, expected, suggestion);
    }

    private static String required(String value, String field) {
        String result = emptyIfNull(value).trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return result;
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
