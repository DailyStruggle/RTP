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
 * Exercises {@link JumpAdjustor} logic paths: floor advance, binary narrowing,
 * and linear landing scan. Uses {@link ConfigurableMockChunk} fixtures.
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
     * <p>Floor advance finds no unsafe blocks, so {@code minY} stays at 60.
     * Binary narrowing is skipped (step clamped to 1 for a small range).
     * Landing scan: {@code i=65} → {@code !isAir(64)} ✓, {@code isAir(65,66)} ✓,
     * {@code isSafe(64,65,66)} ✓ → <b>Y=65</b>.
     */
    @Test
    void directHit_solidFloorAtY64_returnsY65() {
        // range 60-80, solid-safe floor only at Y=64; Y=65 and Y=66 are air
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
     * Step Expansion: unsafe band Y=60..83, safe floor at Y=84, air at Y=85-86.
     * Landing scan skips solid band and finds Y=85.
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
     * Ceiling Clearance: 1-block tunnel at Y=65 (floor at Y=64, ceiling at Y=66).
     * Adjustor rejects Y=65 (no headroom) and finds Y=67 above ceiling.
     */
    @Test
    void ceilingClearance_oneBlockHighTunnel_skipsAndFindsOpenSpace() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(64); // floor of tunnel
        // ceiling of tunnel (insufficient headroom at Y=65)
        chunk.setSolidSafe(66);

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
     * <p>Floor advance moves {@code minY} to Y=60 (first unsafe block).
     * The landing scan finds no position where {@code isAir(i) && isAir(i+1)} → returns {@code null}.
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
     * All Air: the entire range is air - there is no solid floor block, so the landing
     * scan never satisfies {@code !isAir(i-1)} and returns {@code null}.
     */
    @Test
    void allAir_noFloor_returnsNull() {
        // chunk has no solid blocks at all - every block is air
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);

        JumpAdjustor adj = buildAdjustor(60, 80, 1);
        RTPCoords result = adj.adjust(chunk);

        assertNull(result, "JumpAdjustor should return null when there is no solid floor");
    }

    // -----------------------------------------------------------------------
    // adjust(chunk, output) overload - direct boolean form
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
    // Binary narrowing - step > 2
    // -----------------------------------------------------------------------

    /**
     * Binary narrowing active (step=16): a solid-safe floor at Y=64 with air above.
     * The narrowing pass shrinks the window; the landing scan then finds Y=65.
     */
    @Test
    void largeStep_narrowsWindowAndFindsLanding() {
        // With step=16 the binary-search phase narrows the window; place the
        // solid-safe block at the very bottom so it is always found.
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(60); // solid at 60, air at 61+

        JumpAdjustor adj = buildAdjustor(60, 100, 16);
        RTPCoords result = adj.adjust(chunk);

        assertNotNull(result, "binary-narrowing adjustor should find a valid landing");
        assertEquals(61, result.y(), "Landing should be Y=61 above the solid-safe floor at Y=60");
    }

    /**
     * Binary narrowing active (step=8): entire range is solid - the narrowing pass
     * should exhaust the window and return false (null from the nullable overload).
     */
    @Test
    void largeStep_entireRangeSolid_returnsNull() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 60; y <= 100; y++) chunk.setSolid(y);

        JumpAdjustor adj = buildAdjustor(60, 100, 8);
        RTPCoords result = adj.adjust(chunk);

        assertNull(result, "binary-narrowing adjustor should return null when entire range is solid");
    }

    /**
     * Regression: verify that when binary narrowing converges maxY onto the only
     * valid standing Y, the inclusive upper bound preserves that candidate.
     */
    @Test
    void convergedMaxYIsTheOnlyLanding_isNotDropped() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 32; y <= 67; y++) chunk.setSolidSafe(y); // solid ground up to 67, air 68+

        JumpAdjustor adj = buildAdjustor(32, 255, 16);
        RTPCoords result = adj.adjust(chunk);

        assertNotNull(result,
                "JumpAdjustor must not drop a valid landing when binary narrowing converges maxY onto it");
        assertEquals(68, result.y(), "Landing should be Y=68 (one above the solid ground at Y=67)");
    }

    // -----------------------------------------------------------------------
    // adjustColumn - per-column re-validation (cold->hot promotion path)
    // -----------------------------------------------------------------------

    /**
     * {@code adjustColumn} re-validates exactly the requested column and returns
     * the landing Y derived with the same landing-scan predicate as {@code adjust},
     * with the global X/Z resolved from the requested in-chunk local column.
     * This is the entry point the promotion verify uses to avoid re-sampling
     * the fixed sub-columns (vertical adjust returned null regression).
     */
    @Test
    void adjustColumn_findsLandingOnRequestedColumn() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(2, 3, world);
        for (int y = 32; y <= 67; y++) chunk.setSolidSafe(y); // ground up to 67, air 68+

        JumpAdjustor adj = buildAdjustor(32, 255, 16);
        RTPCoords result = adj.adjustColumn(chunk, 5, 9);

        assertNotNull(result, "adjustColumn should find the landing on the requested column");
        assertEquals(68, result.y(), "Landing should be Y=68 (one above the solid ground at Y=67)");
        assertEquals((2 << 4) + 5, result.x(), "Global X should be derived from the requested local X");
        assertEquals((3 << 4) + 9, result.z(), "Global Z should be derived from the requested local Z");
    }

    /**
     * {@code adjustColumn} returns {@code null} when the requested column has no
     * safe standing spot (entire range solid-unsafe), so the caller falls back
     * to the full {@code adjust} sweep.
     */
    @Test
    void adjustColumn_entireRangeUnsafe_returnsNull() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 32; y <= 100; y++) chunk.setSolid(y); // all unsafe

        JumpAdjustor adj = buildAdjustor(32, 100, 16);
        assertNull(adj.adjustColumn(chunk, 7, 7),
                "adjustColumn should return null when the column has no safe landing");
    }

    /**
     * Out-of-range local coordinates are masked into {@code [0..15]}, so a
     * global block X/Z passed verbatim still resolves to the correct in-chunk
     * column.
     */
    @Test
    void adjustColumn_masksLocalCoordsIntoChunk() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        for (int y = 32; y <= 67; y++) chunk.setSolidSafe(y);

        JumpAdjustor adj = buildAdjustor(32, 255, 16);
        RTPCoords result = adj.adjustColumn(chunk, 21, 0); // 21 & 15 == 5

        assertNotNull(result, "adjustColumn should mask local coords and still find a landing");
        assertEquals(5, result.x(), "Local X should be masked (21 & 15 == 5)");
    }

    // -----------------------------------------------------------------------
    // testPlacement - verifier integration
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
    // safetyRadius - live full-load path sweeps [1..safetyRadius] below feet
    // -----------------------------------------------------------------------

    /**
     * Mirrors {@code LinearAdjustorTest.safetyRadius_liveFullLoad_rejectsUnsafeUnderSafeCrust}:
     * with {@code safetyRadius=2}, a safe crust at {@code y-1} over an unsafe block
     * at {@code y-2} must reject the candidate. Before aligning the live path with
     * the probe-path sweep, only {@code y-1} was checked and the crust alone would
     * pass - players would drop through into the fluid.
     */
    @Test
    void safetyRadius_liveFullLoad_rejectsUnsafeUnderSafeCrust() throws Exception {
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

        // No static cache to reset - JumpAdjustor reads safety config directly
        // from RTP.configs at the top of each adjust(...) call now.

        try {
        // y=63 safe crust, y=62 unsafe (fluid analogue), y=71 higher safe floor.
        // step=1 → linear scan from minY upward; bottom-up path accepts at the
        // first column satisfying the full triplet. Without the [1..safetyRadius]
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
                "safetyRadius=2 must reject y=64 (unsafe at y-2) and pick the higher safe floor");
        } finally {
            // Restore the default safetyRadius so later tests see a clean parser state.
            safetyData.put(
                    io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.safetyRadius,
                    0);
        }
    }

    @Test
    void adjustColumn_findsValidColumnDirectly() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(64);

        JumpAdjustor adj = buildAdjustor(60, 80, 1);
        RTPCoords result = adj.adjustColumn(chunk, 7, 7);

        assertNotNull(result, "adjustColumn should locate safe landing");
        assertEquals(65, result.y());
    }

    @Test
    void nullChunk_throwsNullPointerException() {
        JumpAdjustor adj = buildAdjustor(60, 80, 1);
        assertThrows(NullPointerException.class, () -> adj.adjust(null));
        assertThrows(NullPointerException.class, () -> adj.adjustColumn(null, 0, 0));
    }

    @Test
    void coarseStep_refinement_and_earlyReturn() {
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        // (maxY - minY) / 8 = (200 - 0) / 8 = 25. step = 24.
        JumpAdjustor adj = buildAdjustor(0, 200, 24);

        // Put solid block from 0 to 49
        for (int y = 0; y < 50; y++) chunk.setSolid(y);
        // Put an unsafe block at y=50 so minY moves to 50
        chunk.setSolid(50);
        // Put solid from 51 to 118
        for (int y = 51; y <= 118; y++) chunk.setSolid(y);
        // Put a safe landing at y=120: safe floor at 119, air at 120, 121
        chunk.setSolidSafe(119);
        chunk.setSolidSafe(118); // ground depth check

        RTPCoords result = adj.adjust(chunk);
        assertNotNull(result);
        assertEquals(120, result.y());
    }

    @Test
    void adjustFromProbe_windowReject_and_scanMissReject() {
        JumpAdjustor adj = buildAdjustor(60, 80, 1);

        // Window reject: probe [64, 75] < adjustor [60, 80]
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.FakeChunkColumnProbe narrowProbe =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.FakeChunkColumnProbe(0, 0, 64, 75);
        assertEquals(
                io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor.AdjustResult.WINDOW_REJECT,
                adj.adjustFromProbeWithReason(narrowProbe, "w"));
        assertNull(adj.adjustFromProbe(narrowProbe, "w"));

        // Scan miss reject: all air probe
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.FakeChunkColumnProbe airProbe =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.FakeChunkColumnProbe(0, 0, 50, 100);
        assertEquals(
                io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor.AdjustResult.SCAN_MISS_REJECT,
                adj.adjustFromProbeWithReason(airProbe, "w"));
        assertNull(adj.adjustFromProbe(airProbe, "w"));
    }

    @Test
    void adjustFromProbe_successfulLanding() {
        JumpAdjustor adj = buildAdjustor(60, 80, 1);

        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.FakeChunkColumnProbe probe =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.FakeChunkColumnProbe(0, 0, 0, 128);
        probe.setSolidRange(0, 63);
        probe.setAirRange(64, 128);

        RTPCoords result = adj.adjustFromProbe(probe, "w");
        assertNotNull(result);
        assertEquals(64, result.y());
    }

    // -----------------------------------------------------------------------
    // Boundary conditions: y >= minY vs y > minY, maxY ceiling, minY bedrock,
    // and max-step / binary narrowing limits
    // -----------------------------------------------------------------------

    @Test
    void boundary_exactMinYLanding_and_bedrockFloor() {
        // Floor at 59 (solidSafe), feet at 60 (minY).
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(59);

        JumpAdjustor adj = buildAdjustor(60, 80, 1);
        RTPCoords result = adj.adjust(chunk);
        assertNotNull(result, "Landing exactly at minY=60 should be accepted");
        assertEquals(60, result.y());

        // Probe path at minY
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.FakeChunkColumnProbe probe =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.FakeChunkColumnProbe(0, 0, 50, 100);
        probe.setSolidRange(50, 59);
        probe.setAirRange(60, 100);
        RTPCoords probeRes = adj.adjustFromProbe(probe, "w");
        assertNotNull(probeRes);
        assertEquals(60, probeRes.y());

        // adjustColumn at minY
        RTPCoords colRes = adj.adjustColumn(chunk, 7, 7);
        assertNotNull(colRes);
        assertEquals(60, colRes.y());
    }

    @Test
    void boundary_worldMaxHeight_headroomCap() {
        // World maxHeight = 256.
        // In JumpAdjustor: scanTop = Math.min(maxY, chunk.getWorld().getMaxHeight() - 2)
        // If maxY = 256, scanTop = 254.
        // If floor is at 252, feet are at 253. Headroom is 253, 254 (air, i+1 <= 254).
        ConfigurableMockChunk chunk253 = new ConfigurableMockChunk(0, 0, world);
        chunk253.setSolidSafe(252);
        JumpAdjustor adj = buildAdjustor(60, 256, 1);
        RTPCoords res253 = adj.adjust(chunk253);
        assertNotNull(res253, "Landing at 253 should be accepted");
        assertEquals(253, res253.y());

        // If floor is at 254, feet are at 255. But scanTop is 254, so 255 is beyond scanTop.
        ConfigurableMockChunk chunk255 = new ConfigurableMockChunk(0, 0, world);
        chunk255.setSolidSafe(254);
        RTPCoords res255 = adj.adjust(chunk255);
        assertNull(res255, "Landing at 255 must be rejected due to one-cell headroom cap");
    }

    @Test
    void boundary_stepClamping_and_binaryNarrowingBreak() {
        // step = 0 clamped to 1
        JumpAdjustor adjZeroStep = buildAdjustor(60, 80, 0);
        ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
        chunk.setSolidSafe(64);
        assertNotNull(adjZeroStep.adjust(chunk));

        // step > (maxY - minY) / 8 clamped to (maxY - minY) / 8
        // For range 60..140 (diff 80), max step is 10.
        // Provide step = 50 -> clamped to 10.
        JumpAdjustor adjLargeStep = buildAdjustor(60, 140, 50);
        ConfigurableMockChunk chunkLarge = new ConfigurableMockChunk(0, 0, world);
        chunkLarge.setSolidSafe(60);
        RTPCoords resLarge = adjLargeStep.adjust(chunkLarge);
        assertNotNull(resLarge);
        assertEquals(61, resLarge.y());
    }

    @Test
    void boundary_binaryNarrowing_earlyExit_when_i_exceeds_maxYMinusItLen() {
        // In loop:
        // for (int it_len = step; it_len > 2; it_len = it_len / 2) {
        //   for (int i = minY; i < maxY; i += it_len) {
        //     ...
        //     if (i > maxY - it_len) return false;
        //     oldY = i;
        //   }
        // }
        // If a column has no valid air gap satisfying the condition during the step loop,
        // it hits `if (i > maxY - it_len) return false;` and fails immediately.
        ConfigurableMockChunk solidChunk = new ConfigurableMockChunk(0, 0, world);
        // Make the whole chunk completely solid (no air anywhere)
        for (int y = 0; y < 200; y++) solidChunk.setSolid(y);
        JumpAdjustor adj = buildAdjustor(0, 160, 16);
        assertNull(adj.adjust(solidChunk), "Completely solid chunk should return null when step narrowing exceeds range");
    }
}
