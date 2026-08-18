package io.github.dailystruggle.rtp.api.selection;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;

/**
 * Immutable result produced by {@link ILocationGenerator} after a successful location generation cycle.
 * Thread-safe record.
 *
 * @param coords         validated block position
 * @param attempts       candidate evaluation count (&ge; 1)
 * @param verifiedChunks chunks loaded and verified during generation
 * @param reservation    optional reservation holding chunks loaded until dispatch (REQ-API-ARCH-003)
 * @see ILocationGenerator
 * @see ChunkReservation
 */
public record GenerationResult(RTPCoords coords, long attempts, ChunkSet verifiedChunks, ChunkReservation reservation) {
  /**
   * Convenience constructor for results that do not hold a chunk reservation.
   *
   * @param coords         the validated block position
   * @param attempts       number of candidates evaluated; must be &ge; 1
   * @param verifiedChunks the chunks loaded and verified during generation
   */
  public GenerationResult(RTPCoords coords, long attempts, ChunkSet verifiedChunks) {
    this(coords, attempts, verifiedChunks, null);
  }
}
