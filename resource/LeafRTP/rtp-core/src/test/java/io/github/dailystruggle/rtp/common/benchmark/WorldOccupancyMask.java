package io.github.dailystruggle.rtp.common.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Per-chunk occupancy mask read from a real Anvil save, for the ADR-083 measurement vehicle.
 *
 * <p>One cell equals one chunk. A cell is <b>good</b> when its slot in the region file's 4 KiB
 * location table carries a non-zero sector offset and count, i.e. the chunk exists on disk. That
 * is exactly the signal {@code AnvilRegionOccupancyCache} derives on the shipped GENSCAN fast
 * path, so the resulting mask is a real learned-state shape - large clustered good areas around
 * spawn and along travelled corridors, ragged edges, and whole 512-cell macro tiles that are
 * completely empty. A synthetic mask cannot reproduce that, and a uniformity or memory figure
 * measured against a synthetic mask says nothing about the model.
 *
 * <p>The save itself is never committed. It is copied by hand into a gitignored directory (see
 * {@code .gitignore}, {@code /testdata-world/}); when the directory is absent the tests that use
 * this class skip rather than fabricate data.
 *
 * <p>Only the location table is parsed. No chunk payload is inflated, no NBT is decoded, and
 * nothing is written back.
 */
public final class WorldOccupancyMask {

  /** Default location of the hand-copied save, relative to the repository root. */
  public static final String DEFAULT_DIR = "testdata-world/overworld/region";

  private final Map<Long, long[]> bitmaps;
  private final int regionFileCount;
  private final long occupiedChunks;
  private final int minChunkX;
  private final int maxChunkX;
  private final int minChunkZ;
  private final int maxChunkZ;

  private WorldOccupancyMask(
      Map<Long, long[]> bitmaps,
      int regionFileCount,
      long occupiedChunks,
      int minChunkX,
      int maxChunkX,
      int minChunkZ,
      int maxChunkZ) {
    this.bitmaps = bitmaps;
    this.regionFileCount = regionFileCount;
    this.occupiedChunks = occupiedChunks;
    this.minChunkX = minChunkX;
    this.maxChunkX = maxChunkX;
    this.minChunkZ = minChunkZ;
    this.maxChunkZ = maxChunkZ;
  }

  /**
   * Resolves the save directory, honouring {@code -Drtp.simulation.worldRegionDir=...}.
   *
   * @return the directory, which may not exist
   */
  public static Path resolveDirectory() {
    String override = System.getProperty("rtp.simulation.worldRegionDir");
    if (override != null && !override.isBlank()) return Path.of(override);
    // Tests run with the module directory as CWD, so climb to the repository root.
    Path fromModule = Path.of("..").resolve(DEFAULT_DIR).normalize();
    if (Files.isDirectory(fromModule)) return fromModule;
    return Path.of(DEFAULT_DIR);
  }

  /** @return true when a usable save is present */
  public static boolean available() {
    Path dir = resolveDirectory();
    if (!Files.isDirectory(dir)) return false;
    try (Stream<Path> files = Files.list(dir)) {
      return files.anyMatch(p -> p.getFileName().toString().endsWith(".mca"));
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Reads every {@code r.X.Z.mca} location table in {@code dir}.
   *
   * @param dir region directory
   * @return the assembled mask
   */
  public static WorldOccupancyMask load(Path dir) {
    List<Path> files = new ArrayList<>();
    try (Stream<Path> stream = Files.list(dir)) {
      stream.filter(p -> p.getFileName().toString().endsWith(".mca")).forEach(files::add);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    Map<Long, long[]> bitmaps = new HashMap<>();
    long occupied = 0L;
    int minX = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int minZ = Integer.MAX_VALUE;
    int maxZ = Integer.MIN_VALUE;

    byte[] header = new byte[4096];
    for (Path file : files) {
      String[] parts = file.getFileName().toString().split("\\.");
      if (parts.length != 4) continue;
      int rx;
      int rz;
      try {
        rx = Integer.parseInt(parts[1]);
        rz = Integer.parseInt(parts[2]);
      } catch (NumberFormatException e) {
        continue;
      }
      long[] bitmap = new long[16];
      try (var in = Files.newInputStream(file)) {
        int read = in.readNBytes(header, 0, 4096);
        if (read < 4096) continue;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      boolean any = false;
      for (int index = 0; index < 1024; index++) {
        int off = index * 4;
        int sectorOffset =
            ((header[off] & 0xFF) << 16) | ((header[off + 1] & 0xFF) << 8) | (header[off + 2] & 0xFF);
        int sectorCount = header[off + 3] & 0xFF;
        if (sectorOffset == 0 || sectorCount == 0) continue;
        bitmap[index >>> 6] |= 1L << (index & 63);
        occupied++;
        any = true;
      }
      if (!any) continue;
      bitmaps.put((((long) rx) << 32) | (rz & 0xFFFFFFFFL), bitmap);
      minX = Math.min(minX, rx << 5);
      maxX = Math.max(maxX, (rx << 5) + 31);
      minZ = Math.min(minZ, rz << 5);
      maxZ = Math.max(maxZ, (rz << 5) + 31);
    }

    return new WorldOccupancyMask(bitmaps, files.size(), occupied, minX, maxX, minZ, maxZ);
  }

  /**
   * @param cx chunk x
   * @param cz chunk z
   * @return true when the chunk exists on disk
   */
  public boolean isOccupied(int cx, int cz) {
    long key = (((long) (cx >> 5)) << 32) | ((cz >> 5) & 0xFFFFFFFFL);
    long[] bitmap = bitmaps.get(key);
    if (bitmap == null) return false;
    int index = (cx & 31) + ((cz & 31) << 5);
    return (bitmap[index >>> 6] & (1L << (index & 63))) != 0L;
  }

  /** @return region files scanned */
  public int regionFileCount() {
    return regionFileCount;
  }

  /** @return region files holding at least one chunk */
  public int occupiedRegionFileCount() {
    return bitmaps.size();
  }

  /** @return chunks present on disk */
  public long occupiedChunks() {
    return occupiedChunks;
  }

  /** @return inclusive chunk-coordinate bounds, {@code {minX, maxX, minZ, maxZ}} */
  public int[] chunkBounds() {
    return new int[] {minChunkX, maxChunkX, minChunkZ, maxChunkZ};
  }

  /**
   * Largest radius, in chunks, of an origin-centred square that stays inside the save's bounds.
   *
   * @return the radius
   */
  public int inscribedRadius() {
    int r = Math.min(Math.min(-minChunkX, maxChunkX), Math.min(-minChunkZ, maxChunkZ));
    return Math.max(0, r);
  }
}
