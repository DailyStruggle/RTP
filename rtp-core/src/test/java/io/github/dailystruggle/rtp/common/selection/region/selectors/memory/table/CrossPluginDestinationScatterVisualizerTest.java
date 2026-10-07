package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.benchmark.LosslessChunkOutcomeMap;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CircleOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle_Normal;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;
import io.github.dailystruggle.rtp.common.tools.ChartOutputHelper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Renders where each plugin of a StressTestRTP cross-plugin run landed its players, and fits the
 * LeafRTP region {@code shape} block that reproduces each plugin's radial spread.
 *
 * <p>Each panel uses that plugin's newest finished phase in {@code -Drtp.scatter.runsDir} (a label
 * listed in a run's {@code -phases.csv}), so a rerun of one plugin replaces only its panel and a
 * half-finished phase never reaches the published chart; each panel names its run. The test is
 * skipped while any plugin has no finished phase. {@code -Drtp.scatter.csv=<run.csv>} pins every
 * panel to one run instead. Terrain from {@code -Drtp.scatter.regionDir}.
 * {@code -Drtp.scatter.preview=true} fills unfinished plugins from the newest run's partial rows
 * and writes to {@code build/reports/player_distribution/} only, never to {@code docs/}.
 *
 * <p>Fit: landings are reduced to the area fraction {@code f = (r^2 - cr^2) / (R^2 - cr^2)}, the
 * coordinate LeafRTP's circle index is linear in. Each ring is reweighted by 1 / (safe-land share)
 * so terrain rejection, which every plugin pays, does not bias the fit. Candidates: {@code CIRCLE}
 * (even by area; its dual-layer sampler ignores {@code weight}), {@code CIRCLE_NORMAL}
 * ({@code mean}, {@code deviation}) and {@code CIRCLE_DEPRECATED_PURE_SPIRAL} ({@code weight},
 * {@code f = u^weight}). The chosen config is then sampled through LeafRTP's own shape code with
 * the same terrain rejection and compared to the observed landings by a two-sample KS test.
 */
public class CrossPluginDestinationScatterVisualizerTest {

  public static void main(String[] args) throws Exception {
    new CrossPluginDestinationScatterVisualizerTest().testRenderCrossPluginDestinationScatterChart();
  }

  private static final double OUTER_R = 16384.0;
  private static final double INNER_R = 1024.0;
  private static final int RINGS = 16;
  private static final int MASK_RINGS = 64;
  private static final double PROXIMITY_BLOCKS = 48.0;
  // Consecutive-spacing check: a landing within RECENT_BLOCKS of any of the previous RECENT_WINDOW.
  private static final double RECENT_BLOCKS = 256.0;
  private static final int RECENT_WINDOW = 64;
  private static final long SIM_SEED = 20261006L;
  private static final double KS_ALPHA_05 = 1.358;

  private static final Path DEFAULT_RUNS_DIR =
      Path.of("C:\\GameServers\\Minecraft\\testServer\\RTP-Paper\\26.2\\plugins\\StressTestRTP\\runs");
  private static final Path DEFAULT_REGION_DIR =
      Path.of("C:\\GameServers\\Minecraft\\testServer\\RTP-Paper\\26.2\\world\\dimensions\\minecraft\\overworld\\region");

  record PluginSpec(String label, String displayName, String sampling, Color accent) {}

  private static final List<PluginSpec> PLUGINS = List.of(
      new PluginSpec("rtp", "LeafRTP", "CIRCLE, ACCUMULATE: keyed shuffle over the area index", new Color(0x38EF7D)),
      new PluginSpec("betterrtp", "BetterRTP", "Shape: circle", new Color(0xB388FF)),
      new PluginSpec("ezrtp", "EzRTP", "search-pattern: circle (distance drawn evenly, not by area)", new Color(0x4FACFE)),
      new PluginSpec("justrtp", "JustRTP", "circle (area-even distance draw)", new Color(0xFFA726)),
      new PluginSpec("jakesrtp", "JakesRTP", "default-symmetric: circle, gaussian-distribution off", new Color(0xFFEE58)),
      new PluginSpec("huskhomes", "HuskHomes", "radial normal: distribution_mean 0.75, deviation 2.0", new Color(0x26C6DA)));

  record Point2D(long x, long z) {}

  /** One successful teleport and the harness's block check of where it landed. */
  record Landing(Point2D p, String landingClass) {}

  /** Ring a plugin actually landed in, in blocks; differs from the configured ring when a plugin-side cap applied. */
  record Bounds(double inner, double outer) {
    double areaFraction(double r) {
      double f = (r * r - inner * inner) / (outer * outer - inner * inner);
      return Math.max(0.0, Math.min(1.0, f));
    }

    long innerChunks() {
      return Math.round(inner / 16.0);
    }

    long outerChunks() {
      return Math.round(outer / 16.0);
    }

    boolean configured() {
      return inner == INNER_R && outer == OUTER_R;
    }

    /** Exponent NormalMemoryShape applies to its gaussian draw to map it onto the area index. */
    double normalExponent() {
      return (1.0 + (double) innerChunks() / (double) outerChunks()) * 0.5;
    }
  }

  /** Safe-land share per equal-area ring, and total safe-land area in blocks squared. */
  record LandModel(double[] share, double landArea) {}

  /** Chunks a plugin can land in (packed cx, cz), and the random-scatter baseline over them. */
  record PossibleArea(long[] chunks, double areaBlocks, double meanNn, double nearPairs, double recentRepeats) {}

  enum Model { CIRCLE, CIRCLE_NORMAL, CIRCLE_WEIGHTED }

  record Fit(Model model, double weight, double mean, double deviation, double ksFit, double ksUniform) {
    String shapeLine() {
      return switch (model) {
        case CIRCLE -> "name: CIRCLE";
        case CIRCLE_NORMAL -> String.format(Locale.ROOT, "name: CIRCLE_NORMAL  mean: %.2f  deviation: %.2f", mean, deviation);
        case CIRCLE_WEIGHTED -> String.format(Locale.ROOT, "name: CIRCLE_DEPRECATED_PURE_SPIRAL  weight: %.2f", weight);
      };
    }
  }

  record Stats(
      PluginSpec spec,
      List<Point2D> points,
      Map<Point2D, Integer> freq,
      int n,
      int unique,
      int duplicates,
      double duplicatePercent,
      int chunksReused,
      int nearPairs,
      double nnMin,
      double nnP50,
      double nnMean,
      double clarkEvansR,
      double radialTv,
      double minR,
      double maxR,
      Bounds bounds,
      double possibleAreaKm2,
      double expectedNearPairs,
      int recentRepeats,
      double expectedRecentRepeats,
      int checked,
      int safeLandings,
      int waterLandings,
      int blockedLandings,
      int noFloorLandings,
      int hazardLandings,
      double avgHop,
      double[] observedDensity,
      double[] simulatedDensity,
      Fit fit,
      double ksSim,
      double ksCrit,
      boolean simulatedWithShipped) {}

