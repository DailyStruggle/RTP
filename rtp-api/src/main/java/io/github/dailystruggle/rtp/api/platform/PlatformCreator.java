package io.github.dailystruggle.rtp.api.platform;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import io.github.dailystruggle.rtp.api.world.RTPLocation;

import java.util.concurrent.CompletableFuture;

/**
 * Platform-neutral SPI for creating arrival platforms at teleport destinations.
 * Registered via {@code RTPHooks#platformCreator()} (ADR-026, ADR-058).
 * Two-phase lifecycle: async {@link #prepare} off-region, synchronous block writes
 * on the region-owning thread in {@link #createPlatform} (S-004, S-005).
 */
@PublicApi
public interface PlatformCreator {

  /**
   * A short, stable identifier for this creator, used in S-004 audit log lines so an
   * operator can see which platform creator handled (or declined) a teleport.
   *
   * @return a non-null identifier; defaults to the implementation's simple class name
   */
  default String creatorName() {
    return getClass().getSimpleName();
  }

  /**
   * Phase 1: Async preparation off the region thread (file reads, decoding; S-005).
   *
   * @param at confirmed safe arrival location; never null
   * @return future completing with prepared handle or null if declined/failed
   */
  default CompletableFuture<?> prepare(RTPLocation at) {
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Phase 2: Synchronous platform placement on the region-owning thread.
   *
   * @param at       confirmed arrival location; never null
   * @param prepared handle from {@link #prepare}, or null
   * @return true if platform was created; false to fall back to default emergency disc
   */
  default boolean createPlatform(RTPLocation at, Object prepared) {
    return createPlatform(at);
  }

  /**
   * Convenience single-phase placement for creators with no async preparation.
   *
   * @param at confirmed arrival location; never null
   * @return true if platform was created; false to fall back
   */
  default boolean createPlatform(RTPLocation at) {
    return false;
  }
}
