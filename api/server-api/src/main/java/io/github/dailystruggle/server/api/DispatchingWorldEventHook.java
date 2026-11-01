package io.github.dailystruggle.server.api;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe {@link WorldEventHook} base that manages world/block event subscriber lists (ADR-049,
 * ADR-115).
 *
 * <p>Dispatches with per-subscriber exception isolation so a faulty subscriber cannot break fan-out.
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public class DispatchingWorldEventHook implements WorldEventHook {

  private final CopyOnWriteArrayList<Consumer<PlayerInteractBlockContext>> interactHandlers =
      new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<Consumer<BlockBreakContext>> blockBreakHandlers =
      new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<Consumer<LeavesDecayContext>> leavesDecayHandlers =
      new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<Consumer<ChunkLoadContext>> chunkLoadHandlers =
      new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<Consumer<SaplingGrowContext>> saplingGrowHandlers =
      new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<Consumer<PlayerBedEnterContext>> bedEnterHandlers =
      new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<Consumer<PlayerBedLeaveContext>> bedLeaveHandlers =
      new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<Consumer<WorldTimeSkipContext>> timeSkipHandlers =
      new CopyOnWriteArrayList<>();

  @Override
  public AutoCloseable onPlayerInteractBlock(Consumer<PlayerInteractBlockContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    interactHandlers.add(handler);
    return () -> interactHandlers.remove(handler);
  }

  @Override
  public AutoCloseable onBlockBreak(Consumer<BlockBreakContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    blockBreakHandlers.add(handler);
    return () -> blockBreakHandlers.remove(handler);
  }

  @Override
  public AutoCloseable onLeavesDecay(Consumer<LeavesDecayContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    leavesDecayHandlers.add(handler);
    return () -> leavesDecayHandlers.remove(handler);
  }

  @Override
  public AutoCloseable onChunkLoad(Consumer<ChunkLoadContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    chunkLoadHandlers.add(handler);
    return () -> chunkLoadHandlers.remove(handler);
  }

  @Override
  public AutoCloseable onSaplingGrow(Consumer<SaplingGrowContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    saplingGrowHandlers.add(handler);
    return () -> saplingGrowHandlers.remove(handler);
  }

  @Override
  public AutoCloseable onPlayerBedEnter(Consumer<PlayerBedEnterContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    bedEnterHandlers.add(handler);
    return () -> bedEnterHandlers.remove(handler);
  }

  @Override
  public AutoCloseable onPlayerBedLeave(Consumer<PlayerBedLeaveContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    bedLeaveHandlers.add(handler);
    return () -> bedLeaveHandlers.remove(handler);
  }

  @Override
  public AutoCloseable onWorldTimeSkip(Consumer<WorldTimeSkipContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    timeSkipHandlers.add(handler);
    return () -> timeSkipHandlers.remove(handler);
  }

  /**
   * Invoke every registered player block-interaction handler.
   *
   * @param context interaction context; ignored if {@code null}
   */
  public void firePlayerInteractBlock(PlayerInteractBlockContext context) {
    if (context == null) return;
    for (Consumer<PlayerInteractBlockContext> h : interactHandlers) {
      try {
        h.accept(context);
      } catch (Throwable ignored) {
        // intentional: a faulty subscriber must not break fan-out
      }
    }
  }

  /**
   * Invoke every registered block-break handler.
   *
   * @param context block break context; ignored if {@code null}
   */
  public void fireBlockBreak(BlockBreakContext context) {
    if (context == null) return;
    for (Consumer<BlockBreakContext> h : blockBreakHandlers) {
      try {
        h.accept(context);
      } catch (Throwable ignored) {
        // intentional: a faulty subscriber must not break fan-out
      }
    }
  }

  /**
   * Invoke every registered leaf-decay handler.
   *
   * @param context leaf decay context; ignored if {@code null}
   */
  public void fireLeavesDecay(LeavesDecayContext context) {
    if (context == null) return;
    for (Consumer<LeavesDecayContext> h : leavesDecayHandlers) {
      try {
        h.accept(context);
      } catch (Throwable ignored) {
        // intentional: a faulty subscriber must not break fan-out
      }
    }
  }

  /**
   * Invoke every registered chunk-load handler.
   *
   * @param context chunk load context; ignored if {@code null}
   */
  public void fireChunkLoad(ChunkLoadContext context) {
    if (context == null) return;
    for (Consumer<ChunkLoadContext> h : chunkLoadHandlers) {
      try {
        h.accept(context);
      } catch (Throwable ignored) {
        // intentional: a faulty subscriber must not break fan-out
      }
    }
  }

  /**
   * Invoke every registered sapling-grow handler.
   *
   * @param context sapling grow context; ignored if {@code null}
   */
  public void fireSaplingGrow(SaplingGrowContext context) {
    if (context == null) return;
    for (Consumer<SaplingGrowContext> h : saplingGrowHandlers) {
      try {
        h.accept(context);
      } catch (Throwable ignored) {
        // intentional: a faulty subscriber must not break fan-out
      }
    }
  }

  /**
   * Invoke every registered player bed-enter handler.
   *
   * @param context bed-enter context; ignored if {@code null}
   */
  public void firePlayerBedEnter(PlayerBedEnterContext context) {
    if (context == null) return;
    for (Consumer<PlayerBedEnterContext> h : bedEnterHandlers) {
      try {
        h.accept(context);
      } catch (Throwable ignored) {
        // intentional: a faulty subscriber must not break fan-out
      }
    }
  }

  /**
   * Invoke every registered player bed-leave handler.
   *
   * @param context bed-leave context; ignored if {@code null}
   */
  public void firePlayerBedLeave(PlayerBedLeaveContext context) {
    if (context == null) return;
    for (Consumer<PlayerBedLeaveContext> h : bedLeaveHandlers) {
      try {
        h.accept(context);
      } catch (Throwable ignored) {
        // intentional: a faulty subscriber must not break fan-out
      }
    }
  }

  /**
   * Invoke every registered world time-skip handler.
   *
   * @param context time-skip context; ignored if {@code null}
   */
  public void fireWorldTimeSkip(WorldTimeSkipContext context) {
    if (context == null) return;
    for (Consumer<WorldTimeSkipContext> h : timeSkipHandlers) {
      try {
        h.accept(context);
      } catch (Throwable ignored) {
        // intentional: a faulty subscriber must not break fan-out
      }
    }
  }

  /**
   * Returns whether at least one handler is registered for any world event.
   *
   * @return {@code true} iff at least one handler is registered
   */
  public boolean hasSubscribers() {
    return !interactHandlers.isEmpty()
        || !blockBreakHandlers.isEmpty()
        || !leavesDecayHandlers.isEmpty()
        || !chunkLoadHandlers.isEmpty()
        || !saplingGrowHandlers.isEmpty()
        || !bedEnterHandlers.isEmpty()
        || !bedLeaveHandlers.isEmpty()
        || !timeSkipHandlers.isEmpty();
  }
}
