package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of allocating participant destinations within a localized subspace.
 */
public record SubspaceAllocationResult(
    Status status,
    List<RTPLocation> destinations,
    String message) {

  public enum Status {
    /** Locations successfully resolved for all participants. */
    SUCCESS,
    /** Capacity failure: insufficient safe non-colliding candidate locations in the subspace. */
    INSUFFICIENT_SAFE_SLOTS,
    /** Anchor location was null or invalid. */
    INVALID_ANCHOR,
    /** Participant count exceeded profile maximum group size limit. */
    EXCEEDED_MAX_GROUP_SIZE
  }

  public boolean isSuccess() {
    return status == Status.SUCCESS;
  }

  public static SubspaceAllocationResult success(List<RTPLocation> destinations) {
    return new SubspaceAllocationResult(
        Status.SUCCESS,
        Collections.unmodifiableList(Objects.requireNonNull(destinations)),
        "Subspace destinations allocated successfully.");
  }

  public static SubspaceAllocationResult failure(Status status, String message) {
    return new SubspaceAllocationResult(
        Objects.requireNonNull(status),
        Collections.emptyList(),
        Objects.requireNonNull(message));
  }
}
