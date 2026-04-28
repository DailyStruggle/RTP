package io.github.dailystruggle.rtp.api.world;

import java.util.OptionalInt;

/**
 * Lean view of a single chunk's center column used by the biome-lookup fast path
 * (see {@code docs/dev/BIOME_LOOKUP_PERF_PLAN.md}).
 *
 * <p>Implementations answer block- and biome-identifier queries for the chunk's
 * center column (local {@code x=8, z=8}) over a caller-supplied
 * {@code [minY, maxY]} window, without materialising a full {@link RTPChunk}.
 * The on-disk Anvil fast path is the primary producer; live-world adapters
 * that have a cheaper way to answer point queries may also implement this
 * interface.</p>
 *
 * <p>A probe represents a single chunk at world coordinates
 * ({@link #chunkX()}, {@link #chunkZ()}) and is otherwise stateless —
 * callers may cache it, share it across threads where the producer permits,
 * or discard it immediately after use.</p>
 *
 * <p>This interface lives in {@code rtp-api} so that both {@code rtp-core}
 * consumers (e.g. {@code VerticalAdjustor.adjustFromProbe}) and platform
 * adapters that wrap a module-local probe carrier (e.g. {@code rtp-anvil}'s
 * {@code ColumnProbe}) can reference a single type.</p>
 */
public interface ChunkColumnProbe {

  /**
   * @return chunk X coordinate (world coords divided by 16, floored).
   */
  int chunkX();

  /**
   * @return chunk Z coordinate (world coords divided by 16, floored).
   */
  int chunkZ();

  /**
   * @return inclusive lower world-Y bound of the window this probe was built for.
   */
  int minY();

  /**
   * @return inclusive upper world-Y bound of the window this probe was built for.
   */
  int maxY();

  /**
   * Returns the top-of-solid Y for the center column, sourced from the chunk's
   * {@code MOTION_BLOCKING_NO_LEAVES} heightmap when available.
   *
   * <p>Producers that cannot cheaply obtain a heightmap (e.g. older chunk
   * formats or fabricated probes in tests) may return
   * {@link OptionalInt#empty()}. Callers must treat this as a hint only:
   * a present value is <em>not</em> guaranteed to fall inside
   * {@code [minY, maxY]}, and the authoritative Y must still be validated
   * against {@link #blockAt(int)} / {@link #biomeAt(int)}.</p>
   *
   * @return heightmap-derived top-of-solid Y for the center column, or empty.
   */
  OptionalInt heightmapTopY();

  /**
   * Returns the block identifier at the center column at the given world Y.
   *
   * @param y world Y to query; must satisfy {@code minY <= y <= maxY}.
   * @return namespaced block identifier (e.g. {@code "minecraft:stone"}),
   *     or {@code null} if the window does not cover {@code y} or the
   *     producer has no answer for that cell.
   */
  String blockAt(int y);

  /**
   * Returns the block identifier at chunk-local column {@code (localX, localZ)}
   * at the given world Y.
   *
   * <p>The default implementation delegates to {@link #blockAt(int)} regardless
   * of {@code localX}/{@code localZ}, preserving back-compat for legacy
   * single-column producers (the adjustor probe path then evaluates the same
   * data five times instead of five distinct columns — semantically a no-op
   * vs. the pre-multi-column behaviour). Producers whose backing data covers
   * the full chunk (e.g. {@code AnvilColumnProbeAdapter}) override to return
   * the actual off-center palette identifier so the probe-side
   * {@code adjustFromProbeWithReason} sweep can scan all live-path
   * {@code testCoords} columns and so {@code SCAN_MISS} becomes authoritative.</p>
   *
   * @param localX chunk-local X in {@code [0..15]}.
   * @param localZ chunk-local Z in {@code [0..15]}.
   * @param y      world Y to query; must satisfy {@code minY <= y <= maxY}.
   * @return namespaced block identifier or {@code null} as in {@link #blockAt(int)}.
   */
  default String blockAt(int localX, int localZ, int y) {
    return blockAt(y);
  }

  /**
   * Returns the biome identifier at the center column at the given world Y.
   *
   * @param y world Y to query; must satisfy {@code minY <= y <= maxY}.
   * @return namespaced biome identifier (e.g. {@code "minecraft:plains"}),
   *     or {@code null} if the window does not cover {@code y} or the
   *     producer has no answer for that cell.
   */
  String biomeAt(int y);

