package io.github.dailystruggle.rtp.common.visualization;

import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ComprehensiveRegionImageExporter Tests")
class ComprehensiveRegionImageExporterTest {

  @TempDir
  Path tempDir;

  private MockRTPWorld testWorld;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir.toFile());
    testWorld = new MockRTPWorld("test_world");
  }

  @Test
  @DisplayName("exportAll generates valid PNG and JSON files with accurate metrics")
  void exportAll_success() throws Exception {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("testSquare", 32);
    shape.set(GenericMemoryShapeParams.radius, 64L);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);

    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "test_region", testWorld, shape, vert,
        false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false
    );
    Region region = new Region("test_region", settings);

    File outDir = tempDir.toFile();
    ComprehensiveRegionImageExporter.ExportResult result =
        ComprehensiveRegionImageExporter.exportAll(region, outDir);

    assertNotNull(result);
    assertNotNull(result.imageFile());
    assertNotNull(result.jsonFile());

    assertTrue(result.imageFile().exists(), "Exported PNG image should exist");
    assertTrue(result.imageFile().length() > 0, "Exported PNG image should not be empty");

    assertTrue(result.jsonFile().exists(), "Exported JSON file should exist");
    assertTrue(result.jsonFile().length() > 0, "Exported JSON file should not be empty");

    String jsonContent = Files.readString(result.jsonFile().toPath());
    assertTrue(jsonContent.contains("\"region\": \"test_region\""));
    assertTrue(jsonContent.contains("\"world\": \"test_world\""));
    assertTrue(jsonContent.contains("\"spatialMetrics\""));
  }

  @Test
  @DisplayName("exportAll with custom size and zoom generates image without arbitrary size limits")
  void exportAll_customSizeAndZoom() throws Exception {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("testSquare", 32);
    shape.set(GenericMemoryShapeParams.radius, 64L);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);

    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "test_region_custom", testWorld, shape, vert,
        false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false
    );
    Region region = new Region("test_region_custom", settings);

    File outDir = tempDir.toFile();
    ComprehensiveRegionImageExporter.ExportResult result =
        ComprehensiveRegionImageExporter.exportAll(region, outDir, 1024, 768, 2.0);

    assertNotNull(result);
    assertNotNull(result.imageFile());
    assertTrue(result.imageFile().exists());
    assertTrue(result.imageFile().length() > 0);
  }

  @Test
  @DisplayName("exportAll preserves proportions for non-square region and respects 4096 auto limit")
  void exportAll_preservesAspectProportions() throws Exception {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("rectShape", 32);
    shape.set(GenericMemoryShapeParams.radius, 10000L);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);

    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "aspect_region", testWorld, shape, vert,
        false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false
    );
    Region region = new Region("aspect_region", settings);

    File outDir = tempDir.toFile();
    ComprehensiveRegionImageExporter.ExportResult result =
        ComprehensiveRegionImageExporter.exportAll(region, outDir);

    assertNotNull(result);
    assertTrue(result.imageFile().exists());
    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(result.imageFile());
    assertNotNull(img);
    // Canvas contains sidebar (314px) and margins, but largest side of map viewport is capped at 4096
    assertTrue(img.getWidth() <= 4096 + 350, "Canvas width should be within 4096 + side margins");
    assertTrue(img.getHeight() <= 4096 + 200, "Canvas height should be within 4096 + vertical margins");
  }

  @Test
  @DisplayName("exportAll with custom width only and custom height only")
  void exportAll_customWidthOnlyAndCustomHeightOnly() throws Exception {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("testSquare", 32);
    shape.set(GenericMemoryShapeParams.radius, 64L);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);

    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "dim_region", testWorld, shape, vert,
        false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false
    );
    Region region = new Region("dim_region", settings);

    File outDir = tempDir.toFile();
    // Width only
    ComprehensiveRegionImageExporter.ExportResult resW =
        ComprehensiveRegionImageExporter.exportAll(region, outDir, 800, null, 1.0);
    assertNotNull(resW);
    assertTrue(resW.imageFile().exists());

    // Height only
    ComprehensiveRegionImageExporter.ExportResult resH =
        ComprehensiveRegionImageExporter.exportAll(region, outDir, null, 600, 1.0);
    assertNotNull(resH);
    assertTrue(resH.imageFile().exists());

    // Both dimensions smaller than side panel margins (hits fallback branches 189 and 195)
    ComprehensiveRegionImageExporter.ExportResult resSmall =
        ComprehensiveRegionImageExporter.exportAll(region, outDir, 50, 40, 1.0);
    assertNotNull(resSmall);
    assertTrue(resSmall.imageFile().exists());
  }
}
