package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.anvil.AnvilReader;
import io.github.dailystruggle.rtp.anvil.ColumnProbe;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.DownsampledDualLayerSquare;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Empirical vetting of sequential and parallel candidate placements against REAL .MCA world data
 * to evaluate qualitative environmental uniqueness:
 * 1. Surface Block Palette Diversity (Cosine & Jaccard distance over visible view horizon).
 * 2. Biome Diversity (Unique biomes encountered and inter-arrival biome transition frequency).
 * 3. Elevation Variance (Delta Y spread between landing points).
 */
public class McaQualitativeUniquenessBenchmarkTest {

  private static final int VIEW_DISTANCE_CHUNKS = 8; // 8 chunks view distance horizon

  public record ChunkCoord(int cx, int cz) {
    double euclideanDistance(ChunkCoord other) {
      int dx = this.cx - other.cx;
      int dz = this.cz - other.cz;
      return Math.sqrt(dx * dx + dz * dz);
    }
  }

  public record ViewHorizonEnvironment(
      int surfaceY,
      String centerBiome,
      BiomeClass centerBiomeClass,
      Map<String, Integer> blockPaletteHistogram,
      Set<String> presentBiomes
  ) {
    /**
     * Cosine distance between block palette histograms in [0.0 .. 1.0].
     * 0.0 = identical palettes; 1.0 = completely disjoint palettes.
     */
    public double blockPaletteCosineDistance(ViewHorizonEnvironment other) {
      if (this.blockPaletteHistogram.isEmpty() || other.blockPaletteHistogram.isEmpty()) {
        return 1.0;
      }
      double dot = 0.0;
      double mag1 = 0.0;
      double mag2 = 0.0;

      for (Map.Entry<String, Integer> e : this.blockPaletteHistogram.entrySet()) {
        int v1 = e.getValue();
        mag1 += (double) v1 * v1;
        Integer v2 = other.blockPaletteHistogram.get(e.getKey());
        if (v2 != null) {
          dot += (double) v1 * v2;
        }
      }
      for (int v2 : other.blockPaletteHistogram.values()) {
        mag2 += (double) v2 * v2;
      }

      if (mag1 <= 0.0 || mag2 <= 0.0) return 1.0;
      double sim = dot / (Math.sqrt(mag1) * Math.sqrt(mag2));
      return Math.max(0.0, Math.min(1.0, 1.0 - sim));
    }

    /**
     * Biome distance: 0.0 if identical exact biome; 0.5 if same coarse class; 1.0 if different class.
     */
    public double biomeDistance(ViewHorizonEnvironment other) {
      if (this.centerBiome.equals(other.centerBiome)) return 0.0;
      if (this.centerBiomeClass == other.centerBiomeClass && this.centerBiomeClass != BiomeClass.UNKNOWN) {
        return 0.5;
      }
      return 1.0;
    }
  }

  public record QualitativeMetrics(
      String strategyName,
      int totalPlacements,
      double avgSeqPaletteDist,
      double minSeqPaletteDist,
      double maxSeqPaletteDist,
      double[] seqPaletteDistances,
      double avgSeqBiomeDist,
      double seqBiomeChangePercent,
      double avgSeqDeltaY,
      double maxSeqDeltaY,
      double avgParNearestPaletteDist,
      double minParNearestPaletteDist,
      double[] parNearestPaletteDistances,
      double compositeUniquenessScore
  ) {}

