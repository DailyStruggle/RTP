package io.github.dailystruggle.mapsapi.model;

import java.util.Objects;

/**
 * Pre-classified pixel buffer for a single {@code Region}'s safety chart:
 * each entry of {@link #palette} is one of {@code PaletteIndex.BLACK}
 * (outside the region's spatial domain), {@code PaletteIndex.GREEN}
 * (inside the region, not flagged bad), or {@code PaletteIndex.RED}
 * (flagged bad in the region's memory shape). The resolver does the
 * classification against the live {@code MemoryShape} (using
 * {@code locationToXZ}/{@code xzToLocation}/{@code isKnownBad}) and emits
 * a self-contained buffer; the renderer is shape-agnostic and just blits.
 *
 * <p>Earlier revisions of this record carried {@code (centerX, centerZ,
 * radius, long[] badKeys)} and let the renderer assume a circular disk.
 * That presumed shape geometry that {@code MemoryShape} does not expose
 * uniformly (rectangular shapes, ellipses, donuts, etc.) and threw on
 * empty {@code badKeys}. The current encoding is "map encoding": the
 * resolver has already projected the shape's actual locations onto a
 * canvas-sized palette grid, so empty {@code badKeys} legitimately yields
 * an all-green region with no failure.
 *
 * <p>The constructor defensively copies {@link #palette} (REQ-RTP-MAP-002)
 * and {@link #palette()} returns a fresh array on every call so renderers
 * invoked from any thread cannot observe in-flight producer mutations.
 *
 * @param regionName name of the {@code Region} this snapshot was taken from.
 *                   Never {@code null}.
 * @param width      pixel width of {@link #palette}; the buffer is laid
 *                   out row-major as {@code palette[y * width + x]}.
 *                   Shall be strictly positive.
 * @param height     pixel height of {@link #palette}. Shall be strictly
 *                   positive.
 * @param palette    flat {@code width * height} array of palette-index
 *                   bytes (signed Java bytes carrying the unsigned vanilla
 *                   palette code). Never {@code null}; length must equal
 *                   {@code width * height}.
 */
public record RegionBadLocations(String regionName, int width, int height,
                                 byte[] palette) implements ChartModel {

    public RegionBadLocations {
        Objects.requireNonNull(regionName, "regionName");
        if (width <= 0) {
            throw new IllegalArgumentException("width shall be > 0, got " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height shall be > 0, got " + height);
        }
        Objects.requireNonNull(palette, "palette");
        if (palette.length != width * height) {
            throw new IllegalArgumentException(
                    "palette.length shall equal width * height (" + (width * height)
                            + "), got " + palette.length);
        }
        palette = palette.clone();
    }

    /** Defensive accessor: returns a clone of the internal palette buffer. */
    @Override
    public byte[] palette() {
        return palette.clone();
    }
}
