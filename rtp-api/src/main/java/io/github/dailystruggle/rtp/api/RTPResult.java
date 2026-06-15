package io.github.dailystruggle.rtp.api;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import io.github.dailystruggle.rtp.api.world.RTPLocation;

/**
 * Immutable outcome of an {@link RTPAPI#teleport(java.util.UUID, RtpTarget)} request.
 *
 * <p>A result is <em>always</em> delivered for every teleport request - success or
 * failure - so that callers never have to guess whether a teleport silently did
 * nothing (REQ-RTP-S-004). On success {@link #location()} is non-null; on failure
 * {@link #reason()} explains why and {@link #location()} is {@code null}.
 *
 * <p>Idiomatic use:
 * <pre>{@code
 * RTPAPI.teleport(playerId, RtpTarget.defaultRegion())
 *       .thenAccept(result -> {
 *         if (result.isSuccess()) {
 *           getLogger().info("Sent to " + result.location());
 *         } else {
 *           getLogger().warning("RTP failed: " + result.reason() + " - " + result.message());
 *         }
 *       });
 * }</pre>
 */
@PublicApi
public final class RTPResult {

  /** Classifies why a teleport request did not succeed. */
  public enum Reason {
    /** The teleport completed and the player was moved. */
    SUCCESS,
    /** No player with the requested UUID is currently online on this server. */
    PLAYER_OFFLINE,
    /** The requested region or world could not be resolved. */
    INVALID_TARGET,
    /** The pipeline ran but could not find a safe destination. */
    NO_SAFE_LOCATION,
    /** The request was cancelled before it completed (e.g. via {@link RTPAPI#cancel}). */
    CANCELLED,
    /**
     * The player already has a teleport in progress, so this request was
     * rejected without starting a second one. Mirrors the {@code /rtp}
     * command's {@code alreadyTeleporting} guard for the addon-facing API.
     */
    ALREADY_TELEPORTING,
    /**
     * The player is still within their teleport cooldown window, so this
     * request was rejected. Mirrors the {@code /rtp} command's cooldown guard
     * for the addon-facing API.
     */
    COOLDOWN,
    /**
     * The player has reached the configured per-player usage cap
     * ({@code lockAfterUses}) and is currently locked out. Mirrors the
     * {@code /rtp} command's lock-after-uses guard for the addon-facing API.
     */
    LOCKED,
    /**
     * The plugin is reloading its configuration, so new teleports are refused
     * until the reload completes. Mirrors the {@code /rtp} command's reloading
     * guard for the addon-facing API.
     */
    RELOADING,
    /**
     * The request was accepted and enrolled on the cross-server wait queue;
     * the player will be transferred to a peer backend and teleported there
     * when capacity is available. {@link #location()} is {@code null} because
     * the destination is resolved on the remote backend, not on this server.
     * This is an accepted (non-failure) outcome - see {@link #isQueued()}.
     */
    QUEUED,
    /** An unexpected error aborted the request; see {@link #message()}. */
    ERROR
  }

  private final Reason reason;
  private final RTPLocation location;
  private final String message;

  private RTPResult(Reason reason, RTPLocation location, String message) {
    this.reason = reason;
    this.location = location;
    this.message = message;
  }

  /**
   * Builds a success result.
   *
   * @param location the destination the player was teleported to; must not be {@code null}
   * @return a success result
   */
  public static RTPResult success(RTPLocation location) {
    return new RTPResult(Reason.SUCCESS, location, "ok");
  }

  /**
   * Builds a {@link Reason#QUEUED} result: the request was accepted and enrolled
   * on the cross-server wait queue. The player will be transferred to a peer
   * backend and teleported there; no local {@link #location()} exists.
   *
   * @param message a human-readable explanation (e.g. "queued for backend-a");
   *     may be {@code null}
   * @return a queued result
   */
  public static RTPResult queued(String message) {
    return new RTPResult(Reason.QUEUED, null, message);
  }

  /**
   * Builds a failure result.
   *
   * @param reason  the failure classification; must not be {@code null} or
   *     {@link Reason#SUCCESS}
   * @param message a human-readable explanation; may be {@code null}
   * @return a failure result
   */
  public static RTPResult failure(Reason reason, String message) {
    if (reason == null || reason == Reason.SUCCESS) {
      throw new IllegalArgumentException("failure reason must be a non-success Reason");
    }
    return new RTPResult(reason, null, message);
  }

  /**
   * Whether the teleport completed and the player was moved.
   *
   * @return {@code true} if {@link #reason()} is {@link Reason#SUCCESS}
   */
  public boolean isSuccess() {
    return reason == Reason.SUCCESS;
  }

  /**
   * Whether the request was accepted and enrolled on the cross-server wait
   * queue. A queued result is not a failure: the teleport will complete on a
   * remote backend after transfer.
   *
   * @return {@code true} if {@link #reason()} is {@link Reason#QUEUED}
   */
  public boolean isQueued() {
    return reason == Reason.QUEUED;
  }

  /**
   * The outcome classification.
   *
   * @return the reason; never {@code null}
   */
  public Reason reason() {
    return reason;
  }

  /**
   * The destination on success.
   *
   * @return the landing location, or {@code null} on failure
   */
  public RTPLocation location() {
    return location;
  }

  /**
   * A human-readable description of the outcome.
   *
   * @return the message, possibly {@code null}
   */
  public String message() {
    return message;
  }

  @Override
  public String toString() {
    return "RTPResult[" + reason + (location != null ? ", " + location : "")
        + (message != null ? ", \"" + message + "\"" : "") + ']';
  }
}
