package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Consequences of each candidate {@code computeAdmissibleGap} rule, on one terrain, as a chart.
 *
 * <p>Every rule trades table size against discarded usable ground. Neither number decides anything
 * alone, so the comparison drawn here is the frontier each rule traces as {@code
 * spatialResolution} sweeps: runs on one axis, loss on the other. A rule is better only when its
 * curve lies below and left of another's over the operating range, and the chart is the artifact
 * that shows whether that is true rather than asserted.
 *
 * <p>Flat loss counts discarded usable chunks. Weighted loss prices shore beside a large water
 * body below interior ground, because a rule that spends its loss on beaches is cheaper than one
 * spending the same loss inland. Both are drawn; the rules reorder between them, which is the
 * whole reason the choice was unclear.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier.
 */
@Tag("simulation")
@DisplayName("admissible gap policy - size versus discarded ground")
public class AdmissibleGapPolicyBenchmarkTest {

  private static final long SEED = 20260911L;

  private static final int RADIUS_CHUNKS = 128;

  private static final double BAD_SHARE = 0.45d;

  /** Resolutions swept per rule. Each becomes one point on that rule's curve. */
  private static final long[] RESOLUTIONS = {1L, 2L, 3L, 4L, 8L, 16L, 32L, 64L, 128L, 256L, 512L};

  private static final ProximityWeightedLoss.Weighting PRIMARY =
      new ProximityWeightedLoss.Weighting(2, 256L, 0.25d);

  private static final int MAX_REGION_FILES = 256;

  private static final String SAVE_ROOT_PROPERTY = "rtp.simulation.saveRoot";

  /** In-repo overworld save; overridable to a larger real world via the system property. */
  private static final String DEFAULT_SAVE_ROOT = "../testdata-world";

  private static final int SAVE_SEARCH_DEPTH = 8;

  @BeforeAll
  public static void setup() {
    RTP.serverAccessor = new MockRTPServerAccessor(new File("target/test-data"));
  }

  /** One candidate rule for the gap admitted between two adjacent bad runs. */
  private interface GapRule {
    long admissible(long resolution, long leftLength, long rightLength);
  }

  private record Policy(String name, Color color, GapRule rule) {}

  private static List<Policy> policies() {
    List<Policy> out = new ArrayList<>();
    out.add(new Policy("fixed (res)", new Color(0x44, 0x44, 0x44), (res, l, r) -> res));
    out.add(
        new Policy(
            "shipped: clamp(min, res/4, res)",
            new Color(0xC0, 0x39, 0x2B),
            MemoryShape::computeAdmissibleGap));
    out.add(new Policy("min, no floor", new Color(0xE6, 0x7E, 0x22), alpha(1.0d, false)));
    out.add(new Policy("alpha 0.5, no floor", new Color(0x27, 0xAE, 0x60), alpha(0.5d, false)));
    out.add(new Policy("alpha 0.125, no floor", new Color(0x29, 0x80, 0xB9), alpha(0.125d, false)));
    out.add(new Policy("max, floored", new Color(0x8E, 0x44, 0xAD), maxDriver()));
    return out;
  }

  /** Dynamic rule driven by the shorter neighbour, optionally with the shipped {@code res/4} floor. */
  private static GapRule alpha(double alpha, boolean floored) {
    return (res, l, r) -> {
      if (res <= 3L) return Math.max(1L, res);
      long driver = (long) Math.floor(alpha * Math.min(l, r));
      long capped = Math.min(res, Math.max(1L, driver));
      return floored ? Math.max(res / 4L, capped) : capped;
    };
  }

  /** Longer neighbour drives. Inverts the shipped rule's "a pool cannot license its surroundings". */
  private static GapRule maxDriver() {
    return (res, l, r) -> {
      if (res <= 3L) return Math.max(1L, res);
      return Math.max(res / 4L, Math.min(res, Math.max(l, r)));
    };
  }

  @Test
  @DisplayName("the shipped rule is reproduced exactly by this harness before anything is compared")
  public void harnessMatchesShipped() {
    Square shape = spiral();
    NoiseWorldMask world = new NoiseWorldMask(SEED, RADIUS_CHUNKS, BAD_SHARE);
    long[] keys = badKeys(shape, world);
    for (long res : new long[] {2L, 3L, 8L, 32L, 256L}) {
      Square shipped = spiral();
      shipped.setSpatialResolution(res);
      for (long key : keys) shipped.addBadLocation(key, FailTypes.biome);
      shipped.flushAndRebuild(res);
      long[] bad = shipped.badKeysSnapshot();
      long[] probation = shipped.probationKeysSnapshot();
      int shippedRuns = (bad == null ? 0 : bad.length) + (probation == null ? 0 : probation.length);
      assertEquals(
          shippedRuns,
          coalesce(keys, res, MemoryShape::computeAdmissibleGap).size(),
          "harness diverges from shipped coalescing at resolution " + res);
    }
  }

