package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Block-based mountainside and cliff-edge check over a 5x5-chunk grid around a landing; the
 * shipped paths read its 3x3 ({@link #SQUARE_RADIUS}) (ADR-120, extends ADR-119;
 * REQ-RTP-F-015). Reads blocks only, never generator noise, so it
 * behaves the same on custom generators. One definition for the probe, live and measurement
 * readers (ADR-016 parity).
 *
 * <p>Rule: 8 directions, floors 16 and 32 blocks out searched near the landing Y; a direction is
 * steep at {@code tanMax} or more. Rejects when steep directions are at least half of those
 * measured. Plane fit: least squares over floors within +-8 blocks (step 2); rejects at
 * {@code tanMax} or more, or when over a quarter of its points have no floor (layer break).
 *
 * <p>Only readable samples count, never guessed: a direction whose 32-block sample is unreadable
 * uses the 16-block one alone; with neither it is unmeasured. The rule abstains at 0 measured
 * directions, the plane fit when fewer than half of its points are readable.
 */
public final class TerrainShapeCheck {

  /** Square side in chunks and blocks; the landing chunk spans blocks [32, 48) on both axes. */
  public static final int SQUARE_CHUNKS = 5;
  public static final int SQUARE_SIDE = SQUARE_CHUNKS * 16;
  /** Block offset of the landing chunk's NW corner in square coordinates. */
  public static final int CENTRE_ORIGIN = 32;
  /** Floor search half-window around the landing Y. */
  public static final int LAYER_WINDOW = 48;
  public static final int PLANE_RADIUS = 8;
  public static final int PLANE_STEP = 2;
  public static final int DIRECTIONS = 8;
  /**
   * Chunk radius the shipped check reads: 1 (3x3) at every limit, chosen for cost (ADR-120). On the
   * ADR-119 reference save the 3x3 catches 94% (30 deg) to 98% (45 deg) of the 5x5's rejects at
   * about half the neighbour decodes, and never rejects a landing the 5x5 accepts.
   */
  public static final int SQUARE_RADIUS = 1;

  /** Bound on waiting for a resident neighbour's handle; a late one counts as unreadable. */
  public static final long RESIDENT_WAIT_MS = 5_000L;

  private static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

  private TerrainShapeCheck() {}

  /** Slot of chunk offset (dx, dz) in a 5x5 grid; Pre: {@code |dx|, |dz| <= 2}. */
  public static int gridIndex(int dx, int dz) {
    return (dz + 2) * SQUARE_CHUNKS + dx + 2;
  }

  /**
   * Reader over a 5x5 chunk grid ({@link #gridIndex}); a null slot is unreadable. Floor test:
   * non-air at y-1, air at y, as {@link AbstractVerticalAdjustor#floorReader(RTPChunk)}.
   */
  public static SquareReader chunkSquare(RTPChunk<?>[] grid) {
    return new SquareReader() {
      @Override
      public boolean readable(int sx, int sz) {
        return sx >= 0 && sz >= 0 && sx < SQUARE_SIDE && sz < SQUARE_SIDE
            && grid[(sz >> 4) * SQUARE_CHUNKS + (sx >> 4)] != null;
      }

      @Override
      public boolean isFloor(int sx, int feetY, int sz) {
        RTPChunk<?> c = grid[(sz >> 4) * SQUARE_CHUNKS + (sx >> 4)];
        return !c.isAir(sx & 15, feetY - 1, sz & 15) && c.isAir(sx & 15, feetY, sz & 15);
      }
    };
  }

  /** {@code r} limited to chunks within {@code radius} of the landing chunk. */
  public static SquareReader within(SquareReader r, int radius) {
    if (radius >= 2) return r;
    int lo = CENTRE_ORIGIN - 16 * radius, hi = CENTRE_ORIGIN + 16 + 16 * radius;
    return new SquareReader() {
      @Override
      public boolean readable(int sx, int sz) {
        return sx >= lo && sz >= lo && sx < hi && sz < hi && r.readable(sx, sz);
      }

      @Override
      public boolean mayRead(int sx, int sz) {
        return sx >= lo && sz >= lo && sx < hi && sz < hi && r.mayRead(sx, sz);
      }

      @Override
      public boolean isFloor(int sx, int feetY, int sz) {
        return r.isFloor(sx, feetY, sz);
      }
    };
  }

  /**
   * Reader over a probe and the chunks of its region file within {@code radius}, fetched from
   * {@code square} the first time a column in them is read (ADR-120 on-demand square). A chunk
   * {@code square} cannot supply is unreadable. Floor test as the probe
   * {@code AbstractVerticalAdjustor.floorReader}, so a fully readable square gives the live
   * reader's verdict (ADR-016 parity). Pre: used only while the square's job runs.
   */
  public static SquareReader probeSquare(io.github.dailystruggle.rtp.anvil.ChunkColumnProbe centre,
                                         io.github.dailystruggle.rtp.anvil.ChunkSquare square, int radius) {
    io.github.dailystruggle.rtp.anvil.ChunkColumnProbe[] grid =
        new io.github.dailystruggle.rtp.anvil.ChunkColumnProbe[SQUARE_CHUNKS * SQUARE_CHUNKS];
    boolean[] fetched = new boolean[SQUARE_CHUNKS * SQUARE_CHUNKS];
    grid[gridIndex(0, 0)] = centre;
    fetched[gridIndex(0, 0)] = true;
    int lcx = Math.floorMod(centre.chunkX(), 32), lcz = Math.floorMod(centre.chunkZ(), 32);
    return new SquareReader() {
      @Override
      public boolean mayRead(int sx, int sz) {
        if (sx < 0 || sz < 0 || sx >= SQUARE_SIDE || sz >= SQUARE_SIDE) return false;
        int dx = (sx >> 4) - 2, dz = (sz >> 4) - 2;
        if (Math.abs(dx) > radius || Math.abs(dz) > radius) return false;
        int nx = lcx + dx, nz = lcz + dz;
        return nx >= 0 && nz >= 0 && nx < 32 && nz < 32;
      }

      @Override
      public boolean readable(int sx, int sz) {
        if (!mayRead(sx, sz)) return false;
        int slot = (sz >> 4) * SQUARE_CHUNKS + (sx >> 4);
        if (!fetched[slot]) {
          fetched[slot] = true;
          grid[slot] = (square == null) ? null : square.chunk((sx >> 4) - 2, (sz >> 4) - 2);
        }
        return grid[slot] != null;
      }

      @Override
      public boolean isFloor(int sx, int feetY, int sz) {
        io.github.dailystruggle.rtp.anvil.ChunkColumnProbe p = grid[(sz >> 4) * SQUARE_CHUNKS + (sx >> 4)];
        return !p.isAirAt(sx & 15, sz & 15, feetY - 1) && p.isAirAt(sx & 15, sz & 15, feetY);
      }
    };
  }

  /**
   * The 5x5 square around chunk (ccx, ccz) from chunks already at hand: first {@code local}, the
   * safety grid ({@code L x L}, slot {@code (dx + r) * L + (dz + r)}), then chunks the server
   * holds: {@link RTPWorld#getChunkIfReadable} (ADR-110 read-only lookup) only for chunks
   * {@link RTPWorld#isChunkLoaded} reports resident. Never loads or generates a chunk (S-005); a
   * chunk that is not resident, fails or answers after {@link #RESIDENT_WAIT_MS} stays null
   * (unmeasured, not a failure). Never completes exceptionally. Already complete when the safety
   * grid covers the square.
   */
  public static CompletableFuture<RTPChunk<?>[]> residentSquare(
      RTPWorld<?> world, int ccx, int ccz, RTPChunk<?>[] local, int L) {
    return residentSquare(world, ccx, ccz, local, L, 2);
  }

  /** As above, looking up only chunks within {@code radius} ({@link #SQUARE_RADIUS}). */
  public static CompletableFuture<RTPChunk<?>[]> residentSquare(
      RTPWorld<?> world, int ccx, int ccz, RTPChunk<?>[] local, int L, int radius) {
    RTPChunk<?>[] grid = new RTPChunk<?>[SQUARE_CHUNKS * SQUARE_CHUNKS];
    int r = (L - 1) / 2;
    List<CompletableFuture<Void>> pending = new ArrayList<>();
    for (int dz = -2; dz <= 2; dz++) {
      for (int dx = -2; dx <= 2; dx++) {
        int slot = gridIndex(dx, dz);
        if (Math.abs(dx) <= r && Math.abs(dz) <= r && local != null) {
          grid[slot] = local[(dx + r) * L + (dz + r)];
        }
        if (grid[slot] != null || (dx == 0 && dz == 0) || Math.abs(dx) > radius || Math.abs(dz) > radius) continue;
        int cx = ccx + dx, cz = ccz + dz;
        CompletableFuture<Long> key;
        try {
          if (!world.isChunkLoaded(cx, cz)) continue;
          key = world.getChunkIfReadable(cx, cz);
        } catch (RuntimeException e) {
          continue; // adapter refused the query: unmeasured
        }
        if (key == null) continue;
        pending.add(key.thenApply(k -> k)
            .completeOnTimeout(null, RESIDENT_WAIT_MS, TimeUnit.MILLISECONDS)
            .handle((k, ex) -> {
              if (ex != null || k == null) return null;
              try {
                grid[slot] = world.getCachedChunk(k);
              } catch (RuntimeException e) {
                grid[slot] = null; // unloaded or refused meanwhile: unmeasured
              }
              return null;
            }));
      }
    }
    if (pending.isEmpty()) return CompletableFuture.completedFuture(grid);
    return CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).handle((v, ex) -> grid);
  }

  /**
   * Reads the square; coordinates are blocks relative to its NW corner, {@code 0 <= sx, sz < 80}.
   * Contract: {@link #evaluate} calls {@link #isFloor} only on columns where {@link #readable} is
   * true.
   */
  public interface SquareReader {
    /** True when column (sx, sz) lies in a chunk this reader can supply; may fetch that chunk. */
    boolean readable(int sx, int sz);

    /**
     * Cheap upper bound of {@link #readable}: never fetches. {@link #evaluate} counts directions
     * with it and corrects the count when a fetch fails.
     */
    default boolean mayRead(int sx, int sz) {
      return readable(sx, sz);
    }

    /** True when feet-Y {@code feetY} stands on a floor in column (sx, sz); reads y-1 and y. */
    boolean isFloor(int sx, int feetY, int sz);
  }

  /**
   * {@code measured}: rule directions with a readable sample (from readability alone);
   * {@code steep}: steep directions found before the verdict was decided, exact from
   * {@link #rule}, a lower bound from {@link #evaluate}; {@code grade}: plane gradient (rise per
   * block), {@code +Inf} on a layer break, NaN when not computed or abstained; {@code planeKnown}:
   * the plane fit had enough readable points and ran.
   */
  public record Verdict(int steep, int measured, double grade, boolean planeKnown) {
    public boolean ruleRejects() {
      return measured > 0 && steep * 2 >= measured;
    }

    public boolean reject(double tanMax) {
      return ruleRejects() || (planeKnown && grade >= tanMax);
    }

    /** All 8 directions measured: the verdict matches the full square's rule. */
    public boolean full() {
      return measured == DIRECTIONS;
    }
  }

  /** Tangent of an angle in degrees, as {@code tanMax}; Pre: {@code 0 <= degrees < 90}. */
  public static double tanOf(double degrees) {
    return Math.tan(Math.toRadians(degrees));
  }

  /**
   * Staged check, ordered by chunks touched: the plane fit (centre plus at most 3 neighbours),
   * then the 16-block samples (3x3), then 32-block samples only while they can still change the
   * rule. Stops as soon as the verdict is decided. {@link Verdict#reject} equals the eager
   * {@code rule(..).ruleRejects() || plane >= tanMax}; only {@code steep} may be lower.
   *
   * <p>Pure; reads only through {@code r}; at most 81 + 16 floor searches of
   * {@code 2 * LAYER_WINDOW + 1} tests each.
   *
   * <p>Pre: {@code 0 <= lx, lz <= 15}; {@code minY..maxY} bound feet-Y with y-1 readable.
   */
  public static Verdict evaluate(SquareReader r, int lx, int lz, int y, double tanMax, int minY, int maxY) {
    int x0 = CENTRE_ORIGIN + lx, z0 = CENTRE_ORIGIN + lz;
    // measured starts as an upper bound (mayRead) and drops when a fetch fails. Both early exits
    // stay valid as it drops: a lower measured only eases the reject and keeps an accept.
    boolean[] near = new boolean[DIRECTIONS], far = new boolean[DIRECTIONS], nearRead = new boolean[DIRECTIONS];
    int measured = 0;
    for (int i = 0; i < DIRECTIONS; i++) {
      near[i] = r.mayRead(x0 + DIRS[i][0] * 16, z0 + DIRS[i][1] * 16);
      far[i] = r.mayRead(x0 + DIRS[i][0] * 32, z0 + DIRS[i][1] * 32);
      if (near[i] || far[i]) measured++;
    }
    double g = planeGrade(r, lx, lz, y, minY, maxY);
    boolean planeKnown = !Double.isNaN(g);
    if (planeKnown && g >= tanMax) return new Verdict(0, measured, g, true);
    // undecided: measured directions that may still turn steep; open[i]: 32-block sample pending.
    int steep = 0, undecided = measured;
    boolean[] open = far;
    for (int k = 1; k <= 2; k++) {
      for (int i = 0; i < DIRECTIONS; i++) {
        if (k == 1 ? !near[i] : !open[i]) continue;
        if (!r.readable(x0 + DIRS[i][0] * 16 * k, z0 + DIRS[i][1] * 16 * k)) {
          if (k == 2) open[i] = false;
          if (k == 2 || !open[i]) {
            undecided--;
            if (!nearRead[i]) measured--;
          }
        } else if (isSteep(r, x0, z0, i, k, y, tanMax, minY, maxY)) {
          steep++;
          undecided--;
          open[i] = false;
        } else {
          if (k == 1) nearRead[i] = true;
          if (k == 2 || !open[i]) {
            undecided--;
            open[i] = false;
          }
        }
        if (steep * 2 >= measured || (steep + undecided) * 2 < measured) {
          return new Verdict(steep, measured, g, planeKnown);
        }
      }
    }
    return new Verdict(steep, measured, g, planeKnown);
  }

  /** Sample {@code k} (1: 16 blocks, 2: 32 blocks) of direction {@code i} is steep. */
  private static boolean isSteep(SquareReader r, int x0, int z0, int i, int k, int y, double tanMax,
                                 int minY, int maxY) {
    int sx = x0 + DIRS[i][0] * 16 * k, sz = z0 + DIRS[i][1] * 16 * k;
    int f = floorNear(r, sx, sz, y, minY, maxY);
    double step = 16.0 * Math.hypot(DIRS[i][0], DIRS[i][1]);
    return f == Integer.MIN_VALUE || Math.abs(f - y) >= tanMax * step * k;
  }

  /** Mountainside rule alone; {@code grade} NaN, {@code planeKnown} false. */
  public static Verdict rule(SquareReader r, int lx, int lz, int y, double tanMax, int minY, int maxY) {
    int x0 = CENTRE_ORIGIN + lx, z0 = CENTRE_ORIGIN + lz;
    int steep = 0, measured = 0;
    for (int[] d : DIRS) {
      double step = 16.0 * Math.hypot(d[0], d[1]);
      boolean seen = false;
      for (int k = 1; k <= 2; k++) {
        int sx = x0 + d[0] * 16 * k, sz = z0 + d[1] * 16 * k;
        if (!r.readable(sx, sz)) continue;
        seen = true;
        int f = floorNear(r, sx, sz, y, minY, maxY);
        if (f == Integer.MIN_VALUE || Math.abs(f - y) >= tanMax * step * k) {
          steep++;
          break;
        }
      }
      if (seen) measured++;
    }
    return new Verdict(steep, measured, Double.NaN, false);
  }

  /**
   * Plane-fit gradient over +-{@link #PLANE_RADIUS}: NaN when fewer than half of the points are
   * readable (abstain), {@code +Inf} when over a quarter of the readable points have no floor or
   * the fit is degenerate (counts as steep).
   */
  public static double planeGrade(SquareReader r, int lx, int lz, int y, int minY, int maxY) {
    int x0 = CENTRE_ORIGIN + lx, z0 = CENTRE_ORIGIN + lz;
    int pts = 0, missing = 0, outside = 0;
    double sx = 0, sz = 0, sy = 0, sxx = 0, szz = 0, sxz = 0, sxy = 0, szy = 0;
    for (int dz = -PLANE_RADIUS; dz <= PLANE_RADIUS; dz += PLANE_STEP) {
      for (int dx = -PLANE_RADIUS; dx <= PLANE_RADIUS; dx += PLANE_STEP) {
        if (!r.readable(x0 + dx, z0 + dz)) {
          outside++;
          continue;
        }
        pts++;
        int f = floorNear(r, x0 + dx, z0 + dz, y, minY, maxY);
        if (f == Integer.MIN_VALUE) {
          missing++;
          continue;
        }
        double v = f - y;
        sx += dx;
        sz += dz;
        sy += v;
        sxx += dx * dx;
        szz += dz * dz;
        sxz += dx * dz;
        sxy += dx * v;
        szy += dz * v;
      }
    }
    if (pts < outside) return Double.NaN;
    if (missing * 4 > pts) return Double.POSITIVE_INFINITY;
    double n = pts - missing;
    double cxx = sxx - sx * sx / n, czz = szz - sz * sz / n, cxz = sxz - sx * sz / n;
    double cxy = sxy - sx * sy / n, czy = szy - sz * sy / n;
    double det = cxx * czz - cxz * cxz;
    if (Math.abs(det) < 1e-9) return Double.POSITIVE_INFINITY;
    double b = (cxy * czz - czy * cxz) / det, c = (czy * cxx - cxy * cxz) / det;
    return Math.hypot(b, c);
  }

  /**
   * Floor nearest {@code y} within {@link #LAYER_WINDOW}, up before down at equal distance, so it
   * follows the landing's layer and never reads from the sky; {@code Integer.MIN_VALUE} if none.
   */
  static int floorNear(SquareReader r, int sx, int sz, int y, int minY, int maxY) {
    for (int d = 0; d <= LAYER_WINDOW; d++) {
      int up = y + d;
      if (up >= minY && up <= maxY && r.isFloor(sx, up, sz)) return up;
      int down = y - d;
      if (d > 0 && down >= minY && down <= maxY && r.isFloor(sx, down, sz)) return down;
    }
    return Integer.MIN_VALUE;
  }
}
