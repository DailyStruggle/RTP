package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.ScanTask;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.Map;

public final class ScanTaskProcessing extends RTPRunnable {
  /** Spark-profiler frame tag (diagram 05 scan drain). See {@link RTPRunnable#sparkFrameName()}. */
  @Override
  protected String sparkFrameName() { return "rtp_scan_drain"; }

  @Override
  public void run() {
    if (isCancelled()) return;

    for (Map.Entry<String, ScanTask> e : RTP.getInstance().scanTasks.entrySet()) {
      if (e.getValue().isRunning()) continue;
      e.getValue().run();
      if (isCancelled()) return;
    }
  }
}
