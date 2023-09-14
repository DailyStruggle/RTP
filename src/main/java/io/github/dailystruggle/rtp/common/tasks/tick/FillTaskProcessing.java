package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.FillTask;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.Map;

public final class FillTaskProcessing extends RTPRunnable {
    @Override
    public void run() {
        if ( isCancelled() ) return;

        for ( Map.Entry<String, FillTask> e : RTP.getInstance().fillTasks.entrySet() ) {
            if ( e.getValue().isRunning() ) continue;
            e.getValue().run();
            if ( isCancelled() ) return;
        }
    }
}
