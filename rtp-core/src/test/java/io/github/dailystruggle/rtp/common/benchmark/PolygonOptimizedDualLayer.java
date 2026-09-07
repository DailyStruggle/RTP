package io.github.dailystruggle.rtp.common.benchmark;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/**
 * Closed polygon memory shape implemented over the continuous spiral-addressed Hilbert
 * key space and segmented secondary tables (ADR-085).
 *
 * <p>Extends {@link SquareOptimizedDualLayer} bounded by the polygon's axis-aligned bounding box.
 * Outside-polygon points naturally coalesce into contiguous 2D Hilbert runs.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
public class PolygonOptimizedDualLayer extends SquareOptimizedDualLayer {

  public int getBadRunsCount() {
    return badKeysCache.length;
  }

  public void forceMaskWalker() {
    runMaskWalker();
  }

  public void forceFlushAndRebuild() {
    flushAndRebuild(1L);
  }

  public void forceFlushAndRebuild(long res) {
    flushAndRebuild(res);
  }

  private volatile List<int[]> vertices = Collections.emptyList();
  private volatile int minX = 0;
  private volatile int maxX = 0;
  private volatile int minZ = 0;
  private volatile int maxZ = 0;
  private volatile boolean walkerScheduled = false;

  public PolygonOptimizedDualLayer() {
    this("POLYGON_OPTIMIZED_DUAL_LAYER", 32);
  }

  public PolygonOptimizedDualLayer(String name) {
    this(name, 32);
  }

  public PolygonOptimizedDualLayer(String name, int pointEdgeChunks) {
    super(name, pointEdgeChunks);
    data.put(GenericMemoryShapeParams.expand, false);
    data.put(GenericMemoryShapeParams.centerRadius, 0);
  }

  public void setVertices(List<int[]> newVertices) {
    if (newVertices == null || newVertices.size() < 3) {
      throw new IllegalArgumentException(
          "Polygon requires at least 3 vertices (got " + (newVertices == null ? 0 : newVertices.size()) + ")");
    }

    List<int[]> copy = new ArrayList<>(newVertices.size());
    for (int[] v : newVertices) {
      if (v == null || v.length < 2) {
        throw new IllegalArgumentException("Polygon vertex must be int[]{x, z}");
      }
      copy.add(new int[] {v[0], v[1]});
    }

    int firstA = -1, firstB = -1;
    final int n = copy.size();
    outer:
    for (int i = 0; i < n; i++) {
      int[] a1 = copy.get(i);
      int[] a2 = copy.get((i + 1) % n);
      for (int j = i + 1; j < n; j++) {
        if (j == i) continue;
        if ((j + 1) % n == i) continue;
        if (i == 0 && j == n - 1) continue;
        int[] b1 = copy.get(j);
        int[] b2 = copy.get((j + 1) % n);
        if (segmentsProperlyIntersect(a1, a2, b1, b2)) {
          firstA = i;
          firstB = j;
          break outer;
        }
      }
    }
    if (firstA >= 0) {
      throw new IllegalArgumentException(
          "Polygon is self-intersecting: edge " + firstA + "-" + ((firstA + 1) % n)
              + " crosses edge " + firstB + "-" + ((firstB + 1) % n));
    }

    int lx = Integer.MAX_VALUE, lz = Integer.MAX_VALUE;
    int hx = Integer.MIN_VALUE, hz = Integer.MIN_VALUE;
    for (int[] v : copy) {
      if (v[0] < lx) lx = v[0];
      if (v[0] > hx) hx = v[0];
      if (v[1] < lz) lz = v[1];
      if (v[1] > hz) hz = v[1];
    }
    if (lx == hx || lz == hz) {
      throw new IllegalArgumentException(
          "Polygon is degenerate (collinear vertices: AABB has zero extent on one axis)");
    }

    this.minX = lx;
    this.maxX = hx;
    this.minZ = lz;
    this.maxZ = hz;
    this.vertices = Collections.unmodifiableList(copy);

    int width = hx - lx + 1;
    int height = hz - lz + 1;
    int halfExtent = (Math.max(width, height) + 1) / 2;
    int cx = (lx + hx) / 2;
    int cz = (lz + hz) / 2;

    data.put(GenericMemoryShapeParams.radius, halfExtent);
    data.put(GenericMemoryShapeParams.centerRadius, 0);
    data.put(GenericMemoryShapeParams.centerX, cx);
    data.put(GenericMemoryShapeParams.centerZ, cz);
    data.put(GenericMemoryShapeParams.expand, false);

    scheduleMaskWalkerIfEmpty();
  }

  public List<int[]> getVertices() {
    return vertices;
  }

  protected void scheduleMaskWalkerIfEmpty() {
    if (walkerScheduled) return;
    if (vertices.isEmpty()) return;
    if (!isBadLocationsEmpty()) return;
    if (RTP.scheduler == null) return;

    walkerScheduled = true;
    try {
      RTP.scheduler.runTaskAsynchronously(this::runMaskWalker);
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[PolygonOptimizedDualLayer] failed to schedule async mask walker: " + t.getMessage(), t);
      walkerScheduled = false;
    }
  }

  private boolean isBadLocationsEmpty() {
    if (badKeysCache.length > 0) return false;
    return pendingBadLocations.get().isEmpty();
  }

  protected void runMaskWalker() {
    final long range = getRange();
    final MutableRTPCoords coords = new MutableRTPCoords(0, 0);
    final int batchSize = 4096;
    long lastObservedBadCount = 0L;
    int sinceYield = 0;

    for (long i = 0; i < range; i++) {
      if (sinceYield == 0) {
        long currentBadCount = pendingBadLocations.get().size() + badKeysCache.length;
        if (currentBadCount > lastObservedBadCount + batchSize) {
          return;
        }
        lastObservedBadCount = currentBadCount;
      }

      try {
        locationToXZ(i, coords);
      } catch (Throwable t) {
        continue;
      }
      if (!pointInPolygon(coords.x, coords.z)) {
        addBadLocation(i);
      }

      sinceYield++;
      if (sinceYield >= batchSize) {
        sinceYield = 0;
        try {
          Thread.sleep(0L, 1);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  @Override
  public boolean contains(int x, int z) {
    if (!super.contains(x, z)) return false;
    if (vertices.isEmpty()) return true;
    if (x < minX || x > maxX || z < minZ || z > maxZ) return false;
    return pointInPolygon(x, z);
  }

  @Override
  protected boolean supportsExpand() {
    return false;
  }

  @Override
  protected long postProcess(long location) {
    if (location < 0 || vertices.isEmpty()) return location;
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);
    try {
      locationToXZ(location, coords);
    } catch (Throwable t) {
      return location;
    }
    if (!pointInPolygon(coords.x, coords.z)) {
      addBadLocation(location);
      return -1L;
    }
    return location;
  }

  public boolean pointInPolygon(int x, int z) {
    if (vertices.isEmpty()) return false;
    int n = vertices.size();
    boolean inside = false;
    for (int i = 0, j = n - 1; i < n; j = i++) {
      int[] vi = vertices.get(i);
      int[] vj = vertices.get(j);
      int xi = vi[0], zi = vi[1];
      int xj = vj[0], zj = vj[1];
      boolean intersect = ((zi > z) != (zj > z))
          && ((long) x < (long) (xj - xi) * (z - zi) / (zj - zi) + xi);
      if (intersect) {
        inside = !inside;
      }
    }
    return inside;
  }

  private static boolean segmentsProperlyIntersect(int[] a, int[] b, int[] c, int[] d) {
    long cp1 = crossProduct(a, b, c);
    long cp2 = crossProduct(a, b, d);
    long cp3 = crossProduct(c, d, a);
    long cp4 = crossProduct(c, d, b);

    if (((cp1 > 0 && cp2 < 0) || (cp1 < 0 && cp2 > 0))
        && ((cp3 > 0 && cp4 < 0) || (cp3 < 0 && cp4 > 0))) {
      return true;
    }
    return false;
  }

  private static long crossProduct(int[] p1, int[] p2, int[] p3) {
    long x1 = (long) p2[0] - p1[0];
    long z1 = (long) p2[1] - p1[1];
    long x2 = (long) p3[0] - p1[0];
    long z2 = (long) p3[1] - p1[1];
    return x1 * z2 - z1 * x2;
  }
}
