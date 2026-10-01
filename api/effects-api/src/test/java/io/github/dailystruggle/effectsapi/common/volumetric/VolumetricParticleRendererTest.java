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
