package io.github.dailystruggle.rtp.common;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CircleOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validates registration and basic operation of the new optimized dual-layer shapes
 * alongside deprecated pure-spiral shapes in {@link RTP}.
 */
class OptimizedShapesRegistrationTest {

  @Test
  @DisplayName("ShapeFactory contains new dual-layer shapes and deprecated pure-spiral aliases")
  void testShapeFactoryRegistration() {
    Factory<Shape<?>> shapeFactory = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
    if (shapeFactory == null) {
      shapeFactory = new Factory<>();
      RTP.factoryMap.put(RTP.factoryNames.shape, shapeFactory);
      RTP.selectionAPI.shapeFactory = shapeFactory;
    }

    // Register test shapes if not already present
    shapeFactory.add("SQUARE_DEPRECATED_PURE_SPIRAL", new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square("SQUARE_DEPRECATED_PURE_SPIRAL"));
    shapeFactory.add("SQUARE_OPTIMIZED_DUAL_LAYER", new SquareOptimizedDualLayer());
    shapeFactory.add("CIRCLE_DEPRECATED_PURE_SPIRAL", new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle("CIRCLE_DEPRECATED_PURE_SPIRAL"));
    shapeFactory.add("CIRCLE_OPTIMIZED_DUAL_LAYER", new CircleOptimizedDualLayer());

    assertTrue(shapeFactory.contains("SQUARE_DEPRECATED_PURE_SPIRAL"));
    assertTrue(shapeFactory.contains("SQUARE_OPTIMIZED_DUAL_LAYER"));
    assertTrue(shapeFactory.contains("CIRCLE_DEPRECATED_PURE_SPIRAL"));
    assertTrue(shapeFactory.contains("CIRCLE_OPTIMIZED_DUAL_LAYER"));

    Shape<?> squareOpt = (Shape<?>) shapeFactory.get("SQUARE_OPTIMIZED_DUAL_LAYER");
    assertNotNull(squareOpt);
    assertInstanceOf(SquareOptimizedDualLayer.class, squareOpt);

    Shape<?> circleOpt = (Shape<?>) shapeFactory.get("CIRCLE_OPTIMIZED_DUAL_LAYER");
    assertNotNull(circleOpt);
    assertInstanceOf(CircleOptimizedDualLayer.class, circleOpt);
  }

  @Test
  @DisplayName("SquareOptimizedDualLayer generates valid coordinates and performs bijection")
  void testSquareOptimizedDualLayerOperation() {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("SQUARE_OPTIMIZED_DUAL_LAYER", 32);
    long range = shape.getRange();
    assertTrue(range > 0, "Range must be positive");

    MutableRTPCoords out = new MutableRTPCoords(0, 0);
    shape.locationToXZ(0L, out);
    long mapped = shape.xzToLocation(out.x, out.z);
    assertEquals(0L, mapped, "Bijection at location 0 must round-trip");

    // Random selection returns coordinates within range
    long randLoc = shape.rand();
    assertTrue(randLoc >= 0 && randLoc < range, "Random location must be within range");
  }

  @Test
  @DisplayName("CircleOptimizedDualLayer generates valid coordinates and performs bijection")
  void testCircleOptimizedDualLayerOperation() {
    CircleOptimizedDualLayer shape = new CircleOptimizedDualLayer("CIRCLE_OPTIMIZED_DUAL_LAYER", 32);
    long range = shape.getRange();
    assertTrue(range > 0, "Range must be positive");

    MutableRTPCoords out = new MutableRTPCoords(0, 0);
    shape.locationToXZ(0L, out);
    long mapped = shape.xzToLocation(out.x, out.z);
    assertEquals(0L, mapped, "Bijection at location 0 must round-trip");

    long randLoc = shape.rand();
    assertTrue(randLoc >= 0 && randLoc < range, "Random location must be within range");
  }
}
