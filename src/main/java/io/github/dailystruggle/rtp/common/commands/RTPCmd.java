package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.*;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.SetupTeleport;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public interface RTPCmd extends BaseRTPCmd {
    default void init() {
        
    }


    //synchronous command component
    default boolean onCommand(RTPCommandSender sender, CommandsAPICommand command, String label, String[] args) {
        RTP rtp = RTP.getInstance();

        long timingsStart = System.nanoTime();

        UUID senderId = sender.uuid();
        if(rtp.processingPlayers.contains(senderId)) {
            RTP.serverAccessor.sendMessage(senderId,LangKeys.alreadyTeleporting);
            return true;
        }

        //--------------------------------------------------------------------------------------------------------------
        //guard command perms with custom message
        if(!sender.hasPermission("rtp.use")) {
            RTP.serverAccessor.sendMessage(senderId, LangKeys.noPerms);
            return true;
        }

        //--------------------------------------------------------------------------------------------------------------
        //guard last teleport time synchronously to prevent spam
        TeleportData senderData = rtp.latestTeleportData.getOrDefault(senderId, new TeleportData());

        if(senderData.sender == null) {
            senderData.sender = sender;
        }

        long dt = System.nanoTime()-senderData.time;
        if(dt < 0) dt = Long.MAX_VALUE+dt;
        if(dt < sender.cooldown()) {
            RTP.serverAccessor.sendMessage(senderId,LangKeys.cooldownMessage);
            return true;
        }

        if(!senderId.equals(CommandsAPI.serverId)) rtp.processingPlayers.add(senderId);

        return onCommand(senderId,sender::hasPermission,sender::sendMessage,args);
    }

    //async command component
    default boolean compute(UUID senderId, Map<String, List<String>> rtpArgs, CommandsAPICommand nextCommand) {
        long timingsStart = System.nanoTime();

        RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);

        RTP rtp = RTP.getInstance();
        if(nextCommand!=null) {
            rtp.processingPlayers.remove(senderId);
            return true;
        }

        ConfigParser<LangKeys> langParser = (ConfigParser<LangKeys>) rtp.configs.getParser(LangKeys.class);

        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) rtp.configs.getParser(PerformanceKeys.class);
        boolean syncLoading = false;
        Object configValue = perf.getConfigValue(PerformanceKeys.syncLoading, false);
        if(configValue instanceof String s) {
            configValue = Boolean.parseBoolean(s);
            perf.set(PerformanceKeys.syncLoading,configValue);
        }
        if(configValue instanceof Boolean b) syncLoading = b;

        //--------------------------------------------------------------------------------------------------------------
        //collect target players to teleport
        List<RTPPlayer> players = new ArrayList<>();
        if(rtpArgs.containsKey("player")) { //if players are listed, use those.
            List<String> playerNames = rtpArgs.get("player");
            for (String playerName : playerNames) {
                //double check the player is still valid by the time we get here
                RTPPlayer p = RTP.serverAccessor.getPlayer(playerName);
                if (p == null) {
                    String msg = (String) langParser.getConfigValue(LangKeys.badArg, "player:" + rtpArgs.get("player"));
                    RTP.serverAccessor.sendMessage(senderId, msg);
                    continue;
                }

                players.add(p);
            }
        }
        else if(sender instanceof RTPPlayer p) { //if no players but sender is a player, use sender's location
            players = new ArrayList<>(1);
            players.add(p);
        }
        else { //if no players and sender isn't a player, idk who to send
            String msg = (String) langParser.getConfigValue(LangKeys.consoleCmdNotAllowed,"");
            failEvent(sender,msg);
            rtp.processingPlayers.remove(senderId);
            return true;
        }

        for(int i = 0; i < players.size(); i++) {
            RTPPlayer p = players.get(i);

            //get their data
            TeleportData lastTeleportData = rtp.latestTeleportData.get(p.uuid());
            //if p has an incomplete teleport
            if(lastTeleportData != null) {
                if(!lastTeleportData.completed) {
                    String msg = (String) langParser.getConfigValue(LangKeys.alreadyTeleporting, "");
                    RTP.serverAccessor.sendMessage(senderId, p.uuid(), msg);
                    failEvent(sender,msg);
                    continue;
                }

                rtp.priorTeleportData.put(p.uuid(),lastTeleportData);
            }
            lastTeleportData = new TeleportData();
            lastTeleportData.time = timingsStart;
            rtp.latestTeleportData.put(p.uuid(),lastTeleportData);

            String regionName;
            if(rtpArgs.containsKey("region")) {
                //todo: get one region from the list
                regionName = pickOne(rtpArgs.get("region"),"default");
            }
            else {
                String worldName;
                //get region parameter from world options
                if(rtpArgs.containsKey("world")) {
                    //get one world from the list
                    worldName = pickOne(rtpArgs.get("world"),"default");
                }
                else {
                    //use p's world
                    worldName = p.getLocation().world().name();
                }

                ConfigParser<WorldKeys> worldParser = rtp.configs.getWorldParser(worldName);

                if(worldParser == null) {
                    //todo: message world not exist
                    rtp.processingPlayers.remove(senderId);
                    return true;
                }

                regionName = worldParser.getConfigValue(WorldKeys.region, "default").toString();
            }

            SelectionAPI selectionAPI = rtp.selectionAPI;

            Region region = selectionAPI.getRegionOrDefault(regionName);
            RTPWorld rtpWorld = (RTPWorld) region.getData().get(RegionKeys.world);
            Objects.requireNonNull(rtpWorld);

            //check for wbo
            if(rtpArgs.containsKey("worldBorderOverride")) {
                boolean doWBO;
                List<String> WBOVals = rtpArgs.get("worldBorderOverride");
                if(WBOVals.size() > i) doWBO = Boolean.parseBoolean(WBOVals.get(i));
                else doWBO = Boolean.parseBoolean(WBOVals.get(0));

                if(doWBO) {
                    region = region.clone();
                    region.set(RegionKeys.shape, RTP.serverAccessor.getShape(rtpWorld.name()));
                }
            }

            List<String> biomeList = rtpArgs.get("biome");
            Set<String> biomes = null;
            if(biomeList!=null) biomes = new HashSet<>(biomeList);

            //todo: shape params
            //todo: vert params
            //todo: biomes

            SetupTeleport setupTeleport = new SetupTeleport(sender, p, region, biomes);
            lastTeleportData.nextTask = setupTeleport;

            long delay = sender.delay();
            lastTeleportData.delay = delay;
            if(delay>0) {
                String msg = langParser.getConfigValue(LangKeys.delayMessage,"").toString();
                RTP.serverAccessor.sendMessage(senderId,p.uuid(),msg);
            }

            if(!syncLoading) {
                syncLoading = biomes == null
                        && region.hasLocation(p.uuid())
                        && delay<=0;
            }

            if(syncLoading) {
                setupTeleport.run();
            }
            else {
                rtp.setupTeleportPipeline.add(setupTeleport);
            }
        }

        return true;
    }

    @Override
    default String name() {
        return "rtp";
    }

    @Override
    default String permission() {
        return "rtp.use";
    }

    @Override
    default String description() {
        return "teleport randomly";
    }

    void successEvent(RTPCommandSender sender, RTPPlayer player);
    void failEvent(RTPCommandSender sender,String msg);

    private static String pickOne(List<String> param, String d) {
        if(param == null || param.size()==0) return d;
        int sel = ThreadLocalRandom.current().nextInt(param.size());
        return param.get(sel);
    }
}