  @Test
  @DisplayName("Render cross-plugin destination scatter with fitted LeafRTP shape configs")
  public void testRenderCrossPluginDestinationScatterChart() throws Exception {
    List<File> runs = candidateRuns();
    Assumptions.assumeTrue(!runs.isEmpty(), "no StressTestRTP run CSV found");
    // Newest first: each plugin takes the first run that finished its phase.
    Map<String, File> source = new LinkedHashMap<>();
    for (File run : runs) {
      Set<String> finished = finishedPhases(run);
      for (PluginSpec spec : PLUGINS) {
        if (finished.contains(spec.label())) source.putIfAbsent(spec.label(), run);
      }
    }
    List<String> missing = PLUGINS.stream().map(PluginSpec::label).filter(l -> !source.containsKey(l)).toList();
    boolean preview = Boolean.getBoolean("rtp.scatter.preview");
    Assumptions.assumeTrue(missing.isEmpty() || preview,
        "no finished phase in " + runs.size() + " run(s) for: " + missing);
    for (String label : missing) source.put(label, runs.get(0));

    Map<String, List<Landing>> landings = new LinkedHashMap<>();
    Map<String, String> runOf = new LinkedHashMap<>();
    Map<File, Map<String, List<Landing>>> read = new LinkedHashMap<>();
    for (PluginSpec spec : PLUGINS) {
      File run = source.get(spec.label());
      Map<String, List<Landing>> all = read.get(run);
      if (all == null) {
        all = readLandings(run);
        read.put(run, all);
      }
      landings.put(spec.label(), all.get(spec.label()));
      runOf.put(spec.label(), run.getName().replaceFirst("\\.csv$", ""));
      System.out.println("[DEBUG_LOG] " + spec.displayName() + " from run " + run.getAbsolutePath());
    }

    Path regionDir = Path.of(System.getProperty("rtp.scatter.regionDir", DEFAULT_REGION_DIR.toString()));
    LosslessChunkOutcomeMap outcomeMap = null;
    if (Files.isDirectory(regionDir)) {
      File cache = new File("build/cache/mca_"
          + Integer.toHexString(regionDir.toAbsolutePath().toString().hashCode()) + "_r1024_outcomes.bin");
      try {
        outcomeMap = LosslessChunkOutcomeMap.getOrCreate(cache, regionDir);
      } catch (Throwable t) {
        System.out.println("[DEBUG_LOG] Terrain map unavailable, rendering without it: " + t.getMessage());
      }
    }

    Object previousAccessor = RTP.serverAccessor;
    if (RTP.serverAccessor == null) {
      RTP.serverAccessor = new MockRTPServerAccessor(new File("target/test-data"));
    }
    List<Stats> stats = new ArrayList<>();
    try {
      for (PluginSpec spec : PLUGINS) {
        stats.add(computeStats(spec, landings.get(spec.label()), outcomeMap));
      }
    } finally {
      if (previousAccessor == null) RTP.serverAccessor = null;
    }

    renderChart(stats, outcomeMap, runOf, !missing.isEmpty());
  }

  // ---------------------------------------------------------------------------------------------
  // Input
  // ---------------------------------------------------------------------------------------------

  /** Run CSVs, newest first; just the pinned one when {@code rtp.scatter.csv} is set. */
  private static List<File> candidateRuns() {
    String explicit = System.getProperty("rtp.scatter.csv");
    if (explicit != null && !explicit.isBlank()) {
      File f = new File(explicit);
      return f.isFile() ? List.of(f) : List.of();
    }
    Path runsDir = Path.of(System.getProperty("rtp.scatter.runsDir", DEFAULT_RUNS_DIR.toString()));
    if (!Files.isDirectory(runsDir)) return List.of();
    try (Stream<Path> files = Files.list(runsDir)) {
      // Run ids are yyyyMMdd-HHmmss, so reverse lexicographic order is newest first.
      return files
          .filter(p -> p.getFileName().toString().matches("\\d{8}-\\d{6}\\.csv"))
          .sorted(java.util.Comparator.reverseOrder())
          .map(Path::toFile)
          .toList();
    } catch (Exception e) {
      return List.of();
    }
  }

  /** Phase rows are appended when a plugin's phase ends, so a listed label is a finished plugin. */
  private static Set<String> finishedPhases(File runCsv) {
    Set<String> labels = new HashSet<>();
    File phases = new File(runCsv.getParentFile(), runCsv.getName().replaceFirst("\\.csv$", "-phases.csv"));
    if (!phases.isFile()) return labels;
    try (BufferedReader br = new BufferedReader(new FileReader(phases))) {
      String line = br.readLine();
      while ((line = br.readLine()) != null) {
        int comma = line.indexOf(',');
        if (comma > 0) labels.add(line.substring(0, comma).trim().toLowerCase(Locale.ROOT));
      }
    } catch (Exception ignored) {
      // Unreadable phases file reads as "nothing finished": the test skips.
    }
    return labels;
  }

  private static Map<String, List<Landing>> readLandings(File csv) throws Exception {
    Map<String, List<Landing>> out = new LinkedHashMap<>();
    for (PluginSpec spec : PLUGINS) out.put(spec.label(), new ArrayList<>());
    try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
      String headerLine = br.readLine();
      if (headerLine == null) return out;
      String[] headers = headerLine.split(",");
      int targetIdx = -1;
      int successIdx = -1;
      int toXIdx = -1;
      int toZIdx = -1;
      int classIdx = -1;
      for (int i = 0; i < headers.length; i++) {
        String h = headers[i].trim();
        if (h.equalsIgnoreCase("target_label")) targetIdx = i;
        else if (h.equalsIgnoreCase("success")) successIdx = i;
        else if (h.equalsIgnoreCase("to_x")) toXIdx = i;
        else if (h.equalsIgnoreCase("to_z")) toZIdx = i;
        else if (h.equalsIgnoreCase("landing_class")) classIdx = i;
      }
      if (targetIdx < 0 || toXIdx < 0 || toZIdx < 0) return out;
      int maxIdx = Math.max(targetIdx, Math.max(toXIdx, toZIdx));
      String line;
      while ((line = br.readLine()) != null) {
        String[] cols = line.split(",", -1);
        if (cols.length <= maxIdx) continue;
        if (successIdx >= 0 && cols.length > successIdx && !cols[successIdx].trim().equalsIgnoreCase("true")) continue;
        List<Landing> bucket = out.get(cols[targetIdx].trim().toLowerCase(Locale.ROOT));
        if (bucket == null) continue;
        try {
          double x = Double.parseDouble(cols[toXIdx].trim());
          double z = Double.parseDouble(cols[toZIdx].trim());
          // (0, 0) is the harness's placeholder for a teleport with no destination.
          if (Double.isNaN(x) || Double.isNaN(z) || (Math.abs(x) < 0.1 && Math.abs(z) < 0.1)) continue;
          String cls = classIdx >= 0 && cols.length > classIdx ? cols[classIdx].trim() : "";
          bucket.add(new Landing(new Point2D(Math.round(x), Math.round(z)), cls));
        } catch (NumberFormatException ignored) {
          // Malformed coordinate cell: not a landing.
        }
      }
    }
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // Geometry
  // ---------------------------------------------------------------------------------------------

  /**
   * Configured ring unless the landings fall clearly outside it, in which case the ring the
   * plugin actually used (chunk-aligned) so the fit describes its real behaviour.
   */
  private static Bounds observedBounds(double minR, double maxR) {
    double outer = maxR < OUTER_R * 0.95 ? Math.ceil(maxR / 16.0) * 16.0 : OUTER_R;
    double inner = minR < INNER_R * 0.9 ? Math.floor(minR / 16.0) * 16.0 : INNER_R;
    return new Bounds(inner, outer);
  }

