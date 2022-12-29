package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.logging.Level;

public final class SyncTaskProcessing extends RTPRunnable {
    private final long availableTime;

    public SyncTaskProcessing(long availableTime) {
        this.availableTime = availableTime;
    }

    @Override
    public void run() {
        if(isCancelled()) return;
        long start = System.nanoTime();

        RTP.getInstance().cancelTasks.execute(Long.MAX_VALUE);
        if(isCancelled()) return;
        RTP.getInstance().chunkCleanupPipeline.execute(availableTime-(System.nanoTime() - start));
        if(isCancelled()) return;
        RTP.getInstance().teleportPipeline.execute(availableTime-(System.nanoTime() - start));
        if(isCancelled()) return;
        RTP.getInstance().miscSyncTasks.execute(availableTime-(System.nanoTime() - start));
    }
}
