package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;

import java.util.Queue;

/**
 * Slots for the core's general-purpose task pipes and chunk-unload queue (ADR-115 seam 4),
 * formerly the {@code RTP.miscSyncTasks}, {@code miscAsyncTasks}, {@code startupTasks},
 * {@code cancelTasks} and {@code chunksToUnload} instance fields.
 *
 * <p>The core instance installs its pipes in its constructor, where it used to assign the
 * fields, and its unload queue when it becomes current. Every slot is {@code null} before the
 * first core instance is constructed. Shutdown stops the sync and async pipes; nothing clears them.
 */
public final class MiscTasks {
  private static volatile RTPTaskPipe sync;
  private static volatile RTPTaskPipe backgroundSync;
  private static volatile RTPTaskPipe async;
  private static volatile RTPTaskPipe startup;
  private static volatile RTPTaskPipe cancel;
  private static volatile Queue<RTPChunk<?>> chunksToUnload;

  private MiscTasks() {
  }

  /** Pipe drained on the main / region thread each tick (player-facing sync stage). */
  public static RTPTaskPipe sync() {
    return sync;
  }

  public static void setSync(RTPTaskPipe pipe) {
    sync = pipe;
  }

  /** Pipe drained on the main thread during the governed background sync span (ADR-087 Section 4.3 item 4). */
  public static RTPTaskPipe backgroundSync() {
    return backgroundSync;
  }

  public static void setBackgroundSync(RTPTaskPipe pipe) {
    backgroundSync = pipe;
  }

  /** Pipe drained off the main thread. */
  public static RTPTaskPipe async() {
    return async;
  }

  public static void setAsync(RTPTaskPipe pipe) {
    async = pipe;
  }

  /** Pipe drained once core startup completes. */
  public static RTPTaskPipe startup() {
    return startup;
  }

  public static void setStartup(RTPTaskPipe pipe) {
    startup = pipe;
  }

  /** Pipe for teleport cancellations; unbounded per drain. */
  public static RTPTaskPipe cancel() {
    return cancel;
  }

  public static void setCancel(RTPTaskPipe pipe) {
    cancel = pipe;
  }

  /** Chunks awaiting release by {@code ChunkUnloadProcessor}. */
  public static Queue<RTPChunk<?>> chunksToUnload() {
    return chunksToUnload;
  }

  public static void setChunksToUnload(Queue<RTPChunk<?>> queue) {
    chunksToUnload = queue;
  }
}
