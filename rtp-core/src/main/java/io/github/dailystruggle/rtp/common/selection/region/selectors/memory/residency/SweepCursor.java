package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.residency;

/**
 * Read-only view of the backlog selector's dyadic sweep cursor (ADR-092, 2026-09-10).
 *
 * <p>Residency is driven by prediction from this cursor, never by recency: the
 * backlog selection path emits unique, non-repeating candidates in a deterministic
 * dyadic-stride order, so a just-visited bin is the <em>least</em> likely to be
 * needed again until the sweep wraps a full rotation. There is exactly one
 * definition of the dyadic order (the selector's), and the residency manager reads
 * it here rather than re-deriving it, so the two cannot drift.
 */
public interface SweepCursor {

  /** Current sweep counter (the selector's cursor position). */
  long currentSweepCounter();

  /**
   * Distinct upcoming 1024-chunk bins in the exact order the next {@code lookahead}
   * draws will visit them, starting from {@link #currentSweepCounter()}.
   *
   * @param lookahead number of upcoming draws to project
   * @return distinct upcoming bin indices, in first-touch order
   */
  long[] predictUpcomingBins(int lookahead);
}
