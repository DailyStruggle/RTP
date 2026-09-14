package io.github.dailystruggle.rtp.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnvilReaderEdgeCasesTest {

  @Test
  @DisplayName("readChunkEntry validates coordinates and buffer bounds")
  void testBoundsAndCoordinates() {
    assertThrows(CorruptRegionEntryException.class, () -> AnvilReader.readChunkEntry(null, 0, 0));
    assertThrows(CorruptRegionEntryException.class, () -> AnvilReader.readChunkEntry(new byte[100], 0, 0)); // < 8192

    byte[] validHeader = new byte[8192];
    assertThrows(IllegalArgumentException.class, () -> AnvilReader.readChunkEntry(validHeader, -1, 0));
    assertThrows(IllegalArgumentException.class, () -> AnvilReader.readChunkEntry(validHeader, 32, 0));
    assertThrows(IllegalArgumentException.class, () -> AnvilReader.readChunkEntry(validHeader, 0, -1));
    assertThrows(IllegalArgumentException.class, () -> AnvilReader.readChunkEntry(validHeader, 0, 32));

    // Sector offset 0 -> returns null (absent chunk)
    try {
      assertNull(AnvilReader.readChunkEntry(validHeader, 0, 0));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  @DisplayName("Corrupt chunk headers throw CorruptRegionEntryException or UnsupportedAnvilFormatException")
  void testCorruptHeaders() {
    // Sector offset points past file length
    byte[] region = new byte[8192 + 4096];
    int cx = 0, cz = 0;
    // Set location table: sectorOffset = 2 (byte 8192), sectorCount = 5 (needs 20480 bytes, file is 12288)
    region[0] = 0;
    region[1] = 0;
    region[2] = 2;
    region[3] = 5;

    assertThrows(CorruptRegionEntryException.class, () -> AnvilReader.readChunkEntry(region, cx, cz));

    // Valid sector bounds, but external compression flag (0x80)
    byte[] region2 = new byte[8192 + 4096];
    region2[2] = 2; // sectorOffset 2
    region2[3] = 1; // sectorCount 1 (4096 bytes)
    ByteBuffer bb = ByteBuffer.wrap(region2, 8192, 4096);
    bb.putInt(10); // declaredLength
    bb.put((byte) (0x80 | 2)); // compressionByte with EXTERNAL_FLAG

    assertThrows(UnsupportedAnvilFormatException.class, () -> AnvilReader.readChunkEntry(region2, cx, cz));

    // Implausible declared length: length <= 0
    ByteBuffer bbZero = ByteBuffer.wrap(region2, 8192, 4096);
    bbZero.putInt(0);
    bbZero.put((byte) 2);
    assertThrows(CorruptRegionEntryException.class, () -> AnvilReader.readChunkEntry(region2, cx, cz));

    // Implausible declared length: length > budget
    ByteBuffer bbBig = ByteBuffer.wrap(region2, 8192, 4096);
    bbBig.putInt(10000);
    bbBig.put((byte) 2);
    assertThrows(CorruptRegionEntryException.class, () -> AnvilReader.readChunkEntry(region2, cx, cz));

    // Unknown compression mode (e.g. 99)
    ByteBuffer bbUnknown = ByteBuffer.wrap(region2, 8192, 4096);
    bbUnknown.putInt(10);
    bbUnknown.put((byte) 99);
    assertThrows(UnsupportedAnvilFormatException.class, () -> AnvilReader.readChunkEntry(region2, cx, cz));
  }

  @Test
  @DisplayName("toView handles malformed and incomplete section compounds gracefully")
  void testToViewMalformedSections() {
    LinkedHashMap<String, Object> root = new LinkedHashMap<>();
    root.put("DataVersion", 2975);

    Nbt.NbtList sections = new Nbt.NbtList(Nbt.TAG_COMPOUND, new java.util.ArrayList<>());

    // Section 1: not a map
    sections.items.add("not-a-map");

    // Section 2: missing Y
    Map<String, Object> secMissingY = new LinkedHashMap<>();
    secMissingY.put("block_states", Map.of());
    sections.items.add(secMissingY);

    // Section 3: missing block_states
    Map<String, Object> secMissingBs = new LinkedHashMap<>();
    secMissingBs.put("Y", (byte) 0);
    sections.items.add(secMissingBs);

    // Section 4: block_states missing palette
    Map<String, Object> secMissingPal = new LinkedHashMap<>();
    secMissingPal.put("Y", (byte) 1);
    secMissingPal.put("block_states", Map.of("data", new long[4]));
    sections.items.add(secMissingPal);

    // Section 5: palette has non-map or non-string Name
    Map<String, Object> secBadPal = new LinkedHashMap<>();
    secBadPal.put("Y", (byte) 2);
    Nbt.NbtList badPalList = new Nbt.NbtList(Nbt.TAG_COMPOUND, new java.util.ArrayList<>());
    badPalList.items.add("not-map-entry");
    secBadPal.put("block_states", Map.of("palette", badPalList));
    sections.items.add(secBadPal);

    // Section 6: biome palette has non-string entry
    Map<String, Object> secBadBiome = new LinkedHashMap<>();
    secBadBiome.put("Y", (byte) 3);
    Nbt.NbtList badBiomeList = new Nbt.NbtList(Nbt.TAG_STRING, new java.util.ArrayList<>());
    badBiomeList.items.add(12345); // integer instead of string
    secBadBiome.put("biomes", Map.of("palette", badBiomeList));
    sections.items.add(secBadBiome);

    root.put("sections", sections);

    AnvilChunkView view = AnvilReader.toView(root);
    assertNotNull(view);
    assertEquals(2975, view.dataVersion());
    assertTrue(view.sections().isEmpty());
    assertTrue(view.biomeSections().isEmpty());
  }

  @Test
  @DisplayName("readColumnProbe with empty heightmap or absent section")
  void testColumnProbeHeightmapBranches() {
    // Heightmap array present but length 0
    long[] emptyHm = new long[0];
    PaletteSection sec = new PaletteSection(0, List.of("minecraft:stone"), new long[4]);
    // Call computeHeightmapTop via synthetic chunk root
    LinkedHashMap<String, Object> root = new LinkedHashMap<>();
    root.put("DataVersion", 2975);
    LinkedHashMap<String, Object> hm = new LinkedHashMap<>();
    hm.put("MOTION_BLOCKING_NO_LEAVES", emptyHm);
    root.put("Heightmaps", hm);

    Nbt.NbtList secList = new Nbt.NbtList(Nbt.TAG_COMPOUND, new java.util.ArrayList<>());
    Map<String, Object> secMap = new LinkedHashMap<>();
    secMap.put("Y", (byte) 0);
    secMap.put("block_states", Map.of("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(Map.of("Name", "minecraft:stone")))));
    secList.items.add(secMap);
    root.put("sections", secList);

    AnvilChunkView view = AnvilReader.toView(root);
    assertNotNull(view);
    // getSurfaceHeight returns 0 when heightmap is empty
    assertEquals(0, view.getSurfaceHeight(8, 8));

    // Exercise columnProbeDecision directly for complete branch coverage
    java.util.List<String> rootPath = java.util.Collections.emptyList();
    assertEquals(Nbt.SelectiveFilter.Decision.RECURSE,
        AnvilReader.columnProbeDecisionForTest(rootPath, "sections", Nbt.TAG_LIST));
    assertEquals(Nbt.SelectiveFilter.Decision.SKIP,
        AnvilReader.columnProbeDecisionForTest(rootPath, "sections", Nbt.TAG_COMPOUND)); // wrong type
    assertEquals(Nbt.SelectiveFilter.Decision.RECURSE,
        AnvilReader.columnProbeDecisionForTest(rootPath, "Heightmaps", Nbt.TAG_COMPOUND));
    assertEquals(Nbt.SelectiveFilter.Decision.SKIP,
        AnvilReader.columnProbeDecisionForTest(rootPath, "Heightmaps", Nbt.TAG_LIST)); // wrong type
    assertEquals(Nbt.SelectiveFilter.Decision.KEEP,
        AnvilReader.columnProbeDecisionForTest(rootPath, "DataVersion", Nbt.TAG_INT));
    assertEquals(Nbt.SelectiveFilter.Decision.SKIP,
        AnvilReader.columnProbeDecisionForTest(rootPath, "other", Nbt.TAG_INT));

    // Under Heightmaps
    java.util.List<String> hmPath = java.util.List.of("Heightmaps");
    assertEquals(Nbt.SelectiveFilter.Decision.KEEP,
        AnvilReader.columnProbeDecisionForTest(hmPath, "MOTION_BLOCKING_NO_LEAVES", Nbt.TAG_LONG_ARRAY));
    assertEquals(Nbt.SelectiveFilter.Decision.SKIP,
        AnvilReader.columnProbeDecisionForTest(hmPath, "WORLD_SURFACE", Nbt.TAG_LONG_ARRAY));

    // Under sections
    java.util.List<String> secPath1 = java.util.List.of("sections");
    assertEquals(Nbt.SelectiveFilter.Decision.RECURSE,
        AnvilReader.columnProbeDecisionForTest(secPath1, "[]", Nbt.TAG_COMPOUND));

    java.util.List<String> secPath2 = java.util.List.of("sections", "[]");
    assertEquals(Nbt.SelectiveFilter.Decision.KEEP,
        AnvilReader.columnProbeDecisionForTest(secPath2, "Y", Nbt.TAG_BYTE));
    assertEquals(Nbt.SelectiveFilter.Decision.RECURSE,
        AnvilReader.columnProbeDecisionForTest(secPath2, "block_states", Nbt.TAG_COMPOUND));
    assertEquals(Nbt.SelectiveFilter.Decision.RECURSE,
        AnvilReader.columnProbeDecisionForTest(secPath2, "biomes", Nbt.TAG_COMPOUND));
    assertEquals(Nbt.SelectiveFilter.Decision.SKIP,
        AnvilReader.columnProbeDecisionForTest(secPath2, "BlockLight", Nbt.TAG_BYTE_ARRAY));

    java.util.List<String> secPath3 = java.util.List.of("sections", "[]", "block_states");
    assertEquals(Nbt.SelectiveFilter.Decision.KEEP,
        AnvilReader.columnProbeDecisionForTest(secPath3, "palette", Nbt.TAG_LIST));
    assertEquals(Nbt.SelectiveFilter.Decision.KEEP,
        AnvilReader.columnProbeDecisionForTest(secPath3, "data", Nbt.TAG_LONG_ARRAY));
    assertEquals(Nbt.SelectiveFilter.Decision.SKIP,
        AnvilReader.columnProbeDecisionForTest(secPath3, "other", Nbt.TAG_INT));

    // Other path depth
    java.util.List<String> otherPath = java.util.List.of("other");
    assertEquals(Nbt.SelectiveFilter.Decision.SKIP,
        AnvilReader.columnProbeDecisionForTest(otherPath, "foo", Nbt.TAG_INT));
  }
}
