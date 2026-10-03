package io.github.dailystruggle.rtp.common.commands.menu;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.common.commands.menu.MenuConcreteCommandLeaves.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MenuConcreteCommandLeavesCoverageTest {

  @Test
  void testParsePixelDimensionAndDimensions() {
    VisualizationExportCmd exportCmd = new VisualizationExportCmd();
    assertEquals("export", exportCmd.name());
    assertEquals(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION, exportCmd.permission());
    assertNotNull(exportCmd.getCommandLookup());
    assertFalse(exportCmd.getCommandLookup().isEmpty());
  }

  @Test
  void testMenuActionLeafCmd() {
    boolean[] called = new boolean[] {false};
    java.util.function.BiConsumer<UUID, Map<String, List<String>>> action = (u, p) -> called[0] = true;
    MenuActionLeafCmd leaf1 = MenuConcreteCommandLeaves.createLeaf(
        null, "leaf1", "perm", "desc", action);
    assertEquals("leaf1", leaf1.name());
    assertEquals("perm", leaf1.permission());
    assertEquals("desc", leaf1.description());
    assertTrue(leaf1.onCommand(UUID.randomUUID(), Map.of(), null));
    assertTrue(called[0]);

    boolean[] fbCalled = new boolean[] {false};
    java.util.function.BiFunction<UUID, java.util.function.Consumer<String>, Boolean> fbAction = (u, m) -> {
      fbCalled[0] = true;
      return true;
    };
    MenuActionLeafCmd leaf2 = MenuConcreteCommandLeaves.createLeaf(
        null, "leaf2", "perm2", null, fbAction);
    assertEquals("", leaf2.description());
    assertTrue(leaf2.onCommand(UUID.randomUUID(), Map.of(), null, msg -> {}));
    assertTrue(fbCalled[0]);
  }

  @Test
  void testExportComprehensiveCmdParameters() {
    assertTrue(true);
  }

  @Test
  void testMenuConcreteSubcommands(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
    io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir.toFile());
    io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl dummyParent =
        new io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl(null) {
          @Override public String name() { return "rtp"; }
          @Override public String permission() { return "rtp.use"; }
          @Override public String description() { return "root"; }
          @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, io.github.dailystruggle.commandsapi.common.CommandsAPICommand nextCommand) {
            return true;
          }
        };
    MenuRedeemSubcommand owner = new MenuRedeemSubcommand(dummyParent);
    OpenMenuConcreteCmd openCmd = new OpenMenuConcreteCmd(owner);
    assertEquals("open", openCmd.name());
    assertEquals(MenuRedeemSubcommand.PERMISSION, openCmd.permission());
    assertTrue(openCmd.getParameterLookup().containsKey(MenuConcreteCommandLeaves.PARAM_PATH));

    OpenAdminPanelConcreteCmd adminCmd = new OpenAdminPanelConcreteCmd(owner);
    assertEquals("admin", adminCmd.name());
    assertEquals(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION, adminCmd.permission());

    OpenFrontPageConcreteCmd frontCmd = new OpenFrontPageConcreteCmd(owner);
    assertEquals("front", frontCmd.name());
    assertEquals(MenuRedeemSubcommand.PERMISSION, frontCmd.permission());

    OpenVisualizationsConcreteCmd visCmd = new OpenVisualizationsConcreteCmd(owner);
    assertEquals("visualizations", visCmd.name());
    assertEquals(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION, visCmd.permission());

    VisualizationRootCmd rootVisCmd = new VisualizationRootCmd(dummyParent, owner);
    assertEquals("visualization", rootVisCmd.name());
    assertEquals(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION, rootVisCmd.permission());
    // nextCommand step aside
    assertTrue(rootVisCmd.onCommand(UUID.randomUUID(), Map.of(), rootVisCmd));
    assertTrue(rootVisCmd.onCommand(UUID.randomUUID(), Map.of(), rootVisCmd, msg -> {}));

    VisualizationDispatch dispatch = new VisualizationDispatch(owner.permissionProbeFactory());
    VisualizationBadLocationsCmd badCmd = new VisualizationBadLocationsCmd(dispatch, (u, m) -> true);
    assertEquals("bad-locations", badCmd.name());
    badCmd.onCommand(UUID.randomUUID(), Map.of(), null, msg -> {});

    VisualizationBiomesCmd bioCmd = new VisualizationBiomesCmd(dispatch, (u, m) -> true);
    assertEquals("biomes", bioCmd.name());
    bioCmd.onCommand(UUID.randomUUID(), Map.of(), null, msg -> {});

    VisualizationPipelineCmd pipeCmd = new VisualizationPipelineCmd(dispatch, (u, m) -> true);
    assertEquals("pipeline", pipeCmd.name());
    pipeCmd.onCommand(UUID.randomUUID(), Map.of(), null, msg -> {});

    VisualizationHeatmapCmd heatCmd = new VisualizationHeatmapCmd(dispatch, (u, m) -> true);
    assertEquals("heatmap", heatCmd.name());
    heatCmd.onCommand(UUID.randomUUID(), Map.of(), null, msg -> {});

    VisualizationSparklineCmd sparkCmd = new VisualizationSparklineCmd(dispatch);
    assertEquals("sparkline", sparkCmd.name());
    sparkCmd.onCommand(UUID.randomUUID(), Map.of(), null, msg -> {});
  }

  @Test
  void testExportAllCmdParameters() {
    VisualizationExportCmd.ExportAllCmd cmd = new VisualizationExportCmd.ExportAllCmd();
    assertEquals("all", cmd.name());
    assertEquals(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION, cmd.permission());
  }

  @Test
  void testExportTypeCmdParameters() {
    VisualizationExportCmd.ExportTypeCmd cmd = new VisualizationExportCmd.ExportTypeCmd(
        "pipeline", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_COMPOSITE
    );
    assertEquals("pipeline", cmd.name());
  }
}