  @Test
  @DisplayName("chart: runs against discarded usable ground, one curve per candidate rule")
  public void chartPolicies() throws Exception {
    Square shape = spiral();
    NoiseWorldMask world = new NoiseWorldMask(SEED, RADIUS_CHUNKS, BAD_SHARE);
    long[] keys = badKeys(shape, world);
    ProximityWeightedLoss priced =
        new ProximityWeightedLoss(RADIUS_CHUNKS, (cx, cz) -> !world.isOccupied(cx, cz));

    List<Policy> policies = policies();
    double[][] runs = new double[policies.size()][RESOLUTIONS.length];
    double[][] flat = new double[policies.size()][RESOLUTIONS.length];
    double[][] weighted = new double[policies.size()][RESOLUTIONS.length];

    for (int p = 0; p < policies.size(); p++) {
      Policy policy = policies.get(p);
      for (int i = 0; i < RESOLUTIONS.length; i++) {
        long res = RESOLUTIONS[i];
        List<long[]> merged = coalesce(keys, res, policy.rule());
        ProximityWeightedLoss.Loss loss =
            priced.lossOf(PRIMARY, discardPredicate(shape, merged, world));
        runs[p][i] = merged.size();
        flat[p][i] = loss.flat();
        weighted[p][i] = loss.weighted();
        System.out.printf(
            "[DEBUG_LOG] %-34s res=%4d runs=%6d flat=%.4f weighted=%.4f shore=%.3f%n",
            policy.name(), res, merged.size(), loss.flat(), loss.weighted(), loss.shoreShareOfLoss());
      }
      assertTrue(runs[p][0] > 0.0d, "no runs produced by " + policy.name());
    }

    BufferedImage img = render(policies, runs, flat, weighted);
    File reports = new File("build/reports/rtp-simulation/img");
    if (reports.isDirectory() || reports.mkdirs()) {
      ImageIO.write(img, "png", new File(reports, "admissible-gap-policy.png"));
    }
    ImageIO.write(img, "png", new File("../admissible_gap_policy_chart.png"));
  }

  @Test
  @DisplayName(
      "min-driver on variable-width runs: does min(left,right) change the frontier vs fixed(res)")
  public void minDriverOnVariableWidthRuns() {
    // The chart above feeds unit-width runs (right length always 1), so min(left,right)=1 and the
    // driver is provably inert - the same input the shipped mark-and-flush rebuild sees. This test
    // exercises the OTHER shipped path: MemoryShape#coalesceRuns re-coalescing pre-formed,
    // variable-width runs (the biome-union fold and staged re-coalesce), where nextLength is a real
    // run width. If the min driver ever earns its place, it is here.

    Square noiseShape = spiral();
    NoiseWorldMask noise = new NoiseWorldMask(SEED, RADIUS_CHUNKS, BAD_SHARE);
    reportVariableWidth(
        "noise 45%", noiseShape, RADIUS_CHUNKS, (cx, cz) -> !noise.isOccupied(cx, cz));

    Path root = Path.of(System.getProperty(SAVE_ROOT_PROPERTY, DEFAULT_SAVE_ROOT));
    List<Path> dirs = RealWorldVerdictMask.discoverRegionDirectories(root, SAVE_SEARCH_DEPTH);
    if (dirs.isEmpty()) {
      System.out.println(
          "[DEBUG_LOG] no real save under " + root.toAbsolutePath() + " - real-terrain leg skipped");
      return;
    }
    RealWorldVerdictMask real =
        RealWorldVerdictMask.load(
            dirs.get(0), RealWorldVerdictMask.SHIPPED_UNSAFE_BLOCKS, MAX_REGION_FILES);
    int rr = Math.min(RADIUS_CHUNKS, real.inscribedRadius());
    System.out.printf(
        "[DEBUG_LOG] real save: %s regionFiles=%d inscribedRadius=%dch usableShare=%.3f%n",
        dirs.get(0), real.regionFilesRead(), real.inscribedRadius(), real.usableShareOfGenerated());
    if (rr < 8) {
      System.out.println("[DEBUG_LOG] real inscribed radius too small - real-terrain leg skipped");
      return;
    }
    Square realShape = new Square();
    realShape.set(GenericMemoryShapeParams.radius, (long) rr);
    realShape.set(GenericMemoryShapeParams.centerRadius, 0L);
    realShape.set(GenericMemoryShapeParams.centerX, 0L);
    realShape.set(GenericMemoryShapeParams.centerZ, 0L);
    reportVariableWidth("real(r=" + rr + ")", realShape, rr, real::isOccupied);
  }

