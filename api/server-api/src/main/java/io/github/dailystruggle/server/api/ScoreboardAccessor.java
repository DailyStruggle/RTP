package io.github.dailystruggle.server.api;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Platform-neutral scoreboard tag, objective, and score access contract.
 *
 * @governedBy ADR-115
 * @governedBy ADR-116
 */
public interface ScoreboardAccessor {
  /**
   * Returns the set of Minecraft scoreboard tags currently applied to the given player.
   *
   * @param playerId player UUID
   * @return unmodifiable set of scoreboard tags; empty if unknown or player offline
   */
  default Set<String> getScoreboardTags(UUID playerId) {
    return Collections.emptySet();
  }

  /**
   * Adds a scoreboard tag to the player.
   *
   * @param playerId player UUID
   * @param tag the tag to add
   * @return {@code true} if added, {@code false} if already present or player offline
   */
  default boolean addScoreboardTag(UUID playerId, String tag) {
    return false;
  }

  /**
   * Removes a scoreboard tag from the player.
   *
   * @param playerId player UUID
   * @param tag the tag to remove
   * @return {@code true} if removed, {@code false} if not present or player offline
   */
  default boolean removeScoreboardTag(UUID playerId, String tag) {
    return false;
  }

  /**
   * Retrieves the current scoreboard score for the specified player and objective name.
   *
   * @param playerId the player's unique identifier; must not be {@code null}
   * @param objective the objective name; must not be {@code null}
   * @return the player's score, or {@code null} if the objective or score does not exist
   */
  default Integer getScoreboardScore(UUID playerId, String objective) {
    return null;
  }

  /**
   * Sets a scoreboard score for the player on a given objective.
   *
   * @param playerId player UUID
   * @param objective the objective name
   * @param score the score value
   */
  default void setScoreboardScore(UUID playerId, String objective, int score) {}

  /**
   * Resets scores for the player on a given objective, or across all objectives if {@code null}.
   *
   * @param playerId player UUID
   * @param objective objective name, or {@code null} to reset all
   */
  default void resetScoreboardScore(UUID playerId, String objective) {}

  /**
   * Resets scores for the player on a given objective, or across all objectives if {@code null}.
   *
   * <p>Delegates to {@link #resetScoreboardScore(UUID, String)} by default.
   *
   * @param playerId player UUID
   * @param objective objective name, or {@code null} to reset all
   */
  default void resetScoreboardScores(UUID playerId, String objective) {
    resetScoreboardScore(playerId, objective);
  }

  /**
   * Returns whether the host exposes a scoreboard objective.
   *
   * @param objective the objective name; must not be {@code null}
   * @return {@link Optional#of(Object)} containing {@code true} when present or {@code false} when
   *     absent, or {@link Optional#empty()} when the host cannot determine whether it is present
   */
  default Optional<Boolean> hasScoreboardObjective(String objective) {
    return Optional.empty();
  }

  /**
   * Ensures a scoreboard objective exists.
   *
   * @param objective the objective name
   * @param criteria the objective criteria (defaults to {@code "dummy"} if {@code null})
   */
  default void ensureScoreboardObjective(String objective, String criteria) {}
}
