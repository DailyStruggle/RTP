package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import io.github.dailystruggle.rtp.api.platform.PlatformCreator;

/**
 * Single-binding registry for the arrival {@linkplain PlatformCreator platform creator} (ADR-058).
 * Allows addons to override arrival structure generation. Thread-safe.
 */
@PublicApi
public interface PlatformCreatorRegistry {

  /**
   * Install {@code creator} as the active platform creator, overriding the platform default.
   *
   * @param creator non-null platform creator
   */
  void bind(PlatformCreator creator);

  /**
   * @return the currently bound creator, or {@code null} if no addon override is active and
   *     the platform default is used.
   */
  PlatformCreator current();

  /** Restore the platform default (no addon override). */
  void clear();
}
