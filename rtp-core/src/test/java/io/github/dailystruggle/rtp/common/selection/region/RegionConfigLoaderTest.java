package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
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

import java.util.Collections;
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

        // Stub server accessor so RegionConfigLoader's world-lookup path does not NPE.
        // An empty worlds list causes the RTP-2 fallback to leave world=null, which is
        // acceptable for these tests (they assert malformed-value fallbacks on other keys).
        RTPServerAccessor mockAccessor = mock(RTPServerAccessor.class);
        when(mockAccessor.getRTPWorlds()).thenReturn(Collections.<RTPWorld<?>>emptyList());
        when(mockAccessor.getRTPWorld(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        java.io.File dummyDir = new java.io.File(System.getProperty("java.io.tmpdir"), "rtp-test-" + System.nanoTime());
        dummyDir.mkdirs();
        when(mockAccessor.getPluginDirectory()).thenReturn(dummyDir);
        RTP.serverAccessor = mockAccessor;
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
            case backlogCacheCap -> settings.backlogCacheCap();
            case networkReserveSize -> settings.networkReserveSize();
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
                // Tolerant int -> boolean coercion: 1 -> true, 0 -> false.
                Arguments.of(RegionKeys.worldBorderOverride, 1, true, "Integer 1 coerced to true for worldBorderOverride"),
                Arguments.of(RegionKeys.worldBorderOverride, 0, false, "Integer 0 coerced to false for worldBorderOverride"),
                // Tolerant boolean -> int coercion: true -> 1, false -> 0.
                Arguments.of(RegionKeys.cacheCap, true, 1L, "Boolean true coerced to 1 for cacheCap"),
                Arguments.of(RegionKeys.activeChunkCap, true, 1, "Boolean true coerced to 1 for activeChunkCap"),
                Arguments.of(RegionKeys.shape, null, null, "null for shape"),
                Arguments.of(RegionKeys.backlogCacheCap, "malformed_cap", 0L, "String instead of Number for backlogCacheCap"),
                Arguments.of(RegionKeys.networkReserveSize, "bad_num", 0L, "String instead of Number for networkReserveSize"),
                Arguments.of(RegionKeys.spatialResolution, "not_a_number", 0L, "String instead of Number for spatialResolution"),
                Arguments.of(RegionKeys.requirePermission, "maybe", false, "Invalid boolean string for requirePermission"),
                Arguments.of(RegionKeys.requirePermission, 1, true, "Integer 1 for requirePermission"),
                Arguments.of(RegionKeys.requirePermission, 0, false, "Integer 0 for requirePermission")
        );
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("detectFallbackConfiguredWorld returns expected world name or null")
    void testDetectFallbackConfiguredWorld() {
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);

        // Case 1: settings is null
        org.junit.jupiter.api.Assertions.assertNull(RegionConfigLoader.detectFallbackConfiguredWorld(parser, null));

        // Case 2: raw world config is null
        RegionSettings mockSettings = mock(RegionSettings.class);
        doReturn(null).when(parser).getConfigValue(eq(RegionKeys.world), any());
        org.junit.jupiter.api.Assertions.assertNull(RegionConfigLoader.detectFallbackConfiguredWorld(parser, mockSettings));

        // Case 3: bracketed world index "[0]"
        doReturn("[0]").when(parser).getConfigValue(eq(RegionKeys.world), any());
        org.junit.jupiter.api.Assertions.assertNull(RegionConfigLoader.detectFallbackConfiguredWorld(parser, mockSettings));

        // Case 4: dormant region (settings.world() is null) -> returns raw world name
        doReturn("custom_world").when(parser).getConfigValue(eq(RegionKeys.world), any());
        doReturn(null).when(mockSettings).world();
        assertEquals("custom_world", RegionConfigLoader.detectFallbackConfiguredWorld(parser, mockSettings));

        // Case 5: settings.world() matches configured name -> returns null
        RTPWorld<?> mockWorld = mock(RTPWorld.class);
        doReturn("custom_world").when(mockWorld).name();
        doReturn(mockWorld).when(mockSettings).world();
        org.junit.jupiter.api.Assertions.assertNull(RegionConfigLoader.detectFallbackConfiguredWorld(parser, mockSettings));

        // Case 6: settings.world() does NOT match configured name -> returns raw name
        doReturn("other_world").when(mockWorld).name();
        assertEquals("custom_world", RegionConfigLoader.detectFallbackConfiguredWorld(parser, mockSettings));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("applyPolygonVertices handles all vertex formats and logs warnings on invalid")
    void testApplyPolygonVertices() {
        io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Polygon polygon =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Polygon();

        // 1. Non-polygon shape does nothing
        io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square square =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square();
        RegionConfigLoader.applyPolygonVertices(square, java.util.Map.of("vertices", java.util.List.of()));

        // 2. Vertex parsing with int[] pairs
        java.util.Map<String, Object> mapWithIntArrays = new java.util.HashMap<>();
        mapWithIntArrays.put("vertices", java.util.List.of(new int[]{0, 0}, new int[]{100, 0}, new int[]{100, 100}, new int[]{0, 100}));
        RegionConfigLoader.applyPolygonVertices(polygon, mapWithIntArrays);

        // 3. Vertex parsing with [x, z] coordinate maps
        java.util.Map<String, Object> mapWithMaps = new java.util.HashMap<>();
        mapWithMaps.put("vertices", java.util.List.of(
                java.util.Map.of("x", 10, "z", 20),
                java.util.Map.of("X", 30, "Z", 40),
                java.util.Map.of("x", 50, "z", 60)
        ));
        RegionConfigLoader.applyPolygonVertices(polygon, mapWithMaps);

        // 4. Vertex parsing with String bracket and parenthesis formats "[x,z]" / "(x,z)" / "x,z"
        java.util.Map<String, Object> mapWithStrings = new java.util.HashMap<>();
        mapWithStrings.put("vertices", java.util.List.of("[0,0]", "(50,10)", "100 100", "0,100"));
        RegionConfigLoader.applyPolygonVertices(polygon, mapWithStrings);

        // 5. Vertex map form (0: [x, z])
        java.util.Map<String, Object> mapWithNumberedKeys = new java.util.HashMap<>();
        mapWithNumberedKeys.put("vertices", java.util.Map.of(
                "0", java.util.List.of(0, 0),
                "1", java.util.List.of(10, 0),
                "2", java.util.List.of(10, 10)
        ));
        RegionConfigLoader.applyPolygonVertices(polygon, mapWithNumberedKeys);

        // 6. Invalid non-iterable vertex format
        java.util.Map<String, Object> mapWithInvalid = new java.util.HashMap<>();
        mapWithInvalid.put("vertices", "not_a_list");
        RegionConfigLoader.applyPolygonVertices(polygon, mapWithInvalid);

        // 7. Invalid vertex element (not 2 coordinates)
        java.util.Map<String, Object> mapWithBadElement = new java.util.HashMap<>();
        mapWithBadElement.put("vertices", java.util.List.of("1,2,3"));
        RegionConfigLoader.applyPolygonVertices(polygon, mapWithBadElement);

        // 8. Warning on expand: true for polygon
        java.util.Map<String, Object> mapWithExpand = new java.util.HashMap<>();
        mapWithExpand.put("vertices", java.util.List.of("[0,0]", "[10,0]", "[10,10]"));
        mapWithExpand.put("expand", true);
        RegionConfigLoader.applyPolygonVertices(polygon, mapWithExpand);
        org.junit.jupiter.api.Assertions.assertNotNull(polygon);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("load with bracketed world index resolves world from index")
    void testLoadBracketedWorldIndex() {
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        parser.name = "index_region.yml";
        setupDefaultMocks(parser);

        RTPWorld<?> world0 = mock(RTPWorld.class);
        when(world0.name()).thenReturn("world0");
        RTPWorld<?> world1 = mock(RTPWorld.class);
        when(world1.name()).thenReturn("world1");

        when(RTP.serverAccessor.getRTPWorlds()).thenReturn(java.util.List.of(world0, world1));

        // Valid index [1]
        doReturn("[1]").when(parser).getConfigValue(eq(RegionKeys.world), any());
        RegionSettings settings = RegionConfigLoader.load(parser);
        assertEquals(world1, settings.world());

        // Out of bounds index [99] -> null world
        doReturn("[99]").when(parser).getConfigValue(eq(RegionKeys.world), any());
        RegionSettings settingsOob = RegionConfigLoader.load(parser);
        org.junit.jupiter.api.Assertions.assertNull(settingsOob.world());

        // Invalid number format [abc] -> tries to lookup world named "[abc]" which is null
        doReturn("[abc]").when(parser).getConfigValue(eq(RegionKeys.world), any());
        RegionSettings settingsNfe = RegionConfigLoader.load(parser);
        org.junit.jupiter.api.Assertions.assertNull(settingsNfe.world());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("load with direct RTPWorld instance")
    void testLoadDirectRTPWorld() {
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        parser.name = "direct_world.yml";
        setupDefaultMocks(parser);

        RTPWorld<?> directWorld = mock(RTPWorld.class);
        when(directWorld.name()).thenReturn("directWorld");
        doReturn(directWorld).when(parser).getConfigValue(eq(RegionKeys.world), any());

        RegionSettings settings = RegionConfigLoader.load(parser);
        assertEquals(directWorld, settings.world());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("load with reference tokens resolves via ConfigDefaultResolver")
    void testLoadWithReferenceTokens() {
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        parser.name = "ref_region.yml";
        try {
            java.lang.reflect.Field field = ConfigParser.class.getField("defaultReferences");
            field.setAccessible(true);
            field.set(parser, new java.util.concurrent.ConcurrentHashMap<>());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        setupDefaultMocks(parser);

        // Put reference tokens for shape, vert, price, and cacheCap
        doReturn("@config").when(parser).getData(eq(RegionKeys.shape));
        doReturn("@config").when(parser).getData(eq(RegionKeys.vert));
        doReturn("@config").when(parser).getData(eq(RegionKeys.price));
        doReturn("@config").when(parser).getData(eq(RegionKeys.cacheCap));

        RegionSettings settings = RegionConfigLoader.load(parser);

        // Fallbacks are 10L for cacheCap and 0.0 for price when reference cannot resolve from empty mock
        assertEquals(10L, settings.cacheCap());
        assertEquals(0.0, settings.price());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("load with DataSize strings for caps")
    void testLoadWithDataSizeStrings() {
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        parser.name = "datasize_region.yml";
        setupDefaultMocks(parser);

        // Supply data size strings like "1MB" for cacheCap and networkReserveSize
        doReturn("1MB").when(parser).getConfigValue(eq(RegionKeys.cacheCap), any());
        doReturn("500KB").when(parser).getConfigValue(eq(RegionKeys.networkReserveSize), any());

        RegionSettings settings = RegionConfigLoader.load(parser);
        org.junit.jupiter.api.Assertions.assertTrue(settings.cacheCap() > 0);
        org.junit.jupiter.api.Assertions.assertTrue(settings.networkReserveSize() > 0);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("load with RtpYamlSection for shape and vert")
    void testLoadWithRtpYamlSection() {
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        parser.name = "yaml_section_region.yml";
        setupDefaultMocks(parser);

        // Mock factoryMap
        io.github.dailystruggle.rtp.common.factory.Factory<io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?>> shapeFactory = new io.github.dailystruggle.rtp.common.factory.Factory<>();
        io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square square = new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square();
        shapeFactory.add("SQUARE", square);
        RTP.factoryMap.put(RTP.factoryNames.shape, shapeFactory);

        io.github.dailystruggle.rtp.common.factory.Factory<io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor<?>> vertFactory = new io.github.dailystruggle.rtp.common.factory.Factory<>();
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor linear = new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new java.util.ArrayList<>());
        vertFactory.add("LINEAR", linear);
        RTP.factoryMap.put(RTP.factoryNames.vert, vertFactory);

        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection shapeSection = mock(io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection.class);
        java.util.Map<String, Object> shapeMap = new java.util.HashMap<>();
        shapeMap.put("name", "SQUARE");
        when(shapeSection.getMapValues(false)).thenReturn(shapeMap);
        doReturn(shapeSection).when(parser).getConfigValue(eq(RegionKeys.shape), any());

        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection vertSection = mock(io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection.class);
        java.util.Map<String, Object> vertMap = new java.util.HashMap<>();
        vertMap.put("name", "LINEAR");
        when(vertSection.getMapValues(false)).thenReturn(vertMap);
        doReturn(vertSection).when(parser).getConfigValue(eq(RegionKeys.vert), any());

        RegionSettings settings = RegionConfigLoader.load(parser);
        org.junit.jupiter.api.Assertions.assertNotNull(settings.shape());
        assertEquals("SQUARE", settings.shape().name);
        org.junit.jupiter.api.Assertions.assertNotNull(settings.vert());
        assertEquals("linear", settings.vert().name);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("load with null shape and vert falls back to defaults")
    void testLoadNullShapeAndVertFallbacks() {
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        parser.name = "fallback_region.yml";
        setupDefaultMocks(parser);

        io.github.dailystruggle.rtp.common.factory.Factory<io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?>> shapeFactory = new io.github.dailystruggle.rtp.common.factory.Factory<>();
        shapeFactory.add("CIRCLE", new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle_Normal());
        RTP.factoryMap.put(RTP.factoryNames.shape, shapeFactory);

        io.github.dailystruggle.rtp.common.factory.Factory<io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor<?>> vertFactory = new io.github.dailystruggle.rtp.common.factory.Factory<>();
        vertFactory.add("LINEAR", new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new java.util.ArrayList<>()));
        vertFactory.add("JUMP", new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor(new java.util.ArrayList<>()));
        RTP.factoryMap.put(RTP.factoryNames.vert, vertFactory);

        RegionSettings settings = RegionConfigLoader.load(parser);
        org.junit.jupiter.api.Assertions.assertNotNull(settings.shape());
        org.junit.jupiter.api.Assertions.assertNotNull(settings.vert());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("applyPolygonVertices with Object[] array and RtpYamlSection vertices")
    void testApplyPolygonVerticesAdvanced() {
        io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Polygon polygon =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Polygon();

        // 1. Object[] pairs with floating point numbers
        java.util.Map<String, Object> mapWithObjectArrays = new java.util.HashMap<>();
        mapWithObjectArrays.put("vertices", java.util.List.of(
                new Object[]{10.5, 20.3},
                new Object[]{100.0, 50.0},
                new Object[]{50, 100}
        ));
        RegionConfigLoader.applyPolygonVertices(polygon, mapWithObjectArrays);

        // 2. RtpYamlSection for vertices
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection yamlVertices = mock(io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection.class);
        java.util.Map<String, Object> vertexEntries = new java.util.HashMap<>();
        vertexEntries.put("0", java.util.List.of(0, 0));
        vertexEntries.put("1", java.util.List.of(100, 0));
        vertexEntries.put("2", java.util.List.of(100, 100));
        when(yamlVertices.getMapValues(false)).thenReturn(vertexEntries);

        java.util.Map<String, Object> mapWithYamlVertices = new java.util.HashMap<>();
        mapWithYamlVertices.put("vertices", yamlVertices);
        RegionConfigLoader.applyPolygonVertices(polygon, mapWithYamlVertices);

        // 3. Invalid coordinates (non-numeric string) inside parseCoord
        java.util.Map<String, Object> mapWithNfe = new java.util.HashMap<>();
        mapWithNfe.put("vertices", java.util.List.of("[abc, def]"));
        RegionConfigLoader.applyPolygonVertices(polygon, mapWithNfe);

        // 4. Map with missing X or Z
        java.util.Map<String, Object> mapWithMissingCoord = new java.util.HashMap<>();
        mapWithMissingCoord.put("vertices", java.util.List.of(java.util.Map.of("x", 10)));
        RegionConfigLoader.applyPolygonVertices(polygon, mapWithMissingCoord);
        org.junit.jupiter.api.Assertions.assertNotNull(polygon);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("load with unknown shape or vert recovers to default without throwing")
    void testLoadMalformedShapeAndVertRecovery() {
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        parser.name = "malformed_shape_vert.yml";
        setupDefaultMocks(parser);

        // Factory with defaults available
        io.github.dailystruggle.rtp.common.factory.Factory<io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?>> shapeFactory = new io.github.dailystruggle.rtp.common.factory.Factory<>();
        shapeFactory.add("SQUARE", new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square());
        shapeFactory.add("CIRCLE", new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle_Normal());
        RTP.factoryMap.put(RTP.factoryNames.shape, shapeFactory);

        io.github.dailystruggle.rtp.common.factory.Factory<io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor<?>> vertFactory = new io.github.dailystruggle.rtp.common.factory.Factory<>();
        vertFactory.add("LINEAR", new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new java.util.ArrayList<>()));
        RTP.factoryMap.put(RTP.factoryNames.vert, vertFactory);

        // Unknown shape name and invalid data types in shape map
        java.util.Map<String, Object> malformedShapeMap = new java.util.HashMap<>();
        malformedShapeMap.put("name", "NON_EXISTENT_SHAPE");
        malformedShapeMap.put("radius", "invalid_radius");
        doReturn(malformedShapeMap).when(parser).getConfigValue(eq(RegionKeys.shape), any());

        // Unknown vert name and invalid data types in vert map
        java.util.Map<String, Object> malformedVertMap = new java.util.HashMap<>();
        malformedVertMap.put("name", "NON_EXISTENT_VERT");
        malformedVertMap.put("minY", "invalid_y");
        doReturn(malformedVertMap).when(parser).getConfigValue(eq(RegionKeys.vert), any());

        RegionSettings settings = assertDoesNotThrow(() -> RegionConfigLoader.load(parser));
        org.junit.jupiter.api.Assertions.assertNotNull(settings.shape(), "Shape should fall back to default shape");
        org.junit.jupiter.api.Assertions.assertNotNull(settings.vert(), "Vert should fall back to default adjustor");
    }
}