  /**
   * Clark-Evans baseline over the area this plugin can actually land in.
   *
   * <p>The closed form {@code 0.5 / sqrt(n / A)} assumes uniform density over one convex region.
   * Neither holds here: landable terrain is fragmented by coastline, and each plugin has its own
   * radial profile (EzRTP piles up at the inner edge). Against the whole ring every plugin reads
   * as clustered; against a binary land mask, as over-dispersed. Instead the baseline is a random
   * scatter with the plugin's own landing intensity: each chunk in its ring is weighted by the
   * plugin's landings per chunk in that chunk's (terrain class, equal-area ring) cell. That
   * absorbs where a plugin may land (terrain acceptance, skipped known-bad chunks) and its radial
   * shape, so R and the near-pair count measure only local spacing beyond both.
   *
   * <p>{@code areaBlocks} is the effective possible area, {@code (sum w)^2 / sum w^2} chunks:
   * the area a uniform scatter of the same concentration would cover.
   */
  private static PossibleArea possibleArea(List<Point2D> pts, LosslessChunkOutcomeMap map, Bounds b, Random rng) {
    int n = pts.size();
    int classes = 8;
    int rc = LosslessChunkOutcomeMap.RADIUS_CHUNKS;
    long[] chunksByCell = new long[classes * RINGS];
    long[] landingsByCell = new long[classes * RINGS];
    int ringChunks = 0;
    for (int cz = -rc; cz < rc; cz++) {
      for (int cx = -rc; cx < rc; cx++) {
        double r = Math.hypot(cx * 16.0 + 8.0, cz * 16.0 + 8.0);
        if (r < b.inner() || r > b.outer()) continue;
        int cls = map == null ? 0 : map.getOutcome(cx, cz) & 7;
        chunksByCell[cls * RINGS + ringOf(b.areaFraction(r), RINGS)]++;
        ringChunks++;
      }
    }
    for (Point2D p : pts) {
      int cx = (int) Math.floorDiv(p.x, 16L);
      int cz = (int) Math.floorDiv(p.z, 16L);
      int cls = map == null ? 0 : map.getOutcome(cx, cz) & 7;
      double r = Math.hypot(cx * 16.0 + 8.0, cz * 16.0 + 8.0);
      landingsByCell[cls * RINGS + ringOf(b.areaFraction(r), RINGS)]++;
    }
    double[] cellWeight = new double[classes * RINGS];
    for (int i = 0; i < cellWeight.length; i++) {
      cellWeight[i] = chunksByCell[i] > 0 ? (double) landingsByCell[i] / chunksByCell[i] : 0;
    }

    long[] chunks = new long[ringChunks];
    double[] cumulative = new double[ringChunks];
    double sumW = 0;
    double sumW2 = 0;
    int k = 0;
    for (int cz = -rc; cz < rc; cz++) {
      for (int cx = -rc; cx < rc; cx++) {
        double r = Math.hypot(cx * 16.0 + 8.0, cz * 16.0 + 8.0);
        if (r < b.inner() || r > b.outer()) continue;
        int cls = map == null ? 0 : map.getOutcome(cx, cz) & 7;
        double w = cellWeight[cls * RINGS + ringOf(b.areaFraction(r), RINGS)];
        sumW += w;
        sumW2 += w * w;
        chunks[k] = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        cumulative[k] = sumW;
        k++;
      }
    }
    double area = sumW2 > 0 ? sumW * sumW / sumW2 * 256.0 : ringChunks * 256.0;

    int reps = 6;
    double nnSum = 0;
    double pairSum = 0;
    double recentSum = 0;
    double[] xs = new double[n];
    double[] zs = new double[n];
    for (int rep = 0; rep < reps; rep++) {
      for (int i = 0; i < n; i++) {
        int idx = Arrays.binarySearch(cumulative, rng.nextDouble() * sumW);
        if (idx < 0) idx = -idx - 1;
        long c = chunks[Math.min(idx, chunks.length - 1)];
        xs[i] = (int) (c >> 32) * 16.0 + rng.nextDouble() * 16.0;
        zs[i] = (int) c * 16.0 + rng.nextDouble() * 16.0;
      }
      recentSum += recentRepeats(xs, zs);
      double near2 = PROXIMITY_BLOCKS * PROXIMITY_BLOCKS;
      for (int i = 0; i < n; i++) {
        double best2 = Double.MAX_VALUE;
        for (int j = 0; j < n; j++) {
          if (i == j) continue;
          double dx = xs[i] - xs[j];
          double dz = zs[i] - zs[j];
          double d2 = dx * dx + dz * dz;
          if (d2 < best2) best2 = d2;
          if (j > i && d2 <= near2) pairSum++;
        }
        nnSum += Math.sqrt(best2);
      }
    }
    return new PossibleArea(chunks, area, nnSum / ((double) reps * n), pairSum / reps, recentSum / reps);
  }

  /** Median {@link #recentRepeats} over 21 random orderings of the same landings: the ordering-only
   *  baseline, immune to terrain clustering the chunk map cannot resolve. */
  private static double shuffledRecentRepeats(double[] xs, double[] zs, Random rng) {
    int n = xs.length;
    int[] counts = new int[21];
    double[] sx = xs.clone();
    double[] sz = zs.clone();
    for (int t = 0; t < counts.length; t++) {
      for (int i = n - 1; i > 0; i--) {
        int j = rng.nextInt(i + 1);
        double tx = sx[i];
        sx[i] = sx[j];
        sx[j] = tx;
        double tz = sz[i];
        sz[i] = sz[j];
        sz[j] = tz;
      }
      counts[t] = recentRepeats(sx, sz);
    }
    Arrays.sort(counts);
    return counts[counts.length / 2];
  }

  /** Landings within {@link #RECENT_BLOCKS} of any of the previous {@link #RECENT_WINDOW}, in order. */
  private static int recentRepeats(double[] xs, double[] zs) {
    double lim2 = RECENT_BLOCKS * RECENT_BLOCKS;
    int count = 0;
    for (int i = 1; i < xs.length; i++) {
      for (int j = Math.max(0, i - RECENT_WINDOW); j < i; j++) {
        double dx = xs[i] - xs[j];
        double dz = zs[i] - zs[j];
        if (dx * dx + dz * dz <= lim2) {
          count++;
          break;
        }
      }
    }
    return count;
  }

  private static int ringOf(double f, int rings) {
    return Math.min(rings - 1, Math.max(0, (int) (f * rings)));
  }

  /** Safe-land share per equal-area ring; all 1.0 (and the full ring area) without terrain. */
  private static LandModel landModel(LosslessChunkOutcomeMap map, Bounds b) {
    double[] share = new double[MASK_RINGS];
    Arrays.fill(share, 1.0);
    if (map == null) return new LandModel(share, Math.PI * (b.outer() * b.outer() - b.inner() * b.inner()));
    long safeTotal = 0;
    long[] total = new long[MASK_RINGS];
    long[] safe = new long[MASK_RINGS];
    int rc = LosslessChunkOutcomeMap.RADIUS_CHUNKS;
    for (int cz = -rc; cz < rc; cz++) {
      for (int cx = -rc; cx < rc; cx++) {
        double r = Math.hypot(cx * 16.0 + 8.0, cz * 16.0 + 8.0);
        if (r < b.inner() || r > b.outer()) continue;
        int k = ringOf(b.areaFraction(r), MASK_RINGS);
        total[k]++;
        if (map.getOutcome(cx, cz) == LosslessChunkOutcomeMap.OUTCOME_SAFE) {
          safe[k]++;
          safeTotal++;
        }
      }
    }
    for (int k = 0; k < MASK_RINGS; k++) {
      share[k] = total[k] > 0 ? Math.max(1e-3, (double) safe[k] / total[k]) : 1.0;
    }
    return new LandModel(share, Math.max(1.0, safeTotal * 256.0));
  }

  // ---------------------------------------------------------------------------------------------
  // Statistics
  // ---------------------------------------------------------------------------------------------

