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
   * Allocation attempting cache retrieval (L1 Hot -> L2 Cold) before falling back
   * to live dynamic allocation.
   *
   * @param cache group subspace cache
   * @param anchor primary anchor location (used if cache miss)
   * @param parentRegion owning region
   * @param profile placement profile configuration
   * @param participantCount number of players to place
   * @return allocation result
   */
  public static SubspaceAllocationResult allocateWithCache(
      GroupSubspaceCache cache,
      RTPLocation anchor,
      Region parentRegion,
      GroupProfile profile,
      int participantCount) {

    if (parentRegion == null || profile == null) {
      return SubspaceAllocationResult.failure(
          SubspaceAllocationResult.Status.INVALID_ANCHOR,
          "Parent region or profile was null.");
    }
    SubspaceAllocationResult pre = precheck(anchor, parentRegion, profile, participantCount);
    if (pre != null) return pre;

    String profileKey = parentRegion.name + ":" + profile.name();
    if (cache != null) {
      // 1. Try Group L1 (Hot / Kept)
      GroupSubspace hot = cache.pollHot(profileKey);
      if (hot != null) {
        if (hot.slotLocations().size() >= participantCount) {
          return SubspaceAllocationResult.success(
              hot.slotLocations().subList(0, participantCount));
        } else {
          hot.close();
        }
      }

      // 2. Try Group L2 (Cold / Unkept)
      GroupSubspace cold = cache.pollCold(profileKey);
      if (cold != null) {
        if (cold.slotLocations().size() >= participantCount) {
          return SubspaceAllocationResult.success(
              cold.slotLocations().subList(0, participantCount));
        }
      }
    }

    // 3. Fallback to live dynamic allocation
    return allocate(anchor, parentRegion, profile, participantCount);
  }

  /**
   * Production allocation using the parent region's shared {@link CandidateValidator}
   * ({@code Region.candidateValidator()}) - the single validation path (vert -> shared safety scan
   * -> claim/global checks). Must be called off-tick (S-005). This is the entry point real group
   * teleports should use.
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

    // Footprint from the shape block's radius (blocks); selection stride reuses spatialResolution.
    int spacing = profile.spacing();
    SubspaceShape subspace = new SubspaceShape(anchor, profile.radiusBlocks(), parentRegion);
    // Resolve the shape mask (null = full square lattice). Elevation is not a group knob: the
    // region VerticalAdjustor bounds landing Y per column (a windowed-vert clone is a later refinement).
    int latticeUnits = (subspace.getFootprintBlocks() / 2) / Math.max(1, spacing);
    io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?> shape =
        GroupShapes.resolve(profile.shape(), latticeUnits);
    List<RTPLocation> slots =
        subspace.selectSafeSlots(participantCount, spacing, -1, shape, validator);
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
