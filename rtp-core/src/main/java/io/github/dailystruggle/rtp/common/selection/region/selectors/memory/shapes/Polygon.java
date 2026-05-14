package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/**
 * Admin-authored closed polygon memory shape.
 *
 * <p>Implementation per ADR-034:
 * <ul>
 *   <li>Extends {@link Square}; the inherited square is sized to the polygon's
 *       axis-aligned bounding box and carries the spiral index, segmented
 *       bad-locations store, persistence, uniqueness, cache-key contribution
 *       (ADR-022), and registry integration unchanged.</li>
 *   <li>Vertices are stored as a list of {@code int[]{x, z}} in traversal order;
 *       the polygon is implicitly closed.</li>
 *   <li>Self-intersecting vertex lists are rejected at {@link #setVertices(List)}
 *       time via a naive O(n^2) edge-pair check; concave polygons are accepted.</li>
 *   <li>{@code expand} is forced to {@code false} on every {@link #rand()} call
 *       regardless of any value carried in the underlying data map; a warning is
 *       logged once if a config attempts to enable it.</li>
 *   <li>An async curve-walker is scheduled at vertex-assignment time iff the
 *       segmented bad-locations store is empty; it walks the inner Square's
 *       spiral and emits per-column outside-polygon entries via
 *       {@link #addBadLocation(long)}. The walker self-cancels the moment any
 *       path adds an entry (prior session's serialized mask or runtime
 *       safety/biome discoveries).</li>
 * </ul>
 */
public class Polygon extends Square {

  /** Vertex list. Each element is {@code int[]{x, z}}. Empty until {@link #setVertices(List)}. */
  private volatile List<int[]> vertices = Collections.emptyList();

  /** AABB bounds derived from {@link #vertices}, in absolute world coordinates. */
  private volatile int minX = 0;
  private volatile int maxX = 0;
  private volatile int minZ = 0;
  private volatile int maxZ = 0;

  /** Set true once the {@code expand=true} misconfiguration warning has been emitted. */
  private volatile boolean expandWarningLogged = false;

  /** Set true when the async curve-walker has been scheduled at least once. */
  private volatile boolean walkerScheduled = false;

  public Polygon() {
    super("POLYGON");
    // Polygons must not expand: the boundary is admin-authored, not parametric.
    data.put(GenericMemoryShapeParams.expand, false);
    data.put(GenericMemoryShapeParams.centerRadius, 0);
  }

  public Polygon(String newName) {
    super(newName);
    data.put(GenericMemoryShapeParams.expand, false);
    data.put(GenericMemoryShapeParams.centerRadius, 0);
  }

