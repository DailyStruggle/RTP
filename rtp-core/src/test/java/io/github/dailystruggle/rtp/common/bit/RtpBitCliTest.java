package io.github.dailystruggle.rtp.common.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.rtp.common.action.ActionConfigLoader;
import io.github.dailystruggle.rtp.common.action.ActionManager;

import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("REQ-API-F-009: headless BIT PBIT delivery")
class RtpBitCliTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void resetBitState() {
        ActionConfigLoader.loadActionsDirectory(tempDir.resolve("missing").toFile(), new ActionManager());
        BitSeverityPolicy.resetDefaults();
    }

    @Test
    void malformedActionYamlIsReportedAsPbitFailure() throws Exception {
        Path actions = tempDir.resolve("definitions/actions");
        Files.createDirectories(actions);
        Path malformed = actions.resolve("broken.yml");
        Files.writeString(malformed, "lifecycle: [\n");

        ActionConfigLoader.loadActionsDirectory(actions.toFile(), new ActionManager());
        BitReport report = RtpBitComponents.actionDefinitions().check().call();

        assertEquals(BitHealth.FAILED, report.health());
        assertTrue(report.findings().stream().anyMatch(finding ->
                finding.code().equals("BIT_PBIT_FAILED") && finding.source().equals(malformed.toString())));
    }

    @Test
    void validDirectoryPrintsVersionedJsonAndReturnsSuccess() throws Exception {
        Path config = Files.createDirectory(tempDir.resolve("valid"));
        List<String> output = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        int exitCode = RtpBitCli.run(new String[]{"--config", config.toString(), "--json"}, output::add, errors::add);

        assertEquals(0, exitCode);
        assertTrue(errors.isEmpty());
        assertTrue(output.get(0).contains("\"schema\":" + BitReport.CURRENT_SCHEMA));
    }

    @Test
    void strictModeMakesUnknownActionKeysAnError() throws Exception {
        Path actions = tempDir.resolve("strict/definitions/actions");
        Files.createDirectories(actions);
        Files.writeString(actions.resolve("unknown.yml"), "lifecycle:\n  onStart:\n    - CONSOL: say hello\n");
        List<String> output = new ArrayList<>();

        int exitCode = RtpBitCli.run(new String[]{"--config", tempDir.resolve("strict").toString(), "--strict", "--json"},
                output::add, ignored -> {
                });

        assertEquals(1, exitCode);
        assertTrue(output.get(0).contains("ACTION_UNKNOWN_STEP_KEY"));
    }

    @Test
    void actionOptionRunsInitiatedSimulation() throws Exception {
        Path config = tempDir.resolve("action-config");
        Path actions = config.resolve("definitions/actions");
        Files.createDirectories(actions);
        Files.writeString(actions.resolve("sample.yml"), """
                placement: false
                confinement:
                  boundary: NONE
                  duration: 30s
                lifecycle:
                  onStart:
                    - CONSOLE: "say ready"
                  onBoundaryViolation:
                    - CONSOLE: "say violated"
                """);
        List<String> output = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        int exitCode = RtpBitCli.run(new String[]{"--config", config.toString(), "--action", "sample",
                        "--players", "2", "--script", "violate"}, output::add, errors::add);

        assertEquals(0, exitCode);
        assertTrue(errors.isEmpty());
        assertTrue(output.stream().anyMatch(line -> line.toUpperCase().contains("SIMULATION")));
        assertTrue(output.stream().anyMatch(line -> line.contains("say ready")));
        assertTrue(output.stream().anyMatch(line -> line.contains("say violated")));
    }

    @Test
    void missingConfigIsAUsageError() {
        List<String> errors = new ArrayList<>();

        int exitCode = RtpBitCli.run(new String[]{"--json"}, ignored -> {
        }, errors::add);

        assertEquals(2, exitCode);
        assertTrue(errors.get(0).contains("existing directory"));
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    void invalidArgumentsAreReportedAsUsageErrors(String[] args, String expectedMessage) {
        List<String> errors = new ArrayList<>();

        int exitCode = RtpBitCli.run(args, ignored -> {}, errors::add);

        assertEquals(2, exitCode);
        assertTrue(errors.get(0).contains(expectedMessage));
        assertTrue(errors.get(1).startsWith("Usage:"));
    }

    static Stream<Arguments> invalidArguments() {
        return Stream.of(
                Arguments.of((Object) new String[]{"--config"}, "--config requires one directory"),
                Arguments.of((Object) new String[]{"--config", ""}, "--config requires one directory"),
                Arguments.of((Object) new String[]{"--config", "not-a-real-config-directory"},
                        "--config must name an existing directory"),
                Arguments.of((Object) new String[]{"--config", ".", "--config", "."},
                        "--config requires one directory"),
                Arguments.of((Object) new String[]{"--action"}, "--action requires one action id"),
                Arguments.of((Object) new String[]{"--action", " "}, "--action requires one action id"),
                Arguments.of((Object) new String[]{"--action", "first", "--action", "second"},
                        "--action requires one action id"),
                Arguments.of((Object) new String[]{"--players"}, "--players requires a number from 1 to 16"),
                Arguments.of((Object) new String[]{"--players", "many"}, "--players requires a number from 1 to 16"),
                Arguments.of((Object) new String[]{"--players", "0"}, "--players must be between 1 and 16"),
                Arguments.of((Object) new String[]{"--players", "17"}, "--players must be between 1 and 16"),
                Arguments.of((Object) new String[]{"--script"}, "--script requires comma-separated events"),
                Arguments.of((Object) new String[]{"--script", "teleport"}, "--script supports violate,die,expire"),
                Arguments.of((Object) new String[]{"--unknown"}, "unknown argument: --unknown"),
                Arguments.of((Object) new String[]{"--config", ".", "--players", "2"},
                        "--players and --script require --action"),
                Arguments.of((Object) new String[]{"--config", ".", "--script", "violate"},
                        "--players and --script require --action"));
    }

    @Test
    void readsStrictActionsAsBooleanAndString() throws Exception {
        Path booleanConfig = tempDir.resolve("strict-boolean");
        writeUnknownAction(booleanConfig.resolve("definitions/actions/unknown.yml"));
        Files.createDirectories(booleanConfig.resolve("advanced"));
        Files.writeString(booleanConfig.resolve("advanced/bit.yml"), "strictActions: true\n");
        List<String> booleanOutput = new ArrayList<>();
        List<String> booleanErrors = new ArrayList<>();

        assertEquals(1, RtpBitCli.run(new String[]{"--config", booleanConfig.toString()},
                booleanOutput::add, booleanErrors::add));
        assertTrue(BitSeverityPolicy.strictActions());
        assertTrue(booleanOutput.stream().anyMatch(line -> line.contains("ACTION_UNKNOWN_STEP_KEY")));

        Path stringConfig = tempDir.resolve("strict-string");
        writeUnknownAction(stringConfig.resolve("definitions/actions/unknown.yml"));
        Files.createDirectories(stringConfig.resolve("advanced"));
        Files.writeString(stringConfig.resolve("advanced/bit.yml"), "strictActions: 'true'\n");
        List<String> stringOutput = new ArrayList<>();
        List<String> stringErrors = new ArrayList<>();

        assertEquals(1, RtpBitCli.run(new String[]{"--config", stringConfig.toString()},
                stringOutput::add, stringErrors::add));
        assertTrue(BitSeverityPolicy.strictActions());
        assertTrue(stringOutput.stream().anyMatch(line -> line.contains("ACTION_UNKNOWN_STEP_KEY")));
    }

    @Test
    void loadsOffSeverityOverride() {
        BitSeverityPolicy.load(false, Map.of("ACTION_UNKNOWN_STEP_KEY", "OFF"));

        assertTrue(BitSeverityPolicy.effective(new BitFinding(Level.WARNING, "ACTION_UNKNOWN_STEP_KEY",
                "", "", "", "", "")).isEmpty());
    }

    @Test
    void unparsableBitConfigurationReturnsUsageFailure() throws Exception {
        Path config = tempDir.resolve("invalid-bit-config");
        Files.createDirectories(config.resolve("advanced"));
        Files.writeString(config.resolve("advanced/bit.yml"), "strictActions: [\n");
        List<String> errors = new ArrayList<>();

        int exitCode = RtpBitCli.run(new String[]{"--config", config.toString()}, ignored -> {}, errors::add);

        assertEquals(2, exitCode);
        assertTrue(errors.get(0).startsWith("Unable to read BIT configuration:"));
    }

    @Test
    void unknownActionIdIsReportedAsUsageFailure() throws Exception {
        Path config = Files.createDirectory(tempDir.resolve("unknown-action"));
        List<String> errors = new ArrayList<>();

        int exitCode = RtpBitCli.run(new String[]{"--config", config.toString(), "--action", "missing"},
                ignored -> {}, errors::add);

        assertEquals(2, exitCode);
        assertTrue(errors.get(0).contains("Unknown action definition: missing"));
    }

    @Test
    void selectsActionDirectoryFallbackAndConfigFilePath() throws Exception {
        Path fallbackConfig = Files.createDirectory(tempDir.resolve("fallback-config"));
        writeUnknownAction(fallbackConfig.resolve("unknown.yml"));
        List<String> fallbackOutput = new ArrayList<>();

        assertEquals(1, RtpBitCli.run(new String[]{"--config", fallbackConfig.toString(), "--strict"},
                fallbackOutput::add, ignored -> {}));
        assertTrue(fallbackOutput.stream().anyMatch(line -> line.contains("ACTION_UNKNOWN_STEP_KEY")));

        Path configFile = tempDir.resolve("config-file");
        Files.createDirectories(configFile.resolve("definitions/actions"));
        Files.writeString(configFile.resolve("config.yml"), "version: 1\n");
        writeUnknownAction(configFile.resolve("definitions/actions/unknown.yml"));
        List<String> configFileOutput = new ArrayList<>();

        assertEquals(1, RtpBitCli.run(new String[]{"--config", configFile.toString(), "--strict"},
                configFileOutput::add, ignored -> {}));
        assertTrue(configFileOutput.stream().anyMatch(line -> line.contains("ACTION_UNKNOWN_STEP_KEY")));
    }

    @Test
    void emptyScriptValueRunsActionWithoutEvents() throws Exception {
        Path config = tempDir.resolve("empty-script");
        Path actions = config.resolve("definitions/actions");
        Files.createDirectories(actions);
        Files.writeString(actions.resolve("sample.yml"), """
                placement: false
                confinement:
                  boundary: NONE
                  duration: 30s
                lifecycle:
                  onStart:
                    - CONSOLE: "say ready"
                  onBoundaryViolation:
                    - CONSOLE: "say violated"
                """);
        List<String> output = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        int exitCode = RtpBitCli.run(new String[]{"--config", config.toString(), "--action", "sample", "--script", ""},
                output::add, errors::add);

        assertEquals(0, exitCode);
        assertTrue(errors.isEmpty());
        assertTrue(output.stream().anyMatch(line -> line.contains("say ready")));
        assertTrue(output.stream().noneMatch(line -> line.contains("say violated")));
    }

    private static void writeUnknownAction(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "lifecycle:\n  onStart:\n    - CONSOL: say hello\n");
    }
}
