package io.github.dailystruggle.rtp.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DataVersion 5023 (Minecraft 26.3) block palettes and proto-chunk {@code Status} handling
 * (ADR-016; S-004: undecodable or unfinished chunks are UNKNOWN, never an "all air" reject).
 */
class AnvilModernPaletteAndStatusTest {

  private static final int DV_26_3 = 5023;

  private static LinkedHashMap<String, Object> sectionWithPalette(byte y, Nbt.NbtList palette, long[] data) {
    LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
    sec.put("Y", y);
    LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
    bs.put("palette", palette);
    if (data != null) bs.put("data", data);
    sec.put("block_states", bs);
    return sec;
  }

  private static long[] allIndex(int index) {
    int[] idx = new int[4096];
    Arrays.fill(idx, index);
    return AnvilTestFixtures.packIndices(4, idx);
  }

  private static LinkedHashMap<String, Object> root(String status, List<LinkedHashMap<String, Object>> sections)
      throws Exception {
    LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(DV_26_3, new long[37], sections);
    if (status != null) root.put("Status", status);
    return root;
  }

  private static Nbt.NbtList stringPalette(String... ids) {
    return new Nbt.NbtList(Nbt.TAG_STRING, new ArrayList<>(List.of((Object[]) ids)));
  }

  @Test
  @DisplayName("Bare-string block palette (DataVersion 5023) decodes in the column probe and the chunk view")
  void stringPaletteDecodes() throws Exception {
    byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root("minecraft:full", List.of(
        sectionWithPalette((byte) 4, stringPalette("minecraft:air", "minecraft:stone"), allIndex(1)))));

    ColumnProbe probe = AnvilReader.readColumnProbe(region, 0, 0, 60, 90);
    assertNotNull(probe);
    assertEquals("minecraft:stone", probe.blockAt(70));
    assertEquals("minecraft:stone", AnvilReader.readChunkView(region, 0, 0).blockIdAt(8, 70, 8));
  }

  @Test
  @DisplayName("Mixed palette: {\"\": id} wrappers and {id, properties} compounds decode")
  void mixedWrappedPaletteDecodes() throws Exception {
    LinkedHashMap<String, Object> wrapped = new LinkedHashMap<>();
    wrapped.put("", "minecraft:water");
    LinkedHashMap<String, Object> props = new LinkedHashMap<>();
    props.put("age", "23");
    LinkedHashMap<String, Object> kelp = new LinkedHashMap<>();
    kelp.put("id", "minecraft:kelp");
    kelp.put("properties", props);
    Nbt.NbtList palette = new Nbt.NbtList(Nbt.TAG_COMPOUND, new ArrayList<>(List.of(wrapped, kelp)));

    byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root("minecraft:full", List.of(
        sectionWithPalette((byte) 4, palette, allIndex(1)))));

    ColumnProbe probe = AnvilReader.readColumnProbe(region, 0, 0, 60, 90);
    assertNotNull(probe);
    assertEquals("minecraft:kelp", probe.blockAt(70));
    assertEquals("minecraft:water", AnvilReader.blockStateName(wrapped));
    assertEquals("minecraft:stone", AnvilReader.blockStateName(AnvilTestFixtures.paletteEntry("minecraft:stone")));
    assertNull(AnvilReader.blockStateName(12345));
    assertNull(AnvilReader.blockStateName(new LinkedHashMap<String, Object>()));
  }

  @Test
  @DisplayName("Proto-chunk Status gives an UNKNOWN (null) column probe; full or absent Status decodes")
  void protoChunkIsUnknown() throws Exception {
    List<LinkedHashMap<String, Object>> airSections = List.of(
        sectionWithPalette((byte) 4, stringPalette("minecraft:air"), null));

    byte[] proto = AnvilTestFixtures.writeSingleChunkRegion(root("minecraft:structure_starts", airSections));
    assertNull(AnvilReader.readColumnProbe(proto, 0, 0, 60, 90));
    assertTrue(AnvilReader.isIncompleteChunk(AnvilReader.readChunkEntry(proto, 0, 0).root));

    byte[] full = AnvilTestFixtures.writeSingleChunkRegion(root("minecraft:full", airSections));
    assertNotNull(AnvilReader.readColumnProbe(full, 0, 0, 60, 90));
    assertFalse(AnvilReader.isIncompleteChunk(AnvilReader.readChunkEntry(full, 0, 0).root));

    byte[] noStatus = AnvilTestFixtures.writeSingleChunkRegion(root(null, airSections));
    assertNotNull(AnvilReader.readColumnProbe(noStatus, 0, 0, 60, 90));
    assertFalse(AnvilReader.isIncompleteChunk(null));
  }

  @Test
  @DisplayName("No decodable block section gives an UNKNOWN (null) column probe, not an all-air probe")
  void undecodableSectionsAreUnknown() throws Exception {
    Nbt.NbtList bogus = new Nbt.NbtList(Nbt.TAG_INT, new ArrayList<>(List.of(1, 2)));
    byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root("minecraft:full", List.of(
        sectionWithPalette((byte) 4, bogus, null))));
    assertNull(AnvilReader.readColumnProbe(region, 0, 0, 60, 90));
  }
}
