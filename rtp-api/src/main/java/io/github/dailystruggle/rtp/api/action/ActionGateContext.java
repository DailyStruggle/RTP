package io.github.dailystruggle.rtp.api.action;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.Objects;
import java.util.UUID;

/**
 * Contextual evaluation snapshot for an action gate (ADR-093).
 *
 * @param sessionId       the action session ID
 * @param actionId        the action identifier
 * @param participantId   the active or relevant participant ID (may be null if session-level)
 * @param elapsedSeconds  elapsed time in seconds since session start
 * @param remainingSeconds remaining time in seconds before session timeout
 * @param violations      number of recorded boundary violations for the participant
 * @param inBounds        whether the participant is currently within bounds
 * @param distanceSqFromAnchor squared distance from the session anchor
 */
@PublicApi
public record ActionGateContext(
    UUID sessionId,
    String actionId,
    UUID participantId,
    long elapsedSeconds,
    long remainingSeconds,
    int violations,
    boolean inBounds,
    double distanceSqFromAnchor) {

  public ActionGateContext {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(actionId, "actionId must not be null");
  }
}