  @Test
  @DisplayName("Vet Sequential & Parallel Placements on Real MCA World Data for Qualitative Palette & Biome Diversity")
  public void testMcaQualitativeUniqueness() throws Exception {
    System.out.println("[DEBUG_LOG] === MCA Qualitative Uniqueness Benchmark ===");

    Path regionDir = Path.of("testdata-world/overworld/region");
    if (!Files.isDirectory(regionDir)) {
      regionDir = Path.of("C:\\GameServers\\Minecraft\\testServer\\RTP-Folia\\26.1\\world\\dimensions\\minecraft\\overworld\\region");
    }
    assertTrue(Files.isDirectory(regionDir), "Region directory must exist for MCA testing");

    System.out.println("[DEBUG_LOG] Loading MCA regions from: " + regionDir);
    RealWorldVerdictMask mask = RealWorldVerdictMask.load(regionDir, RealWorldVerdictMask.SHIPPED_UNSAFE_BLOCKS, 32);
    int inscribedR = Math.min(mask.inscribedRadius(), 128);
    System.out.printf("[DEBUG_LOG] Loaded %d MCA files | Inscribed Radius = %d chunks%n",
        mask.regionFilesRead(), inscribedR);

    // Bounded region provider: on-demand read with small cache (max 4 files = ~20MB RAM)
    RegionFileProvider regionProvider = new RegionFileProvider(regionDir, 4);

    int pointEdgeP = 32;
    int testCount = 150;

    // 1. Legacy Unconstrained (S = 1)
    SquareOptimizedDualLayer s1Shape = new SquareOptimizedDualLayer("UNCONSTRAINED_S1", pointEdgeP);
    s1Shape.set(GenericMemoryShapeParams.radius, (long) inscribedR);
    s1Shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    s1Shape.set(GenericMemoryShapeParams.centerX, 0L);
    s1Shape.set(GenericMemoryShapeParams.centerZ, 0L);
    s1Shape.set(GenericMemoryShapeParams.uniquePlacements, 0);

    // 2. Spaced S = 64 (R_u = 8 chunks spacing)
    DownsampledDualLayerSquare s64Shape = new DownsampledDualLayerSquare("SPACED_S64", pointEdgeP);
    s64Shape.set(GenericMemoryShapeParams.radius, (long) inscribedR);
    s64Shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    s64Shape.set(GenericMemoryShapeParams.centerX, 0L);
    s64Shape.set(GenericMemoryShapeParams.centerZ, 0L);
    s64Shape.setExplicitStride(64);

    // 3. Spaced S = 256 (R_u = 16 chunks spacing)
    DownsampledDualLayerSquare s256Shape = new DownsampledDualLayerSquare("SPACED_S256", pointEdgeP);
    s256Shape.set(GenericMemoryShapeParams.radius, (long) inscribedR);
    s256Shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    s256Shape.set(GenericMemoryShapeParams.centerX, 0L);
    s256Shape.set(GenericMemoryShapeParams.centerZ, 0L);
    s256Shape.setExplicitStride(256);

    // Sample safe valid placements for each strategy
    List<ChunkCoord> ptsS1 = sampleSafePlacements(s1Shape, mask, inscribedR, testCount);
    List<ChunkCoord> ptsS64 = sampleSafePlacements(s64Shape, mask, inscribedR, testCount);
    List<ChunkCoord> ptsS256 = sampleSafePlacements(s256Shape, mask, inscribedR, testCount);

    System.out.printf("[DEBUG_LOG] Sampled %d safe candidate landings for each model%n", testCount);

    // Evaluate qualitative environmental horizons for each point
    List<ViewHorizonEnvironment> envsS1 = evaluateEnvironments(ptsS1, regionProvider);
    List<ViewHorizonEnvironment> envsS64 = evaluateEnvironments(ptsS64, regionProvider);
    List<ViewHorizonEnvironment> envsS256 = evaluateEnvironments(ptsS256, regionProvider);

    // Calculate quantitative metrics
    QualitativeMetrics qS1 = computeQualitativeMetrics("1. Native Unconstrained (S = 1)", ptsS1, envsS1);
    QualitativeMetrics qS64 = computeQualitativeMetrics("2. Spaced S = 64 (Ru = 8c)", ptsS64, envsS64);
    QualitativeMetrics qS256 = computeQualitativeMetrics("3. Spaced S = 256 (Ru = 16c)", ptsS256, envsS256);

    printQualitativeSummary(qS1);
    printQualitativeSummary(qS64);
    printQualitativeSummary(qS256);

    // Render Qualitative Chart PNG
    File chartFile = new File("mca_qualitative_uniqueness_chart.png");
    File rootChartFile = new File("../mca_qualitative_uniqueness_chart.png");
    File reportFile = new File("build/reports/player_distribution/mca_qualitative_uniqueness_chart.png");
    File rootReportFile = new File("../build/reports/player_distribution/mca_qualitative_uniqueness_chart.png");

    renderQualitativeChart(qS1, qS64, qS256, inscribedR, chartFile);
    renderQualitativeChart(qS1, qS64, qS256, inscribedR, rootChartFile);
    reportFile.getParentFile().mkdirs();
    renderQualitativeChart(qS1, qS64, qS256, inscribedR, reportFile);
    rootReportFile.getParentFile().mkdirs();
    renderQualitativeChart(qS1, qS64, qS256, inscribedR, rootReportFile);

    assertTrue(chartFile.exists(), "Qualitative uniqueness chart must exist");
    System.out.println("[DEBUG_LOG] Successfully rendered chart to: " + chartFile.getAbsolutePath());
  }

