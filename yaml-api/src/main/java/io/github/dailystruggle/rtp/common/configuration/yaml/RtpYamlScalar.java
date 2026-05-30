package io.github.dailystruggle.rtp.common.configuration.yaml;

/**
 * Scalar node: a single value parsed from a YAML scalar token.
 *
 * <p>Supported quoting styles per ADR-025 §Migration:</p>
 * <ul>
 *   <li>{@link Style#PLAIN} — unquoted (e.g. {@code true}, {@code 42}, {@code sqlite}).</li>
 *   <li>{@link Style#SINGLE} — single-quoted (e.g. {@code 'foo'}). Escape is {@code ''}.</li>
 *   <li>{@link Style#DOUBLE} — double-quoted (e.g. {@code "foo"}). Backslash escapes honoured.</li>
 * </ul>
 *
 * <p>The {@link #rawValue} field preserves the lexical form the scalar was
 * read with (without surrounding quotes for SINGLE/DOUBLE). The
 * {@link #style} field lets the writer re-emit the scalar using its
 * original quoting style — important for idempotent round-trip.</p>
 */
public final class RtpYamlScalar extends RtpYamlNode {

    public enum Style { PLAIN, SINGLE, DOUBLE }

    private final String rawValue;
    private final Style style;

    public RtpYamlScalar(String rawValue, Style style) {
        this.rawValue = rawValue == null ? "" : rawValue;
        this.style = style == null ? Style.PLAIN : style;
    }

    public String rawValue() {
        return rawValue;
    }

    public Style style() {
        return style;
    }

    /**
     * Returns the scalar's value coerced to a Java primitive type when
     * the plain form looks like one (boolean, integer, double, null),
     * otherwise the raw string. Mirrors the behaviour callers expect
     * from {@code YamlFile.get(key)}.
     */
    public Object value() {
        if (style != Style.PLAIN) return rawValue;
        if (rawValue.isEmpty()) return "";
        if (rawValue.equals("null") || rawValue.equals("~")) return null;
        if (rawValue.equals("true")) return Boolean.TRUE;
        if (rawValue.equals("false")) return Boolean.FALSE;
        // Integer first (longest match), then double.
        try {
            long l = Long.parseLong(rawValue);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
            return l;
        } catch (NumberFormatException ignored) { /* fallthrough */ }
        // Double-detection requires either a '.' or scientific notation,
        // not just any parseable token (so a bare "1e" doesn't slip).
        if (rawValue.indexOf('.') >= 0 || rawValue.indexOf('e') >= 0 || rawValue.indexOf('E') >= 0) {
            try {
                return Double.parseDouble(rawValue);
            } catch (NumberFormatException ignored) { /* fallthrough */ }
        }
        return rawValue;
    }
}
