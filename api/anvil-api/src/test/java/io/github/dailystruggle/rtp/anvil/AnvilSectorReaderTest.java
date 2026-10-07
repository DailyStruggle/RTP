package io.github.dailystruggle.rtp.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sector-only {@code .mca} reads (ADR-016; REQ-RTP-S-005 off-tick, fail-closed per S-004). */
class AnvilSectorReaderTest {

  private static final int SECTOR = 4096;
  private static final int MIN_Y = 0;
  private static final int MAX_Y = 15;

  @BeforeEach
  void reset() {
    AnvilRegionByteCache.resetAll();
    AnvilRegionHeaderCache.invalidateAll();
    AnvilRegionHeaderCache.resetStats();
    AnvilRegionOccupancyCache.invalidateAll();
    RegionFileResolver.invalidateMemo();
    AnvilSectorReader.resetStats();
    // Long window: cached tables are trusted without a stat, which the stale-table test relies on.
    AnvilRegionByteCache.setRevalidateIntervalMillis(60_000L);
  }

  @AfterEach
  void restore() {
    AnvilRegionByteCache.setRevalidateIntervalMillis(1_000L);
    AnvilRegionHeaderCache.invalidateAll();
    RegionFileResolver.invalidateMemo();
  }

  @Test
  @DisplayName("REQ-RTP-S-005: sector-only column probe and entry decode match the whole-file decode")
  void sectorReads_matchWholeFileDecode(@TempDir Path dir) throws IOException {
    byte[] stone = payload(root(0, 0, "minecraft:stone"));
    byte[] sand = payload(root(3, 2, "minecraft:sand"));
    byte[] lava = payload(root(31, 31, "minecraft:lava"));
    // Padding sectors stand in for a populated multi-MB region file.
    byte[] region = layout(64,
        new int[] {slot(0, 0), slot(3, 2), slot(31, 31)},
        new int[] {2, 10, 40},
        new byte[][] {stone, sand, lava});
    Path file = dir.resolve("r.0.0.mca");
    Files.write(file, region);

    int[][] chunks = {{0, 0}, {3, 2}, {31, 31}};
    for (int[] c : chunks) {
      ColumnProbe whole = AnvilReader.readColumnProbe(region, c[0], c[1], MIN_Y, MAX_Y);
      ColumnProbe sector = AnvilSectorReader.readColumnProbe(file, c[0], c[1], MIN_Y, MAX_Y);
      assertNotNull(sector);
      assertEquals(whole.heightmapTopY(), sector.heightmapTopY());
      for (int y = MIN_Y; y <= MAX_Y; y++) {
        assertEquals(whole.blockAt(y), sector.blockAt(y), "y=" + y + " chunk=" + c[0] + "," + c[1]);
      }
      AnvilChunkView wholeView = AnvilReader.readChunkView(region, c[0], c[1]);
      AnvilChunkView sectorView = AnvilReader.toView(AnvilSectorReader.readChunkEntry(file, c[0], c[1]).root);
      assertEquals(wholeView.blockIdAt(8, 0, 8), sectorView.blockIdAt(8, 0, 8));
    }
    assertNull(AnvilSectorReader.readColumnProbe(file, 1, 1, MIN_Y, MAX_Y), "empty slot");
    assertNull(AnvilSectorReader.readColumnProbe(dir.resolve("r.9.9.mca"), 288, 288, MIN_Y, MAX_Y), "missing file");
    assertEquals(1L, AnvilRegionHeaderCache.misses(), "one location-table read for the whole file");
    assertTrue(AnvilSectorReader.sectorBytesRead() <= 6L * 3 * SECTOR,
        "only the chunks' sector runs are read, saw " + AnvilSectorReader.sectorBytesRead());
    assertEquals(0, AnvilRegionByteCache.size(), "no whole-file load");
  }

  @Test
  @DisplayName("Cached location table predating a re-save is detected by chunk position and re-read")
  void staleTable_isDetectedAndRetried(@TempDir Path dir) throws IOException {
    byte[] a = payload(root(0, 0, "minecraft:stone"));
    byte[] b = payload(root(1, 0, "minecraft:sand"));
    Path file = dir.resolve("r.0.0.mca");
    Files.write(file, layout(4, new int[] {slot(0, 0), slot(1, 0)}, new int[] {2, 3}, new byte[][] {a, b}));
    assertEquals("minecraft:stone", AnvilSectorReader.readColumnProbe(file, 0, 0, MIN_Y, MAX_Y).blockAt(0));

    // Re-save swaps the two chunks' sectors; the cached table still points chunk (0,0) at sector 2.
    Files.write(file, layout(4, new int[] {slot(0, 0), slot(1, 0)}, new int[] {3, 2}, new byte[][] {a, b}));
    ColumnProbe probe = AnvilSectorReader.readColumnProbe(file, 0, 0, MIN_Y, MAX_Y);
    assertEquals("minecraft:stone", probe.blockAt(0), "must never answer with the other chunk's data");
    assertEquals(1L, AnvilSectorReader.staleRetries());
  }

  @Test
  @DisplayName("S-004: a run past EOF read through a fresh table fails closed with a typed exception")
  void corruptRun_failsClosed(@TempDir Path dir) throws IOException {
    byte[] region = new byte[3 * SECTOR];
    region[2] = 50; // sector 50, beyond EOF
    region[3] = 1;
    Path file = dir.resolve("r.0.0.mca");
    Files.write(file, region);
    assertThrows(CorruptRegionEntryException.class,
        () -> AnvilSectorReader.readColumnProbe(file, 0, 0, MIN_Y, MAX_Y));
    assertEquals(0L, AnvilSectorReader.staleRetries(), "fresh tables are not retried");
  }

