package io.github.dailystruggle.rtp.common.commands.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strict minimal JSON reader/writer for {@link EditorLoopbackChannel} messages (ADR-104).
 *
 * <p>Values map to {@link LinkedHashMap}, {@link ArrayList}, {@link String}, {@link Long} (integral
 * literals that fit) or {@link Double}, {@link Boolean} and {@code null}. Fail-closed: duplicate keys,
 * trailing input, bad escapes, raw control characters and nesting beyond {@code MAX_DEPTH} throw
 * {@link IllegalArgumentException}.
 */
public final class EditorLoopbackJson {

    static final int MAX_DEPTH = 32;

    private final String s;
    private int i;

    private EditorLoopbackJson(String s) {
        this.s = s;
    }

    /** Parses one complete JSON document. */
    public static Object parse(String json) {
        if (json == null) throw new IllegalArgumentException("JSON input is null");
        EditorLoopbackJson p = new EditorLoopbackJson(json);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.i != json.length()) throw p.error("trailing characters");
        return v;
    }

    /** JSON string literal with every control character escaped. */
    public static String quote(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2).append('"');
        for (int k = 0; k < raw.length(); k++) {
            char c = raw.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw error("nesting deeper than " + MAX_DEPTH);
        if (i >= s.length()) throw error("unexpected end of input");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) yield number();
                throw error("unexpected character '" + c + "'");
            }
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        skipWs();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            skipWs();
            if (peek() != '"') throw error("expected object key");
            String key = string();
            skipWs();
            expect(':');
            skipWs();
            Object v = value(depth + 1);
            if (m.containsKey(key)) throw error("duplicate key '" + key + "'");
            m.put(key, v);
            skipWs();
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        i++;
        skipWs();
        if (peek() == ']') {
            i++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(value(depth + 1));
            skipWs();
            char c = next();
            if (c == ']') return list;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return sb.toString();
            if (c < 0x20) throw error("raw control character in string");
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw error("truncated \\u escape");
                    int cp = 0;
                    for (int k = 0; k < 4; k++) {
                        int d = Character.digit(s.charAt(i++), 16);
                        if (d < 0) throw error("bad \\u escape");
                        cp = (cp << 4) | d;
                    }
                    sb.append((char) cp);
                }
                default -> throw error("bad escape '\\" + e + "'");
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek() == '-') i++;
        if (peek() == '0') {
            i++;
        } else if (isDigit(peek())) {
            while (isDigit(peek())) i++;
        } else {
            throw error("bad number");
        }
        boolean integral = true;
        if (peek() == '.') {
            integral = false;
            i++;
            if (!isDigit(peek())) throw error("bad fraction");
            while (isDigit(peek())) i++;
        }
        if (peek() == 'e' || peek() == 'E') {
            integral = false;
            i++;
            if (peek() == '+' || peek() == '-') i++;
            if (!isDigit(peek())) throw error("bad exponent");
            while (isDigit(peek())) i++;
        }
        String lit = s.substring(start, i);
        if (integral) {
            try {
                return Long.parseLong(lit);
            } catch (NumberFormatException overflow) {
                // beyond long range: fall through to double
            }
        }
        return Double.parseDouble(lit);
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw error("bad literal");
        i += word.length();
        return v;
    }

    private void skipWs() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            i++;
        }
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private char next() {
        if (i >= s.length()) throw error("unexpected end of input");
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) throw error("expected '" + c + "'");
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("Malformed JSON at offset " + i + ": " + msg);
    }
}
