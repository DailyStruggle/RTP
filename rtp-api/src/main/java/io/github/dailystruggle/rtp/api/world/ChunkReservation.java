package io.github.dailystruggle.rtp.api.world;

import org.checkerframework.checker.mustcall.qual.InheritableMustCall;
import org.checkerframework.checker.mustcall.qual.Owning;

/**
 * A deterministic {@link AutoCloseable} lifecycle wrapper that ties a {@link ChunkSet}'s
 * force-loaded ticket to a bounded scope.
 *
 * <p>Every chunk loaded for teleport validation must be held via a {@code ChunkReservation}
 * opened in a {@code try-with-resources} block. This guarantees that the force-loaded ticket
 * is released even if an exception is thrown mid-validation, preventing the permanent
 * force-load leak described in hazard H-004.
 *
 * <p><b>Invariant:</b> Between construction and the first call to {@link #close()} or
 * {@link #transferOwnership()}, the underlying chunks are force-loaded in the target world.
 * After {@link #close()} or a successful {@link #transferOwnership()}, the reservation no
 * longer holds any ticket.
 *
 * <p><b>Internal use only:</b> {@code ChunkReservation} is not intended to be constructed
 * by addon code. Addons should consume {@code GenerationResult.reservation()} rather than
 * managing chunk tickets directly. See ADR-012 and hazard H-010.
 *
 * <p><b>Thread safety:</b> Instances are not thread-safe. A reservation must be created,
 * used, and closed on the same thread or with external synchronisation.
 */
// @InheritableMustCall is used instead of @MustCall because ChunkReservation is not final.
// It propagates the close() obligation to any subclass, ensuring the checker enforces
// the lifecycle contract on subclasses as well.
@InheritableMustCall("close")
public class ChunkReservation implements AutoCloseable {
  // @Owning declares that this field holds the resource whose lifecycle this class manages.
  // The Checker Framework will verify that close() releases it on all paths.
  private final @Owning ChunkSet chunkSet;
  private final RTPWorld<?> world;
  private boolean transferred = false;

  /**
   * Secure chunk tickets upon reservation.
   *
   * @param chunkSet the chunk set to reserve
   * @param world the world the chunks are in
   */
  // @Owning on the parameter tells the checker that ownership of chunkSet is transferred
  // into this reservation; the caller is no longer responsible for closing it.
  public ChunkReservation(@Owning ChunkSet chunkSet, RTPWorld<?> world) {
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
   * <p>{@code @Owning} on the return type signals to the Checker Framework that the caller
   * receives ownership and becomes responsible for the resource's lifecycle.
   *
   * @return the reserved ChunkSet
   */
  public @Owning ChunkSet transferOwnership() {
    this.transferred = true;
    return this.chunkSet;
  }

  /**
   * Release chunk tickets if ownership was not transferred.
   *
   * <p>This method satisfies the {@code @InheritableMustCall("close")} obligation declared
   * on the class. The Checker Framework recognises {@code close()} as the fulfillment
   * method automatically when used in a try-with-resources block.
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
