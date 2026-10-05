package io.github.dailystruggle.rtp.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.UnaryOperator;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sampled biome reads of a region file (ADR-104 section 4.6). */
@DisplayName("ADR-104 section 4.6 - AnvilRegionSampler partial biome reads")
class AnvilRegionSamplerTest {

  private static final int Y = 64;
  private static final int RCX = -2;
  private static final int RCZ = 3;

  private static final UnaryOperator<String> CANON = s -> {
    String up = s.toUpperCase(Locale.ROOT);
    return up.startsWith("MINECRAFT:") ? up.substring("MINECRAFT:".length()) : up;
  };

  @TempDir Path dir;

  @BeforeEach
  @AfterEach
  void clearCache() {
    AnvilRegionByteCache.invalidateAll();
  }

  private static String biomeFor(int idx) {
    return "minecraft:biome_" + idx;
  }

  private static byte[] chunkPayload(int idx) throws IOException {
    LinkedHashMap<String, Object> sec = AnvilTestFixtures.sectionWithBiomes(
        (byte) (Y >> 4), List.of("minecraft:stone"), null, List.of(biomeFor(idx)), null);
    LinkedHashMap<String, Object> root =
        AnvilTestFixtures.chunkRoot(4671, new long[37], List.of(sec));
    byte[] nbt = Nbt.writeNamedRoot("", root);
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (DeflaterOutputStream z = new DeflaterOutputStream(compressed)) {
      z.write(nbt);
    }
    return compressed.toByteArray();
  }

  /**
   * Writes a region whose populated chunks are {@code present}; each chunk occupies its own
   * sector run. Chunks in {@code garbage} get a header entry but random sector bytes.
   */
  private static byte[] buildRegion(int[] present, int[] garbage) throws IOException {
    Map<Integer, byte[]> payloads = new LinkedHashMap<>();
    for (int idx : present) payloads.put(idx, chunkPayload(idx));
    int sectors = 2;
    Map<Integer, int[]> layout = new HashMap<>();
    for (Map.Entry<Integer, byte[]> e : payloads.entrySet()) {
      int count = (5 + e.getValue().length + 4095) / 4096;
      layout.put(e.getKey(), new int[] {sectors, count});
      sectors += count;
    }
    for (int idx : garbage) {
      layout.put(idx, new int[] {sectors, 2});
      sectors += 2;
    }
    byte[] out = new byte[sectors * 4096];
    Random rnd = new Random(104L);
    for (Map.Entry<Integer, int[]> e : layout.entrySet()) {
      int idx = e.getKey();
      int off = e.getValue()[0];
      int count = e.getValue()[1];
      out[idx * 4] = (byte) (off >>> 16);
      out[idx * 4 + 1] = (byte) (off >>> 8);
      out[idx * 4 + 2] = (byte) off;
      out[idx * 4 + 3] = (byte) count;
      int p = off * 4096;
      byte[] zlib = payloads.get(idx);
      if (zlib == null) {
        byte[] noise = new byte[count * 4096];
        rnd.nextBytes(noise);
        System.arraycopy(noise, 0, out, p, noise.length);
        continue;
      }
      int declared = zlib.length + 1;
      out[p] = (byte) (declared >>> 24);
      out[p + 1] = (byte) (declared >>> 16);
      out[p + 2] = (byte) (declared >>> 8);
      out[p + 3] = (byte) declared;
      out[p + 4] = 2;
      System.arraycopy(zlib, 0, out, p + 5, zlib.length);
    }
    return out;
  }

  private static long key(int idx) {
    int cx = (RCX << 5) | (idx & 31);
    int cz = (RCZ << 5) | (idx >>> 5);
    return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
  }

  /** Mirrors the adapters' whole-file {@code readBiomesInRegionFile} decode. */
  private static Map<Long, String> fullRead(byte[] region) {
    Map<Long, String> out = new HashMap<>();
    for (int lx = 0; lx < 32; lx++) {
      for (int lz = 0; lz < 32; lz++) {
        try {
          AnvilChunkView view = AnvilReader.readChunkView(region, lx, lz);
          if (view == null) continue;
          String raw = view.getBiomeAt(8, Y, 8);
          if (raw == null) continue;
          out.put(key(lz * 32 + lx), CANON.apply(raw));
        } catch (Throwable ignored) {
          // skip
        }
      }
    }
    return out;
  }

  private Path write(byte[] region) throws IOException {
    Path f = dir.resolve("r." + RCX + "." + RCZ + ".mca");
    Files.write(f, region);
    return f;
  }

  @Test
  @DisplayName("ADR-104 4.6: sampled read returns exactly the requested chunks with full-read biomes")
  void sampledMatchesFullRead() throws IOException {
    int[] present = new int[64];
    for (int i = 0; i < present.length; i++) present[i] = i * 16 + (i % 16);
    byte[] region = buildRegion(present, new int[0]);
    Path f = write(region);

    int[] requested = {present[0], present[7], present[33], present[63]};
    Map<Long, String> sampled = AnvilRegionSampler.sampleBiomes(f, RCX, RCZ, Y, requested, CANON);
    Map<Long, String> full = fullRead(region);

    assertEquals(64, full.size());
    assertEquals(requested.length, sampled.size());
    for (int idx : requested) {
      assertEquals(full.get(key(idx)), sampled.get(key(idx)));
      assertEquals(CANON.apply(biomeFor(idx)), sampled.get(key(idx)));
    }
  }

