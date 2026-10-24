package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.KeyPath;
import io.github.dailystruggle.bit.Vocabulary;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Reusable validator for ADR-073 {@code @<file>} cross-file setting references (ADR-121 section 6).
 */
public final class ReferenceSettingCheck {
    public static final BitCode CFG_UNKNOWN_FILE = DirectiveCodes.CFG.register(1201,
            "CFG_REFERENCE_UNKNOWN_FILE", Level.ERROR, BitTier.PBIT, DirectiveCodes.SUBSYSTEM, "",
            "Use a supported @<file> reference token, or replace the token with a literal value.");
    public static final BitCode CFG_SETTING_MISSING = DirectiveCodes.CFG.register(1202,
            "CFG_REFERENCE_SETTING_MISSING", Level.ERROR, BitTier.PBIT, DirectiveCodes.SUBSYSTEM, "",
            "Add the setting to the referenced file or use a literal value.");

    /** Code mapping for cross-file {@code @<file>} reference findings. */
    public record Codes(BitCode unknownFile, BitCode settingMissing) {
        public static final Codes DEFAULT = new Codes(CFG_UNKNOWN_FILE, CFG_SETTING_MISSING);

        public Codes {
            Objects.requireNonNull(unknownFile, "unknownFile");
            Objects.requireNonNull(settingMissing, "settingMissing");
        }
    }

    /**
     * Resolves whether {@code setting} exists in the referenced {@code file} stem.
     *
     * <p>Returns {@code Optional.of(true)} when present, {@code Optional.of(false)} when missing,
     * or {@code Optional.empty()} when the referenced file itself is absent/unknown so no finding
     * should be emitted.
     */
    @FunctionalInterface
    public interface SettingResolver {
        Optional<Boolean> resolves(String file, String setting);
    }

    private final Vocabulary referenceFiles;
    private final Codes codes;
    private final BiFunction<String, String, String> expectedFormat;

    public ReferenceSettingCheck(Vocabulary referenceFiles) {
        this(referenceFiles, Codes.DEFAULT, ReferenceSettingCheck::defaultExpectedDescription);
    }

    public ReferenceSettingCheck(Vocabulary referenceFiles, Codes codes) {
        this(referenceFiles, codes, ReferenceSettingCheck::defaultExpectedDescription);
    }

    public ReferenceSettingCheck(
            Vocabulary referenceFiles,
            Codes codes,
            BiFunction<String, String, String> expectedFormat) {
        this.referenceFiles = Objects.requireNonNull(referenceFiles, "referenceFiles");
        this.codes = Objects.requireNonNull(codes, "codes");
        this.expectedFormat = Objects.requireNonNull(expectedFormat, "expectedFormat");
    }

    public Vocabulary referenceFiles() {
        return referenceFiles;
    }

    public Codes codes() {
        return codes;
    }

    /**
     * Extracts the lower-case file stem from an {@code @<file>} reference token, or {@code null}
     * when {@code value} is not a reference token.
     */
    @SuppressWarnings("java:S1168")
    public static String referencedFile(Object value) {
        if (!(value instanceof String text)) return null;
        String trimmed = text.trim();
        if (!trimmed.startsWith("@") || trimmed.length() <= 1) return null;
        String target = trimmed.substring(1).trim().toLowerCase(Locale.ROOT);
        return target.isEmpty() ? null : target;
    }

    /** True when {@code value} is a non-empty {@code @<file>} reference token. */
    public static boolean isReference(Object value) {
        return referencedFile(value) != null;
    }

    /** Default expected-location description ({@code config.yml defaults.<setting>} or {@code <file>.yml <setting>}). */
    public static String defaultExpectedDescription(String file, String setting) {
        return "config".equals(file) ? "config.yml defaults." + setting : file + ".yml " + setting;
    }

