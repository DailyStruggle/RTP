package io.github.dailystruggle.rtp.api.world;

import org.checkerframework.checker.mustcall.qual.InheritableMustCall;
import org.checkerframework.checker.mustcall.qual.Owning;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;

import java.text.MessageFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Deterministic {@link AutoCloseable} wrapper tying a {@link ChunkSet}'s
 * force-loaded ticket to a bounded scope (S-002, ADR-012). Not thread-safe.
 */
// @InheritableMustCall is used instead of @MustCall because ChunkReservation is not final.
// It propagates the close() obligation to any subclass, ensuring the checker enforces
// the lifecycle contract on subclasses as well.
@InheritableMustCall("close")
public class ChunkReservation implements AutoCloseable {
  /**
   * Diagnostic logging helper for chunk-ticket lifecycle events (architecture diagram 03).
   * <p>All messages are emitted at {@link Level#FINE} or {@link Level#FINER} so production
   * deployments stay silent unless an operator opts in. These logs are intended for tracing
   * the {@code ReqTicket -> TrackRes -> CloseRes -> DropTicket -> UntrackRes} state machine
   * when investigating S-002 / S-005 regressions. Routed through {@link RTPServerAccessor}
   * (per project rule: no {@code java.util.logging.Logger.getLogger} in rtp-api/rtp-core).
   */
  private static void log(Level level, String pattern, Object... args) {
    RTPServerAccessor accessor = RTPAPI.serverAccessor;
    if (accessor == null) return;
    accessor.log(level, MessageFormat.format(pattern, args));
  }

  private static void log(Level level, String msg, Throwable t) {
    RTPServerAccessor accessor = RTPAPI.serverAccessor;
    if (accessor == null) return;
    accessor.log(level, msg, t);
  }

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
    // Architecture diagram 03 - ReqTicket: addPluginChunkTicket() invoked, apply future captured.
    log(Level.FINE, "ChunkReservation opened: world={0}, chunk=({1},{2}), size={3}",
            worldName(), chunkSet.getX(), chunkSet.getZ(),
            chunkSet.chunks() != null ? chunkSet.chunks().size() : -1);
  }

  private String worldName() {
    try {
      return world != null ? String.valueOf(world.name()) : "<null>";
    } catch (Throwable t) {
      return "<unknown>";
    }
  }

  /**
   * Keep or release the chunk tickets for this reservation.
   *
   * @param keep true to keep, false to release
   */
  public void keep(boolean keep) {
    log(Level.FINER, "ChunkReservation keep({0}): world={1}, chunk=({2},{3})",
            keep, worldName(), chunkSet.getX(), chunkSet.getZ());
    world.setForceLoaded(chunkSet.getX(), chunkSet.getZ(), keep);
  }

  /**
   * Future that completes when the initial chunk ticket has been applied.
   */
  public CompletableFuture<Void> readyFuture() {
    return applyFuture;
  }

  /**
   * Blocks current thread (up to {@code timeout}) until the initial ticket is applied.
   * MUST NOT be called on tick threads (REQ-RTP-S-005).
   *
   * @param timeout maximum time to wait
   * @param unit time unit of {@code timeout}
   * @return {@code true} if applied within timeout, {@code false} on timeout
   * @throws InterruptedException if interrupted while waiting
   */
  public boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
    try {
      applyFuture.get(timeout, unit);
      log(Level.FINER, "ChunkReservation awaitReady applied: world={0}, chunk=({1},{2})",
              worldName(), chunkSet.getX(), chunkSet.getZ());
      return true;
    } catch (TimeoutException te) {
      // ADR-015 follow-up: timed out waiting for addPluginChunkTicket to apply.
      log(Level.FINE,
              "ChunkReservation awaitReady timeout after {0} {1}: world={2}, chunk=({3},{4})",
              timeout, unit, worldName(), chunkSet.getX(), chunkSet.getZ());
      return false;
    } catch (java.util.concurrent.ExecutionException ee) {
      log(Level.FINE,
              "ChunkReservation awaitReady failed: world=" + worldName()
                      + ", chunk=(" + chunkSet.getX() + "," + chunkSet.getZ() + ")", ee);
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
    log(Level.FINER, "ChunkReservation ownership transferred: world={0}, chunk=({1},{2})",
            worldName(), chunkSet.getX(), chunkSet.getZ());
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
      // Architecture diagram 03 - CloseRes: try-finally release path; keep(false) drops the
      // plugin chunk ticket and feeds DropTicket -> UntrackRes downstream.
      log(Level.FINE, "ChunkReservation closed: world={0}, chunk=({1},{2})",
              worldName(), chunkSet.getX(), chunkSet.getZ());
      this.keep(false);
      this.transferred = true;
    } else {
      log(Level.FINER,
              "ChunkReservation close() no-op (already transferred/closed): world={0}, chunk=({1},{2})",
              worldName(), chunkSet.getX(), chunkSet.getZ());
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
