package io.github.dailystruggle.rtp.common.visualization;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.mapsapi.image.ImageMapCanvas;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FramedVisualizationExporterTest {

  @Test
  void testFrameVariousKinds(@org.junit.jupiter.api.io.TempDir java.io.File tempDir) {
    io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir);
    ImageMapCanvas canvas = new ImageMapCanvas(16, 16);
    MockRTPWorld world = new MockRTPWorld("test_world");
    Circle shape = new Circle();
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "test_region", world, shape, vert,
        false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false
    );
    Region region = new Region("test_region", settings);

    for (ChartSpec.Kind kind : List.of(
        ChartSpec.Kind.REGION_COMPOSITE,
        ChartSpec.Kind.REGION_BIOMES,
        ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE,
        ChartSpec.Kind.SELECTION_HEATMAP,
        ChartSpec.Kind.REGION_WALK_PATH,
        ChartSpec.Kind.METRIC_SPARKLINE
    )) {
      BufferedImage framed = FramedVisualizationExporter.frame(canvas, kind, kind.name().toLowerCase(), region);
      assertNotNull(framed);
    }

    BufferedImage globalFramed = FramedVisualizationExporter.frame(
        canvas, ChartSpec.Kind.METRIC_SPARKLINE, "sparkline", null);
    assertNotNull(globalFramed);

    assertThrows(IllegalArgumentException.class, () ->
        FramedVisualizationExporter.frame(null, ChartSpec.Kind.REGION_COMPOSITE, "test", region));
  }
}