  /**
   * Forms exact contiguous runs from the bad set, then re-coalesces those variable-width runs under
   * fixed(res) and the shipped min-driven rule, reporting run count and discarded ground for each.
   *
   * @param name terrain label for the log lines
   * @param shape addressing shape
   * @param radius half-edge, in chunks, to sweep
   * @param usable true when a chunk is usable ground
   */
  private void reportVariableWidth(
      String name, Square shape, int radius, ProximityWeightedLoss.ChunkOccupancy usable) {
    long[] keys = badKeysFrom(shape, radius, usable);
    List<long[]> exact = exactRuns(keys);
    ProximityWeightedLoss priced = new ProximityWeightedLoss(radius, usable);
    System.out.printf(
        "[DEBUG_LOG] --- %s: %d bad chunks, %d exact runs ---%n",
        name, keys.length, exact.size());
    for (long res : RESOLUTIONS) {
      List<long[]> fixed = coalesceVariable(exact, res, (r, l, rl) -> r);
      List<long[]> shipped =
          coalesceVariable(exact, res, MemoryShape::computeAdmissibleGap);
      ProximityWeightedLoss.Loss lf = priced.lossOf(PRIMARY, discardOf(shape, fixed, usable));
      ProximityWeightedLoss.Loss ls = priced.lossOf(PRIMARY, discardOf(shape, shipped, usable));
      System.out.printf(
          "[DEBUG_LOG] %-12s res=%4d | fixed runs=%6d flat=%.4f wt=%.4f | "
              + "shipped(min) runs=%6d flat=%.4f wt=%.4f | dRuns=%+d dFlat=%+.4f%n",
          name,
          res,
          fixed.size(),
          lf.flat(),
          lf.weighted(),
          shipped.size(),
          ls.flat(),
          ls.weighted(),
          shipped.size() - fixed.size(),
          ls.flat() - lf.flat());
    }
  }

  /** Merges only strictly contiguous keys, so run widths reflect the terrain, not a policy. */
  private static List<long[]> exactRuns(long[] keys) {
    List<long[]> out = new ArrayList<>();
    long curStart = -1L;
    long curLength = 0L;
    for (long key : keys) {
      if (key < 0L) continue;
      if (curStart == -1L) {
        curStart = key;
        curLength = 1L;
        continue;
      }
      if (key == curStart + curLength) {
        curLength++;
        continue;
      }
      out.add(new long[] {curStart, curLength});
      curStart = key;
      curLength = 1L;
    }
    if (curStart != -1L) out.add(new long[] {curStart, curLength});
    return out;
  }

  /**
   * Re-coalesces pre-formed runs, feeding the rule the running accumulator length and the real width
   * of the incoming run - the only path on which {@code min(left,right)} carries information.
   *
   * <p>Mirrors {@code MemoryShape#coalesceRuns} for a single biome.
   */
  private static List<long[]> coalesceVariable(List<long[]> runs, long resolution, GapRule rule) {
    List<long[]> out = new ArrayList<>();
    long curStart = -1L;
    long curLength = 0L;
    for (long[] run : runs) {
      long nextKey = run[0];
      long nextLength = run[1];
      if (curStart == -1L) {
        curStart = nextKey;
        curLength = nextLength;
        continue;
      }
      long curEnd = curStart + curLength;
      if (nextKey <= curEnd + rule.admissible(resolution, curLength, nextLength)) {
        curLength = Math.max(curLength, nextKey + nextLength - curStart);
        continue;
      }
      out.add(new long[] {curStart, curLength});
      curStart = nextKey;
      curLength = nextLength;
    }
    if (curStart != -1L) out.add(new long[] {curStart, curLength});
    return out;
  }

  private static long[] badKeysFrom(
      Square shape, int radius, ProximityWeightedLoss.ChunkOccupancy usable) {
    long[] keys = new long[4 * radius * radius];
    int out = 0;
    for (int cx = -radius; cx < radius; cx++) {
      for (int cz = -radius; cz < radius; cz++) {
        if (usable.usable(cx, cz)) continue;
        long key = shape.xzToLocation(cx, cz);
        if (key < 0L || key >= shape.getRange()) continue;
        keys[out++] = key;
      }
    }
    long[] trimmed = Arrays.copyOf(keys, out);
    Arrays.sort(trimmed);
    return trimmed;
  }

