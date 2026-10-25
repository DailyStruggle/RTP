package io.github.dailystruggle.rtp.common.visualization;

import io.github.dailystruggle.rtp.anvil.AnvilChunkView;
import io.github.dailystruggle.rtp.anvil.AnvilReader;
import io.github.dailystruggle.rtp.common.tools.ChartOutputHelper;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Visualizes the Y-band elevation map concept from ADR-119 alternatives section.
 *
 * <p>Reads real Anvil region files, computes floor elevation at each chunk,
 * quantizes to Y-bands, then renders a multi-panel image showing:
 * <ol>
 *   <li>Raw elevation heatmap</li>
 *   <li>Y-band quantization</li>
 *   <li>Sparse sampling at increasing densities (1%, 5%, 10%, 50%, 100%)</li>
 *   <li>Interpolated elevation from sparse samples (bilinear)</li>
 * </ol>
 *
 * <p>Uses real world data from the test server, falling back to synthetic terrain
 * if no world data is found.
 */
@Tag("drawing")
public class ElevationMapVisualizationTest {

  private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");

  /** Reference save: a 26.3 Paper overworld. Override with -Drtp.elevation.regionDir=<dir>. */
  private static final Path REFERENCE_REGION_DIR = Path.of(
      System.getProperty("user.home", "")
          + "/Documents/GameServers/Minecraft/testServer/RTP-Paper/26.3"
          + "/world/dimensions/minecraft/overworld/region");

  private static final Path[] WORLD_REGION_PATHS = {
      REFERENCE_REGION_DIR,
      Path.of("testdata-world/overworld/region")
  };

  /** Chunk stride inside each region; 1 reads every chunk. Override with -Drtp.elevation.stride. */
  private static final int CHUNK_STRIDE =
      Math.max(1, Integer.getInteger("rtp.elevation.stride", 1));

  // Image dimensions
  private static final int PANEL_SIZE = 800;
  private static final int PANELS_X = 3;
  private static final int PANELS_Y = 2;
  private static final int IMG_WIDTH = PANEL_SIZE * PANELS_X;
  private static final int IMG_HEIGHT = PANEL_SIZE * PANELS_Y;

  // Y-band configuration; bands are anchored at the 1.18+ overworld floor so negative terrain keeps
  // its own bands instead of being clipped.
  private static final int BAND_HEIGHT = 8; // blocks per band
  private static final int MIN_Y = -64;
  private static final int MAX_Y = 319;
  private static final int NO_FLOOR = Integer.MIN_VALUE;
  private static final int IDW_RADIUS = 16;

  record ChunkElevation(int cx, int cz, int floorY, int band) {}

  /**
   * Contour edges in world Y: each band is [edge[i], edge[i+1]). Ranges follow terrain meaning rather
   * than a fixed step - sea level gets its own narrow band, mountains share a wide one - so a
   * handful of bands carries what a player would call "the shape" of the land.
   */
  private static final int[] CONTOUR_EDGES = {MIN_Y, 62, 65, 72, 88, 112, 144, 192, MAX_Y + 1};

  private static final String[] CONTOUR_NAMES = {
      "below sea", "sea level / shore", "lowland", "plains", "hills", "highland", "mountain", "peak"};

  private static final Color[] CONTOUR_COLORS = {
      new Color(20, 50, 120), new Color(60, 120, 200), new Color(110, 170, 90),
      new Color(150, 190, 100), new Color(200, 190, 110), new Color(170, 120, 70),
      new Color(130, 110, 100), new Color(240, 240, 240)};

  /** Connected same-band patches smaller than this many chunks merge into their neighbour band. */
  private static final int MIN_REGION_CHUNKS = 4;

  private static List<ChunkElevation> cachedScan;

  /** One scan per JVM: a full read of the reference save is the slow part of both tests. */
  private synchronized List<ChunkElevation> loadElevations() throws IOException {
    if (cachedScan == null) {
      List<ChunkElevation> scanned = scanRegionDir(findRegionDir());
      if (scanned.isEmpty()) {
        System.out.println("[DEBUG_LOG] No world data found, generating synthetic terrain");
        scanned = generateSyntheticTerrain(64);
      }
      cachedScan = scanned;
    }
    return cachedScan;
  }

  static int contourBand(int y) {
    for (int i = CONTOUR_EDGES.length - 2; i >= 0; i--) {
      if (y >= CONTOUR_EDGES[i]) return i;
    }
    return 0;
  }

