package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitLevels;
import io.github.dailystruggle.bit.Vocabulary;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.action.ActionCbitCounters;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BitKeys;
import java.lang.System.Logger.Level;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves configured severities for action-definition findings (ADR-121 sections 7 and 10). */
public final class BitSeverityPolicy {
    private static final Set<String> STRUCTURAL_ACTION_CODES = Set.of(
            "ACTION_UNKNOWN_LIFECYCLE_STAGE",
            "ACTION_UNKNOWN_STEP_KEY",
            "ACTION_UNKNOWN_ACTION_NAME",
            "ACTION_UNKNOWN_GATE_TYPE",
            "ACTION_UNKNOWN_TRIGGER_TYPE",
            "ACTION_PLACEHOLDER_OUT_OF_SCOPE",
            "ACTION_SCOREBOARD_MANAGED_TYPO",
            "ACTION_GATE_EXPRESSION_INVALID",
            "ACTION_UNKNOWN_GATE_SUBKEY",
            "ACTION_MISSING_GATE_SUBKEY",
            "ACTION_UNKNOWN_REGION",
            "ACTION_SCHEMATIC_UNRESOLVED",
            "ACTION_PAYLOAD_UNBALANCED",
            "ACTION_PAYLOAD_JSON_INVALID");
    private static final Set<Level> CONFIGURABLE_LEVELS = Set.of(
            Level.ERROR, Level.WARNING, Level.INFO, Level.DEBUG, Level.TRACE, Level.OFF);

    /** Config codes registered through {@link #stagedConfig}; WARNING unless {@code strictConfig}. */
    private static final Set<String> STAGED_CONFIG_CODES = ConcurrentHashMap.newKeySet();

    private static volatile boolean startupComplete;
    private static volatile State state = new State(false, false, Map.of(), Map.of());

    private BitSeverityPolicy() {}

    /** Resets policy to this release's staged defaults: structural action and config findings are warnings. */
    public static void resetDefaults() {
        startupComplete = false;
        state = new State(false, false, Map.of(), Map.of());
        ActionCbitCounters.setMinimumGateSamples(ActionCbitCounters.DEFAULT_GATE_OUTCOME_SAMPLES);
    }

    public static boolean strictActions() {
        return state.strictActions();
    }

    public static boolean strictConfig() {
        return state.strictConfig();
    }

    /**
     * Marks a config code as staged (ADR-121 section 6 risk mitigation): while {@code strictConfig}
     * is false its ERROR findings are reported as WARNING, so they neither reject a reload nor block
     * a previously tolerated config. Returns {@code code} so it can wrap a field initializer.
     */
    public static BitCode stagedConfig(BitCode code) {
        STAGED_CONFIG_CODES.add(code.key().toUpperCase(Locale.ROOT));
        return code;
    }

    public static boolean isStagedConfigCode(String code) {
        return code != null && STAGED_CONFIG_CODES.contains(code.trim().toUpperCase(Locale.ROOT));
    }

    public static boolean isStructuralActionCode(String code) {
        return code != null && STRUCTURAL_ACTION_CODES.contains(code.trim().toUpperCase(Locale.ROOT));
    }

    /** Loads the strict-action preset and per-code severity overrides. Explicit overrides win. */
    public static void load(boolean strictActions, Map<?, ?> rawOverrides) {
        load(strictActions, false, rawOverrides);
    }

    /** Loads both strict presets and per-code severity overrides. Explicit overrides win. */
    public static void load(boolean strictActions, boolean strictConfig, Map<?, ?> rawOverrides) {
        var registry = RtpBitCodes.initialiseOwners();
        Vocabulary registeredCodes = new Vocabulary("registered BIT code",
                registry.codes().stream().map(BitCode::key).toList());
        Map<String, Level> overrides = new TreeMap<>();
        Map<String, String> unresolved = new TreeMap<>();
        if (rawOverrides != null) {
            for (Map.Entry<?, ?> entry : rawOverrides.entrySet()) {
                String key = String.valueOf(entry.getKey()).trim().toUpperCase(Locale.ROOT);
                Optional<BitCode> code = registry.byKey(key);
                String configuredLevel = String.valueOf(entry.getValue()).trim().toUpperCase(Locale.ROOT);
                Level level = null;
                try {
                    level = Level.valueOf(configuredLevel);
                } catch (IllegalArgumentException invalidLevel) {
                    logInvalidLevel(entry.getKey(), configuredLevel);
                }
                if (level == null) continue;
                if (!CONFIGURABLE_LEVELS.contains(level)) {
                    logInvalidLevel(entry.getKey(), configuredLevel);
                    continue;
                }
                if (code.isPresent()) {
                    overrides.put(code.get().key(), level);
                } else {
                    String suggestion = registeredCodes.suggest(key).orElse("");
                    overrides.put(key, level);
                    unresolved.put(key, suggestion);
                    if (startupComplete) logUnregisteredOverride(String.valueOf(entry.getKey()), suggestion);
                }
            }
        }
        state = new State(strictActions, strictConfig, Map.copyOf(overrides), Map.copyOf(unresolved));
    }

