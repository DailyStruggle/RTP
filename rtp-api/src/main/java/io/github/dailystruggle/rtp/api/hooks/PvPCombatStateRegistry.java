package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.UUID;

/**
 * Single-binding SPI describing whether a player is in combat for PvP pre-flight checks.
 * Providers must be thread-safe and non-blocking. Exceptions are logged and fail-open (REQ-RTP-S-004).
 */
@PublicApi
public interface PvPCombatStateRegistry {

  /** Functional interface for the combat-state binding. */
  @FunctionalInterface
  interface Provider {
    /**
     * @param player the player's UUID (non-null)
     * @return {@code true} if the player is currently combat-tagged / in combat
     */
    boolean isInCombat(UUID player);
  }

  /** Install {@code provider} as the active combat-state authority (non-null). */
  void bind(Provider provider);

  /** @return the currently bound provider, or {@code null} when the native fallback is in effect. */
  Provider current();

  /** Unbind any provider; subsequent queries fall back to RTP's native damage tracker. */
  void clear();
}
