package io.github.dailystruggle.bit;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Small, YAML-library-independent validators for map-shaped configuration.
 *
 * <p>The {@link BitCode} overloads build registered findings ({@code codeId != 0}, ADR-121
 * section 10) at the code's default severity; the {@code String code} forms are legacy.
 */
public final class MapValidator {
    private MapValidator() {}

    public static List<BitFinding> unknownKeys(
            Map<?, ?> values, Vocabulary allowed, String source, KeyPath path, BitCode code) {
        return registered(unknownKeys(values, allowed, source, path, code.key()), code);
    }

    public static BitFinding required(
            Map<?, ?> values, String key, String source, KeyPath path, BitCode code) {
        return registered(required(values, key, source, path, code.key()), code);
    }

    public static BitFinding type(
            Object value, Class<?> type, String source, KeyPath path, BitCode code) {
        return registered(type(value, type, source, path, code.key()), code);
    }

    public static BitFinding oneOf(
            Object value, Vocabulary allowed, String source, KeyPath path, BitCode code) {
        return registered(oneOf(value, allowed, source, path, code.key()), code);
    }

    public static BitFinding range(
            Number value, double minimum, double maximum, String source, KeyPath path, BitCode code) {
        return registered(range(value, minimum, maximum, source, path, code.key()), code);
    }

    public static BitFinding reference(
            Object value, Predicate<Object> resolves, String source, KeyPath path, BitCode code) {
        return registered(reference(value, resolves, source, path, code.key()), code);
    }

    private static BitFinding registered(BitFinding finding, BitCode code) {
        if (finding == null) return null;
        return BitFinding.of(code, finding.source(), finding.path(), finding.message(),
                finding.expected(), finding.suggestion());
    }

    private static List<BitFinding> registered(List<BitFinding> findings, BitCode code) {
        return findings.stream().map(finding -> registered(finding, code)).toList();
    }

    public static List<BitFinding> unknownKeys(
            Map<?, ?> values, Vocabulary allowed, String source, KeyPath path, String code) {
        if (values == null || values.isEmpty()) return List.of();
        List<BitFinding> findings = new ArrayList<>();
        for (Object rawKey : values.keySet()) {
            String key = String.valueOf(rawKey);
            if (!allowed.contains(key)) {
                findings.add(new BitFinding(Level.ERROR, code, source, path.key(key).toString(),
                        "Unknown " + allowed.name() + " value: " + key,
                        allowed.values().stream().collect(Collectors.joining(", ")),
                        allowed.suggest(key).orElse("")));
            }
        }
        return List.copyOf(findings);
    }

    public static BitFinding required(
            Map<?, ?> values, String key, String source, KeyPath path, String code) {
        if (values != null && values.containsKey(key) && values.get(key) != null) return null;
        return new BitFinding(Level.ERROR, code, source, path.key(key).toString(),
                "Required key is missing", key, "");
    }

    public static BitFinding type(
            Object value, Class<?> type, String source, KeyPath path, String code) {
        if (value == null || type.isInstance(value)) return null;
        return new BitFinding(Level.ERROR, code, source, path.toString(),
                "Value has the wrong type", type.getSimpleName(), "");
    }

    public static BitFinding oneOf(
            Object value, Vocabulary allowed, String source, KeyPath path, String code) {
        String candidate = value == null ? "" : value.toString();
        if (allowed.contains(candidate)) return null;
        return new BitFinding(Level.ERROR, code, source, path.toString(),
                "Value is not allowed: " + candidate,
                String.join(", ", allowed.values()), allowed.suggest(candidate).orElse(""));
    }

    public static BitFinding range(
            Number value, double minimum, double maximum, String source, KeyPath path, String code) {
        if (value == null || (value.doubleValue() >= minimum && value.doubleValue() <= maximum)) return null;
        return new BitFinding(Level.ERROR, code, source, path.toString(),
                "Value is outside the allowed range", minimum + ".." + maximum, "");
    }

    public static BitFinding reference(
            Object value, Predicate<Object> resolves, String source, KeyPath path, String code) {
        if (value == null || resolves.test(value)) return null;
        return new BitFinding(Level.ERROR, code, source, path.toString(),
                "Referenced value does not resolve", "an existing reference", "");
    }

    public static String allowedValues(Collection<String> values) {
        return values == null ? "" : String.join(", ", values);
    }
}
