package io.github.dailystruggle.rtp.api.schematic;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
