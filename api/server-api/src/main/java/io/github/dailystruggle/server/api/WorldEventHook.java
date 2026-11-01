package io.github.dailystruggle.server.api;

import java.util.function.Consumer;

/**
 * Platform SPI for subscribing to world and block interaction events (ADR-049, ADR-115).
 *
 * <p>Covers player right-click block interactions, block breaks, leaf decay, chunk loads, and
 * sapling/structure growth so core and sibling plugins can implement cross-platform game logic
 * without importing platform event classes. Subscriptions return an {@link AutoCloseable}
 * unregistration handle.
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public interface WorldEventHook {

  /**
   * Subscribe to player block-interaction (right-click block) events.
   *
   * @param handler invoked with the interaction context; must not be {@code null}
   * @return a closeable that unregisters this handler when closed; never {@code null}
   */
  AutoCloseable onPlayerInteractBlock(Consumer<PlayerInteractBlockContext> handler);

  /**
   * Subscribe to block break events.
   *
   * @param handler invoked with the block break context; must not be {@code null}
   * @return a closeable that unregisters this handler when closed; never {@code null}
   */
  AutoCloseable onBlockBreak(Consumer<BlockBreakContext> handler);

  /**
   * Subscribe to natural leaf decay events.
   *
   * @param handler invoked with the leaf decay context; must not be {@code null}
   * @return a closeable that unregisters this handler when closed; never {@code null}
   */
  AutoCloseable onLeavesDecay(Consumer<LeavesDecayContext> handler);

  /**
   * Subscribe to chunk load events.
   *
   * @param handler invoked with the chunk load context; must not be {@code null}
   * @return a closeable that unregisters this handler when closed; never {@code null}
   */
  AutoCloseable onChunkLoad(Consumer<ChunkLoadContext> handler);

  /**
   * Subscribe to vanilla sapling or tree-feature growth events.
   *
   * @param handler invoked with the sapling grow context; must not be {@code null}
   * @return a closeable that unregisters this handler when closed; never {@code null}
   */
  AutoCloseable onSaplingGrow(Consumer<SaplingGrowContext> handler);

  /**
   * Subscribe to player bed-enter events.
   *
   * @param handler invoked with the bed-enter context; must not be {@code null}
   * @return a closeable that unregisters this handler when closed; never {@code null}
   */
  default AutoCloseable onPlayerBedEnter(Consumer<PlayerBedEnterContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    return () -> {};
  }

  /**
   * Subscribe to player bed-leave events.
   *
   * @param handler invoked with the bed-leave context; must not be {@code null}
   * @return a closeable that unregisters this handler when closed; never {@code null}
   */
  default AutoCloseable onPlayerBedLeave(Consumer<PlayerBedLeaveContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    return () -> {};
  }

  /**
   * Subscribe to world time-skip events (e.g. night skip).
   *
   * @param handler invoked with the time-skip context; must not be {@code null}
   * @return a closeable that unregisters this handler when closed; never {@code null}
   */
  default AutoCloseable onWorldTimeSkip(Consumer<WorldTimeSkipContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    return () -> {};
  }
}
