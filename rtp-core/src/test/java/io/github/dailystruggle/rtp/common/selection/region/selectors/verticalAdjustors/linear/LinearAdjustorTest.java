package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.ConfigurableMockChunk;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.GenericVerticalAdjustorKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises all 5 directional states of {@link LinearAdjustor}:
 * Bottom Up (0), Top Down (1), Middle Out (2), Outer In (3), and Shuffled (4).
 */
public class LinearAdjustorTest {

    @TempDir
    Path tempDir;

    private MockRTPWorld world;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        world = new MockRTPWorld("test_world");
    }

    /**
     * Build a fresh {@link LinearAdjustor} configured with the given direction and Y range.
     * {@code requireSkyLight} is always {@code false} so the scan uses the block-based path.
     */
    private LinearAdjustor buildAdjustor(int direction, int minY, int maxY) {
        LinearAdjustor adj = new LinearAdjustor(new ArrayList<>());
        adj.set(GenericVerticalAdjustorKeys.direction, direction);
        adj.set(GenericVerticalAdjustorKeys.minY, (long) minY);
        adj.set(GenericVerticalAdjustorKeys.maxY, (long) maxY);
        adj.set(GenericVerticalAdjustorKeys.requireSkyLight, false);
        return adj;
    }

    // -----------------------------------------------------------------------
    // State 1 - Top Down
    // -----------------------------------------------------------------------

    /**
     * Top-Down scan (state 1): finds the first valid safe landing from the top down.
     */
    @Test
    void topDown_findsFirstSafeBlockFromTop() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 67; y <= 80; y++) chunk.setSolid(y);
        chunk.setSolidSafe(64); // safe floor - player lands at Y=65

        LinearAdjustor adj = buildAdjustor(1, 60, 80);
        RTPCoords result = adj.adjust(chunk);

        assertNotNull(result, "Top-down adjustor should find a valid Y");
        assertEquals(65, result.y(), "Top-down should find Y=65 (first valid triple from top)");
    }

    // -----------------------------------------------------------------------
    // State 0 - Bottom Up
    // -----------------------------------------------------------------------

    /**
     * Bottom-Up scan (state 0): range 60-80, safe floor at 63, unsafe ceiling at 67-80.
     * Air gap: 64-66.
     * <ul>
     *   <li>i=60-63: !isAir(i-1) → i-1 is air → skip</li>
     *   <li>i=64: !isAir(63) → 63 is solidSafe ✓, isAir(64,65) ✓, isSafe(63,64,65) ✓ → <b>Y=64</b></li>
     * </ul>
     */
    @Test
    void bottomUp_findsFirstSafeBlockFromBottom() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 67; y <= 80; y++) chunk.setSolid(y);
        chunk.setSolidSafe(63); // safe floor - player lands at Y=64

        LinearAdjustor adj = buildAdjustor(0, 60, 80);
        RTPCoords result = adj.adjust(chunk);

        assertNotNull(result, "Bottom-up adjustor should find a valid Y");
        assertEquals(64, result.y(), "Bottom-up should find Y=64 (first valid triple from bottom)");
    }

    // -----------------------------------------------------------------------
    // State 2 - Middle Out
    // -----------------------------------------------------------------------

    /**
     * Middle-Out scan (state 2): range 60-80 → {@code middle = 60 + (80-60)/2 = 70}.
     * Safe floor at 69, unsafe ceiling at 74-80. Air gap: 70-73.
     * <ul>
     *   <li>offset i=0: try top y=70 → !isAir(69) ✓, isAir(70,71) ✓, isSafe(69,70,71) ✓
     *       → <b>Y=70</b></li>
     * </ul>
     */
    @Test
    void middleOut_findsGapExpandingFromCenter() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(69); // safe floor - player lands at Y=70
        for (int y = 74; y <= 80; y++) chunk.setSolid(y);

        LinearAdjustor adj = buildAdjustor(2, 60, 80);
        RTPCoords result = adj.adjust(chunk);

        assertNotNull(result, "Middle-out adjustor should find a valid Y");
        assertEquals(70, result.y(), "Middle-out should find Y=70 (just above the safe floor at the center)");
    }

    // -----------------------------------------------------------------------
    // State 3 - Outer In
    // -----------------------------------------------------------------------

    /**
     * Outer-In scan (state 3): converges from outer bounds inward to find a valid landing.
     */
    @Test
    void outerIn_convergesFromEdgesToFindSafeBlock() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(73); // safe floor - player lands at Y=74
        for (int y = 60; y <= 68; y++) chunk.setSolid(y);

        LinearAdjustor adj = buildAdjustor(3, 60, 80);
        RTPCoords result = adj.adjust(chunk);

        assertNotNull(result, "Outer-in adjustor should find a valid Y");
        assertEquals(74, result.y(), "Outer-in should find Y=74 (first valid triple from edges inward)");
    }

    // -----------------------------------------------------------------------
    // State 4 - Shuffled (deterministic via seeded RNG)
    // -----------------------------------------------------------------------

    /**
     * Shuffled scan (state 4): seeded RNG ensures deterministic shuffle order.
     */
    @Test
    void shuffled_deterministicWithSeedAndFindsHiddenGap() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 80; y++) {
            if (y != 70 && y != 71 && y != 72) chunk.setSolid(y);
        }
        chunk.setSolidSafe(70); // safe floor at 70 - only valid landing is Y=71
        // Only valid landing: Y=71 (!isAir(70)✓, isAir(71,72)✓, isSafe(70,71,72)✓)

        long seed = 42L;

        LinearAdjustor adj1 = buildAdjustor(4, 60, 80);
        adj1.setRng(new Random(seed));
        RTPCoords result1 = adj1.adjust(chunk);

        LinearAdjustor adj2 = buildAdjustor(4, 60, 80);
        adj2.setRng(new Random(seed));
        RTPCoords result2 = adj2.adjust(chunk);

        assertNotNull(result1, "Shuffled adjustor should find the hidden gap");
        assertNotNull(result2, "Shuffled adjustor should find the hidden gap (second run)");
        assertEquals(71, result1.y(), "Only valid landing in the column is Y=71");
        assertEquals(result1.y(), result2.y(), "Same seed must produce the same Y result");
    }

    // -----------------------------------------------------------------------
    // No valid landing - all directions
    // -----------------------------------------------------------------------

    /**
     * Bottom-Up (state 0): entire range solid - should return null.
     */
    @Test
    void bottomUp_entireRangeSolid_returnsNull() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 80; y++) chunk.setSolid(y);

        LinearAdjustor adj = buildAdjustor(0, 60, 80);
        assertNull(adj.adjust(chunk), "Bottom-up should return null when entire range is solid");
    }

    /**
     * Top-Down (state 1): entire range solid - should return null.
     */
    @Test
    void topDown_entireRangeSolid_returnsNull() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 80; y++) chunk.setSolid(y);

        LinearAdjustor adj = buildAdjustor(1, 60, 80);
        assertNull(adj.adjust(chunk), "Top-down should return null when entire range is solid");
    }

    /**
     * Middle-Out (state 2): entire range solid - should return null.
     */
    @Test
    void middleOut_entireRangeSolid_returnsNull() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 80; y++) chunk.setSolid(y);

        LinearAdjustor adj = buildAdjustor(2, 60, 80);
        assertNull(adj.adjust(chunk), "Middle-out should return null when entire range is solid");
    }

    /**
     * Outer-In (state 3): entire range solid - should return null.
     */
    @Test
    void outerIn_entireRangeSolid_returnsNull() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 80; y++) chunk.setSolid(y);

        LinearAdjustor adj = buildAdjustor(3, 60, 80);
        assertNull(adj.adjust(chunk), "Outer-in should return null when entire range is solid");
    }

    /**
     * Shuffled (state 4): entire range solid - should return null.
     */
    @Test
    void shuffled_entireRangeSolid_returnsNull() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 80; y++) chunk.setSolid(y);

        LinearAdjustor adj = buildAdjustor(4, 60, 80);
        adj.setRng(new Random(0L));
        assertNull(adj.adjust(chunk), "Shuffled should return null when entire range is solid");
    }

    // -----------------------------------------------------------------------
    // adjustColumn - per-column re-validation (group subspace per-slot path)
    // -----------------------------------------------------------------------

    /**
     * {@code adjustColumn} resolves exactly the requested in-chunk column using the same
     * landing predicate as {@code adjust}, deriving global X/Z from the chunk origin and the
     * requested local column. This is the per-slot resolver the group subspace path relies on
     * (S-001 single "safe column" definition).
     */
    @Test
    void adjustColumn_findsLandingOnRequestedColumn() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(2, 3, world);
        for (int y = 32; y <= 67; y++) chunk.setSolidSafe(y); // ground up to 67, air 68+

        LinearAdjustor adj = buildAdjustor(0, 32, 255);
        RTPCoords result = adj.adjustColumn(chunk, 5, 9);

        assertNotNull(result, "adjustColumn should find the landing on the requested column");
        assertEquals(68, result.y(), "Landing should be Y=68 (one above the solid ground at Y=67)");
        assertEquals((2 << 4) + 5, result.x(), "Global X should be derived from the requested local X");
        assertEquals((3 << 4) + 9, result.z(), "Global Z should be derived from the requested local Z");
    }

    /**
     * {@code adjustColumn} fails closed (returns {@code null}) when the requested column has no
     * safe standing spot, so the group path denies rather than fabricating a Y (S-004).
     */
    @Test
    void adjustColumn_entireRangeUnsafe_returnsNull() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 32; y <= 100; y++) chunk.setSolid(y); // all unsafe

        LinearAdjustor adj = buildAdjustor(0, 32, 100);
        assertNull(adj.adjustColumn(chunk, 7, 7),
                "adjustColumn should return null when the column has no safe landing");
    }

    /**
     * Out-of-range local coordinates are masked into {@code [0..15]}, so a global block X/Z
     * passed verbatim still resolves to the correct in-chunk column.
     */
    @Test
    void adjustColumn_masksLocalCoordsIntoChunk() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 32; y <= 67; y++) chunk.setSolidSafe(y);

        LinearAdjustor adj = buildAdjustor(0, 32, 255);
        RTPCoords result = adj.adjustColumn(chunk, 21, 0); // 21 & 15 == 5

        assertNotNull(result, "adjustColumn should mask local coords and still find a landing");
        assertEquals(5, result.x(), "Local X should be masked (21 & 15 == 5)");
    }

    /**
     * {@code adjustColumn} honors the top-down direction, mirroring {@code adjust(dir=1)}.
     */
    @Test
    void adjustColumn_topDown_findsHighestLanding() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 67; y <= 80; y++) chunk.setSolid(y);
        chunk.setSolidSafe(64); // safe floor - player lands at Y=65

        LinearAdjustor adj = buildAdjustor(1, 60, 80);
        RTPCoords result = adj.adjustColumn(chunk, 3, 3);

        assertNotNull(result, "top-down adjustColumn should find a landing");
        assertEquals(65, result.y(), "top-down adjustColumn should land at Y=65");
    }

    // -----------------------------------------------------------------------
    // requireSkyLight = true - per-Y sky-light gate (V2 behavior, not a fast-path)
    // -----------------------------------------------------------------------

    /**
     * When {@code requireSkyLight=true} the adjustor performs the same per-Y scan as the
     * non-requireSkyLight path; it merely gates the candidate Y on
     * {@link io.github.dailystruggle.rtp.api.world.RTPChunk#getSkyLight} {@code > 7}.
     * No {@code getSurfaceHeight} fast-path is used.
     */
    @Test
    void requireSkyLight_findsLandingViaPerYScan() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(70);

        LinearAdjustor adj = new LinearAdjustor(new ArrayList<>());
        adj.set(GenericVerticalAdjustorKeys.direction, 0);
        adj.set(GenericVerticalAdjustorKeys.minY, 60L);
        adj.set(GenericVerticalAdjustorKeys.maxY, 80L);
        adj.set(GenericVerticalAdjustorKeys.requireSkyLight, true);

        RTPCoords result = adj.adjust(chunk);
        assertNotNull(result, "requireSkyLight adjustor should find a landing via per-Y scan");
    }

    /**
     * Regression: {@code requireSkyLight=true} must reject a roofed cave candidate even
     * when the mock chunk reports a stale full sky-light of 15 at the foothold (the
     * real-world failure mode on freshly-generated / unticked chunks). A safe floor at
     * 64 with air at 65-66 sits under a solid roof at 70-80; the block-data sky-floor
     * binds at 80, so every candidate at {@code y+1 <= 80} fails the gate and the
     * scan returns null instead of placing the player inside the cave.
     */
    @Test
    void requireSkyLight_roofedCave_rejectsDespiteStaleFullSkyLight() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(64); // cave floor (foothold at y=65)
        for (int y = 70; y <= 80; y++) chunk.setSolid(y); // cave roof / overhead terrain

        LinearAdjustor adj = new LinearAdjustor(new ArrayList<>());
        adj.set(GenericVerticalAdjustorKeys.direction, 0);
        adj.set(GenericVerticalAdjustorKeys.minY, 60L);
        adj.set(GenericVerticalAdjustorKeys.maxY, 80L);
        adj.set(GenericVerticalAdjustorKeys.requireSkyLight, true);

        RTPCoords result = adj.adjust(chunk);
        assertNull(result,
                "requireSkyLight must reject a roofed cave even when stored sky-light is a stale 15");
    }

    // -----------------------------------------------------------------------
    // adjust(chunk, output) overload
    // -----------------------------------------------------------------------

    /**
     * The boolean {@code adjust(chunk, output)} overload should return {@code true}
     * and populate the output when a valid landing exists.
     */
    @Test
    void adjustWithOutput_bottomUp_returnsTrueAndSetsY() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(64);

        LinearAdjustor adj = buildAdjustor(0, 60, 80);
        io.github.dailystruggle.rtp.api.world.MutableRTPCoords output =
                new io.github.dailystruggle.rtp.api.world.MutableRTPCoords(world.name(), 0, 0, 0);
        boolean found = adj.adjust(chunk, output);

        assertTrue(found, "adjust(chunk,output) should return true when a valid landing exists");
        assertTrue(output.y >= 60 && output.y <= 80, "Output Y should be within the configured range");
    }

    // -----------------------------------------------------------------------
    // testPlacement - verifier integration
    // -----------------------------------------------------------------------

    @Test
    void testPlacement_passingVerifier_returnsTrue() {
        java.util.List<java.util.function.Predicate<RTPCoords>> verifiers = new java.util.ArrayList<>();
        verifiers.add(coords -> true);
        LinearAdjustor adj = new LinearAdjustor(verifiers);
        adj.set(GenericVerticalAdjustorKeys.direction, 0);
        adj.set(GenericVerticalAdjustorKeys.minY, 60L);
        adj.set(GenericVerticalAdjustorKeys.maxY, 80L);
        adj.set(GenericVerticalAdjustorKeys.requireSkyLight, false);

        RTPCoords coords = new io.github.dailystruggle.rtp.api.world.MutableRTPCoords(world.name(), 0, 65, 0).toImmutable();
        assertTrue(adj.testPlacement(coords));
    }

    @Test
    void testPlacement_failingVerifier_returnsFalse() {
        java.util.List<java.util.function.Predicate<RTPCoords>> verifiers = new java.util.ArrayList<>();
        verifiers.add(coords -> false);
        LinearAdjustor adj = new LinearAdjustor(verifiers);
        adj.set(GenericVerticalAdjustorKeys.direction, 0);
        adj.set(GenericVerticalAdjustorKeys.minY, 60L);
        adj.set(GenericVerticalAdjustorKeys.maxY, 80L);
        adj.set(GenericVerticalAdjustorKeys.requireSkyLight, false);

        RTPCoords coords = new io.github.dailystruggle.rtp.api.world.MutableRTPCoords(world.name(), 0, 65, 0).toImmutable();
        assertFalse(adj.testPlacement(coords));
    }

    // -----------------------------------------------------------------------
    // minY / maxY accessors and keys()
    // -----------------------------------------------------------------------

    @Test
    void minYMaxY_accessors_returnConfiguredValues() {
        LinearAdjustor adj = buildAdjustor(0, 30, 200);
        assertEquals(30, adj.minY());
        assertEquals(200, adj.maxY());
    }

    @Test
    void keys_containsAllEnumNames() {
        LinearAdjustor adj = buildAdjustor(0, 60, 80);
        java.util.List<String> keys = adj.keys();
        for (GenericVerticalAdjustorKeys k : GenericVerticalAdjustorKeys.values()) {
            assertTrue(keys.contains(k.name()), "keys() should contain " + k.name());
        }
    }

    @Test
    void getParameters_returnsNonNull() {
        LinearAdjustor adj = buildAdjustor(0, 60, 80);
        assertNotNull(adj.getParameters());
    }

    // -----------------------------------------------------------------------
    // safetyRadius - live full-load path sweeps [1..safetyRadius] below feet
    // -----------------------------------------------------------------------

    /**
     * Regression guard for the live full-load path: with {@code safetyRadius=2}, a
     * safe crust at {@code y-1} over an unsafe block at {@code y-2} (classic
     * sand-over-water / magma-under-cobblestone) must reject the candidate. Before
     * aligning the live path with the probe-path sweep, only {@code y-1} was checked
     * and the crust alone would pass - players would drop through into the fluid.
     */
    @Test
    void safetyRadius_liveFullLoad_rejectsUnsafeUnderSafeCrust() throws Exception {
        // Configure safetyRadius = 2 on the shared SafetyKeys parser the adjustor reads.
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<
                io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys>
                safety = (io.github.dailystruggle.rtp.common.configuration.ConfigParser<
                        io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys>)
                io.github.dailystruggle.rtp.common.RTP.configs.getParser(
                        io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.class);
        java.lang.reflect.Field dataField =
                io.github.dailystruggle.rtp.common.factory.FactoryValue.class.getDeclaredField("data");
        dataField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.EnumMap<io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys, Object>
                safetyData =
                        (java.util.EnumMap<
                                        io.github.dailystruggle.rtp.common.configuration.enums
                                                .SafetyKeys,
                                        Object>)
                                dataField.get(safety);
        safetyData.put(
                io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.safetyRadius, 2);

        // No static cache to reset - LinearAdjustor reads safety config directly
        // from RTP.configs at the top of each adjust(...) call now.

        try {
        // Column layout: air above 64, safe crust at 63, UNSAFE at 62. Without the
        // [1..safetyRadius] sweep the live path accepts y=64 (only y-1=63 is
        // checked, and 63 is safe). With the sweep it rejects because y-2=62 is
        // unsafe. A higher valid landing at y=72 proves the adjustor is otherwise
        // willing to find a candidate.
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(63); // thin safe crust
        chunk.setSolid(62);     // unsafe block (lava/water analogue) under the crust
        chunk.setSolidSafe(71); // higher safe floor - acceptable landing at y=72

        LinearAdjustor adj = buildAdjustor(0, 60, 80);
        RTPCoords result = adj.adjust(chunk);

        assertNotNull(result, "adjustor should find the higher safe floor at y=72");
        assertEquals(
                72,
                result.y(),
                "safetyRadius=2 must reject y=64 (unsafe at y-2) and pick the higher safe floor");
        } finally {
            // Restore the default safetyRadius so later tests see a clean parser state.
            safetyData.put(
                    io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.safetyRadius,
                    0);
        }
    }
}
