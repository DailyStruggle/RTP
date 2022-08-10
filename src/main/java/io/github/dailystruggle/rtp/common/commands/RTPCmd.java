package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.SetupTeleport;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public abstract class RTPCmd implements BaseRTPCmd {
    private final Semaphore senderChecksGuard = new Semaphore(1);
    private final List<Predicate<RTPCommandSender>> senderChecks = new ArrayList<>();

    public void addSenderCheck(Predicate<RTPCommandSender> senderCheck) {
        try {
            senderChecksGuard.acquire();
            senderChecks.add(senderCheck);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        finally {
            senderChecksGuard.release();
        }
    }

    //synchronous command component
    protected boolean onCommandSync(UUID senderId) {
        RTP api = RTP.getInstance();

        RTPCommandSender sender = RTP.getInstance().serverAccessor.getSender(senderId);

        if(api.processingPlayers.contains(senderId)) {
            api.serverAccessor.sendMessage(senderId,LangKeys.alreadyTeleporting);
        }
        api.processingPlayers.add(senderId);

        long start = System.nanoTime();

        ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) api.configs.getParser(ConfigKeys.class);
        ConfigParser<LangKeys> langParser = (ConfigParser<LangKeys>) api.configs.getParser(LangKeys.class);

        //--------------------------------------------------------------------------------------------------------------
        //guard command perms with custom message
        if(!sender.hasPermission("rtp.use")) {
            api.serverAccessor.sendMessage(senderId, LangKeys.noPerms);
            return true;
        }

        //--------------------------------------------------------------------------------------------------------------
        //guard last teleport time synchronously to prevent spam
        TeleportData senderData = (sender instanceof RTPPlayer)
                ? api.latestTeleportData.getOrDefault(sender.uuid(), new TeleportData())
                : new TeleportData();
        if(senderData.sender == null) {
            senderData.sender = sender;
        }

        ConcurrentHashMap<UUID, TeleportData> latestTeleportData = RTP.getInstance().latestTeleportData;

        if(!sender.hasPermission("rtp.noCooldown")) {
            long lastTime;

            TeleportData teleportData = latestTeleportData.get(senderId);
            lastTime = (teleportData != null) ? teleportData.time : 0;

            long cooldownTime = sender.cooldown();

            if ((start - lastTime) < cooldownTime){
                String msg = (String) langParser.getConfigValue(LangKeys.cooldownMessage, "");
                api.serverAccessor.sendMessage(senderId, msg);
                return true;
            }
        }

        boolean validSender = true;
        try {
            senderChecksGuard.acquire();
            for(var a : senderChecks) {
                validSender &= a.test(sender);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            senderChecksGuard.release();
        }

        return validSender;
    }

    public boolean onCommandAsync(RTPCommandSender sender, Map<String, List<String>> rtpArgs, CommandsAPICommand nextCommand) {
        RTP api = RTP.getInstance();

        ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) api.configs.getParser(ConfigKeys.class);
        ConfigParser<LangKeys> langParser = (ConfigParser<LangKeys>) api.configs.getParser(LangKeys.class);

        MultiConfigParser<WorldKeys> worldsParser = (MultiConfigParser<WorldKeys>) api.configs.getParser(WorldKeys.class);
        MultiConfigParser<RegionKeys> regionsParser = (MultiConfigParser<RegionKeys>) api.configs.getParser(RegionKeys.class);

        //--------------------------------------------------------------------------------------------------------------
        //collect target players to teleport
        List<RTPPlayer> players = new ArrayList<>();
        if(rtpArgs.containsKey("player")) { //if players are listed, use those.
            List<String> playerNames = rtpArgs.get("player");
            for (String playerName : playerNames) {
                //double check the player is still valid by the time we get here
                RTPPlayer p = RTP.getInstance().serverAccessor.getPlayer(playerName);
                if (p == null) {
                    String msg = (String) langParser.getConfigValue(LangKeys.badArg, "player:" + rtpArgs.get("player"));
                    sender.sendMessage(msg);
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
            triggerFailEvent(sender);
            sender.sendMessage(msg);
            return true;
        }

        for(int i = 0; i < players.size(); i++) {
            RTPPlayer p = players.get(i);

            //get their data
            TeleportData lastTeleportData = api.latestTeleportData.get(p.uuid());
            //if p has an incomplete teleport
            if(lastTeleportData != null) {
                if(!lastTeleportData.completed) {
                    String msg = (String) langParser.getConfigValue(LangKeys.alreadyTeleporting, "");
                    sender.sendMessage(msg);
                    if(!sender.uuid().equals(p.uuid())) p.sendMessage(msg);
                    triggerFailEvent(sender);
                    continue;
                }

                api.priorTeleportData.put(p.uuid(),lastTeleportData);
            }
            lastTeleportData = new TeleportData();
            api.latestTeleportData.put(p.uuid(),lastTeleportData);

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

                //todo: validate world parser exists

                //todo: world to region
                ConfigParser<WorldKeys> worldParser = api.configs.getWorldParser(worldName);
                if(worldParser == null) {
                    //todo: message world not exist
                    return true;
                }
                regionName = worldParser.getConfigValue(WorldKeys.region, "default").toString();
            }

            SelectionAPI selectionAPI = api.selectionAPI;

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
                    region.set(RegionKeys.shape, api.serverAccessor.getShape(rtpWorld.name()));
                }
            }

            //todo: shape params
            //todo: vert params
            //todo: biomes

            if(region.hasLocation(p.uuid())) {
                //todo: initiate teleport action if here
            }

            //todo: default case, setupTeleport
            SetupTeleport setupTeleport = new SetupTeleport(sender, p, region, null);
            api.setupTeleportPipeline.add(setupTeleport);
        }
        return true;
    }

    public abstract void triggerFailEvent(RTPCommandSender player);

    @Override
    public String name() {
        return "rtp";
    }

    @Override
    public String permission() {
        return "rtp.use";
    }

    @Override
    public String description() {
        return "teleport randomly";
    }

    private static String pickOne(List<String> param, String d) {
        if(param == null || param.size()==0) return d;
        int sel = ThreadLocalRandom.current().nextInt(param.size());
        return param.get(sel);
    }
}
