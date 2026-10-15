package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPChunk;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cold entries drained from the backlog were screened only for unsafe surface blocks by the
 * region-file pre-filter; promotion to hot runs the rest of the check on the loaded chunk.
 */
@DisplayName("REQ-RTP-S-001 / REQ-RTP-S-003: cold->hot promotion checks biome, safety and claims")
class RegionPromotionChecksTest {

    @TempDir
    Path tempDir;

    private MockRTPWorld world;
    private Region region;
    private final RTPCoords landing = new RTPCoords("promotion_world", 8, 32, 8);

    @BeforeEach
    void setUp() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.setLocationGenerator(new LocationGenerator());
        world = new MockRTPWorld("promotion_world");
        accessor.addWorld(world);
        RegionSettings settings = new RegionSettings(
                "promotion", world, new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false, 10L, 10L, 0L, 5, 0.0, 1L, "", false);
        region = new Region("promotion", settings);
    }

    private MockRTPChunk chunk(String biome, boolean safe) {
        return new MockRTPChunk(0, 0, world) {
            @Override
            public String getBiome(int x, int y, int z) {
                return biome;
            }

            @Override
            public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
                return safe;
            }
        };
    }

    private LocationGenerator.FailTypes check(MockRTPChunk c) throws Exception {
        CompletableFuture<LocationGenerator.FailTypes> f = region.promotionChecks(landing, c);
        assertTrue(f.isDone(), "single-chunk checks with sync verifiers complete inline");
        return f.get();
    }

    @Test
    void passingLocationIsPromoted() throws Exception {
        assertNull(check(chunk("PLAINS", true)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void blacklistedBiomeIsDropped() throws Exception {
        ConfigParser<BiomesKeys> biomes = (ConfigParser<BiomesKeys>) RTP.configs.getParser(BiomesKeys.class);
        biomes.set(BiomesKeys.biomeWhitelist, false);
        biomes.set(BiomesKeys.biomes, List.of("DESERT"));

        assertEquals(LocationGenerator.FailTypes.biome, check(chunk("DESERT", true)));
        assertNull(check(chunk("PLAINS", true)));
    }

    @Test
    void unsafeColumnIsDropped() throws Exception {
        assertEquals(LocationGenerator.FailTypes.safety, check(chunk("PLAINS", false)));
    }

    @Test
    void claimedLocationIsDropped() throws Exception {
        try (AutoCloseable claim = GlobalRegionVerifiers.addGlobalRegionVerifier(c -> false)) {
            assertEquals(LocationGenerator.FailTypes.safetyExternal, check(chunk("PLAINS", true)));
        }
        assertNull(check(chunk("PLAINS", true)));
    }
}
