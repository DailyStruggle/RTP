package io.github.dailystruggle.rtp.api.schematic;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the production {@link SpongeSchematicDecoder} + {@link SchematicPlacementPlanner}
 * against the committed {@code skyblock_island.schem} fixture (ADR-058 Amendment 1). Unlike
 * {@link SkyblockIslandFixtureTest} (which uses a throwaway reader to validate the file),
 * this exercises the real platform-neutral decode/plan code each adapter's paster runs.
 */
class SpongeSchematicDecoderTest {

  private static final String AIR = "minecraft:air";
  private static final String DIRT = "minecraft:dirt";
  private static final String GRASS = "minecraft:grass_block[snowy=false]";
  private static final String CHEST = "minecraft:chest[facing=north,type=single,waterlogged=false]";

  private static DecodedSchematic decodeFixture() throws IOException {
    try (InputStream in = SpongeSchematicDecoderTest.class
        .getResourceAsStream("/schematics/skyblock_island.schem")) {
      assertNotNull(in, "fixture must be on the test classpath");
      SchematicSource src = new SchematicSource("skyblock_island",
          Path.of("schematics", "skyblock_island.schem"), "schem");
      return SpongeSchematicDecoder.decode(in, src);
    }
  }

  @Test
  void decoderReportsDimensionsPaletteAndBlockEntities() throws IOException {
    DecodedSchematic schem = decodeFixture();

    assertEquals(8, schem.width());
    assertEquals(10, schem.height());
    assertEquals(7, schem.length());

    // Count blocks via the production paletteIndexAt + palette accessors.
    Map<String, Integer> counts = new HashMap<>();
    for (int y = 0; y < schem.height(); y++) {
      for (int z = 0; z < schem.length(); z++) {
        for (int x = 0; x < schem.width(); x++) {
          int idx = schem.paletteIndexAt(x, y, z);
          counts.merge(schem.palette().get(idx), 1, Integer::sum);
        }
      }
    }
    assertEquals(81, counts.getOrDefault(DIRT, 0), "dirt");
    assertEquals(27, counts.getOrDefault(GRASS, 0), "grass");
    assertEquals(393, counts.getOrDefault(AIR, 0), "air");
    assertEquals(1, counts.getOrDefault(CHEST, 0), "chest");

    // Block entity: one chest carrying 1 ice + 1 lava_bucket + 10 obsidian.
    List<BlockEntityData> bes = schem.blockEntities();
    assertEquals(1, bes.size());
    BlockEntityData chest = bes.get(0);
    assertTrue(chest.id().contains("chest"), () -> "chest id: " + chest.id());
    @SuppressWarnings("unchecked")
    List<Object> items = (List<Object>) chest.nbt().get("Items");
    assertNotNull(items, "chest Items");
    Map<String, Integer> itemCounts = new HashMap<>();
    for (Object o : items) {
      @SuppressWarnings("unchecked")
      Map<String, Object> item = (Map<String, Object>) o;
      itemCounts.merge((String) item.get("id"), ((Number) item.get("Count")).intValue(), Integer::sum);
    }
    assertEquals(1, itemCounts.getOrDefault("minecraft:ice", 0));
    assertEquals(1, itemCounts.getOrDefault("minecraft:lava_bucket", 0));
    assertEquals(10, itemCounts.getOrDefault("minecraft:obsidian", 0));
  }

