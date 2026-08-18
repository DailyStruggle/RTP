package io.github.dailystruggle.rtp.common.tasks.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.schematic.AbstractFileSchematicPaster;
import io.github.dailystruggle.rtp.api.schematic.BlockPlacement;
import io.github.dailystruggle.rtp.api.schematic.LoadedSchematic;
import io.github.dailystruggle.rtp.api.schematic.PasteOptions;
import io.github.dailystruggle.rtp.api.schematic.PasteResult;
import io.github.dailystruggle.rtp.api.schematic.SchematicPlacementPlanner;
import io.github.dailystruggle.rtp.api.schematic.SchematicSource;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.prefab.PrefabSchematicInstaller;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.Skyblock;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@code rtp-core} region-schematic wiring (ADR-058).
 *
 * <p>Tests file-presence source resolution and bottom-center anchored schematic pasting.
 */
class RegionSchematicServiceTest {

  private static final String AIR = "minecraft:air";
  private static final String DIRT = "minecraft:dirt";
  private static final String GRASS = "minecraft:grass_block[snowy=false]";
  private static final String CHEST = "minecraft:chest[facing=north,type=single,waterlogged=false]";

  /** Records the planned placements so the pipeline's paste step can be asserted in a unit test. */
  private static final class RecordingPaster extends AbstractFileSchematicPaster {
    List<BlockPlacement> placements;

    @Override
    public PasteResult paste(LoadedSchematic schematic, RTPLocation at, PasteOptions options) {
      placements = SchematicPlacementPlanner.plan(schematic, at, options);
      return placements.isEmpty() ? PasteResult.PASTE_ERROR : PasteResult.PASTED;
    }
  }

  private File pluginDir;

  @BeforeEach
  void setUp() throws Exception {
    pluginDir = Files.createTempDirectory("rtp-adr058").toFile();
    RTP.serverAccessor = new MockRTPServerAccessor(pluginDir);
  }

  @AfterEach
  void tearDown() {
    RTP.serverAccessor = null;
  }

  private void installFixture(String regionName) throws Exception {
    Path dir = pluginDir.toPath().resolve(RegionSchematicService.SCHEMATICS_DIRNAME);
    Files.createDirectories(dir);
    try (InputStream in = getClass().getResourceAsStream("/schematics/skyblock_island.schem")) {
      assertNotNull(in, "fixture must be on the test classpath");
      Files.copy(in, dir.resolve(regionName + ".schem"));
    }
  }

  @Test
  @DisplayName("no schematics directory / no file -> null source (default platform path)")
  void absentFileResolvesNull() {
    assertNull(RegionSchematicService.resolveSource("hub"),
        "no schematics dir means no source");
    assertNull(RegionSchematicService.resolveSource(null));
    assertNull(RegionSchematicService.resolveSource(""));
  }

  @Test
  @DisplayName("presence of schematics/<region>.schem is the knob -> resolved source")
  void presentFileResolvesSource() throws Exception {
    installFixture("hub");
    SchematicSource src = RegionSchematicService.resolveSource("hub");
    assertNotNull(src, "a file named after the region must resolve");
    assertEquals("hub", src.name());
    assertEquals("schem", src.formatHint());
    assertTrue(Files.isRegularFile(src.path()));
    // A different region with no file stays on the default platform path.
    assertNull(RegionSchematicService.resolveSource("wild"));
  }

  @Test
  @DisplayName("resolved island loads + pastes to a BOTTOM_CENTER footprint under the arrival")
  void loadAndPasteAgainstIsland() throws Exception {
    installFixture("hub");
    SchematicSource src = RegionSchematicService.resolveSource("hub");
    assertNotNull(src);

    RecordingPaster paster = new RecordingPaster();
    assertTrue(paster.supports(src), "Sponge .schem with an existing file is supported");

    LoadedSchematic loaded = paster.load(src).getNow(null);
    assertNotNull(loaded, "decode of the committed fixture must succeed");
    assertEquals(8, loaded.width());
    assertEquals(10, loaded.height());
    assertEquals(7, loaded.length());

    RTPLocation at = new RTPLocation(null, 100, 64, 200);
    PasteResult result = paster.paste(loaded, at, PasteOptions.defaults());
    assertSame(PasteResult.PASTED, result);

    List<BlockPlacement> placements = paster.placements;
    // 560 cells minus 393 air == 167 solid placements (air-skipped by default).
    assertEquals(167, placements.size(), "air-skipped placements");
    assertFalse(placements.stream().anyMatch(p -> p.blockState().equals(AIR)));
    assertEquals(81, placements.stream().filter(p -> p.blockState().equals(DIRT)).count(), "dirt");
    assertEquals(27, placements.stream().filter(p -> p.blockState().equals(GRASS)).count(), "grass");
    assertTrue(placements.stream().anyMatch(p -> p.blockState().equals(CHEST)), "chest block");

    // BOTTOM_CENTER: centerX=(8-1)/2=3, centerZ=(7-1)/2=3; bottom layer at the arrival Y.
    int baseX = 100 - 3;
    int baseZ = 200 - 3;
    assertTrue(placements.stream().allMatch(
            p -> p.x() >= baseX && p.x() <= baseX + 7
                && p.y() >= 64 && p.y() <= 64 + 9
                && p.z() >= baseZ && p.z() <= baseZ + 6),
        "every placement lies within the anchored bounding box");
    assertEquals(64, placements.stream().mapToInt(BlockPlacement::y).min().orElseThrow(),
        "bottom layer sits at the arrival Y (player stands on top, before any vert overwrite)");
  }

  @Test
  @DisplayName("Skyblock prefab bakes the island in and core resolves it for the default region")
  void skyblockPrefabInstallIsResolvedByRegionName() throws Exception {
    // Before applying the prefab the default region has no arrival schematic.
    assertNull(RegionSchematicService.resolveSource("default"),
        "no schematic before the prefab is applied");

    // Applying the Skyblock prefab extracts the bundled /schematics/skyblock.schem.
    PrefabSchematicInstaller.Result r =
        PrefabSchematicInstaller.install(pluginDir, Skyblock.INSTANCE);
    assertEquals(List.of("skyblock"), r.installed(), "the bundled island must be installed");

    // It lands under the *region* name (default.schem), so the file-presence knob in
    // RegionSchematicService now resolves for the "default" region the prefab overlays.
    SchematicSource src = RegionSchematicService.resolveSource("default");
    assertNotNull(src, "Skyblock prefab must make the default region resolve a schematic");
    assertEquals("default", src.name());
    assertTrue(Files.isRegularFile(src.path()));

    // And the resolved file really is the island (decodes to the known dims).
    LoadedSchematic loaded = new RecordingPaster().load(src).getNow(null);
    assertNotNull(loaded, "installed schematic must decode");
    assertEquals(8, loaded.width());
    assertEquals(10, loaded.height());
    assertEquals(7, loaded.length());
  }
}
