package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.MapAllocationRequest;
import io.github.dailystruggle.mapsapi.MapHandle;
import io.github.dailystruggle.mapsapi.PaletteIndex;
import io.github.dailystruggle.mapsapi.model.RegionBadLocations;
import io.github.dailystruggle.mapsapi.testfixtures.InMemoryMapBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Drives {@link RegionBadLocationsRenderer} against {@link InMemoryMapBinding}.
 * The renderer is now a pure blit (with nearest-neighbour scaling when the
 * canvas size differs from the model buffer size); classification lives in
 * the {@code RegionBadLocationsShapeResolver}, so these tests fabricate
 * the palette buffer directly and assert that it lands on the canvas
 * pixel-for-pixel.
 */
@DisplayName("RegionBadLocationsRenderer - blit + nearest-neighbour scaling")
class RegionBadLocationsRendererTest {

    private final InMemoryMapBinding binding = new InMemoryMapBinding();
    private final RegionBadLocationsRenderer renderer = RegionBadLocationsRenderer.INSTANCE;

    private MapHandle allocate(String id) {
        return binding.allocate(new MapAllocationRequest(
                id, null, MapAllocationRequest.Locking.LOCKED));
    }

    @Test
    @DisplayName("identical-resolution buffer is blitted pixel-for-pixel")
    void identityBlit() {
        MapHandle h = allocate("identity");
        // 128x128 all-green model.
        byte[] palette = new byte[128 * 128];
        java.util.Arrays.fill(palette, PaletteIndex.GREEN);
        // Stamp the four corners and the centre with distinguishable colours.
        palette[0]                     = PaletteIndex.BLACK;            // (0,0)
        palette[127]                   = PaletteIndex.RED;              // (127,0)
        palette[127 * 128]             = PaletteIndex.RED;              // (0,127)
        palette[127 * 128 + 127]       = PaletteIndex.BLACK;            // (127,127)
        palette[64 * 128 + 64]         = PaletteIndex.RED;              // centre

        RegionBadLocations m = new RegionBadLocations("r", 128, 128, palette);
        binding.renderEphemeral(h, renderer, m);

        byte[][] pixels = binding.snapshot(h);
        assertEquals(PaletteIndex.BLACK, pixels[0][0]);
        assertEquals(PaletteIndex.RED,   pixels[0][127]);
        assertEquals(PaletteIndex.RED,   pixels[127][0]);
        assertEquals(PaletteIndex.BLACK, pixels[127][127]);
        assertEquals(PaletteIndex.RED,   pixels[64][64]);
        assertEquals(PaletteIndex.GREEN, pixels[10][10]);
        assertEquals(1, binding.commitCount(h));
    }

    @Test
    @DisplayName("smaller-resolution buffer is upscaled with nearest-neighbour")
    void nearestNeighbourUpscale() {
        MapHandle h = allocate("upscale");
        // 2x2 chequerboard: GREEN, RED, RED, GREEN.
        byte[] palette = {
                PaletteIndex.GREEN, PaletteIndex.RED,
                PaletteIndex.RED,   PaletteIndex.GREEN,
        };
        RegionBadLocations m = new RegionBadLocations("r", 2, 2, palette);
        binding.renderEphemeral(h, renderer, m);

        byte[][] pixels = binding.snapshot(h);
        // Top-left quadrant of the 128x128 canvas should be GREEN.
        assertEquals(PaletteIndex.GREEN, pixels[0][0]);
        assertEquals(PaletteIndex.GREEN, pixels[10][10]);
        // Top-right quadrant should be RED.
        assertEquals(PaletteIndex.RED, pixels[10][120]);
        // Bottom-left quadrant should be RED.
        assertEquals(PaletteIndex.RED, pixels[120][10]);
        // Bottom-right quadrant should be GREEN.
        assertEquals(PaletteIndex.GREEN, pixels[120][120]);
        assertEquals(1, binding.commitCount(h));
    }

    @Test
    @DisplayName("canvas dimensions remain vanilla 128x128")
    void canvasIs128x128() {
        MapHandle h = allocate("dim");
        byte[] palette = new byte[128 * 128];
        java.util.Arrays.fill(palette, PaletteIndex.GREEN);
        RegionBadLocations m = new RegionBadLocations("r", 128, 128, palette);
        binding.renderEphemeral(h, renderer, m);

        byte[][] pixels = binding.snapshot(h);
        assertEquals(128, pixels.length);
        for (byte[] row : pixels) {
            assertEquals(128, row.length);
        }
    }

    @Test
    @DisplayName("renderer null-arg rejection")
    void nullArgs() {
        byte[] palette = new byte[1];
        RegionBadLocations m = new RegionBadLocations("r", 1, 1, palette);
        TinyCanvas canvas = new TinyCanvas();
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(null, m));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(canvas, null));
    }

    /** Minimal {@link io.github.dailystruggle.mapsapi.MapCanvas} stub for null-arg test only. */
    private static final class TinyCanvas implements io.github.dailystruggle.mapsapi.MapCanvas {
        @Override public int width() { return 128; }
        @Override public int height() { return 128; }
        @Override public void setPixel(int x, int y, byte paletteIndex) {}
        @Override public void fillRect(int x0, int y0, int x1, int y1, byte paletteIndex) {}
        @Override public void drawText(int x, int y, String text, byte paletteIndex) {}
        @Override public void clear() {}
        @Override public void commit() {}
    }
}
