package io.github.dailystruggle.rtp.common.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.rtp.common.action.ActionCbitCounters;
import io.github.dailystruggle.rtp.common.action.ActionDefinitionValidator;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BitKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("REQ-API-F-009 action finding severity policy (ADR-121)")
class BitSeverityPolicyTest {
    @TempDir
    Path pluginDirectory;
    private MockRTPServerAccessor accessor;

    @BeforeEach
    void installRtp() {
        accessor = RTPTestSetup.install(pluginDirectory.toFile());
    }

    @AfterEach
    void resetPolicy() {
        BitSeverityPolicy.resetDefaults();
        RTPTestSetup.cleanUp();
    }

    @Test
    void defaultDowngradesStructuralActionFindingsToWarning() {
        BitSeverityPolicy.resetDefaults();

        assertEquals(Level.WARNING, BitSeverityPolicy.effective(stepKeyFinding()).orElseThrow());
    }

    @Test
    void strictActionsKeepsStructuralActionFindingsAsErrors() {
        BitSeverityPolicy.load(true, Map.of());

        assertEquals(Level.ERROR, BitSeverityPolicy.effective(stepKeyFinding()).orElseThrow());
    }

    @Test
    void explicitOverrideWinsOverStrictActionsPreset() {
        BitSeverityPolicy.load(true, Map.of("ACTION_UNKNOWN_STEP_KEY", "INFO"));

        assertEquals(Level.INFO, BitSeverityPolicy.effective(stepKeyFinding()).orElseThrow());
    }

    @Test
    void offSuppressesFindingAndUnknownKeysOrLevelsAreIgnored() {
        accessor.logMessages.clear();
        BitSeverityPolicy.load(true, Map.of(
                "ACTION_UNKNOWN_STEP_KEY", "OFF",
                "ACTION_UNKNOWN_STEP_KYE", "ERROR",
                "ACTION_UNKNOWN_ACTION_NAME", "LOUD"));

        assertTrue(BitSeverityPolicy.effective(stepKeyFinding()).isEmpty());
        assertEquals(Level.ERROR, BitSeverityPolicy.effective(actionNameFinding()).orElseThrow());
        assertTrue(accessor.logMessages.stream().anyMatch(message ->
                message.contains("advanced/bit.yml overrides.ACTION_UNKNOWN_STEP_KYE")));
        assertTrue(accessor.logMessages.stream().anyMatch(message ->
                message.contains("overrides.ACTION_UNKNOWN_ACTION_NAME ignored: invalid severity LOUD")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuredGateOutcomeSampleThresholdIsLoaded() {
        ConfigParser<BitKeys> parser = mock(ConfigParser.class);
        when(parser.getConfigValue(BitKeys.gateOutcomeSamples, ActionCbitCounters.DEFAULT_GATE_OUTCOME_SAMPLES))
                .thenReturn(37);
        when(parser.getConfigValue(BitKeys.strictActions, false)).thenReturn(false);
        when(parser.getConfigValue(BitKeys.strictConfig, false)).thenReturn(false);
        when(parser.getMap(BitKeys.overrides)).thenReturn(Map.of());

        BitSeverityPolicy.loadFromConfig(parser);

        assertEquals(37, ActionCbitCounters.minimumGateSamples());
    }

    private static BitFinding stepKeyFinding() {
        return BitFinding.of(ActionDefinitionValidator.UNKNOWN_STEP_KEY,
                "actions.yml", "actions.test.lifecycle.onStart[0].CONSOL",
                "Unknown action step key", "CONSOLE", "CONSOLE");
    }

    private static BitFinding actionNameFinding() {
        return BitFinding.of(ActionDefinitionValidator.UNKNOWN_ACTION_NAME,
                "actions.yml", "actions.test.lifecycle.onStart[0].ACTION",
                "Unknown action name", "PULL_BACK", "PULL_BACK");
    }
}