  @Test
  void plannerSkipsAirAndCentersFootprintUnderArrival() throws IOException {
    DecodedSchematic schem = decodeFixture();
    RTPLocation at = new RTPLocation(null, 100, 64, 200);

    List<BlockPlacement> placements =
        SchematicPlacementPlanner.plan(schem, at, PasteOptions.defaults());

    // pasteAir=false -> 560 cells minus 393 air == 167 solid placements.
    assertEquals(167, placements.size(), "air-skipped placements");
    assertFalse(placements.stream().anyMatch(p -> p.blockState().equals(AIR)),
        "no air placements when pasteAir=false");

    // BOTTOM_CENTER: footprint centered horizontally (centerX=(8-1)/2=3, centerZ=(7-1)/2=3),
    // bottom layer at the arrival Y. The whole box maps into
    // [baseX, baseX+width-1] x [baseY, baseY+height-1] x [baseZ, baseZ+length-1].
    int baseX = 100 - 3;
    int baseZ = 200 - 3;
    assertTrue(placements.stream().allMatch(
            p -> p.x() >= baseX && p.x() <= baseX + 7
                && p.y() >= 64 && p.y() <= 64 + 9
                && p.z() >= baseZ && p.z() <= baseZ + 6),
        "every placement lies within the anchored bounding box");
    int minY = placements.stream().mapToInt(BlockPlacement::y).min().orElseThrow();
    assertEquals(64, minY, "bottom layer sits at arrival Y");

    // The chest's block-state cell must be among the placements (block entity preserved
    // separately, but its block is placed).
    assertTrue(placements.stream().anyMatch(p -> p.blockState().equals(CHEST)),
        "chest block is placed");
  }

  @Test
  void plannerResolvesBlockEntityToAbsoluteCoordsWithItems() throws IOException {
    DecodedSchematic schem = decodeFixture();
    RTPLocation at = new RTPLocation(null, 100, 64, 200);

    List<PlacedBlockEntity> placed =
        SchematicPlacementPlanner.planBlockEntities(schem, at, PasteOptions.defaults());
    assertEquals(1, placed.size(), "one chest block entity");

    PlacedBlockEntity chest = placed.get(0);
    BlockEntityData data = chest.data();
    // BOTTOM_CENTER: baseX=100-3, baseY=64, baseZ=200-3; absolute = base + relative.
    assertEquals(100 - 3 + data.x(), chest.x(), "absolute X = baseX + relative");
    assertEquals(64 + data.y(), chest.y(), "absolute Y = baseY + relative");
    assertEquals(200 - 3 + data.z(), chest.z(), "absolute Z = baseZ + relative");
    // The chest must lie inside the anchored bounding box.
    assertTrue(chest.x() >= 100 - 3 && chest.x() <= 100 - 3 + 7);
    assertTrue(chest.y() >= 64 && chest.y() <= 64 + 9);
    assertTrue(chest.z() >= 200 - 3 && chest.z() <= 200 - 3 + 6);

    // The decoded Items payload is carried through unchanged for the paster to restore.
    @SuppressWarnings("unchecked")
    List<Object> items = (List<Object>) data.nbt().get("Items");
    assertNotNull(items, "chest Items preserved on the placed block entity");
    assertEquals(3, items.size(), "ice + lava_bucket + obsidian stacks");
  }

