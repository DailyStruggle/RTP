package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.common.RTP;

public class ChunkUnloadProcessor implements Runnable {
  @Override
  public void run() {
    RTP instance = RTP.getInstance();
    if (instance == null) return;
    for (int i = 0; i < 10; i++) {
      RTPChunk<?> chunk = instance.chunksToUnload.poll();
      if (chunk == null) break;
      if (chunk.isLoaded()) {
        chunk.unload();
      }
    }
  }
}
