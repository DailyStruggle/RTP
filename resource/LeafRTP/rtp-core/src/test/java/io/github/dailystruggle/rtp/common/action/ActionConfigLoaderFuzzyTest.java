package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.api.action.ParameterType;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ActionConfigLoader - fuzzy parameter type and confinement boundary resolution")
class ActionConfigLoaderFuzzyTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
    }

    @AfterEach
    void tearDown() {
        RTPTestSetup.cleanUp();
    }

    @Test
    @DisplayName("Confinement boundary autocorrects perceptible typos and falls back on imperceptible inputs")
    void testConfinementBoundaryFuzzy() {
        // "regin" -> REGION
        String yaml1 = """
                confinement:
                  boundary: regin
                """;
        var root1 = RtpYamlConfig.parse(yaml1);
        ActionDefinition def1 = ActionConfigLoader.parseDefinition("test1", root1);
        assertEquals(ConfinementBoundary.REGION, def1.confinement().boundary());

        // "subspce" -> SUBSPACE
        String yaml2 = """
                confinement:
                  boundary: subspce
                """;
        var root2 = RtpYamlConfig.parse(yaml2);
        ActionDefinition def2 = ActionConfigLoader.parseDefinition("test2", root2);
        assertEquals(ConfinementBoundary.SUBSPACE, def2.confinement().boundary());

        // "xyz999" -> SUBSPACE fallback
        String yaml3 = """
                confinement:
                  boundary: xyz999
                """;
        var root3 = RtpYamlConfig.parse(yaml3);
        ActionDefinition def3 = ActionConfigLoader.parseDefinition("test3", root3);
        assertEquals(ConfinementBoundary.SUBSPACE, def3.confinement().boundary());
    }

    @Test
    @DisplayName("Parameter type autocorrects perceptible typos and falls back on imperceptible inputs")
    void testParameterTypeFuzzy() {
        // "playr" -> PLAYER
        String yaml1 = """
                command:
                  parameters:
                    - name: target
                      type: playr
                """;
        var root1 = RtpYamlConfig.parse(yaml1);
        ActionDefinition def1 = ActionConfigLoader.parseDefinition("test1", root1);
        assertFalse(def1.command().parameters().isEmpty());
        assertEquals(ParameterType.PLAYER, def1.command().parameters().get(0).type());

        // "numbr" -> NUMBER
        String yaml2 = """
                command:
                  parameters:
                    - name: amount
                      type: numbr
                """;
        var root2 = RtpYamlConfig.parse(yaml2);
        ActionDefinition def2 = ActionConfigLoader.parseDefinition("test2", root2);
        assertFalse(def2.command().parameters().isEmpty());
        assertEquals(ParameterType.NUMBER, def2.command().parameters().get(0).type());

        // "xyz999" -> STRING fallback
        String yaml3 = """
                command:
                  parameters:
                    - name: raw
                      type: xyz999
                """;
        var root3 = RtpYamlConfig.parse(yaml3);
        ActionDefinition def3 = ActionConfigLoader.parseDefinition("test3", root3);
        assertFalse(def3.command().parameters().isEmpty());
        assertEquals(ParameterType.STRING, def3.command().parameters().get(0).type());
    }
}
