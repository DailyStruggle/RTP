package io.github.dailystruggle.rtp.common.benchmark;

import io.github.dailystruggle.rtp.anvil.AnvilPrefilter;
import io.github.dailystruggle.rtp.anvil.AnvilReader;
import io.github.dailystruggle.rtp.anvil.ColumnProbe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.stream.Stream;

/**
 * Per-chunk pass/fail read from a real save using the shipped Anvil compute path.
 *
 * <p>Distinct from {@link WorldOccupancyMask}, which answers only "does this chunk exist on disk".
 * That is the GENSCAN fast path and it is not a safety signal: a fully generated ocean is occupied.
 * Every density figure in the ADR-083/084/085 line of work was measured against that proxy, so the
 * unusable set it modelled was ungenerated ground rather than water and lava. This mask answers the
 * question the plugin actually asks, by decoding the chunk and testing its centre column against
 * the configured unsafe-block list.
 *
 * <p>The decision is computed by shipped code, not reimplemented here:
 *
 * <ul>
 *   <li>{@link AnvilReader#readColumnProbe} decodes the centre column and the surface heightmap -
 *       the same selective-parse path {@code AnvilProbeSupport} uses off-tick.
 *   <li>{@link AnvilPrefilter#DEFAULT_RECONCILER} normalises palette identifiers, so a name read
 *       from disk is compared in the same canonical form as a name read from config.
 *   <li>The surface plus the two blocks above it are tested, which is the rule
 *       {@code AnvilPrefilter.probeSyncDetailed} applies.
 * </ul>
 *
 * <p><b>Why the centre column and not all 256.</b> {@code probeSync} rejects a chunk when
 * <i>any</i> of its 256 columns is unsafe, which is the right posture for an advisory pre-filter but
 * the wrong granularity for a per-chunk mask: at that rule almost every generated chunk on a real
 * save carries one waterlogged block somewhere and the usable share collapses towards zero. The
 * chunk-granular unit these measurements address is the chunk centre - the destination a chunk-scale
 * key resolves to - so the centre column is what decides the cell. Same three-block rule, one
 * column.
 *
 * <p>Region bytes are read with {@link Files#readAllBytes} and released after the region is
 * classified, rather than through {@code AnvilRegionByteCache}. The cache exists to collapse
 * concurrent probes of the same region onto one buffer, which is exactly what a full sweep does not
 * do - it touches each region once - and retaining hundreds of multi-megabyte buffers would decide
 * the benchmark's heap figures rather than the model's.
 *
 * <p>Nothing is written. No chunk is generated. When no save is present the callers skip.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier.
 */
public final class RealWorldVerdictMask {

  /** Outcome for one chunk, at the granularity of the chunk's centre column. */
  public enum Cell {
    /** Surface and the two blocks above it are all safe. */
    USABLE,
    /** Water, waterlogged surface, bubble column, kelp or seagrass. */
    WATER,
    /** Lava or magma. */
    LAVA,
    /** Some other configured unsafe block. */
    OTHER_UNSAFE,
    /** Not present on disk, or present but undecodable - the probe's UNKNOWN. */
    UNGENERATED,
    /** Safe by the block rule, but every one of its eight neighbours is not - a stranded island. */
    ISOLATED
  }

  /**
   * Plain block names from the shipped {@code advanced/blocks.yml} {@code unsafeBlocks} list.
   *
   * <p>Copied rather than loaded: the baseline resource lives in {@code rtp-plugin}, which
   * {@code rtp-core} does not depend on, and a benchmark must not invert a module dependency to
   * read a config file. The tag entries ({@code #minecraft:fire}, {@code #minecraft:logs}) and the
   * state predicate ({@code *[waterlogged=true]}) are omitted because expanding them needs a
   * platform registry; their absence can only make this mask <i>more</i> permissive than the
   * shipped rule, so a usable share measured here is an upper bound.
   */
  public static final Set<String> SHIPPED_UNSAFE_BLOCKS =
      Set.of(
          "WATER",
          "LAVA",
          "BUBBLE_COLUMN",
          "POWDER_SNOW",
          "MAGMA_BLOCK",
          "CACTUS",
          "SWEET_BERRY_BUSH",
          "WITHER_ROSE",
          "CHORUS_PLANT",
          "CHORUS_FLOWER",
          "POINTED_DRIPSTONE",
          "COBWEB",
          "CREAKING_HEART",
          "BEDROCK",
          "NETHER_PORTAL",
          "END_PORTAL",
          "END_GATEWAY",
          "TNT",
          "END_CRYSTAL",
          "RESPAWN_ANCHOR",
          "SEAGRASS",
          "TALL_SEAGRASS",
          "KELP",
          "KELP_PLANT");

