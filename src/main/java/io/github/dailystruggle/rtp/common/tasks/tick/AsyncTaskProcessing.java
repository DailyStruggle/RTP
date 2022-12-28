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
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class AsyncTaskProcessing extends RTPRunnable {
    long step = 0;
    private final long availableTime;

    public AsyncTaskProcessing(long availableTime) {
        this.availableTime = availableTime;
    }

    @Override
    public void run() {
        if(isCancelled()) return;
        long start = System.nanoTime();

        RTP instance = RTP.getInstance();

        instance.cancelTasks.execute(Long.MAX_VALUE);
        if(isCancelled()) return;
        instance.loadChunksPipeline.execute(availableTime-(start-System.nanoTime()));
        if(isCancelled()) return;
        instance.setupTeleportPipeline.execute(availableTime-(start-System.nanoTime()));
        if(isCancelled()) return;
        instance.miscAsyncTasks.execute(availableTime-(start-System.nanoTime()));
        if(isCancelled()) return;

        for(Map.Entry<String, FillTask> e : instance.fillTasks.entrySet()) {
            if(e.getValue().isRunning()) continue;
            e.getValue().run();
            if(isCancelled()) return;
        }

        long period = 0;
        if(RTP.configs !=null) {
            ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
            if(perf!=null) period = perf.getNumber(PerformanceKeys.period,0).longValue();
        }

        List<Region> regions = new ArrayList<>(instance.selectionAPI.permRegionLookup.values());
        if(period<=0) period = regions.size();
        if(period<=0) return;

        //step computation according to period
        double increment = ((double) period)/regions.size();
        long location = (long) (increment*step);
        for( int i = 0; i < regions.size(); i++) {
            if(isCancelled()) return;
            long l = (long) (((double)i)*increment);

            if(l == location) {
                regions.get(i).execute(availableTime - (System.nanoTime()-start));
            }
        }
        step = (step+1)%period;

        instance.databaseAccessor.processQueries(availableTime-(start-System.nanoTime()));
    }
}