    /** Reports addon overrides that remained unresolved after startup owners had registered. */
    public static void reportUnresolvedOverrides() {
        boolean alreadyComplete = startupComplete;
        startupComplete = true;
        if (alreadyComplete) return;
        var registry = RtpBitCodes.initialiseOwners();
        state.unresolvedOverrides().forEach((key, suggestion) -> {
            if (registry.byKey(key).isEmpty()) logUnregisteredOverride(key, suggestion);
        });
    }

    /** Loads the active {@code advanced/bit.yml} parser. */
    public static void loadFromConfig(ConfigParser<BitKeys> parser) {
        if (parser == null) {
            resetDefaults();
            return;
        }
        ActionCbitCounters.setMinimumGateSamples(positiveInt(
                parser.getConfigValue(BitKeys.gateOutcomeSamples, ActionCbitCounters.DEFAULT_GATE_OUTCOME_SAMPLES)));
        load(bool(parser.getConfigValue(BitKeys.strictActions, false)),
                bool(parser.getConfigValue(BitKeys.strictConfig, false)),
                parser.getMap(BitKeys.overrides));
    }

    private static boolean bool(Object raw) {
        return raw instanceof Boolean value ? value : Boolean.parseBoolean(String.valueOf(raw));
    }

    private static int positiveInt(Object raw) {
        int value;
        try {
            value = raw instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException invalidValue) {
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP bit] advanced/bit.yml gateOutcomeSamples must be a positive integer; using "
                            + ActionCbitCounters.DEFAULT_GATE_OUTCOME_SAMPLES,
                    invalidValue);
            return ActionCbitCounters.DEFAULT_GATE_OUTCOME_SAMPLES;
        }
        if (value > 0) return value;
        RTP.log(java.util.logging.Level.WARNING,
                "[RTP bit] advanced/bit.yml gateOutcomeSamples must be positive; using "
                        + ActionCbitCounters.DEFAULT_GATE_OUTCOME_SAMPLES);
        return ActionCbitCounters.DEFAULT_GATE_OUTCOME_SAMPLES;
    }

    /** Returns the effective severity, or empty when the finding is suppressed with {@code OFF}. */
    public static Optional<Level> effective(BitFinding finding) {
        if (finding == null) return Optional.empty();
        State current = state;
        Level override = current.overrides().get(finding.code().trim().toUpperCase(Locale.ROOT));
        if (override == Level.OFF) return Optional.empty();
        if (override != null) return Optional.of(override);
        if (!current.strictActions() && STRUCTURAL_ACTION_CODES.contains(finding.code().toUpperCase(Locale.ROOT))) {
            return Optional.of(Level.WARNING);
        }
        if (!current.strictConfig() && finding.severity() == Level.ERROR && isStagedConfigCode(finding.code())) {
            return Optional.of(Level.WARNING);
        }
        return Optional.of(finding.severity());
    }

    /** Returns the finding with its effective registered severity, or empty when suppressed. */
    public static Optional<BitFinding> effectiveFinding(BitFinding finding) {
        Optional<Level> severity = effective(finding);
        if (severity.isEmpty()) return Optional.empty();
        if (severity.get() == finding.severity()) return Optional.of(finding);
        BitCode code = RtpBitCodes.REGISTRY.byKey(finding.code()).orElse(null);
        if (code == null) return Optional.of(finding);
        return Optional.of(BitFinding.of(code, severity.get(), finding.source(), finding.path(),
                finding.message(), finding.expected(), finding.suggestion()));
    }

    /** Maps a concrete finding severity to the host logger without treating OFF as a finding. */
    public static java.util.logging.Level toJul(Level level) {
        return BitLevels.toJul(level);
    }

    private static void logInvalidLevel(Object key, String configuredLevel) {
        RTP.log(java.util.logging.Level.WARNING,
                "[RTP bit] advanced/bit.yml overrides." + key + " ignored: invalid severity "
                        + configuredLevel + "; use ERROR, WARNING, INFO, DEBUG, TRACE or OFF");
    }

    private static void logUnregisteredOverride(String key, String suggestion) {
        String didYouMean = suggestion.isBlank() ? "" : "; did you mean " + suggestion + "?";
        RTP.log(java.util.logging.Level.WARNING,
                "[RTP bit] advanced/bit.yml overrides." + key
                        + " ignored: unregistered BIT code" + didYouMean);
    }

    private record State(
            boolean strictActions, boolean strictConfig, Map<String, Level> overrides,
            Map<String, String> unresolvedOverrides) {}
}
