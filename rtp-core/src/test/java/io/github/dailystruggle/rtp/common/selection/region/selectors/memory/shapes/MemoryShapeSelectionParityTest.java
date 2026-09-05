package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.EllipseMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ-RTP-F-002 / REQ-RTP-F-003 / REQ-RTP-F-007 - selection parity across memory shapes.
 *
 * <p>Every shape shares one selection invariant (bad-run accumulation, mode dispatch,
 * unique-placement marking) and contributes only its own distribution model. These tests pin the
 * shared behaviour that hand-written per-shape copies had drifted away from, plus the per-shape
 * differences that are deliberate.
 */
@DisplayName("REQ-RTP-F-002/F-003/F-007 memory shape selection parity")
public class MemoryShapeSelectionParityTest {

  @BeforeAll
  static void setupServer() {
    MockRTPServerAccessor accessor =
        new MockRTPServerAccessor(new java.io.File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
  }

  /** Mark every location in {@code [0, range)} as bad. */
  private static void markAllBad(MemoryShape<?> shape) {
    long range = shape.getRange();
    for (long i = 0; i < range; i++) shape.addBadLocation(i);
  }

  // ---------------------------------------------------------------------------
  // mode is case-insensitive on every shape
  // ---------------------------------------------------------------------------

  @ParameterizedTest(name = "mode=\"{0}\" rerolls when every location is bad")
  @ValueSource(strings = {"REROLL", "reroll", "ReRoll"})
  @DisplayName("REROLL is honored regardless of configured case")
  void reroll_isCaseInsensitive_onSquare(String mode) {
    Square shape = new Square();
    shape.set(GenericMemoryShapeParams.radius, 16L);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.mode, mode);
    shape.setRng(new Random(42));
    markAllBad(shape);

    assertEquals(-1L, shape.rand(), "a fully-bad shape must request a re-roll, not hand back a known-bad location");
  }

  @ParameterizedTest(name = "mode=\"{0}\" rerolls when every location is bad")
  @ValueSource(strings = {"REROLL", "reroll"})
  @DisplayName("REROLL is honored regardless of configured case (Rectangle)")
  void reroll_isCaseInsensitive_onRectangle(String mode) {
    Rectangle shape = new Rectangle();
    shape.set(RectangleParams.width, 8L);
    shape.set(RectangleParams.height, 8L);
    shape.set(RectangleParams.mode, mode);
    shape.setRng(new Random(42));
    markAllBad(shape);

    assertEquals(-1L, shape.rand());
  }

  // ---------------------------------------------------------------------------
  // NEAREST repairs, and re-rolls rather than returning a known-bad location
  // ---------------------------------------------------------------------------

  @ParameterizedTest(name = "mode=\"{0}\" never returns a known-bad location")
  @ValueSource(strings = {"NEAREST", "nearest"})
  @DisplayName("NEAREST snaps out of a bad run instead of returning it")
  void nearest_repairsSample_onSquare(String mode) {
    Square shape = new Square();
    shape.set(GenericMemoryShapeParams.radius, 64L);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.mode, mode);
    shape.setRng(new Random(7));

    // Bad run over the lower half only, so a nearest-good repair is always possible.
    long half = shape.getRange() / 2;
    for (long i = 0; i < half; i++) shape.addBadLocation(i);

