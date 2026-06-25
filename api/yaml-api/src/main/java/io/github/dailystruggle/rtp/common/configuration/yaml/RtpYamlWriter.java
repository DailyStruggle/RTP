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
        // Emit a document/file header (the root mapping's own block comments)
        // ahead of the first entry, separated by a blank line so a reload
        // re-attaches it to the root rather than gluing it to the first key.
        if (startsWithRenderableComment(root.blockComments())) {
            w.writeBlockComments(root.blockComments(), 0);
            w.out.append(NL);
        }
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
            // Visually separate a commented scalar entry from the preceding
            // entry with a blank line. Source files that were round-tripped
            // through the parser carry an explicit BLANK_LINE_SENTINEL for
            // this, but nodes rebuilt programmatically (e.g. a menu Apply or an
            // "@config" materialization) attach their comment block with no
            // leading sentinel, so emitting them produces a cramped layout with
            // the comment jammed against the previous value. Insert the
            // separator here when one is not already present so the written
            // YAML keeps the one-blank-line-before-each-comment-block style of
            // the shipped resources.
            //
            // Section-valued entries (nested mappings/sequences such as
            // "network:") are deliberately excluded: their own indented block
            // already provides clear visual separation, and forcing an extra
            // blank line above every sub-tree header reads as noise.
            if (!first
                    && startsWithRenderableComment(value.blockComments())
                    && !(value instanceof RtpYamlMapping)
                    && !(value instanceof RtpYamlSequence)
                    && !endsWithBlankLine()) {
                out.append(NL);
            }
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

    /**
     * True when the comment list begins with an actual comment line (i.e. its
     * first element is not a {@link RtpYamlReader#BLANK_LINE_SENTINEL}). A list
     * that already starts with a sentinel will emit its own separating blank
     * line, so no extra separator is needed in that case.
     */
    private static boolean startsWithRenderableComment(List<String> comments) {
        return !comments.isEmpty()
                && !RtpYamlReader.BLANK_LINE_SENTINEL.equals(comments.get(0));
    }

    /** True when the emitted output already ends with a blank line (or is empty). */
    private boolean endsWithBlankLine() {
        int len = out.length();
        if (len == 0) return true;
        if (out.charAt(len - 1) != '\n') return false;
        return len < 2 || out.charAt(len - 2) == '\n';
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
