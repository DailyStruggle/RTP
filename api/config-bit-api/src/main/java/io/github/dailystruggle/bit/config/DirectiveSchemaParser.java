package io.github.dailystruggle.bit.config;

import io.github.dailystruggle.yaml.ConfigDirectives;
import io.github.dailystruggle.yaml.YamlConfig;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure-Java parser and immutable model for ADR-064 YAML block-comment schema directives:
 * {@code @type}, {@code @options}, {@code @range}, {@code @pattern}, {@code @unit}, {@code @default},
 * and {@code @source} ({@code :config-bit-api}).
 *
 * @governedBy ADR-064, ADR-115, ADR-121
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

    public static DirectiveSchemaParser from(ConfigDirectives d) {
        if (d == null) return EMPTY;
        if (d.type() == null
                && d.options().isEmpty()
                && d.rangeMin() == null
                && d.rangeMax() == null
                && !d.rangeMalformed()
                && d.pattern() == null
                && d.compiledPattern() == null
                && !d.patternMalformed()
                && d.unit() == null
                && d.defaultValue() == null
                && d.source() == null) {
            return EMPTY;
        }
        return new DirectiveSchemaParser(
                d.type(),
                d.options(),
                d.rangeMin(),
                d.rangeMax(),
                d.rangeMalformed(),
                d.pattern(),
                d.compiledPattern(),
                d.patternMalformed(),
                d.unit(),
                d.defaultValue(),
                d.source());
    }

    public static DirectiveSchemaParser parse(String commentBlock) {
        if (commentBlock == null || commentBlock.isBlank()) return EMPTY;
        return from(ConfigDirectives.parse(commentBlock));
    }

    public static DirectiveSchemaParser parse(List<String> commentLines) {
        if (commentLines == null || commentLines.isEmpty()) return EMPTY;
        return from(ConfigDirectives.parseLines(commentLines));
    }

    public static Map<String, DirectiveSchemaParser> parseAll(YamlConfig yaml) {
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
}
