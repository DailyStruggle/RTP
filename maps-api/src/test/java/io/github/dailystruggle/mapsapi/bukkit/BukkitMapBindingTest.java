package io.github.dailystruggle.mapsapi.bukkit;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import io.github.dailystruggle.mapsapi.MapAllocationRequest;
import io.github.dailystruggle.mapsapi.MapHandle;
import io.github.dailystruggle.mapsapi.model.Heatmap2D;
import io.github.dailystruggle.mapsapi.render.HeatmapRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle + behaviour tests for {@link BukkitMapBinding}. Covers
 * {@code CHECKLIST-maps-api.md} Stage 2.1 + 2.9: allocation idempotency,
 * one-shot {@code renderEphemeral} dispatch, palette translation surface,
 * {@code bindLive} explicit deferral, and the no-world failure path.
 */
@DisplayName("BukkitMapBinding - Stage 2.1 lifecycle / behaviour")
class BukkitMapBindingTest {

    private ServerMock server;
    private BukkitMapBinding binding;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        binding = new BukkitMapBinding();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("allocate creates a MapView and returns a MapHandle with its id")
    void allocateReturnsHandleBackedByMapView() {
        MapHandle handle = binding.allocate(new MapAllocationRequest(
                "bad-points/world/test-1", null, MapAllocationRequest.Locking.LOCKED));
        assertNotNull(handle);
        assertEquals("bad-points/world/test-1", handle.chartId());
        assertNotNull(binding.viewOf(handle), "MapView should resolve back from handle.mapId()");
    }

    @Test
    @DisplayName("allocate is idempotent: same chartId returns the same handle")
    void allocateIsIdempotentByChartId() {
        MapAllocationRequest req = new MapAllocationRequest(
                "bad-points/world/idemp", null, MapAllocationRequest.Locking.LOCKED);
        MapHandle first = binding.allocate(req);
        MapHandle second = binding.allocate(req);
        assertSame(first, second, "Repeated allocate() for the same chartId must dedupe");
    }

    @Test
    @DisplayName("renderEphemeral installs exactly one MapRenderer on the backing MapView")
    void renderEphemeralInstallsExactlyOneRenderer() {
        MapHandle handle = binding.allocate(new MapAllocationRequest(
                "bad-points/world/render", null, MapAllocationRequest.Locking.LOCKED));
        Heatmap2D model = new Heatmap2D(4, 4, new double[16], 0.0, 1.0);

        binding.renderEphemeral(handle, HeatmapRenderer.INSTANCE, model);

        org.bukkit.map.MapView view = binding.viewOf(handle);
        assertNotNull(view);
        assertEquals(1, view.getRenderers().size(),
                "renderEphemeral should leave exactly one one-shot renderer attached");
    }

    @Test
    @DisplayName("renderEphemeral on an unknown handle throws IllegalStateException (REQ-RTP-S-004)")
    void renderEphemeralOnUnknownHandleThrows() {
        MapHandle bogus = new MapHandle("bad-points/world/bogus", null, Integer.MAX_VALUE);
        Heatmap2D model = new Heatmap2D(2, 2, new double[4], 0.0, 1.0);
        assertThrows(IllegalStateException.class,
                () -> binding.renderEphemeral(bogus, HeatmapRenderer.INSTANCE, model));
    }

    @Test
    @DisplayName("bindLive is explicitly deferred and throws UnsupportedOperationException")
    void bindLiveIsDeferred() {
        MapHandle handle = binding.allocate(new MapAllocationRequest(
                "bad-points/world/live-stub", null, MapAllocationRequest.Locking.LOCKED));
        Heatmap2D model = new Heatmap2D(2, 2, new double[4], 0.0, 1.0);
        assertThrows(UnsupportedOperationException.class,
                () -> binding.bindLive(handle, HeatmapRenderer.INSTANCE, () -> model));
    }

    @Test
    @DisplayName("Logical palette translation: 0 maps to MapPalette.TRANSPARENT")
    void logicalPaletteZeroIsTransparent() {
        byte translated = BukkitMapBinding.toVanillaPalette((byte) 0);
        assertEquals(org.bukkit.map.MapPalette.TRANSPARENT, translated);
    }

    @Test
    @DisplayName("Logical palette translation: indices 1..31 differ from index 0 (transparent)")
    void logicalPaletteRampIsDistinctFromTransparent() {
        for (int i = 1; i < 32; i++) {
            byte v = BukkitMapBinding.toVanillaPalette((byte) i);
            assertTrue(v != org.bukkit.map.MapPalette.TRANSPARENT,
                    "logical " + i + " resolved to TRANSPARENT; ramp should be opaque");
        }
    }
}
