package io.github.dailystruggle.rtp.common.configuration.yaml;

import java.util.List;
import java.util.Map;

/**
 * Emitter for the in-house YAML AST.
 *
 * <p>Design contract:</p>
 * <ul>
 *   <li>Round-trip idempotence: {@code emit(parse(emit(parse(x))))} equals
 *       {@code emit(parse(x))} byte-for-byte (LF line endings, terminal
 *       newline included).</li>
 *   <li>Block comments above a node are emitted as {@code # <text>}
 *       lines at the node's indent, immediately before the node line.</li>
 *   <li>Scalar quoting style is preserved from the parsed AST
 *       ({@link RtpYamlScalar.Style#PLAIN}, {@code SINGLE}, {@code DOUBLE}).
 *       The writer never silently re-quotes a value.</li>
 *   <li>Mapping insertion order is preserved.</li>
 *   <li>Sequences are emitted block-style only ({@code - item} per line).</li>
 * </ul>
 */
public final class RtpYamlWriter {

    private static final String NL = "\n";

    private final StringBuilder out = new StringBuilder();

    private RtpYamlWriter() {}

    public static String emit(RtpYamlMapping root) {
        RtpYamlWriter w = new RtpYamlWriter();
        w.writeMapping(root, 0, true);
        if (!root.trailingComments().isEmpty()) {
            // Separate trailing comments from the last entry with a blank
            // line — preserves the layout convention used by shipped YAMLs
            // (e.g. "# DO NOT TOUCH VERSION NUMBER" above the version key).
            w.out.append(NL);
            w.writeBlockComments(root.trailingComments(), 0);
        }
        return w.out.toString();
    }

    private void writeMapping(RtpYamlMapping mapping, int indent, boolean isRoot) {
        Map<String, RtpYamlNode> entries = mapping.entries();
        boolean first = true;
        for (Map.Entry<String, RtpYamlNode> e : entries.entrySet()) {
            String key = e.getKey();
            RtpYamlNode value = e.getValue();
            // Emit block comments above this entry.
            writeBlockComments(value.blockComments(), indent);
            // Emit the key line.
            writeIndent(indent);
            out.append(quoteKeyIfNeeded(key)).append(':');
            if (value instanceof RtpYamlScalar) {
                RtpYamlScalar sc = (RtpYamlScalar) value;
                if (isEmptyScalar(sc)) {
                    out.append(NL);
                } else {
                    out.append(' ').append(emitScalar(sc)).append(NL);
                }
            } else if (value instanceof RtpYamlMapping) {
                out.append(NL);
                writeMapping((RtpYamlMapping) value, indent + 2, false);
            } else if (value instanceof RtpYamlSequence) {
                out.append(NL);
                writeSequence((RtpYamlSequence) value, indent + 2);
            } else {
                out.append(NL);
            }
            first = false;
        }
    }

    private void writeSequence(RtpYamlSequence seq, int indent) {
        for (RtpYamlNode item : seq.items()) {
            writeBlockComments(item.blockComments(), indent);
            writeIndent(indent);
            out.append('-');
            if (item instanceof RtpYamlScalar) {
                RtpYamlScalar sc = (RtpYamlScalar) item;
                if (isEmptyScalar(sc)) {
                    out.append(NL);
                } else {
                    out.append(' ').append(emitScalar(sc)).append(NL);
                }
            } else if (item instanceof RtpYamlMapping) {
                // Emit the first mapping entry inline after "- ", subsequent
                // entries indented by 2 more spaces.
                RtpYamlMapping m = (RtpYamlMapping) item;
                Map<String, RtpYamlNode> entries = m.entries();
                if (entries.isEmpty()) {
                    out.append(NL);
                } else {
                    boolean firstEntry = true;
                    for (Map.Entry<String, RtpYamlNode> e : entries.entrySet()) {
                        if (firstEntry) {
                            out.append(' ').append(quoteKeyIfNeeded(e.getKey())).append(':');
                            emitInlineOrBlock(e.getValue(), indent + 2);
                            firstEntry = false;
                        } else {
                            writeBlockComments(e.getValue().blockComments(), indent + 2);
                            writeIndent(indent + 2);
                            out.append(quoteKeyIfNeeded(e.getKey())).append(':');
                            emitInlineOrBlock(e.getValue(), indent + 2);
                        }
                    }
                }
            } else if (item instanceof RtpYamlSequence) {
                out.append(NL);
                writeSequence((RtpYamlSequence) item, indent + 2);
            } else {
                out.append(NL);
            }
        }
    }

    private void emitInlineOrBlock(RtpYamlNode value, int indent) {
        if (value instanceof RtpYamlScalar) {
            RtpYamlScalar sc = (RtpYamlScalar) value;
            if (isEmptyScalar(sc)) {
                out.append(NL);
            } else {
                out.append(' ').append(emitScalar(sc)).append(NL);
            }
        } else if (value instanceof RtpYamlMapping) {
            out.append(NL);
            writeMapping((RtpYamlMapping) value, indent + 2, false);
        } else if (value instanceof RtpYamlSequence) {
            out.append(NL);
            writeSequence((RtpYamlSequence) value, indent + 2);
        } else {
            out.append(NL);
        }
    }

    private void writeBlockComments(List<String> comments, int indent) {
        for (String line : comments) {
            if (RtpYamlReader.BLANK_LINE_SENTINEL.equals(line)) {
                out.append(NL);
                continue;
            }
            writeIndent(indent);
            out.append('#');
            if (!line.isEmpty()) out.append(' ').append(line);
            out.append(NL);
        }
    }

    private void writeIndent(int n) {
        for (int i = 0; i < n; i++) out.append(' ');
    }

    private static boolean isEmptyScalar(RtpYamlScalar sc) {
        return sc.style() == RtpYamlScalar.Style.PLAIN && sc.rawValue().isEmpty();
    }

    private static String emitScalar(RtpYamlScalar sc) {
        switch (sc.style()) {
            case DOUBLE: return "\"" + escapeDouble(sc.rawValue()) + "\"";
            case SINGLE: return "'" + sc.rawValue().replace("'", "''") + "'";
            case PLAIN:
            default:    return sc.rawValue();
        }
    }

    private static String escapeDouble(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String quoteKeyIfNeeded(String key) {
        // Our YAML subset does not require quoting for any plain key that
        // doesn't contain ':', '#', leading/trailing spaces, or quote chars.
        // Be conservative — only emit a double-quoted form when necessary.
        if (key.isEmpty()) return "\"\"";
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == ':' || c == '#' || c == '"' || c == '\'' || c == '\n' || c == '\r') {
                return "\"" + escapeDouble(key) + "\"";
            }
        }
        if (key.charAt(0) == ' ' || key.charAt(key.length() - 1) == ' ') {
            return "\"" + escapeDouble(key) + "\"";
        }
        return key;
    }
}
