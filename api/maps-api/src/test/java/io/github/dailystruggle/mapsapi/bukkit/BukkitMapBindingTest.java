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
 * Lifecycle and behaviour tests for {@link BukkitMapBinding}: allocation idempotency,
 * one-shot {@code renderEphemeral} dispatch, palette translation surface,
 * {@code bindLive} explicit deferral, and the no-world failure path.
 */
@DisplayName("BukkitMapBinding - lifecycle and behaviour")
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
    @DisplayName("bindLive installs a live renderer and returns a working Cancellation")
    void bindLiveInstallsAndCancels() {
        MapHandle handle = binding.allocate(new MapAllocationRequest(
                "bad-points/world/live-stub", null, MapAllocationRequest.Locking.LOCKED));
        Heatmap2D model = new Heatmap2D(2, 2, new double[4], 0.0, 1.0);
        io.github.dailystruggle.mapsapi.Cancellation c =
                binding.bindLive(handle, HeatmapRenderer.INSTANCE, () -> model);
        org.junit.jupiter.api.Assertions.assertNotNull(c);
        org.junit.jupiter.api.Assertions.assertFalse(c.cancelled());
        c.cancel();
        org.junit.jupiter.api.Assertions.assertTrue(c.cancelled());
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

    @Test
    @DisplayName("Stage 2.2: onPlayerQuit drops the viewer's cached chartIds (idempotent re-allocate yields a fresh handle)")
    void onPlayerQuitReleasesViewerHandles() {
        java.util.UUID viewer = java.util.UUID.randomUUID();
        MapAllocationRequest req = new MapAllocationRequest(
                "bad-points/world/quit", viewer, MapAllocationRequest.Locking.LOCKED);
        MapHandle first = binding.allocate(req);
        assertNotNull(first);
        // Sanity: second allocate dedupes while the viewer is cached.
        assertSame(first, binding.allocate(req));

        binding.onPlayerQuit(viewer);

        // After quit, the cache entry is gone -> a fresh allocate produces a new handle
        // (and a new backing MapView). The previous handle is intentionally unreferenced
        // and eligible for GC; the binding never holds it after onPlayerQuit.
        MapHandle replacement = binding.allocate(req);
        assertNotNull(replacement);
        assertTrue(replacement != first,
                "onPlayerQuit should release the cached chartId so a fresh allocate produces a new handle");
    }

    @Test
    @DisplayName("Stage 2.2: onPlayerQuit on an unknown viewer is a safe no-op")
    void onPlayerQuitUnknownViewerIsNoop() {
        binding.onPlayerQuit(java.util.UUID.randomUUID());
        // Subsequent allocate still works
        MapHandle handle = binding.allocate(new MapAllocationRequest(
                "bad-points/world/unknown-quit", null, MapAllocationRequest.Locking.LOCKED));
        assertNotNull(handle);
    }

    @Test
    @DisplayName("Stage 2.2: onDisable clears every cache and subsequent allocate throws IllegalStateException")
    void onDisableMakesBindingUnusable() {
        binding.allocate(new MapAllocationRequest(
                "bad-points/world/disable-1", java.util.UUID.randomUUID(),
                MapAllocationRequest.Locking.LOCKED));
        binding.onDisable();
        assertThrows(IllegalStateException.class,
                () -> binding.allocate(new MapAllocationRequest(
                        "bad-points/world/post-disable", null,
                        MapAllocationRequest.Locking.LOCKED)),
                "after onDisable, allocate must refuse with IllegalStateException");
    }

    @Test
    @DisplayName("deliverTo and live rendering execution against mock player")
    void testDeliverToAndLiveRender() {
        org.bukkit.entity.Player mockPlayer = server.addPlayer("TestViewer");
        MapHandle handle = binding.allocate(new MapAllocationRequest(
                "bad-points/world/deliver", mockPlayer.getUniqueId(), MapAllocationRequest.Locking.LOCKED));

        // Test deliverTo
        binding.deliverTo(handle, mockPlayer.getUniqueId());
        assertTrue(mockPlayer.getWorld().getEntities().stream().anyMatch(e -> e.getType() == org.bukkit.entity.EntityType.ITEM));

        // Test deliverTo with unknown viewer throws IllegalStateException
        assertThrows(IllegalStateException.class, () -> binding.deliverTo(handle, java.util.UUID.randomUUID()));

        // Test deliverTo with bogus handle throws IllegalStateException
        MapHandle bogus = new MapHandle("bad-points/world/bogus", null, 99999);
        assertThrows(IllegalStateException.class, () -> binding.deliverTo(bogus, mockPlayer.getUniqueId()));

        // Test live renderer paint pass
        Heatmap2D model = new Heatmap2D(4, 4, new double[16], 0.0, 1.0);
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);
        io.github.dailystruggle.mapsapi.Cancellation c = binding.bindLive(handle, HeatmapRenderer.INSTANCE, () -> {
            counter.incrementAndGet();
            return model;
        });

        org.bukkit.map.MapView view = binding.viewOf(handle);
        assertNotNull(view);
        org.bukkit.map.MapRenderer liveRenderer = view.getRenderers().get(0);

        org.bukkit.map.MapCanvas dummyCanvas = new org.bukkit.map.MapCanvas() {
            private final byte[] buf = new byte[128 * 128];
            @Override public org.bukkit.map.MapView getMapView() { return view; }
            @Override public void setPixel(int x, int y, byte color) { buf[y * 128 + x] = color; }
            @Override public byte getPixel(int x, int y) { return buf[y * 128 + x]; }
            @Override public byte getBasePixel(int x, int y) { return 0; }
            @Override public java.awt.Color getBasePixelColor(int x, int y) { return java.awt.Color.BLACK; }
            @Override public java.awt.Color getPixelColor(int x, int y) { return java.awt.Color.BLACK; }
            @Override public void setPixelColor(int x, int y, java.awt.Color color) {}
            @Override public void drawImage(int x, int y, java.awt.Image image) {}
            @Override public void drawText(int x, int y, org.bukkit.map.MapFont font, String text) {}
            @Override public org.bukkit.map.MapCursorCollection getCursors() { return new org.bukkit.map.MapCursorCollection(); }
            @Override public void setCursors(org.bukkit.map.MapCursorCollection cursors) {}
        };

        liveRenderer.render(view, dummyCanvas, mockPlayer);
        assertEquals(1, counter.get());

        // Cancel and verify renderer short-circuits
        c.cancel();
        liveRenderer.render(view, dummyCanvas, mockPlayer);
        assertEquals(1, counter.get());

        // Test one-shot renderer pass on CraftMapView/MapView
        MapHandle oneShotHandle = binding.allocate(new MapAllocationRequest(
                "bad-points/world/oneshot-render", mockPlayer.getUniqueId(), MapAllocationRequest.Locking.LOCKED));
        binding.renderEphemeral(oneShotHandle, HeatmapRenderer.INSTANCE, model);
        org.bukkit.map.MapView oneShotView = binding.viewOf(oneShotHandle);
        org.bukkit.map.MapRenderer oneShotRenderer = oneShotView.getRenderers().get(0);
        oneShotRenderer.render(oneShotView, dummyCanvas, mockPlayer);
        // Second render invocation hits short-circuit `if (rendered) return;`
        oneShotRenderer.render(oneShotView, dummyCanvas, mockPlayer);

        // Test one-shot renderer with RegionBiomesRgb to exercise setPixelRgb, fillRect, drawText, clear on BukkitMapCanvas
        io.github.dailystruggle.mapsapi.model.RegionBiomesRgb biomeModel = new io.github.dailystruggle.mapsapi.model.RegionBiomesRgb(
                "world", 128, 128, new int[128 * 128], new byte[128 * 128]);
        MapHandle biomeHandle = binding.allocate(new MapAllocationRequest(
                "bad-points/world/biome-render", mockPlayer.getUniqueId(), MapAllocationRequest.Locking.LOCKED));
        binding.renderEphemeral(biomeHandle, io.github.dailystruggle.mapsapi.render.RegionBiomesRgbRenderer.INSTANCE, biomeModel);
        org.bukkit.map.MapView biomeView = binding.viewOf(biomeHandle);
        biomeView.getRenderers().get(0).render(biomeView, dummyCanvas, mockPlayer);

        // Directly exercise BukkitMapCanvas coordinate clipping & methods
        org.bukkit.map.MapRenderer customRenderer = new org.bukkit.map.MapRenderer() {
            @Override
            public void render(org.bukkit.map.MapView map, org.bukkit.map.MapCanvas canvas, org.bukkit.entity.Player player) {}
        };
        // Use a test renderer to invoke BukkitMapCanvas directly via renderEphemeral
        binding.renderEphemeral(biomeHandle, (canvas, m) -> {
            // Out of bounds clipping
            canvas.setPixel(-1, 0, (byte) 1);
            canvas.setPixel(128, 0, (byte) 1);
            canvas.setPixel(0, -1, (byte) 1);
            canvas.setPixel(0, 128, (byte) 1);

            canvas.setPixelRgb(-1, 0, 0xFFFFFF);
            canvas.setPixelRgb(128, 0, 0xFFFFFF);
            canvas.setPixelRgb(0, -1, 0xFFFFFF);
            canvas.setPixelRgb(0, 128, 0xFFFFFF);

            // Valid bounds
            canvas.setPixel(5, 5, (byte) 1);
            canvas.setPixelRgb(6, 6, 0x123456);
            canvas.fillRect(10, 10, 20, 20, (byte) 2);
            canvas.drawText(0, 0, "Test", (byte) 0);
            canvas.clear();
            canvas.commit();
            assertEquals(128, canvas.width());
            assertEquals(128, canvas.height());
        }, biomeModel);
        biomeView.getRenderers().get(0).render(biomeView, dummyCanvas, mockPlayer);
    }
}
