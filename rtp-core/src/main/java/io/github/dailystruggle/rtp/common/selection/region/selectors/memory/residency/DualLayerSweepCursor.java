package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.residency;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;

/**
 * {@link SweepCursor} backed directly by a {@link SquareOptimizedDualLayer}, the
 * single source of the dyadic order. The manager reads the selector's cursor
 * through this adapter rather than re-deriving the traversal, so warming and
 * selection cannot drift apart (ADR-092, 2026-09-10).
 */
public final class DualLayerSweepCursor implements SweepCursor {

  private final SquareOptimizedDualLayer shape;

  public DualLayerSweepCursor(SquareOptimizedDualLayer shape) {
    if (shape == null) {
      throw new IllegalArgumentException("shape");
    }
    this.shape = shape;
  }

  @Override
  public long currentSweepCounter() {
    return shape.currentSweepCounter();
  }

  @Override
  public long[] predictUpcomingBins(int lookahead) {
    return shape.predictUpcomingBins(lookahead);
  }
}
