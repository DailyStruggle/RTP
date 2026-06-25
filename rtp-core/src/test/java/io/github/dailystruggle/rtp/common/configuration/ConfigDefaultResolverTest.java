package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ADR-073 - unit coverage for the {@code @<file>} config-default inheritance resolver.
 */
@DisplayName("ADR-073 - ConfigDefaultResolver @<file> reference resolution")
class ConfigDefaultResolverTest {

    private MockedStatic<RTP> rtpMockedStatic;
    private Configs mockConfigs;

    @BeforeEach
    void setUp() {
        rtpMockedStatic = mockStatic(RTP.class);
        mockConfigs = mock(Configs.class);
        RTP.configs = mockConfigs;
    }

    @AfterEach
    void tearDown() {
        rtpMockedStatic.close();
    }

    @Test
    @DisplayName("isReference / referencedFile recognize @<file> tokens, ignore literals")
    void tokenDetection() {
        assertTrue(ConfigDefaultResolver.isReference("@config"));
        assertTrue(ConfigDefaultResolver.isReference("  @economy  "));
        assertFalse(ConfigDefaultResolver.isReference("@"));
        assertFalse(ConfigDefaultResolver.isReference("256"));
        assertFalse(ConfigDefaultResolver.isReference(256));
        assertFalse(ConfigDefaultResolver.isReference(null));
        assertEquals("config", ConfigDefaultResolver.referencedFile("@config"));
        assertEquals("economy", ConfigDefaultResolver.referencedFile("@Economy"));
        assertNull(ConfigDefaultResolver.referencedFile("literal"));
    }

    @Test
    @DisplayName("a literal value is returned unchanged")
    void literalPassthrough() {
        assertEquals(256, ConfigDefaultResolver.resolve(256, "radius", -1));
        assertEquals("CIRCLE", ConfigDefaultResolver.resolve("CIRCLE", "shape", null));
    }

    @Test
    @DisplayName("@config resolves a scalar from config.yml#defaults")
    void resolvesConfigScalar() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("cacheCap", 50);
        defaults.put("requirePermission", false);

        ConfigParser<ConfigKeys> configParser = mock(ConfigParser.class);
        when(configParser.getData(ConfigKeys.defaults)).thenReturn(defaults);
        when(mockConfigs.getParser(ConfigKeys.class)).thenReturn(configParser);

        assertEquals(50, ConfigDefaultResolver.resolve("@config", "cacheCap", 10));
        assertEquals(false, ConfigDefaultResolver.resolve("@config", "requirePermission", true));
    }

    @Test
    @DisplayName("@config resolves a whole named shape block from config.yml#defaults")
    void resolvesConfigBlock() {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("name", "CIRCLE");
        shape.put("radius", 256);
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("shape", shape);

        ConfigParser<ConfigKeys> configParser = mock(ConfigParser.class);
        when(configParser.getData(ConfigKeys.defaults)).thenReturn(defaults);
        when(mockConfigs.getParser(ConfigKeys.class)).thenReturn(configParser);

        Object resolved = ConfigDefaultResolver.resolve("@config", "shape", null);
        assertSame(shape, resolved);
    }

    @Test
    @DisplayName("@economy resolves a scalar from economy.yml")
    void resolvesEconomyScalar() {
        ConfigParser<EconomyKeys> economyParser = mock(ConfigParser.class);
        when(economyParser.getData(EconomyKeys.price)).thenReturn(50.0);
        when(mockConfigs.getParser(EconomyKeys.class)).thenReturn(economyParser);

        assertEquals(50.0, ConfigDefaultResolver.resolve("@economy", "price", 0.0));
    }

    @Test
    @DisplayName("a missing global default falls back rather than throwing")
    void missingDefaultFallsBack() {
        ConfigParser<ConfigKeys> configParser = mock(ConfigParser.class);
        when(configParser.getData(ConfigKeys.defaults)).thenReturn(new LinkedHashMap<>());
        when(mockConfigs.getParser(ConfigKeys.class)).thenReturn(configParser);

        assertEquals(10, ConfigDefaultResolver.resolve("@config", "cacheCap", 10));
    }

    @Test
    @DisplayName("an unknown referenced file falls back rather than throwing")
    void unknownFileFallsBack() {
        assertEquals(7, ConfigDefaultResolver.resolve("@nosuchfile", "cacheCap", 7));
    }
}
