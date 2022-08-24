package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.ChunkSet;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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

        for(var e : instance.fillTasks.entrySet()) {
            if(e.getValue().isRunning()) continue;
            e.getValue().run();
            if(isCancelled()) return;
        }

        long period = 0;
        if(instance.configs!=null) {
            ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) instance.configs.getParser(PerformanceKeys.class);
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
                regions.get(i).execute(availableTime - (start - System.nanoTime()));
            }
        }
        step = (step+1)%period;
    }
}
