package io.github.dailystruggle.rtp.api.economy;

import java.util.UUID;

/**
 * Platform-agnostic economy abstraction used to charge and refund players for
 * random teleport requests.
 *
 * <p>Implementations bridge to a concrete economy plugin (e.g. Vault + EssentialsX)
 * and are injected into the plugin at startup. When no economy plugin is present,
 * the server accessor returns a no-op implementation so that the teleport pipeline
 * never throws a {@link NullPointerException}.
 *
 * <p><b>Thread safety:</b> All methods may be called from the async generation
 * pipeline; implementations must be safe to invoke off the main server thread
 * (REQ-API-ARCH-001).
 */
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