  /** Probe Y window. Wide enough for every vanilla dimension height in the supported range. */
  private static final int MIN_Y = -128;

  private static final int MAX_Y = 512;

  /** One byte per chunk, keyed by packed region coordinate. */
  private final Map<Long, byte[]> regions;

  /**
   * One {@link BiomeClass} ordinal per chunk, same keying as {@link #regions}.
   *
   * <p>Captured from the same {@link ColumnProbe} the safety verdict is read from, so the biome
   * channel costs no additional region read, no additional decode and no additional I/O. A second
   * sweep for biomes would double the read cost of every measurement in this package for data that
   * is already in hand.
   */
  private final Map<Long, byte[]> biomes;

  private final long[] counts;
  private final int regionFilesRead;
  private final long decodeFailures;

  private RealWorldVerdictMask(
      Map<Long, byte[]> regions,
      Map<Long, byte[]> biomes,
      long[] counts,
      int regionFilesRead,
      long decodeFailures) {
    this.regions = regions;
    this.biomes = biomes;
    this.counts = counts;
    this.regionFilesRead = regionFilesRead;
    this.decodeFailures = decodeFailures;
  }

  /**
   * Region directories under a root, richest first.
   *
   * <p>Ordered by region-file count so a caller that wants "the biggest real world available" does
   * not have to know the server layout, which differs per platform and per Minecraft version
   * ({@code world/region}, {@code world/dimensions/minecraft/overworld/region}, and so on).
   *
   * @param root search root, e.g. {@code C:\GameServers}
   * @param maxDepth directory depth to walk
   * @return region directories holding at least one {@code .mca}, richest first
   */
  public static List<Path> discoverRegionDirectories(Path root, int maxDepth) {
    if (!Files.isDirectory(root)) return List.of();
    record Candidate(Path dir, long files) {}
    List<Candidate> found = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(root, maxDepth)) {
      walk.filter(Files::isDirectory)
          .filter(p -> "region".equals(p.getFileName().toString()))
          .forEach(
              dir -> {
                try (Stream<Path> files = Files.list(dir)) {
                  long n = files.filter(f -> f.getFileName().toString().endsWith(".mca")).count();
                  if (n > 0L) found.add(new Candidate(dir, n));
                } catch (IOException ignored) {
                  // An unreadable directory is simply not a candidate.
                }
              });
    } catch (IOException | RuntimeException e) {
      return List.of();
    }
    found.sort(Comparator.comparingLong(Candidate::files).reversed());
    List<Path> out = new ArrayList<>(found.size());
    for (Candidate c : found) out.add(c.dir);
    return out;
  }

  /**
   * Classifies every chunk of every region file in {@code regionDir}.
   *
   * @param regionDir directory holding {@code r.X.Z.mca}
   * @param unsafeBlocks raw unsafe-block names; reconciled internally
   * @param maxRegionFiles cap on region files, so a 6 000-file save does not have to be swept whole
   * @return the assembled mask
   */
  public static RealWorldVerdictMask load(
      Path regionDir, Set<String> unsafeBlocks, int maxRegionFiles) {
    Set<String> reconciled = new LinkedHashSet<>();
    for (String raw : unsafeBlocks) {
      String r = AnvilPrefilter.DEFAULT_RECONCILER.apply(raw);
      if (r != null) reconciled.add(r);
    }

    List<Path> files = new ArrayList<>();
    try (Stream<Path> stream = Files.list(regionDir)) {
      stream.filter(p -> p.getFileName().toString().endsWith(".mca")).forEach(files::add);
    } catch (IOException e) {
      return new RealWorldVerdictMask(Map.of(), Map.of(), new long[Cell.values().length], 0, 0L);
    }
    // Sorted before capping so a capped run is deterministic, and centred so the cap keeps the
    // regions nearest the origin - the ones an origin-centred domain actually addresses.
    files.sort(Comparator.comparingLong(RealWorldVerdictMask::originDistanceSquared));
    if (files.size() > maxRegionFiles) files = files.subList(0, maxRegionFiles);

    Map<Long, byte[]> regions = new ConcurrentHashMap<>();
    Map<Long, byte[]> biomes = new ConcurrentHashMap<>();
    AtomicLongArray tally = new AtomicLongArray(Cell.values().length);
    java.util.concurrent.atomic.AtomicLong failures = new java.util.concurrent.atomic.AtomicLong();

    files.parallelStream()
        .forEach(file -> classifyRegion(file, reconciled, regions, biomes, tally, failures));

    long[] counts = new long[Cell.values().length];
    for (int i = 0; i < counts.length; i++) counts[i] = tally.get(i);
    return new RealWorldVerdictMask(regions, biomes, counts, files.size(), failures.get());
  }

  /** One chunk's two verdicts, produced from a single probe. */
  private record Verdict(Cell cell, BiomeClass biome) {}

  private static void classifyRegion(
      Path file,
      Set<String> reconciledUnsafe,
      Map<Long, byte[]> regions,
      Map<Long, byte[]> biomes,
      AtomicLongArray tally,
      java.util.concurrent.atomic.AtomicLong failures) {
    int[] rxz = parseRegionCoords(file);
    if (rxz == null) return;
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (IOException | OutOfMemoryError e) {
      failures.incrementAndGet();
      return;
    }
    byte[] cells = new byte[1024];
    byte[] biomeCells = new byte[1024];
    for (int index = 0; index < 1024; index++) {
      int lx = index & 31;
      int lz = index >>> 5;
      Verdict verdict;
      try {
        verdict = classifyChunk(bytes, lx, lz, reconciledUnsafe);
      } catch (IOException | RuntimeException e) {
        // Same posture as the shipped probe: a decode failure is UNKNOWN, never a crash.
        failures.incrementAndGet();
        verdict = new Verdict(Cell.UNGENERATED, BiomeClass.UNKNOWN);
      }
      cells[index] = (byte) verdict.cell().ordinal();
      biomeCells[index] = (byte) verdict.biome().ordinal();
      tally.incrementAndGet(verdict.cell().ordinal());
    }
    long key = (((long) rxz[0]) << 32) | (rxz[1] & 0xFFFFFFFFL);
    regions.put(key, cells);
    biomes.put(key, biomeCells);
  }

  /**
   * The pass/fail rule: surface and the two blocks above it, at the chunk's centre column; and the
   * biome at the surface of that same column.
   *
   * <p>The biome is read at the surface rather than at a fixed Y because biomes are three
   * dimensional from 1.18 onward - a fixed Y would report a cave biome under a forest, which is not
   * the biome a player arriving at the surface is standing in.
   */
  private static Verdict classifyChunk(byte[] regionBytes, int lx, int lz, Set<String> unsafe)
      throws IOException {
    ColumnProbe probe = AnvilReader.readColumnProbe(regionBytes, lx, lz, MIN_Y, MAX_Y);
    if (probe == null || !probe.hasHeightmap()) {
      return new Verdict(Cell.UNGENERATED, BiomeClass.UNKNOWN);
    }
    int surface = probe.heightmapTopY();
    BiomeClass biome = BiomeClass.classify(probe.biomeAt(surface));
    Cell worst = Cell.USABLE;
    for (int dy = 0; dy <= 2; dy++) {
      String raw = probe.blockAt(surface + dy);
      if (raw == null) continue;
      String name = AnvilPrefilter.DEFAULT_RECONCILER.apply(raw);
      if (name == null || !unsafe.contains(name)) continue;
      Cell cell = categorise(name);
      // Water beats "other" in the report because it is the feature the noise map imitates; a
      // chunk unsafe for two reasons is attributed to the more specific one.
      if (worst == Cell.USABLE || cell == Cell.LAVA) worst = cell;
    }
    return new Verdict(worst, biome);
  }

  private static Cell categorise(String name) {
    String n = name.toUpperCase(Locale.ROOT);
    if (n.equals("LAVA") || n.equals("MAGMA_BLOCK")) return Cell.LAVA;
    if (n.equals("WATER")
        || n.equals("BUBBLE_COLUMN")
        || n.startsWith("KELP")
        || n.endsWith("SEAGRASS")) {
      return Cell.WATER;
    }
    return Cell.OTHER_UNSAFE;
  }

  private static int[] parseRegionCoords(Path file) {
    String[] parts = file.getFileName().toString().split("\\.");
    if (parts.length != 4) return null;
    try {
      return new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static long originDistanceSquared(Path file) {
    int[] rxz = parseRegionCoords(file);
    if (rxz == null) return Long.MAX_VALUE;
    long x = rxz[0];
    long z = rxz[1];
    return x * x + z * z;
  }

  /**
   * @param cx chunk x
   * @param cz chunk z
   * @return the cell's classification; {@link Cell#UNGENERATED} outside the swept region files
   */
  public Cell cellAt(int cx, int cz) {
    byte[] cells = regions.get((((long) (cx >> 5)) << 32) | ((cz >> 5) & 0xFFFFFFFFL));
    if (cells == null) return Cell.UNGENERATED;
    return Cell.values()[cells[(cx & 31) + ((cz & 31) << 5)]];
  }

  /**
   * Classification after the stranded-island filter.
   *
   * <p>A single safe chunk ringed by water is not a viable starting position - a player dropped
   * there is stranded - so it is reported {@link Cell#ISOLATED} and counts as unusable. The same
   * rule is applied to {@link NoiseWorldMask}, because a fidelity comparison in which the two sides
   * define "usable" differently measures the definition rather than the terrain.
   *
   * <p>One pass over raw classifications, so it never cascades: removing an island does not strand
   * its neighbour. Ungenerated ground counts as unusable for the neighbourhood test, which makes
   * the filter conservative at the edge of the swept region files.
   *
   * @param cx chunk x
   * @param cz chunk z
   * @return the filtered classification
   */
  public Cell filteredCellAt(int cx, int cz) {
    Cell base = cellAt(cx, cz);
    if (base != Cell.USABLE) return base;
    for (int dz = -1; dz <= 1; dz++) {
      for (int dx = -1; dx <= 1; dx++) {
        if (dx == 0 && dz == 0) continue;
        if (cellAt(cx + dx, cz + dz) == Cell.USABLE) return Cell.USABLE;
      }
    }
    return Cell.ISOLATED;
  }

  /**
   * @param cx chunk x
   * @param cz chunk z
   * @return the biome class at the chunk's surface centre; {@link BiomeClass#UNKNOWN} outside the
   *     swept region files
   */
  public BiomeClass biomeAt(int cx, int cz) {
    byte[] cells = biomes.get((((long) (cx >> 5)) << 32) | ((cz >> 5) & 0xFFFFFFFFL));
    if (cells == null) return BiomeClass.UNKNOWN;
    return BiomeClass.values()[cells[(cx & 31) + ((cz & 31) << 5)]];
  }

  /**
   * @param cx chunk x
   * @param cz chunk z
   * @return true when the chunk is usable ground, stranded islands excluded
   */
  public boolean isOccupied(int cx, int cz) {
    return filteredCellAt(cx, cz) == Cell.USABLE;
  }

  /** @return chunks classified as {@code cell} across every region file swept */
  public long count(Cell cell) {
    return counts[cell.ordinal()];
  }

  /** @return chunks classified in total */
  public long classifiedChunks() {
    long total = 0L;
    for (long c : counts) total += c;
    return total;
  }

  /** @return usable share among chunks that exist on disk, i.e. excluding UNGENERATED */
  public double usableShareOfGenerated() {
    long generated = classifiedChunks() - count(Cell.UNGENERATED);
    return generated == 0L ? 0.0d : (double) count(Cell.USABLE) / generated;
  }

  /** @return region files swept */
  public int regionFilesRead() {
    return regionFilesRead;
  }

  /** @return chunks whose decode failed; reported rather than swallowed (REQ-RTP-S-004 spirit) */
  public long decodeFailures() {
    return decodeFailures;
  }

  /**
   * Largest radius, in chunks, of an origin-centred square fully inside the swept region files.
   *
   * @return the radius, zero when the origin's own region was not swept
   */
  public int inscribedRadius() {
    int r = 0;
    // Grows one ring at a time and stops on the first gap, so the returned radius encloses only
    // fully swept ground - a corner-only check would accept a square with a hole in its side.
    while (r < 1024) {
      int next = r + 1;
      boolean complete = true;
      for (int i = -next; i < next && complete; i++) {
        complete =
            regions.containsKey(key(i, -next))
                && regions.containsKey(key(i, next - 1))
                && regions.containsKey(key(-next, i))
                && regions.containsKey(key(next - 1, i));
      }
      if (!complete) break;
      r = next;
    }
    return r * 32;
  }

  private static long key(int rx, int rz) {
    return (((long) rx) << 32) | (rz & 0xFFFFFFFFL);
  }
}