  /** A usable chunk is discarded when coalescing swallowed its key into a bad run. */
  private static ProximityWeightedLoss.ChunkOccupancy discardOf(
      Square shape, List<long[]> merged, ProximityWeightedLoss.ChunkOccupancy usable) {
    long[] starts = new long[merged.size()];
    long[] ends = new long[merged.size()];
    for (int i = 0; i < merged.size(); i++) {
      starts[i] = merged.get(i)[0];
      ends[i] = merged.get(i)[0] + merged.get(i)[1];
    }
    return (cx, cz) -> {
      if (!usable.usable(cx, cz)) return false;
      long key = shape.xzToLocation(cx, cz);
      if (key < 0L || key >= shape.getRange()) return false;
      int idx = Arrays.binarySearch(starts, key);
      if (idx >= 0) return true;
      int prev = -idx - 2;
      return prev >= 0 && key < ends[prev];
    };
  }

  // -------------------------------------------------------------------------------------
  // measurement
  // -------------------------------------------------------------------------------------

  /**
   * One greedy left-to-right pass, the gap recomputed from the accumulator's current length.
   *
   * <p>Transcribed from {@code MemoryShape#coalesceRuns}. The accumulator is load-bearing: the
   * rule is order-dependent and not idempotent, so feeding it pre-merge lengths is a different
   * rule and yields a different count.
   */
  private static List<long[]> coalesce(long[] keys, long resolution, GapRule rule) {
    List<long[]> out = new ArrayList<>();
    long curStart = -1L;
    long curLength = 0L;
    for (long key : keys) {
      if (key < 0L) continue;
      if (curStart == -1L) {
        curStart = key;
        curLength = 1L;
        continue;
      }
      long curEnd = curStart + curLength;
      if (key <= curEnd + rule.admissible(resolution, curLength, 1L)) {
        curLength = Math.max(curLength, key + 1L - curStart);
        continue;
      }
      out.add(new long[] {curStart, curLength});
      curStart = key;
      curLength = 1L;
    }
    if (curStart != -1L) out.add(new long[] {curStart, curLength});
    return out;
  }

  /** A usable chunk is discarded when coalescing swallowed its key into a bad run. */
  private static ProximityWeightedLoss.ChunkOccupancy discardPredicate(
      Square shape, List<long[]> merged, NoiseWorldMask world) {
    long[] starts = new long[merged.size()];
    long[] ends = new long[merged.size()];
    for (int i = 0; i < merged.size(); i++) {
      starts[i] = merged.get(i)[0];
      ends[i] = merged.get(i)[0] + merged.get(i)[1];
    }
    return (cx, cz) -> {
      if (world.isOccupied(cx, cz)) return false;
      long key = shape.xzToLocation(cx, cz);
      if (key < 0L || key >= shape.getRange()) return false;
      int idx = Arrays.binarySearch(starts, key);
      if (idx >= 0) return true;
      int prev = -idx - 2;
      return prev >= 0 && key < ends[prev];
    };
  }

  private static long[] badKeys(Square shape, NoiseWorldMask world) {
    long[] keys = new long[4 * RADIUS_CHUNKS * RADIUS_CHUNKS];
    int out = 0;
    for (int cx = -RADIUS_CHUNKS; cx < RADIUS_CHUNKS; cx++) {
      for (int cz = -RADIUS_CHUNKS; cz < RADIUS_CHUNKS; cz++) {
        if (!world.isOccupied(cx, cz)) continue;
        long key = shape.xzToLocation(cx, cz);
        if (key < 0L || key >= shape.getRange()) continue;
        keys[out++] = key;
      }
    }
    long[] trimmed = Arrays.copyOf(keys, out);
    Arrays.sort(trimmed);
    return trimmed;
  }

