package io.github.dailystruggle.rtp.api.hooks;

import java.util.UUID;

/**
 * Single-binding SPI describing whether a player is currently "in combat" for the
 * optional PvP / combat-tag pre-flight gate (ROADMAP Tier 2).
 *
 * <p>Modelled on {@link AnvilPrefilterRegistry}: RTP ships a native fallback
 * (damage-event tracking inside the platform adapter) that answers the question
 * when no provider is bound. A third-party combat-tag plugin (e.g. PvPManager,
 * SimpleCombatLog, CombatLogX) may {@link #bind(Provider)} its own authority,
 * <i>replacing</i> the native check exactly the way an external biome/anvil
 * pre-filter replaces the built-in one. Calling {@link #clear()} restores the
 * native fallback.
 *
 * <p><b>Threading.</b> {@link Provider#isInCombat(UUID)} may be invoked from the
 * command thread and from the teleport pipeline; implementations shall be
 * thread-safe and non-blocking (they must not perform synchronous I/O or block a
 * region/tick thread).
 *
 * <p><b>Failure mode.</b> A provider that throws is treated by RTP as "not in
 * combat" and logged once at WARNING; the gate never silently swallows the error
 * (REQ-RTP-S-004) and the teleport is not blocked by a buggy integration.
 */
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
