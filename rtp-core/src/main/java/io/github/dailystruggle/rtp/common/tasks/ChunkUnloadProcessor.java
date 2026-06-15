package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.common.RTP;

/**
 * Periodic task that drains up to 50 entries from the
 * {@link RTP#chunksToUnload} queue and unloads each loaded chunk.
 */
public class ChunkUnloadProcessor implements Runnable {

  /** Constructs a processor. */
  public ChunkUnloadProcessor() {}
  @Override
  public void run() {
    try {
      RTP instance = RTP.getInstance();
      if (instance == null) return;
      for (int i = 0; i < 50; i++) {
        RTPChunk<?> chunk = instance.chunksToUnload.poll();
        if (chunk == null) break;
        if (chunk.isLoaded()) {
          chunk.unload();
        }
      }
    } catch (Throwable t) {
      t.printStackTrace();
    }
  }
}
