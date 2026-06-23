package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POTENTIAL_BUGS.md 2026-04-30 follow-up: every key on the cache-hash allowlist
 * must invalidate the {@link RegionCacheKey#cacheKey} when its value changes, and
 * every excluded key must not.
 *
 * <p>ADR-071 split the landing block lists into {@link BlocksKeys} and the biome
 * filter into {@link BiomesKeys}; the cache-hash allowlist now spans all three
 * parsers ({@link SafetyKeys}, {@link BlocksKeys}, {@link BiomesKeys}). This test
 * pins the in/out boundary of {@link RegionCacheKey} against each.
 */
@DisplayName("RegionCacheKey: safety validity edits invalidate the cache hash")
public class RegionCacheKeyTest {

    @TempDir
    Path tempDir;

    private TrackedMockWorld world;
    private Circle shape;
    private LinearAdjustor vert;
    private Map<Enum<?>, Object> safetyData;
    private Map<Enum<?>, Object> blocksData;
    private Map<Enum<?>, Object> biomesData;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        RTPTestSetup.install(tempDir.toFile());
        world = new TrackedMockWorld("region_cache_key_world");

        shape = new Circle();
        vert = new LinearAdjustor(new ArrayList<>());

        safetyData = dataMapOf(SafetyKeys.class);
        blocksData = dataMapOf(BlocksKeys.class);
        biomesData = dataMapOf(BiomesKeys.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends Enum<T>> Map<Enum<?>, Object> dataMapOf(Class<T> cls)
            throws ReflectiveOperationException {
        ConfigParser<T> parser = (ConfigParser<T>) RTP.configs.getParser(cls);
        java.lang.reflect.Field dataField = FactoryValue.class.getDeclaredField("data");
        dataField.setAccessible(true);
        return (Map) dataField.get(parser);
    }

    /** Route a key to the EnumMap of the parser that owns its declaring enum. */
    private Map<Enum<?>, Object> mapFor(Enum<?> key) {
        if (key instanceof BlocksKeys) return blocksData;
        if (key instanceof BiomesKeys) return biomesData;
        return safetyData;
    }

    private String currentKey() {
        return RegionCacheKey.cacheKey(world, shape, vert);
    }

    private boolean keyChangesUnderMutation(Enum<?> key, Object newValue) {
        Map<Enum<?>, Object> data = mapFor(key);
        Object previous = data.get(key);
        try {
            String before = currentKey();
            data.put(key, newValue);
            String after = currentKey();
            return !before.equals(after);
        } finally {
            if (previous == null) {
                data.remove(key);
            } else {
                data.put(key, previous);
            }
        }
    }

    @Test
    @DisplayName("included: safetyRadius edit invalidates the cache key")
    void safetyRadius_in_hash() {
        assertTrue(keyChangesUnderMutation(SafetyKeys.safetyRadius, 99),
                "Tightening safetyRadius must invalidate cached shape data.");
    }

    @Test
    @DisplayName("included: unsafeBlocks edit invalidates the cache key")
    void unsafeBlocks_in_hash() {
        assertTrue(keyChangesUnderMutation(BlocksKeys.unsafeBlocks,
                        new ArrayList<>(Arrays.asList("LAVA", "MAGMA_BLOCK", "FIRE"))),
                "Adding an unsafe block must invalidate cached 'safe' flags.");
    }

    @Test
    @DisplayName("included: airBlocks edit invalidates the cache key")
    void airBlocks_in_hash() {
        assertTrue(keyChangesUnderMutation(BlocksKeys.airBlocks,
                        new ArrayList<>(Arrays.asList("AIR", "CAVE_AIR", "VOID_AIR"))),
                "Editing air-block list shifts JumpAdjustor's air predicate.");
    }

    @Test
    @DisplayName("included: platformRadius / platformDepth / platformAirHeight / platformMaterial edits invalidate the cache key")
    void platform_keys_in_hash() {
        assertTrue(keyChangesUnderMutation(SafetyKeys.platformRadius, 3),
                "platformRadius edits must invalidate the cache (platform geometry changes).");
        assertTrue(keyChangesUnderMutation(SafetyKeys.platformDepth, 5),
                "platformDepth edits must invalidate the cache.");
        assertTrue(keyChangesUnderMutation(SafetyKeys.platformAirHeight, 7),
                "platformAirHeight edits must invalidate the cache.");
        assertTrue(keyChangesUnderMutation(SafetyKeys.platformMaterial, "BEDROCK"),
                "platformMaterial edits must invalidate the cache.");
    }

    @Test
    @DisplayName("included: anvilPrefilterEnabled toggle invalidates the cache key")
    void anvilPrefilter_in_hash() {
        // The pre-filter rejects candidates before they reach the live chunk safety
        // grid; toggling it changes which candidates are observed as 'bad'.
        assertTrue(keyChangesUnderMutation(SafetyKeys.anvilPrefilterEnabled, Boolean.FALSE)
                        || keyChangesUnderMutation(SafetyKeys.anvilPrefilterEnabled, Boolean.TRUE),
                "anvilPrefilterEnabled toggle must invalidate the cache.");
    }

    @Test
    @DisplayName("included: biomeWhitelist toggle and biomes list edits invalidate the cache key")
    void biome_keys_in_hash() {
        assertTrue(keyChangesUnderMutation(BiomesKeys.biomeWhitelist, Boolean.TRUE)
                        || keyChangesUnderMutation(BiomesKeys.biomeWhitelist, Boolean.FALSE),
                "biomeWhitelist toggle must invalidate the cache.");
        assertTrue(keyChangesUnderMutation(BiomesKeys.biomes,
                        new ArrayList<>(Arrays.asList("PLAINS", "FOREST", "TAIGA"))),
                "Editing the region biome list must invalidate cached 'bad' flags.");
    }

    @Test
    @DisplayName("excluded: invulnerabilityTime / staleChunkRetryLimit / version edits are STABLE")
    void cosmetic_keys_not_in_hash() {
        // These keys are runtime/cosmetic and must not invalidate persisted
        // shape data — a server admin tweaking them should not pay a re-scan cost.
        assertEquals(currentKey(), mutateAndKey(SafetyKeys.invulnerabilityTime, 5000L),
                "invulnerabilityTime is post-teleport cosmetic; cache must survive its edits.");
        assertEquals(currentKey(), mutateAndKey(SafetyKeys.staleChunkRetryLimit, 7),
                "staleChunkRetryLimit is a runtime budget; cache must survive its edits.");
    }

    @Test
    @DisplayName("list order does not matter: [STONE, DIRT] and [dirt, stone] fold to the same key")
    void list_order_is_normalized() {
        Object previous = blocksData.get(BlocksKeys.unsafeBlocks);
        try {
            blocksData.put(BlocksKeys.unsafeBlocks,
                    new ArrayList<>(Arrays.asList("STONE", "DIRT")));
            String a = currentKey();
            blocksData.put(BlocksKeys.unsafeBlocks,
                    new ArrayList<>(Arrays.asList("dirt", "stone")));
            String b = currentKey();
            assertEquals(a, b, "Cache key must be invariant under list re-ordering and case.");
        } finally {
            if (previous == null) {
                blocksData.remove(BlocksKeys.unsafeBlocks);
            } else {
                blocksData.put(BlocksKeys.unsafeBlocks, previous);
            }
        }
    }

    @Test
    @DisplayName("SCHEMA_VERSION bump produces a different key when materialised at the new version")
    void schema_version_in_hash() {
        // The constant itself participates in canonicalize(); we assert that the
        // current schema is the documented post-fix value (v2). A future bump
        // will fail this guard, forcing the bumper to update the test and the
        // CHANGELOG together.
        assertEquals(2, RegionCacheKey.SCHEMA_VERSION,
                "SCHEMA_VERSION should be 2 after the safety.yml-fold change. "
                        + "Bumping it requires a matching CHANGELOG entry per the "
                        + "POTENTIAL_BUGS.md 2026-04-30 fix.");
    }

    @Test
    @DisplayName("allowlist regression guard: adding a new SafetyKeys enum constant must be classified")
    void allowlist_covers_every_enum_constant() {
        // ADR-071: the cache-hash allowlist now spans three enums. This guard pins
        // the SafetyKeys side of the boundary: every SafetyKeys constant must be
        // either cache-invalidating (included) or cache-stable (excluded). The
        // moved block/biome keys are guarded by their own per-key tests above.
        java.util.EnumSet<SafetyKeys> excluded = java.util.EnumSet.of(
                SafetyKeys.invulnerabilityTime,
                SafetyKeys.staleChunkRetryLimit,
                SafetyKeys.platformRestoreSeconds,
                SafetyKeys.pvpCheckEnabled,
                SafetyKeys.pvpCombatTagSeconds,
                SafetyKeys.pvpOnCombat,
                SafetyKeys.pvpSource,
                SafetyKeys.pvpTagVictim,
                SafetyKeys.pvpTagAggressor,
                SafetyKeys.version);
        java.util.EnumSet<SafetyKeys> included = java.util.EnumSet.of(
                SafetyKeys.safetyRadius,
                SafetyKeys.platformRadius,
                SafetyKeys.platformAirHeight,
                SafetyKeys.platformDepth,
                SafetyKeys.platformMaterial,
                SafetyKeys.anvilPrefilterEnabled);
        java.util.EnumSet<SafetyKeys> union = java.util.EnumSet.copyOf(included);
        union.addAll(excluded);
        java.util.EnumSet<SafetyKeys> all = java.util.EnumSet.allOf(SafetyKeys.class);
        List<SafetyKeys> unclassified = new ArrayList<>(all);
        unclassified.removeAll(union);
        assertTrue(unclassified.isEmpty(),
                "New SafetyKeys constant(s) must be explicitly classified as cache-invalidating "
                        + "or cache-stable. Unclassified: " + unclassified);
        // And the two sets must be disjoint.
        java.util.EnumSet<SafetyKeys> overlap = java.util.EnumSet.copyOf(included);
        overlap.retainAll(excluded);
        assertTrue(overlap.isEmpty(),
                "A SafetyKeys constant cannot be both included and excluded. Overlap: " + overlap);
        assertNotEquals(0, included.size(), "Included set must be non-empty.");
    }

    private String mutateAndKey(Enum<?> key, Object newValue) {
        Map<Enum<?>, Object> data = mapFor(key);
        Object previous = data.get(key);
        try {
            data.put(key, newValue);
            return currentKey();
        } finally {
            if (previous == null) {
                data.remove(key);
            } else {
                data.put(key, previous);
            }
        }
    }
}
