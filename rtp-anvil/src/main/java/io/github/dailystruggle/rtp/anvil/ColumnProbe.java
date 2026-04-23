package io.github.dailystruggle.rtp.anvil;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Lean, read-only view of a single chunk's <em>center column</em> ({@code lx=8, lz=8})
 * over a caller-supplied world-Y window, produced by
 * {@link AnvilReader#readColumnProbe(byte[], int, int, int, int)}.
 *
 * <p>Built from a <i>selectively</i>-parsed chunk NBT root (see
 * {@link Nbt#readRootCompoundSelective}): non-relevant root children (e.g.
 * {@code block_entities}, {@code structures}, {@code Entities}, tick queues) and
 * non-relevant section children ({@code BlockLight}, {@code SkyLight}) are never
 * materialised. For center-column queries — the only queries this probe answers — this
 * yields the same answers {@link AnvilChunkView#blockIdAt}/{@link AnvilChunkView#getBiomeAt}
 * would at {@code (8, y, 8)}, while doing substantially less allocation and NBT-parse work.
 *
 * <p>The probe is intentionally minimal. It does <b>not</b> answer queries at arbitrary
 * {@code (x, z)} coordinates — {@link AnvilChunkView} is the right target for those. The
 * per-candidate biome-lookup optimization (see {@code docs/dev/BIOME_LOOKUP_PERF_PLAN.md})
 * is framed around "one candidate per chunk, center column", which is what this type
 * serves.
 *
 * @param minY               inclusive lower world-Y bound supplied at construction
 * @param maxY               inclusive upper world-Y bound supplied at construction
 * @param heightmapTopY      world-Y of the top motion-blocking, no-leaves block at the
 *                           chunk's {@code (8, 8)} column, or {@link Integer#MIN_VALUE}
 *                           when the {@code MOTION_BLOCKING_NO_LEAVES} heightmap was
 *                           absent/malformed (callers should treat as a hint, not as
 *                           authoritative)
 * @param sections           block-state sections covering the probe's Y window (may
 *                           overshoot when sections straddle {@code minY}/{@code maxY};
 *                           callers use {@link #blockAt(int)} which bounds-checks)
 * @param biomeSections      biome sections paralleling {@code sections}
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
        if (worldY < minY || worldY > maxY) return null;
        int sy = Math.floorDiv(worldY, 16);
        int ly = Math.floorMod(worldY, 16);
        for (PaletteSection s : sections) {
            if (s.sectionY() == sy) {
                return s.blockIdAt(CENTER_LOCAL_X, ly, CENTER_LOCAL_Z);
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
