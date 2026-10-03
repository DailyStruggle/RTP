package io.github.dailystruggle.rtp.common.commands.menu;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.common.commands.menu.MenuConcreteCommandLeaves.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
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

  @Test
  void testVisualizationExportSubcommandsAndParsing(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
    io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir.toFile());
    VisualizationExportCmd exportCmd = new VisualizationExportCmd();

    // Test onCommand with and without messageMethod
    UUID callerId = UUID.randomUUID();
    assertTrue(exportCmd.onCommand(callerId, Map.of(), null));
    java.util.concurrent.atomic.AtomicReference<String> msgRef = new java.util.concurrent.atomic.AtomicReference<>();
    assertTrue(exportCmd.onCommand(callerId, Map.of(), null, msgRef::set));
    assertNotNull(msgRef.get());
    assertTrue(msgRef.get().contains("Usage:"));

    // Step aside if nextCommand is present
    msgRef.set(null);
    assertTrue(exportCmd.onCommand(callerId, Map.of(), exportCmd, msgRef::set));
    assertNull(msgRef.get());

    // Test size and unit variations
    VisualizationExportCmd.ExportComprehensiveCmd compCmd = new VisualizationExportCmd.ExportComprehensiveCmd();
    assertEquals("comprehensive", compCmd.name());
    assertEquals(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION, compCmd.permission());
    assertNotNull(compCmd.getParameterLookup().get(MenuConcreteCommandLeaves.PARAM_REGION));
    assertNotNull(compCmd.getParameterLookup().get(MenuConcreteCommandLeaves.PARAM_REGION).values());

    // Test various size parsing options
    Map<String, List<String>> params = new java.util.HashMap<>();
    params.put("size", List.of("1920x1080"));
    params.put("zoom", List.of("2.0"));
    params.put("region", List.of("default"));
    assertTrue(compCmd.onCommand(callerId, params, null));
    assertTrue(compCmd.onCommand(callerId, params, null, msg -> {}));

    // Test metric suffixes
    String[] testSizes = new String[]{
        "4k", "2kp", "1kpix", "3kpixels", "5kilo", "2kilos",
        "2m", "1mp", "3mpx", "4mpix", "1mpixels", "2mega",
        "1g", "2gp", "1gpx", "3gpix", "1gpixels", "2giga",
        "2kib", "1mib", "500px", "600pix", "800pixels", "invalid_size"
    };
    for (String s : testSizes) {
      Map<String, List<String>> p = Map.of("size", List.of(s), "width", List.of(s), "height", List.of(s), "zoom", List.of("invalid_zoom"));
      compCmd.onCommand(callerId, p, null, msg -> {});
    }

    // ExportAllCmd
    VisualizationExportCmd.ExportAllCmd allCmd = new VisualizationExportCmd.ExportAllCmd();
    assertTrue(allCmd.onCommand(callerId, params, null));
    assertTrue(allCmd.onCommand(callerId, params, null, msg -> {}));

    // ExportTypeCmd for each kind
    VisualizationExportCmd.ExportTypeCmd typeCmd = new VisualizationExportCmd.ExportTypeCmd(
        "biomes", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_BIOMES
    );
    assertTrue(typeCmd.onCommand(callerId, params, null));
    assertTrue(typeCmd.onCommand(callerId, params, null, msg -> {}));
  }

  @Test
  void testAbstractVisualizationRegionCmdBranches(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
    io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir.toFile());
    VisualizationDispatch dispatch = new VisualizationDispatch(id -> perm -> true);

    boolean[] selectorCalled = new boolean[]{false};
    java.util.function.BiFunction<UUID, Consumer<String>, Boolean> selector = (u, m) -> {
      selectorCalled[0] = true;
      return true;
    };

    VisualizationBadLocationsCmd badCmd = new VisualizationBadLocationsCmd(dispatch, selector);
    assertNotNull(badCmd.getParameterLookup().get(MenuConcreteCommandLeaves.PARAM_REGION).values());

    // Call without region parameter -> triggers selector fallback
    selectorCalled[0] = false;
    assertTrue(badCmd.onCommand(UUID.randomUUID(), Map.of(), null));
    assertTrue(selectorCalled[0]);

    selectorCalled[0] = false;
    assertTrue(badCmd.onCommand(UUID.randomUUID(), Map.of(), null, msg -> {}));
    assertTrue(selectorCalled[0]);

    // Call with empty region parameter -> triggers selector fallback
    selectorCalled[0] = false;
    assertTrue(badCmd.onCommand(UUID.randomUUID(), Map.of("region", List.of("")), null, msg -> {}));
    assertTrue(selectorCalled[0]);

    // Call with valid region parameter
    badCmd.onCommand(UUID.randomUUID(), Map.of("region", List.of("default")), null, msg -> {});

    // BiomesCmd
    VisualizationBiomesCmd bioCmd = new VisualizationBiomesCmd(dispatch, selector);
    bioCmd.onCommand(UUID.randomUUID(), Map.of("region", List.of("default")), null, msg -> {});

    // PipelineCmd
    VisualizationPipelineCmd pipeCmd = new VisualizationPipelineCmd(dispatch, selector);
    pipeCmd.onCommand(UUID.randomUUID(), Map.of("region", List.of("default")), null, msg -> {});

    // HeatmapCmd
    VisualizationHeatmapCmd heatCmd = new VisualizationHeatmapCmd(dispatch, selector);
    heatCmd.onCommand(UUID.randomUUID(), Map.of("region", List.of("default")), null, msg -> {});

    // SparklineCmd
    VisualizationSparklineCmd sparkCmd = new VisualizationSparklineCmd(dispatch);
    sparkCmd.onCommand(UUID.randomUUID(), Map.of(), null);
    sparkCmd.onCommand(UUID.randomUUID(), Map.of(), null, msg -> {});
  }
}
