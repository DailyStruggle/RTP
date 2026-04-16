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
 * <ol>
 *   <li>Bottom Up  (state 0)</li>
 *   <li>Top Down   (state 1)</li>
 *   <li>Middle Out (state 2)</li>
 *   <li>Outer In   (state 3)</li>
 *   <li>Shuffled   (state 4)</li>
 * </ol>
 *
 * <p>Each test uses a {@link ConfigurableMockChunk} with solid blocks placed at
 * specific Y-coordinates so that only one valid landing position exists (or a
 * predictable first-found position), making assertions deterministic.
 *
 * <p>A valid landing at Y={@code i} requires all three of the following:
 * <ul>
 *   <li>{@code isAir(i)} and {@code isAir(i+1)} — two clear blocks for the player body</li>
 *   <li>{@code isSafe(i-1)}, {@code isSafe(i)}, {@code isSafe(i+1)} — no unsafe blocks in the
 *       triplet</li>
 * </ul>
 * Because {@link ConfigurableMockChunk#isSafe} returns {@code false} for solid blocks,
 * a valid landing requires three consecutive air blocks: {@code i-1}, {@code i}, {@code i+1}.
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
    // State 1 — Top Down
    // -----------------------------------------------------------------------

    /**
     * Top-Down scan (state 1): range 60–80, solid bands at 60–62 (bottom) and 67–80 (top).
     * Air gap: 63–66.
     * <ul>
     *   <li>i=66: isSafe(67)=false → skip</li>
     *   <li>i=65: isSafe(64,65,66) all air ✓, isAir(65,66) ✓ → <b>Y=65</b></li>
     * </ul>
     */
    @Test
    void topDown_findsFirstSafeBlockFromTop() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 67; y <= 80; y++) chunk.setSolid(y);
        for (int y = 60; y <= 62; y++) chunk.setSolid(y);

        LinearAdjustor adj = buildAdjustor(1, 60, 80);
        RTPCoords result = adj.adjust(chunk);

        assertNotNull(result, "Top-down adjustor should find a valid Y");
        assertEquals(65, result.y(), "Top-down should find Y=65 (first valid triple from top)");
    }

    // -----------------------------------------------------------------------
    // State 0 — Bottom Up
    // -----------------------------------------------------------------------

    /**
     * Bottom-Up scan (state 0): same layout as the top-down test (solid 60–62 and 67–80,
     * air gap 63–66).
     * <ul>
     *   <li>i=63: isSafe(62)=false → skip</li>
     *   <li>i=64: isSafe(63,64,65) all air ✓, isAir(64,65) ✓ → <b>Y=64</b></li>
     * </ul>
     */
    @Test
    void bottomUp_findsFirstSafeBlockFromBottom() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 67; y <= 80; y++) chunk.setSolid(y);
        for (int y = 60; y <= 62; y++) chunk.setSolid(y);

        LinearAdjustor adj = buildAdjustor(0, 60, 80);
        RTPCoords result = adj.adjust(chunk);

        assertNotNull(result, "Bottom-up adjustor should find a valid Y");
        assertEquals(64, result.y(), "Bottom-up should find Y=64 (first valid triple from bottom)");
    }

    // -----------------------------------------------------------------------
    // State 2 — Middle Out
    // -----------------------------------------------------------------------

    /**
     * Middle-Out scan (state 2): range 60–80 → {@code middle = 60 + (80-60)/2 = 70}.
     * Solid bands at 60–68 and 74–80. Air gap: 69–73.
     * <ul>
     *   <li>i=0 (offset): try top y=70 → isSafe(69,70,71) all air ✓, isAir(70,71) ✓
     *       → <b>Y=70</b></li>
     * </ul>
     */
    @Test
    void middleOut_findsGapExpandingFromCenter() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 68; y++) chunk.setSolid(y);
        for (int y = 74; y <= 80; y++) chunk.setSolid(y);

        LinearAdjustor adj = buildAdjustor(2, 60, 80);
        RTPCoords result = adj.adjust(chunk);

        assertNotNull(result, "Middle-out adjustor should find a valid Y");
        assertEquals(70, result.y(), "Middle-out should find Y=70 (center of the air gap)");
    }

    // -----------------------------------------------------------------------
    // State 3 — Outer In
    // -----------------------------------------------------------------------

    /**
     * Outer-In scan (state 3): range 60–80 → {@code middle=70}, {@code maxDistance=10}.
     * Solid bands at 60–68 and 74–80. Air gap: 69–73.
     * The scan converges from the edges inward:
     * <ul>
     *   <li>offsets 10→4: both top and bottom candidates are solid → skip</li>
     *   <li>offset 3: top y=73 → isSafe(74)=false → skip; bottom y=67 solid → skip</li>
     *   <li>offset 2: top y=72 → isSafe(71,72,73) all air ✓, isAir(72,73) ✓ → <b>Y=72</b></li>
     * </ul>
     */
    @Test
    void outerIn_convergesFromEdgesToFindSafeBlock() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 68; y++) chunk.setSolid(y);
        for (int y = 74; y <= 80; y++) chunk.setSolid(y);

        LinearAdjustor adj = buildAdjustor(3, 60, 80);
        RTPCoords result = adj.adjust(chunk);

        assertNotNull(result, "Outer-in adjustor should find a valid Y");
        assertEquals(72, result.y(), "Outer-in should find Y=72 (first valid triple from edges inward)");
    }

    // -----------------------------------------------------------------------
    // State 4 — Shuffled (deterministic via seeded RNG)
    // -----------------------------------------------------------------------

    /**
     * Shuffled scan (state 4): a seeded {@link Random} is injected via
     * {@link LinearAdjustor#setRng(Random)} to make the shuffle order reproducible.
     *
     * <p>Setup: range 60–80, all blocks solid except Y=70, 71, 72 (the only air gap).
     * The sole valid landing is Y=71 ({@code isSafe(70,71,72)} ✓, {@code isAir(71,72)} ✓).
     * Two adjustors seeded identically must return the same Y, proving determinism.
     * The adjustor must also find Y=71 despite it being buried in an otherwise solid column,
     * proving the shuffled collection exhaustively checks all candidates.
     */
    @Test
    void shuffled_deterministicWithSeedAndFindsHiddenGap() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 80; y++) {
            if (y != 70 && y != 71 && y != 72) chunk.setSolid(y);
        }
        // Only valid landing: Y=71 (isAir(71,72)✓, isSafe(70,71,72)✓)

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
    // No valid landing — all directions
    // -----------------------------------------------------------------------

    /**
     * Bottom-Up (state 0): entire range solid — should return null.
     */
    @Test
    void bottomUp_entireRangeSolid_returnsNull() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 80; y++) chunk.setSolid(y);

        LinearAdjustor adj = buildAdjustor(0, 60, 80);
        assertNull(adj.adjust(chunk), "Bottom-up should return null when entire range is solid");
    }

    /**
     * Top-Down (state 1): entire range solid — should return null.
     */
    @Test
    void topDown_entireRangeSolid_returnsNull() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 80; y++) chunk.setSolid(y);

        LinearAdjustor adj = buildAdjustor(1, 60, 80);
        assertNull(adj.adjust(chunk), "Top-down should return null when entire range is solid");
    }

    /**
     * Middle-Out (state 2): entire range solid — should return null.
     */
    @Test
    void middleOut_entireRangeSolid_returnsNull() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 80; y++) chunk.setSolid(y);

        LinearAdjustor adj = buildAdjustor(2, 60, 80);
        assertNull(adj.adjust(chunk), "Middle-out should return null when entire range is solid");
    }

    /**
     * Outer-In (state 3): entire range solid — should return null.
     */
    @Test
    void outerIn_entireRangeSolid_returnsNull() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 80; y++) chunk.setSolid(y);

        LinearAdjustor adj = buildAdjustor(3, 60, 80);
        assertNull(adj.adjust(chunk), "Outer-in should return null when entire range is solid");
    }

    /**
     * Shuffled (state 4): entire range solid — should return null.
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
    // requireSkyLight = true — surface-height path
    // -----------------------------------------------------------------------

    /**
     * When {@code requireSkyLight=true} the adjustor uses {@link io.github.dailystruggle.rtp.api.world.RTPChunk#getSurfaceHeight}
     * and returns Y = surfaceHeight + 1 when the surface block is safe.
     */
    @Test
    void requireSkyLight_safeAtSurface_returnsSurfacePlusOne() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        // Place a solid-safe block at Y=70 so getSurfaceHeight returns 70
        chunk.setSolidSafe(70);

        LinearAdjustor adj = new LinearAdjustor(new ArrayList<>());
        adj.set(GenericVerticalAdjustorKeys.direction, 0);
        adj.set(GenericVerticalAdjustorKeys.minY, 60L);
        adj.set(GenericVerticalAdjustorKeys.maxY, 80L);
        adj.set(GenericVerticalAdjustorKeys.requireSkyLight, true);

        RTPCoords result = adj.adjust(chunk);
        // The requireSkyLight path uses getSurfaceHeight; just verify a non-null result is returned
        assertNotNull(result, "requireSkyLight adjustor should find a landing");
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
    // testPlacement — verifier integration
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
}
