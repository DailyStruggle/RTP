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
 * ADR-113 unofficial 1.18-1.20.4 range: 1.18+ chunks share the root-level
 * {@code sections}/{@code block_states} layout and decode; pre-1.18 {@code Level}-wrapped
 * chunks (not yet re-saved after a world upgrade) are UNKNOWN and fall through to the
 * live async load (ADR-016; S-004: never an "all air" reject).
 */
class AnvilLegacyChunkLayoutTest {

  /** Minecraft 1.18.2. */
  private static final int DV_1_18_2 = 2975;
  /** Minecraft 1.16.5. */
  private static final int DV_1_16_5 = 2586;

  private static long[] allIndex(int index) {
    int[] idx = new int[4096];
    Arrays.fill(idx, index);
    return AnvilTestFixtures.packIndices(4, idx);
  }

  @Test
  @DisplayName("1.18.2 chunk (DataVersion 2975, Status \"full\") decodes in the column probe and the chunk view")
  void chunk1182Decodes() throws Exception {
    LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(DV_1_18_2, new long[37], List.of(
        AnvilTestFixtures.section((byte) 4, List.of("minecraft:air", "minecraft:stone"), allIndex(1))));
    root.put("Status", "full");
    byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root);

    assertFalse(AnvilReader.isIncompleteChunk(AnvilReader.readChunkEntry(region, 0, 0).root));
    ColumnProbe probe = AnvilReader.readColumnProbe(region, 0, 0, 60, 90);
    assertNotNull(probe);
    assertEquals("minecraft:stone", probe.blockAt(70));
    AnvilChunkView view = AnvilReader.readChunkView(region, 0, 0);
    assertEquals(DV_1_18_2, view.dataVersion());
    assertEquals("minecraft:stone", view.blockIdAt(8, 70, 8));
  }

  @Test
  @DisplayName("Pre-1.18 Level-wrapped chunk is UNKNOWN (null column probe, no decoded sections)")
  void levelWrappedChunkIsUnknown() throws Exception {
    LinkedHashMap<String, Object> oldSection = new LinkedHashMap<>();
    oldSection.put("Y", (byte) 4);
    oldSection.put("Palette", new Nbt.NbtList(Nbt.TAG_COMPOUND,
        new ArrayList<>(List.of(AnvilTestFixtures.paletteEntry("minecraft:stone")))));
    LinkedHashMap<String, Object> level = new LinkedHashMap<>();
    level.put("Status", "full");
    level.put("Sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, new ArrayList<>(List.of(oldSection))));
    LinkedHashMap<String, Object> root = new LinkedHashMap<>();
    root.put("DataVersion", DV_1_16_5);
    root.put("Level", level);
    byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root);

    assertNull(AnvilReader.readColumnProbe(region, 0, 0, 60, 90));
    AnvilChunkView view = AnvilReader.readChunkView(region, 0, 0);
    assertTrue(view == null || view.sections().isEmpty(), "Level-wrapped sections must not decode");
  }
}
