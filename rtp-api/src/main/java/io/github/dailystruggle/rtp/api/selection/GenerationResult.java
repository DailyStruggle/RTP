package io.github.dailystruggle.rtp.api.selection;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;

/**
 * Immutable result produced by {@link ILocationGenerator} after a successful
 * location generation cycle.
 *
 * <p>A result is only produced when a candidate location has passed all configured
 * safety checks (biome filter, surface scan, claim/protection validation). The
 * {@link #attempts} field exposes the number of candidate positions evaluated
 * before this result was accepted, which is useful for diagnostic logging and
 * performance tuning.
 *
 * <p>If {@link #reservation} is non-null, the surrounding chunks are being held
 * loaded by a plugin chunk ticket until the teleport is dispatched, at which point
 * the reservation must be closed to release the ticket (REQ-API-ARCH-003).
 *
 * <p><b>Thread safety:</b> All fields are final (record semantics). Safe to read
 * from any thread.
 *
 * @param coords         the validated block position of the selected location
 * @param attempts       number of candidate positions evaluated before this location
 *                       was accepted; always &ge; 1
 * @param verifiedChunks the set of chunks that were loaded and verified during
 *                       generation; used to release tickets after the teleport
 * @param reservation    optional chunk reservation keeping the area loaded until
 *                       teleport dispatch; {@code null} when no reservation was made
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
