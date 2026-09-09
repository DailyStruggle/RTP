package io.github.dailystruggle.rtp.common.visualization;

import io.github.dailystruggle.mapsapi.image.ImageMapCanvas;
import io.github.dailystruggle.mapsapi.model.CompositeRegionModel;
import io.github.dailystruggle.mapsapi.render.CompositeRegionRenderer;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CircleOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Empirical unit test validating ADR-089 composite rendering directly against an RTP MemoryShape.
 */
public class CompositeRegionModelBuilderTest {

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir.toFile());
  }

  @Test
  @DisplayName("ADR-089: CompositeRegionModelBuilder builds and exports composite map from MemoryShape")
  public void testCompositeRegionModelBuilding() throws IOException {
    CircleOptimizedDualLayer shape = new CircleOptimizedDualLayer("COMPOSITE_TEST_SHAPE", 32);
    shape.set(GenericMemoryShapeParams.radius, 128L);
    shape.set(GenericMemoryShapeParams.centerRadius, 16L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);

    MockRTPWorld world = new MockRTPWorld("composite_world");
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "test_region", world, shape, vert,
        false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false
    );
    Region region = new Region("test_region", settings);

    List<CompositeRegionModel.Marker> markers = new ArrayList<>();
    markers.add(new CompositeRegionModel.Marker(64, 40, 0x2ECC71, 0x000000, 1));
    markers.add(new CompositeRegionModel.Marker(40, 70, 0x3498DB, 0x000000, 1));
    markers.add(new CompositeRegionModel.Marker(80, 80, 0x9B59B6, 0x000000, 1));

    List<CompositeRegionModel.VectorLine> lines = new ArrayList<>();
    lines.add(new CompositeRegionModel.VectorLine(64, 40, 40, 70, 0xF1C40F));
    lines.add(new CompositeRegionModel.VectorLine(40, 70, 80, 80, 0xF1C40F));

    CompositeRegionModel.QueueGauge l1 = new CompositeRegionModel.QueueGauge("L1 Hot", 15, 20, 0x2ECC71);
    CompositeRegionModel.QueueGauge l2 = new CompositeRegionModel.QueueGauge("L2 Cold", 45, 60, 0x3498DB);
    CompositeRegionModel.QueueGauge l3 = new CompositeRegionModel.QueueGauge("L3 Backlog", 120, 200, 0x9B59B6);

    CompositeRegionModel model = CompositeRegionModelBuilder.build(
        region, 128, 128, markers, lines, l1, l2, l3
    );

    assertNotNull(model);
    assertEquals("test_region", model.regionName());
    assertEquals(128, model.width());
    assertEquals(128, model.height());

    // Render onto ImageMapCanvas
    ImageMapCanvas canvas = new ImageMapCanvas(512, 512);
    CompositeRegionRenderer.INSTANCE.render(canvas, model);
    canvas.commit();

    File outDir = new File("build/reports/visualizations");
    outDir.mkdirs();
    File outFile = new File(outDir, "composite_builder_test_output.png");
    canvas.writeToFile(outFile, "png");

    // Also copy to project root and testServer debug directory for easy viewing
    canvas.writeToFile(new File("example_composite_overlay_512.png"), "png");
    File testServerDebug = new File("C:\\GameServers\\Minecraft\\testServer\\RTP-Folia\\26.1\\plugins\\RTP\\database\\regionData\\debug");
    if (testServerDebug.exists()) {
      canvas.writeToFile(new File(testServerDebug, "example_composite_overlay_512.png"), "png");
    }

    assertTrue(outFile.exists() && outFile.length() > 500, "Rendered output file must exist on disk");
    System.out.println("[DEBUG_LOG] Successfully rendered composite region model to " + outFile.getAbsolutePath());
  }

  @Test
  @DisplayName("ADR-089: Generate high-resolution rich terrain composite map with real-world scale and queue bars")
  public void testGenerateRichWorldCompositeMap() throws IOException {
    int w = 256;
    int h = 256;
    int[] biomes = new int[w * h];
    boolean[] hazards = new boolean[w * h];
    boolean[] inside = new boolean[w * h];

    // Build rich realistic terrain mask:
    // Oceans, River channels, Plains, Forests, Snowy peaks, Deserts
    java.util.Random rand = new java.util.Random(42L);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int idx = y * w + x;
        double dx = x - 128.0;
        double dy = y - 128.0;
        double dist = Math.sqrt(dx * dx + dy * dy);

        // Circular domain with center hole
        if (dist <= 120.0 && dist >= 16.0) {
          inside[idx] = true;

          // Perlin-like biome noise distribution
          double nx = x * 0.04;
          double ny = y * 0.04;
          double elevation = Math.sin(nx) + Math.cos(ny) + 0.5 * Math.sin(nx * 2 + ny * 2);

          if (elevation < -0.3) {
            // Ocean / River (Hazard)
            biomes[idx] = 0x2980B9; // Blue ocean
            hazards[idx] = true;
          } else if (elevation < 0.2) {
            // Plains
            biomes[idx] = 0x27AE60; // Green plains
          } else if (elevation < 0.7) {
            // Forest / Taiga
            biomes[idx] = 0x1E824C; // Deep forest green
          } else if (elevation < 1.1) {
            // Desert / Badlands
            biomes[idx] = 0xE67E22; // Warm desert sand
          } else {
            // Mountain peak
            biomes[idx] = 0xBDC3C7; // Stone / snow
          }
        }
      }
    }

    // Populate candidate points for each queue tier
    List<CompositeRegionModel.Marker> markers = new ArrayList<>();
    List<CompositeRegionModel.VectorLine> lines = new ArrayList<>();

    // L1 Hot Queue: 15 ready chunks (Emerald)
    int[][] l1Coords = {
        {100, 80}, {140, 60}, {160, 110}, {80, 140}, {120, 180},
        {170, 150}, {70, 90}, {190, 100}, {110, 150}, {150, 170},
        {85, 175}, {135, 100}, {95, 115}, {165, 80}, {130, 140}
    };
    for (int[] pt : l1Coords) {
      markers.add(new CompositeRegionModel.Marker(pt[0], pt[1], 0x2ECC71, 0x000000, 2));
    }

    // L2 Cold Queue: 20 pre-verified chunks (Cyan)
    int[][] l2Coords = {
        {60, 60}, {180, 70}, {70, 160}, {170, 170}, {110, 50},
        {50, 110}, {190, 130}, {130, 200}, {90, 65}, {160, 55},
        {65, 125}, {175, 145}, {125, 45}, {75, 190}, {185, 90},
        {105, 185}, {145, 40}, {55, 140}, {195, 115}, {135, 210}
    };
    for (int[] pt : l2Coords) {
      markers.add(new CompositeRegionModel.Marker(pt[0], pt[1], 0x3498DB, 0x000000, 1));
    }

    // L3 Backlog Queue: 12 screened macro-bins (Purple)
    int[][] l3Coords = {
        {50, 80}, {190, 80}, {80, 50}, {160, 190}, {70, 70},
        {180, 180}, {60, 150}, {170, 60}, {110, 210}, {140, 45},
        {80, 195}, {150, 200}
    };
    for (int[] pt : l3Coords) {
      markers.add(new CompositeRegionModel.Marker(pt[0], pt[1], 0x9B59B6, 0x000000, 1));
    }

    // Recent teleports & Dyadic arrival trajectory trail (Gold / Orange)
    int[][] trajectory = {
        {100, 80}, {170, 170}, {70, 160}, {160, 60}, {120, 180},
        {60, 60}, {190, 100}, {85, 175}, {140, 45}, {95, 115}
    };
    for (int i = 0; i < trajectory.length; i++) {
      int[] pt = trajectory[i];
      markers.add(new CompositeRegionModel.Marker(pt[0], pt[1], 0xF1C40F, 0x000000, 3));
      if (i > 0) {
        int[] prev = trajectory[i - 1];
        lines.add(new CompositeRegionModel.VectorLine(prev[0], prev[1], pt[0], pt[1], 0xF39C12));
      }
    }

    // Queue gauges
    CompositeRegionModel.QueueGauge l1 = new CompositeRegionModel.QueueGauge("L1 Hot", 15, 20, 0x2ECC71);
    CompositeRegionModel.QueueGauge l2 = new CompositeRegionModel.QueueGauge("L2 Cold", 48, 60, 0x3498DB);
    CompositeRegionModel.QueueGauge l3 = new CompositeRegionModel.QueueGauge("L3 Backlog", 185, 250, 0x9B59B6);

    CompositeRegionModel model = new CompositeRegionModel(
        "overworld_main", w, h, biomes, hazards, inside, markers, lines, l1, l2, l3
    );

    // Render 1: Vanilla Map scale (128x128)
    ImageMapCanvas mapCanvas = new ImageMapCanvas(128, 128);
    CompositeRegionRenderer.INSTANCE.render(mapCanvas, model);
    mapCanvas.writeToFile(new File("example_composite_map_128.png"), "png");

    // Render 2: High-Resolution 1024x1024 Export
    ImageMapCanvas exportCanvas = new ImageMapCanvas(1024, 1024);
    CompositeRegionRenderer.INSTANCE.render(exportCanvas, model);
    File exportFile = new File("example_composite_overlay_1024.png");
    exportCanvas.writeToFile(exportFile, "png");

    // Also copy to test server debug and docs/assets/img
    File testServerDebug = new File("C:\\GameServers\\Minecraft\\testServer\\RTP-Folia\\26.1\\plugins\\RTP\\database\\regionData\\debug");
    if (testServerDebug.exists()) {
      exportCanvas.writeToFile(new File(testServerDebug, "example_composite_overlay_1024.png"), "png");
    }
    exportCanvas.writeToFile(new File("docs/assets/img/example_composite_overlay_1024.png"), "png");

    assertTrue(exportFile.exists() && exportFile.length() > 1000);
    System.out.println("[DEBUG_LOG] Successfully rendered high-res ADR-089 example image to " + exportFile.getAbsolutePath());
  }
}
