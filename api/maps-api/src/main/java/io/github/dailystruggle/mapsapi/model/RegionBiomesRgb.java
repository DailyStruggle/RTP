package io.github.dailystruggle.mapsapi.model;

import java.util.Objects;

/**
 * Pre-classified pixel buffer for a {@code Region}'s biome chart, carrying
 * per-pixel 24-bit RGB plus a parallel byte mask. Used by
 * {@code RegionBiomesRgbRenderer}; sibling to {@link RegionBadLocations}
 * but uses the binding's full RGB pathway
 * ({@code MapCanvas#setPixelRgb}) rather than the 32-slot logical palette.
 *
 * <p>The {@link #mask} byte at each index encodes how the {@link #rgb}
 * entry should be rendered:
 * <ul>
 *   <li>{@code 0} - outside the region's spatial domain, render as solid
 *       BLACK (the renderer uses
 *       {@code PaletteIndex.BLACK} via {@code setPixel}). The {@code rgb}
 *       entry shall be ignored.</li>
 *   <li>{@code 1} - in-disk-unsampled cell the flood-fill could not reach.
 *       Render as BLACK per ADR-aligned "honest unknown" policy. The
 *       {@code rgb} entry shall be ignored.</li>
 *   <li>{@code 2} - sampled / flood-filled cell; render via
 *       {@code MapCanvas#setPixelRgb(x, y, rgb[idx])} to get the binding's
 *       best match for that biome's server-provided colour.</li>
 * </ul>
 *
 * <p>The constructor defensively copies both arrays (REQ-RTP-MAP-002) and
 * accessors return fresh copies on every call so renderers invoked from
 * any thread cannot observe in-flight producer mutations.
 *
 * @param regionName name of the {@code Region} this snapshot is from
 * @param width      pixel width; buffers are row-major {@code [y*width + x]}
 * @param height     pixel height
 * @param rgb        flat {@code width*height} array of 0xRRGGBB ints
 * @param mask       parallel byte mask (see above)
 */
public record RegionBiomesRgb(String regionName, int width, int height,
                              int[] rgb, byte[] mask) implements ChartModel {

    /** Mask byte: cell is outside the region's spatial domain. */
    public static final byte MASK_OUTSIDE = 0;
    /** Mask byte: cell is in-disk but the flood-fill never reached it. */
    public static final byte MASK_UNSAMPLED = 1;
    /** Mask byte: cell carries a valid RGB to render via {@code setPixelRgb}. */
    public static final byte MASK_RGB = 2;

    public RegionBiomesRgb {
        Objects.requireNonNull(regionName, "regionName");
        if (width <= 0) {
            throw new IllegalArgumentException("width shall be > 0, got " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height shall be > 0, got " + height);
        }
        Objects.requireNonNull(rgb, "rgb");
        Objects.requireNonNull(mask, "mask");
        int n = width * height;
        if (rgb.length != n) {
            throw new IllegalArgumentException(
                    "rgb.length shall equal width * height (" + n + "), got " + rgb.length);
        }
        if (mask.length != n) {
            throw new IllegalArgumentException(
                    "mask.length shall equal width * height (" + n + "), got " + mask.length);
        }
        rgb = rgb.clone();
        mask = mask.clone();
    }

    @Override
    public int[] rgb() {
        return rgb.clone();
    }

    @Override
    public byte[] mask() {
        return mask.clone();
    }
}
