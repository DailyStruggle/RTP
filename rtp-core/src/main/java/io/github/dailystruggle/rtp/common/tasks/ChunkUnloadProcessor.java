package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;

/**
 * Periodic task that drains up to 50 entries from the
 * {@link MiscTasks#chunksToUnload()} queue and unloads each loaded chunk.
 */
public class ChunkUnloadProcessor implements Runnable {

  /** Constructs a processor. */
  public ChunkUnloadProcessor() {
    // Explicit public constructor
  }
  @Override
  public void run() {
    try {
      TeleportSessions sessions = TeleportSessions.current();
      if (sessions == null) return;
      for (int i = 0; i < 50; i++) {
        RTPChunk<?> chunk = MiscTasks.chunksToUnload().poll();
        if (chunk == null) break;
        if (chunk.isLoaded()) {
          chunk.unload();
        }
      }
    } catch (Throwable t) {
      RTPRuntime.log(java.util.logging.Level.WARNING, "Exception during ChunkUnloadProcessor execution", t);
    }
  }
}
