package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.NetworkMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.PlaceholderMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code messages} config owner (ADR-121 section 6; REQ-RTP-F-013, S-007).
 *
 * <p>Two passes: bundled locales against the English baseline ({@link MessageKeyParity} over
 * {@link ConfigEnvironment#resources()}), and the operator's {@code advanced/messages/*.yml} against
 * the canonical key set (message enum names, bundled baseline keys, {@code version}). All findings
 * are WARNING: a missing message falls back to English at runtime, never breaks a command. A
 * resource tree without the baseline (headless, bare test classpath) skips the bundled pass.
 */
public final class MessagesConfigCheck implements ConfigOwnerCheck {
    public static final String ID = "messages";
    private static final String SUBSYSTEM = ConfigPbit.SUBSYSTEM;
    private static final String VERSION_KEY = "version";

    public static final BitCode BASELINE_UNMAPPED = RtpBitCodes.RTP.register(1501,
            "RTP_MESSAGES_BASELINE_UNMAPPED", Level.WARNING, BitTier.PBIT, SUBSYSTEM, "",
            "Add a row for the key to the baseline .<file>.lang.yml so locales can rename it.");
    public static final BitCode LOCALE_VALUES_MISSING = RtpBitCodes.RTP.register(1502,
            "RTP_MESSAGES_LOCALE_VALUES_MISSING", Level.WARNING, BitTier.PBIT, SUBSYSTEM, "",
            "Ship the locale's <file>.yml beside its .<file>.lang.yml, or remove the rename map.");
    public static final BitCode LOCALE_KEY_MISSING = RtpBitCodes.RTP.register(1503,
            "RTP_MESSAGES_LOCALE_KEY_MISSING", Level.WARNING, BitTier.PBIT, SUBSYSTEM, "",
            "Mirror the baseline key into the locale value file under its translated name.");
    public static final BitCode LOCALE_STALE_ROW = RtpBitCodes.RTP.register(1504,
            "RTP_MESSAGES_LOCALE_STALE_ROW", Level.WARNING, BitTier.PBIT, SUBSYSTEM, "",
            "Remove the rename-map row; the baseline no longer ships that key.");
    public static final BitCode LOCALE_UNRESOLVED_ROW = RtpBitCodes.RTP.register(1505,
            "RTP_MESSAGES_LOCALE_UNRESOLVED_ROW", Level.WARNING, BitTier.PBIT, SUBSYSTEM, "",
            "Make the rename-map target a top-level key of the sibling value file.");
    public static final BitCode KEY_MISSING = RtpBitCodes.RTP.register(1510,
            "RTP_MESSAGES_KEY_MISSING", Level.WARNING, BitTier.PBIT, SUBSYSTEM, "",
            "Add the key back (or delete the file to regenerate it); until then the English default is used.");
    public static final BitCode KEY_UNKNOWN = RtpBitCodes.RTP.register(1511,
            "RTP_MESSAGES_KEY_UNKNOWN", Level.WARNING, BitTier.PBIT, SUBSYSTEM, "",
            "Rename the key to a known message key or remove it; unknown keys are never read.");

    public static final MessageParityCodes CODES = new MessageParityCodes(
            BASELINE_UNMAPPED,
            LOCALE_VALUES_MISSING,
            LOCALE_KEY_MISSING,
            LOCALE_STALE_ROW,
            LOCALE_UNRESOLVED_ROW,
            KEY_MISSING,
            KEY_UNKNOWN);

    public static final MessagesConfigCheck INSTANCE = new MessagesConfigCheck();

    private MessagesConfigCheck() {}

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean owns(String path) {
        return ConfigReferenceCheck.inDirectory(path, MessageKeyParity.MESSAGES_DIR);
    }

    @Override
    public List<BitFinding> check(ConfigSnapshot snapshot, ConfigEnvironment environment) {
        MessageKeyParity.ResourceReader bundled = MessageKeyParity.ResourceReader.classpath(environment.resources());
        List<BitFinding> findings = new ArrayList<>(bundledFindings(bundled));
        if (snapshot != null) findings.addAll(operatorFindings(snapshot, bundled));
        return findings;
    }

    /** Bundled-locale parity findings for the split messages tree of {@code reader}. */
    public static List<BitFinding> bundledFindings(MessageKeyParity.ResourceReader reader) {
        return CODES.bundledFindings(reader);
    }

    /** Operator file findings: canonical keys missing from, or unknown to, each on-disk message file. */
    public static List<BitFinding> operatorFindings(ConfigSnapshot snapshot, MessageKeyParity.ResourceReader bundled) {
        List<BitFinding> findings = new ArrayList<>();
        for (String file : MessageKeyParity.MESSAGE_FILES) {
            String path = MessageKeyParity.MESSAGES_DIR + "/" + file + ".yml";
            ConfigSnapshot.Document document = snapshot.document(path).orElse(null);
            if (document == null) continue;
            findings.addAll(CODES.operatorFindings(document, file, expectedKeys(file, bundled)));
        }
        return findings;
    }

    /** Canonical keys of one messages file: enum names, {@code version}, and bundled baseline keys. */
    public static Set<String> expectedKeys(String file, MessageKeyParity.ResourceReader bundled) {
        Set<String> keys = new LinkedHashSet<>();
        for (Enum<?> constant : enumConstants(file)) keys.add(constant.name());
        keys.add(VERSION_KEY);
        if (bundled != null) keys.addAll(MessageKeyParity.keys(bundled, MessageKeyParity.MESSAGES_DIR + "/" + file + ".yml"));
        return keys;
    }

    /** Message enum backing {@code advanced/messages/<file>.yml}; empty for an unknown file. */
    public static Enum<?>[] enumConstants(String file) {
        return switch (file) {
            case "commands" -> CommandMessages.values();
            case "network" -> NetworkMessages.values();
            case "placeholders" -> PlaceholderMessages.values();
            case "player" -> PlayerMessages.values();
            case "system" -> SystemMessages.values();
            default -> new Enum<?>[0];
        };
    }
}
