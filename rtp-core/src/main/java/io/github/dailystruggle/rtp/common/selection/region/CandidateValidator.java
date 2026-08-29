package io.github.dailystruggle.rtp.common.selection.region;

import org.jetbrains.annotations.Nullable;

/**
 * Reservation-agnostic per-candidate location validator - the single shared definition of "turn a
 * column {@code (worldX, worldZ)} into a verified, standable, claim-safe location, or reject it".
 *
 * <p>Implementations chain the three canonical validation layers already used by the real teleport
 * path so no consumer grows a second, drifting definition of safety:
 *
 * <ol>
 *   <li>standable-{@code Y} resolution via the region {@code VerticalAdjustor} (S-001),</li>
 *   <li>block-clearance verdict via {@link SafetyScan} / {@code RTPChunk.isSafe} (S-001), and</li>
 *   <li>claim / global checks via {@code GlobalRegionVerifiers} (S-003, ADR-026).</li>
 * </ol>
 *
 * <p>Validation runs off-tick against pre-cached / loaded chunk data (S-005). Callers on Folia are
 * responsible for invoking from - or the implementation for hopping to - the owning region thread
 * for any live {@code isSafe} reads.
 */
@FunctionalInterface
public interface CandidateValidator {

  /**
   * Validates a world column, returning a resolved standable location or {@code null} on rejection.
   *
   * @param worldX absolute world block X
   * @param worldZ absolute world block Z
   * @return a verified {@link RTPLocation} (with a resolved standable {@code Y}), or {@code null} if
   *     the column has no safe, claim-clear landing
   */
  @Nullable
  RTPLocation validate(int worldX, int worldZ);
}
