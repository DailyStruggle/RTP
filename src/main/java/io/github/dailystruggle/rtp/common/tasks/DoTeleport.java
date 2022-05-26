package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.substitutions.RTPPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class DoTeleport extends RTPRunnable {
    public static final List<Consumer<DoTeleport>> preActions = new ArrayList<>();
    public static final List<Consumer<DoTeleport>> postActions = new ArrayList<>();
    private final RTPCommandSender sender;
    private final RTPPlayer player;
    private final RTPLocation location;
    private final Region region;

    public DoTeleport(RTPCommandSender sender,
                      RTPPlayer player,
                      RTPLocation location,
                      Region region) {
        this.sender = sender;
        this.player = player;
        this.location = location;
        this.region = region;
    }

    @Override
    public void run() {
        preActions.forEach(consumer -> consumer.accept(this));

        //todo: safety checks
        player.setLocation(location);

        TeleportData teleportData = RTP.getInstance().latestTeleportData.get(player.uuid());
        teleportData.completed = true;

        RTP.getInstance().chunkCleanupPipeline.add(new ChunkCleanup(location, region));

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
        var that = (DoTeleport) obj;
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
        return "DoTeleport[" +
                "sender=" + sender + ", " +
                "player=" + player + ", " +
                "location=" + location + ", " +
                "region=" + region + ']';
    }

}
