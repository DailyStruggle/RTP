package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.anvil.ChunkCoord;
import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Classify every chunk in {@code chunks} in one call. RTP passes all staged candidates of one
     * region file together, so providers backed by {@code .mca} reads should override this to
     * open the file once per batch (see {@code AnvilPrefilter#probeBatchSyncDetailed}).
     *
     * <p>Called off the server tick thread (S-005); may block on disk. Chunks missing from the
     * result are treated as {@link Decision#UNKNOWN}. The default delegates to
     * {@link #classify(RTPWorld, int, int)} per chunk.</p>
     *
     * @param world  the world the chunks belong to (non-null)
     * @param chunks chunks to classify (non-null; may contain duplicates)
     * @return decision per chunk (non-null)
     */
    default Map<ChunkCoord, Decision> classifyBatch(RTPWorld world, List<ChunkCoord> chunks) {
      Map<ChunkCoord, Decision> out = new LinkedHashMap<>();
      for (ChunkCoord c : chunks) {
        if (c != null && !out.containsKey(c)) out.put(c, classify(world, c.x(), c.z()));
      }
      return out;
    }
  }

  /** Install {@code provider} as the active anvil pre-filter. */
  void bind(Provider provider);

  /** @return the currently bound provider, or {@code null} when not installed. */
  Provider current();

  /** Unbind any provider; subsequent classifications fall back to chunk loads. */
  void clear();
}