  /**
   * Convenience: whether the block at the given Y on the center column is
   * considered air.
   *
   * <p>The default implementation tests for any namespaced {@code *:air}
   * identifier ({@code minecraft:air}, {@code minecraft:cave_air},
   * {@code minecraft:void_air}). Producers with a cheaper representation
   * (e.g. direct palette-index comparison) may override.</p>
   *
   * @param y world Y to query.
   * @return true iff {@link #blockAt(int)} returns a non-null identifier
   *     whose path segment is {@code "air"}.
   */
  default boolean isAirAt(int y) {
    String b = blockAt(y);
    if (b == null) return false;
    int colon = b.indexOf(':');
    String path = (colon >= 0) ? b.substring(colon + 1) : b;
    return path.equals("air") || path.equals("cave_air") || path.equals("void_air");
  }

  /**
   * Convenience: whether the block at chunk-local column {@code (localX, localZ)}
   * at the given Y is considered air. Default delegates to
   * {@link #blockAt(int, int, int)} and applies the same path-segment test as
   * {@link #isAirAt(int)}. Producers that override {@link #isAirAt(int)} for a
   * cheaper representation should also override this overload.
   *
   * @param localX chunk-local X in {@code [0..15]}.
   * @param localZ chunk-local Z in {@code [0..15]}.
   * @param y      world Y to query.
   */
  default boolean isAirAt(int localX, int localZ, int y) {
    String b = blockAt(localX, localZ, y);
    if (b == null) return false;
    int colon = b.indexOf(':');
    String path = (colon >= 0) ? b.substring(colon + 1) : b;
    return path.equals("air") || path.equals("cave_air") || path.equals("void_air");
  }

  /**
   * Whether the on-disk light data backing {@link #skyLightAt(int)} is authoritative
   * for this chunk.
   *
   * <p>Mirrors the vanilla chunk NBT {@code isLightOn} flag: when {@code false}, the
   * lighting engine has not (yet) produced a valid sky-light nibble array for the
   * chunk, and callers that need a lighting answer must fall back to the
   * authoritative path (i.e. load the chunk and let the server finish lighting).
   * Producers that did not retain the flag, or that do not source sky-light from a
   * save-file at all, should return {@code true} — the contract is that a {@code
   * true} return means {@link #skyLightAt(int)} answers can be trusted.</p>
   *
   * <p>Default: {@code true}. Producers that conditionally retain sky-light (e.g.
   * only when {@code requireSkyLight} is configured) should still return {@code true}
   * here; the no-sky-light path is signalled by {@link #skyLightAt(int)} returning
   * its "unknown / open" default (15).</p>
   *
   * @return true iff {@link #skyLightAt(int)} values are trustworthy for this chunk.
   */
  default boolean isLightOn() {
    return true;
  }

  /**
   * Returns the sky-light level at the center column at the given world Y, in the
   * vanilla {@code 0..15} range. A return of {@code 15} means "fully lit / sees the
   * sky"; lower values mean sky-light is attenuated or absent.
   *
   * <p>Producers that do not retain sky-light data (the default, cheapest probe)
   * return {@code 15} for every in-window Y — semantically equivalent to a vanilla
   * section with no {@code SkyLight} tag, which the vanilla lighting engine treats
   * as fully lit. Callers that require a real sky-light answer must gate on
   * {@link #isLightOn()} first.</p>
   *
   * <p>Out-of-window Ys also return {@code 15} (rather than throwing) so that
   * one-off queries at {@code y+1} near the top of the window don't need a separate
   * bounds check; callers that need strict bounds enforcement should compare
   * against {@link #minY()} / {@link #maxY()} explicitly.</p>
   *
   * @param y world Y to query.
   * @return sky-light level in {@code 0..15}.
   */
  default int skyLightAt(int y) {
    return 15;
  }

  /**
   * Sky-light level at chunk-local column {@code (localX, localZ)} at the given Y.
   * Default delegates to {@link #skyLightAt(int)} (center column) for back-compat
   * with single-column producers; producers that retain per-column sky-light
   * data should override.
   */
  default int skyLightAt(int localX, int localZ, int y) {
    return skyLightAt(y);
  }
}