  @Test
  @DisplayName("Decoded results hold no reference to the reused per-thread scratch buffer")
  void results_surviveScratchReuse(@TempDir Path dir) throws IOException {
    byte[] a = payload(root(0, 0, "minecraft:stone"));
    byte[] b = payload(root(1, 0, "minecraft:sand"));
    Path file = dir.resolve("r.0.0.mca");
    Files.write(file, layout(4, new int[] {slot(0, 0), slot(1, 0)}, new int[] {2, 3}, new byte[][] {a, b}));
    ColumnProbe first = AnvilSectorReader.readColumnProbe(file, 0, 0, MIN_Y, MAX_Y);
    AnvilReader.ChunkEntry firstEntry = AnvilSectorReader.readChunkEntry(file, 0, 0);
    AnvilSectorReader.readColumnProbe(file, 1, 0, MIN_Y, MAX_Y);
    AnvilSectorReader.readChunkEntry(file, 1, 0);
    assertEquals("minecraft:stone", first.blockAt(0));
    assertEquals("minecraft:stone", AnvilReader.toView(firstEntry.root).blockIdAt(8, 0, 8));
  }

  @Test
  @DisplayName("Shared adapter column probe resolves .mca and reads only sectors")
  void prefilterProbeColumn_usesSectorPath(@TempDir Path world) throws IOException {
    Path regionDir = world.resolve("region");
    Files.createDirectories(regionDir);
    // Chunk (33, -1) lives in r.1.-1.mca at local (1, 31).
    Files.write(regionDir.resolve("r.1.-1.mca"),
        layout(4, new int[] {slot(1, 31)}, new int[] {2}, new byte[][] {payload(root(33, -1, "minecraft:stone"))}));
    ColumnProbe probe = AnvilPrefilter.probeColumn(world, "", 33, -1, MIN_Y, MAX_Y);
    assertNotNull(probe);
    assertEquals("minecraft:stone", probe.blockAt(0));
    assertNull(AnvilPrefilter.probeColumn(world, "", 500, 500, MIN_Y, MAX_Y), "no region file");
    assertEquals(0, AnvilRegionByteCache.size(), "no whole-file load");
  }

  @Test
  @DisplayName("resolveExisting memoizes absence within the window and re-checks without one")
  void resolverMemo_isWindowBounded(@TempDir Path world) throws IOException {
    assertNull(RegionFileResolver.resolveExisting(world, "", 0, 0));
    Path regionDir = world.resolve("region");
    Files.createDirectories(regionDir);
    Files.write(regionDir.resolve("r.0.0.mca"), new byte[2 * SECTOR]);
    assertNull(RegionFileResolver.resolveExisting(world, "", 0, 0), "absence is memoized within the window");
    AnvilRegionByteCache.setRevalidateIntervalMillis(0L);
    RegionFileResolver.ResolvedRegion found = RegionFileResolver.resolveExisting(world, "", 0, 0);
    assertNotNull(found);
    assertEquals(regionDir.resolve("r.0.0.mca"), found.path());
  }

  @Test
  @DisplayName("Occupancy bitmap comes from the location table, never a whole-file load")
  void occupancy_readsTableOnly(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("r.0.0.mca");
    Files.write(file, layout(64, new int[] {slot(4, 5)}, new int[] {2},
        new byte[][] {payload(root(4, 5, "minecraft:stone"))}));
    assertTrue(AnvilRegionOccupancyCache.isOccupied(file, 4, 5));
    assertTrue(!AnvilRegionOccupancyCache.isOccupied(file, 5, 4));
    assertEquals(0, AnvilRegionByteCache.size());
    assertEquals(1, AnvilRegionHeaderCache.size());
  }

  // ---------------------------------------------------------------------------- fixtures

  private static int slot(int lx, int lz) {
    return lx + (lz << 5);
  }

  private static LinkedHashMap<String, Object> root(int chunkX, int chunkZ, String block) throws IOException {
    long[] hm = new long[37];
    hm[0] = 1L;
    LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
        DataVersionSupport.MC_1_20_DATA_VERSION, hm,
        List.of(AnvilTestFixtures.section((byte) 0, List.of(block))));
    root.put("xPos", chunkX);
    root.put("zPos", chunkZ);
    return root;
  }

  /** Length prefix + zlib compression byte + payload, unpadded. */
  private static byte[] payload(LinkedHashMap<String, Object> root) throws IOException {
    byte[] nbt = Nbt.writeNamedRoot("", root);
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (DeflaterOutputStream z = new DeflaterOutputStream(compressed)) {
      z.write(nbt);
    }
    byte[] zlib = compressed.toByteArray();
    byte[] out = new byte[5 + zlib.length];
    int declared = zlib.length + 1;
    out[0] = (byte) (declared >>> 24);
    out[1] = (byte) (declared >>> 16);
    out[2] = (byte) (declared >>> 8);
    out[3] = (byte) declared;
    out[4] = 2;
    System.arraycopy(zlib, 0, out, 5, zlib.length);
    return out;
  }

  /** Region of {@code totalSectors} with each payload at its sector offset (one sector each). */
  private static byte[] layout(int totalSectors, int[] slots, int[] sectorOffsets, byte[][] payloads) {
    byte[] out = new byte[totalSectors * SECTOR];
    for (int i = 0; i < slots.length; i++) {
      int count = (payloads[i].length + SECTOR - 1) / SECTOR;
      int e = slots[i] * 4;
      out[e] = (byte) (sectorOffsets[i] >>> 16);
      out[e + 1] = (byte) (sectorOffsets[i] >>> 8);
      out[e + 2] = (byte) sectorOffsets[i];
      out[e + 3] = (byte) count;
      System.arraycopy(payloads[i], 0, out, sectorOffsets[i] * SECTOR, payloads[i].length);
    }
    return out;
  }
}
