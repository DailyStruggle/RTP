package io.github.dailystruggle.rtp.api.schematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchematicModelsTest {

  @Test
  @DisplayName("BlockEntityData and PlacedBlockEntity models")
  void testBlockEntityModels() {
    BlockEntityData data = new BlockEntityData(1, 2, 3, "minecraft:chest", Map.of("Lock", "key"));
    assertEquals(1, data.x());
    assertEquals(2, data.y());
    assertEquals(3, data.z());
    assertEquals("minecraft:chest", data.id());
    assertEquals("key", data.nbt().get("Lock"));
    assertTrue(data.toString().contains("BlockEntityData"));
    assertTrue(data.toString().contains("minecraft:chest"));

    assertThrows(NullPointerException.class, () -> new BlockEntityData(1, 2, 3, null, Map.of()));
    assertThrows(NullPointerException.class, () -> new BlockEntityData(1, 2, 3, "id", null));

    PlacedBlockEntity placed = new PlacedBlockEntity(10, 20, 30, data);
    assertEquals(10, placed.x());
    assertEquals(20, placed.y());
    assertEquals(30, placed.z());
    assertEquals(data, placed.data());
    assertTrue(placed.toString().contains("PlacedBlockEntity"));

    assertThrows(NullPointerException.class, () -> new PlacedBlockEntity(1, 2, 3, null));
  }

  @Test
  @DisplayName("BlockPlacement and PasteOptions models")
  void testBlockPlacementAndPasteOptions() {
    BlockPlacement bp = new BlockPlacement(10, 64, 20, "minecraft:stone");
    assertEquals(10, bp.x());
    assertEquals(64, bp.y());
    assertEquals(20, bp.z());
    assertEquals("minecraft:stone", bp.blockState());
    assertTrue(bp.toString().contains("BlockPlacement"));

    BlockPlacement bp2 = new BlockPlacement(10, 64, 20, "minecraft:stone");
    assertEquals(bp, bp2);
    assertEquals(bp.hashCode(), bp2.hashCode());
    assertFalse(bp.equals(null));
    assertFalse(bp.equals("diff"));

    BlockPlacement bpDiffX = new BlockPlacement(11, 64, 20, "minecraft:stone");
    BlockPlacement bpDiffY = new BlockPlacement(10, 65, 20, "minecraft:stone");
    BlockPlacement bpDiffZ = new BlockPlacement(10, 64, 21, "minecraft:stone");
    BlockPlacement bpDiffState = new BlockPlacement(10, 64, 20, "minecraft:dirt");
    assertFalse(bp.equals(bpDiffX));
    assertFalse(bp.equals(bpDiffY));
    assertFalse(bp.equals(bpDiffZ));
    assertFalse(bp.equals(bpDiffState));
    assertThrows(NullPointerException.class, () -> new BlockPlacement(10, 64, 20, null));

    PasteOptions opt = new PasteOptions(PasteAnchor.CENTER, true, false);
    assertTrue(opt.pasteAir());
    assertFalse(opt.claimAware());
    assertEquals(PasteAnchor.CENTER, opt.anchor());
    assertTrue(opt.toString().contains("PasteOptions"));

    PasteOptions optSame = new PasteOptions(PasteAnchor.CENTER, true, false);
    PasteOptions optDiffAnchor = new PasteOptions(PasteAnchor.BOTTOM_CENTER, true, false);
    PasteOptions optDiffAir = new PasteOptions(PasteAnchor.CENTER, false, false);
    PasteOptions optDiffClaim = new PasteOptions(PasteAnchor.CENTER, true, true);

    assertEquals(opt, opt);
    assertEquals(opt, optSame);
    assertEquals(opt.hashCode(), optSame.hashCode());
    assertFalse(opt.equals(null));
    assertFalse(opt.equals("other"));
    assertFalse(opt.equals(optDiffAnchor));
    assertFalse(opt.equals(optDiffAir));
    assertFalse(opt.equals(optDiffClaim));

    assertThrows(NullPointerException.class, () -> new PasteOptions(null, false, false));

    PasteOptions defaultOpt = PasteOptions.defaults();
    assertNotNull(defaultOpt);
    assertEquals(PasteAnchor.BOTTOM_CENTER, defaultOpt.anchor());
    assertFalse(defaultOpt.pasteAir());
    assertTrue(defaultOpt.claimAware());
  }

  @Test
  @DisplayName("DecodedSchematic coordinates, dimensions, bounds, and string representation")
  void testDecodedSchematic() {
    SchematicSource source = new SchematicSource("test-house", java.nio.file.Path.of("test.schem"), "schem");
    assertEquals("test-house", source.name());
    assertEquals("schem", source.formatHint());
    assertEquals(java.nio.file.Path.of("test.schem"), source.path());
    assertTrue(source.toString().contains("SchematicSource"));

    SchematicSource sourceSame = new SchematicSource("test-house", java.nio.file.Path.of("test.schem"), "schem");
    SchematicSource sourceDiffName = new SchematicSource("other", java.nio.file.Path.of("test.schem"), "schem");
    SchematicSource sourceDiffPath = new SchematicSource("test-house", java.nio.file.Path.of("other.schem"), "schem");
    SchematicSource sourceDiffHint = new SchematicSource("test-house", java.nio.file.Path.of("test.schem"), "other");
    SchematicSource sourceNullHint = new SchematicSource("test-house", java.nio.file.Path.of("test.schem"), null);
    assertEquals("", sourceNullHint.formatHint());

    assertEquals(source, source);
    assertEquals(source, sourceSame);
    assertEquals(source.hashCode(), sourceSame.hashCode());
    assertFalse(source.equals(null));
    assertFalse(source.equals("other"));
    assertFalse(source.equals(sourceDiffName));
    assertFalse(source.equals(sourceDiffPath));
    assertFalse(source.equals(sourceDiffHint));

    assertThrows(NullPointerException.class, () -> new SchematicSource(null, java.nio.file.Path.of("test.schem"), "schem"));
    assertThrows(NullPointerException.class, () -> new SchematicSource("test-house", null, "schem"));

    List<String> palette = List.of("minecraft:air", "minecraft:stone");
    int[] indices = new int[2 * 2 * 2]; // 8
    indices[0] = 1;
    int[] offset = new int[]{1, 2, 3};

    DecodedSchematic schematic = new DecodedSchematic(
        source, 2, 2, 2, palette, indices, List.of(), offset);

    assertEquals(source, schematic.source());
    assertEquals(2, schematic.width());
    assertEquals(2, schematic.height());
    assertEquals(2, schematic.length());
    assertEquals(palette, schematic.palette());
    assertEquals(1, schematic.offsetX());
    assertEquals(2, schematic.offsetY());
    assertEquals(3, schematic.offsetZ());
    assertTrue(schematic.blockEntities().isEmpty());

    // In-bounds query
    assertEquals(1, schematic.paletteIndexAt(0, 0, 0));
    assertEquals(0, schematic.paletteIndexAt(1, 0, 0));

    // Out-of-bounds query returns -1
    assertEquals(-1, schematic.paletteIndexAt(-1, 0, 0));
    assertEquals(-1, schematic.paletteIndexAt(0, -1, 0));
    assertEquals(-1, schematic.paletteIndexAt(0, 0, -1));
    assertEquals(-1, schematic.paletteIndexAt(2, 0, 0));
    assertEquals(-1, schematic.paletteIndexAt(0, 2, 0));
    assertEquals(-1, schematic.paletteIndexAt(0, 0, 2));

    assertTrue(schematic.toString().contains("DecodedSchematic"));

    // Validation checks
    assertThrows(NullPointerException.class, () -> new DecodedSchematic(null, 2, 2, 2, palette, indices, List.of(), offset));
    assertThrows(IllegalArgumentException.class, () -> new DecodedSchematic(source, 2, 2, 2, palette, indices, List.of(), new int[]{1, 2}));
    assertThrows(IllegalArgumentException.class, () -> new DecodedSchematic(source, 2, 2, 2, palette, new int[5], List.of(), offset));
  }

  @Test
  @DisplayName("LoadedSchematic default methods")
  void testLoadedSchematicDefaults() {
    SchematicSource src = new SchematicSource("dummy", java.nio.file.Path.of("dummy.schem"), "schem");
    LoadedSchematic loaded = new LoadedSchematic() {
      @Override
      public SchematicSource source() {
        return src;
      }
      @Override
      public int width() { return 5; }
      @Override
      public int height() { return 6; }
      @Override
      public int length() { return 7; }
    };

    assertEquals(src, loaded.source());
    assertEquals(5, loaded.width());
    assertEquals(6, loaded.height());
    assertEquals(7, loaded.length());
    assertTrue(loaded.palette().isEmpty());
    assertEquals(-1, loaded.paletteIndexAt(0, 0, 0));
    assertTrue(loaded.blockEntities().isEmpty());
    assertEquals(0, loaded.offsetX());
    assertEquals(0, loaded.offsetY());
    assertEquals(0, loaded.offsetZ());
  }
}
