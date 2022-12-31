package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.FillTask;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FillTaskProcessing extends RTPRunnable {
    public FillTaskProcessing() {

    }

    @Override
    public void run() {
        if(isCancelled()) return;

        for(Map.Entry<String, FillTask> e : RTP.getInstance().fillTasks.entrySet()) {
            if(e.getValue().isRunning()) continue;
            e.getValue().run();
            if(isCancelled()) return;
        }
    }
}
