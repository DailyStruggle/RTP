package io.github.dailystruggle.effectsapi.common.volumetric;

import io.github.dailystruggle.effectsapi.common.hologram.HologramHandle;
import io.github.dailystruggle.effectsapi.common.hologram.HologramProvider;
import io.github.dailystruggle.effectsapi.common.hologram.HologramRegistry;
import io.github.dailystruggle.effectsapi.common.hologram.VirtualHologramHandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Volumetric Particle Wireframe & Visuals Parity Tests (Phase 3)")
class VolumetricParticleRendererTest {

    @Test
    @DisplayName("GeometryPointGenerator computes points for all styles")
    void testGeometryPointGenerators() {
        SpatialBounds bounds = SpatialBounds.of(0, 64, 0, 10, 70, 10);

        // 1. OUTLINE
        List<Vector3d> outline = GeometryPointGenerator.generate(bounds, GeometryStyle.OUTLINE, 1.0, 0);
        assertNotNull(outline);
        assertFalse(outline.isEmpty());
        // All points must lie within or on the boundary
        for (Vector3d pt : outline) {
            assertTrue(bounds.contains(pt));
        }

        // 2. BEAM
        List<Vector3d> beam = GeometryPointGenerator.generate(bounds, GeometryStyle.BEAM, 1.0, 0);
        assertNotNull(beam);
        assertFalse(beam.isEmpty());
        for (Vector3d pt : beam) {
            assertTrue(bounds.contains(pt));
        }

        // 3. SWIRL
        List<Vector3d> swirl = GeometryPointGenerator.generate(bounds, GeometryStyle.SWIRL, 1.0, 5);
        assertNotNull(swirl);
        assertFalse(swirl.isEmpty());
        for (Vector3d pt : swirl) {
            assertTrue(bounds.contains(pt));
        }

        // 4. PULSE
        List<Vector3d> pulse = GeometryPointGenerator.generate(bounds, GeometryStyle.PULSE, 1.0, 10);
        assertNotNull(pulse);
        assertFalse(pulse.isEmpty());
        for (Vector3d pt : pulse) {
            assertTrue(bounds.contains(pt));
        }

        // 5. DUST_WALL
        List<Vector3d> dustWall = GeometryPointGenerator.generate(bounds, GeometryStyle.DUST_WALL, 1.0, 0);
        assertNotNull(dustWall);
        assertFalse(dustWall.isEmpty());
        for (Vector3d pt : dustWall) {
            assertTrue(bounds.contains(pt));
        }

        // 6. CORNER_BRACKETS
        List<Vector3d> brackets = GeometryPointGenerator.generate(bounds, GeometryStyle.CORNER_BRACKETS, 1.0, 0);
        assertNotNull(brackets);
        assertEquals(24, brackets.size());
        for (Vector3d pt : brackets) {
            assertTrue(bounds.contains(pt));
        }
    }

    @Test
    @DisplayName("REQ-SLEUTH-FARM-001: SpatialBounds plane projection and slab ray intersection preserve Y-column privacy")
    void testPlaneProjectionAndSlabRayIntersection() {
        SpatialBounds undergroundFarm = SpatialBounds.of(160, -54, 320, 176, -40, 336);

        // Surface player in the same chunk (10, 20) at Y = 70 is 110 blocks above the +Y plane
        Vector3d surfaceProj = undergroundFarm.projectOntoBounds(168.0, 70.0, 328.0);
        assertEquals(new Vector3d(168.0, -40.0, 328.0), surfaceProj);
        assertEquals(110.0, undergroundFarm.distanceToPlaneProjectedBounds(168.0, 70.0, 328.0), 1.0e-6);

        // Adjacent chunk player at (159, -48, 324) is 1.0 block outside the -X plane
        Vector3d borderProj = undergroundFarm.projectOntoBounds(159.0, -48.0, 324.0);
        assertEquals(new Vector3d(160.0, -48.0, 324.0), borderProj);
        assertEquals(1.0, undergroundFarm.distanceToPlaneProjectedBounds(159.0, -48.0, 324.0), 1.0e-6);

        // Inside point projects to itself with 0 distance
        assertEquals(0.0, undergroundFarm.distanceToPlaneProjectedBounds(165.0, -45.0, 325.0), 1.0e-6);

        // Slab ray-box intersection tie-breaking and axis-parallel safety
        SpatialBounds farmWest = SpatialBounds.of(90, 64, 100, 96, 70, 106);
        SpatialBounds farmEast = SpatialBounds.of(104, 64, 100, 110, 70, 106);
        Vector3d eye = new Vector3d(100.0, 66.0, 103.0);

        double hitWest = farmWest.rayIntersectionDistance(eye, new Vector3d(-1.0, 0.0, 0.0));
        double missEast = farmEast.rayIntersectionDistance(eye, new Vector3d(-1.0, 0.0, 0.0));
        assertEquals(4.0, hitWest, 1.0e-6);
        assertEquals(Double.POSITIVE_INFINITY, missEast);

        double hitEast = farmEast.rayIntersectionDistance(eye, new Vector3d(1.0, 0.0, 0.0));
        assertEquals(4.0, hitEast, 1.0e-6);

        // Parallel ray outside slab misses cleanly without NaN
        assertEquals(Double.POSITIVE_INFINITY, farmWest.rayIntersectionDistance(eye, new Vector3d(0.0, 0.0, 1.0)));
        // Origin inside bounds returns 0.0
        assertEquals(0.0, farmWest.rayIntersectionDistance(new Vector3d(93.0, 66.0, 103.0), new Vector3d(1.0, 0.0, 0.0)), 1.0e-6);
    }