  private Stats computeStats(PluginSpec spec, List<Landing> landed, LosslessChunkOutcomeMap map) throws Exception {
    List<Point2D> pts = landed.stream().map(Landing::p).toList();
    int n = pts.size();
    Map<Point2D, Integer> freq = new LinkedHashMap<>();
    for (Point2D p : pts) freq.merge(p, 1, Integer::sum);

    // Harness block check at the landing; UNCHECKED_* rows were unloaded before it could look.
    int checked = 0;
    int safeLandings = 0;
    int waterLandings = 0;
    int blockedLandings = 0;
    int noFloorLandings = 0;
    int hazardLandings = 0;
    for (Landing l : landed) {
      String c = l.landingClass().toUpperCase(Locale.ROOT);
      if (c.isEmpty() || c.startsWith("UNCHECKED")) continue;
      checked++;
      switch (c) {
        case "SAFE" -> safeLandings++;
        case "WATER" -> waterLandings++;
        case "SUFFOCATING" -> blockedLandings++;
        case "NO_FLOOR" -> noFloorLandings++;
        default -> hazardLandings++;
      }
    }

    if (n < 2) {
      Fit none = new Fit(Model.CIRCLE, 1.0, 0.5, 1.0, Double.NaN, Double.NaN);
      return new Stats(spec, pts, freq, n, freq.size(), n - freq.size(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
          new Bounds(INNER_R, OUTER_R), 0, 0, 0, 0, checked, safeLandings, waterLandings, blockedLandings,
          noFloorLandings,
          hazardLandings, 0, new double[RINGS], new double[RINGS], none, Double.NaN, Double.NaN, false);
    }

    int unique = freq.size();
    int duplicates = n - unique;
    // The keyed shuffle never revisits a chunk within a cycle, so a reused chunk is the spacing
    // guarantee failing; nearby-but-distinct chunks are not covered while expand is off.
    Set<Long> chunks = new HashSet<>();
    for (Point2D p : pts) {
      chunks.add(((long) Math.floorDiv(p.x, 16L) << 32) ^ (Math.floorDiv(p.z, 16L) & 0xFFFFFFFFL));
    }
    int chunksReused = n - chunks.size();

    double[] nn = new double[n];
    int nearPairs = 0;
    for (int i = 0; i < n; i++) {
      Point2D a = pts.get(i);
      double best = Double.MAX_VALUE;
      for (int j = 0; j < n; j++) {
        if (i == j) continue;
        Point2D b = pts.get(j);
        double d = Math.hypot(a.x - b.x, a.z - b.z);
        if (d < best) best = d;
        if (j > i && d <= PROXIMITY_BLOCKS) nearPairs++;
      }
      nn[i] = best;
    }
    double nnMean = Arrays.stream(nn).average().orElse(0);
    double[] nnSorted = nn.clone();
    Arrays.sort(nnSorted);

    double hop = 0;
    for (int i = 0; i + 1 < n; i++) {
      hop += Math.hypot(pts.get(i).x - pts.get(i + 1).x, pts.get(i).z - pts.get(i + 1).z);
    }

    double minR = Double.MAX_VALUE;
    double maxR = 0;
    for (Point2D p : pts) {
      double r = Math.hypot(p.x, p.z);
      minR = Math.min(minR, r);
      maxR = Math.max(maxR, r);
    }
    Bounds bounds = observedBounds(minR, maxR);
    LandModel land = landModel(map, bounds);

    PossibleArea possible = possibleArea(pts, map, bounds, new Random(SIM_SEED ^ (spec.label().hashCode() * 31L)));
    double expectedNn = possible.meanNn();
    double expectedNearPairs = possible.nearPairs();
    double[] ox = new double[n];
    double[] oz = new double[n];
    for (int i = 0; i < n; i++) {
      ox[i] = pts.get(i).x;
      oz[i] = pts.get(i).z;
    }
    int recent = recentRepeats(ox, oz);
    double shuffledRecent = shuffledRecentRepeats(ox, oz, new Random(SIM_SEED ^ spec.label().hashCode() * 17L));

    double[] f = new double[n];
    double[] w = new double[n];
    int[] ringCounts = new int[RINGS];
    for (int i = 0; i < n; i++) {
      Point2D p = pts.get(i);
      f[i] = bounds.areaFraction(Math.hypot(p.x, p.z));
      w[i] = 1.0 / land.share()[ringOf(f[i], MASK_RINGS)];
      ringCounts[ringOf(f[i], RINGS)]++;
    }
    double[] observedDensity = new double[RINGS];
    double tv = 0;
    for (int k = 0; k < RINGS; k++) {
      observedDensity[k] = (double) ringCounts[k] / n * RINGS;
      tv += Math.abs((double) ringCounts[k] / n - 1.0 / RINGS);
    }
    tv *= 0.5;

    Fit fit = fitRadial(f, w, bounds);

    // Validate the fitted config with the shipped shape code under the same terrain rejection.
    boolean[] shipped = new boolean[1];
    double[] simF = simulate(fit, bounds, map, Math.max(n, 4096),
        new Random(SIM_SEED ^ spec.label().hashCode()), shipped);
    double[] simDensity = new double[RINGS];
    for (double v : simF) simDensity[ringOf(v, RINGS)] += 1.0;
    for (int k = 0; k < RINGS; k++) simDensity[k] = simF.length > 0 ? simDensity[k] / simF.length * RINGS : 0;
    double[] obsSorted = f.clone();
    Arrays.sort(obsSorted);
    double ksSim = simF.length > 0 ? twoSampleKs(obsSorted, simF) : Double.NaN;
    double ksCrit = simF.length > 0
        ? KS_ALPHA_05 * Math.sqrt((double) (n + simF.length) / ((double) n * simF.length))
        : Double.NaN;

    Stats s = new Stats(spec, pts, freq, n, unique, duplicates, 100.0 * duplicates / n, chunksReused, nearPairs,
        nnSorted[0], nnSorted[n / 2], nnMean, nnMean / expectedNn, tv, minR, maxR, bounds,
        possible.areaBlocks() / 1.0e6, expectedNearPairs, recent, shuffledRecent,
        checked, safeLandings, waterLandings, blockedLandings, noFloorLandings, hazardLandings,
        hop / (n - 1), observedDensity, simDensity, fit, ksSim, ksCrit, shipped[0]);

    System.out.printf(Locale.ROOT,
        "[DEBUG_LOG]   recent<=256b/last64=%d (own landings shuffled %.1f, terrain model %.1f) chunksReused=%d%n",
        recent, shuffledRecent, possible.recentRepeats(), chunksReused);
    System.out.printf(Locale.ROOT, "[DEBUG_LOG] %s: n=%d unique=%d dup=%d near<=48=%d (random %.0f) "
            + "nn[min=%.1f p50=%.1f mean=%.1f random=%.1f] CE-R=%.3f possible=%.1f km2 radialTV=%.4f "
            + "r=[%.0f, %.0f] bounds=[%.0f, %.0f] checked=%d safe=%d water=%d blocked=%d noFloor=%d hazard=%d%n",
        spec.displayName(), n, unique, duplicates, nearPairs, expectedNearPairs, s.nnMin(), s.nnP50(), nnMean,
        expectedNn, s.clarkEvansR(), s.possibleAreaKm2(), tv, minR, maxR, bounds.inner(), bounds.outer(), checked,
        safeLandings, waterLandings,
        blockedLandings, noFloorLandings, hazardLandings);
    System.out.printf(Locale.ROOT, "[DEBUG_LOG]   fit %s | D_fit=%.4f D_uniform=%.4f | shipped-sim D=%.4f crit=%.4f%n",
        fit.shapeLine(), fit.ksFit(), fit.ksUniform(), ksSim, ksCrit);
    return s;
  }

  // ---------------------------------------------------------------------------------------------
  // Fit
  // ---------------------------------------------------------------------------------------------

  /** Weighted empirical CDF over sorted area fractions; lo/hi are the CDF just before / at each point. */
  private record Ecdf(double[] x, double[] lo, double[] hi) {}

  private static Ecdf weightedEcdf(double[] f, double[] w) {
    int n = f.length;
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++) order[i] = i;
    Arrays.sort(order, (a, b) -> Double.compare(f[a], f[b]));
    double total = Arrays.stream(w).sum();
    double[] x = new double[n];
    double[] lo = new double[n];
    double[] hi = new double[n];
    double acc = 0;
    for (int i = 0; i < n; i++) {
      x[i] = f[order[i]];
      lo[i] = acc / total;
      acc += w[order[i]];
      hi[i] = acc / total;
    }
    return new Ecdf(x, lo, hi);
  }

