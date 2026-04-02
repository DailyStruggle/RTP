package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

public final class SyncTaskProcessing extends RTPRunnable {
  private final long availableTime;

  public SyncTaskProcessing(long availableTime) {
    this.availableTime = availableTime;
  }

  @Override
  public void run() {
    if (isCancelled()) return;
    if (trackingId != null) {
      io.github.dailystruggle.rtp.common.tools.MemoryTracker.updateTracking(trackingId);
    }
    long start = System.nanoTime();

    RTP.getInstance().cancelTasks.execute(Long.MAX_VALUE);
    if (isCancelled()) return;
    RTP.getInstance().miscSyncTasks.execute(availableTime - (System.nanoTime() - start));
    CommandsAPI.execute();
  }
}
