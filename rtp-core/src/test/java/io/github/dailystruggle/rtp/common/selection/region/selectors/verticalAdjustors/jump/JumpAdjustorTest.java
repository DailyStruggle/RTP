package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.ConfigurableMockChunk;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the core logic paths of {@link JumpAdjustor}.
 *
 * <p>The {@link JumpAdjustor} finds a valid landing Y via three phases:
 * <ol>
 *   <li><b>Phase 1</b> – scans bottom-up to find the first non-air unsafe block and
 *       advances {@code minY} to it.</li>
 *   <li><b>Phase 2</b> – binary-search-like jumps (only active when {@code step > 2}),
 *       halving {@code step} each iteration to narrow the search window.</li>
 *   <li><b>Phase 3</b> – linear scan from {@code minY} to {@code maxY} looking for the
 *       pattern: solid floor at {@code i-1}, two clear air blocks at {@code i} and
 *       {@code i+1}, all three positions safe.</li>
 * </ol>
 *
 * <p>Each test uses a {@link ConfigurableMockChunk} with blocks placed at specific
 * Y-coordinates:
 * <ul>
 *   <li>{@link ConfigurableMockChunk#setSolid(int)} – non-air <em>and</em> unsafe
 *       (e.g. lava, fire). Phase 1 advances {@code minY} past these.</li>
 *   <li>{@link ConfigurableMockChunk#setSolidSafe(int)} – non-air <em>but</em> safe
 *       (e.g. stone, dirt). Used for floor blocks that Phase 3 can stand on.</li>
 * </ul>
 */
public class JumpAdjustorTest {

    @TempDir
    Path tempDir;

    private MockRTPWorld world;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        world = new MockRTPWorld("test_world");
    }

    /**
     * Build a fresh {@link JumpAdjustor} with the given parameters.
     * {@code requireSkyLight} is always {@code false}.
     */
    private JumpAdjustor buildAdjustor(int minY, int maxY, int step) {
        JumpAdjustor adj = new JumpAdjustor(new ArrayList<>());
        adj.set(JumpAdjustorKeys.minY, (long) minY);
        adj.set(JumpAdjustorKeys.maxY, (long) maxY);
        adj.set(JumpAdjustorKeys.step, (long) step);
        adj.set(JumpAdjustorKeys.requireSkyLight, false);
        return adj;
    }

    // -----------------------------------------------------------------------
    // Direct Hit
    // -----------------------------------------------------------------------

    /**
     * Direct Hit: solid-safe floor at Y=64, two air blocks above (Y=65, 66).
     *
     * <p>Phase 1 finds no unsafe blocks, so {@code minY} stays at 60.
     * Phase 2 is skipped (step clamped to 1 for a small range).
     * Phase 3 linear scan: {@code i=65} → {@code !isAir(64)} ✓, {@code isAir(65,66)} ✓,
     * {@code isSafe(64,65,66)} ✓ → <b>Y=65</b>.
     */
    @Test
    void directHit_solidFloorAtY64_returnsY65() {
        // range 60–80, solid-safe floor only at Y=64; Y=65 and Y=66 are air
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(64);

        JumpAdjustor adj = buildAdjustor(60, 80, 1);
        RTPCoords result = adj.adjust(chunk);

        assertNotNull(result, "JumpAdjustor should find a valid landing above the solid floor");
        assertEquals(65, result.y(), "Landing should be Y=65 (one above the solid-safe floor at Y=64)");
    }

    // -----------------------------------------------------------------------
    // Step Expansion
    // -----------------------------------------------------------------------

    /**
     * Step Expansion: unsafe solid band from Y=60 to Y=83, with a safe floor at Y=84
     * and an open air gap at Y=85–86.
     *
     * <p>Range 60–100, step=1 (Phase 2 skipped).
     * Phase 1 advances {@code minY} to the first unsafe block (Y=60).
     * Phase 3 linear scan skips all solid positions and finds {@code i=85}:
     * {@code !isAir(84)} ✓, {@code isAir(85,86)} ✓, {@code isSafe(84,85,86)} ✓ → <b>Y=85</b>.
     */
    @Test
    void stepExpansion_solidBaseline_jumpsToGapOneStepAway() {
        // unsafe solid from 60 to 83; solid-safe floor at 84; Y=85 and Y=86 are air
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 83; y++) chunk.setSolid(y);
        chunk.setSolidSafe(84);

        JumpAdjustor adj = buildAdjustor(60, 100, 1);
        RTPCoords result = adj.adjust(chunk);

        assertNotNull(result, "JumpAdjustor should scan past the solid baseline and find the gap");
        assertEquals(85, result.y(), "Landing should be Y=85 (first air above solid-safe floor at Y=84)");
    }

    // -----------------------------------------------------------------------
    // Ceiling Clearance
    // -----------------------------------------------------------------------

    /**
     * Ceiling Clearance: a 1-block-high tunnel at Y=65 (solid-safe floor at Y=64,
     * solid-safe ceiling at Y=66). The adjustor must reject Y=65 because
     * {@code isAir(i+1)=isAir(66)} is {@code false}, and instead find the next open
     * space above the ceiling.
     *
     * <p>Layout: solid-safe at Y=64 (floor) and Y=66 (ceiling), air only at Y=65.
     * Next valid gap: floor at Y=66 (solid-safe), air at Y=67 and Y=68 → <b>Y=67</b>.
     */
    @Test
    void ceilingClearance_oneBlockHighTunnel_skipsAndFindsOpenSpace() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(64); // floor of tunnel
        chunk.setSolidSafe(66); // ceiling of tunnel (only 1 air block at Y=65 — not enough headroom)

        JumpAdjustor adj = buildAdjustor(60, 80, 1);
        RTPCoords result = adj.adjust(chunk);

        assertNotNull(result, "JumpAdjustor should skip the 1-block tunnel and find open space above");
        assertEquals(67, result.y(),
                "Landing should be Y=67 (two clear blocks above the ceiling at Y=66)");
    }

    // -----------------------------------------------------------------------
    // No Valid Landing
    // -----------------------------------------------------------------------

    /**
     * No Valid Landing: the entire range is filled with unsafe solid blocks.
     *
     * <p>Phase 1 advances {@code minY} to Y=60 (first unsafe block).
     * Phase 3 finds no position where {@code isAir(i) && isAir(i+1)} → returns {@code null}.
     */
    @Test
    void noValidLanding_entireRangeSolid_returnsNull() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 80; y++) chunk.setSolid(y);

        JumpAdjustor adj = buildAdjustor(60, 80, 1);
        RTPCoords result = adj.adjust(chunk);

        assertNull(result, "JumpAdjustor should return null when no valid landing exists");
    }

    // -----------------------------------------------------------------------
    // All Air (no floor)
    // -----------------------------------------------------------------------

    /**
     * All Air: the entire range is air — there is no solid floor block, so Phase 3
     * never satisfies {@code !isAir(i-1)} and returns {@code null}.
     */
    @Test
    void allAir_noFloor_returnsNull() {
        // chunk has no solid blocks at all — every block is air
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);

        JumpAdjustor adj = buildAdjustor(60, 80, 1);
        RTPCoords result = adj.adjust(chunk);

        assertNull(result, "JumpAdjustor should return null when there is no solid floor");
    }
}
