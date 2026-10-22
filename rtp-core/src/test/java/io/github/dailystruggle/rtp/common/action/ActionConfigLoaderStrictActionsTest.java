package io.github.dailystruggle.rtp.common.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("REQ-API-F-009 strict action registration (ADR-121)")
class ActionConfigLoaderStrictActionsTest {
    @TempDir
    Path pluginDirectory;

    @AfterEach
    void resetPolicy() {
        BitSeverityPolicy.resetDefaults();
    }

    @Test
    void strictActionsRejectsOnlyDefinitionsWithErrors() throws IOException {
        writeDefinitions();
        BitSeverityPolicy.load(true, java.util.Map.of());
        ActionManager manager = new ActionManager();

        ActionConfigLoader.loadActions(pluginDirectory.toFile(), manager);

        assertTrue(manager.getAction("bad").isEmpty());
        assertTrue(manager.getAction("good").isPresent());
        assertEquals(List.of("bad", "good"), ActionConfigLoader.loadedDefinitions().stream()
                .map(ActionConfigLoader.LoadedDefinition::id).sorted().toList());
    }

    @Test
    void defaultPolicyStillLoadsDefinitionsWithWarnings() throws IOException {
        writeDefinitions();
        BitSeverityPolicy.resetDefaults();
        ActionManager manager = new ActionManager();

        ActionConfigLoader.loadActions(pluginDirectory.toFile(), manager);

        assertTrue(manager.getAction("bad").isPresent());
        assertFalse(manager.getAction("good").isEmpty());
    }

    @Test
    void unknownActionNameIsDroppedFromParsedLifecycle() {
        var definition = ActionConfigLoader.parseDefinition("bad-action", RtpYamlConfig.parse("""
                lifecycle:
                  onStart:
                    - ACTION: NOT_A_RUNTIME_ACTION
                """));

        assertTrue(definition.lifecycle().onStart().get(0).actions().isEmpty());
    }

    @Test
    void unknownActionNameIsDroppedFromCommandSubcommand() {
        var definition = ActionConfigLoader.parseDefinition("bad-command", RtpYamlConfig.parse("""
                command:
                  name: bad-command
                  subcommands:
                    cancel:
                      run:
                        - ACTION: NOT_A_RUNTIME_ACTION
                """));

        assertTrue(definition.command().subcommands().get("cancel").actions().isEmpty());
    }

    private void writeDefinitions() throws IOException {
        Path actions = Files.createDirectories(pluginDirectory.resolve("definitions/actions"));
        Files.writeString(actions.resolve("bad.yml"), """
                lifecycle:
                  onStart:
                    - CONSOLE: say valid
                    - CONSOL: say invalid
                """);
        Files.writeString(actions.resolve("good.yml"), "alias: good\n");
    }
}
