package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-F-005 / REQ-RTP-F-009 - learned-state maintenance stays bounded per selection.
 *
 * <p>A rebuild of the bad-run arrays is a full merge, so it has to be amortized: marks that the
 * merge would coalesce into an existing run are absorbed in place, and the remaining pending marks
 * are rebuilt in batches rather than once per {@code rand()}. These tests pin both behaviours,
 * plus the invariant that a mark is honoured by {@code isKnownBad} the moment it is recorded
 * regardless of which path took it.
 */
@DisplayName("REQ-RTP-F-005/F-009 amortized learned-state maintenance")
public class MemoryShapeAmortizedLearningTest {

  @BeforeAll
  static void setupServer() {
    MockRTPServerAccessor accessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
  }

  /** A square with one committed bad run at {@code [100, 110)} and clean dirty flags. */
  private static Square shapeWithRun() {
    Square shape = new Square();
    shape.set(GenericMemoryShapeParams.radius, 256L);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    shape.setRng(new Random(4L));
    for (long i = 100L; i < 110L; i++) shape.addBadLocation(i);
    shape.flushAndRebuild(1L);
    assertFalse(shape.badLocationsDirty, "precondition: run committed, nothing pending");
    return shape;
  }

  @Test
  @DisplayName("a mark already inside a committed run is a no-op")
  void coveredMark_isNoOp() {
    Square shape = shapeWithRun();
    long before = shape.getEffectiveBadCount();

    shape.addBadLocation(105L);

    assertEquals(before, shape.getEffectiveBadCount(), "a covered cell must not be counted twice");
    assertFalse(shape.badLocationsDirty, "a covered cell must not dirty the learned state");
    assertTrue(shape.isKnownBad(105L));
  }

  @Test
  @DisplayName("a mark within spatialResolution of a run extends that run in place")
  void adjacentMark_isAbsorbedWithoutRebuild() {
    Square shape = shapeWithRun();
    long before = shape.getEffectiveBadCount();

    // spatialResolution == 1, so 110 (the cell right after the run) is absorbable.
    shape.addBadLocation(110L);

    assertEquals(before + 1L, shape.getEffectiveBadCount(), "the run must grow by exactly one cell");
    assertFalse(
        shape.badLocationsDirty,
        "an absorbed mark must not require a rebuild - that is the whole point");
    assertTrue(shape.isKnownBad(110L), "the absorbed cell must read as bad immediately");
    assertFalse(shape.isKnownBad(120L), "absorption must not widen the run beyond the mark");
  }

  @Test
  @DisplayName("a mark far from every run still flows through the pending batch")
  void distantMark_flowsThroughPendingState() {
    Square shape = shapeWithRun();

    shape.addBadLocation(5_000L);

    assertTrue(
        shape.badLocationsDirty, "a genuinely new run changes the array length, so it must rebuild");
    assertTrue(shape.isKnownBad(5_000L), "a pending mark must be honoured before the rebuild");
  }

  @Test
  @DisplayName("selection honours pending marks even when the rebuild is deferred")
  void deferredRebuild_doesNotLoseMarks() {
    Square shape = shapeWithRun();

    // Mark a spread of isolated cells, none absorbable, then confirm every one reads as bad
    // through whichever path it took while selections continue to run.
    for (long i = 0; i < 40; i++) {
      long location = 10_000L + i * 997L;
      shape.addBadLocation(location);
      shape.rand();
      assertTrue(shape.isKnownBad(location), "mark " + location + " was lost across a selection");
    }

    shape.flushAndRebuild(1L);
    for (long i = 0; i < 40; i++) {
      assertTrue(
          shape.isKnownBad(10_000L + i * 997L),
          "mark must survive the eventual rebuild");
    }
  }
}
