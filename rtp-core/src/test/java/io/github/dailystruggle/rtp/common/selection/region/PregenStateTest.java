package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("PregenState Tests")
class PregenStateTest {

    @TempDir
    File tempDir;

    private Region region;
    private MockRTPWorld world;

    @BeforeEach
    void setUp() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir);
        world = new MockRTPWorld("pregen_world");
        accessor.addWorld(world);

        RegionSettings settings = new RegionSettings(
                "pregen_region",
                world,
                new Square(),
                new LinearAdjustor(new ArrayList<>()),
                false,
                false,
                16L,
                1000L,
                0L,
                8,
                0.0,
                2L,
                "",
                false);
        region = new Region("pregen_region", settings);
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
    }

    @Test
    @DisplayName("build returns null when region shape is null")
    void build_nullShape_returnsNull() {
        Region mockRegion = mock(Region.class);
        when(mockRegion.getSettings()).thenReturn(region.getSettings());
        when(mockRegion.getShape()).thenReturn(null);
        doReturn(region.getVert()).when(mockRegion).getVert();

        PregenState state = PregenState.build(mockRegion, null);
        assertNull(state);
    }

    @Test
    @DisplayName("build returns null when region vert is null")
    void build_nullVert_returnsNull() {
        Region mockRegion = mock(Region.class);
        when(mockRegion.getSettings()).thenReturn(region.getSettings());
        doReturn(region.getShape()).when(mockRegion).getShape();
        doReturn(null).when(mockRegion).getVert();

        PregenState state = PregenState.build(mockRegion, null);
        assertNull(state);
    }

    @Test
    @DisplayName("build with explicit biomes sets whitelist true and normalizes biome names")
    void build_explicitBiomes() {
        Set<String> inputBiomes = Set.of("plains", "Desert");
        PregenState state = PregenState.build(region, inputBiomes);

        assertNotNull(state);
        assertFalse(state.defaultBiomes);
        assertTrue(state.biomeWhitelist);
        assertTrue(state.biomeNames.contains("PLAINS"));
        assertTrue(state.biomeNames.contains("DESERT"));
        assertEquals(2, state.biomeNames.size());
        assertEquals(2L, state.resolution);
    }

    @Test
    @DisplayName("build with null/empty biomes falls back to default biomes configuration")
    void build_defaultBiomesFallback() {
        ConfigParser<BiomesKeys> biomesParser = (ConfigParser<BiomesKeys>) RTP.configs.getParser(BiomesKeys.class);
        biomesParser.set(BiomesKeys.biomeWhitelist, false);
        biomesParser.set(BiomesKeys.biomes, List.of("NETHER_WASTES", "soul_sand_valley"));

        PregenState state = PregenState.build(region, null);

        assertNotNull(state);
        assertTrue(state.defaultBiomes);
        assertFalse(state.biomeWhitelist);
        assertTrue(state.biomeNames.contains("NETHER_WASTES"));
        assertTrue(state.biomeNames.contains("SOUL_SAND_VALLEY"));
    }

    @Test
    @DisplayName("build configures biome weighting and weights map")
    void build_biomeWeighting() {
        ConfigParser<BiomesKeys> biomesParser =
                (ConfigParser<BiomesKeys>) RTP.configs.getParser(BiomesKeys.class);
        biomesParser.set(BiomesKeys.biomeWeighted, true);
        biomesParser.set(BiomesKeys.biomeWeights, Map.of("PLAINS", 2.5, "DESERT", -1.0));

        PregenState state = PregenState.build(region, Set.of("PLAINS", "DESERT"));

        assertNotNull(state);
        assertTrue(state.biomeWeighted);
        assertNotNull(state.biomeWeights);
        assertEquals(2.5, state.biomeWeights.get("PLAINS"));
        assertNull(state.biomeWeights.get("DESERT")); // Negative weight dropped
    }

    @Test
    @DisplayName("build compiles unsafe blocks and handles invalid tokens via SafetyCompilationCache")
    void build_unsafeBlocksAndTokens() {
        ConfigParser<BlocksKeys> blocksParser =
                (ConfigParser<BlocksKeys>) RTP.configs.getParser(BlocksKeys.class);
        blocksParser.set(BlocksKeys.unsafeBlocks, List.of("LAVA", "WATER", "#invalid_tag:bad!"));

        PregenState state = PregenState.build(region, null);

        assertNotNull(state);
        assertTrue(state.unsafeBlocks.contains("LAVA"));
        assertTrue(state.unsafeBlocks.contains("WATER"));
    }

    @Test
    @DisplayName("build computes attempt bounds and ceiling")
    void build_attemptsCalculation() {
        ConfigParser<PerformanceKeys> perfParser =
                (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        perfParser.set(PerformanceKeys.maxAttempts, 50L);

        PregenState state = PregenState.build(region, null);

        assertNotNull(state);
        assertEquals(50L, state.maxAttemptsBase);
        assertEquals(50L, state.maxAttempts);
        assertEquals(5000L, state.maxAttemptsCeiling); // 50 * 100
        assertNotNull(state.failMap);
    }
}
