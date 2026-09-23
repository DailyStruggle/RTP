package io.github.dailystruggle.rtp.api.action;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.List;
import java.util.UUID;

/**
 * Handle to an active scripted action session (ADR-093).
 */
@PublicApi
public interface ActionSession {

  /**
   * The unique session identifier.
   */
  UUID sessionId();

  /**
   * The identifier of the action definition.
   */
  String actionId();

  /**
   * Immutable list of participant UUIDs in this session.
   */
  List<UUID> participants();

  /**
   * Elapsed time in seconds since session start.
   */
  long elapsedSeconds();

  /**
   * Remaining time in seconds before session timeout, or -1 if untimed.
   */
  long remainingSeconds();

  /**
   * Returns whether the session is currently active and armed.
   */
  boolean isActive();

  /**
   * Number of recorded boundary violations for the given participant.
   */
  int getViolations(UUID participantId);

  /**
   * Safely disarms the session, unregistering move watchers and running teardown.
   */
  void disarm();

  /**
   * Triggers a safe pull-back for the specified participant to their assigned safe slot.
   */
  void pullBack(UUID participantId);
}
