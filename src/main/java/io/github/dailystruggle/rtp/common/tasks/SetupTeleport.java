package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class SetupTeleport extends RTPRunnable {
    public static final List<Consumer<SetupTeleport>> preActions = new ArrayList<>();
    public static final List<BiConsumer<SetupTeleport,Boolean>> postActions = new ArrayList<>();

    private final RTPCommandSender sender;
    private final RTPPlayer player;
    private final @NotNull Region region;
    private final @Nullable Set<String> biomes;

    public SetupTeleport(RTPCommandSender sender,
                         RTPPlayer player,
                         @NotNull Region region,
                         @Nullable Set<String> biomes) {
        this.sender = sender;
        this.player = player;
        this.region = region;
        this.biomes = biomes;
    }

    @Override
    public void run() {
        isRunning = true;
        preActions.forEach(consumer -> consumer.accept(this));

        ConfigParser<MessagesKeys> langParser = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        boolean syncLoading = false;
        Object configValue = perf.getConfigValue(PerformanceKeys.syncLoading, false);
        if(configValue instanceof String) {
            configValue = Boolean.parseBoolean((String) configValue);
        }
        if(configValue instanceof Boolean) syncLoading = (Boolean) configValue;

        RTP rtp = RTP.getInstance();

        TeleportData teleportData = RTP.getInstance().latestTeleportData.get(player.uuid());
        if(teleportData == null) {
            teleportData = new TeleportData();
            teleportData.sender = sender;
            teleportData.originalLocation = player.getLocation();
            teleportData.time = System.nanoTime();
            teleportData.nextTask = this;
            teleportData.targetRegion = region;
            teleportData.delay = sender.delay();
            RTP.getInstance().latestTeleportData.put(player.uuid(),teleportData);
        }

        teleportData.targetRegion = this.region;

        teleportData.originalLocation = player.getLocation();

        RTP.getInstance().latestTeleportData.put(player.uuid(),teleportData);

        Map.Entry<RTPLocation, Long> pair = this.region.getLocation(sender, player, biomes);
        if(pair == null) { //player gets put on region queue
            return;
        }
        else if(pair.getKey() == null) {
            teleportData.attempts = pair.getValue();
            String msg = langParser.getConfigValue(MessagesKeys.unsafe,"").toString();
            RTP.serverAccessor.sendMessage(sender.uuid(),player.uuid(),msg);
            postActions.forEach(consumer -> consumer.accept(this, false));
            isRunning = false;
            RTPTeleportCancel.refund(player.uuid());
            return;
        }

        if(isCancelled()) return;
        LoadChunks loadChunks = new LoadChunks(this.sender, this.player, pair.getKey(), this.region);
        teleportData.nextTask = loadChunks;
        teleportData.attempts = pair.getValue();

        boolean sync = syncLoading;
        if(!syncLoading) {
            sync = teleportData.delay<=0
                    && (biomes == null || biomes.size()==0)
                    && !loadChunks.modified;
        }

        if(sync) {
            loadChunks.run();
        }
        else {
            rtp.loadChunksPipeline.add(loadChunks);
        }

        postActions.forEach(consumer -> consumer.accept(this, true));
        isRunning = false;
    }

    public RTPCommandSender sender() {
        return sender;
    }

    public RTPPlayer player() {
        return player;
    }

    public @NotNull Region region() {
        return region;
    }

    public @Nullable Set<String> biomes() {
        return biomes;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        SetupTeleport that = (SetupTeleport) obj;
        return Objects.equals(this.sender, that.sender) &&
                Objects.equals(this.player, that.player) &&
                Objects.equals(this.region, that.region) &&
                Objects.equals(this.biomes, that.biomes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sender, player, region, biomes);
    }

    @Override
    public String toString() {
        return "SetupTeleport[" +
                "sender=" + sender + ", " +
                "player=" + player + ", " +
                "region=" + region + ", " +
                "biomes=" + biomes + ']';
    }
}
