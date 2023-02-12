package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

public final class AsyncTaskProcessing extends RTPRunnable {
    private final long availableTime;
    private static final AtomicLong step = new AtomicLong();
    private static final AtomicLong betweenStep = new AtomicLong();
    private static final Semaphore stepSemaphore = new Semaphore(1);

    public AsyncTaskProcessing(long availableTime) {
        this.availableTime = availableTime;
    }

    @Override
    public void run() {
        if (isCancelled()) return;
        long start = System.nanoTime();

        RTP.getInstance().cancelTasks.execute(Long.MAX_VALUE);
        if (isCancelled()) return;
        RTP.getInstance().setupTeleportPipeline.execute(availableTime - (System.nanoTime() - start));
        if (isCancelled()) return;
        RTP.getInstance().loadChunksPipeline.execute(availableTime - (System.nanoTime() - start));
        if (isCancelled()) return;
        RTP.getInstance().miscAsyncTasks.execute(availableTime - (System.nanoTime() - start));
        if (isCancelled()) return;

        long period = 0;
        if (RTP.configs != null) {
            ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
            if (perf != null) period = perf.getNumber(PerformanceKeys.period, 0).longValue();
        }

        List<Region> regions = new ArrayList<>(RTP.selectionAPI.permRegionLookup.values());
        if (period < regions.size()) period = regions.size();

        long betweenTime = Math.max((period / regions.size())-1,0);
        long betweenStep;
        long step;
        try {
            stepSemaphore.acquire();
            if(betweenTime<=0) betweenStep = 0;
            else betweenStep = AsyncTaskProcessing.betweenStep.incrementAndGet() % betweenTime;
            step = AsyncTaskProcessing.step.get();
            if(betweenStep==0) step = (step+1)%regions.size();
            AsyncTaskProcessing.betweenStep.set(betweenStep);
            AsyncTaskProcessing.step.set(step);
        } catch (InterruptedException e) {
            e.printStackTrace();
            return;
        } finally {
            stepSemaphore.release();
        }

        //step computation according to period
        if(betweenStep == 0) {
            if (isCancelled()) return;
            Region region = regions.get((int) step);
            region.execute(availableTime - (System.nanoTime() - start));
        }
    }
}
