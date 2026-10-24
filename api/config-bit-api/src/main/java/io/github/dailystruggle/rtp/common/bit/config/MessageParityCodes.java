package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.Vocabulary;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Code mapping and finding mapper for {@link MessageKeyParity} and operator message-file checks
 * (ADR-121 section 6; REQ-RTP-F-013).
 */
public record MessageParityCodes(
        BitCode baselineUnmapped,
        BitCode localeValuesMissing,
        BitCode localeKeyMissing,
        BitCode localeStaleRow,
        BitCode localeUnresolvedRow,
        BitCode keyMissing,
        BitCode keyUnknown) {

    public static final BitCode CFG_BASELINE_UNMAPPED = DirectiveCodes.CFG.register(1501,
            "CFG_MESSAGES_BASELINE_UNMAPPED", Level.WARNING, BitTier.PBIT, DirectiveCodes.SUBSYSTEM, "",
            "Add a row for the key to the baseline .<file>.lang.yml so locales can rename it.");
    public static final BitCode CFG_LOCALE_VALUES_MISSING = DirectiveCodes.CFG.register(1502,
            "CFG_MESSAGES_LOCALE_VALUES_MISSING", Level.WARNING, BitTier.PBIT, DirectiveCodes.SUBSYSTEM, "",
            "Ship the locale's <file>.yml beside its .<file>.lang.yml, or remove the rename map.");
    public static final BitCode CFG_LOCALE_KEY_MISSING = DirectiveCodes.CFG.register(1503,
            "CFG_MESSAGES_LOCALE_KEY_MISSING", Level.WARNING, BitTier.PBIT, DirectiveCodes.SUBSYSTEM, "",
            "Mirror the baseline key into the locale value file under its translated name.");
    public static final BitCode CFG_LOCALE_STALE_ROW = DirectiveCodes.CFG.register(1504,
            "CFG_MESSAGES_LOCALE_STALE_ROW", Level.WARNING, BitTier.PBIT, DirectiveCodes.SUBSYSTEM, "",
            "Remove the rename-map row; the baseline no longer ships that key.");
    public static final BitCode CFG_LOCALE_UNRESOLVED_ROW = DirectiveCodes.CFG.register(1505,
            "CFG_MESSAGES_LOCALE_UNRESOLVED_ROW", Level.WARNING, BitTier.PBIT, DirectiveCodes.SUBSYSTEM, "",
            "Make the rename-map target a top-level key of the sibling value file.");
    public static final BitCode CFG_KEY_MISSING = DirectiveCodes.CFG.register(1510,
            "CFG_MESSAGES_KEY_MISSING", Level.WARNING, BitTier.PBIT, DirectiveCodes.SUBSYSTEM, "",
            "Add the key back (or delete the file to regenerate it); until then the English default is used.");
    public static final BitCode CFG_KEY_UNKNOWN = DirectiveCodes.CFG.register(1511,
            "CFG_MESSAGES_KEY_UNKNOWN", Level.WARNING, BitTier.PBIT, DirectiveCodes.SUBSYSTEM, "",
            "Rename the key to a known message key or remove it; unknown keys are never read.");

    public static final MessageParityCodes DEFAULT = new MessageParityCodes(
            CFG_BASELINE_UNMAPPED,
            CFG_LOCALE_VALUES_MISSING,
            CFG_LOCALE_KEY_MISSING,
            CFG_LOCALE_STALE_ROW,
            CFG_LOCALE_UNRESOLVED_ROW,
            CFG_KEY_MISSING,
            CFG_KEY_UNKNOWN);

    public MessageParityCodes {
        Objects.requireNonNull(baselineUnmapped, "baselineUnmapped");
        Objects.requireNonNull(localeValuesMissing, "localeValuesMissing");
        Objects.requireNonNull(localeKeyMissing, "localeKeyMissing");
        Objects.requireNonNull(localeStaleRow, "localeStaleRow");
        Objects.requireNonNull(localeUnresolvedRow, "localeUnresolvedRow");
        Objects.requireNonNull(keyMissing, "keyMissing");
        Objects.requireNonNull(keyUnknown, "keyUnknown");
    }

    /** Maps one {@link MessageKeyParity.Gap} to a {@link BitFinding}. */
    public BitFinding finding(MessageKeyParity.Gap gap) {
        Objects.requireNonNull(gap, "gap");
        String where = gap.locale().isEmpty() ? "baseline " + gap.category() : gap.locale() + "/" + gap.category();
        return switch (gap.kind()) {
            case BASELINE_UNMAPPED -> BitFinding.of(baselineUnmapped, gap.path(), gap.key(),
                    where + ".yml key has no rename-map row", gap.key() + ": <localized name>",
                    baselineUnmapped.remediation());
            case LOCALE_VALUES_MISSING -> BitFinding.of(localeValuesMissing, gap.path(), "",
                    where + " ships a rename map but no value file; runtime falls back to English",
                    gap.path(), localeValuesMissing.remediation());
            case LOCALE_KEY_MISSING -> BitFinding.of(localeKeyMissing, gap.path(), gap.expected(),
                    where + " is missing baseline key " + gap.key() + "; runtime falls back to English",
                    gap.expected(), localeKeyMissing.remediation());
            case LOCALE_STALE_ROW -> BitFinding.of(localeStaleRow, gap.path(), gap.key(),
                    where + " rename map has a row for a key the baseline does not ship", "a baseline key",
                    localeStaleRow.remediation());
            case LOCALE_UNRESOLVED_ROW -> BitFinding.of(localeUnresolvedRow, gap.path(), gap.key(),
                    where + " maps " + gap.key() + " -> " + gap.expected() + " but the value file has no such key",
                    gap.expected(), localeUnresolvedRow.remediation());
        };
    }

    /** Bundled-locale parity findings for {@code categories} of {@code reader}. */
    public List<BitFinding> bundledFindings(
            MessageKeyParity.ResourceReader reader, Collection<String> categories) {
        List<BitFinding> findings = new ArrayList<>();
        if (reader == null || categories == null) return findings;
        for (MessageKeyParity.Gap gap : MessageKeyParity.gaps(reader, categories)) {
            findings.add(finding(gap));
        }
        return findings;
    }

    /** Bundled-locale parity findings for {@link MessageKeyParity#messageCategories()} of {@code reader}. */
    public List<BitFinding> bundledFindings(MessageKeyParity.ResourceReader reader) {
        return bundledFindings(reader, MessageKeyParity.messageCategories());
    }

    /**
     * Operator message-file findings: canonical keys in {@code expectedKeys} missing from, or
     * unknown to, {@code document}.
     */
    public List<BitFinding> operatorFindings(
            ConfigSnapshot.Document document, String fileStem, Set<String> expectedKeys) {
        List<BitFinding> findings = new ArrayList<>();
        if (document == null || expectedKeys == null) return findings;
        String path = document.path();
        Map<String, Object> values = document.values();
        for (String key : expectedKeys) {
            if (values.containsKey(key)) continue;
            findings.add(BitFinding.of(keyMissing, path, document.localizedKey(key),
                    "Message key is missing; the English default is used", key, keyMissing.remediation()));
        }
        Vocabulary vocabulary = new Vocabulary(fileStem + " message keys", expectedKeys);
        for (String key : values.keySet()) {
            if (expectedKeys.contains(key)) continue;
            String suggestion = vocabulary.suggest(key).map(s -> "did you mean " + s + "?")
                    .orElse(keyUnknown.remediation());
            findings.add(BitFinding.of(keyUnknown, path, document.localizedKey(key),
                    "Unknown message key: " + key, "a key of " + fileStem + ".yml", suggestion));
        }
        return findings;
    }
}
