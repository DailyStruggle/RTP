package io.github.dailystruggle.rtp.api.world;

import org.checkerframework.checker.mustcall.qual.InheritableMustCall;
import org.checkerframework.checker.mustcall.qual.Owning;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
   * The future returned by the initial {@code keep(true)} invocation. Completes when the
   * underlying platform call ({@code addPluginChunkTicket}) has actually been applied.
   * Callers on off-thread contexts must {@link #awaitReady(long, TimeUnit)} before relying
   * on the chunk being pinned (ADR-015 Paper chunk-system-v2 follow-up).
   */
  private final CompletableFuture<Void> applyFuture;

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
    CompletableFuture<Void> f = world.setForceLoaded(chunkSet.getX(), chunkSet.getZ(), true);
    this.applyFuture = (f != null) ? f : CompletableFuture.completedFuture(null);
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
   * Returns the future that completes when the initial {@code addPluginChunkTicket} call has
   * actually been applied on the underlying platform. Callers on off-thread contexts must
   * await this before relying on the chunk being pinned.
   *
   * <p>ADR-015 Paper chunk-system-v2 follow-up: on the Bukkit/Paper adapter the raw
   * {@code addPluginChunkTicket} call is scheduled onto the primary thread when invoked
   * off-thread. Before this follow-up, the location generator would return from
   * {@code new ChunkReservation(...)} before the ticket was actually applied and the stale-
   * chunk guard (REQ-RTP-S-005) would then reject the still-not-pinned chunk.</p>
   *
   * @return the apply future; never {@code null}
   */
  public CompletableFuture<Void> readyFuture() {
    return applyFuture;
  }

  /**
   * Blocks the current thread (up to {@code timeout}) until the initial ticket application
   * has been confirmed. This is a bounded wait and MUST NOT be called on any tick thread
   * (REQ-RTP-S-005 prohibits blocking on tick threads, but scheduler / async threads are
   * permitted).
   *
   * @param timeout the maximum time to wait
   * @param unit    the time unit of the {@code timeout} argument
   * @return {@code true} if the ticket application completed within the timeout;
   *         {@code false} if the wait timed out (the caller SHOULD treat this as a
   *         ticket-apply failure and reject the candidate)
   * @throws InterruptedException if the current thread was interrupted while waiting
   */
  public boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
    try {
      applyFuture.get(timeout, unit);
      return true;
    } catch (TimeoutException te) {
      return false;
    } catch (java.util.concurrent.ExecutionException ee) {
      return false;
    }
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
