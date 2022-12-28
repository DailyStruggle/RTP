package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.*;
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

        RTP.getInstance().invulnerablePlayers.put(player.uuid(),System.currentTimeMillis());

        TeleportData teleportData = RTP.getInstance().latestTeleportData.get(player.uuid());
        if(teleportData == null) {
            teleportData = new TeleportData();
            teleportData.sender = (sender != null) ? sender : player;
            teleportData.originalLocation = player.getLocation();
            teleportData.time = System.currentTimeMillis();
            teleportData.nextTask = this;
            teleportData.delay = sender.delay();
        }
        teleportData.targetRegion = region;
        teleportData.selectedLocation = location;
        teleportData.completed = true;
        RTP.getInstance().latestTeleportData.put(player.uuid(),teleportData);

        CompletableFuture<Boolean> setLocation = player.setLocation(location);

        Map<String,Object> dataMap = DatabaseAccessor.toColumns(teleportData);
        dataMap.put("playerName",player.name());
        Map<String,Object> saveMap = new HashMap<>();
        if (RTP.getInstance().databaseAccessor instanceof YamlFileDatabase) {
            saveMap.put(player.uuid().toString(),dataMap);
        }
        else {
            saveMap.put("UUID",player.uuid().toString());
            saveMap.putAll(dataMap);
        }
        RTP.getInstance().databaseAccessor.setValue("teleportData", saveMap);

        RTP.getInstance().chunkCleanupPipeline.add(new ChunkCleanup(location, region));

        RTP.getInstance().processingPlayers.remove(player.uuid());

        TeleportData finalTeleportData = teleportData;
        setLocation.thenAccept(aBoolean -> {
            ConfigParser<LoggingKeys> logging = (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
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
                finalTeleportData.processingTime = System.currentTimeMillis() - finalTeleportData.time;
                RTP.getInstance().latestTeleportData.put(player.uuid(),finalTeleportData);
                RTP.serverAccessor.sendMessage(player.uuid(), MessagesKeys.teleportMessage);

                if(verbose) {
                    long time = finalTeleportData.processingTime;
                    ConfigParser<MessagesKeys> langParser = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
                    long days = TimeUnit.MILLISECONDS.toDays(time);
                    long hours = TimeUnit.MILLISECONDS.toHours(time)%24;
                    long minutes = TimeUnit.MILLISECONDS.toMinutes(time)%60;
                    long seconds = TimeUnit.MILLISECONDS.toSeconds(time)%60;
                    long millis = time % 1000;
                    if(millis>500 && seconds>0) {
                        seconds++;
                        millis = 0;
                    }

                    String replacement = "";
                    if(days>0) replacement += days + langParser.getConfigValue(MessagesKeys.days,"").toString() + " ";
                    if(hours>0) replacement += hours + langParser.getConfigValue(MessagesKeys.hours,"").toString() + " ";
                    if(minutes>0) replacement += minutes + langParser.getConfigValue(MessagesKeys.minutes,"").toString() + " ";
                    if(seconds>0) replacement += seconds + langParser.getConfigValue(MessagesKeys.seconds,"").toString();
                    if(seconds<2) {
                        replacement += millis + langParser.getConfigValue(MessagesKeys.millis,"").toString();
                    }
                    RTP.log(Level.INFO, "[RTP] completed teleport for player:"+player.name() + " in " + replacement);
                }
            }
            else {
                if(verbose) RTP.log(Level.WARNING, "[RTP] failed to complete teleport for player:"+player.name());
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
