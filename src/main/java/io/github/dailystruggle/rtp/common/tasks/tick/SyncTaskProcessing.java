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

        RTP instance = RTP.getInstance();

        instance.cancelTasks.execute(Long.MAX_VALUE);
        if(isCancelled()) return;
        instance.chunkCleanupPipeline.execute(availableTime);
        if(isCancelled()) return;
        instance.teleportPipeline.execute(availableTime-(start-System.nanoTime()));
        if(isCancelled()) return;
        instance.miscSyncTasks.execute(availableTime-(start-System.nanoTime()));
    }
}
