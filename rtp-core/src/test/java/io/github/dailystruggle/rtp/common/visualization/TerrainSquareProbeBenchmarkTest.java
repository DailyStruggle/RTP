package io.github.dailystruggle.rtp.common.visualization;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.anvil.AnvilColumnProbeAdapter;
import io.github.dailystruggle.rtp.anvil.AnvilPrefilter;
import io.github.dailystruggle.rtp.anvil.AnvilReader;
import io.github.dailystruggle.rtp.anvil.AnvilRegionByteCache;
import io.github.dailystruggle.rtp.anvil.AnvilRegionHeaderCache;
import io.github.dailystruggle.rtp.anvil.ChunkColumnProbe;
import io.github.dailystruggle.rtp.anvil.ColumnProbe;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.TerrainShapeCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-120 cost evidence for the probe pre-compute: centre probes, then the on-demand terrain
 * square for candidates that pass a stand-in prefilter (dry standable landing at column 8, 8).
 * Costs are per candidate over all candidates, on the shipped {@link AnvilPrefilter} pool with
 * the region bytes resident. Reads the ADR-119 reference save ({@code -Drtp.elevation.regionDir}
 * overrides); skips when it is absent.
 */
class TerrainSquareProbeBenchmarkTest {

  private static final String REGION_DIR = System.getProperty("rtp.elevation.regionDir",
      "/home/johngoldsmith/Documents/GameServers/Minecraft/testServer/RTP-Paper/26.3"
          + "/world/dimensions/minecraft/overworld/region");
  private static final int GROUP = Integer.getInteger("rtp.terrainBench.group", 16);
  private static final int ROUNDS = Integer.getInteger("rtp.terrainBench.rounds", 200);
  private static final int WARMUP = 60;
  private static final int MIN_Y = -64;
  private static final int MAX_Y = 319;

  /** deg -1: probes only; 0: every same-file chunk of the 5x5 (eager design, lean decode); else staged within radius. */
  private record Mode(String name, double deg, int radius) {}