  private static void printQualitativeSummary(QualitativeMetrics q) {
    System.out.printf("[DEBUG_LOG] %s:%n" +
            "   Sequential Palette Cosine Dist: Avg = %.3f | Min = %.3f | Max = %.3f%n" +
            "   Sequential Biome Transition Rate: %.1f%% | Avg Biome Dist = %.3f%n" +
            "   Sequential Elevation Delta Y: Avg = %.1f blk | Max = %.0f blk%n" +
            "   Parallel Nearest-Neighbor Palette Dist: Avg = %.3f | Min = %.3f%n" +
            "   Composite Environmental Uniqueness Score: %.1f / 100%n",
        q.strategyName,
        q.avgSeqPaletteDist, q.minSeqPaletteDist, q.maxSeqPaletteDist,
        q.seqBiomeChangePercent, q.avgSeqBiomeDist,
        q.avgSeqDeltaY, q.maxSeqDeltaY,
        q.avgParNearestPaletteDist, q.minParNearestPaletteDist,
        q.compositeUniquenessScore * 100.0);
  }

  private static List<ChunkCoord> sampleSafePlacements(
      SquareOptimizedDualLayer shape, RealWorldVerdictMask mask, int R, int count) {
    List<ChunkCoord> list = new ArrayList<>(count);
    int attempts = 0;
    while (list.size() < count && attempts < count * 50) {
      attempts++;
      int[] res = shape.select();
      if (res == null) continue;
      int cx = res[0];
      int cz = res[1];
      if (Math.abs(cx) <= R && Math.abs(cz) <= R && mask.isOccupied(cx, cz)) {
        list.add(new ChunkCoord(cx, cz));
      }
    }
    return list;
  }

  private static final class RegionFileProvider {
    private final Path regionDir;
    private final int maxCached;
    private final java.util.LinkedHashMap<Long, byte[]> lruCache;

    public RegionFileProvider(Path regionDir, int maxCached) {
      this.regionDir = regionDir;
      this.maxCached = maxCached;
      this.lruCache = new java.util.LinkedHashMap<>(maxCached + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
          return size() > RegionFileProvider.this.maxCached;
        }
      };
    }

