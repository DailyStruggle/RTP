package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import io.github.dailystruggle.rtp.api.claim.ClaimBoundary;
import io.github.dailystruggle.rtp.api.claim.ClaimBoundaryProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Registry of {@link ClaimBoundaryProvider} instances (ADR-026).
 *
 * <p>Allows multiple claim integrations (Factions, Towny, Lands, etc.) to register territory
 * lookup services without collision.
 */
@PublicApi
public interface ClaimBoundaryRegistry {

  /**
   * Register a claim boundary provider.
   *
   * @param provider non-null provider
   * @return auto-closeable unregister handle
   */
  AutoCloseable register(ClaimBoundaryProvider provider);

  /**
   * Unregister a previously registered claim boundary provider.
   *
   * @param provider provider to unregister
   * @return {@code true} if removed, {@code false} if not found
   */
  boolean unregister(ClaimBoundaryProvider provider);

  /**
   * Resolve a claim boundary for a player.
   *
   * @param playerId  player UUID
   * @param worldName target world name
   * @param namespace target claim system namespace (e.g. {@code "factions"}), or {@code null}/{@code "auto"} for highest-priority
   * @return non-null {@link Optional} containing the claim boundary if found
   */
  Optional<ClaimBoundary> resolve(UUID playerId, String worldName, String namespace);

  /**
   * Resolves a claim boundary using automatic priority lookup.
   *
   * @param playerId  player UUID
   * @param worldName target world name
   * @return non-null {@link Optional} containing the claim boundary if found
   */
  default Optional<ClaimBoundary> resolve(UUID playerId, String worldName) {
    return resolve(playerId, worldName, null);
  }

  /**
   * Unmodifiable snapshot of currently registered providers in descending priority order.
   *
   * @return list of providers
   */
  List<ClaimBoundaryProvider> providers();

  /**
   * Remove all registered providers.
   */
  void clear();

  /**
   * @return number of currently registered providers
   */
  int size();
}