  private static Square spiral() {
    Square shape = new Square();
    shape.set(GenericMemoryShapeParams.radius, (long) RADIUS_CHUNKS);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);
    return shape;
  }

  // -------------------------------------------------------------------------------------
  // drawing
  // -------------------------------------------------------------------------------------

  private static BufferedImage render(
      List<Policy> policies, double[][] runs, double[][] flat, double[][] weighted) {
    int panelW = 640;
    int panelH = 460;
    int legendH = 26 * policies.size() + 40;
    BufferedImage img =
        new BufferedImage(panelW * 2 + 60, panelH + legendH + 60, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, img.getWidth(), img.getHeight());

    g.setColor(new Color(0x22, 0x22, 0x22));
    g.setFont(new Font("SansSerif", Font.BOLD, 17));
    g.drawString(
        "computeAdmissibleGap: table size against discarded usable ground (lower-left is better)",
        20,
        28);
    g.setFont(new Font("SansSerif", Font.PLAIN, 12));
    g.drawString(
        "spiral r="
            + RADIUS_CHUNKS
            + " chunks, "
            + (int) (BAD_SHARE * 100)
            + "% unusable, resolution swept 1..512 along each curve",
        20,
        46);

    panel(g, 30, 60, panelW, panelH, policies, runs, flat, "flat loss (all ground priced alike)");
    panel(
        g,
        panelW + 50,
        60,
        panelW,
        panelH,
        policies,
        runs,
        weighted,
        "weighted loss (shore discounted)");

    int ly = panelH + 90;
    g.setFont(new Font("SansSerif", Font.PLAIN, 13));
    for (Policy policy : policies) {
      g.setColor(policy.color());
      g.setStroke(new BasicStroke(3f));
      g.drawLine(34, ly - 5, 74, ly - 5);
      g.fillOval(50, ly - 9, 8, 8);
      g.setColor(new Color(0x22, 0x22, 0x22));
      g.drawString(policy.name(), 86, ly);
      ly += 26;
    }
    g.dispose();
    return img;
  }

  private static void panel(
      Graphics2D g,
      int ox,
      int oy,
      int w,
      int h,
      List<Policy> policies,
      double[][] xs,
      double[][] ys,
      String title) {
    int padL = 66;
    int padB = 44;
    int padT = 30;
    int padR = 16;
    int plotW = w - padL - padR;
    int plotH = h - padT - padB;

    double maxX = 1.0d;
    double maxY = 1e-9d;
    for (int p = 0; p < xs.length; p++) {
      for (int i = 0; i < xs[p].length; i++) {
        maxX = Math.max(maxX, xs[p][i]);
        maxY = Math.max(maxY, ys[p][i]);
      }
    }
    double logMaxX = Math.log10(Math.max(10.0d, maxX));

    g.setColor(new Color(0x22, 0x22, 0x22));
    g.setFont(new Font("SansSerif", Font.BOLD, 13));
    g.drawString(title, ox + padL, oy + 18);

    g.setColor(new Color(0xDD, 0xDD, 0xDD));
    g.setStroke(new BasicStroke(1f));
    for (int i = 0; i <= 5; i++) {
      int y = oy + padT + (int) (plotH * i / 5.0d);
      g.drawLine(ox + padL, y, ox + padL + plotW, y);
    }
    for (int d = 0; d <= (int) Math.ceil(logMaxX); d++) {
      int x = ox + padL + (int) (plotW * d / logMaxX);
      if (x <= ox + padL + plotW) g.drawLine(x, oy + padT, x, oy + padT + plotH);
    }

    g.setColor(new Color(0x33, 0x33, 0x33));
    g.setStroke(new BasicStroke(1.6f));
    g.drawLine(ox + padL, oy + padT, ox + padL, oy + padT + plotH);
    g.drawLine(ox + padL, oy + padT + plotH, ox + padL + plotW, oy + padT + plotH);

    g.setFont(new Font("SansSerif", Font.PLAIN, 11));
    for (int i = 0; i <= 5; i++) {
      double v = maxY * (5 - i) / 5.0d;
      int y = oy + padT + (int) (plotH * i / 5.0d);
      g.drawString(String.format("%.3f", v), ox + padL - 46, y + 4);
    }
    for (int d = 0; d <= (int) Math.ceil(logMaxX); d++) {
      int x = ox + padL + (int) (plotW * d / logMaxX);
      if (x > ox + padL + plotW) continue;
      g.drawString(String.valueOf((long) Math.pow(10, d)), x - 8, oy + padT + plotH + 18);
    }
    g.drawString("runs held (log scale)", ox + padL + plotW / 2 - 54, oy + padT + plotH + 36);

    for (int p = 0; p < policies.size(); p++) {
      g.setColor(policies.get(p).color());
      g.setStroke(new BasicStroke(2.4f));
      int prevX = -1;
      int prevY = -1;
      for (int i = 0; i < xs[p].length; i++) {
        int x = ox + padL + (int) (plotW * Math.log10(Math.max(1.0d, xs[p][i])) / logMaxX);
        int y = oy + padT + (int) (plotH * (1.0d - ys[p][i] / maxY));
        if (prevX >= 0) g.drawLine(prevX, prevY, x, y);
        g.fillOval(x - 3, y - 3, 6, 6);
        prevX = x;
        prevY = y;
      }
    }
  }
}
