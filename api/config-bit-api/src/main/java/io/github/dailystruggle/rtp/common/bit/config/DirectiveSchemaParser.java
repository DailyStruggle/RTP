package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parser and immutable model for ADR-064 YAML block-comment schema directives: {@code @type},
 * {@code @options}, {@code @range}, {@code @pattern}, {@code @unit}, {@code @default}, and
 * {@code @source}.
 */
public record DirectiveSchemaParser(
        String type,
        List<String> options,
        Double rangeMin,
        Double rangeMax,
        boolean rangeMalformed,
        String pattern,
        Pattern compiledPattern,
        boolean patternMalformed,
        String unit,
        String defaultValue,
        String source) {

    private static final Pattern LINE_SPLIT = Pattern.compile("\\R");

    public static final DirectiveSchemaParser EMPTY = new DirectiveSchemaParser(
            null, List.of(), null, null, false, null, null, false, null, null, null);

    public DirectiveSchemaParser {
        options = options == null ? List.of() : List.copyOf(options);
    }

    public boolean hasRange() {
        return rangeMin != null || rangeMax != null;
    }

    public boolean hasPattern() {
        return compiledPattern != null;
    }

    public boolean hasChecks() {
        return type != null || !options.isEmpty() || hasRange() || rangeMalformed || hasPattern() || patternMalformed;
    }

    /** Parses ADR-064 directives from a multi-line comment string. */
    public static DirectiveSchemaParser parse(String commentBlock) {
        if (commentBlock == null || commentBlock.isBlank()) return EMPTY;
        return parse(List.of(LINE_SPLIT.split(commentBlock)));
    }

    /** Parses ADR-064 directives from the comment lines attached to one YAML key. */
    public static DirectiveSchemaParser parse(List<String> commentLines) {
        if (commentLines == null || commentLines.isEmpty()) return EMPTY;
        String type = null;
        List<String> options = List.of();
        Double rangeMin = null;
        Double rangeMax = null;
        boolean rangeMalformed = false;
        String pattern = null;
        Pattern compiledPattern = null;
        boolean patternMalformed = false;
        String unit = null;
        String defaultValue = null;
        String source = null;

        for (String rawLine : commentLines) {
            if (rawLine == null) continue;
            String line = stripHashPrefix(rawLine);
            if (!line.startsWith("@")) continue;
            int colon = line.indexOf(':');
            if (colon <= 1) continue;
            String tag = line.substring(1, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (tag) {
                case "type" -> type = cleanScalar(value);
                case "options" -> options = parseOptions(value);
                case "range" -> {
                    RangeParseResult range = parseRange(value);
                    rangeMin = range.min();
                    rangeMax = range.max();
                    rangeMalformed = range.malformed();
                }
                case "pattern" -> {
                    PatternParseResult parsedPattern = parsePattern(value);
                    pattern = parsedPattern.raw();
                    compiledPattern = parsedPattern.compiled();
                    patternMalformed = parsedPattern.malformed();
                }
                case "unit" -> unit = cleanScalar(value);
                case "default" -> defaultValue = parseDefault(value);
                case "source" -> source = cleanScalar(value);
                default -> {
                    // Unrecognised directive tags are ignored.
                }
            }
        }
        return new DirectiveSchemaParser(
                type, options, rangeMin, rangeMax, rangeMalformed,
                pattern, compiledPattern, patternMalformed, unit, defaultValue, source);
    }

    /** Parses directives for every key in {@code yaml} (deep traversal). */
    public static Map<String, DirectiveSchemaParser> parseAll(RtpYamlConfig yaml) {
        Objects.requireNonNull(yaml, "yaml");
        Map<String, DirectiveSchemaParser> out = new LinkedHashMap<>();
        for (String key : yaml.getKeys(true)) {
            DirectiveSchemaParser parsed = parse(yaml.getComment(key));
            if (!EMPTY.equals(parsed)) {
                out.put(key, parsed);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static String stripHashPrefix(String rawLine) {
        String trimmed = rawLine.trim();
        while (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }

    private static String cleanScalar(String raw) {
        String unquoted = unquote(raw.trim()).trim();
        return unquoted.isEmpty() ? null : unquoted;
    }

    private static String parseDefault(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            String unquoted = unquote(trimmed);
            return unquoted.isEmpty() ? null : unquoted;
        }
        if (trimmed.equalsIgnoreCase("null") || trimmed.equals("~")) return null;
        return trimmed;
    }

    private static List<String> parseOptions(String raw) {
        String body = raw.trim();
        if (body.startsWith("[") && body.endsWith("]")) {
            body = body.substring(1, body.length() - 1).trim();
        }
        if (body.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : body.split(",")) {
            String item = unquote(part.trim()).trim();
            if (!item.isEmpty()) out.add(item);
        }
        return List.copyOf(out);
    }

    private record RangeParseResult(Double min, Double max, boolean malformed) {}

    private static RangeParseResult parseRange(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return new RangeParseResult(null, null, true);
        }
        String inner = trimmed.substring(1, trimmed.length() - 1);
        String[] parts = inner.split(",", -1);
        if (parts.length != 2) {
            return new RangeParseResult(null, null, true);
        }
        BoundParse min = parseBound(parts[0].trim());
        BoundParse max = parseBound(parts[1].trim());
        if (min.malformed() || max.malformed()) {
            return new RangeParseResult(null, null, true);
        }
        if (min.value() != null && max.value() != null && min.value() > max.value()) {
            return new RangeParseResult(null, null, true);
        }
        return new RangeParseResult(min.value(), max.value(), false);
    }

    private record BoundParse(Double value, boolean malformed) {}

    private static BoundParse parseBound(String token) {
        if (token.equalsIgnoreCase("null")) {
            return new BoundParse(null, false);
        }
        if (token.isEmpty()) {
            return new BoundParse(null, true);
        }
        try {
            double parsed = Double.parseDouble(token);
            if (!Double.isFinite(parsed)) return new BoundParse(null, true);
            return new BoundParse(parsed, false);
        } catch (NumberFormatException e) {
            return new BoundParse(null, true);
        }
    }

    private record PatternParseResult(String raw, Pattern compiled, boolean malformed) {}

    @SuppressWarnings("PMD.RegexCompiledPerCall") // Pattern text is runtime-supplied from the YAML @pattern directive.
    private static PatternParseResult parsePattern(String raw) {
        String unquoted = unquote(raw.trim()).trim();
        if (unquoted.isEmpty()) {
            return new PatternParseResult("", null, true);
        }
        try {
            return new PatternParseResult(unquoted, Pattern.compile(unquoted), false);
        } catch (PatternSyntaxException e) {
            return new PatternParseResult(unquoted, null, true);
        }
    }

    private static String unquote(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }
}
