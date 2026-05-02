package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.world.RTPWorld;

/**
 * Single-binding registry for world-border integrations (ChunkyBorder, vanilla
 * world border, custom plugins) that constrain RTP's candidate range to "inside the
 * border" coordinates.
 *
 * <p>RTP consults the bound provider during region setup and per-attempt sampling.
 * When no provider is bound, RTP falls back to the configured region radius and the
 * platform-native {@code World#getWorldBorder()} where available (ADR-026).
 *
 * <p><b>Threading.</b> {@link Provider#isInside(RTPWorld, double, double)} may be
 * invoked from the async generation pipeline; implementations shall be thread-safe
 * and shall not perform blocking I/O.
 */
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