  @Test
  void decodesLitematicMultiRegionWithNegativeSizeCrossLongBitPackingAndTileEntities()
      throws IOException {
    ByteArrayOutputStream raw = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
      out.writeByte(10); // Root compound
      out.writeUTF("");

      out.writeByte(10); // Regions compound
      out.writeUTF("Regions");

      // Region 1: 6x2x2 = 24 blocks, negative Size (-6, -2, -2) from Position (5, 1, 1) -> min (0, 0, 0)
      // 5 palette entries -> bits = 3 -> 24 * 3 = 72 bits spanning 2 longs (exercises bo + bits > 64).
      out.writeByte(10);
      out.writeUTF("Main");

      out.writeByte(10);
      out.writeUTF("Position");
      writeIntTag(out, "x", 5);
      writeIntTag(out, "y", 1);
      writeIntTag(out, "z", 1);
      out.writeByte(0);

      out.writeByte(10);
      out.writeUTF("Size");
      writeIntTag(out, "x", -6);
      writeIntTag(out, "y", -2);
      writeIntTag(out, "z", -2);
      out.writeByte(0);

      out.writeByte(9); // BlockStatePalette list of compounds
      out.writeUTF("BlockStatePalette");
      out.writeByte(10);
      out.writeInt(5);
      // 0: air (empty Name fallback)
      writeStringTag(out, "Name", "");
      out.writeByte(0);
      // 1: stone (no Properties)
      writeStringTag(out, "Name", "minecraft:stone");
      out.writeByte(0);
      // 2: chest with multiple properties
      writeStringTag(out, "Name", "minecraft:chest");
      out.writeByte(10);
      out.writeUTF("Properties");
      writeStringTag(out, "facing", "north");
      writeStringTag(out, "waterlogged", "false");
      out.writeByte(0);
      out.writeByte(0);
      // 3: dirt with empty Properties compound
      writeStringTag(out, "Name", "minecraft:dirt");
      out.writeByte(10);
      out.writeUTF("Properties");
      out.writeByte(0);
      out.writeByte(0);
      // 4: oak_log
      writeStringTag(out, "Name", "minecraft:oak_log");
      out.writeByte(0);

      // Pack 24 3-bit indices into 2 longs; include an out-of-range index 7 (falls back to 0).
      long low = 0L;
      long high = 0L;
      for (int i = 0; i < 24; i++) {
        long val = (i == 0) ? 1L : (i == 1 ? 2L : (i == 21 ? 4L : (i == 22 ? 7L : 3L)));
        int bitIdx = i * 3;
        int li = bitIdx >>> 6;
        int bo = bitIdx & 63;
        if (li == 0) {
          low |= (val << bo);
          if (bo + 3 > 64) {
            high |= (val >>> (64 - bo));
          }
        } else {
          high |= (val << bo);
        }
      }
      out.writeByte(12); // TAG_Long_Array BlockStates
      out.writeUTF("BlockStates");
      out.writeInt(2);
      out.writeLong(low);
      out.writeLong(high);

      out.writeByte(9); // TileEntities list of 3 compounds
      out.writeUTF("TileEntities");
      out.writeByte(10);
      out.writeInt(3);
      // TE 1: lowercase "id"
      writeIntTag(out, "x", 1);
      writeIntTag(out, "y", 0);
      writeIntTag(out, "z", 0);
      writeStringTag(out, "id", "minecraft:chest");
      writeStringTag(out, "CustomName", "Loot");
      out.writeByte(0);
      // TE 2: uppercase "Id"
      writeIntTag(out, "x", 2);
      writeIntTag(out, "y", 0);
      writeIntTag(out, "z", 0);
      writeStringTag(out, "Id", "minecraft:barrel");
      out.writeByte(0);
      // TE 3: missing id and coordinates
      out.writeByte(0);

      out.writeByte(0); // end Main region

      // Region 2: zero-size region (skipped)
      out.writeByte(10);
      out.writeUTF("Empty");
      out.writeByte(10);
      out.writeUTF("Size");
      writeIntTag(out, "x", 0);
      writeIntTag(out, "y", 1);
      writeIntTag(out, "z", 1);
      out.writeByte(0);
      out.writeByte(0);

      // Region 3: positive Size without Position (defaults to 0,0,0)
      out.writeByte(10);
      out.writeUTF("Sub");
      out.writeByte(10);
      out.writeUTF("Size");
      writeIntTag(out, "x", 1);
      writeIntTag(out, "y", 1);
      writeIntTag(out, "z", 1);
      out.writeByte(0);
      out.writeByte(9);
      out.writeUTF("BlockStatePalette");
      out.writeByte(10);
      out.writeInt(1);
      writeStringTag(out, "Name", "minecraft:stone");
      out.writeByte(0);
      out.writeByte(12);
      out.writeUTF("BlockStates");
      out.writeInt(1);
      out.writeLong(0L);
      out.writeByte(0);

      out.writeByte(0); // end Regions
      out.writeByte(0); // end Root
    }

