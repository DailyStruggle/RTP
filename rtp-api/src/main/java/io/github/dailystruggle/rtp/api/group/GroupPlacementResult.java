package io.github.dailystruggle.rtp.api.group;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable outcome of a {@link GroupPlacementService#place(GroupPlacementRequest)} request.
 *
 * <p>Delivered for every request, never silently dropped (S-004). Success carries an ordered
 * per-participant destination map; failure carries a {@link Reason} and message with an empty map.
 */
@PublicApi
public final class GroupPlacementResult {

  /** Classifies why a group placement request did or did not succeed. */
  public enum Reason {
    /** All participants were allocated and dispatched to their destinations. */
    SUCCESS,
    /** The named region could not be resolved. */
    INVALID_REGION,
    /** The participant count exceeds the supplied {@code maxGroupSize}. */
    EXCEEDED_MAX_GROUP_SIZE,
    /** The subspace did not contain enough safe standable slots for the group (S-004). */
    INSUFFICIENT_SAFE_SLOTS,
    /** No usable anchor location could be found for the region. */
    NO_ANCHOR,
    /** The request was cancelled (e.g. all participants disconnected) before dispatch. */
    CANCELLED,
    /** An unexpected error aborted the request; see {@link #message()}. */
    ERROR
  }

  private final Reason reason;
  private final String message;
  private final Map<UUID, RTPLocation> placements;

  private GroupPlacementResult(Reason reason, String message, Map<UUID, RTPLocation> placements) {
    this.reason = reason;
    this.message = message;
    this.placements = Collections.unmodifiableMap(new LinkedHashMap<>(placements));
  }

  /**
   * Builds a success result.
   *
   * @param placements ordered per-participant destinations; must not be {@code null} or empty
   * @return a success result
   * @throws IllegalArgumentException if {@code placements} is null or empty
   */
  public static GroupPlacementResult success(Map<UUID, RTPLocation> placements) {
    if (placements == null || placements.isEmpty()) {
      throw new IllegalArgumentException("success requires a non-empty placement map");
    }
    return new GroupPlacementResult(Reason.SUCCESS, "ok", placements);
  }

  /**
   * Builds a failure result with an empty placement map.
   *
   * @param reason non-success classification; must not be {@code null} or {@link Reason#SUCCESS}
   * @param message human-readable explanation; may be {@code null}
   * @return a failure result
   * @throws IllegalArgumentException if {@code reason} is null or {@link Reason#SUCCESS}
   */
  public static GroupPlacementResult failure(Reason reason, String message) {
    if (reason == null || reason == Reason.SUCCESS) {
      throw new IllegalArgumentException("failure reason must be a non-success Reason");
    }
    return new GroupPlacementResult(reason, message, Collections.emptyMap());
  }

  /**
   * @return {@code true} if {@link #reason()} is {@link Reason#SUCCESS}
   */
  public boolean isSuccess() {
    return reason == Reason.SUCCESS;
  }

  /**
   * @return the outcome classification; never {@code null}
   */
  public Reason reason() {
    return reason;
  }

  /**
   * @return a human-readable description of the outcome; possibly {@code null}
   */
  public String message() {
    return message;
  }

  /**
   * @return an unmodifiable, ordered per-participant destination map; empty on failure
   */
  public Map<UUID, RTPLocation> placements() {
    return placements;
  }

  @Override
  public String toString() {
    return "GroupPlacementResult[" + reason
        + ", placed=" + placements.size()
        + (message != null ? ", \"" + message + "\"" : "") + ']';
  }
}