    @Test
    @DisplayName("REQ-SLEUTH-FARM-002: Corner brackets clamp on 1x1x1 bounds and proximity aperture filters by plane distance")
    void testCornerBracketsAndPlaneProjectedAperture() {
        SpatialBounds unitCube = SpatialBounds.of(10, 64, 20, 11, 65, 21);
        List<Vector3d> unitBrackets = GeometryPointGenerator.generateCornerBrackets(unitCube, 1.0);
        assertEquals(24, unitBrackets.size());
        for (Vector3d pt : unitBrackets) {
            assertTrue(unitCube.contains(pt), "Point must remain inside 1x1x1 unit cube: " + pt);
        }

        SpatialBounds farmHull = SpatialBounds.of(100, -50, 200, 112, -42, 212);
        // Surface viewer far above in the same X/Z column -> zero aperture points
        List<Vector3d> farSurface = GeometryPointGenerator.generatePlaneProjectedAperture(
                farmHull, new Vector3d(106, 70, 206), 8.0, 4.0, 1.0);
        assertTrue(farSurface.isEmpty());

        // Viewer 2 blocks outside -X plane near bottom-front edge -> localized aperture points <= 32
        Vector3d nearWest = new Vector3d(98.0, -49.0, 202.0);
        List<Vector3d> aperture = GeometryPointGenerator.generatePlaneProjectedAperture(
                farmHull, nearWest, 8.0, 4.0, 1.0);
        assertFalse(aperture.isEmpty());
        assertTrue(aperture.size() <= 32);
        Vector3d projectedOnWest = new Vector3d(100.0, -49.0, 202.0);
        for (Vector3d pt : aperture) {
            assertTrue(farmHull.contains(pt));
            assertEquals(100.0, pt.x(), 1.0e-6);
            assertTrue(pt.distance(projectedOnWest) <= 4.0 + 1.0e-6);
        }
    }

    @Test
    @DisplayName("VolumetricParticleRenderer distance culling respects 48 block radius")
    void testDistanceCulling() {
        VolumetricParticleRenderer renderer = new VolumetricParticleRenderer(48.0, 200);
        assertEquals(48.0, renderer.renderDistance());

        Vector3d origin = new Vector3d(0, 64, 0);
        Vector3d near = new Vector3d(10, 64, 10); // dist ~14.14 < 48
        Vector3d far = new Vector3d(100, 64, 100); // dist ~141.4 > 48

        assertTrue(renderer.isVisibleToAny(near, List.of(origin)));
        assertFalse(renderer.isVisibleToAny(far, List.of(origin)));

        // Culling multiple points
        List<Vector3d> rawPoints = List.of(
                new Vector3d(5, 64, 5),   // inside
                new Vector3d(20, 64, 20), // inside
                new Vector3d(80, 64, 80)  // outside
        );

        List<Vector3d> culled = renderer.cullAndRateLimit(rawPoints, List.of(origin), 1L);
        assertEquals(2, culled.size());
        assertEquals(5.0, culled.get(0).x());
        assertEquals(20.0, culled.get(1).x());
    }

