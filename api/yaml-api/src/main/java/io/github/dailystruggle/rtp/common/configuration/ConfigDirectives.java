package io.github.dailystruggle.rtp.common.configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Runtime parser for {@code @…} directive lines in YAML block comments (ADR-064).
 * Surfaces finite domains ({@code @options}, {@code @source}) for menu value pickers and
 * {@code @range} / {@code @pattern} / {@code @unit} / {@code @default} for config PBIT (ADR-121 section 6).
 */
public final class ConfigDirectives {

    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    private static final ConfigDirectives EMPTY =
            new ConfigDirectives(null, Collections.emptyList(), null, null, null, null, false, null, null, false, null);

    private final String type;
    private final List<String> options;
    private final String source;
    private final String unit;
    private final Double rangeMin;
    private final Double rangeMax;
    private final boolean rangeMalformed;
    private final String pattern;
    private final Pattern compiledPattern;
    private final boolean patternMalformed;
    private final String defaultValue;

    private ConfigDirectives(String type, List<String> options, String source, String unit,
                             Double rangeMin, Double rangeMax, boolean rangeMalformed,
                             String pattern, Pattern compiledPattern, boolean patternMalformed,
                             String defaultValue) {
        this.type = type;
        this.options = options;
        this.source = source;
        this.unit = unit;
        this.rangeMin = rangeMin;
        this.rangeMax = rangeMax;
        this.rangeMalformed = rangeMalformed;
        this.pattern = pattern;
        this.compiledPattern = compiledPattern;
        this.patternMalformed = patternMalformed;
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
        return parseLines(Arrays.asList(LINE_BREAK.split(comment, -1)));
    }

    /**
     * Parse directives from block-comment lines.
     *
     * @param commentLines the comment lines, or {@code null}
     * @return the parsed directives (never {@code null})
     */
    public static ConfigDirectives parseLines(List<String> commentLines) {
        if (commentLines == null || commentLines.isEmpty()) return EMPTY;
        String type = null;
        List<String> options = Collections.emptyList();
        String source = null;
        String unit = null;
        Double rangeMin = null;
        Double rangeMax = null;
        boolean rangeSeen = false;
        boolean rangeMalformed = false;
        String pattern = null;
        Pattern compiledPattern = null;
        boolean patternSeen = false;
        boolean patternMalformed = false;
        String defaultValue = null;
        for (String line : commentLines) {
            if (line == null) continue;
            String s = line.stripLeading();
            while (s.startsWith("#")) {
                s = s.substring(1).stripLeading();
            }
            if (!s.startsWith("@")) continue;
            int colon = s.indexOf(':');
            if (colon < 0) continue;
            String key = s.substring(1, colon).trim().toLowerCase(Locale.ROOT);
            String value = stripTrailingComment(s.substring(colon + 1)).trim();
            switch (key) {
                case "type" -> type = cleanScalar(value);
                case "source" -> source = cleanScalar(value);
                case "options" -> options = parseFlowList(value);
                case "unit" -> unit = cleanScalar(value);
                case "default" -> defaultValue = parseDefault(value);
                case "range" -> {
                    rangeSeen = true;
                    List<String> bounds = splitFlow(value);
                    if (bounds.size() != 2) {
                        rangeMin = null;
                        rangeMax = null;
                        rangeMalformed = true;
                    } else {
                        try {
                            rangeMin = parseBound(bounds.get(0));
                            rangeMax = parseBound(bounds.get(1));
                            if (rangeMin != null && rangeMax != null && rangeMin > rangeMax) {
                                rangeMin = null;
                                rangeMax = null;
                                rangeMalformed = true;
                            } else {
                                rangeMalformed = false;
                            }
                        } catch (NumberFormatException e) {
                            rangeMin = null;
                            rangeMax = null;
                            rangeMalformed = true;
                        }
                    }
                }
                case "pattern" -> {
                    patternSeen = true;
                    String unquoted = stripQuotes(value).trim();
                    if (unquoted.isEmpty()) {
                        pattern = "";
                        compiledPattern = null;
                        patternMalformed = true;
                    } else {
                        pattern = unquoted;
                        try {
                            compiledPattern = compilePattern(unquoted);
                            patternMalformed = false;
                        } catch (PatternSyntaxException e) {
                            compiledPattern = null;
                            patternMalformed = true;
                        }
                    }
                }
                default -> { /* unrecognised directive keys are ignored */ }
            }
        }
        if (type == null && options.isEmpty() && source == null && unit == null
                && !rangeSeen && !patternSeen && defaultValue == null) {
            return EMPTY;
        }
        return new ConfigDirectives(
                type, options, source, unit,
                rangeMin, rangeMax, rangeMalformed,
                pattern, compiledPattern, patternMalformed,
                defaultValue);
    }

    @SuppressWarnings("PMD.RegexCompiledPerCall") // Pattern text is runtime-supplied from the YAML @pattern directive.
    private static Pattern compilePattern(String regex) {
        return Pattern.compile(regex);
    }

    private static String cleanScalar(String raw) {
        String unquoted = stripQuotes(raw).trim();
        return unquoted.isEmpty() ? null : unquoted;
    }

    private static String parseDefault(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        if (trimmed.equalsIgnoreCase("null") || trimmed.equals("~")) return null;
        return trimmed;
    }

    /** {@code null}/{@code ~} = open end; otherwise a finite number, else NumberFormatException. */
    private static Double parseBound(String raw) {
        String v = raw.trim();
        if (v.equals("~") || v.equalsIgnoreCase("null")) return null;
        if (v.isEmpty()) throw new NumberFormatException("empty bound");
        double d = Double.parseDouble(v);
        if (!Double.isFinite(d)) throw new NumberFormatException("non-finite bound");
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
        return out.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(out);
    }

    /** Quote-aware split of a flow sequence body; items keep their quotes. */
    private static List<String> splitFlow(String raw) {
        if (raw == null) return Collections.emptyList();
        String v = raw.trim();
        if (v.isEmpty()) return Collections.emptyList();
        if (v.startsWith("[")) v = v.substring(1);
        if (v.endsWith("]")) v = v.substring(0, v.length() - 1);
        if (v.isBlank()) return Collections.emptyList();
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

    /** True when {@code @range} is present but not {@code [min, max]} with numeric/null bounds, min &lt;= max. */
    public boolean rangeMalformed() {
        return rangeMalformed;
    }

    /** The {@code @pattern} regex string with quotes removed, or {@code null} when absent. */
    public String pattern() {
        return pattern;
    }

    /** The compiled {@code @pattern} regular expression, or {@code null} when absent or malformed. */
    public Pattern compiledPattern() {
        return compiledPattern;
    }

    /** True when a valid {@code @pattern} regex is present. */
    public boolean hasPattern() {
        return compiledPattern != null;
    }

    /** True when {@code @pattern} is present but empty or not a valid regular expression. */
    public boolean patternMalformed() {
        return patternMalformed;
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
