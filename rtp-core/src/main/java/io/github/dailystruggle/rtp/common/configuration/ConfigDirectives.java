package io.github.dailystruggle.rtp.common.configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Runtime parser for {@code @…} directive lines in YAML block comments (ADR-064).
 * Surfaces finite domains ({@code @options}, {@code @source}) for menu value pickers and
 * {@code @range} / {@code @unit} / {@code @default} for config PBIT (ADR-121 section 6).
 */
public final class ConfigDirectives {

    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    private static final ConfigDirectives EMPTY =
            new ConfigDirectives(null, Collections.emptyList(), null, null, null, null, false, null);

    private final String type;
    private final List<String> options;
    private final String source;
    private final String unit;
    private final Double rangeMin;
    private final Double rangeMax;
    private final boolean rangeMalformed;
    private final String defaultValue;

    private ConfigDirectives(String type, List<String> options, String source, String unit,
                             Double rangeMin, Double rangeMax, boolean rangeMalformed, String defaultValue) {
        this.type = type;
        this.options = options;
        this.source = source;
        this.unit = unit;
        this.rangeMin = rangeMin;
        this.rangeMax = rangeMax;
        this.rangeMalformed = rangeMalformed;
        this.defaultValue = defaultValue;
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
        String unit = null;
        Double rangeMin = null;
        Double rangeMax = null;
        boolean rangeSeen = false;
        boolean rangeMalformed = false;
        String defaultValue = null;
        for (String line : LINE_BREAK.split(comment, -1)) {
            String s = line.stripLeading();
            if (s.startsWith("#")) {
                s = s.substring(1).stripLeading();
            }
            if (!s.startsWith("@")) continue;
            int colon = s.indexOf(':');
            if (colon < 0) continue;
            String key = s.substring(1, colon).trim().toLowerCase(Locale.ROOT);
            String value = stripTrailingComment(s.substring(colon + 1)).trim();
            switch (key) {
                case "type" -> type = stripQuotes(value);
                case "source" -> source = stripQuotes(value);
                case "options" -> options = parseFlowList(value);
                case "unit" -> unit = stripQuotes(value);
                case "default" -> defaultValue = stripQuotes(value);
                case "range" -> {
                    rangeSeen = true;
                    List<String> bounds = splitFlow(value);
                    if (bounds.size() != 2) {
                        rangeMalformed = true;
                    } else {
                        try {
                            rangeMin = parseBound(bounds.get(0));
                            rangeMax = parseBound(bounds.get(1));
                            rangeMalformed = rangeMin != null && rangeMax != null && rangeMin > rangeMax;
                        } catch (NumberFormatException e) {
                            rangeMin = null;
                            rangeMax = null;
                            rangeMalformed = true;
                        }
                    }
                }
                default -> { /* unrecognised directive keys are ignored */ }
            }
        }
        if (type == null && options.isEmpty() && source == null && unit == null
                && !rangeSeen && defaultValue == null) {
            return EMPTY;
        }
        return new ConfigDirectives(type, options, source, unit, rangeMin, rangeMax, rangeMalformed, defaultValue);
    }

    /** {@code null}/{@code ~}/empty = open end; otherwise a finite number, else NumberFormatException. */
    private static Double parseBound(String raw) {
        String v = raw.trim();
        if (v.isEmpty() || v.equals("~") || v.equalsIgnoreCase("null")) return null;
        double d = Double.parseDouble(v);
        if (Double.isNaN(d)) throw new NumberFormatException("NaN bound");
        return d;
    }

    /**
     * Parse a YAML-flow list literal such as {@code ["yaml", "sqlite"]} or
     * {@code [a, b, c]} into its string elements. Tolerates missing brackets
     * and single/double quotes; commas inside quotes stay in the item. Returns an
     * empty list when nothing parses.
     */
    private static List<String> parseFlowList(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : splitFlow(raw)) {
            String item = stripQuotes(part.trim());
            if (!item.isEmpty()) out.add(item);
        }
        return out.isEmpty() ? Collections.emptyList() : out;
    }

    /** Quote-aware split of a flow sequence body; items keep their quotes. */
    private static List<String> splitFlow(String raw) {
        if (raw == null) return Collections.emptyList();
        String v = raw.trim();
        if (v.isEmpty()) return Collections.emptyList();
        if (v.startsWith("[")) v = v.substring(1);
        if (v.endsWith("]")) v = v.substring(0, v.length() - 1);
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                current.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                current.append(c);
            } else if (c == ',') {
                out.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString().trim());
        return out;
    }

    /** Drops a YAML end-of-line comment ({@code  # ...} outside quotes). */
    private static String stripTrailingComment(String raw) {
        char quote = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#' && i > 0 && Character.isWhitespace(raw.charAt(i - 1))) {
                return raw.substring(0, i);
            }
        }
        return raw;
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

    /** The {@code @unit} token (e.g. {@code seconds}), or {@code null}. */
    public String unit() {
        return unit;
    }

    /** Inclusive {@code @range} lower bound; {@code null} when open or absent. */
    public Double rangeMin() {
        return rangeMin;
    }

    /** Inclusive {@code @range} upper bound; {@code null} when open or absent. */
    public Double rangeMax() {
        return rangeMax;
    }

    /** True when a {@code @range} declares at least one finite bound. */
    public boolean hasRange() {
        return rangeMin != null || rangeMax != null;
    }

    /** True when {@code @range} is present but not {@code [min, max]} with numeric/null bounds, min <= max. */
    public boolean rangeMalformed() {
        return rangeMalformed;
    }

    /** The {@code @default} scalar with quotes removed; {@code null} when absent ({@code ""} stays empty). */
    public String defaultValue() {
        return defaultValue;
    }

    /**
     * Whether this key declares a finite value domain - either an
     * {@code @options} literal list or an {@code @source} registry.
     */
    public boolean hasFiniteDomain() {
        return !options.isEmpty() || (source != null && !source.isEmpty());
    }
}
