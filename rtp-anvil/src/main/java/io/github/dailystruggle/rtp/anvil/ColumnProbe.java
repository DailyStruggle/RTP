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
 * non-relevant section children ({@code BlockLight} and — when the caller did not
 * request sky-light — {@code SkyLight}) are never materialised. For center-column
 * queries — the only queries this probe answers — this yields the same answers
 * {@link AnvilChunkView#blockIdAt}/{@link AnvilChunkView#getBiomeAt} would at
 * {@code (8, y, 8)}, while doing substantially less allocation and NBT-parse work.
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
 * @param isLightOn          mirrors the vanilla chunk NBT {@code isLightOn} flag; when
 *                           {@code false} the {@code SkyLight} nibble arrays on
 *                           {@code sections} are stale and {@link #skyLightAt(int)}
 *                           values should not be trusted. When the probe was built
 *                           with {@code includeSkyLight=false} this field is
 *                           {@code true} by convention (callers must not rely on
 *                           sky-light in that case).
 */
public record ColumnProbe(int minY,
                          int maxY,
                          int heightmapTopY,
                          List<PaletteSection> sections,
                          List<BiomePaletteSection> biomeSections,
                          boolean isLightOn) {

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
     * Back-compat constructor for probes built without a sky-light pass. Defaults
     * {@code isLightOn} to {@code true} — a producer that did not request sky-light
     * must leave {@link PaletteSection#skyLight()} null in every section, so
     * {@link #skyLightAt(int)} returns the "open" default (15) regardless.
     */
    public ColumnProbe(int minY,
                       int maxY,
                       int heightmapTopY,
                       List<PaletteSection> sections,
                       List<BiomePaletteSection> biomeSections) {
        this(minY, maxY, heightmapTopY, sections, biomeSections, true);
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
     * Returns the sky-light level at center-column world-Y {@code worldY} in {@code 0..15}.
     *
     * <p>If the probe was built without {@code includeSkyLight}, the underlying
     * {@link PaletteSection#skyLight()} arrays are null and this method returns
     * {@code 15} (vanilla "absent tag means fully lit" convention). Callers that need
     * a trusted sky-light answer must first check {@link #isLightOn()}.</p>
     *
     * <p>Out-of-window Ys return {@code 15} rather than throwing, matching the
     * {@code ChunkColumnProbe.skyLightAt(int)} contract in {@code rtp-api}.</p>
     */
    public int skyLightAt(int worldY) {
        return skyLightAt(CENTER_LOCAL_X, CENTER_LOCAL_Z, worldY);
    }

    /**
     * Sky-light level at chunk-local column {@code (localX, localZ)} at world-Y
     * {@code worldY}, in the vanilla {@code 0..15} range. Same semantics as
     * {@link #skyLightAt(int)}; off-center cells are read directly from the
     * underlying {@link PaletteSection#skyLight()} nibble array (when present),
     * with no extra allocation or parse cost.
     */
    public int skyLightAt(int localX, int localZ, int worldY) {
        if (worldY < minY || worldY > maxY) return 15;
        int sy = Math.floorDiv(worldY, 16);
        int ly = Math.floorMod(worldY, 16);
        for (PaletteSection s : sections) {
            if (s.sectionY() == sy) {
                return s.skyLightAt(localX, ly, localZ);
            }
        }
        return 15;
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
