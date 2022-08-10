package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
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
        location.world().platform(location);

        player.setLocation(location);

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
        teleportData.completed = true;

        RTP.getInstance().chunkCleanupPipeline.add(new ChunkCleanup(location, region));

        RTP.getInstance().processingPlayers.remove(player.uuid());

        teleportData.processingTime = System.nanoTime() - teleportData.time;

        ConfigParser<LangKeys> lang = (ConfigParser<LangKeys>) RTP.getInstance().configs.getParser(LangKeys.class);
        String msg = lang.getConfigValue(LangKeys.teleportMessage, "").toString();
        RTP.getInstance().serverAccessor.sendMessage(player.uuid(),msg);

        postActions.forEach(consumer -> consumer.accept(this));

//        long dt = System.nanoTime() - t;
//        double millis = ((double)(TimeUnit.NANOSECONDS.toMicros(dt)%1000000))/1000;
//        String timeStr = millis + "ms";
//        RTP.log(Level.WARNING, "DOTELEPORT: " + timeStr);
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
