package io.github.dailystruggle.rtp.common.selection.model;

/**
 * Why a candidate location was rejected.
 *
 * <p>Tier 1 (ADR-115): shapes, TTL policy and selection all record causes, so the enum has no
 * dependency on any other {@code rtp-core} package. Constant order is persisted: spatial memory
 * stores {@link #ordinal()} as a byte per bad-location run, so add new constants only at the end
 * and never reorder or remove one.
 */
public enum FailTypes {
    biome,
    worldBorder,
    timeout,
    vert,
    safety,
    safetyExternal,
    nullChunk,
    /** Probe fast path rejected the candidate because the center-column biome
     * didn't match the allow/deny filter. Accounts probe-driven biome misses
     * separately from post-chunk-load {@link #biome} misses so operators can
     * see how much work the fast path is saving. */
    prefilterBiome,
    /** Probe fast path rejected the candidate because the center-column block
     * at the selected Y was unsafe (water / lava / caller-configured unsafe set). */
    prefilterBlock,
    /** Probe fast path rejected the candidate because the vertical adjustor
     * found no acceptable Y within the probe's window (either the heightmap
     * was outside {@code [minY, maxY]} or every Y-step failed the adjustor's
     * placement test). */
    prefilterRange,
    /** Candidate chunk's column is not yet generated on disk and the
     * configured {@code PerformanceKeys.pregeneratedPreference} weight
     * drew a reject this attempt. Tracked separately so operators can see
     * how often the pregenerated-area bias dropped a candidate vs. the
     * other reject buckets. */
    ungenerated,
    /** Candidate location (and, for a {@code uniquePlacements} radius &gt; 1,
     * the surrounding chunks) was marked bad to enforce unique placements:
     * a successfully-used spot is consumed so the same area is not handed
     * out again. Tracked separately from {@link #misc} so operators can see
     * how much of the region the unique-placements knob has retired. */
    uniquePlacement,
    misc,
    /** Live recheck over the server-resident 5x5-chunk square found a mountainside or cliff edge
     * steeper than the vert's {@code maxHillAngle} (ADR-120). Also the spatial-memory cause for
     * terrain verdicts that measured all 8 directions. */
    terrain,
    /** Probe fast path rejected the candidate on a provisional terrain verdict, read from the
     * chunks of one region file (ADR-120). Counted apart from {@link #terrain} so operators can
     * see how much the pre-compute saves. */
    prefilterTerrain
}
