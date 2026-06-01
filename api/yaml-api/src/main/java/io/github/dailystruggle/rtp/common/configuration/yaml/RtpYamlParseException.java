package io.github.dailystruggle.rtp.common.configuration.yaml;

/**
 * Thrown when {@link RtpYamlReader} encounters a YAML construct outside
 * the RTP-supported subset (anchors, aliases, merge keys, flow style,
 * tags, document separators, block scalars) or a syntactic error.
 *
 * <p>Carries 1-based line/column information so callers can surface
 * actionable diagnostics. The {@link #messageKey()} accessor returns
 * the stable identifier intended to map to a {@code messages.yml} key
 * (see ADR-025 §Migration Step 5).</p>
 */
public final class RtpYamlParseException extends RuntimeException {

    private final int line;
    private final int column;
    private final String messageKey;

    public RtpYamlParseException(String messageKey, String detail, int line, int column) {
        super("line " + line + ", column " + column + ": " + detail);
        this.messageKey = messageKey;
        this.line = line;
        this.column = column;
    }

    public int line() { return line; }
    public int column() { return column; }
    public String messageKey() { return messageKey; }
}
