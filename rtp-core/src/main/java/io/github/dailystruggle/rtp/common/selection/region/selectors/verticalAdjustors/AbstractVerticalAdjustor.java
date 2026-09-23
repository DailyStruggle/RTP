package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors;

import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Common base for vertical adjustors performing 3D block search, safety probing, and sky-light gating.
 */
public abstract class AbstractVerticalAdjustor<T extends Enum<T>> extends VerticalAdjustor<T> {

  protected static final List<List<Integer>> TEST_COORDS =
      Arrays.asList(
          Arrays.asList(7, 7),
          Arrays.asList(2, 2),
          Arrays.asList(12, 12),
          Arrays.asList(2, 12),
          Arrays.asList(12, 2));

  protected AbstractVerticalAdjustor(
      Class<T> clazz, String name, List<Predicate<RTPCoords>> verifiers, EnumMap<T, Object> data) {
    super(clazz, name, verifiers, data);
  }

  @Override
  public @Nullable RTPCoords adjust(@NotNull RTPChunk chunk) {
    MutableRTPCoords output = new MutableRTPCoords(chunk.getWorld().name(), 0, 0, 0);
    if (adjust(chunk, output)) return output.toImmutable();
    return null;
  }

  @Override
  public boolean testPlacement(@NotNull RTPCoords coords) {
    for (int i = 0; i < verifiers.size(); i++) {
      Predicate<RTPCoords> rtpLocationPredicate = verifiers.get(i);
      if (!rtpLocationPredicate.test(coords)) return false;
    }
    return true;
  }

  /**
   * Sweeps {@code chunk.isSafe} across {@code [1..platformDepth]} cells below the
   * candidate feet-Y.
   */
  @SuppressWarnings("unchecked")
  protected static boolean isGroundSafe(
      RTPChunk chunk, int x, int y, int z, Set<String> unsafeBlocks, int platformDepth) {
    int depth = Math.max(1, platformDepth);
    for (int d = 1; d <= depth; d++) {
      if (!chunk.isSafe(x, y - d, z, unsafeBlocks)) return false;
    }
    return true;
  }

  /**
   * Returns highest non-air Y on column {@code (x, z)} derived from block data.
   * Any {@code y+1 > floor} has unobstructed sky access, avoiding stale light nibbles.
   * Returns {@link Integer#MIN_VALUE} if column is entirely air.
   */
  protected static int computeColumnSkyFloor(RTPChunk chunk, int x, int z) {
    int top = chunk.getWorld().getMaxHeight() - 1;
    int bottom = chunk.getWorld().getMinHeight();
    for (int y = top; y >= bottom; y--) {
      if (!chunk.isAir(x, y, z)) return y;
    }
    return Integer.MIN_VALUE;
  }

  /**
   * Returns highest non-air Y on column {@code (lx, lz)} within probe window.
   * Any {@code y+1 > floor} has unobstructed sky access, avoiding stale light nibbles.
   * Returns {@link Integer#MIN_VALUE} if column is entirely air.
   */
  protected static int computeColumnSkyFloor(ChunkColumnProbe probe, int lx, int lz) {
    int top = probe.maxY();
    int bottom = probe.minY();
    for (int y = top; y >= bottom; y--) {
      if (!probe.isAirAt(lx, lz, y)) return y;
    }
    return Integer.MIN_VALUE;
  }

  /**
   * Canonicalise an identifier or material token into uppercase, namespace-stripped form.
   */
  public static String canon(String id) {
    if (id == null) return null;
    String s = id.trim();
    if (s.isEmpty()) return null;
    if (s.charAt(0) == '#') return s.toUpperCase(java.util.Locale.ROOT);
    int colon = s.indexOf(':');
    String local = (colon >= 0) ? s.substring(colon + 1) : s;
    int bracket = local.indexOf('[');
    if (bracket >= 0) local = local.substring(0, bracket);
    return local.toUpperCase(java.util.Locale.ROOT);
  }
}
