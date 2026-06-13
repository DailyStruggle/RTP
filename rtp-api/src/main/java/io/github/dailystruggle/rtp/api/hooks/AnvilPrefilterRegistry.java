package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import io.github.dailystruggle.rtp.api.world.RTPWorld;

/**
 * Single-binding SPI for the optional Anvil-NBT pre-filter (see ADR-016 and the
 * {@code rtp-anvil} module).
 *
 * <p>This registry replaces the historical reflective lookup of
 * {@code io.github.dailystruggle.rtp.anvil.AnvilRegionByteCache} performed by
 * {@code ScanTask}. Modules that ship anvil-style fast pre-filters bind their
 * provider during their own initialisation; consumers in {@code rtp-core} read it
 * via the bound {@link Provider} or fall back to a per-attempt chunk load when
 * unbound (ADR-026).
 *
 * <p><b>Threading.</b> {@link Provider} methods are called from the scan pipeline
 * (off the main thread on Folia) and from per-attempt verification. Implementations
 * shall be thread-safe.
 */
@PublicApi
public interface AnvilPrefilterRegistry {

  /** Functional interface for the anvil pre-filter binding. */
  interface Provider {
    /**
     * Quickly classify a chunk column as definitely-rejected, definitely-accepted,
     * or unknown. The {@code unknown} case forces RTP to load the chunk and run a
     * full pipeline pass.
     */
    enum Decision { ACCEPT, REJECT, UNKNOWN }

    /**
     * @param world the world the chunk belongs to (non-null)
     * @param cx    chunk X
     * @param cz    chunk Z
     * @return a non-null decision for the chunk based on cached NBT/region data
     */
    Decision classify(RTPWorld world, int cx, int cz);
  }

  /** Install {@code provider} as the active anvil pre-filter. */
  void bind(Provider provider);

  /** @return the currently bound provider, or {@code null} when not installed. */
  Provider current();

  /** Unbind any provider; subsequent classifications fall back to chunk loads. */
  void clear();
}
