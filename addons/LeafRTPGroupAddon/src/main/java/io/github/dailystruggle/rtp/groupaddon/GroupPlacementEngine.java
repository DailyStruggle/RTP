package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.common.selection.region.CandidateValidator;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SubspaceShape;
import java.util.List;

/**
 * Engine for resolving multi-participant destinations within localized subspaces.
 */
public final class GroupPlacementEngine {

  private GroupPlacementEngine() {}

  /**
   * Allocates safe destinations for a group of participants using an anchor from the parent region.
   *
   * <p>Selection is two-stage (see {@link SubspaceShape}): a chunk-granularity Stage 1 pre-filter
   * over the profile's {@code subspaceChunkRadius} footprint, then block-granularity Stage 2 bin
   * screening via {@code validator}. Capacity denial is measured against block-validated slots, not
   * chunk bits, so the count reflects real standable positions.
   *
   * @param anchor primary anchor location drawn from region queue
   * @param parentRegion owning region
   * @param profile placement profile configuration
   * @param participantCount number of players to place
   * @param validator block-level standability resolver (never {@code null})
   * @return allocation result (either SUCCESS with locations, or fail-closed failure status)
   */
  public static SubspaceAllocationResult allocate(
      RTPLocation anchor,
      Region parentRegion,
      GroupProfile profile,
      int participantCount,
      SubspaceShape.BlockValidator validator) {

    if (anchor == null || parentRegion == null) {
      return SubspaceAllocationResult.failure(
          SubspaceAllocationResult.Status.INVALID_ANCHOR,
          "Anchor location or parent region was null.");
    }

    SubspaceAllocationResult pre = precheck(anchor, parentRegion, profile, participantCount);
    if (pre != null) return pre;

    SubspaceShape subspace = new SubspaceShape(anchor, profile.subspaceChunkRadius(), parentRegion);
    List<RTPLocation> slots =
        subspace.selectSafeSlots(participantCount, profile.minSeparation(), validator);
    return toResult(slots, participantCount);
  }

  /**
   * Production allocation using the parent region's shared {@link CandidateValidator}
   * ({@code Region.candidateValidator()}) - the single validation path (vert -> shared safety scan
   * -> claim/global checks). Must be called off-tick (S-005). This is the entry point real group
   * teleports should use; the {@code BlockValidator} overload above exists for deterministic tests.
   *
   * @param anchor primary anchor location drawn from region queue
   * @param parentRegion owning region (supplies the shared validator)
   * @param profile placement profile configuration
   * @param participantCount number of players to place
   * @return allocation result (SUCCESS with locations, or fail-closed failure status)
   */
  public static SubspaceAllocationResult allocate(
      RTPLocation anchor,
      Region parentRegion,
      GroupProfile profile,
      int participantCount) {

    if (anchor == null || parentRegion == null) {
      return SubspaceAllocationResult.failure(
          SubspaceAllocationResult.Status.INVALID_ANCHOR,
          "Anchor location or parent region was null.");
    }
    return allocate(anchor, parentRegion, profile, participantCount, parentRegion.candidateValidator());
  }

  /**
   * Core allocation over an explicit shared {@link CandidateValidator}.
   *
   * @param anchor primary anchor location drawn from region queue
   * @param parentRegion owning region
   * @param profile placement profile configuration
   * @param participantCount number of players to place
   * @param validator shared per-candidate validator (never {@code null})
   * @return allocation result (SUCCESS with locations, or fail-closed failure status)
   */
  public static SubspaceAllocationResult allocate(
      RTPLocation anchor,
      Region parentRegion,
      GroupProfile profile,
      int participantCount,
      CandidateValidator validator) {

    if (anchor == null || parentRegion == null) {
      return SubspaceAllocationResult.failure(
          SubspaceAllocationResult.Status.INVALID_ANCHOR,
          "Anchor location or parent region was null.");
    }
    SubspaceAllocationResult pre = precheck(anchor, parentRegion, profile, participantCount);
    if (pre != null) return pre;

    SubspaceShape subspace = new SubspaceShape(anchor, profile.subspaceChunkRadius(), parentRegion);
    List<RTPLocation> slots =
        subspace.selectSafeSlots(participantCount, profile.minSeparation(), validator);
    return toResult(slots, participantCount);
  }

  private static SubspaceAllocationResult precheck(
      RTPLocation anchor, Region parentRegion, GroupProfile profile, int participantCount) {
    if (participantCount > profile.maxGroupSize()) {
      return SubspaceAllocationResult.failure(
          SubspaceAllocationResult.Status.EXCEEDED_MAX_GROUP_SIZE,
          "Participant count (" + participantCount + ") exceeds max group size ("
              + profile.maxGroupSize() + ").");
    }
    return null;
  }

  private static SubspaceAllocationResult toResult(List<RTPLocation> slots, int participantCount) {
    if (slots.size() < participantCount) {
      return SubspaceAllocationResult.failure(
          SubspaceAllocationResult.Status.INSUFFICIENT_SAFE_SLOTS,
          "Subspace contains insufficient safe candidate locations for " + participantCount + " participants.");
    }
    return SubspaceAllocationResult.success(slots);
  }
}