    /**
     * Snapshot-backed {@link SettingResolver} that looks up {@code <file>.yml} (and a nested
     * {@code defaults} map for {@code config.yml}) in {@code snapshot}.
     */
    public static SettingResolver snapshotResolver(ConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return (file, setting) -> {
            Optional<ConfigSnapshot.Document> doc = snapshot.document(file + ".yml");
            if (doc.isEmpty()) return Optional.empty();
            if ("config".equals(file)) {
                Map<?, ?> defaults = ConfigConstraint.asMap(doc.get().get("defaults"));
                return Optional.of(defaults != null && defaults.containsKey(setting));
            }
            return Optional.of(doc.get().get(setting) != null);
        };
    }

    /** Checks all top-level entries (and any nested map entries named in {@code nestedSections}) of {@code document}. */
    public List<BitFinding> checkDocument(
            ConfigSnapshot.Document document,
            Set<String> nestedSections,
            SettingResolver resolver) {
        List<BitFinding> findings = new ArrayList<>();
        checkDocument(document, nestedSections, resolver, findings);
        return findings;
    }

    /** Appends findings for all top-level and {@code nestedSections} entries of {@code document}. */
    public void checkDocument(
            ConfigSnapshot.Document document,
            Set<String> nestedSections,
            SettingResolver resolver,
            List<BitFinding> findings) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(findings, "findings");
        if (document == null) return;
        Set<String> sections = nestedSections == null ? Set.of() : nestedSections;
        for (Map.Entry<String, Object> entry : document.values().entrySet()) {
            KeyPath path = KeyPath.root(document.localizedKey(entry.getKey()));
            if (sections.contains(entry.getKey())) {
                Map<?, ?> nestedMap = ConfigConstraint.asMap(entry.getValue());
                if (nestedMap == null) continue;
                for (Map.Entry<?, ?> nested : nestedMap.entrySet()) {
                    String key = String.valueOf(nested.getKey());
                    checkToken(document.path(), path.key(key), key, nested.getValue(), resolver, findings);
                }
            } else {
                checkToken(document.path(), path, entry.getKey(), entry.getValue(), resolver, findings);
            }
        }
    }

    /** Validates a single setting value if it is an {@code @<file>} reference token. */
    public void checkToken(
            String source,
            KeyPath path,
            String setting,
            Object value,
            SettingResolver resolver,
            List<BitFinding> findings) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(findings, "findings");
        String file = referencedFile(value);
        if (file == null) return;
        String token = String.valueOf(value).trim();
        if (!referenceFiles.contains(file)) {
            findings.add(BitFinding.of(codes.unknownFile(), source, path.toString(),
                    "Reference " + token + " names no supported file; the loader uses a hard-coded fallback",
                    String.join(", ", referenceFiles.values().stream().map(name -> "@" + name).toList()),
                    referenceFiles.suggest(file).map(name -> "@" + name).orElse(codes.unknownFile().remediation())));
            return;
        }
        Optional<Boolean> resolves = resolver.resolves(file, setting);
        if (Boolean.FALSE.equals(resolves.orElse(null))) {
            String expected = expectedFormat.apply(file, setting);
            findings.add(BitFinding.of(codes.settingMissing(), source, path.toString(),
                    "Reference " + token + " has no " + expected + "; the loader uses a hard-coded fallback",
                    expected, codes.settingMissing().remediation()));
        }
    }

    /** Evaluates {@code documents} against {@code snapshot} using {@link #snapshotResolver(ConfigSnapshot)}. */
    public List<BitFinding> evaluate(
            ConfigSnapshot snapshot,
            Collection<ConfigSnapshot.Document> documents,
            Set<String> nestedSections) {
        if (snapshot == null || documents == null) return List.of();
        SettingResolver resolver = snapshotResolver(snapshot);
        List<BitFinding> findings = new ArrayList<>();
        for (ConfigSnapshot.Document document : documents) {
            checkDocument(document, nestedSections, resolver, findings);
        }
        return findings;
    }
}