  private interface Cdf {
    double at(double f);
  }

  private static double ks(Ecdf e, Cdf model, int step) {
    double d = 0;
    for (int i = 0; i < e.x.length; i += step) {
      double m = model.at(e.x[i]);
      d = Math.max(d, Math.max(Math.abs(e.hi[i] - m), Math.abs(e.lo[i] - m)));
    }
    return d;
  }

  private static Cdf normalCdf(double mean, double deviation, Bounds bounds) {
    double s = deviation / 8.0;
    double e = bounds.normalExponent();
    double a = phi((0.0 - mean) / s);
    double b = phi((1.0 - mean) / s);
    double span = Math.max(1e-12, b - a);
    return f -> Math.max(0.0, Math.min(1.0, (phi((Math.pow(f, e) - mean) / s) - a) / span));
  }

  private static Fit fitRadial(double[] f, double[] w, Bounds bounds) {
    Ecdf e = weightedEcdf(f, w);
    int coarse = Math.max(1, f.length / 400);

    double ksUniform = ks(e, x -> x, 1);

    double lnSum = 0;
    double wSum = 0;
    for (int i = 0; i < f.length; i++) {
      lnSum += w[i] * -Math.log(Math.max(1e-6, f[i]));
      wSum += w[i];
    }
    double weight = Math.max(0.1, Math.min(10.0, lnSum / wSum));
    double invW = 1.0 / weight;
    double ksWeighted = ks(e, x -> Math.pow(x, invW), 1);

    // Means outside [0.05, 0.95] trigger NormalMemoryShape's reflection, which this model omits.
    double bestMean = 0.5;
    double bestDev = 1.0;
    double bestD = Double.MAX_VALUE;
    for (double m = 0.05; m <= 0.9501; m += 0.01) {
      for (double dev = 0.2; dev <= 16.0001; dev += dev < 4.0 ? 0.1 : 0.25) {
        double d = ks(e, normalCdf(m, dev, bounds), coarse);
        if (d < bestD) {
          bestD = d;
          bestMean = m;
          bestDev = dev;
        }
      }
    }
    double m0 = bestMean;
    double d0 = bestDev;
    for (double m = Math.max(0.05, m0 - 0.01); m <= Math.min(0.95, m0 + 0.01) + 1e-9; m += 0.002) {
      for (double dev = Math.max(0.1, d0 - 0.25); dev <= d0 + 0.25 + 1e-9; dev += 0.02) {
        double d = ks(e, normalCdf(m, dev, bounds), 1);
        if (d < bestD) {
          bestD = d;
          bestMean = m;
          bestDev = dev;
        }
      }
    }
    double ksNormal = ks(e, normalCdf(bestMean, bestDev, bounds), 1);

    // Prefer the simplest config that fits about as well: plain CIRCLE, then CIRCLE_NORMAL.
    double best = Math.min(ksUniform, Math.min(ksWeighted, ksNormal));
    if (ksUniform <= best + 0.01) {
      return new Fit(Model.CIRCLE, 1.0, 0.5, 1.0, ksUniform, ksUniform);
    }
    if (ksNormal <= ksWeighted + 0.005) {
      return new Fit(Model.CIRCLE_NORMAL, 1.0, bestMean, bestDev, ksNormal, ksUniform);
    }
    return new Fit(Model.CIRCLE_WEIGHTED, weight, 0.5, 1.0, ksWeighted, ksUniform);
  }