    long location = shape.rand();
    assertNotEquals(-1L, location, "a repairable sample must not re-roll");
    assertFalse(shape.isKnownBad(location), "NEAREST must not return a known-bad location");
  }

  @Test
  @DisplayName("NEAREST re-rolls when no good neighbour exists")
  void nearest_rerollsWhenUnrepairable_onCircle() {
    Circle shape = new Circle();
    shape.set(GenericMemoryShapeParams.radius, 16L);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.mode, "NEAREST");
    shape.setRng(new Random(11));
    markAllBad(shape);

    long location = shape.rand();
    assertTrue(
        location == -1L || !shape.isKnownBad(location),
        "NEAREST must either repair the sample or re-roll, never return a known-bad location");
  }

  // ---------------------------------------------------------------------------
  // uniquePlacements is a chunk radius on every shape, including the normal variants
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("numeric uniqueplacements is honored and preserved on Square_Normal")
  void uniquePlacements_numericValue_isHonoredOnSquareNormal() {
    Square_Normal shape = new Square_Normal();
    shape.set(NormalDistributionParams.radius, 64L);
    shape.set(NormalDistributionParams.centerRadius, 0L);
    shape.set(NormalDistributionParams.mode, "ACCUMULATE");
    shape.set(NormalDistributionParams.uniquePlacements, 2);
    shape.setRng(new Random(3));

    long location = shape.rand();
    assertNotEquals(-1L, location);

    // Previously parsed with Boolean.parseBoolean("2") == false, which both skipped the
    // marking and wrote the coerced false back over the operator's configured value.
    assertEquals(
        2,
        shape.getNumber(NormalDistributionParams.uniquePlacements, -1).intValue(),
        "the configured chunk radius must not be overwritten");
    // The marking is enqueued into pendingBadLocations; a second selection flushes it into
    // the prefix-sum arrays that getEffectiveBadCount() reports on.
    shape.rand();
    assertTrue(
        shape.getEffectiveBadCount() > 0,
        "a positive uniqueplacements radius must mark the landing area");
  }

  // ---------------------------------------------------------------------------
  // expand is refused by shapes bounded smaller than their range
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Ellipse forces expand off, like Polygon")
  void expand_isCoercedOff_onEllipse() {
    Ellipse shape = new Ellipse();
    shape.set(EllipseMemoryShapeParams.radius, 32L);
    shape.set(EllipseMemoryShapeParams.radius2, 16L);
    shape.set(EllipseMemoryShapeParams.centerRadius, 0L);
    shape.set(EllipseMemoryShapeParams.centerRadius2, 0L);
    shape.set(EllipseMemoryShapeParams.expand, true);
    shape.setRng(new Random(5));

    shape.rand();

    Object expand = shape.getData().get(EllipseMemoryShapeParams.expand);
    assertEquals(
        Boolean.FALSE,
        expand,
        "an ellipse is inscribed in the circle its range describes, so expand must be refused");
  }

  @Test
  @DisplayName("Circle still honors expand")
  void expand_isRetained_onCircle() {
    Circle shape = new Circle();
    shape.set(GenericMemoryShapeParams.radius, 32L);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.set(GenericMemoryShapeParams.expand, true);
    shape.setRng(new Random(5));

    shape.rand();

    assertEquals(
        Boolean.TRUE,
        shape.getData().get(GenericMemoryShapeParams.expand),
        "circles are exactly bounded by their range, so expand remains meaningful");
  }

  // ---------------------------------------------------------------------------
  // deliberate per-shape distribution differences are preserved
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Rectangle keeps its plain uniform draw over the unadjusted range")
  void rectangle_usesUniformCurve() {
    Rectangle shape = new Rectangle();
    shape.set(RectangleParams.width, 256L);
    shape.set(RectangleParams.height, 256L);
    shape.set(RectangleParams.mode, "REROLL");
    shape.setRng(new Random(1234));

    long range = shape.getRange();
    long expected = (long) (range * new Random(1234).nextDouble());

    assertEquals(
        expected,
        shape.rand(),
        "Rectangle exposes neither weight nor expand, so its draw must stay uniform over the raw range");
  }

  @Test
  @DisplayName("weight biases the draw on shapes that expose it")
  void weight_biasesDraw_onCircle() {
    long lowWeightTotal = 0;
    long highWeightTotal = 0;
    int samples = 200;

    for (int i = 0; i < samples; i++) {
      Circle inner = new Circle();
      inner.set(GenericMemoryShapeParams.radius, 512L);
      inner.set(GenericMemoryShapeParams.centerRadius, 0L);
      inner.set(GenericMemoryShapeParams.mode, "REROLL");
      inner.set(GenericMemoryShapeParams.weight, 4.0);
      inner.setRng(new Random(i));
      lowWeightTotal += inner.rand();

      Circle outer = new Circle();
      outer.set(GenericMemoryShapeParams.radius, 512L);
      outer.set(GenericMemoryShapeParams.centerRadius, 0L);
      outer.set(GenericMemoryShapeParams.mode, "REROLL");
      outer.set(GenericMemoryShapeParams.weight, 1.0);
      outer.setRng(new Random(i));
      highWeightTotal += outer.rand();
    }

    assertTrue(
        lowWeightTotal < highWeightTotal,
        "a higher weight exponent must pull samples toward the center");
  }
}