  @Test
  @DisplayName("ADR-119: contour map with defined Y ranges and its compression against raw heights")
  public void testDrawContourMap() throws Exception {
    System.out.println("\n[DEBUG_LOG] === CONTOUR MAP (ADR-119) ===");
    List<ChunkElevation> elevations = loadElevations();

    int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
    int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
    for (ChunkElevation e : elevations) {
      minX = Math.min(minX, e.cx);
      maxX = Math.max(maxX, e.cx);
      minZ = Math.min(minZ, e.cz);
      maxZ = Math.max(maxZ, e.cz);
    }
    int gridW = maxX - minX + 1;
    int gridH = maxZ - minZ + 1;
    int bands = CONTOUR_NAMES.length;

    // -1 = no data, else contour band index.
    int[][] band = new int[gridH][gridW];
    for (int[] row : band) Arrays.fill(row, -1);
    java.util.Set<Integer> distinctY = new java.util.HashSet<>();
    for (ChunkElevation e : elevations) {
      band[e.cz - minZ][e.cx - minX] = contourBand(e.floorY);
      distinctY.add(e.floorY);
    }
    long chunks = elevations.size();

    int regionsBefore = countRegions(band);
    long runsBefore = countRuns(band);
    int[][] smooth = new int[gridH][];
    for (int z = 0; z < gridH; z++) smooth[z] = band[z].clone();
    long changed = 0;
    for (int pass = 0; pass < 3; pass++) changed += mergeSmallRegions(smooth, MIN_REGION_CHUNKS);
    int regionsAfter = countRegions(smooth);
    long runsAfter = countRuns(smooth);

    long[] share = new long[bands];
    for (int[] row : smooth) for (int b : row) if (b >= 0) share[b]++;

    // Storage estimates per chunk grid. Raw = one height per chunk over the 384-block column.
    int rawBits = 32 - Integer.numberOfLeadingZeros(MAX_Y - MIN_Y);
    int bandBits = 32 - Integer.numberOfLeadingZeros(bands - 1);
    int lenBits = 32 - Integer.numberOfLeadingZeros(gridW);
    long rawBytes = (chunks * rawBits + 7) / 8;
    long bandBytes = (chunks * bandBits + 7) / 8;
    long rleBytes = (runsAfter * (bandBits + lenBits) + 7) / 8;

    System.out.printf("[DEBUG_LOG] Chunks=%,d distinctY=%d bands=%d%n", chunks, distinctY.size(), bands);
    System.out.printf("[DEBUG_LOG] Regions: %,d raw-banded -> %,d after merging <%d-chunk patches (%,d chunks relabelled, %.2f%%)%n",
        regionsBefore, regionsAfter, MIN_REGION_CHUNKS, changed, 100.0 * changed / chunks);
    System.out.printf("[DEBUG_LOG] Row runs: %,d -> %,d (%.1f chunks/run)%n",
        runsBefore, runsAfter, (double) chunks / runsAfter);
    System.out.printf("[DEBUG_LOG] Bytes: raw %,d (%d bit) | banded %,d (%d bit) | banded+RLE %,d (%.1fx smaller than raw)%n",
        rawBytes, rawBits, bandBytes, bandBits, rleBytes, (double) rawBytes / rleBytes);
    for (int b = 0; b < bands; b++) {
      System.out.printf("[DEBUG_LOG]   band %d %-18s Y[%d,%d) %6.2f%%%n", b, CONTOUR_NAMES[b],
          CONTOUR_EDGES[b], CONTOUR_EDGES[b + 1], 100.0 * share[b] / chunks);
    }

    // Render: 1600px map on the left, legend + stats on the right.
    int mapSize = 1600;
    BufferedImage img = new BufferedImage(mapSize + 800, mapSize, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setColor(Color.BLACK);
    g.fillRect(0, 0, img.getWidth(), img.getHeight());

    int m = 30;
    int span = mapSize - 2 * m;
    int mapW = gridW >= gridH ? span : (int) ((long) span * gridW / gridH);
    int mapH = gridH >= gridW ? span : (int) ((long) span * gridH / gridW);
    for (int z = 0; z < gridH; z++) {
      for (int x = 0; x < gridW; x++) {
        if (smooth[z][x] < 0) continue;
        g.setColor(CONTOUR_COLORS[smooth[z][x]]);
        fillCell(g, m, m, x, z, gridW, gridH, mapW, mapH);
      }
    }
    // Contour lines on every cell edge where the band changes.
    g.setColor(new Color(0, 0, 0, 200));
    for (int z = 0; z < gridH; z++) {
      for (int x = 0; x < gridW; x++) {
        int b = smooth[z][x];
        if (b < 0) continue;
        if (x + 1 < gridW && smooth[z][x + 1] >= 0 && smooth[z][x + 1] != b) {
          int ex = edge(m, x + 1, gridW, mapW);
          g.drawLine(ex, edge(m, z, gridH, mapH), ex, edge(m, z + 1, gridH, mapH));
        }
        if (z + 1 < gridH && smooth[z + 1][x] >= 0 && smooth[z + 1][x] != b) {
          int ez = edge(m, z + 1, gridH, mapH);
          g.drawLine(edge(m, x, gridW, mapW), ez, edge(m, x + 1, gridW, mapW), ez);
        }
      }
    }

    int lx = mapSize + 20;
    int ly = 50;
    g.setColor(Color.WHITE);
    g.setFont(new Font("SansSerif", Font.BOLD, 20));
    g.drawString("Contour map - " + bands + " defined Y ranges", lx, ly);
    g.setFont(new Font("SansSerif", Font.PLAIN, 14));
    g.drawString(String.format("chunks x[%d,%d] z[%d,%d], 1 cell = 1 chunk", minX, maxX, minZ, maxZ), lx, ly + 24);
    ly += 60;
    for (int b = bands - 1; b >= 0; b--) {
      g.setColor(CONTOUR_COLORS[b]);
      g.fillRect(lx, ly, 40, 28);
      g.setColor(Color.DARK_GRAY);
      g.drawRect(lx, ly, 40, 28);
      g.setColor(Color.WHITE);
      g.setFont(new Font("SansSerif", Font.BOLD, 15));
      g.drawString(String.format("Y %d .. %d", CONTOUR_EDGES[b], CONTOUR_EDGES[b + 1] - 1), lx + 52, ly + 13);
      g.setFont(new Font("SansSerif", Font.PLAIN, 13));
      g.drawString(String.format("%s - %.1f%% of chunks", CONTOUR_NAMES[b], 100.0 * share[b] / chunks), lx + 52, ly + 28);
      ly += 40;
    }

    ly += 30;
    g.setColor(Color.WHITE);
    g.setFont(new Font("SansSerif", Font.BOLD, 18));
    g.drawString("Compression vs raw heights", lx, ly);
    g.setFont(new Font("Monospaced", Font.PLAIN, 14));
    String[] lines = {
        String.format("chunks             %,d", chunks),
        String.format("distinct floor Y   %d  ->  %d bands", distinctY.size(), bands),
        String.format("raw   (%d bit/chk)  %,d B", rawBits, rawBytes),
        String.format("band  (%d bit/chk)  %,d B", bandBits, bandBytes),
        String.format("band + row RLE     %,d B  (%.1fx)", rleBytes, (double) rawBytes / rleBytes),
        "",
        String.format("regions  before    %,d", regionsBefore),
        String.format("regions  after     %,d  (min %d chunks)", regionsAfter, MIN_REGION_CHUNKS),
        String.format("relabelled         %,d chunks (%.2f%%)", changed, 100.0 * changed / chunks),
        String.format("row runs           %,d -> %,d", runsBefore, runsAfter),
        String.format("mean run length    %.1f chunks", (double) chunks / runsAfter),
        "",
        "Floor = 1 above highest non-air block at",
        "chunk centre; water counts as floor, so",
        "oceans read as sea level, not seabed."};
    for (String line : lines) {
      ly += 22;
      g.drawString(line, lx, ly);
    }
    g.dispose();

    boolean written = ChartOutputHelper.writeChart(img, "adr-119-contour-map.png");
    System.out.printf("[DEBUG_LOG] Contour image %s (%,dx%,d)%n",
        written ? "written" : "unchanged", img.getWidth(), img.getHeight());
    org.junit.jupiter.api.Assertions.assertTrue(regionsAfter <= regionsBefore,
        "merging small patches must never add regions");
    org.junit.jupiter.api.Assertions.assertTrue(rleBytes < rawBytes,
        "banded run-length form must be smaller than one raw height per chunk");
  }

  /** Row-major runs of equal band; a no-data cell breaks a run. */
  private static long countRuns(int[][] band) {
    long runs = 0;
    for (int[] row : band) {
      int prev = Integer.MIN_VALUE;
      for (int b : row) {
        if (b >= 0 && b != prev) runs++;
        prev = b;
      }
    }
    return runs;
  }

  /** 4-connected same-band component labels; returns component count. */
  private static int labelRegions(int[][] band, int[][] label, List<Integer> sizes) {
    int h = band.length, w = band[0].length;
    for (int[] row : label) Arrays.fill(row, -1);
    int[] stack = new int[w * h];
    int next = 0;
    for (int z = 0; z < h; z++) {
      for (int x = 0; x < w; x++) {
        if (band[z][x] < 0 || label[z][x] >= 0) continue;
        int b = band[z][x];
        int sp = 0;
        int size = 0;
        stack[sp++] = z * w + x;
        label[z][x] = next;
        while (sp > 0) {
          int p = stack[--sp];
          int pz = p / w, px = p % w;
          size++;
          int[][] nbr = {{px + 1, pz}, {px - 1, pz}, {px, pz + 1}, {px, pz - 1}};
          for (int[] n : nbr) {
            if (n[0] < 0 || n[0] >= w || n[1] < 0 || n[1] >= h) continue;
            if (label[n[1]][n[0]] >= 0 || band[n[1]][n[0]] != b) continue;
            label[n[1]][n[0]] = next;
            stack[sp++] = n[1] * w + n[0];
          }
        }
        sizes.add(size);
        next++;
      }
    }
    return next;
  }

  private static int countRegions(int[][] band) {
    int[][] label = new int[band.length][band[0].length];
    return labelRegions(band, label, new ArrayList<>());
  }

  /**
   * Relabels every patch smaller than {@code minSize} to the band most common along its border.
   * Returns chunks relabelled. Patches with no data-bearing neighbour are left alone.
   */
  private static long mergeSmallRegions(int[][] band, int minSize) {
    int h = band.length, w = band[0].length;
    int[][] label = new int[h][w];
    List<Integer> sizes = new ArrayList<>();
    labelRegions(band, label, sizes);
    int bands = CONTOUR_NAMES.length;
    // Border votes per small region, collected in one sweep.
    java.util.Map<Integer, int[]> votes = new java.util.HashMap<>();
    for (int z = 0; z < h; z++) {
      for (int x = 0; x < w; x++) {
        int l = label[z][x];
        if (l < 0 || sizes.get(l) >= minSize) continue;
        int[][] nbr = {{x + 1, z}, {x - 1, z}, {x, z + 1}, {x, z - 1}};
        for (int[] n : nbr) {
          if (n[0] < 0 || n[0] >= w || n[1] < 0 || n[1] >= h) continue;
          int nb = band[n[1]][n[0]];
          if (nb < 0 || label[n[1]][n[0]] == l) continue;
          votes.computeIfAbsent(l, k -> new int[bands])[nb]++;
        }
      }
    }
    long changed = 0;
    for (int z = 0; z < h; z++) {
      for (int x = 0; x < w; x++) {
        int[] v = votes.get(label[z][x]);
        if (v == null) continue;
        int best = 0;
        for (int b = 1; b < bands; b++) if (v[b] > v[best]) best = b;
        if (v[best] > 0 && band[z][x] != best) {
          band[z][x] = best;
          changed++;
        }
      }
    }
    return changed;
  }

  @Test
  @DisplayName("ADR-119: visualize Y-band elevation map with sparse sampling and interpolation")
  public void testDrawElevationMap() throws Exception {
    System.out.println("\n[DEBUG_LOG] === ELEVATION MAP VISUALIZATION (ADR-119) ===");

    // 1. Load real world data
    List<ChunkElevation> elevations = loadElevations();
    System.out.printf("[DEBUG_LOG] Scanned %,d chunks with elevation data%n", elevations.size());

    // 2. Compute bounds
    int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
    int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
    int minFloorY = Integer.MAX_VALUE, maxFloorY = Integer.MIN_VALUE;
    for (ChunkElevation e : elevations) {
      minX = Math.min(minX, e.cx);
      maxX = Math.max(maxX, e.cx);
      minZ = Math.min(minZ, e.cz);
      maxZ = Math.max(maxZ, e.cz);
      minFloorY = Math.min(minFloorY, e.floorY);
      maxFloorY = Math.max(maxFloorY, e.floorY);
    }

    System.out.printf("[DEBUG_LOG] Chunk range: x=[%d,%d] z=[%d,%d]%n", minX, maxX, minZ, maxZ);
    System.out.printf("[DEBUG_LOG] Elevation range: Y=[%d,%d] (range %d blocks)%n",
        minFloorY, maxFloorY, maxFloorY - minFloorY);

    // 3. Build elevation grid
    int gridW = maxX - minX + 1;
    int gridH = maxZ - minZ + 1;
    int[][] elevationGrid = new int[gridH][gridW];
    int[][] bandGrid = new int[gridH][gridW];
    boolean[][] hasData = new boolean[gridH][gridW];

    for (ChunkElevation e : elevations) {
      int gx = e.cx - minX;
      int gz = e.cz - minZ;
      if (gx >= 0 && gx < gridW && gz >= 0 && gz < gridH) {
        elevationGrid[gz][gx] = e.floorY;
        bandGrid[gz][gx] = e.band;
        hasData[gz][gx] = true;
      }
    }

    // 4. Compute band stats
    int minBand = Math.floorDiv(minFloorY - MIN_Y, BAND_HEIGHT);
    int maxBand = Math.floorDiv(maxFloorY - MIN_Y, BAND_HEIGHT);
    System.out.printf("[DEBUG_LOG] Band range: [%d,%d] (bandHeight=%d, %d distinct bands)%n",
        minBand, maxBand, BAND_HEIGHT, maxBand - minBand + 1);

    // 5. Render
    System.out.println("[DEBUG_LOG] Rendering elevation map visualization...");
    BufferedImage img = new BufferedImage(IMG_WIDTH, IMG_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setColor(Color.BLACK);
    g.fillRect(0, 0, IMG_WIDTH, IMG_HEIGHT);

    int margin = 40;
    int panelW = (PANEL_SIZE - margin * 2);
    int panelH = (PANEL_SIZE - margin * 2);

    // Panel 1: Raw elevation heatmap
    renderPanel(g, 0, 0, "Raw Elevation (floor Y)",
        elevationGrid, hasData, minFloorY, maxFloorY, false);

    // Panel 2: Y-band quantization
    renderPanel(g, 1, 0, "Y-Band Quantization (bandHeight=" + BAND_HEIGHT + ")",
        bandGrid, hasData, minBand, maxBand, false);

    // Panel 3: Sparse sampling at 5% density
    renderSparsePanel(g, 2, 0, "Sparse Sampling (5%%)",
        elevationGrid, hasData, minFloorY, maxFloorY, 0.05, new Random(42));

    // Panel 4: Interpolated from 10% samples (IDW)
    double[][] interpolated = interpolateBilinear(elevationGrid, hasData, 0.1, new Random(42));
    renderInterpolatedPanel(g, 0, 1, "IDW Interpolation (10%% samples)",
        interpolated, elevationGrid, hasData, minFloorY, maxFloorY);

    // Panel 5: Interpolation error map
    renderErrorPanel(g, 1, 1, "Interpolation Error (blocks)",
        interpolated, elevationGrid, hasData, minFloorY, maxFloorY);

    // Panel 6: Band height comparison
    renderBandHeightComparison(g, 2, 1, "Band Height Comparison",
        elevationGrid, hasData, minFloorY, maxFloorY);

    g.dispose();

    // 6. Save
    boolean written = ChartOutputHelper.writeChart(img, "adr-119-elevation-map.png");
    System.out.printf("[DEBUG_LOG] Image %s (%,dx%,d)%n",
        written ? "written" : "unchanged", IMG_WIDTH, IMG_HEIGHT);
    org.junit.jupiter.api.Assertions.assertTrue(true, "Visualization complete");
  }

  private Path findRegionDir() {
    String override = System.getProperty("rtp.elevation.regionDir");
    if (override != null && Files.isDirectory(Path.of(override))) {
      System.out.printf("[DEBUG_LOG] Using world data (override): %s%n", override);
      return Path.of(override);
    }
    for (Path p : WORLD_REGION_PATHS) {
      if (Files.isDirectory(p)) {
        System.out.printf("[DEBUG_LOG] Using world data: %s%n", p);
        return p;
      }
    }
    System.out.println("[DEBUG_LOG] No world data directory found");
    return null;
  }

  private List<ChunkElevation> scanRegionDir(Path regionDir) throws IOException {
    List<ChunkElevation> elevations = new ArrayList<>();
    if (regionDir == null || !Files.isDirectory(regionDir)) return elevations;

    int scanned = 0;
    int withElevation = 0;
    int regions = 0;
    AnvilReader reader = AnvilReader.INSTANCE;
    try (var stream = Files.newDirectoryStream(regionDir, "r.*.mca")) {
      for (Path file : stream) {
        Matcher m = REGION_FILE.matcher(file.getFileName().toString());
        if (!m.matches()) continue;

        int regionX = Integer.parseInt(m.group(1));
        int regionZ = Integer.parseInt(m.group(2));
        byte[] regionBytes = Files.readAllBytes(file);
        regions++;

        for (int cz = 0; cz < 32; cz += CHUNK_STRIDE) {
          for (int cx = 0; cx < 32; cx += CHUNK_STRIDE) {
            if (!reader.isChunkGenerated(regionBytes, cx, cz)) continue;
            scanned++;

            try {
              AnvilChunkView view = reader.readChunkView(regionBytes, cx, cz);
              int floorY = findFloorY(view);
              if (floorY != NO_FLOOR) {
                int globalCx = regionX * 32 + cx;
                int globalCz = regionZ * 32 + cz;
                int band = Math.floorDiv(floorY - MIN_Y, BAND_HEIGHT);
                elevations.add(new ChunkElevation(globalCx, globalCz, floorY, band));
                withElevation++;
              }
            } catch (IOException ignored) {
              // Skip corrupt chunks
            }
          }
        }
        if (regions % 50 == 0) {
          System.out.printf("[DEBUG_LOG] Progress: %,d regions, %,d chunks, %,d with elevation%n",
              regions, scanned, withElevation);
        }
      }
    }
    System.out.printf("[DEBUG_LOG] Scanned %,d regions, %,d chunks, %,d with elevation%n",
        regions, scanned, withElevation);
    return elevations;
  }

  /**
   * Floor Y at chunk centre (7,7): one above the highest non-air block, scanning only the emitted
   * sections. Water counts as a floor, so oceans read as sea level rather than seabed.
   */
  private int findFloorY(AnvilChunkView view) {
    int lx = 7, lz = 7;
    int topSection = Integer.MIN_VALUE;
    for (var s : view.sections()) topSection = Math.max(topSection, s.sectionY());
    if (topSection == Integer.MIN_VALUE) return NO_FLOOR;
    int top = Math.min(MAX_Y, topSection * 16 + 15);
    int bottom = Math.max(MIN_Y, view.minHeight());
    for (int y = top; y >= bottom; y--) {
      String block = view.blockIdAt(lx, y, lz);
      if (block == null) continue; // absent section = air
      String reconciled = io.github.dailystruggle.rtp.anvil.AnvilPrefilter.DEFAULT_RECONCILER.apply(block);
      if ("AIR".equals(reconciled) || "CAVE_AIR".equals(reconciled) || "VOID_AIR".equals(reconciled)) {
        continue;
      }
      // Found solid block; floor is one above
      return y + 1;
    }
    return NO_FLOOR;
  }

  /** Pixel edge of grid cell {@code i} when {@code cells} cells span {@code span} pixels. */
  private static int edge(int origin, int i, int cells, int span) {
    return origin + (int) ((long) i * span / cells);
  }

  /** Fills grid cell (gx, gz) scaled into a span; at least one pixel so dense grids still show. */
  private static void fillCell(Graphics2D g, int ox, int oy, int gx, int gz,
      int gridW, int gridH, int w, int h) {
    int x0 = edge(ox, gx, gridW, w);
    int y0 = edge(oy, gz, gridH, h);
    int x1 = edge(ox, gx + 1, gridW, w);
    int y1 = edge(oy, gz + 1, gridH, h);
    g.fillRect(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
  }

  /** Generate synthetic terrain with hills, valleys, and a cliff. */
  private List<ChunkElevation> generateSyntheticTerrain(int gridSize) {
    List<ChunkElevation> elevations = new ArrayList<>();
    Random rng = new Random(12345);
    double[][] noise = generateNoise(gridSize, gridSize, 4, rng);

    for (int z = 0; z < gridSize; z++) {
      for (int x = 0; x < gridSize; x++) {
        // Base terrain: plains with hills
        double n = noise[z][x];
        int baseY = 64 + (int) (n * 30);

        // Add a cliff on the right half
        if (x > gridSize / 2) {
          baseY += 40;
        }

        // Add a valley in the center
        double distFromCenter = Math.sqrt(
            Math.pow(x - gridSize / 2.0, 2) + Math.pow(z - gridSize / 2.0, 2));
        if (distFromCenter < gridSize / 4) {
          baseY -= 20;
        }

        int floorY = Math.max(MIN_Y, Math.min(MAX_Y - 1, baseY));
        int band = (floorY - MIN_Y) / BAND_HEIGHT;
        elevations.add(new ChunkElevation(x, z, floorY, band));
      }
    }
    return elevations;
  }

  /** Simple value noise for synthetic terrain. */
  private double[][] generateNoise(int w, int h, int octaves, Random rng) {
    double[][] result = new double[h][w];
    for (int octave = 0; octave < octaves; octave++) {
      double freq = Math.pow(2, octave);
      double amp = 1.0 / Math.pow(2, octave);
      for (int z = 0; z < h; z++) {
        for (int x = 0; x < w; x++) {
          double nx = x / (double) w * freq;
          double nz = z / (double) h * freq;
          double v = rng.nextDouble() * amp;
          result[z][x] += v;
        }
      }
    }
    return result;
  }

  private void renderPanel(Graphics2D g, int px, int py, String title,
      int[][] grid, boolean[][] hasData, int minVal, int maxVal, boolean showSamples) {
    int ox = px * PANEL_SIZE + 40;
    int oy = py * PANEL_SIZE + 40;
    int w = PANEL_SIZE - 80;
    int h = PANEL_SIZE - 80;

    // Title
    g.setColor(Color.WHITE);
    g.setFont(new Font("SansSerif", Font.BOLD, 14));
    g.drawString(title, ox, oy + 10);

    // Grid
    int gridW = grid[0].length;
    int gridH = grid.length;

    for (int gz = 0; gz < gridH; gz++) {
      for (int gx = 0; gx < gridW; gx++) {
        if (!hasData[gz][gx]) continue;
        int val = grid[gz][gx];
        double t = (val - minVal) / (double) Math.max(1, maxVal - minVal);
        g.setColor(elevationColor(t));
        fillCell(g, ox, oy + 20, gx, gz, gridW, gridH, w, h);
      }
    }

    // Color bar
    renderColorBar(g, ox + w + 5, oy + 20, 15, h, minVal, maxVal);
  }

  private void renderSparsePanel(Graphics2D g, int px, int py, String title,
      int[][] grid, boolean[][] hasData, int minVal, int maxVal,
      double density, Random rng) {
    int ox = px * PANEL_SIZE + 40;
    int oy = py * PANEL_SIZE + 40;
    int w = PANEL_SIZE - 80;
    int h = PANEL_SIZE - 80;

    g.setColor(Color.WHITE);
    g.setFont(new Font("SansSerif", Font.BOLD, 14));
    g.drawString(title, ox, oy + 10);

    int gridW = grid[0].length;
    int gridH = grid.length;

    // Select random samples
    List<Point2D> samples = new ArrayList<>();
    for (int gz = 0; gz < gridH; gz++) {
      for (int gx = 0; gx < gridW; gx++) {
        if (!hasData[gz][gx]) continue;
        if (rng.nextDouble() < density) {
          samples.add(new Point2D.Double(gx, gz));
        }
      }
    }

    // Draw sampled points
    for (Point2D p : samples) {
      int gx = (int) p.getX();
      int gz = (int) p.getY();
      int val = grid[gz][gx];
      double t = (val - minVal) / (double) Math.max(1, maxVal - minVal);
      g.setColor(elevationColor(t));
      g.fillRect(edge(ox, gx, gridW, w) - 1, edge(oy + 20, gz, gridH, h) - 1, 3, 3);
    }

    // Faint grid for context
    g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.15f));
    for (int gz = 0; gz < gridH; gz++) {
      for (int gx = 0; gx < gridW; gx++) {
        if (!hasData[gz][gx]) continue;
        int val = grid[gz][gx];
        double t = (val - minVal) / (double) Math.max(1, maxVal - minVal);
        g.setColor(elevationColor(t));
        fillCell(g, ox, oy + 20, gx, gz, gridW, gridH, w, h);
      }
    }
    g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f));

