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

public final class LoadChunks extends RTPRunnable {
    public static final List<Consumer<LoadChunks>> preActions = new ArrayList<>();
    public static final List<Consumer<LoadChunks>> postActions = new ArrayList<>();

    static {
        preActions.add(task -> task.isRunning = true);
        postActions.add(task -> task.isRunning = false);
    }

    private final RTPCommandSender sender;
    private final RTPPlayer player;
    private final RTPLocation location;
    private final Region region;
    public boolean modified = false;

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

        ChunkSet chunkSet = this.region.chunks(location, radius2);

        TeleportData teleportData = RTP.getInstance().latestTeleportData.get(player.uuid());
        if(teleportData == null) {
            teleportData = new TeleportData();
            teleportData.sender = sender;
            teleportData.originalLocation = player.getLocation();
            teleportData.selectedLocation = location;
            teleportData.time = System.nanoTime();
            teleportData.nextTask = this;
            teleportData.targetRegion = region;
            teleportData.delay = sender.delay();
            RTP.getInstance().latestTeleportData.put(player.uuid(),teleportData);
        }

        if (max > chunkSet.chunks.size()) {
            chunkSet.keep(false);
            chunkSet = teleportData.targetRegion.chunks(location, radius2);
            chunkSet.keep(true);
            modified = true;
        }
    }

    @Override
    public void run() {
        preActions.forEach(consumer -> consumer.accept(this));
        long start = System.nanoTime();

        TeleportData teleportData = RTP.getInstance().latestTeleportData.get(player.uuid());
        DoTeleport doTeleport = new DoTeleport(sender, player, location, region);
        teleportData.nextTask = doTeleport;

        long lastTime = teleportData.time;

        long delay = sender.delay();
        long dT = (start - lastTime);
        long remainingTime = delay - dT;
        long toTicks = (TimeUnit.NANOSECONDS.toMillis(remainingTime)/50);

        ChunkSet chunkSet = this.region.locAssChunks.get(location);

        if(     toTicks<1 &&
                (sender.hasPermission("rtp.noDelay.chunks") || chunkSet.complete.isDone())) {
            if(Bukkit.isPrimaryThread()) doTeleport.run();
            else RTP.getInstance().teleportPipeline.add(doTeleport);
            postActions.forEach(consumer -> consumer.accept(this));
            return;
        }

        if(!chunkSet.complete.getNow(false)) {
            for (CompletableFuture<RTPChunk> cfChunk : chunkSet.chunks) {
                try {
                    cfChunk.get();
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }


        doTeleport.setDelay(toTicks);

        if(toTicks<1 && Bukkit.isPrimaryThread()) doTeleport.run();
        else RTP.getInstance().teleportPipeline.add(doTeleport);

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
        LoadChunks that = (LoadChunks) obj;
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
}
