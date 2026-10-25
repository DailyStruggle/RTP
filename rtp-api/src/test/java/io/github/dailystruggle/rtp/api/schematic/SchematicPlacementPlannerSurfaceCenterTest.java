package io.github.dailystruggle.rtp.api.schematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.platform.BlockDelta;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the ADR-058 {@link PasteAnchor#SURFACE_CENTER} vertical drop in
 * {@link SchematicPlacementPlanner}: the schematic is shifted down so the lowest standable
 * floor in its center column lands one block below the arrival Y (player stands on it), and a
 * roof above an interior floor never pushes the player onto the roof.
 */
class SchematicPlacementPlannerSurfaceCenterTest {

  private static final String AIR = "minecraft:air";
  private static final String DIRT = "minecraft:dirt";
  private static final String GRASS = "minecraft:grass_block[snowy=false]";
  private static final String STONE = "minecraft:stone";

  /** Single-column schematic; {@code layers[y]} is the block state (null = empty) at (0,y,0). */
  private static LoadedSchematic column(String... layers) {
    List<String> palette = List.of(AIR, DIRT, GRASS, STONE);
    Map<String, Integer> idx = Map.of(AIR, 0, DIRT, 1, GRASS, 2, STONE, 3);
    return new LoadedSchematic() {
      @Override public SchematicSource source() {
        return new SchematicSource("col", java.nio.file.Path.of("col.schem"), "schem");
      }
      @Override public int width() { return 1; }
      @Override public int height() { return layers.length; }
      @Override public int length() { return 1; }
      @Override public List<String> palette() { return palette; }
      @Override public int paletteIndexAt(int x, int y, int z) {
        if (x != 0 || z != 0 || y < 0 || y >= layers.length || layers[y] == null) return -1;
        return idx.get(layers[y]);
      }
    };
  }

  @Test
  @DisplayName("open-top island: player stands on the grass surface")
  void openTopSurface() {
    // y: 0 dirt, 1 grass, 2 air, 3 air -> lowest standable floor is the grass at y=1.
    LoadedSchematic schem = column(DIRT, GRASS, AIR, AIR);
    RTPLocation at = new RTPLocation(null, 0, 64, 0);
    List<BlockPlacement> p = SchematicPlacementPlanner.plan(
        schem, at, new PasteOptions(PasteAnchor.SURFACE_CENTER, false, true));

    // floorY=1 -> baseY = 64 - 1 - 1 = 62. grass(y=1) lands at 63 (one below feet); dirt at 62.
    int grassY = p.stream().filter(b -> b.blockState().equals(GRASS))
        .mapToInt(BlockPlacement::y).findFirst().orElseThrow();
    assertEquals(63, grassY, "grass surface sits one block below the arrival Y (64)");
    int dirtY = p.stream().filter(b -> b.blockState().equals(DIRT))
        .mapToInt(BlockPlacement::y).findFirst().orElseThrow();
    assertEquals(62, dirtY);
  }

  @Test
  @DisplayName("roofed structure: player stands on the interior floor, not the roof")
  void roofedInteriorFloor() {
    // y: 0 stone floor, 1 air, 2 air, 3 stone roof. Lowest standable floor is y=0 (two air above),
    // NOT the roof at y=3. Player must stand inside on the stone floor.
    LoadedSchematic schem = column(STONE, AIR, AIR, STONE);
    RTPLocation at = new RTPLocation(null, 0, 100, 0);
    List<BlockPlacement> p = SchematicPlacementPlanner.plan(
        schem, at, new PasteOptions(PasteAnchor.SURFACE_CENTER, false, true));

    // floorY=0 -> baseY = 100 - 0 - 1 = 99. floor(y=0) at 99, roof(y=3) at 102 (above the player).
    List<Integer> stoneY = p.stream().filter(b -> b.blockState().equals(STONE))
        .map(BlockPlacement::y).sorted().toList();
    assertEquals(List.of(99, 102), stoneY, "interior floor below feet, roof well above the head");
    assertTrue(stoneY.get(0) < 100, "interior floor is below the arrival Y, player is inside");
  }

  @Test
  @DisplayName("coordinate-based plan, planDeltas, and planBlockEntities match RTPLocation overloads across anchors")
  void coordinateOverloadsMatchRtpLocationAcrossAnchors() {
    BlockEntityData chestEntity = new BlockEntityData(1, 1, 1, "minecraft:chest", Map.of());
    LoadedSchematic schem = new LoadedSchematic() {
      @Override public SchematicSource source() {
        return new SchematicSource("box", java.nio.file.Path.of("box.schem"), "schem");
      }
      @Override public int width() { return 3; }
      @Override public int height() { return 4; }
      @Override public int length() { return 3; }
      @Override public int offsetX() { return -1; }
      @Override public int offsetY() { return 2; }
      @Override public int offsetZ() { return -1; }
      @Override public List<String> palette() { return List.of(AIR, DIRT, STONE); }
      @Override public int paletteIndexAt(int x, int y, int z) {
        return (y == 0) ? 2 : (y == 1 && x == 1 && z == 1 ? 1 : 0);
      }
      @Override public List<BlockEntityData> blockEntities() {
        return List.of(chestEntity);
      }
    };

    RTPLocation at = new RTPLocation(null, 12, 70, -8);
    for (PasteAnchor anchor : PasteAnchor.values()) {
      PasteOptions opts = new PasteOptions(anchor, false, true);
      List<BlockPlacement> byLoc = SchematicPlacementPlanner.plan(schem, at, opts);
      List<BlockPlacement> byCoords =
          SchematicPlacementPlanner.plan(schem, 12, 70, -8, opts);
      assertEquals(byLoc, byCoords, "plan parity for anchor " + anchor);

      List<BlockDelta> expectedDeltas =
          byCoords.stream().map(BlockPlacement::toBlockDelta).toList();
      assertEquals(
          expectedDeltas,
          SchematicPlacementPlanner.planDeltas(schem, 12, 70, -8, opts),
          "planDeltas coordinate parity for anchor " + anchor);
      assertEquals(
          expectedDeltas,
          SchematicPlacementPlanner.planDeltas(schem, at, opts),
          "planDeltas location parity for anchor " + anchor);

      List<PlacedBlockEntity> entitiesByLoc =
          SchematicPlacementPlanner.planBlockEntities(schem, at, opts);
      List<PlacedBlockEntity> entitiesByCoords =
          SchematicPlacementPlanner.planBlockEntities(schem, 12, 70, -8, opts);
      assertEquals(entitiesByLoc.size(), entitiesByCoords.size());
      for (int i = 0; i < entitiesByLoc.size(); i++) {
        PlacedBlockEntity a = entitiesByLoc.get(i);
        PlacedBlockEntity b = entitiesByCoords.get(i);
        assertEquals(a.x(), b.x());
        assertEquals(a.y(), b.y());
        assertEquals(a.z(), b.z());
        assertEquals(a.data(), b.data());
      }
    }

    // Also exercise pasteAir=true, out-of-bounds palette indices, null palette entry, empty entities, and no standable floor
    LoadedSchematic edgeSchem = new LoadedSchematic() {
      @Override public SchematicSource source() {
        return new SchematicSource("edge", java.nio.file.Path.of("edge.schem"), "schem");
      }
      @Override public int width() { return 4; }
      @Override public int height() { return 1; }
      @Override public int length() { return 1; }
      @Override public List<String> palette() {
        return java.util.Arrays.asList(AIR, null, "invalid::[state");
      }
      @Override public int paletteIndexAt(int x, int y, int z) {
        if (x == 0) return -1;
        if (x == 1) return 99;
        if (x == 2) return 1;
        return 2;
      }
    };
    List<BlockPlacement> edgePlacements =
        SchematicPlacementPlanner.plan(
            edgeSchem, 0, 64, 0, new PasteOptions(PasteAnchor.SURFACE_CENTER, true, true));
    assertEquals(1, edgePlacements.size());
    assertTrue(
        SchematicPlacementPlanner.planBlockEntities(
                edgeSchem, 0, 64, 0, PasteOptions.defaults())
            .isEmpty());
  }
}