    SchematicSource src = new SchematicSource("island", Path.of("island.litematic"), "litematic");
    DecodedSchematic decoded =
        SpongeSchematicDecoder.decode(new ByteArrayInputStream(raw.toByteArray()), src);

    assertEquals(6, decoded.width());
    assertEquals(2, decoded.height());
    assertEquals(2, decoded.length());
    assertTrue(decoded.palette().contains("minecraft:stone"));
    assertTrue(decoded.palette().contains("minecraft:chest[facing=north,waterlogged=false]"));
    assertTrue(decoded.palette().contains("minecraft:oak_log"));
    assertEquals(3, decoded.blockEntities().size());
    assertEquals("minecraft:chest", decoded.blockEntities().get(0).id());
    assertEquals("minecraft:barrel", decoded.blockEntities().get(1).id());
    assertEquals("minecraft:unknown", decoded.blockEntities().get(2).id());
  }

  @Test
  void litematicDecoderRejectsMalformedRegions() throws IOException {
    SchematicSource src = new SchematicSource("bad", Path.of("bad.litematic"), "litematic");

    // 1. Missing Size compound
    byte[] missingSize = buildSingleRegionLitematic(out -> {});
    assertThrows(
        IOException.class,
        () -> SpongeSchematicDecoder.decode(new ByteArrayInputStream(missingSize), src));

    // 2. Empty BlockStatePalette
    byte[] emptyPalette =
        buildSingleRegionLitematic(
            out -> {
              out.writeByte(10);
              out.writeUTF("Size");
              writeIntTag(out, "x", 1);
              writeIntTag(out, "y", 1);
              writeIntTag(out, "z", 1);
              out.writeByte(0);
            });
    assertThrows(
        IOException.class,
        () -> SpongeSchematicDecoder.decode(new ByteArrayInputStream(emptyPalette), src));

    // 3. Missing BlockStates long[]
    byte[] missingBlockStates =
        buildSingleRegionLitematic(
            out -> {
              out.writeByte(10);
              out.writeUTF("Size");
              writeIntTag(out, "x", 1);
              writeIntTag(out, "y", 1);
              writeIntTag(out, "z", 1);
              out.writeByte(0);
              out.writeByte(9);
              out.writeUTF("BlockStatePalette");
              out.writeByte(10);
              out.writeInt(1);
              writeStringTag(out, "Name", "minecraft:air");
              out.writeByte(0);
            });
    assertThrows(
        IOException.class,
        () -> SpongeSchematicDecoder.decode(new ByteArrayInputStream(missingBlockStates), src));

    // 4. Only zero-size regions -> no non-empty regions
    byte[] allZeroRegions =
        buildSingleRegionLitematic(
            out -> {
              out.writeByte(10);
              out.writeUTF("Size");
              writeIntTag(out, "x", 0);
              writeIntTag(out, "y", 1);
              writeIntTag(out, "z", 1);
              out.writeByte(0);
            });
    assertThrows(
        IOException.class,
        () -> SpongeSchematicDecoder.decode(new ByteArrayInputStream(allZeroRegions), src));
  }

  @FunctionalInterface
  private interface NbtWriter {
    void write(DataOutputStream out) throws IOException;
  }

  private static byte[] buildSingleRegionLitematic(NbtWriter regionBody) throws IOException {
    ByteArrayOutputStream raw = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
      out.writeByte(10);
      out.writeUTF("");
      out.writeByte(10);
      out.writeUTF("Regions");
      out.writeByte(10);
      out.writeUTF("R1");
      regionBody.write(out);
      out.writeByte(0);
      out.writeByte(0);
      out.writeByte(0);
    }
    return raw.toByteArray();
  }

  private static void writeIntTag(DataOutputStream out, String name, int val) throws IOException {
    out.writeByte(3);
    out.writeUTF(name);
    out.writeInt(val);
  }

  private static void writeStringTag(DataOutputStream out, String name, String val)
      throws IOException {
    out.writeByte(8);
    out.writeUTF(name);
    out.writeUTF(val);
  }
}
