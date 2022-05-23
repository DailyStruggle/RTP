package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.ChunkSet;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.substitutions.RTPPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;

public record LoadChunks(RTPCommandSender sender,
                         RTPPlayer player,
                         RTPLocation location,
                         Region region) implements Runnable {
    public static final List<Consumer<LoadChunks>> preActions = new ArrayList<>();
    public static final List<Consumer<LoadChunks>> postActions = new ArrayList<>();

    @Override
    public void run() {
        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.getInstance().configs.getParser(PerformanceKeys.class);
        long radius1 = perf.getNumber(PerformanceKeys.viewDistanceSelect,0L).longValue();
        long radius2 = perf.getNumber(PerformanceKeys.viewDistanceTeleport,0L).longValue();
        long max = (radius2 * radius2 *4) + (4* radius2) + 1;

        ChunkSet chunkSet = this.region.chunks(location, radius1);

        preActions.forEach(consumer -> consumer.accept(this));
        TeleportData teleportData = RTP.getInstance().latestTeleportData.get(sender.uuid());

        RTP.log(Level.WARNING,"[RTP] at loadChunks");

        ChunkSet totalChunks;
        
        if(max > chunkSet.chunks().size()) {
            totalChunks = teleportData.targetRegion.chunks(location, radius2);
        }
        else totalChunks = chunkSet;

        RTP.log(Level.WARNING,"[RTP] loading");
        for (CompletableFuture<RTPChunk> cfChunk : totalChunks.chunks()) {
            try {
                cfChunk.get();
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }

        RTP.getInstance().teleportPipeline.add(new DoTeleport(sender,player,location,region));

        postActions.forEach(consumer -> consumer.accept(this));
    }
}
