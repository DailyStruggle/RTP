package io.github.dailystruggle.rtp.api.claim;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.Optional;
import java.util.UUID;

/**
 * SPI provider for resolving player claim or faction boundaries.
 *
 * <p>Implemented by claim integrations (e.g. Factions, Towny, Lands, WorldGuard) and registered
 * via {@code RTPAPI.hooks().claimBoundaries().register(provider)}.
 */
@PublicApi
public interface ClaimBoundaryProvider {

  /**
   * Namespace identifying the claim system (e.g. {@code "factions"}, {@code "towny"}, {@code "lands"}).
   *
   * @return provider namespace, lowercase, never {@code null}
   */
  String namespace();

  /**
   * Priority of this provider when multiple providers match or when automatic resolution is used.
   * Higher priority values are queried first.
   *
   * @return priority integer; default is {@code 0}
   */
  default int priority() {
    return 0;
  }

  /**
   * Resolves the primary claim or territory boundary for the given player in the specified world.
   *
   * @param playerId  UUID of the player
   * @param worldName world identifier
   * @return non-null {@link Optional} containing the claim boundary if the player holds a claim in that world
   */
  Optional<ClaimBoundary> getBoundary(UUID playerId, String worldName);

  /**
   * Resolves the claim boundary at the specified coordinates in the specified world.
   *
   * @param worldName world identifier
   * @param x         block X coordinate
   * @param z         block Z coordinate
   * @return non-null {@link Optional} containing the claim boundary if the coordinates fall in a claim
   */
  default Optional<ClaimBoundary> getBoundaryAt(String worldName, int x, int z) {
    return Optional.empty();
  }
}
