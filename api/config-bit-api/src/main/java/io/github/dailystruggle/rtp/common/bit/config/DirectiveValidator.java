package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.Vocabulary;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reusable ADR-064 comment-directive validator over a {@link ConfigSnapshot.Document} and its
 * bundled default YAML text (ADR-064, ADR-121 section 6).
 */
public final class DirectiveValidator {
    private final DirectiveCodes codes;

    public DirectiveValidator() {
        this(DirectiveCodes.DEFAULT);
    }

    public DirectiveValidator(DirectiveCodes codes) {
        this.codes = Objects.requireNonNull(codes, "codes");
    }

    public DirectiveCodes codes() {
        return codes;
    }

    public List<BitFinding> check(ConfigSnapshot.Document document, String bundledText) {
        return validate(document, bundledText, codes);
    }

    public List<BitFinding> check(ConfigSnapshot snapshot, String path, ClassLoader resourceLoader) {
        return validate(snapshot, path, resourceLoader, codes);
    }

    /** Validates {@code document} against {@code bundledText} using {@link DirectiveCodes#DEFAULT}. */
    public static List<BitFinding> validate(ConfigSnapshot.Document document, String bundledText) {
        return validate(document, bundledText, DirectiveCodes.DEFAULT);
    }

    /**
     * Validates the document at {@code path} in {@code snapshot} against the bundled default loaded
     * from {@code resourceLoader}. Returns an empty list when either the on-disk document or the
     * bundled resource is absent.
     */
    public static List<BitFinding> validate(
            ConfigSnapshot snapshot, String path, ClassLoader resourceLoader, DirectiveCodes codes) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(codes, "codes");
        Optional<ConfigSnapshot.Document> found = snapshot == null ? Optional.empty() : snapshot.document(path);
        if (found.isEmpty()) return List.of();
        String bundledText = readResource(resourceLoader, path);
        if (bundledText == null) return List.of();
        return validate(found.get(), bundledText, codes);
    }

    /** Validates {@code document} against {@code bundledText} using {@code codes}. */
    public static List<BitFinding> validate(
            ConfigSnapshot.Document document, String bundledText, DirectiveCodes codes) {
        Objects.requireNonNull(codes, "codes");
        if (document == null || bundledText == null) return List.of();
        String path = document.path();
        List<BitFinding> findings = new ArrayList<>();
        String bundledSource = "bundled:" + path;
        RtpYamlConfig bundled;
        try {
            bundled = RtpYamlConfig.parse(bundledText);
        } catch (RuntimeException e) {
            findings.add(BitFinding.of(codes.malformed(), bundledSource, "",
                    "Bundled default does not parse: " + e.getMessage(), "valid YAML",
                    codes.malformed().remediation()));
            return findings;
        }
        for (String key : bundled.getKeys(true)) {
            DirectiveSchemaParser directives = DirectiveSchemaParser.parse(bundled.getComment(key));
            if (directives.hasChecks()) {
                if (directives.rangeMalformed()) {
                    findings.add(BitFinding.of(codes.malformed(), bundledSource, key,
                            "@range is not [min, max] with numeric or null bounds and min <= max",
                            "[min, max]", codes.malformed().remediation()));
                }
                if (directives.patternMalformed()) {
                    findings.add(BitFinding.of(codes.malformed(), bundledSource, key,
                            "@pattern is not a valid regular expression",
                            "valid regular expression", codes.malformed().remediation()));
                }
                validateValue(directives, bundled.get(key), codes).ifPresent(v -> findings.add(
                        BitFinding.of(codes.defaultInvalid(), bundledSource, key + v.suffix(),
                                "Bundled value: " + v.message(), v.expected(),
                                codes.defaultInvalid().remediation())));
                if (directives.defaultValue() != null) {
                    validateValue(directives, directives.defaultValue(), codes).ifPresent(v -> findings.add(
                            BitFinding.of(codes.defaultInvalid(), bundledSource, key,
                                    "@default: " + v.message(), v.expected(),
                                    codes.defaultInvalid().remediation())));
                }
                String diskKey = diskKey(document, key);
                if (document.yaml().contains(diskKey)) {
                    validateValue(directives, document.yaml().get(diskKey), codes).ifPresent(v -> findings.add(
                            BitFinding.of(v.code(), path, diskKey + v.suffix(), v.message(),
                                    v.expected(), v.suggestion())));
                }
            }
        }
        Set<String> known = bundled.getKeys(false);
        Vocabulary vocabulary = new Vocabulary("keys", known);
        for (String localized : document.yaml().getKeys(false)) {
            String canonical = document.canonical().getOrDefault(localized, localized);
            if (known.contains(canonical)) continue;
            String suggestion = vocabulary.suggest(canonical).map(s -> "did you mean '" + s + "'?")
                    .orElse(codes.unknownKey().remediation());
            findings.add(BitFinding.of(codes.unknownKey(), path, localized,
                    "Unknown top-level key '" + localized + "'", "a key of the bundled " + path, suggestion));
        }
        return findings;
    }

    private record Violation(BitCode code, String suffix, String message, String expected, String suggestion) {}

    /** First violation of {@code d} by {@code value}; empty when valid or not decidable. */
    private static Optional<Violation> validateValue(
            DirectiveSchemaParser d, Object value, DirectiveCodes codes) {
        if (value == null) return Optional.empty();
        String type = d.type() == null ? "" : d.type().trim().toLowerCase(Locale.ROOT);
        if (value instanceof RtpYamlSection || value instanceof Map<?, ?>) {
            // A scalar directive on a section documents its children; only list<> is decidable.
            if (type.startsWith("list<")) {
                return Optional.of(new Violation(codes.typeMismatch(), "", "expected a list, found a section",
                        type, codes.typeMismatch().remediation()));
            }
            return Optional.empty();
        }
        if (type.startsWith("map")) {
            if (value instanceof List<?>) {
                return Optional.of(new Violation(codes.typeMismatch(), "", "expected a section, found a list",
                        type, codes.typeMismatch().remediation()));
            }
            return Optional.empty();
        }
        if (type.startsWith("list<") && type.endsWith(">")) {
            if (!(value instanceof List<?> items)) return Optional.empty();
            String inner = type.substring(5, type.length() - 1).trim();
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                if (item instanceof List<?> || item instanceof RtpYamlSection || item instanceof Map<?, ?>) {
                    return Optional.of(new Violation(codes.typeMismatch(), "[" + i + "]",
                            "expected a " + inner + " element, found a nested structure", inner,
                            codes.typeMismatch().remediation()));
                }
                Optional<Violation> v = validateScalar(inner, d, item, "[" + i + "]", codes);
                if (v.isPresent()) return v;
            }
            return Optional.empty();
        }
        if (value instanceof List<?>) {
            if (type.isEmpty()) return Optional.empty();
            return Optional.of(new Violation(codes.typeMismatch(), "", "expected a single " + type + ", found a list",
                    type, codes.typeMismatch().remediation()));
        }
        return validateScalar(type, d, value, "", codes);
    }

    private static Optional<Violation> validateScalar(
            String type, DirectiveSchemaParser d, Object value, String suffix, DirectiveCodes codes) {
        if (value == null) return Optional.empty();
        String text = String.valueOf(value).trim();
        switch (type) {
            case "boolean" -> {
                if (value instanceof Boolean || text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
                    return checkPattern(d, text, suffix, codes);
                }
                return Optional.of(new Violation(codes.typeMismatch(), suffix,
                        "expected true or false, found '" + text + "'",
                        "boolean", codes.typeMismatch().remediation()));
            }
            case "integer", "number" -> {
                Double number = numberOf(value);
                if (number == null) {
                    if (!d.options().isEmpty()) return checkOptions(d, text, suffix, codes);
                    if (d.unit() != null && !(value instanceof Boolean)) {
                        return checkPattern(d, text, suffix, codes);
                    }
                    return Optional.of(new Violation(codes.typeMismatch(), suffix,
                            "expected " + article(type) + ", found '" + text + "'",
                            type, codes.typeMismatch().remediation()));
                }
                if (type.equals("integer") && (number != Math.rint(number) || number.isInfinite())) {
                    return Optional.of(new Violation(codes.typeMismatch(), suffix,
                            "expected a whole number, found " + text,
                            "integer", codes.typeMismatch().remediation()));
                }
                Optional<Violation> rangeViolation = checkRange(d, number, text, suffix, codes);
                return rangeViolation.isPresent() ? rangeViolation : checkPattern(d, text, suffix, codes);
            }
            default -> {
                if (!d.options().isEmpty() && (type.isEmpty() || type.equals("enum") || type.equals("string"))) {
                    Optional<Violation> optionViolation = checkOptions(d, text, suffix, codes);
                    if (optionViolation.isPresent()) return optionViolation;
                }
                Double number = numberOf(value);
                if (number != null) {
                    Optional<Violation> rangeViolation = checkRange(d, number, text, suffix, codes);
                    if (rangeViolation.isPresent()) return rangeViolation;
                }
                return checkPattern(d, text, suffix, codes);
            }
        }
    }

    private static Optional<Violation> checkOptions(
            DirectiveSchemaParser d, String text, String suffix, DirectiveCodes codes) {
        Vocabulary options = new Vocabulary("options", d.options());
        if (options.contains(text)) return Optional.empty();
        String suggestion = options.suggest(text).map(s -> "did you mean '" + s + "'?")
                .orElse("one of " + d.options());
        return Optional.of(new Violation(codes.optionUnknown(), suffix, "'" + text + "' is not an allowed value",
                String.valueOf(d.options()), suggestion));
    }

    private static Optional<Violation> checkRange(
            DirectiveSchemaParser d, double number, String text, String suffix, DirectiveCodes codes) {
        if (!d.hasRange()) return Optional.empty();
        boolean low = d.rangeMin() != null && number < d.rangeMin();
        boolean high = d.rangeMax() != null && number > d.rangeMax();
        if (!low && !high) return Optional.empty();
        String expected = "[" + bound(d.rangeMin()) + ", " + bound(d.rangeMax()) + "]";
        return Optional.of(new Violation(codes.outOfRange(), suffix, text + " is outside " + expected, expected,
                codes.outOfRange().remediation()));
    }

    private static Optional<Violation> checkPattern(
            DirectiveSchemaParser d, String text, String suffix, DirectiveCodes codes) {
        if (!d.hasPattern()) return Optional.empty();
        if (d.compiledPattern().matcher(text).matches()) return Optional.empty();
        return Optional.of(new Violation(codes.patternMismatch(), suffix,
                "'" + text + "' does not match @pattern " + d.pattern(),
                d.pattern(), codes.patternMismatch().remediation()));
    }

    private static Double numberOf(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (!(value instanceof String s)) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        try {
            double d = Double.parseDouble(v);
            return Double.isNaN(d) ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String bound(Double value) {
        if (value == null) return "null";
        return value == Math.rint(value) && !value.isInfinite() ? String.valueOf(value.longValue()) : value.toString();
    }

    private static String article(String type) {
        return type.equals("integer") ? "an integer" : "a number";
    }

    /** Canonical dotted key -> on-disk key; only the top-level segment is localized (ADR-076). */
    public static String diskKey(ConfigSnapshot.Document document, String canonicalKey) {
        int dot = canonicalKey.indexOf('.');
        if (dot < 0) return document.localizedKey(canonicalKey);
        return document.localizedKey(canonicalKey.substring(0, dot)) + canonicalKey.substring(dot);
    }

    /** {@code null} when absent; a read failure propagates as {@link UncheckedIOException}. */
    public static String readResource(ClassLoader loader, String resource) {
        if (loader == null) return null;
        try (InputStream in = loader.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("bundled default unreadable: " + resource, e);
        }
    }
}
