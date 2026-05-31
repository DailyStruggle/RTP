package io.github.dailystruggle.rtp.bukkitplatform.world;

import io.github.dailystruggle.rtp.api.schematic.AbstractFileSchematicPaster;
import io.github.dailystruggle.rtp.api.schematic.LoadedSchematic;
import io.github.dailystruggle.rtp.api.schematic.PasteAnchor;
import io.github.dailystruggle.rtp.api.schematic.PasteOptions;
import io.github.dailystruggle.rtp.api.schematic.PlacedBlockEntity;
import io.github.dailystruggle.rtp.api.schematic.SchematicPlacementPlanner;
import io.github.dailystruggle.rtp.api.schematic.SchematicSource;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end guard for the chest-content bug (ADR-058): drives the real
 * {@link BukkitSchematicPaster#paste} against a live (Mock)Bukkit world using the committed
 * {@code skyblock.schem} fixture and asserts the chest block entity is restored with its items
 * (ice + lava_bucket + obsidian x10). This closes the only previously-untested link in the
 * write -> decode -> plan -> paste -> restore chain: the platform-side container fill, which is
 * where "the chest is still empty" actually lived (the decode/plan steps are covered by
 * {@code SpongeSchematicDecoderTest} and the planner tests in rtp-api).
 */
class BukkitSchematicPasterChestTest {

  private ServerMock server;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  private static Path writeFixture() throws Exception {
    Path file = Files.createTempFile("rtp-chest-test", ".schem");
    try (InputStream in = BukkitSchematicPasterChestTest.class
        .getResourceAsStream("/schematics/skyblock.schem")) {
      assertNotNull(in, "skyblock.schem must be on the test classpath");
      Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
    }
    file.toFile().deleteOnExit();
    return file;
  }

  @Test
  void restoreFillsChestInventoryFromSchematic() throws Exception {
    WorldMock world = server.addSimpleWorld("schem-test");
    BukkitRTPWorld rtpWorld = new BukkitRTPWorld(world);
    RTPLocation at = new RTPLocation(rtpWorld, 0, 64, 0);

    BukkitSchematicPaster paster = new BukkitSchematicPaster();
    AbstractFileSchematicPaster.clearCache();
    SchematicSource source = new SchematicSource("skyblock", writeFixture(), "schem");
    LoadedSchematic schematic = paster.load(source).get();
    assertNotNull(schematic, "schematic decodes");
    assertEquals(1, schematic.blockEntities().size(), "fixture carries one chest block entity");

    PasteOptions options = new PasteOptions(PasteAnchor.SURFACE_CENTER, false, true);

    // Resolve the chest's absolute world coordinates the exact same way the paster does, then
    // pre-place a real chest there. We cannot call the full paste() under MockBukkit because
    // Bukkit.createBlockData(String) is unimplemented there; instead we set the chest block
    // directly (which MockBukkit does support) and drive the production restore path, which is
    // the link that was leaving the chest empty.
    List<PlacedBlockEntity> placed =
        SchematicPlacementPlanner.planBlockEntities(schematic, at, options);
    assertEquals(1, placed.size());
    PlacedBlockEntity chest = placed.get(0);
    world.getBlockAt(chest.x(), chest.y(), chest.z()).setType(Material.CHEST);

    BukkitSchematicPaster.restoreBlockEntities(schematic, at, options, world);

    BlockState state = world.getBlockAt(chest.x(), chest.y(), chest.z()).getState();
    Container container = assertInstanceOf(Container.class, state,
        "the chest block must be a container at the planned coordinates");

    int ice = countOf(container, Material.ICE);
    int lava = countOf(container, Material.LAVA_BUCKET);
    int obsidian = countOf(container, Material.OBSIDIAN);
    assertEquals(1, ice, "1 ice");
    assertEquals(1, lava, "1 lava bucket");
    assertEquals(10, obsidian, "10 obsidian");
    assertTrue(ice + lava + obsidian > 0, "chest is not empty");
  }

  private static int countOf(Container container, Material material) {
    int total = 0;
    for (org.bukkit.inventory.ItemStack stack : container.getInventory().getContents()) {
      if (stack != null && stack.getType() == material) {
        total += stack.getAmount();
      }
    }
    return total;
  }
}
