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

    // -----------------------------------------------------------------------
    // adjust(chunk, output) overload — direct boolean form
    // -----------------------------------------------------------------------

    /**
     * Exercises the {@code adjust(chunk, output)} overload directly.
     * A solid-safe floor at Y=64 should produce {@code true} and populate the output.
     */
    @Test
    void adjustWithOutput_solidFloor_returnsTrueAndSetsY() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(64);

        JumpAdjustor adj = buildAdjustor(60, 80, 1);
        io.github.dailystruggle.rtp.api.world.MutableRTPCoords output =
                new io.github.dailystruggle.rtp.api.world.MutableRTPCoords(world.name(), 0, 0, 0);
        boolean found = adj.adjust(chunk, output);

        assertTrue(found, "adjust(chunk,output) should return true when a valid landing exists");
        assertEquals(65, output.y, "Output Y should be 65 (one above solid-safe floor at Y=64)");
    }

    /**
     * Exercises the {@code adjust(chunk, output)} overload when no valid landing exists.
     */
    @Test
    void adjustWithOutput_noValidLanding_returnsFalse() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 80; y++) chunk.setSolid(y);

        JumpAdjustor adj = buildAdjustor(60, 80, 1);
        io.github.dailystruggle.rtp.api.world.MutableRTPCoords output =
                new io.github.dailystruggle.rtp.api.world.MutableRTPCoords(world.name(), 0, 0, 0);
        boolean found = adj.adjust(chunk, output);

        assertFalse(found, "adjust(chunk,output) should return false when no valid landing exists");
    }

    // -----------------------------------------------------------------------
    // Phase 2 — step > 2 (binary-search phase)
    // -----------------------------------------------------------------------

    /**
     * Phase 2 active (step=16): a solid-safe floor at Y=64 with air above.
     * Phase 2 narrows the window; Phase 3 then finds Y=65.
     */
    @Test
    void phase2_largeStep_narrowsWindowAndFindsLanding() {
        // With step=16 the binary-search phase narrows the window; place the
        // solid-safe block at the very bottom so it is always found.
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(60); // solid at 60, air at 61+

        JumpAdjustor adj = buildAdjustor(60, 100, 16);
        RTPCoords result = adj.adjust(chunk);

        assertNotNull(result, "Phase-2 adjustor should find a valid landing");
        assertEquals(61, result.y(), "Landing should be Y=61 above the solid-safe floor at Y=60");
    }

    /**
     * Phase 2 active (step=8): entire range is solid — Phase 2 should exhaust the window
     * and return false (null from the nullable overload).
     */
    @Test
    void phase2_largeStep_entireRangeSolid_returnsNull() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 100; y++) chunk.setSolid(y);

        JumpAdjustor adj = buildAdjustor(60, 100, 8);
        RTPCoords result = adj.adjust(chunk);

        assertNull(result, "Phase-2 adjustor should return null when entire range is solid");
    }

    // -----------------------------------------------------------------------
    // testPlacement — verifier integration
    // -----------------------------------------------------------------------

    /**
     * {@code testPlacement} with an always-passing verifier returns {@code true}.
     */
    @Test
    void testPlacement_passingVerifier_returnsTrue() {
        java.util.List<java.util.function.Predicate<RTPCoords>> verifiers = new java.util.ArrayList<>();
        verifiers.add(coords -> true);
        JumpAdjustor adj = new JumpAdjustor(verifiers);
        adj.set(JumpAdjustorKeys.minY, 60L);
        adj.set(JumpAdjustorKeys.maxY, 80L);
        adj.set(JumpAdjustorKeys.step, 1L);
        adj.set(JumpAdjustorKeys.requireSkyLight, false);

        RTPCoords coords = new io.github.dailystruggle.rtp.api.world.MutableRTPCoords(world.name(), 0, 65, 0).toImmutable();
        assertTrue(adj.testPlacement(coords), "testPlacement should return true when all verifiers pass");
    }

    /**
     * {@code testPlacement} with an always-failing verifier returns {@code false}.
     */
    @Test
    void testPlacement_failingVerifier_returnsFalse() {
        java.util.List<java.util.function.Predicate<RTPCoords>> verifiers = new java.util.ArrayList<>();
        verifiers.add(coords -> false);
        JumpAdjustor adj = new JumpAdjustor(verifiers);
        adj.set(JumpAdjustorKeys.minY, 60L);
        adj.set(JumpAdjustorKeys.maxY, 80L);
        adj.set(JumpAdjustorKeys.step, 1L);
        adj.set(JumpAdjustorKeys.requireSkyLight, false);

        RTPCoords coords = new io.github.dailystruggle.rtp.api.world.MutableRTPCoords(world.name(), 0, 65, 0).toImmutable();
        assertFalse(adj.testPlacement(coords), "testPlacement should return false when a verifier fails");
    }

    // -----------------------------------------------------------------------
    // minY / maxY accessors
    // -----------------------------------------------------------------------

    @Test
    void minYMaxY_accessors_returnConfiguredValues() {
        JumpAdjustor adj = buildAdjustor(30, 200, 1);
        assertEquals(30, adj.minY(), "minY() should return the configured minimum Y");
        assertEquals(200, adj.maxY(), "maxY() should return the configured maximum Y");
    }

    // -----------------------------------------------------------------------
    // keys() and getParameters()
    // -----------------------------------------------------------------------

    @Test
    void keys_returnsAllEnumNames() {
        JumpAdjustor adj = buildAdjustor(60, 80, 1);
        java.util.Collection<String> keys = adj.keys();
        for (JumpAdjustorKeys k : JumpAdjustorKeys.values()) {
            assertTrue(keys.contains(k.name()), "keys() should contain " + k.name());
        }
    }

    @Test
    void getParameters_returnsNonNull() {
        JumpAdjustor adj = buildAdjustor(60, 80, 1);
        assertNotNull(adj.getParameters(), "getParameters() should not return null");
    }

    // -----------------------------------------------------------------------
    // platformDepth — live full-load path sweeps [1..platformDepth] below feet
    // -----------------------------------------------------------------------

    /**
     * Mirrors {@code LinearAdjustorTest.platformDepth_liveFullLoad_rejectsUnsafeUnderSafeCrust}:
     * with {@code platformDepth=2}, a safe crust at {@code y-1} over an unsafe block
     * at {@code y-2} must reject the candidate. Before aligning the live path with
     * the probe-path sweep, only {@code y-1} was checked and the crust alone would
     * pass — players would drop through into the fluid.
     */
    @Test
    void platformDepth_liveFullLoad_rejectsUnsafeUnderSafeCrust() throws Exception {
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
                io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.platformDepth, 2);

        java.lang.reflect.Field lastUpdateField =
                JumpAdjustor.class.getDeclaredField("lastUpdate");
        lastUpdateField.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) lastUpdateField.get(null)).set(0L);

        try {
        // y=63 safe crust, y=62 unsafe (fluid analogue), y=71 higher safe floor.
        // step=1 → linear scan from minY upward; bottom-up path accepts at the
        // first column satisfying the full triplet. Without the [1..platformDepth]
        // sweep, y=64 passes (only y-1=63 checked, safe); with the sweep, y=64 is
        // rejected (y-2=62 unsafe) and the scan proceeds until it finds y=72.
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(63);
        chunk.setSolid(62);
        chunk.setSolidSafe(71);

        JumpAdjustor adj = buildAdjustor(60, 80, 1);
        RTPCoords result = adj.adjust(chunk);

        assertNotNull(result, "adjustor should find the higher safe floor at y=72");
        assertEquals(
                72,
                result.y(),
                "platformDepth=2 must reject y=64 (unsafe at y-2) and pick the higher safe floor");
        } finally {
            // Static fields leak across tests; restore the default so later tests are unaffected.
            safetyData.put(
                    io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.platformDepth,
                    1);
            ((java.util.concurrent.atomic.AtomicLong) lastUpdateField.get(null)).set(0L);
        }
    }
}
