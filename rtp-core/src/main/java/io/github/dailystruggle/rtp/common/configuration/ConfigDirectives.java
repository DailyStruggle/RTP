package io.github.dailystruggle.rtp.common.configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Runtime parser for {@code @…} directive lines in YAML block comments (ADR-064).
 * Surfaces finite domains ({@code @options}, {@code @source}) for menu value pickers.
 */
public final class ConfigDirectives {

    private static final ConfigDirectives EMPTY =
            new ConfigDirectives(null, Collections.emptyList(), null);

    private final String type;
    private final List<String> options;
    private final String source;

    private ConfigDirectives(String type, List<String> options, String source) {
        this.type = type;
        this.options = options;
        this.source = source;
    }

    /**
     * Parse directives from a raw block-comment string.
     *
     * @param comment the raw block comment, or {@code null}
     * @return the parsed directives (never {@code null})
     */
    public static ConfigDirectives parse(String comment) {
        if (comment == null || comment.isEmpty()) return EMPTY;
        String type = null;
        List<String> options = Collections.emptyList();
        String source = null;
        for (String line : comment.split("\\R", -1)) {
            String s = line.stripLeading();
            if (s.startsWith("#")) {
                s = s.substring(1).stripLeading();
            }
            if (!s.startsWith("@")) continue;
            int colon = s.indexOf(':');
            if (colon < 0) continue;
            String key = s.substring(1, colon).trim().toLowerCase(Locale.ROOT);
            String value = s.substring(colon + 1).trim();
            switch (key) {
                case "type" -> type = stripQuotes(value);
                case "source" -> source = stripQuotes(value);
                case "options" -> options = parseFlowList(value);
                default -> { /* @range / @unit / @default etc. - not consumed here */ }
            }
        }
        if (type == null && options.isEmpty() && source == null) return EMPTY;
        return new ConfigDirectives(type, options, source);
    }

    /**
     * Parse a YAML-flow list literal such as {@code ["yaml", "sqlite"]} or
     * {@code [a, b, c]} into its string elements. Tolerates missing brackets
     * and single/double quotes. Returns an empty list when nothing parses.
     */
    private static List<String> parseFlowList(String raw) {
        if (raw == null) return Collections.emptyList();
        String v = raw.trim();
        if (v.isEmpty()) return Collections.emptyList();
        if (v.startsWith("[")) v = v.substring(1);
        if (v.endsWith("]")) v = v.substring(0, v.length() - 1);
        List<String> out = new ArrayList<>();
        for (String part : v.split(",")) {
            String item = stripQuotes(part.trim());
            if (!item.isEmpty()) out.add(item);
        }
        return out.isEmpty() ? Collections.emptyList() : out;
    }

    private static String stripQuotes(String s) {
        if (s == null) return "";
        String v = s.trim();
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                v = v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    /** The {@code @type} value (e.g. {@code enum}, {@code string}), or {@code null}. */
    public String type() {
        return type;
    }

    /** The {@code @options} literal list (never {@code null}; empty when absent). */
    public List<String> options() {
        return options;
    }

    /** The {@code @source} registry name (e.g. {@code shape}, {@code vert}), or {@code null}. */
    public String source() {
        return source;
    }

    /**
     * Whether this key declares a finite value domain - either an
     * {@code @options} literal list or an {@code @source} registry.
     */
    public boolean hasFiniteDomain() {
        return !options.isEmpty() || (source != null && !source.isEmpty());
    }
}
