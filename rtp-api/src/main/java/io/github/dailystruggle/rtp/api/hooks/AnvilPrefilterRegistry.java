package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import io.github.dailystruggle.rtp.api.world.RTPWorld;

/**
 * Single-binding SPI for optional Anvil-NBT pre-filter (ADR-016, ADR-026).
 *
 * <p>When bound, provides fast NBT-based chunk rejection before chunk loading.
 * Thread safety: {@link Provider} implementations must be thread-safe.
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