    @Test
    @DisplayName("VolumetricParticleRenderer rate limiter caps at 200 particles per tick")
    void testRateLimiter() {
        VolumetricParticleRenderer renderer = new VolumetricParticleRenderer(48.0, 200);

        List<Vector3d> manyPoints = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            manyPoints.add(new Vector3d(i % 10, 64, (i * 7) % 10));
        }

        Vector3d viewer = new Vector3d(5, 64, 5);

        // First tick: only 200 granted
        List<Vector3d> tick1 = renderer.cullAndRateLimit(manyPoints, List.of(viewer), 1L);
        assertEquals(200, tick1.size());
        assertEquals(200, renderer.rateLimiter().currentUsage());

        // Subsequent call on same tick: 0 granted
        List<Vector3d> tick1Again = renderer.cullAndRateLimit(manyPoints, List.of(viewer), 1L);
        assertTrue(tick1Again.isEmpty());

        // Advance to next tick: quota resets to 200
        List<Vector3d> tick2 = renderer.cullAndRateLimit(manyPoints, List.of(viewer), 2L);
        assertEquals(200, tick2.size());
        assertEquals(200, renderer.rateLimiter().currentUsage());
    }

    @Test
    @DisplayName("WireframeParticleLoop registers wireframes, runs tick and invokes spawner")
    void testWireframeParticleLoop() {
        AtomicInteger particlesSpawned = new AtomicInteger(0);
        WireframeParticleLoop.ParticleSink sink = (world, type, loc) -> particlesSpawned.incrementAndGet();
        WireframeParticleLoop.ViewerProvider viewers = world -> List.of(new Vector3d(5, 64, 5));

        try (WireframeParticleLoop loop = new WireframeParticleLoop(sink, viewers)) {
            SpatialBounds bounds = SpatialBounds.of(0, 64, 0, 10, 66, 10);
            SpatialWireframeRenderSpec spec = new SpatialWireframeRenderSpec(
                    "wireframe_arena",
                    "world",
                    bounds,
                    "minecraft:portal",
                    GeometryStyle.OUTLINE,
                    1.0
            );

            loop.registerWireframe(spec);
            assertEquals(1, loop.getActiveWireframes().size());
            assertSame(spec, loop.getWireframe("wireframe_arena"));
            assertSame(spec, loop.getWireframe("WIREFRAME_ARENA"));

            // Run one tick
            loop.tick();
            assertTrue(particlesSpawned.get() > 0);
            assertTrue(particlesSpawned.get() <= 200);

            // Unregister
            assertTrue(loop.unregisterWireframe("wireframe_arena"));
            assertNull(loop.getWireframe("wireframe_arena"));
            assertEquals(0, loop.getActiveWireframes().size());
        }
    }

    @Test
    @DisplayName("Display Entity / Hologram Provider renders dynamic floating text above volume center")
    void testHologramProvider() {
        HologramProvider mockProvider = (id, world, pos, lines) -> new VirtualHologramHandle(id, world, pos, lines);
        HologramRegistry.register(mockProvider);
        assertTrue(HologramRegistry.isAvailable());
        assertSame(mockProvider, HologramRegistry.getProvider());

        SpatialBounds bounds = SpatialBounds.of(-10, 64, -10, 10, 70, 10);
        Vector3d center = bounds.center();
        assertEquals(0.0, center.x());
        assertEquals(67.0, center.y());
        assertEquals(0.0, center.z());

        double yOffset = 1.5;
        List<String> countdownLines = List.of("§6§lArena Wireframe", "§eTeleporting in §c15s");

        try (HologramHandle handle = mockProvider.spawnAboveBounds(
                "volume_holo",
                "world",
                bounds,
                yOffset,
                countdownLines
        )) {
            assertEquals("volume_holo", handle.id());
            assertEquals("world", handle.worldName());
            assertEquals(0.0, handle.position().x());
            assertEquals(71.5, handle.position().y()); // maxY (70) + 1.5
            assertEquals(0.0, handle.position().z());
            assertEquals(countdownLines, handle.lines());

            // Dynamic text update
            List<String> updatedLines = List.of("§6§lArena Wireframe", "§eTeleporting in §c14s");
            handle.updateLines(updatedLines);
            assertEquals(updatedLines, handle.lines());
        }

        HologramRegistry.register(null);
        assertFalse(HologramRegistry.isAvailable());
    }
}
