package io.github.dailystruggle.rtp.folia.tasks;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;

public class FoliaTaskDispatcher extends RTPTaskPipe {
    private final int maxTasksPerTick;

    public FoliaTaskDispatcher(int maxTasksPerTick) {
        this.maxTasksPerTick = maxTasksPerTick;
    }

    @Override
    public void execute() {
        if (stop) return;
        for (int i = 0; i < maxTasksPerTick; i++) {
            Runnable runnable = runnables.poll();
            if (runnable == null) break;

            RTPLocation location = null;
            if (runnable instanceof RTPRunnable) {
                location = ((RTPRunnable) runnable).getTargetLocation();
            }

            if (location != null) {
                RTP.scheduler.runTask(location, runnable);
            } else {
                RTP.scheduler.runTaskAsynchronously(runnable);
            }
        }
    }
}
