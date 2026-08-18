package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import io.github.dailystruggle.rtp.api.economy.RTPEconomy;

/**
 * Single-binding registry for platform-agnostic {@link RTPEconomy} (ADR-026).
 * Manages active economy provider used to charge/refund teleport requests.
 */
@PublicApi
public interface EconomyProviderRegistry {

  /**
   * Install {@code provider} as the active economy implementation.
   *
   * @param provider non-null economy implementation
   */
  void bind(RTPEconomy provider);

  /**
   * @return the currently bound provider, or {@code null} if none is bound and the
   *     platform default no-op is in effect.
   */
  RTPEconomy current();

  /** Unbind any third-party provider; restores the platform default. */
  void clear();
}
