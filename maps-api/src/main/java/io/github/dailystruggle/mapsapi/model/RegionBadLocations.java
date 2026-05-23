package io.github.dailystruggle.mapsapi.model;

import java.util.Objects;

/**
 * Snapshot of the bad-location set held by a single {@code Region}'s memory
 * shape: every coordinate the region has observed to be unsafe (claim
 * conflict, biome filter, vertical-search miss, anvil prefilter, etc.).
 * Consumed by {@code RegionBadLocationsRenderer} to draw the admin-facing
 * region-shape visualization: red for bad cells, green for the rest of the
 * region disk, black for outside the disk.
 *
 * <p>The data model is intentionally two-tone (bad vs. not-bad). Regions
 * persist a bad set but no parallel "verified safe" set; "not flagged bad"
 * is the only signal available for "good inside the region disk" without
 * incurring a fresh scan. A biome backdrop is a separate, future overlay
 * chart shape (deferred per the issue thread that landed Option A on the
 * palette and Option 1+3 on this record).
 *
 * <p>Coordinates use {@code chunkKey}-style packed longs:
 * {@code (long) blockX << 32 | (blockZ & 0xFFFFFFFFL)} — the same packing
 * used by {@code MemoryShape.pendingBadLocations}. Renderers decode them
 * via {@code (int) (key >> 32)} for {@code blockX} and {@code (int) key}
 * for {@code blockZ}.
 *
 * <p>The constructor defensively copies {@code badKeys} (REQ-RTP-MAP-002)
 * and {@link #badKeys()} returns a fresh array on every call so renderers
 * invoked from any thread cannot observe in-flight producer mutations.
 *
 * @param regionName name of the {@code Region} this snapshot was taken from
 * @param centerX    region centre block-X (used by the renderer to translate
 *                   block coordinates into canvas pixel coordinates)
 * @param centerZ    region centre block-Z
 * @param radius     region radius in blocks; the renderer paints the
 *                   inscribed disk green and everything outside it black.
 *                   Shall be strictly positive.
 * @param badKeys    flat array of bad-location packed longs (see above).
 *                   May be empty (no bad locations on record) but never
 *                   {@code null}. Duplicate keys are tolerated and produce
 *                   the same red pixel.
 */
public record RegionBadLocations(String regionName, int centerX, int centerZ,
                                 int radius, long[] badKeys) implements ChartModel {

    public RegionBadLocations {
        Objects.requireNonNull(regionName, "regionName");
        if (radius <= 0) {
            throw new IllegalArgumentException("radius shall be > 0, got " + radius);
        }
        Objects.requireNonNull(badKeys, "badKeys");
        badKeys = badKeys.clone();
    }

    /** Defensive accessor — returns a clone of the internal bad-keys array. */
    @Override
    public long[] badKeys() {
        return badKeys.clone();
    }

    /** Number of bad-location entries in this snapshot. */
    public int badCount() {
        return badKeys.length;
    }
}
