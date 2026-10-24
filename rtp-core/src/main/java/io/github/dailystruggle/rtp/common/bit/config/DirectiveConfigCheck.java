package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.Vocabulary;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.configuration.ConfigDirectives;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ADR-064 directive PBIT for one single-file {@code ConfigParser} owner (ADR-121 section 6).
 *
 * <p>Directives come from the bundled default resource at the same relative path (English
 * baseline), never from the operator's file, whose comments may be gone. On-disk top-level keys
 * map to canonical through the ADR-076 rename map. Checks: {@code @type}, {@code @options}
 * (case-insensitive), {@code @range}, unknown top-level keys, and a self-test that the bundled
 * value and {@code @default} satisfy their own directives. Unsure cases (unit-bearing strings,
 * {@code @source} registries, sections under a scalar directive) are skipped, not flagged.
 * A missing on-disk file or bundled resource yields no findings.
 */
public final class DirectiveConfigCheck implements ConfigOwnerCheck {
    public static final BitCode TYPE_MISMATCH = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1100,
            "RTP_DIRECTIVE_TYPE_MISMATCH", Level.ERROR, BitTier.PBIT, ConfigPbit.SUBSYSTEM, "",
            "Write a value of the type named by the key's @type directive in the bundled default file."));
    public static final BitCode OPTION_UNKNOWN = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1101,
            "RTP_DIRECTIVE_OPTION_UNKNOWN", Level.ERROR, BitTier.PBIT, ConfigPbit.SUBSYSTEM, "",
            "Use one of the values listed by the key's @options directive (case does not matter)."));
    public static final BitCode OUT_OF_RANGE = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1102,
            "RTP_DIRECTIVE_OUT_OF_RANGE", Level.ERROR, BitTier.PBIT, ConfigPbit.SUBSYSTEM, "",
            "Keep the value inside the key's @range [min, max] (inclusive; null is open-ended)."));
    public static final BitCode UNKNOWN_KEY = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1103,
            "RTP_DIRECTIVE_UNKNOWN_KEY", Level.WARNING, BitTier.PBIT, ConfigPbit.SUBSYSTEM, "",
            "Remove or rename the key; it is not read. Compare with the bundled default file."));
    public static final BitCode DEFAULT_INVALID = RtpBitCodes.RTP.register(1104,
            "RTP_DIRECTIVE_DEFAULT_INVALID", Level.ERROR, BitTier.PBIT, ConfigPbit.SUBSYSTEM, "",
            "Shipping bug: the bundled default value or @default violates its own directives; fix the resource.");
    public static final BitCode MALFORMED = RtpBitCodes.RTP.register(1105,
            "RTP_DIRECTIVE_MALFORMED", Level.ERROR, BitTier.PBIT, ConfigPbit.SUBSYSTEM, "",
            "Shipping bug: the bundled default does not parse or has an unreadable directive; fix the resource.");

    /**
     * Core single-file owners, check id -> path relative to the plugin data folder and to the jar root.
     * Addon-owned files are bound by the addon itself via {@link #bound}, since their defaults live in
     * the addon jar, not on {@link ConfigEnvironment#resources()}.
     */
    static final Map<String, String> OWNER_FILES = ownerFiles();

    private final String id;
    private final String path;
    /** Bundled-default source; null = {@link ConfigEnvironment#resources()}. */
    private final ClassLoader resourceLoader;

    public DirectiveConfigCheck(String id, String path) {
        this(id, path, null);
    }

    private DirectiveConfigCheck(String id, String path, ClassLoader resources) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.path = Objects.requireNonNull(path, "path must not be null");
        this.resourceLoader = resources;
    }

    /**
     * Check for {@code path} whose bundled default (directives) is read from {@code resources}
     * instead of the environment loader; for addons registering their own file through
     * {@link ConfigPbit#register}.
     */
    public static DirectiveConfigCheck bound(String id, String path, ClassLoader resources) {
        return new DirectiveConfigCheck(id, path, Objects.requireNonNull(resources, "resources must not be null"));
    }

    private static Map<String, String> ownerFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("config", "config.yml");
        files.put("safety", "safety.yml");
        files.put("economy", "economy.yml");
        files.put("blocks", "advanced/blocks.yml");
        files.put("biomes", "advanced/biomes.yml");
        files.put("logging", "advanced/logging.yml");
        files.put("database", "advanced/database.yml");
        files.put("performance", "advanced/performance.yml");
        files.put("metrics", "advanced/metrics.yml");
        files.put("ttl", "advanced/ttl.yml");
        files.put("bit", "advanced/bit.yml");
        return Collections.unmodifiableMap(files);
    }

    /** One check per {@link #OWNER_FILES} entry, in declaration order. */
    public static List<ConfigOwnerCheck> owners() {
        List<ConfigOwnerCheck> out = new ArrayList<>();
        OWNER_FILES.forEach((ownerId, file) -> out.add(new DirectiveConfigCheck(ownerId, file)));
        return List.copyOf(out);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean owns(String candidate) {
        return path.equals(candidate);
    }

    public String path() {
        return path;
    }

    @Override
    public List<BitFinding> check(ConfigSnapshot snapshot, ConfigEnvironment environment) {
        Optional<ConfigSnapshot.Document> found = snapshot == null ? Optional.empty() : snapshot.document(path);
        if (found.isEmpty()) return List.of();
        ConfigSnapshot.Document document = found.get();
        ClassLoader resourceLoader = this.resourceLoader;
        if (resourceLoader == null) {
            resourceLoader = environment == null
                    ? ConfigEnvironment.HEADLESS.resources() : environment.resources();
        }
        String bundledText = readResource(resourceLoader, path);
        if (bundledText == null) return List.of();
        List<BitFinding> findings = new ArrayList<>();
        String bundledSource = "bundled:" + path;
        RtpYamlConfig bundled;
        try {
            bundled = RtpYamlConfig.parse(bundledText);
        } catch (RuntimeException e) {
            findings.add(BitFinding.of(MALFORMED, bundledSource, "",
                    "Bundled default does not parse: " + e.getMessage(), "valid YAML", MALFORMED.remediation()));
            return findings;
        }
        for (String key : bundled.getKeys(true)) {
            ConfigDirectives directives = ConfigDirectives.parse(bundled.getComment(key));
            if (hasChecks(directives)) {
                if (directives.rangeMalformed()) {
                    findings.add(BitFinding.of(MALFORMED, bundledSource, key,
                            "@range is not [min, max] with numeric or null bounds and min <= max",
                            "[min, max]", MALFORMED.remediation()));
                }
                validate(directives, bundled.get(key)).ifPresent(v -> findings.add(BitFinding.of(DEFAULT_INVALID,
                        bundledSource, key + v.suffix(), "Bundled value: " + v.message(), v.expected(),
                        DEFAULT_INVALID.remediation())));
                if (directives.defaultValue() != null) {
                    validate(directives, directives.defaultValue()).ifPresent(v -> findings.add(BitFinding.of(
                            DEFAULT_INVALID, bundledSource, key, "@default: " + v.message(), v.expected(),
                            DEFAULT_INVALID.remediation())));
                }
                String diskKey = diskKey(document, key);
                if (document.yaml().contains(diskKey)) {
                    validate(directives, document.yaml().get(diskKey)).ifPresent(v -> findings.add(BitFinding.of(
                            v.code(), path, diskKey + v.suffix(), v.message(), v.expected(), v.suggestion())));
                }
            }
        }
        Set<String> known = bundled.getKeys(false);
        Vocabulary vocabulary = new Vocabulary("keys", known);
        for (String localized : document.yaml().getKeys(false)) {
            String canonical = document.canonical().getOrDefault(localized, localized);
            if (known.contains(canonical)) continue;
            String suggestion = vocabulary.suggest(canonical).map(s -> "did you mean '" + s + "'?")
                    .orElse(UNKNOWN_KEY.remediation());
            findings.add(BitFinding.of(UNKNOWN_KEY, path, localized,
                    "Unknown top-level key '" + localized + "'", "a key of the bundled " + path, suggestion));
        }
        return findings;
    }

    private record Violation(BitCode code, String suffix, String message, String expected, String suggestion) {}

    private static boolean hasChecks(ConfigDirectives d) {
        return d.type() != null || !d.options().isEmpty() || d.hasRange() || d.rangeMalformed();
    }

    /** First violation of {@code d} by {@code value}; empty when valid or not decidable. */
    private static Optional<Violation> validate(ConfigDirectives d, Object value) {
        if (value == null) return Optional.empty();
        String type = d.type() == null ? "" : d.type().trim().toLowerCase(Locale.ROOT);
        if (value instanceof RtpYamlSection || value instanceof Map<?, ?>) {
            // A scalar directive on a section documents its children; only list<> is decidable.
            if (type.startsWith("list<")) {
                return Optional.of(new Violation(TYPE_MISMATCH, "", "expected a list, found a section",
                        type, TYPE_MISMATCH.remediation()));
            }
            return Optional.empty();
        }
        if (type.startsWith("map")) {
            if (value instanceof List<?>) {
                return Optional.of(new Violation(TYPE_MISMATCH, "", "expected a section, found a list",
                        type, TYPE_MISMATCH.remediation()));
            }
            return Optional.empty();
        }
        if (type.startsWith("list<") && type.endsWith(">")) {
            if (!(value instanceof List<?> items)) return Optional.empty();
            String inner = type.substring(5, type.length() - 1).trim();
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                if (item instanceof List<?> || item instanceof RtpYamlSection || item instanceof Map<?, ?>) {
                    return Optional.of(new Violation(TYPE_MISMATCH, "[" + i + "]",
                            "expected a " + inner + " element, found a nested structure", inner,
                            TYPE_MISMATCH.remediation()));
                }
                Optional<Violation> v = validateScalar(inner, d, item, "[" + i + "]");
                if (v.isPresent()) return v;
            }
            return Optional.empty();
        }
        if (value instanceof List<?>) {
            if (type.isEmpty()) return Optional.empty();
            return Optional.of(new Violation(TYPE_MISMATCH, "", "expected a single " + type + ", found a list",
                    type, TYPE_MISMATCH.remediation()));
        }
        return validateScalar(type, d, value, "");
    }

    private static Optional<Violation> validateScalar(String type, ConfigDirectives d, Object value, String suffix) {
        if (value == null) return Optional.empty();
        String text = String.valueOf(value).trim();
        switch (type) {
            case "boolean" -> {
                if (value instanceof Boolean || text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
                    return Optional.empty();
                }
                return Optional.of(new Violation(TYPE_MISMATCH, suffix, "expected true or false, found '" + text + "'",
                        "boolean", TYPE_MISMATCH.remediation()));
            }
            case "integer", "number" -> {
                Double number = numberOf(value);
                if (number == null) {
                    if (!d.options().isEmpty()) return checkOptions(d, text, suffix);
                    if (d.unit() != null && !(value instanceof Boolean)) return Optional.empty();
                    return Optional.of(new Violation(TYPE_MISMATCH, suffix,
                            "expected " + article(type) + ", found '" + text + "'", type, TYPE_MISMATCH.remediation()));
                }
                if (type.equals("integer") && (number != Math.rint(number) || number.isInfinite())) {
                    return Optional.of(new Violation(TYPE_MISMATCH, suffix,
                            "expected a whole number, found " + text, "integer", TYPE_MISMATCH.remediation()));
                }
                return checkRange(d, number, text, suffix);
            }
            default -> {
                if (!d.options().isEmpty() && (type.isEmpty() || type.equals("enum") || type.equals("string"))) {
                    return checkOptions(d, text, suffix);
                }
                Double number = numberOf(value);
                return number == null ? Optional.empty() : checkRange(d, number, text, suffix);
            }
        }
    }

    private static Optional<Violation> checkOptions(ConfigDirectives d, String text, String suffix) {
        Vocabulary options = new Vocabulary("options", d.options());
        if (options.contains(text)) return Optional.empty();
        String suggestion = options.suggest(text).map(s -> "did you mean '" + s + "'?")
                .orElse("one of " + d.options());
        return Optional.of(new Violation(OPTION_UNKNOWN, suffix, "'" + text + "' is not an allowed value",
                String.valueOf(d.options()), suggestion));
    }

    private static Optional<Violation> checkRange(ConfigDirectives d, double number, String text, String suffix) {
        if (!d.hasRange()) return Optional.empty();
        boolean low = d.rangeMin() != null && number < d.rangeMin();
        boolean high = d.rangeMax() != null && number > d.rangeMax();
        if (!low && !high) return Optional.empty();
        String expected = "[" + bound(d.rangeMin()) + ", " + bound(d.rangeMax()) + "]";
        return Optional.of(new Violation(OUT_OF_RANGE, suffix, text + " is outside " + expected, expected,
                OUT_OF_RANGE.remediation()));
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
    private static String diskKey(ConfigSnapshot.Document document, String canonicalKey) {
        int dot = canonicalKey.indexOf('.');
        if (dot < 0) return document.localizedKey(canonicalKey);
        return document.localizedKey(canonicalKey.substring(0, dot)) + canonicalKey.substring(dot);
    }

    /** {@code null} when absent; a read failure propagates and is reported as {@code BIT_COMPONENT_THROWN}. */
    private static String readResource(ClassLoader loader, String resource) {
        if (loader == null) return null;
        try (InputStream in = loader.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("bundled default unreadable: " + resource, e);
        }
    }
}