  /** Standard normal CDF (Abramowitz-Stegun 7.1.26, |error| < 1.5e-7). */
  private static double phi(double x) {
    double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x) / Math.sqrt(2.0));
    double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592)
        * t * Math.exp(-x * x / 2.0);
    return x >= 0 ? 0.5 * (1.0 + y) : 0.5 * (1.0 - y);
  }

  private static double twoSampleKs(double[] a, double[] b) {
    int i = 0;
    int j = 0;
    double d = 0;
    while (i < a.length && j < b.length) {
      double v = Math.min(a[i], b[j]);
      while (i < a.length && a[i] <= v) i++;
      while (j < b.length && b[j] <= v) j++;
      d = Math.max(d, Math.abs((double) i / a.length - (double) j / b.length));
    }
    return d;
  }

  // ---------------------------------------------------------------------------------------------
  // Simulation through the shipped shape code
  // ---------------------------------------------------------------------------------------------

  private static MemoryShape<?> buildShape(Fit fit, Bounds b, Random rng, boolean dualLayer) {
    MemoryShape<?> shape;
    switch (fit.model()) {
      case CIRCLE_NORMAL -> {
        Circle_Normal s = new Circle_Normal();
        s.set(NormalDistributionParams.radius, b.outerChunks());
        s.set(NormalDistributionParams.centerRadius, b.innerChunks());
        s.set(NormalDistributionParams.mean, fit.mean());
        s.set(NormalDistributionParams.deviation, fit.deviation());
        shape = s;
      }
      case CIRCLE_WEIGHTED -> {
        Circle s = new Circle();
        s.set(GenericMemoryShapeParams.radius, b.outerChunks());
        s.set(GenericMemoryShapeParams.centerRadius, b.innerChunks());
        s.set(GenericMemoryShapeParams.weight, fit.weight());
        shape = s;
      }
      default -> {
        if (dualLayer) {
          CircleOptimizedDualLayer s = new CircleOptimizedDualLayer("CIRCLE");
          s.set(GenericMemoryShapeParams.radius, b.outerChunks());
          s.set(GenericMemoryShapeParams.centerRadius, b.innerChunks());
          shape = s;
        } else {
          // Same index distribution as the dual-layer CIRCLE: weight 1 is even by area.
          Circle s = new Circle();
          s.set(GenericMemoryShapeParams.radius, b.outerChunks());
          s.set(GenericMemoryShapeParams.centerRadius, b.innerChunks());
          s.set(GenericMemoryShapeParams.weight, 1.0);
          shape = s;
        }
      }
    }
    shape.setRng(rng);
    return shape;
  }

  /**
   * Draws {@code target} landings from the fitted config through {@code MemoryShape.sample} and
   * {@code locationToXZ}, rejecting chunks outside the ring (the dual-layer CIRCLE indexes the
   * ring's bounding square of macro-cells and drops the corners) and non-safe chunks, like a live
   * search would.
   *
   * @return sorted area fractions of the accepted landings
   */
  private static double[] simulate(Fit fit, Bounds b, LosslessChunkOutcomeMap map, int target, Random rng,
      boolean[] shipped) throws Exception {
    Method sample = MemoryShape.class.getDeclaredMethod("sample", double.class);
    sample.setAccessible(true);
    for (boolean dualLayer : new boolean[] {true, false}) {
      if (dualLayer && fit.model() != Model.CIRCLE) continue;
      try {
        MemoryShape<?> shape = buildShape(fit, b, rng, dualLayer);
        double range = shape.getRange();
        MutableRTPCoords coords = new MutableRTPCoords(0, 0);
        double[] out = new double[target];
        int accepted = 0;
        long attempts = 0;
        while (accepted < target && attempts < (long) target * 64) {
          attempts++;
          long loc = (long) (double) (Double) sample.invoke(shape, range);
          shape.locationToXZ(loc, coords);
          double rc = Math.hypot(coords.x + 0.5, coords.z + 0.5) * 16.0;
          if (rc < b.inner() || rc > b.outer()) continue;
          if (map != null && map.getOutcome(coords.x, coords.z) != LosslessChunkOutcomeMap.OUTCOME_SAFE) continue;
          double r = Math.hypot(coords.x * 16.0 + rng.nextInt(16), coords.z * 16.0 + rng.nextInt(16));
          out[accepted++] = b.areaFraction(r);
        }
        if (accepted < target / 2) {
          throw new IllegalStateException("implausible sample: accepted=" + accepted + " of " + attempts);
        }
        double[] res = Arrays.copyOf(out, accepted);
        Arrays.sort(res);
        shipped[0] = dualLayer || fit.model() != Model.CIRCLE;
        return res;
      } catch (Throwable t) {
        System.out.println("[DEBUG_LOG] Simulation via " + (dualLayer ? "CircleOptimizedDualLayer" : fit.model())
            + " failed: " + t);
      }
    }
    return new double[0];
  }

  // ---------------------------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------------------------

  private static final int MARGIN = 30;
  private static final int COLS = 3;
  private static final int CARD_W = 540;
  private static final int PLOT = CARD_W - 32;
  private static final int CARD_HEADER = 52;
  private static final int RADIAL_H = 132;
  private static final int METRICS_H = 92;
  private static final int CALLOUT_H = 84;
  private static final int CARD_H = CARD_HEADER + PLOT + 12 + RADIAL_H + 10 + METRICS_H + CALLOUT_H + 16;
  private static final int HEADER_H = 112;
  private static final int BANNER_H = 116;
  private static final double PLOT_RANGE = 17000.0;

  private static final Color BG = new Color(0x0C1017);
  private static final Color CARD = new Color(0x131B24);
  private static final Color CARD_EDGE = new Color(0x233140);
  private static final Color TEXT = new Color(0xCFD8DC);
  private static final Color MUTED = new Color(0x90A4AE);
  private static final Color GOOD = new Color(0x38EF7D);
  private static final Color BAD = new Color(0xFF5252);
  private static final Color DUPLICATE = new Color(0xFF1744);

  private void renderChart(List<Stats> stats, LosslessChunkOutcomeMap map, Map<String, String> runOf,
      boolean previewOnly) throws Exception {
    int rows = (stats.size() + COLS - 1) / COLS;
    int width = COLS * CARD_W + (COLS + 1) * MARGIN;
    int height = HEADER_H + rows * CARD_H + (rows - 1) * MARGIN + MARGIN + BANNER_H + MARGIN;

    BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setColor(BG);
    g.fillRect(0, 0, width, height);

    int n = stats.stream().mapToInt(Stats::n).max().orElse(0);
    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
    g.drawString("WHERE EACH RTP PLUGIN LANDS ITS PLAYERS", MARGIN, 40);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
    g.setColor(MUTED);
    List<String> runIds = runOf.values().stream().distinct().sorted(java.util.Comparator.reverseOrder()).toList();
    String runText = runIds.size() == 1 ? "stress run " + runIds.get(0)
        : "stress runs " + String.join(", ", runIds) + " (newest finished phase per plugin)";
    g.drawString(String.format(Locale.ROOT,
        "Paper 26.2 %s | up to %,d teleports per plugin | every plugin set to the same circle: "
            + "1,024 to 16,384 blocks around 0,0 | pregenerated terrain underneath", runText, n), MARGIN, 64);
    drawLegend(g, MARGIN, 92);

    // LeafRTP replaying its own config sets the floor the terrain model can resolve: the sim
    // rejects by chunk-level mask, the live plugin by block-level checks.
    double selfCheck = stats.stream().filter(s -> s.spec().label().equals("rtp")).mapToDouble(Stats::ksSim)
        .filter(d -> !Double.isNaN(d)).findFirst().orElse(Double.NaN);
    BufferedImage terrain = renderTerrain(map);
    for (int i = 0; i < stats.size(); i++) {
      int col = i % COLS;
      int row = i / COLS;
      int x = MARGIN + col * (CARD_W + MARGIN);
      int y = HEADER_H + row * (CARD_H + MARGIN);
      renderCard(g, x, y, stats.get(i), runOf.get(stats.get(i).spec().label()), terrain, selfCheck);
    }

    int bannerY = HEADER_H + rows * CARD_H + (rows - 1) * MARGIN + MARGIN;
    renderBanner(g, MARGIN, bannerY, width - 2 * MARGIN, BANNER_H);
    g.dispose();

    if (previewOnly) {
      ChartOutputHelper.writeReportOnly(img, "player_distribution", "cross_plugin_destinations_scatter_preview.png");
      System.out.printf("[DEBUG_LOG] Incomplete run: saved preview to %s%n",
          ChartOutputHelper.getReportFile("player_distribution", "cross_plugin_destinations_scatter_preview.png")
              .getAbsolutePath());
      return;
    }
    ChartOutputHelper.writeChart(img, "player_distribution", "cross_plugin_destinations_scatter_chart.png");
    ChartOutputHelper.writeChart(img, "player_distribution", "cross_plugin_destinations_scatter_chart_16k.png");
    System.out.printf("[DEBUG_LOG] Saved chart to %s%n",
        ChartOutputHelper.getDocsAssetFile("cross_plugin_destinations_scatter_chart.png").getAbsolutePath());
  }

  private static void drawLegend(Graphics2D g, int x, int y) {
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    g.setColor(DUPLICATE);
    g.fillOval(x, y - 8, 8, 8);
    g.setColor(TEXT);
    g.drawString("Exact duplicate landing", x + 14, y);
    x += 170;
    for (PluginSpec spec : PLUGINS) {
      g.setColor(spec.accent());
      g.fillRect(x, y - 7, 6, 6);
      x += 10;
    }
    g.setColor(TEXT);
    g.drawString("Landing (plugin colour)", x + 4, y);
    x += 170;
    g.setColor(new Color(0x122416));
    g.fillRect(x, y - 8, 10, 10);
    g.setColor(TEXT);
    g.drawString("Land", x + 16, y);
    x += 60;
    g.setColor(new Color(0x0A131F));
    g.fillRect(x, y - 8, 10, 10);
    g.setColor(new Color(0x1E2C3C));
    g.drawRect(x, y - 8, 10, 10);
    g.setColor(TEXT);
    g.drawString("Water", x + 16, y);
    x += 70;
    g.setColor(new Color(0xFF, 0x52, 0x52, 160));
    g.drawOval(x, y - 9, 10, 10);
    g.setColor(TEXT);
    g.drawString("1,024-block exclusion / 16,384-block edge", x + 16, y);
  }

  private static BufferedImage renderTerrain(LosslessChunkOutcomeMap map) {
    BufferedImage terrain = new BufferedImage(PLOT, PLOT, BufferedImage.TYPE_INT_RGB);
    for (int py = 0; py < PLOT; py++) {
      double bz = -PLOT_RANGE + (py + 0.5) / PLOT * 2.0 * PLOT_RANGE;
      int cz = (int) Math.floor(bz / 16.0);
      for (int px = 0; px < PLOT; px++) {
        double bx = -PLOT_RANGE + (px + 0.5) / PLOT * 2.0 * PLOT_RANGE;
        int cx = (int) Math.floor(bx / 16.0);
        int rgb = 0x0A0E14;
        if (map != null) {
          byte o = map.getOutcome(cx, cz);
          if (o == LosslessChunkOutcomeMap.OUTCOME_SAFE) rgb = 0x122416;
          else if (o == LosslessChunkOutcomeMap.OUTCOME_WATER) rgb = 0x0A131F;
          else if (o == LosslessChunkOutcomeMap.OUTCOME_UNGENERATED) rgb = 0x080B10;
          else rgb = 0x181216;
        }
        terrain.setRGB(px, py, rgb);
      }
    }
    return terrain;
  }

  private static int toPx(double block, int origin) {
    return origin + (int) ((block + PLOT_RANGE) / (2.0 * PLOT_RANGE) * PLOT);
  }

  private void renderCard(Graphics2D g, int x, int y, Stats s, String runId, BufferedImage terrain,
      double selfCheck) {
    g.setColor(CARD);
    g.fillRoundRect(x, y, CARD_W, CARD_H, 12, 12);
    g.setColor(CARD_EDGE);
    g.drawRoundRect(x, y, CARD_W, CARD_H, 12, 12);

    Color accent = s.spec().accent();
    g.setColor(new Color(0x18222E));
    g.fillRoundRect(x + 2, y + 2, CARD_W - 4, CARD_HEADER - 4, 10, 10);
    g.setColor(accent);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
    g.drawString(s.spec().displayName(), x + 16, y + 24);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    g.setColor(TEXT);
    g.drawString(s.spec().sampling(), x + 16, y + 41);
    if (runId != null) {
      String runLabel = "run " + runId;
      g.setColor(MUTED);
      g.drawString(runLabel, x + CARD_W - 16 - g.getFontMetrics().stringWidth(runLabel), y + 24);
    }

    int plotX = x + 16;
    int plotY = y + CARD_HEADER;
    g.drawImage(terrain, plotX, plotY, null);
    g.setColor(new Color(0x1B2632));
    g.drawRect(plotX, plotY, PLOT, PLOT);
    g.setColor(new Color(0x18, 0x24, 0x30));
    g.drawLine(plotX + PLOT / 2, plotY, plotX + PLOT / 2, plotY + PLOT);
    g.drawLine(plotX, plotY + PLOT / 2, plotX + PLOT, plotY + PLOT / 2);

    double outerPx = OUTER_R / (2.0 * PLOT_RANGE) * PLOT;
    double innerPx = INNER_R / (2.0 * PLOT_RANGE) * PLOT;
    double cxPx = plotX + PLOT / 2.0;
    double czPx = plotY + PLOT / 2.0;
    g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 90));
    g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {4f, 4f}, 0f));
    g.draw(new Ellipse2D.Double(cxPx - outerPx, czPx - outerPx, 2 * outerPx, 2 * outerPx));
    g.setStroke(new BasicStroke(1f));
    g.setColor(new Color(0xFF, 0x52, 0x52, 140));
    g.draw(new Ellipse2D.Double(cxPx - innerPx, czPx - innerPx, 2 * innerPx, 2 * innerPx));

    Color dot = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 150);
    List<int[]> dupes = new ArrayList<>();
    for (Map.Entry<Point2D, Integer> e : s.freq().entrySet()) {
      int px = toPx(e.getKey().x, plotX);
      int pz = toPx(e.getKey().z, plotY);
      if (px < plotX || px >= plotX + PLOT || pz < plotY || pz >= plotY + PLOT) continue;
      if (e.getValue() > 1) {
        dupes.add(new int[] {px, pz});
      } else {
        g.setColor(dot);
        g.fillRect(px - 1, pz - 1, 3, 3);
      }
    }
    for (int[] d : dupes) {
      g.setColor(new Color(0xFF, 0x17, 0x44, 220));
      g.fillOval(d[0] - 3, d[1] - 3, 7, 7);
      g.setColor(Color.WHITE);
      g.fillRect(d[0] - 1, d[1] - 1, 3, 3);
    }

    if (s.n() == 0) {
      g.setColor(BAD);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
      g.drawString("No successful landings in this run", plotX + 120, plotY + PLOT / 2 - 12);
    } else if (!s.bounds().configured()) {
      g.setColor(new Color(0x0C, 0x10, 0x17, 210));
      g.fillRect(plotX + 1, plotY + 1, PLOT - 1, 36);
      g.setColor(BAD);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
      g.drawString(String.format(Locale.ROOT, "Landed %,.0f to %,.0f b, not the configured 1,024 to 16,384 b",
          s.minR(), s.maxR()), plotX + 8, plotY + 16);
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
      g.drawString("The fit below uses the ring this plugin actually landed in.", plotX + 8, plotY + 31);
    }

    int radialY = plotY + PLOT + 12;
    renderRadial(g, x + 16, radialY, CARD_W - 32, RADIAL_H, s);
    int metricsY = radialY + RADIAL_H + 10;
    renderMetrics(g, x + 16, metricsY, CARD_W - 32, s);
    renderCallout(g, x + 10, metricsY + METRICS_H, CARD_W - 20, CALLOUT_H - 6, s, selfCheck);
  }

  private static void renderRadial(Graphics2D g, int x, int y, int w, int h, Stats s) {
    g.setColor(new Color(0x0F161E));
    g.fillRect(x, y, w, h);
    g.setColor(new Color(0x1B2632));
    g.drawRect(x, y, w, h);

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
    g.setColor(MUTED);
    g.drawString("Landings per unit area, 16 equal-area rings (1.0 = even by area)", x + 6, y + 13);

    int top = y + 20;
    int bottom = y + h - 16;
    int left = x + 6;
    int right = x + w - 6;
    double yMax = 2.0;
    for (int k = 0; k < RINGS; k++) {
      yMax = Math.max(yMax, Math.max(s.observedDensity()[k], s.simulatedDensity()[k]));
    }
    yMax = Math.min(4.0, Math.ceil(yMax * 2) / 2.0);
    double barW = (double) (right - left) / RINGS;

    Color accent = s.spec().accent();
    for (int k = 0; k < RINGS; k++) {
      double v = Math.min(yMax, s.observedDensity()[k]);
      int bh = (int) Math.round(v / yMax * (bottom - top));
      g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 150));
      g.fillRect((int) (left + k * barW) + 1, bottom - bh, (int) barW - 2, bh);
    }
    int evenY = bottom - (int) Math.round(1.0 / yMax * (bottom - top));
    g.setColor(new Color(0x90, 0xA4, 0xAE, 140));
    g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {3f, 3f}, 0f));
    g.drawLine(left, evenY, right, evenY);
    g.setStroke(new BasicStroke(2f));
    g.setColor(Color.WHITE);
    int prevX = -1;
    int prevY = -1;
    for (int k = 0; k < RINGS; k++) {
      double v = Math.min(yMax, s.simulatedDensity()[k]);
      int px = (int) (left + (k + 0.5) * barW);
      int py = bottom - (int) Math.round(v / yMax * (bottom - top));
      if (prevX >= 0) g.drawLine(prevX, prevY, px, py);
      g.fillOval(px - 2, py - 2, 5, 5);
      prevX = px;
      prevY = py;
    }
    g.setStroke(new BasicStroke(1f));

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
    g.setColor(MUTED);
    g.drawString(String.format(Locale.ROOT, "inner %,.0f b", s.bounds().inner()), left, y + h - 4);
    g.drawString(String.format(Locale.ROOT, "outer %,.0f b", s.bounds().outer()), right - 66, y + h - 4);
    g.drawString(String.format(Locale.ROOT, "max %.1f", yMax), right - 40, top + 8);
    g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 200));
    g.fillRect(x + w / 2 - 92, y + h - 11, 8, 8);
    g.setColor(TEXT);
    g.drawString("observed", x + w / 2 - 80, y + h - 4);
    g.setColor(Color.WHITE);
    g.drawLine(x + w / 2 - 28, y + h - 7, x + w / 2 - 14, y + h - 7);
    g.drawString("LeafRTP config below (simulated)", x + w / 2 - 10, y + h - 4);
  }

  private static void renderMetrics(Graphics2D g, int x, int y, int w, Stats s) {
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    int line = y + 12;
    int col2 = x + w / 2 + 6;

    g.setColor(TEXT);
    g.drawString(String.format(Locale.ROOT, "Landed: %,d  |  unique: %,d", s.n(), s.unique()), x, line);
    g.setColor(s.chunksReused() == 0 ? GOOD : TEXT);
    g.drawString(String.format(Locale.ROOT, "Same chunk twice: %,d  |  exact spot: %,d", s.chunksReused(),
        s.duplicates()), col2, line);

    line += 17;
    // No baseline: the per-chunk terrain map cannot resolve how clustered landable ground is at
    // 48 b, and spacing beyond one chunk is not claimed while expand is off.
    g.setColor(TEXT);
    g.drawString(String.format(Locale.ROOT, "Pairs within 48 b: %,d", s.nearPairs()), x, line);
    // Baseline: the same landings in random order, so only the ordering is compared.
    double ratio = s.expectedRecentRepeats() > 0 ? s.recentRepeats() / s.expectedRecentRepeats() : 1.0;
    g.setColor(ratio <= 0.9 ? GOOD : ratio <= 1.1 ? TEXT : BAD);
    g.drawString(String.format(Locale.ROOT, "<256 b of last 64: %,d (shuffled ~%,.0f)", s.recentRepeats(),
        s.expectedRecentRepeats()), col2, line);

    line += 17;
    // Below 1 is clustered (players land near each other); above 1 is more evenly spread than random.
    g.setColor(s.clarkEvansR() >= 0.97 ? GOOD : s.clarkEvansR() >= 0.9 ? TEXT : BAD);
    g.drawString(String.format(Locale.ROOT, "Clark-Evans R: %.3f (1 random, >1 spread)", s.clarkEvansR()), x, line);
    g.setColor(s.radialTv() < 0.05 ? GOOD : TEXT);
    g.drawString(String.format(Locale.ROOT, "Radial TV vs even-by-area: %.3f", s.radialTv()), col2, line);

    line += 17;
    boolean inBand = s.n() == 0 || (s.minR() >= INNER_R - 16 && s.maxR() <= OUTER_R + 16);
    g.setColor(inBand ? TEXT : BAD);
    g.drawString(String.format(Locale.ROOT, "Distance: %,.0f to %,.0f b  |  avg hop %,.0f b", s.minR(), s.maxR(),
        s.avgHop()), x, line);

    line += 17;
    if (s.checked() == 0) {
      g.setColor(MUTED);
      g.drawString("Landing block check: no landings checked", x, line);
    } else {
      double safePct = 100.0 * s.safeLandings() / s.checked();
      g.setColor(safePct >= 99.0 ? GOOD : safePct >= 90.0 ? TEXT : BAD);
      g.drawString(String.format(Locale.ROOT,
          "Block check: %.1f%% safe of %,d | water %,d, in leaves %,d, no floor %,d, hazard %,d",
          safePct, s.checked(), s.waterLandings(), s.blockedLandings(), s.noFloorLandings(),
          s.hazardLandings()), x, line);
    }
  }

  private static void renderCallout(Graphics2D g, int x, int y, int w, int h, Stats s, double selfCheck) {
    g.setColor(new Color(0x10261A));
    g.fillRoundRect(x, y, w, h, 8, 8);
    g.setColor(new Color(0x38, 0xEF, 0x7D, 160));
    g.drawRoundRect(x, y, w, h, 8, 8);

    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
    g.setColor(GOOD);
    String title = s.spec().label().equals("rtp")
        ? "LeafRTP config used for this run"
        : "Same spread in LeafRTP: regions/<name>.yml shape block";
    g.drawString(title, x + 10, y + 16);

    g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
    g.setColor(Color.WHITE);
    g.drawString(s.fit().shapeLine(), x + 10, y + 35);
    g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
    g.setColor(TEXT);
    g.drawString(String.format(Locale.ROOT, "radius: %d  centerRadius: %d   (chunks)", s.bounds().outerChunks(),
        s.bounds().innerChunks()), x + 10, y + 51);

    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
    String verdict;
    Color verdictColor;
    if (Double.isNaN(s.ksSim())) {
      verdict = "Not simulated (no landings or shape unavailable)";
      verdictColor = MUTED;
    } else {
      double floor = Double.isNaN(selfCheck) ? s.ksCrit() : Math.max(s.ksCrit(), selfCheck * 1.1);
      boolean match = s.ksSim() <= floor;
      String tolerance = floor > s.ksCrit()
          ? String.format(Locale.ROOT, "LeafRTP self-check %.3f", selfCheck)
          : String.format(Locale.ROOT, "5%% limit %.3f", s.ksCrit());
      verdict = String.format(Locale.ROOT, "Replayed through LeafRTP's shape code: KS D %.3f (%s) - %s",
          s.ksSim(), tolerance, match ? "same radial spread" : "closest fit");
      verdictColor = match ? GOOD : new Color(0xFFD54F);
    }
    g.setColor(verdictColor);
    g.drawString(verdict, x + 10, y + 69);
  }

  private static void renderBanner(Graphics2D g, int x, int y, int w, int h) {
    g.setColor(new Color(0x10261A));
    g.fillRoundRect(x, y, w, h, 12, 12);
    g.setColor(new Color(0x38, 0xEF, 0x7D, 180));
    g.drawRoundRect(x, y, w, h, 12, 12);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
    g.setColor(GOOD);
    g.drawString("Every spread above can be set up in LeafRTP through configuration.", x + 18, y + 28);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(TEXT);
    g.drawString("The green box under each panel is the region shape block that gives that plugin's distance-from-center "
        + "distribution: CIRCLE for even-by-area, CIRCLE_NORMAL (mean, deviation) for a bell curve,", x + 18, y + 50);
    g.drawString("or the weighted spiral circle for a power curve. Each is fitted to this run with terrain rejection "
        + "factored out, then replayed through LeafRTP's own shape code over the same terrain and KS-tested against "
        + "the observed landings.", x + 18, y + 68);
    g.drawString("The tolerance is the larger of the 5% KS limit and LeafRTP's own config replayed against its own "
        + "landings, which is as close as the chunk-level terrain map can resolve.", x + 18, y + 84);
    g.drawString("Spacing baselines (48-block pairs, Clark-Evans R, repeats within 256 blocks of the previous 64 "
        + "landings) use random points drawn at each plugin's own landing rate per terrain class and distance band.",
        x + 18, y + 100);
  }
}
