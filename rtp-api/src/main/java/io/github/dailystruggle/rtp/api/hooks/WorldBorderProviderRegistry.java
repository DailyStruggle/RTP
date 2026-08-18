package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import io.github.dailystruggle.rtp.api.world.RTPWorld;

/**
 * Single-binding registry for world-border integrations (ADR-026).
 * Constrains candidate coordinate generation to inside border bounds.
 */
@PublicApi
public interface WorldBorderProviderRegistry {

  /** Functional interface for world-border membership tests. */
  @FunctionalInterface
  interface Provider {
    /**
     * @return {@code true} if {@code (x, z)} lies inside the border for {@code world}.
     */
    boolean isInside(RTPWorld world, double x, double z);
  }

  /**
   * Install {@code provider} as the active world-border integration.
   *
   * @param provider non-null provider
   */
  void bind(Provider provider);

  /**
   * @return the currently bound provider, or {@code null} if no third-party
   *     integration is active and the platform default is used.
   */
  Provider current();

  /** Restore the platform default (no third-party border). */
  void clear();
}
