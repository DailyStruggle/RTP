package io.github.dailystruggle.bstats.api;

import java.util.Map;

/**
 * Minimal JSON writer for the bStats v2 payload. The payload is a closed shape
 * (strings, ints, booleans, nested objects, one array), so a dependency-free
 * writer is enough.
 */
final class BStatsJson {

    private BStatsJson() {}

    static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /** Scalar value: numbers and booleans raw, everything else as a quoted string. */
    static String value(Object v) {
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return quote(v == null ? "" : String.valueOf(v));
    }

    /** Appends {@code "key":value} pairs of {@code fields} (comma-separated, no braces). */
    static void appendFields(StringBuilder sb, Map<String, ?> fields) {
        boolean first = true;
        for (Map.Entry<String, ?> e : fields.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(e.getKey())).append(':').append(value(e.getValue()));
        }
    }
}
