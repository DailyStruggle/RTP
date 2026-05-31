package io.github.dailystruggle.rtp.api.schematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
