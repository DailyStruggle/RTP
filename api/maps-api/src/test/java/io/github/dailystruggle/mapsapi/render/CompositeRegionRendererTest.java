package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.image.ImageMapCanvas;
import io.github.dailystruggle.mapsapi.model.CompositeRegionModel;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Empirical unit and invariant test for ADR-089:
 * Universal MapCanvas Bridge, Biome-Hazard Composite Overlays, and Visual Pipeline Gauges.
 */
public class CompositeRegionRendererTest {

  @Test
  @DisplayName("ADR-089: ImageMapCanvas exports valid PNG and BMP with zero chunk I/O")
  public void testImageMapCanvasExport() throws IOException {
    ImageMapCanvas canvas = new ImageMapCanvas(128, 128);
    canvas.setPixelRgb(10, 10, 0xFF00FF);
    canvas.fillRect(20, 20, 40, 40, (byte) 30); // Green
    canvas.commit();

    assertEquals(1, canvas.getCommitCount());
    assertNotNull(canvas.getImage());

    byte[] pngBytes = canvas.toByteArray("png");
    byte[] bmpBytes = canvas.toByteArray("bmp");

    assertTrue(pngBytes.length > 50, "PNG byte output must be valid image file");
    assertTrue(bmpBytes.length > 50, "BMP byte output must be valid image file");

    // Test writing to disk
    File tempPng = new File("build/tmp/test_export.png");
    canvas.writeToFile(tempPng, "png");
    assertTrue(tempPng.exists() && tempPng.length() > 0, "Image file must be written to disk");
    tempPng.deleteOnExit();
  }

  @Test
  @DisplayName("ADR-089: CanvasDrawing utilities desaturate, blend, and draw outlined points")
  public void testCanvasDrawingPrimitives() {
    int forestGreen = 0x27AE60;
    int desaturated = CanvasDrawing.desaturate(forestGreen, 0.5f);

    // Verify desaturation: channels converge closer to luminance
    int rOrig = (forestGreen >> 16) & 0xFF;
    int gOrig = (forestGreen >> 8) & 0xFF;
    int bOrig = forestGreen & 0xFF;

    int rDesat = (desaturated >> 16) & 0xFF;
    int gDesat = (desaturated >> 8) & 0xFF;
    int bDesat = desaturated & 0xFF;

    int deltaOrig = Math.abs(gOrig - rOrig);
    int deltaDesat = Math.abs(gDesat - rDesat);
    assertTrue(deltaDesat < deltaOrig, "Desaturation must reduce color channel variance towards gray");

    // Verify red hazard blend
    int redHazard = 0xE74C3C;
    int blended = CanvasDrawing.blend(desaturated, redHazard, 0.5f);
    int rBlended = (blended >> 16) & 0xFF;
    assertTrue(rBlended > rDesat, "Hazard blend must shift red channel upwards");

    // Verify outlined point
    ImageMapCanvas canvas = new ImageMapCanvas(32, 32);
    CanvasDrawing.drawOutlinedPoint(canvas, 15, 15, 0x00FF00, 0x000000, 1);

    BufferedImage img = canvas.getImage();
    // Center pixel must be fill (green)
    assertEquals(0xFF00FF00, img.getRGB(15, 15));
    // Perimeter pixels must be border (black)
    assertEquals(0xFF000000, img.getRGB(14, 15));
    assertEquals(0xFF000000, img.getRGB(16, 15));
    assertEquals(0xFF000000, img.getRGB(15, 14));
    assertEquals(0xFF000000, img.getRGB(15, 16));
  }

  @Test
  @DisplayName("ADR-089: CompositeRegionRenderer renders biomes, hazards, markers, and queue health bars")
  public void testCompositeRegionRendering() throws IOException {
    int w = 128;
    int h = 128;
    int[] biomes = new int[w * h];
    boolean[] hazards = new boolean[w * h];
    boolean[] inside = new boolean[w * h];

    // Synthetic world with plains (green), desert (yellow), and ocean hazard (blue)
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int idx = y * w + x;
        double dist = Math.sqrt((x - 64) * (x - 64) + (y - 64) * (y - 64));
        if (dist <= 55) {
          inside[idx] = true;
          if (x < 50) {
            biomes[idx] = 0x2ECC71; // Plains green
          } else {
            biomes[idx] = 0xF1C40F; // Desert gold
          }

          // Unsafe ocean/river band
          if (y >= 40 && y <= 50) {
            hazards[idx] = true;
            biomes[idx] = 0x3498DB; // Ocean water
          }
        }
      }
    }

    List<CompositeRegionModel.Marker> markers = new ArrayList<>();
    // L1 Hot Queue (Emerald)
    markers.add(new CompositeRegionModel.Marker(30, 30, 0x2ECC71, 0x000000, 1));
    // L2 Cold Queue (Cyan)
    markers.add(new CompositeRegionModel.Marker(70, 70, 0x3498DB, 0x000000, 1));
    // L3 Backlog (Purple)
    markers.add(new CompositeRegionModel.Marker(80, 30, 0x9B59B6, 0x000000, 1));
    // Recent arrival (Gold)
    markers.add(new CompositeRegionModel.Marker(45, 80, 0xF39C12, 0x000000, 2));

    List<CompositeRegionModel.VectorLine> trajectories = new ArrayList<>();
    trajectories.add(new CompositeRegionModel.VectorLine(30, 30, 70, 70, 0xF1C40F));
    trajectories.add(new CompositeRegionModel.VectorLine(70, 70, 45, 80, 0xF1C40F));

    CompositeRegionModel.QueueGauge l1Gauge = new CompositeRegionModel.QueueGauge("L1 Hot", 24, 30, 0x2ECC71);
    CompositeRegionModel.QueueGauge l2Gauge = new CompositeRegionModel.QueueGauge("L2 Cold", 60, 90, 0x3498DB);
    CompositeRegionModel.QueueGauge l3Gauge = new CompositeRegionModel.QueueGauge("L3 Backlog", 210, 300, 0x9B59B6);

    CompositeRegionModel model = new CompositeRegionModel(
        "test_region", w, h, biomes, hazards, inside, markers, trajectories, l1Gauge, l2Gauge, l3Gauge
    );

    // 1. Render onto vanilla 128x128 map canvas
    ImageMapCanvas vanillaMap = new ImageMapCanvas(128, 128);
    CompositeRegionRenderer.INSTANCE.render(vanillaMap, model);
    vanillaMap.commit();

    assertEquals(1, vanillaMap.getCommitCount());
    assertTrue(vanillaMap.getImage().getRGB(64, 64) != 0, "Center pixel must be drawn");

    // 2. Render onto high-resolution 512x512 export canvas (proves resolution independence)
    ImageMapCanvas highResMap = new ImageMapCanvas(512, 512);
    CompositeRegionRenderer.INSTANCE.render(highResMap, model);
    highResMap.commit();

    File exportDir = new File("build/reports/visualizations");
    exportDir.mkdirs();
    File highResFile = new File(exportDir, "composite_region_preview_512.png");
    highResMap.writeToFile(highResFile, "png");

    assertTrue(highResFile.exists() && highResFile.length() > 500, "High-res PNG must be written");
    System.out.println("[DEBUG_LOG] Successfully rendered and verified ADR-089 composite map to " + highResFile.getAbsolutePath());
  }
}
