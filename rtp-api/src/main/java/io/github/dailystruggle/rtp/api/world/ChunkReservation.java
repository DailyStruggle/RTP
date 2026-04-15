package io.github.dailystruggle.rtp.api.world;

/**
 * A deterministic AutoCloseable lifecycle wrapper for ChunkSet at the fast cache boundary.
 */
public class ChunkReservation implements AutoCloseable {
  private final ChunkSet chunkSet;
  private final RTPWorld<?> world;
  private boolean transferred = false;

  /**
   * Secure chunk tickets upon reservation.
   *
   * @param chunkSet the chunk set to reserve
   * @param world the world the chunks are in
   */
  public ChunkReservation(ChunkSet chunkSet, RTPWorld<?> world) {
    this.chunkSet = chunkSet;
    this.world = world;
    this.keep(true);
  }

  /**
   * Keep or release the chunk tickets for this reservation.
   *
   * @param keep true to keep, false to release
   */
  public void keep(boolean keep) {
    world.setForceLoaded(chunkSet.getX(), chunkSet.getZ(), keep);
  }

  /**
   * Re-apply the force-loaded state without affecting the ticket counter
   */
  public void refresh() {
    if (world != null) {
      world.refreshForceLoaded(chunkSet.getX(), chunkSet.getZ());
    }
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
      this.keep(false);
      this.transferred = true;
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
