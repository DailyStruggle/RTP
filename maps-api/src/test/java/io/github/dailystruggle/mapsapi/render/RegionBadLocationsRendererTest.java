package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.MapAllocationRequest;
import io.github.dailystruggle.mapsapi.MapHandle;
import io.github.dailystruggle.mapsapi.PaletteIndex;
import io.github.dailystruggle.mapsapi.model.RegionBadLocations;
import io.github.dailystruggle.mapsapi.testfixtures.InMemoryMapBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Drives {@link RegionBadLocationsRenderer} against {@link InMemoryMapBinding}
 * and asserts on the resulting pixel buffer:
 * <ul>
 *   <li>corners outside the inscribed disk are {@link PaletteIndex#BLACK};</li>
 *   <li>the canvas centre is {@link PaletteIndex#GREEN} when no bad keys
 *       cover it;</li>
 *   <li>a bad key on the centre paints {@link PaletteIndex#RED} (red
 *       overrides the green disk);</li>
 *   <li>bad keys outside the region's bounding square are silently
 *       clipped (no exception, no out-of-canvas writes);</li>
 *   <li>{@link io.github.dailystruggle.mapsapi.MapCanvas#commit()} is
 *       invoked exactly once per ephemeral render.</li>
 * </ul>
 */
@DisplayName("RegionBadLocationsRenderer - palette + disk geometry + clip semantics")
class RegionBadLocationsRendererTest {

    private final InMemoryMapBinding binding = new InMemoryMapBinding();
    private final RegionBadLocationsRenderer renderer = RegionBadLocationsRenderer.INSTANCE;

    private MapHandle allocate(String id) {
        return binding.allocate(new MapAllocationRequest(
                id, null, MapAllocationRequest.Locking.LOCKED));
    }

    /** Pack a block coordinate the same way {@link RegionBadLocations#badKeys()} expects. */
    private static long key(int bx, int bz) {
        return ((long) bx << 32) | (bz & 0xFFFFFFFFL);
    }

    @Test
    @DisplayName("empty bad set: corners are BLACK, centre is GREEN")
    void emptyBadSet() {
        MapHandle h = allocate("empty");
        RegionBadLocations m = new RegionBadLocations("r", 0, 0, 100, new long[0]);
        binding.renderEphemeral(h, renderer, m);

        byte[][] pixels = binding.snapshot(h);
        // Corners are outside the inscribed circle -> BLACK.
        assertEquals(PaletteIndex.BLACK, pixels[0][0]);
        assertEquals(PaletteIndex.BLACK, pixels[0][127]);
        assertEquals(PaletteIndex.BLACK, pixels[127][0]);
        assertEquals(PaletteIndex.BLACK, pixels[127][127]);
        // Centre lies inside the inscribed circle -> GREEN.
        assertEquals(PaletteIndex.GREEN, pixels[64][64]);
        assertEquals(1, binding.commitCount(h));
    }

    @Test
    @DisplayName("bad key at region centre paints RED at canvas centre")
    void badKeyAtCentre() {
        MapHandle h = allocate("centre-bad");
        long[] keys = { key(0, 0) }; // centerX=centerZ=0 -> exact centre
        RegionBadLocations m = new RegionBadLocations("r", 0, 0, 100, keys);
        binding.renderEphemeral(h, renderer, m);

        byte[][] pixels = binding.snapshot(h);
        // The bad key lands at the centre pixel; with radius=100 and
        // 128-pixel canvas, the centre block maps to pixel (63 or 64).
        // Accept either: assert RED appears somewhere in a small box.
        boolean foundRed = false;
        for (int y = 60; y <= 68 && !foundRed; y++) {
            for (int x = 60; x <= 68 && !foundRed; x++) {
                if (pixels[y][x] == PaletteIndex.RED) foundRed = true;
            }
        }
        assertEquals(true, foundRed, "expected at least one RED pixel near canvas centre");
        // Far corner still BLACK.
        assertEquals(PaletteIndex.BLACK, pixels[0][0]);
    }

    @Test
    @DisplayName("bad key outside region bounding square is silently clipped")
    void badKeyOutsideClipped() {
        MapHandle h = allocate("outside");
        long[] keys = { key(10_000, 10_000) }; // way outside radius=100 region
        RegionBadLocations m = new RegionBadLocations("r", 0, 0, 100, keys);
        // Must not throw, must still commit exactly once.
        binding.renderEphemeral(h, renderer, m);

        byte[][] pixels = binding.snapshot(h);
        // No RED anywhere on the canvas.
        for (byte[] row : pixels) {
            for (byte b : row) {
                assertNotEquals(PaletteIndex.RED, b,
                        "out-of-range bad key shall not produce any RED pixel");
            }
        }
        assertEquals(1, binding.commitCount(h));
    }

    @Test
    @DisplayName("red overrides green: bad pixel sits on top of the green disk")
    void redOverGreen() {
        MapHandle h = allocate("override");
        // A bad key offset slightly from centre, definitely inside the disk.
        long[] keys = { key(5, 5) };
        RegionBadLocations m = new RegionBadLocations("r", 0, 0, 1000, keys);
        binding.renderEphemeral(h, renderer, m);

        byte[][] pixels = binding.snapshot(h);
        boolean foundRed = false;
        int greenCount = 0;
        for (byte[] row : pixels) {
            for (byte b : row) {
                if (b == PaletteIndex.RED) foundRed = true;
                if (b == PaletteIndex.GREEN) greenCount++;
            }
        }
        assertEquals(true, foundRed, "expected RED for the bad-flagged location");
        // Green disk should still dominate the canvas (radius >> 1 pixel red dot).
        assertEquals(true, greenCount > 1000,
                "expected the inscribed green disk to remain mostly green; got " + greenCount);
    }

    @Test
    @DisplayName("canvas dimensions remain vanilla 128x128")
    void canvasIs128x128() {
        MapHandle h = allocate("dim");
        RegionBadLocations m = new RegionBadLocations("r", 0, 0, 100, new long[0]);
        binding.renderEphemeral(h, renderer, m);

        byte[][] pixels = binding.snapshot(h);
        assertEquals(128, pixels.length);
        for (byte[] row : pixels) {
            assertEquals(128, row.length);
        }
    }

    @Test
    @DisplayName("renderer null-arg rejection (direct render(canvas, model) call)")
    void nullArgs() {
        RegionBadLocations m = new RegionBadLocations("r", 0, 0, 100, new long[0]);
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
