package io.github.dailystruggle.rtp.api.action;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.Objects;
import java.util.UUID;

/**
 * Outcome of an action trigger invocation (ADR-093).
 *
 * @param success      whether the action session was successfully allocated, armed, and started
 * @param sessionId    the unique session ID if started, or null if failed
 * @param failureReason human-readable failure reason if failed, or null if success
 */
@PublicApi
public record ActionSessionResult(boolean success, UUID sessionId, String failureReason) {

  public static ActionSessionResult success(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    return new ActionSessionResult(true, sessionId, null);
  }

  public static ActionSessionResult failure(String failureReason) {
    Objects.requireNonNull(failureReason, "failureReason must not be null");
    return new ActionSessionResult(false, null, failureReason);
  }
}
