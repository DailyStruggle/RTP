package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;

public class RegionConfigLoaderTest {
    private MockedStatic<RTP> rtpMockedStatic;
    private Configs mockConfigs;
    private ConfigParser<LoggingKeys> mockLoggingParser;

    @BeforeEach
    void setUp() {
        rtpMockedStatic = mockStatic(RTP.class);
        mockConfigs = mock(Configs.class);
        mockLoggingParser = mock(ConfigParser.class);

        RTP.configs = mockConfigs;
        when(mockConfigs.getParser(LoggingKeys.class)).thenReturn(mockLoggingParser);
        // Default logging value
        doReturn(false).when(mockLoggingParser).getConfigValue(eq(LoggingKeys.detailed_region_init), any());
    }

    @AfterEach
    void tearDown() {
        rtpMockedStatic.close();
    }

    @ParameterizedTest
    @MethodSource("provideMalformedData")
    void testLoadWithMalformedData(RegionKeys key, Object malformedValue, Object expectedFallback, String description) {
        ConfigParser<RegionKeys> mockRegionParser = mock(ConfigParser.class);
        mockRegionParser.name = "testRegion.yml";

        // Setup mock to return malformed value for the specific key, and defaults for others
        setupDefaultMocks(mockRegionParser);
        doReturn(malformedValue).when(mockRegionParser).getConfigValue(eq(key), any());

        RegionSettings settings = assertDoesNotThrow(() -> RegionConfigLoader.load(mockRegionParser),
                "RegionConfigLoader.load should not throw exception for " + description);

        // Verify the fallback value is used
        Object actualValue = getSettingValue(settings, key);
        assertEquals(expectedFallback, actualValue, "Fallback value mismatch for " + description);
    }

    private void setupDefaultMocks(ConfigParser<RegionKeys> parser) {
        doReturn(null).when(parser).getConfigValue(eq(RegionKeys.world), any());
        doReturn(null).when(parser).getConfigValue(eq(RegionKeys.shape), any());
        doReturn(null).when(parser).getConfigValue(eq(RegionKeys.vert), any());
        doReturn(false).when(parser).getConfigValue(eq(RegionKeys.worldBorderOverride), any());
        doReturn(false).when(parser).getConfigValue(eq(RegionKeys.requirePermission), any());
        doReturn(10L).when(parser).getConfigValue(eq(RegionKeys.cacheCap), any());
        doReturn(3).when(parser).getConfigValue(eq(RegionKeys.activeChunkCap), any());
        doReturn(0.0).when(parser).getConfigValue(eq(RegionKeys.price), any());
        doReturn(1L).when(parser).getConfigValue(eq(RegionKeys.spatialResolution), any());
        doReturn("default").when(parser).getConfigValue(eq(RegionKeys.override), any());
    }

    private Object getSettingValue(RegionSettings settings, RegionKeys key) {
        return switch (key) {
            case worldBorderOverride -> settings.worldBorderOverride();
            case cacheCap -> settings.cacheCap();
            case activeChunkCap -> settings.activeChunkCap();
            case price -> settings.price();
            case spatialResolution -> settings.spatialResolution();
            case requirePermission -> settings.requirePermission();
            case override -> settings.override();
            default -> null;
        };
    }

    private static Stream<Arguments> provideMalformedData() {
        return Stream.of(
                Arguments.of(RegionKeys.price, "invalid_price", 0.0, "String instead of Double for price"),
                Arguments.of(RegionKeys.cacheCap, -10, -10L, "Negative number for cacheCap (currently accepted as is)"),
                Arguments.of(RegionKeys.activeChunkCap, "invalid", 0, "String instead of Integer for activeChunkCap"),
                Arguments.of(RegionKeys.worldBorderOverride, null, false, "null for worldBorderOverride"),
                Arguments.of(RegionKeys.shape, null, null, "null for shape")
        );
    }
}
