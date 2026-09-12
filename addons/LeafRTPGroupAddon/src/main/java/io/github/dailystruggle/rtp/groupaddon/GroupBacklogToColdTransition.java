package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.CandidateValidator;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.cache.RejectionReason;
import io.github.dailystruggle.rtp.common.selection.region.cache.StageTransition;
import io.github.dailystruggle.rtp.common.selection.region.cache.TransitionOutcome;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Off-tick candidate screening transition promoting {@link GroupBacklogEntry} to cold {@link GroupSubspace}
 * (ADR-078 Phase 6, REQ-RTP-S-004, REQ-RTP-S-005).
 *
 * <p>Candidate subspace layouts are verified off-tick using {@link CandidateValidator} or the parent region's
 * Anvil/region pre-filter. Explicit failure causes are reported via {@link TransitionOutcome#rejected(RejectionReason, String)}.
 */
public final class GroupBacklogToColdTransition
    implements StageTransition<GroupBacklogEntry, GroupSubspace> {
  private final Region region;
  private final GroupProfile profile;
  private final CandidateValidator validator;

  public GroupBacklogToColdTransition(
      Region region, GroupProfile profile, CandidateValidator validator) {
    this.region = region;
    this.profile = profile;
    this.validator =
        (validator != null)
            ? validator
            : ((region != null) ? region.candidateValidator() : null);
  }

  public GroupBacklogToColdTransition(Region region, GroupProfile profile) {
    this(region, profile, null);
  }

  public Region region() {
    return region;
  }

  public GroupProfile profile() {
    return profile;
  }

  public CandidateValidator validator() {
    return validator;
  }

  @Override
  public CompletableFuture<TransitionOutcome<GroupSubspace>> promote(GroupBacklogEntry source) {
    if (source == null) {
      return CompletableFuture.completedFuture(
          TransitionOutcome.rejected(RejectionReason.ERROR, "source backlog entry was null"));
    }
    if (region == null) {
      source.setValidity(GroupBacklogEntry.Validity.INVALIDATED);
      return CompletableFuture.completedFuture(
          TransitionOutcome.rejected(RejectionReason.OUT_OF_BOUNDS, "parent region was null"));
    }
    if (profile == null) {
      source.setValidity(GroupBacklogEntry.Validity.INVALIDATED);
      return CompletableFuture.completedFuture(
          TransitionOutcome.rejected(RejectionReason.OUT_OF_BOUNDS, "group profile was null"));
    }
    if (source.anchor() == null) {
      source.setValidity(GroupBacklogEntry.Validity.INVALIDATED);
      return CompletableFuture.completedFuture(
          TransitionOutcome.rejected(
              RejectionReason.OUT_OF_BOUNDS, "candidate anchor coordinate was null"));
    }

    CandidateValidator v = (validator != null) ? validator : region.candidateValidator();
    if (v == null) {
      source.setValidity(GroupBacklogEntry.Validity.INVALIDATED);
      return CompletableFuture.completedFuture(
          TransitionOutcome.rejected(
              RejectionReason.ERROR, "candidate validator was unavailable"));
    }

    try {
      RTPLocation anchor = new RTPLocation(source.anchor(), 1);
      SubspaceAllocationResult result =
          GroupPlacementEngine.allocate(anchor, region, profile, profile.maxGroupSize(), v);

      if (result.isSuccess() && !result.destinations().isEmpty()) {
        source.setValidity(GroupBacklogEntry.Validity.VALIDATED);
        source.setValidatedSlots(result.destinations());
        GroupSubspace coldSubspace =
            new GroupSubspace(
                anchor,
                profile.radiusBlocks(),
                result.destinations(),
                Collections.emptyList());
        return CompletableFuture.completedFuture(TransitionOutcome.promoted(coldSubspace));
      } else {
        source.setValidity(GroupBacklogEntry.Validity.INVALIDATED);
        RejectionReason reason = mapRejectionReason(result.status());
        String msg =
            (result.message() != null)
                ? result.message()
                : "subspace candidate validation failed";
        return CompletableFuture.completedFuture(TransitionOutcome.rejected(reason, msg));
      }
    } catch (Throwable t) {
      source.setValidity(GroupBacklogEntry.Validity.INVALIDATED);
      RTP.log(
          Level.WARNING,
          "[LeafRTPGroupAddon] unexpected error during backlog-to-cold screening transition",
          t);
      String detail = (t.getMessage() != null) ? t.getMessage() : t.getClass().getSimpleName();
      return CompletableFuture.completedFuture(
          TransitionOutcome.rejected(RejectionReason.ERROR, detail));
    }
  }

  private static RejectionReason mapRejectionReason(SubspaceAllocationResult.Status status) {
    if (status == null) return RejectionReason.ERROR;
    return switch (status) {
      case INSUFFICIENT_SAFE_SLOTS -> RejectionReason.UNSAFE_BLOCK;
      case INVALID_ANCHOR, EXCEEDED_MAX_GROUP_SIZE -> RejectionReason.OUT_OF_BOUNDS;
      default -> RejectionReason.ERROR;
    };
  }
}
