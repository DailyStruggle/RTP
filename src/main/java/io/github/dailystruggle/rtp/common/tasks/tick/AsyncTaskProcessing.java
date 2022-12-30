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
        
        RTP.getInstance().cancelTasks.execute(Long.MAX_VALUE);
        if(isCancelled()) return;
        RTP.getInstance().loadChunksPipeline.execute(availableTime-(System.nanoTime() - start));
        if(isCancelled()) return;
        RTP.getInstance().setupTeleportPipeline.execute(availableTime-(System.nanoTime() - start));
        if(isCancelled()) return;
        RTP.getInstance().miscAsyncTasks.execute(availableTime-(System.nanoTime() - start));
        if(isCancelled()) return;

        for(Map.Entry<String, FillTask> e : RTP.getInstance().fillTasks.entrySet()) {
            if(e.getValue().isRunning()) continue;
            e.getValue().run();
            if(isCancelled()) return;
        }

        long period = 0;
        if(RTP.configs !=null) {
            ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
            if(perf!=null) period = perf.getNumber(PerformanceKeys.period,0).longValue();
        }

        List<Region> regions = new ArrayList<>(RTP.selectionAPI.permRegionLookup.values());
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

        RTP.getInstance().databaseAccessor.processQueries(availableTime-(System.nanoTime() - start));
    }
}
