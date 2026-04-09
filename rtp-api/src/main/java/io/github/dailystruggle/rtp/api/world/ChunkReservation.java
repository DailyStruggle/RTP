package io.github.dailystruggle.rtp.api.world;

/**
 * A deterministic AutoCloseable lifecycle wrapper for ChunkSet at the fast cache boundary.
 */
public class ChunkReservation implements AutoCloseable {
  private final ChunkSet chunkSet;
  private final RTPWorld<?> world;
  private final RTPChunkManager chunkManager;
  private boolean transferred = false;

  /**
   * Secure chunk tickets upon reservation.
   *
   * @param chunkSet the chunk set to reserve
   * @param world the world the chunks are in
   * @param chunkManager the chunk manager to use for ticket management
   */
  public ChunkReservation(ChunkSet chunkSet, RTPWorld<?> world, RTPChunkManager chunkManager) {
    this.chunkSet = chunkSet;
    this.world = world;
    this.chunkManager = chunkManager;
    this.chunkManager.keep(chunkSet, true, world);
  }

  /**
   * Transfer ownership of the ChunkSet, preventing the reservation from releasing tickets when closed.
   *
   * @return the reserved ChunkSet
   */
  public ChunkSet transferOwnership() {
    this.transferred = true;
    return this.chunkSet;
  }

  /**
   * Release chunk tickets if ownership was not transferred.
   */
  @Override
  public void close() {
    if (!transferred) {
      chunkManager.keep(chunkSet, false, world);
    }
  }

  /**
   * Get the ChunkSet without transferring ownership.
   *
   * @return the ChunkSet
   */
  public ChunkSet getChunkSet() {
    return chunkSet;
  }
}
