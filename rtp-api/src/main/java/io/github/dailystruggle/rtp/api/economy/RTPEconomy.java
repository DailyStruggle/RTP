package io.github.dailystruggle.rtp.api.economy;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.UUID;

/**
 * Platform-agnostic economy SPI for charges and refunds (REQ-API-ARCH-001).
 * Methods must be safe to call off the main server thread.
 */
@PublicApi
public interface RTPEconomy {
  /**
   * Credits {@code money} to the player's account (e.g. for a refund on
   * teleport failure).
   *
   * @param playerId the UUID of the player to credit; must not be {@code null}
   * @param money    the amount to add; must be &gt; 0
   */
  void give(UUID playerId, double money);

  /**
   * Attempts to deduct {@code money} from the player's account.
   *
   * @param playerId the UUID of the player to charge; must not be {@code null}
   * @param money    the amount to deduct; must be &gt; 0
   * @return {@code true} if the player had sufficient funds and the deduction
   *         succeeded; {@code false} if the player cannot afford the cost
   */
  boolean take(UUID playerId, double money);

  /**
   * Returns the current balance of the player's account.
   *
   * @param playerId the UUID of the player to query; must not be {@code null}
   * @return the player's current balance; {@code 0.0} if the player has no account
   */
  double bal(UUID playerId);
}
