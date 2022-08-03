package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class SetupTeleport extends RTPRunnable {
    public static final List<Consumer<SetupTeleport>> preActions = new ArrayList<>();
    public static final List<Consumer<SetupTeleport>> postActions = new ArrayList<>();
    private final RTPCommandSender sender;
    private final RTPPlayer player;
    private final @NotNull Region region;
    private final @Nullable Set<String> biomes;
    private LoadChunks loadChunks = null;

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
        preActions.forEach(consumer -> consumer.accept(this));

        RTP rtp = RTP.getInstance();

        TeleportData teleportData = RTP.getInstance().latestTeleportData.get(player.uuid());

        teleportData.targetRegion = this.region;

        teleportData.originalLocation = player.getLocation();
        RTPLocation location = this.region.getLocation(sender, player, null);
        if (location!=null) {
            LoadChunks loadChunks = new LoadChunks(this.sender, this.player, location, this.region);
            this.loadChunks = loadChunks;
            rtp.loadChunksPipeline.add(loadChunks);
            postActions.forEach(consumer -> consumer.accept(this));
            return;
        }

        postActions.forEach(consumer -> consumer.accept(this));

        //todo: append player queue or load chunks
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
        var that = (SetupTeleport) obj;
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
