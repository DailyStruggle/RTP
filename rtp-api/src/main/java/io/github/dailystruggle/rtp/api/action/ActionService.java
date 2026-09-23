package io.github.dailystruggle.rtp.api.action;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Platform-neutral entry point for declarative scripted actions (ADR-093).
 *
 * <p>Callers invoke {@link #trigger(String, java.util.List, ActionContext)} to place, confine,
 * and orchestrate participants through the action lifecycle.
 */
@PublicApi
public interface ActionService {

  /**
   * Triggers an action by identifier for the given participants.
   *
   * @param actionId     the identifier of the action definition
   * @param participants list of participant UUIDs; must not be null or empty
   * @param context      invocation context; must not be null
   * @return future completing off-tick with the session result; never null (S-004, S-005)
   */
  CompletableFuture<ActionSessionResult> trigger(
      String actionId, java.util.List<UUID> participants, ActionContext context);

  /**
   * Retrieves an active action session by session ID.
   *
   * @param sessionId the session identifier
   * @return optional containing the active session if present
   */
  Optional<ActionSession> getSession(UUID sessionId);

  /**
   * Finds the active session for a participant, if any.
   *
   * @param participantId the participant UUID
   * @return optional containing the participant's active session if present
   */
  Optional<ActionSession> getSessionForParticipant(UUID participantId);

  /**
   * Disarms an active session by session ID.
   *
   * @param sessionId the session identifier
   */
  void disarm(UUID sessionId);

  /**
   * Registers a custom programmatic predicate gate for third-party plugins (ADR-093 Section 3).
   *
   * @param name      the gate identifier (referenced in action configs under {@code predicate: <name>})
   * @param predicate the predicate evaluating the gate context
   */
  void registerPredicate(String name, Predicate<ActionGateContext> predicate);

  /**
   * Returns all loaded action identifiers.
   *
   * @return immutable set of loaded action IDs
   */
  Set<String> getActionIds();
}
