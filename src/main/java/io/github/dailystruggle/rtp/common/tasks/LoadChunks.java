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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class LoadChunks extends RTPRunnable {
    public static final List<Consumer<LoadChunks>> preActions = new ArrayList<>();
    public static final List<Consumer<LoadChunks>> postActions = new ArrayList<>();
    private final RTPCommandSender sender;
    private final RTPPlayer player;
    private final RTPLocation location;
    private final Region region;
    private ChunkSet chunkSet;
    private DoTeleport doTeleport = null;

    public LoadChunks(RTPCommandSender sender,
                      RTPPlayer player,
                      RTPLocation location,
                      Region region) {
        this.sender = sender;
        this.player = player;
        this.location = location;
        this.region = region;

        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.getInstance().configs.getParser(PerformanceKeys.class);
        long radius2 = perf.getNumber(PerformanceKeys.viewDistanceTeleport, 0L).longValue();
        long max = (radius2 * radius2 * 4) + (4 * radius2) + 1;

        chunkSet = this.region.chunks(location, radius2);

        preActions.forEach(consumer -> consumer.accept(this));
        TeleportData teleportData = RTP.getInstance().latestTeleportData.get(player.uuid());

        if (max > chunkSet.chunks().size()) {
            chunkSet.keep(false);
            chunkSet = teleportData.targetRegion.chunks(location, radius2);
            chunkSet.keep(true);
        }
    }

    @Override
    public void run() {
        for (CompletableFuture<RTPChunk> cfChunk : chunkSet.chunks()) {
            try {
                cfChunk.get();
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }

        DoTeleport doTeleport = new DoTeleport(sender, player, location, region);
        RTP.getInstance().teleportPipeline.add(doTeleport);
        this.doTeleport = doTeleport;

        postActions.forEach(consumer -> consumer.accept(this));
    }

    public RTPCommandSender sender() {
        return sender;
    }

    public RTPPlayer player() {
        return player;
    }

    public RTPLocation location() {
        return location;
    }

    public Region region() {
        return region;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (LoadChunks) obj;
        return Objects.equals(this.sender, that.sender) &&
                Objects.equals(this.player, that.player) &&
                Objects.equals(this.location, that.location) &&
                Objects.equals(this.region, that.region);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sender, player, location, region);
    }

    @Override
    public String toString() {
        return "LoadChunks[" +
                "sender=" + sender + ", " +
                "player=" + player + ", " +
                "location=" + location + ", " +
                "region=" + region + ']';
    }

    public ChunkSet chunkSet() {
        return chunkSet;
    }

    public DoTeleport doTeleport() {
        return doTeleport;
    }
}
