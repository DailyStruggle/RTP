package io.github.dailystruggle.rtp.anvil;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Lean read-only view of a chunk's center column ({@code lx=8, lz=8}) over a
 * caller-supplied Y window, produced by
 * {@link AnvilReader#readColumnProbe(byte[], int, int, int, int)}. Built from a
 * selectively-parsed NBT root (block_entities, structures, Entities, light
 * sections are skipped). {@code blockAt(int)} answers the same as
 * {@link AnvilChunkView#blockIdAt} at {@code (8, y, 8)} with much less alloc.
 * {@code heightmapTopY = Integer.MIN_VALUE} when the {@code MOTION_BLOCKING_NO_LEAVES}
 * heightmap is absent/malformed.
 */
public record ColumnProbe(int minY,
                          int maxY,
                          int heightmapTopY,
                          List<PaletteSection> sections,
                          List<BiomePaletteSection> biomeSections) {

    /** Chunk-local X used for all center-column queries. */
    public static final int CENTER_LOCAL_X = 8;
    /** Chunk-local Z used for all center-column queries. */
    public static final int CENTER_LOCAL_Z = 8;

    public ColumnProbe {
        Objects.requireNonNull(sections, "sections");
        Objects.requireNonNull(biomeSections, "biomeSections");
        sections = Collections.unmodifiableList(sections);
        biomeSections = Collections.unmodifiableList(biomeSections);
    }

    /**
     * Returns the raw namespaced block identifier at center-column world-Y {@code worldY},
     * or {@code null} if {@code worldY} is outside {@code [minY, maxY]} or the covering
     * section was not emitted on disk.
     */
    public String blockAt(int worldY) {
        return blockAt(CENTER_LOCAL_X, CENTER_LOCAL_Z, worldY);
    }

    /**
     * Returns the raw namespaced block identifier at chunk-local column
     * {@code (localX, localZ)} and world-Y {@code worldY}, or {@code null} if
     * {@code worldY} is outside {@code [minY, maxY]} or the covering section
     * was not emitted on disk.
     *
     * <p>The probe's backing {@link PaletteSection}s already hold the entire
     * 16×16×16 section data, so off-center reads are O(1) palette-index lookups
     * with no additional NBT-parse or allocation cost. This entry point exists
     * to let probe-side adjustors mirror the multi-column {@code testCoords}
     * sweep performed by the live {@code adjust(RTPChunk, MutableRTPCoords)}
     * path, so a probe-side {@code SCAN_MISS} can be authoritative across the
     * same five columns rather than only the center.</p>
     */
    public String blockAt(int localX, int localZ, int worldY) {
        if (worldY < minY || worldY > maxY) return null;
        int sy = Math.floorDiv(worldY, 16);
        int ly = Math.floorMod(worldY, 16);
        for (PaletteSection s : sections) {
            if (s.sectionY() == sy) {
                return s.blockIdAt(localX, ly, localZ);
            }
        }
        return null;
    }

    /**
     * Returns the raw namespaced biome identifier at center-column world-Y {@code worldY},
     * or {@code null} if {@code worldY} is outside {@code [minY, maxY]} or the covering
     * section has no biome container on disk.
     */
    public String biomeAt(int worldY) {
        if (worldY < minY || worldY > maxY) return null;
        int sy = Math.floorDiv(worldY, 16);
        int ly = Math.floorMod(worldY, 16);
        for (BiomePaletteSection bs : biomeSections) {
            if (bs.sectionY() == sy) {
                return bs.biomeIdAt(CENTER_LOCAL_X, ly, CENTER_LOCAL_Z);
            }
        }
        return null;
    }

    /**
     * True iff the {@code MOTION_BLOCKING_NO_LEAVES} heightmap was decoded at construction.
     * When false, {@link #heightmapTopY()} returns {@link Integer#MIN_VALUE} and callers
     * should not rely on it for pre-filtering.
     */
    public boolean hasHeightmap() {
        return heightmapTopY != Integer.MIN_VALUE;
    }
}
