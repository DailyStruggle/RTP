package io.github.dailystruggle.server.api;

import java.util.function.Consumer;

/**
 * No-op {@link WorldEventHook} singleton (ADR-049, ADR-115).
 *
 * <p>Subscriptions are discarded and return no-op {@link AutoCloseable} handles.
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public final class NoopWorldEventHook implements WorldEventHook {

  /** Process-wide singleton. */
  public static final NoopWorldEventHook INSTANCE = new NoopWorldEventHook();

  private static final AutoCloseable NOOP_SUBSCRIPTION = () -> {};

  private NoopWorldEventHook() {}

  @Override
  public AutoCloseable onPlayerInteractBlock(Consumer<PlayerInteractBlockContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    return NOOP_SUBSCRIPTION;
  }

  @Override
  public AutoCloseable onBlockBreak(Consumer<BlockBreakContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    return NOOP_SUBSCRIPTION;
  }

  @Override
  public AutoCloseable onLeavesDecay(Consumer<LeavesDecayContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    return NOOP_SUBSCRIPTION;
  }

  @Override
  public AutoCloseable onChunkLoad(Consumer<ChunkLoadContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    return NOOP_SUBSCRIPTION;
  }

  @Override
  public AutoCloseable onSaplingGrow(Consumer<SaplingGrowContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    return NOOP_SUBSCRIPTION;
  }

  @Override
  public AutoCloseable onPlayerBedEnter(Consumer<PlayerBedEnterContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    return NOOP_SUBSCRIPTION;
  }

  @Override
  public AutoCloseable onPlayerBedLeave(Consumer<PlayerBedLeaveContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    return NOOP_SUBSCRIPTION;
  }

  @Override
  public AutoCloseable onWorldTimeSkip(Consumer<WorldTimeSkipContext> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    return NOOP_SUBSCRIPTION;
  }
}
