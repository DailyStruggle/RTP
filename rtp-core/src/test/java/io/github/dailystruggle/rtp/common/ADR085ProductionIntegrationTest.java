package io.github.dailystruggle.rtp.common;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CircleOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.util.PointEdgeSelector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ADR085ProductionIntegrationTest {

  @Test
  @DisplayName("PointEdgeSelector derives valid power-of-two P with hard guard")
  void testPointEdgeSelectorDerivation() {
    // Radius = 100 chunks -> (2 * 100) / P >= 64 -> maxAllowedP = 200 / 64 = 3 -> P=2
    int p100 = PointEdgeSelector.derivePFromRadius(100);
    assertEquals(2, p100);
    assertTrue(PointEdgeSelector.isAdmissible(100, p100));

    // Radius = 2048 chunks -> (2 * 2048) / P >= 64 -> maxAllowedP = 4096 / 64 = 64 -> capped at DEFAULT_P (32)
    int p2048 = PointEdgeSelector.derivePFromRadius(2048);
    assertEquals(32, p2048);
    assertTrue(PointEdgeSelector.isAdmissible(2048, p2048));

    // Tiny radius: 10 chunks -> (2 * 10) / P = 20 < 64 -> fallback to P=1
    int pTiny = PointEdgeSelector.derivePFromRadius(10);
    assertEquals(1, pTiny);

    // Transitions
    PointEdgeSelector.Transition t1 = PointEdgeSelector.decideTransition(16, 32);
    assertTrue(t1.losslessRatchet());

    PointEdgeSelector.Transition t2 = PointEdgeSelector.decideTransition(32, 16);
    assertFalse(t2.losslessRatchet());
  }

  @Test
  @DisplayName("SquareOptimizedDualLayer and CircleOptimizedDualLayer expose curve metadata")
  void testCurveMetadata() {
    SquareOptimizedDualLayer square = new SquareOptimizedDualLayer("SQUARE_TEST", 16);
    assertEquals(MemoryShape.CURVE_SPIRAL_HILBERT, square.getCurveName());
    assertEquals(16, square.getPointEdgeChunks());

    CircleOptimizedDualLayer circle = new CircleOptimizedDualLayer("CIRCLE_TEST", 32);
    assertEquals(MemoryShape.CURVE_SPIRAL_HILBERT, circle.getCurveName());
    assertEquals(32, circle.getPointEdgeChunks());

    Square standardSquare = new Square("STANDARD_SQUARE");
    assertEquals(MemoryShape.CURVE_SPIRAL, standardSquare.getCurveName());
    assertEquals(1, standardSquare.getPointEdgeChunks());
  }

  @Test
  @DisplayName("MemoryShape saves and loads Format Version 3 with curve metadata and keyWidth gating")
  void testFormatVersion3SaveLoadRoundTrip() throws Exception {
    java.io.File tempDir = java.nio.file.Files.createTempDirectory("rtp_v3_test").toFile();
    try {
      io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
          new io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor(tempDir);
      io.github.dailystruggle.rtp.common.RTP.serverAccessor = accessor;
      io.github.dailystruggle.rtp.api.RTPAPI.setServerAccessor(accessor);

      SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("SQUARE_V5_TEST", 16);
      shape.addBadLocation(100L);
      shape.addBadLocation(200L);

      shape.save("v5_test_file", "world_v5");

      java.io.File savedFile = new java.io.File(tempDir, "database/regionData/v5_test_file.bin");
      assertTrue(savedFile.exists(), "Binary save file must exist");

      // Inspect binary header
      byte[] bytes = java.nio.file.Files.readAllBytes(savedFile.toPath());
      java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN);
      assertEquals(0x52545031, buf.getInt(), "BIN_MAGIC mismatch");
      assertEquals(3, buf.getInt(), "BIN_VERSION mismatch");

      int curveLen = buf.getInt();
      byte[] cBytes = new byte[curveLen];
      buf.get(cBytes);
      String curveName = new String(cBytes, java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(MemoryShape.CURVE_SPIRAL_HILBERT, curveName);
      assertEquals(16, buf.getInt(), "P value mismatch");
      assertEquals(4, buf.getInt(), "keyWidth should be 4 for range <= Integer.MAX_VALUE");

      // Now load into fresh shape
      SquareOptimizedDualLayer target = new SquareOptimizedDualLayer("SQUARE_V5_TARGET", 16);
      target.load("v5_test_file", "world_v5").get(5, java.util.concurrent.TimeUnit.SECONDS);

      assertEquals(MemoryShape.CURVE_SPIRAL_HILBERT, target.getCurveName());
      assertEquals(16, target.getPointEdgeChunks());
    } finally {
      // recursive delete temp dir
      java.nio.file.Files.walk(tempDir.toPath())
          .sorted(java.util.Comparator.reverseOrder())
          .map(java.nio.file.Path::toFile)
          .forEach(java.io.File::delete);
    }
  }
}