  @Test
  @DisplayName("ADR-104 4.6: sampled read touches only header + requested sectors; garbage elsewhere is never decoded")
  void readsOnlyHeaderAndRequestedSectors() throws IOException {
    int[] present = {0, 5, 100, 1023};
    int[] garbage = new int[200];
    for (int i = 0; i < garbage.length; i++) garbage[i] = 200 + i;
    byte[] region = buildRegion(present, garbage);
    Path f = write(region);

    int[] requested = {5, 1023};
    long[] bytesRead = new long[1];
    Map<Long, String> sampled = AnvilRegionSampler.sampleBiomesOrThrow(
        f, RCX, RCZ, Y, requested, CANON, bytesRead);

    assertEquals(2, sampled.size());
    assertEquals("BIOME_5", sampled.get(key(5)));
    assertEquals("BIOME_1023", sampled.get(key(1023)));
    long expected = 4096L;
    for (int idx : requested) expected += (region[idx * 4 + 3] & 0xFF) * 4096L;
    assertEquals(expected, bytesRead[0]);
    assertTrue(bytesRead[0] * 10 < region.length,
        "read " + bytesRead[0] + " of " + region.length + " bytes");
  }

  @Test
  @DisplayName("ADR-104 4.6: missing and garbage chunks are skipped per chunk")
  void missingAndGarbageChunksSkipped() throws IOException {
    byte[] region = buildRegion(new int[] {1, 2}, new int[] {3});
    Path f = write(region);

    Map<Long, String> sampled = AnvilRegionSampler.sampleBiomes(
        f, RCX, RCZ, Y, new int[] {1, 3, 4, 999, -1, 1024}, CANON);

    assertEquals(Map.of(key(1), "BIOME_1"), sampled);
  }

  @Test
  @DisplayName("ADR-104 4.6: missing file yields empty map and lastModified -1")
  void missingFile() {
    Path f = dir.resolve("r.9.9.mca");
    assertTrue(AnvilRegionSampler.sampleBiomes(f, 9, 9, Y, new int[] {0, 1}, CANON).isEmpty());
    assertEquals(-1L, AnvilRegionSampler.lastModifiedMillis(f));
    assertEquals(-1L, AnvilRegionSampler.lastModifiedMillis(null));
    assertEquals(-1L, AnvilRegionSampler.lastModifiedMillis(dir));
  }

  @Test
  @DisplayName("ADR-104 4.6: lastModifiedMillis reports the file mtime")
  void lastModifiedOfExistingFile() throws IOException {
    Path f = write(buildRegion(new int[] {0}, new int[0]));
    assertEquals(Files.getLastModifiedTime(f).toMillis(), AnvilRegionSampler.lastModifiedMillis(f));
  }

  @Test
  @DisplayName("ADR-104 4.6: fresh AnvilRegionByteCache entry is reused without disk reads")
  void usesFreshCacheEntry() throws IOException {
    byte[] region = buildRegion(new int[] {10, 20}, new int[0]);
    Path f = write(region);
    assertTrue(AnvilRegionByteCache.get(f) != null);

    long[] bytesRead = new long[1];
    Map<Long, String> sampled = AnvilRegionSampler.sampleBiomesOrThrow(
        f, RCX, RCZ, Y, new int[] {10, 20, 30}, CANON, bytesRead);

    assertEquals(0L, bytesRead[0]);
    assertEquals(Map.of(key(10), "BIOME_10", key(20), "BIOME_20"), sampled);
  }

  @Test
  @DisplayName("ADR-104 4.6: peek is non-loading and mtime-gated")
  void peekDoesNotLoad() throws IOException {
    Path f = write(buildRegion(new int[] {0}, new int[0]));
    long mtime = AnvilRegionSampler.lastModifiedMillis(f);
    assertEquals(null, AnvilRegionByteCache.peek(f, mtime));
    assertEquals(0, AnvilRegionByteCache.size());
    AnvilRegionByteCache.get(f);
    assertTrue(AnvilRegionByteCache.peek(f, mtime) != null);
    assertEquals(null, AnvilRegionByteCache.peek(f, mtime + 1));
  }

  @Test
  @DisplayName("ADR-104 4.6: empty or null request and truncated file yield empty map")
  void degenerateInputs() throws IOException {
    Path f = write(buildRegion(new int[] {0}, new int[0]));
    assertTrue(AnvilRegionSampler.sampleBiomes(f, RCX, RCZ, Y, new int[0], CANON).isEmpty());
    assertTrue(AnvilRegionSampler.sampleBiomes(f, RCX, RCZ, Y, null, CANON).isEmpty());
    Path tiny = dir.resolve("r.0.0.mca");
    Files.write(tiny, new byte[100]);
    assertFalse(AnvilRegionSampler.sampleBiomes(f, RCX, RCZ, Y, new int[] {0}, null).isEmpty());
    assertTrue(AnvilRegionSampler.sampleBiomes(tiny, 0, 0, Y, new int[] {0}, CANON).isEmpty());
  }

  @Test
  @DisplayName("ADR-104 4.6: readChunkViewFromSectors decodes the same view as the whole-region path")
  void sectorDecodeParity() throws IOException {
    byte[] region = buildRegion(new int[] {42}, new int[0]);
    int off = ((region[42 * 4] & 0xFF) << 16) | ((region[42 * 4 + 1] & 0xFF) << 8)
        | (region[42 * 4 + 2] & 0xFF);
    int count = region[42 * 4 + 3] & 0xFF;
    byte[] sectors = new byte[count * 4096];
    System.arraycopy(region, off * 4096, sectors, 0, sectors.length);
    AnvilChunkView a = AnvilReader.readChunkViewFromSectors(sectors, 10, 1);
    AnvilChunkView b = AnvilReader.readChunkView(region, 10, 1);
    assertEquals(b.getBiomeAt(8, Y, 8), a.getBiomeAt(8, Y, 8));
    assertEquals(b.dataVersion(), a.dataVersion());
  }
}
