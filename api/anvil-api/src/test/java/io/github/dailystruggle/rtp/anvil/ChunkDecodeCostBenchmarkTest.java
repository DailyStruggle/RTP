package io.github.dailystruggle.rtp.anvil;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-120 cost evidence: where one chunk decode spends its time (inflate vs NBT parse), and what
 * the terrain-square decode saves. Reads the ADR-119 reference save
 * ({@code -Drtp.elevation.regionDir} overrides); skips when it is absent.
 */
class ChunkDecodeCostBenchmarkTest {

  private static final String REGION_DIR = System.getProperty("rtp.elevation.regionDir",
      System.getProperty("user.home", "")
          + "/Documents/GameServers/Minecraft/testServer/RTP-Paper/26.3"
          + "/world/dimensions/minecraft/overworld/region");
  private static final int CHUNKS = Integer.getInteger("rtp.decodeBench.chunks", 3000);
  private static final int WARMUP = 1000;
  private static final int MIN_Y = -64;
  private static final int MAX_Y = 319;
  /** A terrain-square window: landing Y 70 +- 48, plus y-1. */
  private static final int WIN_MIN = 70 - 49;
  private static final int WIN_MAX = 70 + 48;

  @Test
  @DisplayName("ADR-120: per-chunk decode cost split and the terrain-square decode")
  void decodeCostSplit() throws Exception {
    Path regionDir = REGION_DIR.isEmpty() ? null : Path.of(REGION_DIR);
    Assumptions.assumeTrue(regionDir != null && Files.isDirectory(regionDir), "no reference save");
    List<Path> files = new ArrayList<>();
    try (var s = Files.list(regionDir)) {
      s.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).sorted().forEach(files::add);
    }
    Assumptions.assumeTrue(!files.isEmpty(), "no region files");
    Random rnd = new Random(120);
    List<byte[]> regions = new ArrayList<>();
    List<int[]> picks = new ArrayList<>();
    for (int i = 0; i < Math.min(24, files.size()); i++) regions.add(Files.readAllBytes(files.get(rnd.nextInt(files.size()))));
    for (int tries = 0; picks.size() < WARMUP + CHUNKS && tries < 100_000; tries++) {
      int r = rnd.nextInt(regions.size()), lx = rnd.nextInt(32), lz = rnd.nextInt(32);
      byte[] b = regions.get(r);
      if (!AnvilReader.INSTANCE.isChunkGenerated(b, lx, lz) || (compression(b, lx, lz) != 2 && compression(b, lx, lz) != 4)) continue;
      if (AnvilReader.readColumnProbe(b, lx, lz, MIN_Y, MAX_Y) == null) continue;
      picks.add(new int[] {r, lx, lz});
    }
    Assumptions.assumeTrue(picks.size() > WARMUP, "too few zlib / LZ4 chunks");

    long[] ns = new long[5];
    long sink = 0, bytes = 0, hashNs = 0;
    for (int pass = 0; pass < 2; pass++) {
      java.util.Arrays.fill(ns, 0);
      int n = 0;
      for (int[] p : picks) {
        byte[] b = regions.get(p[0]);
        long t0 = System.nanoTime();
        sink += decompressAsShipped(b, p[1], p[2]).length;
        long t1 = System.nanoTime();
        byte[] raw = decompressAsShipped(b, p[1], p[2]);
        long th = System.nanoTime();
        sink += Lz4BlockDecoder.xxHash32(raw, 0, raw.length, 0);
        bytes += raw.length;
        hashNs += (pass == 1) ? System.nanoTime() - th : 0;
        long t2 = System.nanoTime();
        sink += AnvilReader.readColumnProbe(b, p[1], p[2], MIN_Y, MAX_Y).sections().size();
        long t3 = System.nanoTime();
        sink += AnvilReader.readColumnProbe(b, p[1], p[2], WIN_MIN, WIN_MAX).sections().size();
        long t4 = System.nanoTime();
        ColumnProbe t = AnvilReader.readTerrainProbe(b, b.length, p[1], p[2], WIN_MIN, WIN_MAX);
        sink += (t == null) ? 0 : t.sections().size();
        long t5 = System.nanoTime();
        if (pass == 1 || n >= WARMUP) {
          ns[0] += t1 - t0;
          ns[1] += t2 - t1;
          ns[2] += t3 - t2;
          ns[3] += t4 - t3;
          ns[4] += t5 - t4;
        }
        n++;
      }
      if (pass == 1) {
        assertTrue(bytes > 0, "benchmark must decode chunk bytes");
        assertTrue(hashNs >= 0, "hash timing must be non-negative");
        for (long bucket : ns) assertTrue(bucket >= 0, "timing buckets must be non-negative");
        double k = 1e3 * picks.size();
        System.out.printf("%n[DEBUG_LOG] === ADR-120 CHUNK DECODE COST (%s, %d chunks, zlib/LZ4) ===%n", regionDir,
            picks.size());
        System.out.printf("[DEBUG_LOG] decompress (as shipped) %.1f us | xxHash32 of the output %.1f us | %.0f KiB/chunk%n",
            ns[0] / k, hashNs / k, bytes / 2.0 / picks.size() / 1024);
        System.out.printf("[DEBUG_LOG] column probe full window %.1f us | column probe terrain window %.1f us"
            + " | terrain probe %.1f us%n", ns[2] / k, ns[3] / k, ns[4] / k);
        System.out.printf("[DEBUG_LOG] parse share of the column probe = %.0f%% (sink %d)%n",
            100.0 * (ns[2] - ns[0]) / Math.max(1, ns[2]), sink);
      }
    }
  }

  private static int entry(byte[] b, int lx, int lz) {
    int e = (lx + (lz << 5)) * 4;
    return ((b[e] & 0xFF) << 16) | ((b[e + 1] & 0xFF) << 8) | (b[e + 2] & 0xFF);
  }

  private static int compression(byte[] b, int lx, int lz) {
    return b[entry(b, lx, lz) * 4096 + 4] & 0xFF;
  }

  private static int length(byte[] b, int lx, int lz) {
    int o = entry(b, lx, lz) * 4096;
    return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
  }

  private static byte[] decompressAsShipped(byte[] b, int lx, int lz) throws Exception {
    int o = entry(b, lx, lz) * 4096;
    if (compression(b, lx, lz) == 4) {
      return Lz4BlockDecoder.decode(b, o + 5, length(b, lx, lz) - 1, AnvilReader.MAX_DECOMPRESSED_CHUNK_BYTES);
    }
    try (InflaterInputStream in = new InflaterInputStream(new java.io.ByteArrayInputStream(b, o + 5, length(b, lx, lz) - 1));
         ByteArrayOutputStream out = new ByteArrayOutputStream(4 * length(b, lx, lz))) {
      byte[] buf = new byte[4096];
      int r;
      while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
      return out.toByteArray();
    }
  }
}
