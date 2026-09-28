package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.image.ImageMapCanvas;
import io.github.dailystruggle.mapsapi.model.RegionWalkPath;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RegionWalkPathResolver and RegionWalkPathRenderer integration")
class RegionWalkPathResolverTest {

  @TempDir
  Path tempDir;

  private Region testRegion;
  private SquareOptimizedDualLayer shape;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir.toFile());

    shape = new SquareOptimizedDualLayer("testSquare", 32);
    shape.set(GenericMemoryShapeParams.radius, 128L);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.centerX, 0L);
    shape.set(GenericMemoryShapeParams.centerZ, 0L);

    MockRTPWorld testWorld = new MockRTPWorld("walk_world");
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "walk_region", testWorld, shape, vert,
        false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false
    );
    testRegion = new Region("walk_region", settings);

    if (RTP.selectionAPI != null) {
      RTP.selectionAPI.permRegionLookup.put("walk_region", testRegion);
    }
  }

  @AfterEach
  void tearDown() {
    ChartSpecResolvers.resetForTest();
  }

  @Test
  @DisplayName("RegionWalkPathResolver resolves walk path and renders to canvas")
  void resolvesAndRendersWalkPath() throws Exception {
    ChartSpec spec = ChartSpec.of(ChartSpec.Kind.REGION_WALK_PATH, "walk_region");
    ChartSpecResolver resolver = ChartSpecResolvers.get(ChartSpec.Kind.REGION_WALK_PATH);
    assertNotNull(resolver);

    ChartSpecResolver.Resolution resolution = resolver.resolve(spec);
    assertNotNull(resolution);
    assertTrue(resolution.model() instanceof RegionWalkPath);

    RegionWalkPath walkModel = (RegionWalkPath) resolution.model();
    assertEquals("walk_region", walkModel.regionName());
    assertEquals(shape.getRange(), walkModel.steps().size(), "Every point on the path must be captured");
    assertTrue(walkModel.boundW() > 0);
    assertTrue(walkModel.boundH() > 0);

    // Verify step statuses present
    boolean hasValid = walkModel.steps().stream()
        .anyMatch(s -> s.status() == RegionWalkPath.StepStatus.VALID);
    assertTrue(hasValid, "Should have valid steps");

    // Render to canvas
    ImageMapCanvas canvas = new ImageMapCanvas(128, 128);
    @SuppressWarnings("unchecked")
    io.github.dailystruggle.mapsapi.render.ChartRenderer<io.github.dailystruggle.mapsapi.model.ChartModel> renderer =
        (io.github.dailystruggle.mapsapi.render.ChartRenderer<io.github.dailystruggle.mapsapi.model.ChartModel>) resolution.renderer();
    renderer.render(canvas, walkModel);
    canvas.commit();

    File target = new File(tempDir.toFile(), "walk_test.png");
    canvas.writeToFile(target, "png");
    assertTrue(target.exists());
    assertTrue(target.length() > 0);
  }

  @Test
  @DisplayName("RegionWalkPathResolver rejects null spec or wrong kind")
  void rejectsInvalidRequests() {
    RegionWalkPathResolver resolver = new RegionWalkPathResolver();
    assertThrows(ChartSpecResolver.UnresolvableChartSpecException.class, () -> resolver.resolve(null));
    ChartSpec wrongSpec = ChartSpec.of(ChartSpec.Kind.METRIC_SPARKLINE, "walk_region");
    assertThrows(ChartSpecResolver.UnresolvableChartSpecException.class, () -> resolver.resolve(wrongSpec));
  }
}
