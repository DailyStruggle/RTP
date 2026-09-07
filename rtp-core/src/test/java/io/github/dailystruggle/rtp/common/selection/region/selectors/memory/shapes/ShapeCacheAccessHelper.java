package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

/**
 * Test helper for inspecting internal cache arrays in {@link MemoryShape}.
 */
public final class ShapeCacheAccessHelper {
  private ShapeCacheAccessHelper() {}

  public static int getBadRunsCount(MemoryShape<?> shape) {
    return shape.badKeysCache.length;
  }

  public static long getBadSum(MemoryShape<?> shape) {
    long[] sums = shape.badPrefixSumsCache;
    return (sums.length > 0) ? sums[sums.length - 1] : 0L;
  }

  public static void flushAndRebuild(MemoryShape<?> shape) {
    shape.flushAndRebuild(1L);
  }

  public static void flushAndRebuild(MemoryShape<?> shape, long resolution) {
    shape.flushAndRebuild(resolution);
  }

  public static void runPolygonMaskWalker(Polygon polygon) {
    polygon.runMaskWalker();
  }
}