  /**
   * Install the polygon's vertex list, derive the AABB, mirror it onto the
   * inherited Square ({@code radius}, {@code centerX}, {@code centerZ}), validate
   * for self-intersection, and schedule the async curve-walker if the segmented
   * bad-locations store is empty.
   *
   * @param newVertices vertex list (each entry is {@code int[]{x, z}}); must contain
   *     at least 3 entries.
   * @throws IllegalArgumentException if the vertex list has fewer than 3 vertices,
   *     is collinear, or self-intersects (the error names the offending edge pair).
   */
  public void setVertices(List<int[]> newVertices) {
    if (newVertices == null || newVertices.size() < 3) {
      throw new IllegalArgumentException(
          "Polygon requires at least 3 vertices (got " + (newVertices == null ? 0 : newVertices.size()) + ")");
    }

    // Defensive copy.
    List<int[]> copy = new ArrayList<>(newVertices.size());
    for (int[] v : newVertices) {
      if (v == null || v.length < 2) {
        throw new IllegalArgumentException("Polygon vertex must be int[]{x, z}");
      }
      copy.add(new int[] {v[0], v[1]});
    }

    // Reject self-intersection.
    int firstA = -1, firstB = -1;
    final int n = copy.size();
    outer:
    for (int i = 0; i < n; i++) {
      int[] a1 = copy.get(i);
      int[] a2 = copy.get((i + 1) % n);
      for (int j = i + 1; j < n; j++) {
        // Skip adjacent edges (they share an endpoint by construction).
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

    // Compute AABB.
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

    // Mirror AABB onto inherited Square: half-extents and AABB center.
    long halfX = ((long) hx - (long) lx + 1) / 2;
    long halfZ = ((long) hz - (long) lz + 1) / 2;
    long radius = Math.max(halfX, halfZ);
    long centerX = ((long) lx + (long) hx) / 2;
    long centerZ = ((long) lz + (long) hz) / 2;

    data.put(GenericMemoryShapeParams.radius, (int) radius);
    data.put(GenericMemoryShapeParams.centerX, (int) centerX);
    data.put(GenericMemoryShapeParams.centerZ, (int) centerZ);
    data.put(GenericMemoryShapeParams.centerRadius, 0);
    data.put(GenericMemoryShapeParams.expand, false);

    // Schedule the async curve-walker iff the segmented bad-locations store is
    // currently empty. A prior session's serialized mask or runtime discoveries
    // that already populated the store satisfy the inference rule from ADR-034
    // and the walker is skipped.
    scheduleMaskWalkerIfEmpty();
  }

  /** Returns the immutable vertex list. */
  public List<int[]> getVertices() {
    return vertices;
  }

  /**
   * Hook used by {@link #setVertices(List)} and by {@code load()} extension points
   * to spawn the async curve-walker only when the bad-locations store is empty.
   */
  protected void scheduleMaskWalkerIfEmpty() {
    if (walkerScheduled) return;
    if (vertices.isEmpty()) return;
    if (!isBadLocationsEmpty()) return;
    if (RTP.scheduler == null) {
      // Test or pre-bootstrap context: fall back to per-sample predicate only.
      return;
    }
    walkerScheduled = true;
    try {
      RTP.scheduler.runTaskAsynchronously(this::runMaskWalker);
    } catch (Throwable t) {
      // Best-effort; never let a scheduling failure escape into the caller of setVertices.
      RTP.log(Level.WARNING, "[Polygon] failed to schedule async mask walker: " + t.getMessage(), t);
      walkerScheduled = false;
    }
  }

  /** True iff no bad-locations are currently recorded (pending or merged caches). */
  private boolean isBadLocationsEmpty() {
    if (badKeysCache.length > 0) return false;
    return pendingBadLocations.get().isEmpty();
  }

  /**
   * Async curve-walker. Walks the inner Square's spiral index space, evaluates the
   * polygon predicate for each column, and batches outside-polygon indices into
   * {@link #addBadLocation(long)} calls. Yields periodically to avoid starving the
   * async pool. Self-cancels the moment any external path adds a bad-location entry.
   */
  protected void runMaskWalker() {
    final long range = getRange();
    final MutableRTPCoords coords = new MutableRTPCoords(0, 0);
    final int batchSize = 4096;
    long lastObservedBadCount = 0L;
    int sinceYield = 0;

    for (long i = 0; i < range; i++) {
      // Cancellation check: if any entry has been added since we started walking
      // and it isn't from us (i.e., the bad count grew faster than our walk), bail.
      // We approximate "is the store still ours" by snapshotting pending size.
      if (sinceYield == 0) {
        long currentBadCount = pendingBadLocations.get().size() + badKeysCache.length;
        if (currentBadCount > lastObservedBadCount + batchSize) {
          // External writer interleaved with our batch — yield authority.
          return;
        }
        lastObservedBadCount = currentBadCount;
      }

      try {
        locationToXZ(i, coords);
      } catch (Throwable t) {
        // Malformed spiral index for the inherited Square; skip.
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
  public long rand() {
    // Force expand=false defensively on every call: the boundary is admin-authored
    // and the segmented bad-locations store contains the polygon mask, which the
    // expand path would happily push past.
    Object existing = data.get(GenericMemoryShapeParams.expand);
    if (existing instanceof Boolean && (Boolean) existing) {
      if (!expandWarningLogged) {
        expandWarningLogged = true;
        RTP.log(Level.WARNING,
            "[Polygon] expand=true is not supported on Polygon shapes; forcing expand=false");
      }
      data.put(GenericMemoryShapeParams.expand, false);
    } else if (existing != null && !(existing instanceof Boolean)
        && Boolean.parseBoolean(existing.toString())) {
      if (!expandWarningLogged) {
        expandWarningLogged = true;
        RTP.log(Level.WARNING,
            "[Polygon] expand=true is not supported on Polygon shapes; forcing expand=false");
      }
      data.put(GenericMemoryShapeParams.expand, false);
    }

    long location = super.rand();

    // Warmup-window predicate: until the async walker has fully populated the
    // segmented store, the sampling path tie-breaks via the point-in-polygon
    // predicate so admins never see an outside-polygon sample.
    if (location < 0 || vertices.isEmpty()) return location;
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);
    try {
      locationToXZ(location, coords);
    } catch (Throwable t) {
      return location;
    }
    if (!pointInPolygon(coords.x, coords.z)) {
      addBadLocation(location);
      return -1;
    }
    return location;
  }

  // ---------------------------------------------------------------------------
  // Polygon geometry helpers.
  // ---------------------------------------------------------------------------

  /**
   * Standard ray-cast point-in-polygon test (counts edge crossings to the right
   * of {@code (x, z)}). Handles concave polygons natively. Returns {@code true}
   * for points strictly inside the polygon; boundary behavior is consistent but
   * not specified beyond "one and only one of two adjacent inside/outside calls
   * for a boundary point will return true", matching the well-known convention.
   */
  public boolean pointInPolygon(int x, int z) {
    final List<int[]> verts = vertices;
    final int n = verts.size();
    if (n < 3) return true;
    boolean inside = false;
    for (int i = 0, j = n - 1; i < n; j = i++) {
      int[] vi = verts.get(i);
      int[] vj = verts.get(j);
      int xi = vi[0], zi = vi[1];
      int xj = vj[0], zj = vj[1];
      boolean straddles = (zi > z) != (zj > z);
      if (!straddles) continue;
      // Compute intersection of the horizontal ray with the edge.
      double xIntersect = (double) (xj - xi) * (double) (z - zi) / (double) (zj - zi) + xi;
      if (x < xIntersect) inside = !inside;
    }
    return inside;
  }

  /**
   * Returns true iff segments {@code a1->a2} and {@code b1->b2} cross each other
   * in their interiors (i.e., a proper intersection). Collinear overlaps and
   * shared-endpoint touches are not reported as intersections.
   */
  private static boolean segmentsProperlyIntersect(int[] a1, int[] a2, int[] b1, int[] b2) {
    long d1 = cross(b1, b2, a1);
    long d2 = cross(b1, b2, a2);
    long d3 = cross(a1, a2, b1);
    long d4 = cross(a1, a2, b2);
    return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
        && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
  }

  /** 2D cross product of vectors {@code (a->b)} and {@code (a->c)}. */
  private static long cross(int[] a, int[] b, int[] c) {
    long abx = (long) b[0] - (long) a[0];
    long abz = (long) b[1] - (long) a[1];
    long acx = (long) c[0] - (long) a[0];
    long acz = (long) c[1] - (long) a[1];
    return abx * acz - abz * acx;
  }

  @Override
  public java.util.Collection<String> keys() {
    // Reuse Square's key set; polygon-specific 'vertices' is handled outside the
    // generic CommandParameter surface (it's a structured config value).
    return super.keys();
  }
}