    renderColorBar(g, ox + w + 5, oy + 20, 15, h, minVal, maxVal);
    g.setColor(Color.GRAY);
    g.setFont(new Font("SansSerif", Font.PLAIN, 10));
    g.drawString("Samples: " + samples.size(), ox, oy + h + 15);
  }

  private void renderInterpolatedPanel(Graphics2D g, int px, int py, String title,
      double[][] interpolated, int[][] original, boolean[][] hasData,
      int minVal, int maxVal) {
    int ox = px * PANEL_SIZE + 40;
    int oy = py * PANEL_SIZE + 40;
    int w = PANEL_SIZE - 80;
    int h = PANEL_SIZE - 80;

    g.setColor(Color.WHITE);
    g.setFont(new Font("SansSerif", Font.BOLD, 14));
    g.drawString(title, ox, oy + 10);

    int gridW = original[0].length;
    int gridH = original.length;

    for (int gz = 0; gz < gridH; gz++) {
      for (int gx = 0; gx < gridW; gx++) {
        if (!hasData[gz][gx]) continue;
        double val = interpolated[gz][gx];
        if (Double.isNaN(val)) continue;
        double t = Math.max(0, Math.min(1, (val - minVal) / (double) Math.max(1, maxVal - minVal)));
        g.setColor(elevationColor(t));
        fillCell(g, ox, oy + 20, gx, gz, gridW, gridH, w, h);
      }
    }

    renderColorBar(g, ox + w + 5, oy + 20, 15, h, minVal, maxVal);
  }

  private void renderErrorPanel(Graphics2D g, int px, int py, String title,
      double[][] interpolated, int[][] original, boolean[][] hasData,
      int minVal, int maxVal) {
    int ox = px * PANEL_SIZE + 40;
    int oy = py * PANEL_SIZE + 40;
    int w = PANEL_SIZE - 80;
    int h = PANEL_SIZE - 80;

    g.setColor(Color.WHITE);
    g.setFont(new Font("SansSerif", Font.BOLD, 14));
    g.drawString(title, ox, oy + 10);

    int gridW = original[0].length;
    int gridH = original.length;

    // Compute max error for scale; NaN (no sample within radius) is excluded, not counted as 0.
    double maxError = 0;
    double sumError = 0;
    long counted = 0;
    long uncovered = 0;
    for (int gz = 0; gz < gridH; gz++) {
      for (int gx = 0; gx < gridW; gx++) {
        if (!hasData[gz][gx]) continue;
        if (Double.isNaN(interpolated[gz][gx])) {
          uncovered++;
          continue;
        }
        double err = Math.abs(interpolated[gz][gx] - original[gz][gx]);
        maxError = Math.max(maxError, err);
        sumError += err;
        counted++;
      }
    }
    if (maxError == 0) maxError = 1;
    double meanError = counted == 0 ? 0 : sumError / counted;
    System.out.printf("[DEBUG_LOG] IDW error: mean=%.2f max=%.0f blocks over %,d chunks (%,d uncovered)%n",
        meanError, maxError, counted, uncovered);

    for (int gz = 0; gz < gridH; gz++) {
      for (int gx = 0; gx < gridW; gx++) {
        if (!hasData[gz][gx] || Double.isNaN(interpolated[gz][gx])) continue;
        double err = Math.abs(interpolated[gz][gx] - original[gz][gx]);
        double t = err / maxError;
        g.setColor(errorColor(t));
        fillCell(g, ox, oy + 20, gx, gz, gridW, gridH, w, h);
      }
    }
    g.setColor(Color.GRAY);
    g.setFont(new Font("SansSerif", Font.PLAIN, 10));
    g.drawString(String.format("mean %.2f blocks, %,d uncovered", meanError, uncovered), ox, oy + h + 35);

    // Error scale bar
    g.setColor(Color.WHITE);
    g.setFont(new Font("SansSerif", Font.PLAIN, 10));
    g.drawString("0", ox + w + 5, oy + 20);
    g.drawString(String.format("%.0f", maxError), ox + w + 5, oy + h + 15);
    for (int i = 0; i < h; i++) {
      double t = 1.0 - (double) i / h;
      g.setColor(errorColor(t));
      g.fillRect(ox + w + 10, oy + 20 + i, 5, 1);
    }
  }

  private void renderBandHeightComparison(Graphics2D g, int px, int py, String title,
      int[][] grid, boolean[][] hasData, int minVal, int maxVal) {
    int ox = px * PANEL_SIZE + 40;
    int oy = py * PANEL_SIZE + 40;

    g.setColor(Color.WHITE);
    g.setFont(new Font("SansSerif", Font.BOLD, 14));
    g.drawString(title, ox, oy + 10);

    int[] bandHeights = {4, 8, 16, 32};
    String[] labels = {"4", "8", "16", "32"};
    int panelCount = bandHeights.length;
    int panelW = (PANEL_SIZE - 80) / panelCount;
    int panelH = PANEL_SIZE - 100;

    for (int i = 0; i < panelCount; i++) {
      int bh = bandHeights[i];
      int pOx = ox + i * panelW;
      int pOy = oy + 25;

      // Label with band count
      int minBand = Math.floorDiv(minVal - MIN_Y, bh);
      int maxBand = Math.floorDiv(maxVal - MIN_Y, bh);
      g.setColor(Color.WHITE);
      g.setFont(new Font("SansSerif", Font.BOLD, 11));
      g.drawString("bh=" + labels[i] + " (" + (maxBand - minBand + 1) + " bands)", pOx + 3, pOy + 12);

      int gridW = grid[0].length;
      int gridH = grid.length;
      // Keep the sub-map square-ish: the column is narrow, so height follows the width.
      int subW = panelW - 4;
      int subH = Math.min(panelH, (int) ((long) subW * gridH / gridW));

      for (int gz = 0; gz < gridH; gz++) {
        for (int gx = 0; gx < gridW; gx++) {
          if (!hasData[gz][gx]) continue;
          int band = Math.floorDiv(grid[gz][gx] - MIN_Y, bh);
          double t = (band - minBand) / (double) Math.max(1, maxBand - minBand);
          t = Math.max(0, Math.min(1, t));
          g.setColor(elevationColor(t));
          fillCell(g, pOx, pOy + 18, gx, gz, gridW, gridH, subW, subH);
        }
      }
    }
  }

  /** Bilinear interpolation from sparse samples. */
  private double[][] interpolateBilinear(int[][] grid, boolean[][] hasData,
      double density, Random rng) {
    int gridW = grid[0].length;
    int gridH = grid.length;
    double[][] result = new double[gridH][gridW];

    // Initialize with NaN
    for (int gz = 0; gz < gridH; gz++) {
      Arrays.fill(result[gz], Double.NaN);
    }

    // Select samples
    List<int[]> samples = new ArrayList<>();
    for (int gz = 0; gz < gridH; gz++) {
      for (int gx = 0; gx < gridW; gx++) {
        if (!hasData[gz][gx]) continue;
        if (rng.nextDouble() < density) {
          samples.add(new int[]{gx, gz, grid[gz][gx]});
        }
      }
    }

    if (samples.isEmpty()) return result;

    // Sample lookup grid so each cell only visits its radius window, not every sample: a full scan
    // of the reference save is ~10^5 samples x ~10^5 cells, which the all-pairs loop cannot finish.
    int[][] sampleAt = new int[gridH][gridW];
    for (int[] row : sampleAt) Arrays.fill(row, NO_FLOOR);
    for (int[] s : samples) sampleAt[s[1]][s[0]] = s[2];

    // Inverse-distance-weighted interpolation (simpler than bilinear for sparse data). A sampled
    // cell is excluded from its own estimate (dist == 0), so the error panel measures prediction.
    int r = IDW_RADIUS;
    for (int gz = 0; gz < gridH; gz++) {
      for (int gx = 0; gx < gridW; gx++) {
        if (!hasData[gz][gx]) continue;

        double sumWeight = 0;
        double sumValue = 0;
        for (int sz = Math.max(0, gz - r); sz <= Math.min(gridH - 1, gz + r); sz++) {
          for (int sx = Math.max(0, gx - r); sx <= Math.min(gridW - 1, gx + r); sx++) {
            int sv = sampleAt[sz][sx];
            if (sv == NO_FLOOR) continue;
            int d2 = (gx - sx) * (gx - sx) + (gz - sz) * (gz - sz);
            if (d2 == 0 || d2 > r * r) continue;
            double weight = 1.0 / d2;
            sumWeight += weight;
            sumValue += weight * sv;
          }
        }

        if (sumWeight > 0) {
          result[gz][gx] = sumValue / sumWeight;
        }
      }
    }

    return result;
  }

  private void renderColorBar(Graphics2D g, int x, int y, int w, int h, int minVal, int maxVal) {
    for (int i = 0; i < h; i++) {
      double t = 1.0 - (double) i / h;
      g.setColor(elevationColor(t));
      g.fillRect(x, y + i, w, 1);
    }
    g.setColor(Color.WHITE);
    g.setFont(new Font("SansSerif", Font.PLAIN, 10));
    FontMetrics fm = g.getFontMetrics();
    String top = String.valueOf(maxVal);
    String bot = String.valueOf(minVal);
    g.drawString(top, x - fm.stringWidth(top) - 3, y + 10);
    g.drawString(bot, x - fm.stringWidth(bot) - 3, y + h);
  }

  /** Elevation color: blue (low) -> green (mid) -> brown (high) -> white (peak). */
  private Color elevationColor(double t) {
    t = Math.max(0, Math.min(1, t));
    if (t < 0.33) {
      // Blue to green
      double s = t / 0.33;
      return new Color(
          (int) (0 * (1 - s) + 34 * s),
          (int) (100 * (1 - s) + 139 * s),
          (int) (200 * (1 - s) + 34 * s));
    } else if (t < 0.66) {
      // Green to brown
      double s = (t - 0.33) / 0.33;
      return new Color(
          (int) (34 * (1 - s) + 139 * s),
          (int) (139 * (1 - s) + 90 * s),
          (int) (34 * (1 - s) + 43 * s));
    } else {
      // Brown to white
      double s = (t - 0.66) / 0.34;
      return new Color(
          (int) (139 * (1 - s) + 255 * s),
          (int) (90 * (1 - s) + 255 * s),
          (int) (43 * (1 - s) + 255 * s));
    }
  }

  /** Error color: green (low) -> yellow -> red (high). */
  private Color errorColor(double t) {
    t = Math.max(0, Math.min(1, t));
    if (t < 0.5) {
      double s = t / 0.5;
      return new Color(
          (int) (0 * (1 - s) + 255 * s),
          (int) (200 * (1 - s) + 200 * s),
          0);
    } else {
      double s = (t - 0.5) / 0.5;
      return new Color(
          255,
          (int) (200 * (1 - s) + 0 * s),
          0);
    }
  }
}
