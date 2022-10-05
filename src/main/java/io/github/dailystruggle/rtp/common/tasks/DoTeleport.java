package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
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

        CompletableFuture<Boolean> setLocation = player.setLocation(location);

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

        TeleportData finalTeleportData = teleportData;
        setLocation.whenComplete((aBoolean, throwable) -> {
            ConfigParser<LoggingKeys> logging = (ConfigParser<LoggingKeys>) RTP.getInstance().configs.getParser(LoggingKeys.class);
            boolean verbose = true;
            if(logging!=null) {
                Object o = logging.getConfigValue(LoggingKeys.teleport,false);
                if (o instanceof Boolean) {
                    verbose = (Boolean) o;
                } else {
                    verbose = Boolean.parseBoolean(o.toString());
                }
            }

            if(aBoolean) {
                finalTeleportData.processingTime = System.nanoTime() - finalTeleportData.time;
                RTP.getInstance().latestTeleportData.put(player.uuid(),finalTeleportData);
                RTP.serverAccessor.sendMessage(player.uuid(),LangKeys.teleportMessage);

                if(verbose) {
                    long time = finalTeleportData.processingTime;
                    ConfigParser<LangKeys> langParser = (ConfigParser<LangKeys>) RTP.getInstance().configs.getParser(LangKeys.class);
                    long days = TimeUnit.NANOSECONDS.toDays(time);
                    long hours = TimeUnit.NANOSECONDS.toHours(time)%24;
                    long minutes = TimeUnit.NANOSECONDS.toMinutes(time)%60;
                    long seconds = TimeUnit.NANOSECONDS.toSeconds(time)%60;

                    String replacement = "";
                    if(days>0) replacement += days + langParser.getConfigValue(LangKeys.days,"").toString() + " ";
                    if(hours>0) replacement += hours + langParser.getConfigValue(LangKeys.hours,"").toString() + " ";
                    if(minutes>0) replacement += minutes + langParser.getConfigValue(LangKeys.minutes,"").toString() + " ";
                    if(seconds>0) replacement += seconds + langParser.getConfigValue(LangKeys.seconds,"").toString();
                    if(seconds<2) {
                        double millis;
                        if(seconds<1) millis = ((double) TimeUnit.NANOSECONDS.toMicros(time))/1000 % 1000;
                        else millis = TimeUnit.NANOSECONDS.toMillis(time)%1000;
                        replacement += millis + langParser.getConfigValue(LangKeys.millis,"").toString();
                    }
                    RTP.log(Level.INFO, "[plugin] completed teleport for player:"+player.name() + " in " + replacement);
                }
            }
            else {
                if(verbose) RTP.log(Level.WARNING, "[plugin] failed to complete teleport for player:"+player.name());
            }
        });

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
        DoTeleport that = (DoTeleport) obj;
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
