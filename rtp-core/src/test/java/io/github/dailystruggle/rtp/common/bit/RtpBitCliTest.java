package io.github.dailystruggle.rtp.common.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.rtp.common.action.ActionConfigLoader;
import io.github.dailystruggle.rtp.common.action.ActionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