  @Test
  @DisplayName("ADR-120: probe pre-compute cost with the on-demand terrain square (reference save)")
  void probeSquareCost() throws Exception {
    Path regionDir = REGION_DIR.isEmpty() ? null : Path.of(REGION_DIR);
    Assumptions.assumeTrue(regionDir != null && Files.isDirectory(regionDir), "no reference save");
    Path world = regionDir.getParent();
    List<Path> files = new ArrayList<>();
    try (var s = Files.list(regionDir)) {
      s.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).sorted().forEach(files::add);
    }
    Assumptions.assumeTrue(!files.isEmpty(), "no region files");
    AnvilRegionByteCache.setRevalidateIntervalMillis(60_000L);
    try {
      System.out.printf("%n[DEBUG_LOG] === ADR-120 PROBE + ON-DEMAND SQUARE COST (%s) ===%n", regionDir);
      System.out.printf("[DEBUG_LOG] group=%d rounds=%d, per candidate over all candidates; %s, %d cpus%n", GROUP,
          ROUNDS, System.getProperty("java.vm.name") + " " + System.getProperty("java.version"),
          Runtime.getRuntime().availableProcessors());
      int shipped = TerrainShapeCheck.SQUARE_RADIUS;
      Mode[] modes = {new Mode("probe only", -1, 2), new Mode("eager 5x5", 0, 2), new Mode("5x5 30deg", 30, 2),
          new Mode("3x3 30deg", 30, shipped), new Mode("3x3 45deg", 45, shipped),
          new Mode("3x3 60deg", 60, shipped)};
      for (int rep = 0; rep < 2; rep++) {
        for (Mode m : modes) run(world, files, m, rep == 1);
      }
    } finally {
      AnvilRegionByteCache.setRevalidateIntervalMillis(1_000L);
    }
  }

  private static void run(Path world, List<Path> files, Mode mode, boolean print) throws Exception {
    Random rnd = new Random(120);
    long nanos = 0, candidates = 0, passed = 0, rejected = 0;
    long decodes0 = 0;
    for (int round = 0; round < WARMUP + ROUNDS; round++) {
      Path file = files.get(rnd.nextInt(files.size()));
      String[] parts = file.getFileName().toString().split("\\.");
      int rx = Integer.parseInt(parts[1]), rz = Integer.parseInt(parts[2]);
      AnvilRegionHeaderCache.get(file);
      byte[] bytes;
      try (AnvilRegionByteCache.Lease lease = AnvilRegionByteCache.acquire(file)) {
        if (lease == null) continue;
        bytes = java.util.Arrays.copyOf(lease.buffer(), lease.length());
      }
      List<int[]> picks = new ArrayList<>();
      for (int tries = 0; picks.size() < GROUP && tries < 400; tries++) {
        int lx = rnd.nextInt(32), lz = rnd.nextInt(32);
        if (AnvilReader.INSTANCE.isChunkGenerated(bytes, lx, lz)) picks.add(new int[] {rx * 32 + lx, rz * 32 + lz});
      }
      if (round == WARMUP) decodes0 = AnvilPrefilter.squareJobDecodes();
      long s = System.nanoTime();
      List<CompletableFuture<ColumnProbe>> probes = new ArrayList<>();
      for (int[] p : picks) probes.add(AnvilPrefilter.probeColumnAsync(world, "", p[0], p[1], MIN_Y - 1, MAX_Y));
      List<CompletableFuture<?>> jobs = new ArrayList<>();
      int pass = 0;
      for (int i = 0; i < picks.size(); i++) {
        ColumnProbe cp = probes.get(i).join();
        if (cp == null) continue;
        ChunkColumnProbe probe = new AnvilColumnProbeAdapter(cp, picks.get(i)[0], picks.get(i)[1]);
        int y = landing(probe);
        if (y == Integer.MIN_VALUE) continue; // stand-in prefilter reject: no square
        pass++;
        if (mode.deg() < 0) continue;
        jobs.add(AnvilPrefilter.squareJobAsync(world, "", picks.get(i)[0], picks.get(i)[1],
            Math.max(MIN_Y - 1, y - TerrainShapeCheck.LAYER_WINDOW - 1),
            Math.min(MAX_Y, y + TerrainShapeCheck.LAYER_WINDOW), job(mode, probe, y)));
      }
      long rej = 0;
      for (CompletableFuture<?> j : jobs) if (Boolean.TRUE.equals(j.join())) rej++;
      long d = System.nanoTime() - s;
      if (round >= WARMUP) {
        nanos += d;
        candidates += picks.size();
        passed += pass;
        rejected += rej;
      }
    }
    assertTrue(candidates > 0, "benchmark must process candidates");
    assertTrue(passed <= candidates, "prefilter passes cannot exceed candidates");
    assertTrue(rejected <= passed, "terrain rejects cannot exceed prefilter passes");
    long decodes = AnvilPrefilter.squareJobDecodes() - decodes0;
    if (!print) return;
    System.out.printf("[DEBUG_LOG] %-13s %8.1f us/candidate | square decodes %5.2f/candidate %5.2f/square job"
            + " | passed prefilter %5.1f%% | terrain reject %5.1f%% of passed%n", mode.name(),
        nanos / 1e3 / Math.max(1, candidates), decodes / (double) Math.max(1, candidates),
        decodes / (double) Math.max(1, passed), 100.0 * passed / Math.max(1, candidates),
        100.0 * rejected / Math.max(1, passed));
  }

  private static Function<io.github.dailystruggle.rtp.anvil.ChunkSquare, Boolean> job(Mode mode,
                                                                                      ChunkColumnProbe probe, int y) {
    if (mode.deg() == 0) {
      return sq -> {
        int n = 0;
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) if ((dx | dz) != 0 && sq.chunk(dx, dz) != null) n++;
        return n < 0;
      };
    }
    double tan = TerrainShapeCheck.tanOf(mode.deg());
    int radius = mode.radius();
    return sq -> TerrainShapeCheck.evaluate(TerrainShapeCheck.probeSquare(probe, sq, radius), 8, 8, y, tan, MIN_Y,
        MAX_Y).reject(tan);
  }

  /** Top dry floor at column (8, 8): non-air, non-liquid below, air at feet; else MIN_VALUE. */
  private static int landing(ChunkColumnProbe p) {
    for (int y = MAX_Y; y > MIN_Y; y--) {
      if (p.isAirAt(8, 8, y) && !p.isAirAt(8, 8, y - 1)) {
        String below = p.blockAt(8, 8, y - 1);
        if (below == null || below.contains("WATER") || below.contains("LAVA")) return Integer.MIN_VALUE;
        return y;
      }
    }
    return Integer.MIN_VALUE;
  }
}
