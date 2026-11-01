package io.github.dailystruggle.rtp.api.schematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.platform.BlockDelta;
import io.github.dailystruggle.server.api.ServerAccessor;
import io.github.dailystruggle.server.api.ServerAccessors;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the leafrtp-ADR-041 {@link PasteAnchor#SURFACE_CENTER} vertical drop in
 * {@link SchematicPlacementPlanner} and the {@link SchematicFileLoader} decode cache.
 */
class SchematicPlacementPlannerSurfaceCenterTest {

  private static final String AIR = "minecraft:air";
  private static final String DIRT = "minecraft:dirt";
  private static final String GRASS = "minecraft:grass_block[snowy=false]";
  private static final String STONE = "minecraft:stone";

  private record SimpleCoord(int x, int y, int z) implements SchematicCoordinate {}

  /** Single-column schematic; {@code layers[y]} is the block state (null = empty) at (0,y,0). */
  private static LoadedSchematic column(String... layers) {
    List<String> palette = List.of(AIR, DIRT, GRASS, STONE);
    Map<String, Integer> idx = Map.of(AIR, 0, DIRT, 1, GRASS, 2, STONE, 3);
    return new LoadedSchematic() {
      @Override public SchematicSource source() {
        return new SchematicSource("col", Path.of("col.schem"), "schem");
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
    LoadedSchematic schem = column(DIRT, GRASS, AIR, AIR);
    SchematicCoordinate at = new SimpleCoord(0, 64, 0);
    List<BlockPlacement> p = SchematicPlacementPlanner.plan(
        schem, at, new PasteOptions(PasteAnchor.SURFACE_CENTER, false, true));

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
    LoadedSchematic schem = column(STONE, AIR, AIR, STONE);
    SchematicCoordinate at = new SimpleCoord(0, 100, 0);
    List<BlockPlacement> p = SchematicPlacementPlanner.plan(
        schem, at, new PasteOptions(PasteAnchor.SURFACE_CENTER, false, true));

    List<Integer> stoneY = p.stream().filter(b -> b.blockState().equals(STONE))
        .map(BlockPlacement::y).sorted().toList();
    assertEquals(List.of(99, 102), stoneY, "interior floor below feet, roof well above the head");
    assertTrue(stoneY.get(0) < 100, "interior floor is below the arrival Y, player is inside");
  }

  @Test
  @DisplayName("coordinate-based plan, planDeltas, and planBlockEntities match SchematicCoordinate overloads across anchors")
  void coordinateOverloadsMatchSchematicCoordinateAcrossAnchors() {
    BlockEntityData chestEntity = new BlockEntityData(1, 1, 1, "minecraft:chest", Map.of());
    LoadedSchematic schem = new LoadedSchematic() {
      @Override public SchematicSource source() {
        return new SchematicSource("box", Path.of("box.schem"), "schem");
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

    SchematicCoordinate at = new SimpleCoord(12, 70, -8);
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

    LoadedSchematic edgeSchem = new LoadedSchematic() {
      @Override public SchematicSource source() {
        return new SchematicSource("edge", Path.of("edge.schem"), "schem");
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

  @Test
  @DisplayName("SchematicFileLoader caches decoded schematics, checks format hints, and audits failures")
  void schematicFileLoaderCacheSupportsAndLogging() throws Exception {
    SchematicFileLoader.clearCache();
    Path file = Files.createTempFile("schem-loader-test", ".schem");
    try (InputStream in = SchematicPlacementPlannerSurfaceCenterTest.class
        .getResourceAsStream("/schematics/skyblock_island.schem")) {
      assertNotNull(in);
      Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
    }
    file.toFile().deleteOnExit();

    SchematicSource src = new SchematicSource("skyblock", file, "schem");
    assertTrue(SchematicFileLoader.supports(src));
    assertTrue(SchematicFileLoader.supports(new SchematicSource("skyblock", file, "schematic")));
    assertTrue(SchematicFileLoader.supports(new SchematicSource("skyblock", file, "litematic")));
    assertTrue(SchematicFileLoader.supports(new SchematicSource("skyblock", file, "")));
    assertFalse(SchematicFileLoader.supports(new SchematicSource("skyblock", file, "nbt")));
    assertFalse(SchematicFileLoader.supports(null));
    assertFalse(SchematicFileLoader.supports(new SchematicSource("missing", Path.of("no_such_file.schem"), "schem")));

    assertNull(SchematicFileLoader.load(null).get());
    LoadedSchematic first = SchematicFileLoader.load(src).get();
    LoadedSchematic second = SchematicFileLoader.load(src).get();
    assertNotNull(first);
    assertSame(first, second);

    SchematicFileLoader.clearCache();
    LoadedSchematic afterClear = SchematicFileLoader.load(src).get();
    assertNotNull(afterClear);
    assertNotSame(first, afterClear);

    // Verify failure logging via ServerAccessors and fallback supplier
    ServerAccessor prev = ServerAccessors.get();
    try {
      List<String> logs = new ArrayList<>();
      ServerAccessor testAccessor = new ServerAccessor() {
        @Override public String getServerVersion() { return "test"; }
        @Override public String getPlatform() { return "paper"; }
        @Override public Integer getServerIntVersion() { return 21; }
        @Override public void log(Level level, String message) { logs.add(level + ":" + message); }
        @Override public void log(Level level, String message, Throwable t) { logs.add(level + ":" + message); }
      };
      ServerAccessors.set(testAccessor);
      assertNull(SchematicFileLoader.load(new SchematicSource("bad", Path.of("missing_schem.schem"), "schem")).get());
      assertEquals(1, logs.size());
      SchematicFileLoader.logIfPossible(Level.INFO, "hello", null);
      assertEquals(2, logs.size());

      ServerAccessors.set(null);
      assertNull(SchematicFileLoader.load(
          new SchematicSource("bad2", Path.of("missing_schem2.schem"), "schem"),
          () -> testAccessor).get());
      assertEquals(3, logs.size());
    } finally {
      ServerAccessors.set(prev);
    }
  }
}
