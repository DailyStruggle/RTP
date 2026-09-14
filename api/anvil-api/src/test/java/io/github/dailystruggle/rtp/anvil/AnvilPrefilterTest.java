package io.github.dailystruggle.rtp.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REQ-RTP-S-005 / ADR-016 - verdict-matrix tests for {@link AnvilPrefilter}.
 *
 * <p>Each test assembles a minimal single-chunk region buffer (via
 * {@link AnvilTestFixtures}) with a known surface block, writes it into a temp
 * world-folder layout, and asserts the probe returns the expected
 * {@link Verdict}. The probe is invoked through {@link AnvilPrefilter#probeSync}
 * to keep dispatch deterministic; the async {@link AnvilPrefilter#probe} entry
 * point is exercised indirectly via the wire-in in {@code BukkitRTPWorld.getChunkAt}.
 */
@DisplayName("REQ-RTP-S-005 / ADR-016 - AnvilPrefilter verdict matrix")
class AnvilPrefilterTest {

  // ADR-016: this module is platform-neutral; no MockBukkit setup is required.
  // The DEFAULT_RECONCILER (namespace-strip + Locale.ROOT upper-case) handles
  // both "LAVA" and "minecraft:lava" inputs without consulting any Bukkit registry.

  @Test
  @DisplayName("Lava at surface ⇒ REJECT when listed unsafe")
  void lavaAtSurfaceRejects(@TempDir Path worldFolder) throws IOException {
    writeSyntheticRegion(worldFolder, 0, 0, lavaAtOriginRoot());
    Verdict v = AnvilPrefilter.probeSync(
        worldFolder, "", 0, 0, Set.of("LAVA"));
    assertEquals(Verdict.REJECT, v);
  }

  @Test
  @DisplayName("Lava at surface with vanilla namespace input ⇒ REJECT via split normalization")
  void lavaAtSurfaceVanillaNamespaceRejects(@TempDir Path worldFolder) throws IOException {
    writeSyntheticRegion(worldFolder, 0, 0, lavaAtOriginRoot());
    // The probe reconciles internally; config-style raw ids with a minecraft: namespace
    // must resolve to the same canonical form that the palette-side reconciliation
    // produces for "minecraft:lava".
    Verdict v = AnvilPrefilter.probeSync(
        worldFolder, "", 0, 0, Set.of("minecraft:lava"));
    assertEquals(Verdict.REJECT, v);
  }

  @Test
  @DisplayName("ADR-016: REJECT retains the decoded AnvilChunkView so the vert adjustor can scan below surface")
  void rejectRetainsView(@TempDir Path worldFolder) throws IOException {
    // Regression guard for ADR-016: the prefilter is a data source, not a gate.
    // When the surface is unsafe (lava), probeSyncDetailed must still return the
    // decoded view so BukkitRTPWorld.getChunkAt can mint an Anvil-backed RTPChunk
    // and the vert adjustor can look for a safe air pocket below the surface.
    // Under ADR-016's original decision, result.view() was null on REJECT and
    // the caller returned a null chunk key, which materialised as the 10
    // unattributed fails/cycle observed on vanilla Spigot 1.20.1 in the nether.
    writeSyntheticRegion(worldFolder, 0, 0, lavaAtOriginRoot());
    AnvilPrefilter.ProbeResult result = AnvilPrefilter.probeSyncDetailed(
        worldFolder, "", 0, 0, Set.of("LAVA"));
    assertEquals(Verdict.REJECT, result.verdict());
    org.junit.jupiter.api.Assertions.assertNotNull(
        result.view(),
        "ADR-016: view must be retained on REJECT so the caller can mint an Anvil-backed chunk");
    org.junit.jupiter.api.Assertions.assertFalse(
        result.view().sections().isEmpty(),
        "the retained view should carry its decoded sections");
  }

  @Test
  @DisplayName("Stone at surface ⇒ ACCEPT when only lava is listed unsafe")
  void stoneAtSurfaceAccepts(@TempDir Path worldFolder) throws IOException {
    writeSyntheticRegion(worldFolder, 0, 0, stoneAtOriginRoot());
    Verdict v = AnvilPrefilter.probeSync(
        worldFolder, "", 0, 0, Set.of("LAVA"));
    assertEquals(Verdict.ACCEPT, v);
  }

  @Test
  @DisplayName("Empty unsafe set ⇒ ACCEPT (prefilter never rejects what config permits)")
  void emptyUnsafeAlwaysAccepts(@TempDir Path worldFolder) throws IOException {
    writeSyntheticRegion(worldFolder, 0, 0, lavaAtOriginRoot());
    Verdict v = AnvilPrefilter.probeSync(
        worldFolder, "", 0, 0, Collections.emptySet());
    assertEquals(Verdict.ACCEPT, v);
  }

  @Test
  @DisplayName("Missing region file ⇒ UNKNOWN (fall through to live load / generation)")
  void missingRegionFileUnknown(@TempDir Path worldFolder) throws IOException {
    // Intentionally write nothing. The probe must treat the absent file as UNKNOWN
    // rather than REJECT - per ADR-016, ungenerated chunks are the live load's job.
    Verdict v = AnvilPrefilter.probeSync(
        worldFolder, "", 0, 0, Set.of("LAVA"));
    assertEquals(Verdict.UNKNOWN, v);
  }

  @Test
  @DisplayName("Arbitrary DataVersion ⇒ decoder is authoritative (gate retired 2026-04-20)")
  void arbitraryDataVersionDeferredToDecoder(@TempDir Path worldFolder) throws IOException {
    // ADR-016 section 13.1 follow-up: the DataVersion range gate has been retired.
    // Every DataVersion is admitted; the decoder (`AnvilReader`) is the only
    // gate, and decode failures surface as WARNING + UNKNOWN. Here we feed
    // DataVersion=1 (well outside any real MC range) but with a valid
    // synthetic chunk payload containing lava at the surface - the probe
    // must now parse it and return REJECT rather than the old UNKNOWN.
    writeSyntheticRegion(worldFolder, 0, 0, rootAtDataVersion(1));
    Verdict v = AnvilPrefilter.probeSync(
        worldFolder, "", 0, 0, Set.of("LAVA"));
    assertEquals(Verdict.REJECT, v);
  }

  @Test
  @DisplayName("Negative chunk coords resolve to the correct region file via floorMod")
  void negativeChunkCoordsResolveCorrectly(@TempDir Path worldFolder) throws IOException {
    // Chunk (-1, -1) lives in region (-1, -1) at region-local (31, 31).
    LinkedHashMap<String, Object> root = airEverywhereRoot();
    writeSyntheticRegionAtLocalSlot(worldFolder, -1, -1, 31, 31, root);
    Verdict v = AnvilPrefilter.probeSync(
        worldFolder, "", -1, -1, Set.of("LAVA"));
    assertEquals(Verdict.ACCEPT, v);
  }

  @Test
  @DisplayName("Real fixture (1_20_R1) at a random candidate chunk ⇒ ACCEPT with lava unsafe")
  void realFixtureAccepts(@TempDir Path worldFolder) throws IOException {
    // Copy the trimmed real region file into the temp world tree and probe the single
    // populated chunk (0,0). The fixture was captured from a vanilla spawn region, so
    // we expect ACCEPT: the heightmap top should not be lava anywhere in the chunk.
    Path regionDir = worldFolder.resolve("region");
    Files.createDirectories(regionDir);
    try (InputStream in = AnvilPrefilterTest.class.getResourceAsStream(
        "/anvil/real/1_20_R1/r.0.0.mca")) {
      if (in == null) throw new IllegalStateException("Missing real fixture");
      Files.write(regionDir.resolve("r.0.0.mca"), in.readAllBytes());
    }
    Verdict v = AnvilPrefilter.probeSync(
        worldFolder, "", 0, 0, Set.of("LAVA"));
    assertEquals(Verdict.ACCEPT, v);
  }

  @Test
  @DisplayName("Prefilter reads region bytes via AnvilRegionByteCache (OOM regression, 2026-04-23)")
  void prefilterRoutesThroughRegionByteCache(@TempDir Path worldFolder) throws IOException {
    // Regression guard for the 2026-04-23 OOM: AnvilPrefilter.probeSyncDetailed used
    // to call Files.readAllBytes directly, bypassing the 16-entry LRU cache and the
    // miss-coalescing path. Under ScanTask's 50-in-flight workload this let 50
    // concurrent probes each allocate the same 2-8 MB .mca byte[], triggering
    // "Retried waiting for GCLocker too often" and OOM on the ForkJoin common pool.
    // This test asserts the prefilter now shares the same cache as
    // BukkitRTPWorld / FoliaRTPWorld: a second probe of the same chunk must register
    // as a cache hit.
    writeSyntheticRegion(worldFolder, 0, 0, stoneAtOriginRoot());
    AnvilRegionByteCache.invalidateAll();
    AnvilRegionByteCache.resetStats();
    Verdict first = AnvilPrefilter.probeSync(
        worldFolder, "", 0, 0, Set.of("LAVA"));
    Verdict second = AnvilPrefilter.probeSync(
        worldFolder, "", 0, 0, Set.of("LAVA"));
    assertEquals(Verdict.ACCEPT, first);
    assertEquals(Verdict.ACCEPT, second);
    AnvilRegionByteCache.Stats stats = AnvilRegionByteCache.stats();
    assertTrue(stats.misses() >= 1,
        "first probe should register as a cache miss; stats=" + stats);
    assertTrue(stats.hits() >= 1,
        "second probe of same region should register as a cache hit (proves "
            + "prefilter is not bypassing AnvilRegionByteCache); stats=" + stats);
  }

  @Test
  @DisplayName("Prefilter probe handles corrupt or missing entries and edge cases")
  void prefilterCorruptAndEdgeCases(@TempDir Path worldFolder) throws IOException {
    // Missing region file returns UNKNOWN
    Verdict missing = AnvilPrefilter.probeSync(worldFolder, "", 10, 10, Set.of());
    assertEquals(Verdict.UNKNOWN, missing);

    // Empty root / missing heightmap / missing sections returns UNKNOWN
    LinkedHashMap<String, Object> emptyRoot = new LinkedHashMap<>();
    emptyRoot.put("DataVersion", DataVersionSupport.MC_1_20_DATA_VERSION);
    writeSyntheticRegion(worldFolder, 0, 0, emptyRoot);
    Verdict emptyVerdict = AnvilPrefilter.probeSync(worldFolder, "", 0, 0, Set.of());
    assertEquals(Verdict.UNKNOWN, emptyVerdict);

    // Corrupt region file (truncated)
    Path regionDir = worldFolder.resolve("region");
    Files.createDirectories(regionDir);
    Files.write(regionDir.resolve("r.1.1.mca"), new byte[100]); // < 8192
    Verdict corrupt = AnvilPrefilter.probeSync(worldFolder, "", 32, 32, Set.of());
    assertEquals(Verdict.UNKNOWN, corrupt);

    // Additional branches:
    // 1. worldFolder == null in probeDetailed
    AnvilPrefilter.ProbeResult nullWorldRes = AnvilPrefilter.probeDetailed(null, null, 0, 0, Set.of(), null).join();
    assertEquals(Verdict.UNKNOWN, nullWorldRes.verdict());

    // 2. probeSyncDetailed with null unsafe set returns ACCEPT
    Path cleanWorld = worldFolder.resolve("cleanWorld");
    Files.createDirectories(cleanWorld);
    LinkedHashMap<String, Object> stoneRoot = stoneAtOriginRoot();
    writeSyntheticRegion(cleanWorld, 0, 0, stoneRoot);
    AnvilPrefilter.ProbeResult nullUnsafeRes = AnvilPrefilter.probeSyncDetailed(cleanWorld, "", 0, 0, null);
    assertEquals(Verdict.ACCEPT, nullUnsafeRes.verdict());
    assertNotNull(nullUnsafeRes.view());

    // 3. regionFileFor helper test with dimension subpath
    Path overworldPath = AnvilPrefilter.regionFileFor(worldFolder, "", 0, 0);
    assertTrue(overworldPath.toString().endsWith("r.0.0.mca"));
    Path netherPath = AnvilPrefilter.regionFileFor(worldFolder, "DIM-1", 0, 0);
    assertTrue(netherPath.toString().contains("DIM-1"));

    // 4. Default reconciler edge cases
    org.junit.jupiter.api.Assertions.assertNull(AnvilPrefilter.DEFAULT_RECONCILER.apply(null));
    assertEquals("STONE", AnvilPrefilter.DEFAULT_RECONCILER.apply("minecraft:stone"));
    assertEquals("LAVA", AnvilPrefilter.DEFAULT_RECONCILER.apply("lava"));

    // 5. Unsafe block at groundY + 1 and groundY + 2
    // Build root with stone at groundY=0 and lava at groundY+1=1
    List<String> lavaAbovePalette = Arrays.asList("minecraft:air", "minecraft:stone", "minecraft:lava");
    int[] indices = new int[4096];
    indices[PackedPaletteDecoder.entryIndex(0, 0, 0)] = 1; // groundY=0 -> stone
    indices[PackedPaletteDecoder.entryIndex(0, 1, 0)] = 2; // groundY+1=1 -> lava
    long[] packed = AnvilTestFixtures.packIndices(4, indices);
    LinkedHashMap<String, Object> sec = AnvilTestFixtures.section((byte) 0, lavaAbovePalette, packed);
    long[] hm = new long[37];
    hm[0] = 1L; // column 0,0 has ground at relative 0
    LinkedHashMap<String, Object> customRoot = AnvilTestFixtures.chunkRoot(
        DataVersionSupport.MC_1_20_DATA_VERSION, hm, List.of(sec));

    Path customWorld = worldFolder.resolve("customWorld");
    Files.createDirectories(customWorld);
    writeSyntheticRegion(customWorld, 0, 0, customRoot);
    Verdict customVerdict = AnvilPrefilter.probeSync(customWorld, "", 0, 0, Set.of("LAVA"));
    assertEquals(Verdict.REJECT, customVerdict);

    // 6. Unsafe block at groundY + 2
    int[] indices2 = new int[4096];
    indices2[PackedPaletteDecoder.entryIndex(0, 0, 0)] = 1; // groundY=0 -> stone
    indices2[PackedPaletteDecoder.entryIndex(0, 2, 0)] = 2; // groundY+2=2 -> lava
    long[] packed2 = AnvilTestFixtures.packIndices(4, indices2);
    LinkedHashMap<String, Object> sec2 = AnvilTestFixtures.section((byte) 0, lavaAbovePalette, packed2);
    LinkedHashMap<String, Object> customRoot2 = AnvilTestFixtures.chunkRoot(
        DataVersionSupport.MC_1_20_DATA_VERSION, hm, List.of(sec2));
    Path customWorld2 = worldFolder.resolve("customWorld2");
    Files.createDirectories(customWorld2);
    writeSyntheticRegion(customWorld2, 0, 0, customRoot2);
    assertEquals(Verdict.REJECT, AnvilPrefilter.probeSync(customWorld2, "", 0, 0, Set.of("LAVA")));
  }

  // ---------------------------------------------------------------------------- helpers

  /**
   * Writes a synthetic single-chunk region at absolute chunk coords {@code (cx, cz)},
   * which must satisfy {@code cx >>> 5 == 0 && cz >>> 5 == 0} for the simple slot-0
   * writer. Tests that need negative region coords should use
   * {@link #writeSyntheticRegionAtLocalSlot}.
   */
  private static void writeSyntheticRegion(
      Path worldFolder, int cx, int cz, LinkedHashMap<String, Object> root) throws IOException {
    int regionX = cx >> 5;
    int regionZ = cz >> 5;
    byte[] bytes = AnvilTestFixtures.writeSingleChunkRegion(root);
    Path regionDir = worldFolder.resolve("region");
    Files.createDirectories(regionDir);
    Files.write(regionDir.resolve("r." + regionX + "." + regionZ + ".mca"), bytes);
  }

  /**
   * Writes a synthetic region file whose location header has the chunk entry at a caller-chosen
   * region-local slot {@code (rx, rz)} instead of {@code (0,0)}. Used to cover
   * {@link Math#floorMod}-based region-local coordinate resolution for negative chunks.
   */
  private static void writeSyntheticRegionAtLocalSlot(
      Path worldFolder, int cx, int cz, int rx, int rz, LinkedHashMap<String, Object> root)
      throws IOException {
    int regionX = cx >> 5;
    int regionZ = cz >> 5;
    byte[] baseBytes = AnvilTestFixtures.writeSingleChunkRegion(root);
    // Move the location-table entry from slot (0,0) to slot (rx,rz). The payload itself
    // is identical; only the 4-byte location-table entry changes.
    byte[] bytes = baseBytes.clone();
    int sectorOffset = bytes[0] & 0xFF; // untouched; we keep the same payload layout
    int sectorHi     = bytes[1] & 0xFF;
    int sectorMid    = bytes[2] & 0xFF;
    int sectorCount  = bytes[3] & 0xFF;
    // Clear the original slot 0,0.
    bytes[0] = bytes[1] = bytes[2] = bytes[3] = 0;
    int newIndex = (rx & 31) + ((rz & 31) << 5);
    int newOffset = newIndex * 4;
    bytes[newOffset]     = (byte) sectorOffset;
    bytes[newOffset + 1] = (byte) sectorHi;
    bytes[newOffset + 2] = (byte) sectorMid;
    bytes[newOffset + 3] = (byte) sectorCount;

    Path regionDir = worldFolder.resolve("region");
    Files.createDirectories(regionDir);
    Files.write(regionDir.resolve("r." + regionX + "." + regionZ + ".mca"), bytes);
  }

  /**
   * Build a chunk root where the column at {@code (0,0)} has lava at world-Y 0 and air
   * everywhere else. Heightmap entry at {@code (0,0)} is {@code 1}, meaning the top
   * motion-blocking block is at relative height {@code 0} (absolute {@code minHeight + 0 = 0}).
   */
  private static LinkedHashMap<String, Object> lavaAtOriginRoot() throws IOException {
    List<String> palette = Arrays.asList("minecraft:air", "minecraft:lava");
    int[] indices = new int[4096];
    indices[0] = 1; // y=0, z=0, x=0 → lava
    long[] packed = AnvilTestFixtures.packIndices(4, indices);

    LinkedHashMap<String, Object> section = AnvilTestFixtures.section((byte) 0, palette, packed);
    long[] heightmap = new long[37];
    // Entry at column index 0 = 1 → ground at (minHeight + 0) = 0.
    heightmap[0] = 1L;
    return AnvilTestFixtures.chunkRoot(
        DataVersionSupport.MC_1_20_DATA_VERSION, heightmap, List.of(section));
  }

  /** Same shape as {@link #lavaAtOriginRoot} but with stone instead of lava at the surface. */
  private static LinkedHashMap<String, Object> stoneAtOriginRoot() throws IOException {
    List<String> palette = Arrays.asList("minecraft:air", "minecraft:stone");
    int[] indices = new int[4096];
    indices[0] = 1;
    long[] packed = AnvilTestFixtures.packIndices(4, indices);

    LinkedHashMap<String, Object> section = AnvilTestFixtures.section((byte) 0, palette, packed);
    long[] heightmap = new long[37];
    heightmap[0] = 1L;
    return AnvilTestFixtures.chunkRoot(
        DataVersionSupport.MC_1_20_DATA_VERSION, heightmap, List.of(section));
  }

  /**
   * A chunk root whose heightmap is entirely zero (empty columns), so the probe has no
   * surface to sample in any column and therefore returns {@link Verdict#ACCEPT}.
   */
  private static LinkedHashMap<String, Object> airEverywhereRoot() throws IOException {
    List<String> palette = Arrays.asList("minecraft:air", "minecraft:stone");
    int[] indices = new int[4096]; // all zero → all air
    long[] packed = AnvilTestFixtures.packIndices(4, indices);

    LinkedHashMap<String, Object> section = AnvilTestFixtures.section((byte) 0, palette, packed);
    long[] heightmap = new long[37]; // all zero → no columns to sample
    return AnvilTestFixtures.chunkRoot(
        DataVersionSupport.MC_1_20_DATA_VERSION, heightmap, List.of(section));
  }

  /**
   * A chunk root with a caller-chosen {@code DataVersion}. Used to exercise the
   * {@link DataVersionSupport} whitelist gate.
   */
  private static LinkedHashMap<String, Object> rootAtDataVersion(int dataVersion) throws IOException {
    List<String> palette = Arrays.asList("minecraft:air", "minecraft:lava");
    int[] indices = new int[4096];
    indices[0] = 1;
    long[] packed = AnvilTestFixtures.packIndices(4, indices);

    LinkedHashMap<String, Object> section = AnvilTestFixtures.section((byte) 0, palette, packed);
    long[] heightmap = new long[37];
    heightmap[0] = 1L;
    return AnvilTestFixtures.chunkRoot(dataVersion, heightmap, List.of(section));
  }
}