    public synchronized byte[] getRegionBytes(int rx, int rz) {
      long key = (((long) rx) << 32) | (rz & 0xFFFFFFFFL);
      byte[] cached = lruCache.get(key);
      if (cached != null) return cached;

      Path f = regionDir.resolve(String.format("r.%d.%d.mca", rx, rz));
      if (!Files.isRegularFile(f)) return null;
      try {
        byte[] bytes = Files.readAllBytes(f);
        lruCache.put(key, bytes);
        return bytes;
      } catch (IOException e) {
        return null;
      }
    }
  }

  private static List<ViewHorizonEnvironment> evaluateEnvironments(
      List<ChunkCoord> coords, RegionFileProvider provider) {

    List<ViewHorizonEnvironment> list = new ArrayList<>(coords.size());
    for (ChunkCoord pt : coords) {
      list.add(probeEnvironment(pt.cx, pt.cz, provider));
    }
    return list;
  }

  private static ViewHorizonEnvironment probeEnvironment(
      int centerCx, int centerCz, RegionFileProvider provider) {

    // Probe center chunk for surface Y & primary biome
    ColumnProbe centerProbe = probeChunk(centerCx, centerCz, provider);
    int surfaceY = (centerProbe != null && centerProbe.hasHeightmap()) ? centerProbe.heightmapTopY() : 64;
    String centerBiome = (centerProbe != null) ? centerProbe.biomeAt(surfaceY) : "minecraft:plains";
    if (centerBiome == null) centerBiome = "minecraft:plains";
    BiomeClass centerClass = BiomeClass.classify(centerBiome);

    // Sample visible view horizon (sample a grid of chunks in radius VIEW_DISTANCE_CHUNKS)
    Map<String, Integer> paletteHist = new HashMap<>();
    Set<String> presentBiomes = new HashSet<>();
    presentBiomes.add(centerBiome);

    int step = 2; // sample every 2nd chunk in the view bubble to balance perf and fidelity
    for (int dx = -VIEW_DISTANCE_CHUNKS; dx <= VIEW_DISTANCE_CHUNKS; dx += step) {
      for (int dz = -VIEW_DISTANCE_CHUNKS; dz <= VIEW_DISTANCE_CHUNKS; dz += step) {
        int cx = centerCx + dx;
        int cz = centerCz + dz;

        ColumnProbe probe = probeChunk(cx, cz, provider);
        if (probe == null || !probe.hasHeightmap()) continue;

        int topY = probe.heightmapTopY();
        String biome = probe.biomeAt(topY);
        if (biome != null) presentBiomes.add(biome);

        // Read surface block and ground layers (surface down to surface - 3)
        for (int y = topY; y >= Math.max(probe.minY(), topY - 3); y--) {
          String block = probe.blockAt(y);
          if (block != null && !block.equals("minecraft:air") && !block.equals("minecraft:cave_air")) {
            paletteHist.merge(block, 1, Integer::sum);
          }
        }
      }
    }

    return new ViewHorizonEnvironment(surfaceY, centerBiome, centerClass, paletteHist, presentBiomes);
  }

  private static ColumnProbe probeChunk(int cx, int cz, RegionFileProvider provider) {
    int rx = cx >> 5;
    int rz = cz >> 5;
    byte[] regionBytes = provider.getRegionBytes(rx, rz);
    if (regionBytes == null) return null;

    int lx = cx & 31;
    int lz = cz & 31;
    try {
      return AnvilReader.readColumnProbe(regionBytes, lx, lz, -64, 320);
    } catch (Exception e) {
      return null;
    }
  }

  private static QualitativeMetrics computeQualitativeMetrics(
      String name, List<ChunkCoord> pts, List<ViewHorizonEnvironment> envs) {

    int n = envs.size();
    if (n < 2) {
      return new QualitativeMetrics(name, n, 0, 0, 0, new double[0], 0, 0, 0, 0, 0, 0, new double[0], 0);
    }

    // 1. Sequential Transitions (i -> i+1)
    double[] seqPalette = new double[n - 1];
    double sumSeqPalette = 0.0;
    double minSeqPalette = Double.MAX_VALUE;
    double maxSeqPalette = 0.0;

    double sumSeqBiome = 0.0;
    int biomeChanges = 0;
    double sumDeltaY = 0.0;
    double maxDeltaY = 0.0;

    for (int i = 0; i < n - 1; i++) {
      ViewHorizonEnvironment e1 = envs.get(i);
      ViewHorizonEnvironment e2 = envs.get(i + 1);

      double pDist = e1.blockPaletteCosineDistance(e2);
      seqPalette[i] = pDist;
      sumSeqPalette += pDist;
      if (pDist < minSeqPalette) minSeqPalette = pDist;
      if (pDist > maxSeqPalette) maxSeqPalette = pDist;

      double bDist = e1.biomeDistance(e2);
      sumSeqBiome += bDist;
      if (!e1.centerBiome().equals(e2.centerBiome())) {
        biomeChanges++;
      }

      double dY = Math.abs(e1.surfaceY() - e2.surfaceY());
      sumDeltaY += dY;
      if (dY > maxDeltaY) maxDeltaY = dY;
    }

    double avgSeqPalette = sumSeqPalette / (n - 1);
    double avgSeqBiome = sumSeqBiome / (n - 1);
    double biomeChangePct = (double) biomeChanges / (n - 1) * 100.0;
    double avgDeltaY = sumDeltaY / (n - 1);

    // 2. Parallel Nearest-Neighbor Palette Diversity
    // For each placement, what is the palette distance to its geographically closest neighbor?
    double[] parNearestPalette = new double[n];
    double sumParPalette = 0.0;
    double minParPalette = Double.MAX_VALUE;

    for (int i = 0; i < n; i++) {
      ChunkCoord pi = pts.get(i);
      int closestIdx = -1;
      double closestGeoDist = Double.MAX_VALUE;

      for (int j = 0; j < n; j++) {
        if (i == j) continue;
        double geoD = pi.euclideanDistance(pts.get(j));
        if (geoD < closestGeoDist) {
          closestGeoDist = geoD;
          closestIdx = j;
        }
      }

      double pDist = (closestIdx >= 0) ? envs.get(i).blockPaletteCosineDistance(envs.get(closestIdx)) : 1.0;
      parNearestPalette[i] = pDist;
      sumParPalette += pDist;
      if (pDist < minParPalette) minParPalette = pDist;
    }

    double avgParPalette = sumParPalette / n;
    Arrays.sort(seqPalette);
    Arrays.sort(parNearestPalette);

    // Composite Uniqueness Score [0.0 .. 1.0]:
    // 40% Palette contrast + 30% Biome diversity + 30% Parallel neighbor isolation
    double composite = 0.40 * avgSeqPalette + 0.30 * avgSeqBiome + 0.30 * avgParPalette;

    return new QualitativeMetrics(
        name,
        n,
        avgSeqPalette,
        minSeqPalette,
        maxSeqPalette,
        seqPalette,
        avgSeqBiome,
        biomeChangePct,
        avgDeltaY,
        maxDeltaY,
        avgParPalette,
        minParPalette,
        parNearestPalette,
        composite
    );
  }

  private static void renderQualitativeChart(
      QualitativeMetrics q1, QualitativeMetrics q2, QualitativeMetrics q3, int R, File outFile) throws Exception {

    int width = 1200;
    int height = 750;
    BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    // Background
    g.setColor(new Color(0x0C1017));
    g.fillRect(0, 0, width, height);

    // Title
    int margin = 35;
    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
    g.drawString("MCA Ground-Truth Qualitative Environmental Uniqueness Benchmark", margin, 40);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(new Color(0x90A4AE));
    g.drawString("Vetting Real Anvil World Data (.mca): Surface Block Palette Contrast, Biome Transitions, and Visible Horizon Separation", margin, 60);

    // 3 Metric Cards Across the Top
    int cardY = 85;
    int cardW = (width - margin * 2 - 40) / 3;
    int cardH = 200;

    renderStrategyCard(g, margin, cardY, cardW, cardH, q1, new Color(0x29B6F6));
    renderStrategyCard(g, margin + cardW + 20, cardY, cardW, cardH, q2, new Color(0x66BB6A));
    renderStrategyCard(g, margin + (cardW + 20) * 2, cardY, cardW, cardH, q3, new Color(0xFFA726));

    // Dual Plot Area in the Middle:
    // Left: Sequential Block Palette Cosine Distance CDF
    // Right: Parallel Nearest-Neighbor Palette Contrast CDF
    int plotSectionY = cardY + cardH + 30;
    int plotSectionH = 380;
    int subPlotW = (width - margin * 2 - 50) / 2;

    renderCdfPlotBox(g, margin, plotSectionY, subPlotW, plotSectionH,
        "1. Sequential Palette Contrast CDF (i -> i+1)",
        "Cosine distance between consecutive arrivals: higher = more varied player experience",
        q1.seqPaletteDistances, q2.seqPaletteDistances, q3.seqPaletteDistances);

    renderCdfPlotBox(g, margin + subPlotW + 50, plotSectionY, subPlotW, plotSectionH,
        "2. Parallel Neighbor Palette Contrast CDF",
        "Palette distance to geographically closest neighbor: higher = zero duplicate biomes/scenery",
        q1.parNearestPaletteDistances, q2.parNearestPaletteDistances, q3.parNearestPaletteDistances);

    g.dispose();
    ImageIO.write(img, "PNG", outFile);
  }

  private static void renderStrategyCard(
      Graphics2D g, int x, int y, int w, int h, QualitativeMetrics q, Color col) {

    g.setColor(new Color(0x131B24));
    g.fillRoundRect(x, y, w, h, 12, 12);
    g.setColor(new Color(0x233140));
    g.drawRoundRect(x, y, w, h, 12, 12);

    // Accent line
    g.setColor(col);
    g.fillRect(x + 15, y + 16, 4, 18);

    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
    g.drawString(q.strategyName, x + 26, y + 30);

    // Composite Badge
    int score = (int) Math.round(q.compositeUniquenessScore * 100.0);
    g.setColor(new Color(0x1C2836));
    g.fillRoundRect(x + w - 75, y + 16, 60, 24, 6, 6);
    g.setColor(col);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
    g.drawString(score + " / 100", x + w - 68, y + 33);

    // Detail rows
    int rowY = y + 65;
    renderMetricRow(g, x + 18, rowY, "Seq Palette Cosine Dist:", String.format("%.3f (Min: %.2f)", q.avgSeqPaletteDist, q.minSeqPaletteDist));
    renderMetricRow(g, x + 18, rowY + 24, "Biome Transition Rate:", String.format("%.1f%% of teleports", q.seqBiomeChangePercent));
    renderMetricRow(g, x + 18, rowY + 48, "Avg Elevation Delta Y:", String.format("%.1f blocks (Max: %.0f)", q.avgSeqDeltaY, q.maxSeqDeltaY));
    renderMetricRow(g, x + 18, rowY + 72, "Parallel Neighbor Palette:", String.format("%.3f contrast", q.avgParNearestPaletteDist));
    renderMetricRow(g, x + 18, rowY + 96, "Min Neighbor Palette:", String.format("%.3f (0=identical)", q.minParNearestPaletteDist));
  }

  private static void renderMetricRow(Graphics2D g, int x, int y, String label, String val) {
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    g.setColor(new Color(0x90A4AE));
    g.drawString(label, x, y);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
    g.setColor(Color.WHITE);
    g.drawString(val, x + 175, y);
  }

  private static void renderCdfPlotBox(
      Graphics2D g, int x, int y, int w, int h, String title, String subtitle,
      double[] d1, double[] d2, double[] d3) {

    g.setColor(new Color(0x131B24));
    g.fillRoundRect(x, y, w, h, 12, 12);
    g.setColor(new Color(0x233140));
    g.drawRoundRect(x, y, w, h, 12, 12);

    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
    g.drawString(title, x + 18, y + 26);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
    g.setColor(new Color(0x90A4AE));
    g.drawString(subtitle, x + 18, y + 40);

    int plotX = x + 45;
    int plotY = y + 55;
    int plotW = w - 65;
    int plotH = h - 90;

    g.setColor(new Color(0x0A0E14));
    g.fillRect(plotX, plotY, plotW, plotH);
    g.setColor(new Color(0x1B2632));
    g.drawRect(plotX, plotY, plotW, plotH);

    // Grid lines (0 to 100%)
    g.setColor(new Color(0x17212D));
    for (int p = 20; p <= 100; p += 20) {
      int gy = plotY + plotH - (int) (p * plotH / 100.0);
      g.drawLine(plotX, gy, plotX + plotW, gy);
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
      g.setColor(new Color(0x546E7A));
      g.drawString(p + "%", plotX - 28, gy + 4);
    }

    // X Axis ticks (0.0 to 1.0 cosine distance)
    for (double d = 0.0; d <= 1.0; d += 0.2) {
      int gx = plotX + (int) (d * plotW);
      g.setColor(new Color(0x17212D));
      g.drawLine(gx, plotY, gx, plotY + plotH);
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
      g.setColor(new Color(0x546E7A));
      g.drawString(String.format("%.1f", d), gx - 8, plotY + plotH + 16);
    }

    // Curves
    plotCdfCurve(g, plotX, plotY, plotW, plotH, d1, new Color(0x29B6F6), 2.2f);
    plotCdfCurve(g, plotX, plotY, plotW, plotH, d2, new Color(0x66BB6A), 2.2f);
    plotCdfCurve(g, plotX, plotY, plotW, plotH, d3, new Color(0xFFA726), 2.2f);

    // Legend at bottom
    int legY = plotY + plotH + 28;
    renderPlotLegend(g, plotX + 10, legY, new Color(0x29B6F6), "S=1 Native");
    renderPlotLegend(g, plotX + 115, legY, new Color(0x66BB6A), "S=64 (Ru=8c)");
    renderPlotLegend(g, plotX + 230, legY, new Color(0xFFA726), "S=256 (Ru=16c)");
  }

  private static void renderPlotLegend(Graphics2D g, int x, int y, Color col, String label) {
    g.setColor(col);
    g.fillRect(x, y - 6, 12, 4);
    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
    g.drawString(label, x + 16, y);
  }

  private static void plotCdfCurve(
      Graphics2D g, int x, int y, int w, int h, double[] sortedValues, Color col, float stroke) {
    if (sortedValues == null || sortedValues.length == 0) return;
    g.setColor(col);
    g.setStroke(new BasicStroke(stroke));

    int prevPx = x + (int) (sortedValues[0] * w);
    int prevPy = y + h;

    for (int i = 0; i < sortedValues.length; i++) {
      double val = Math.max(0.0, Math.min(1.0, sortedValues[i]));
      double cdf = (double) (i + 1) / sortedValues.length;

      int px = x + (int) (val * w);
      int py = y + h - (int) (cdf * h);

      g.drawLine(prevPx, prevPy, px, py);
      prevPx = px;
      prevPy = py;
    }
    g.setStroke(new BasicStroke(1.0f));
  }
}
