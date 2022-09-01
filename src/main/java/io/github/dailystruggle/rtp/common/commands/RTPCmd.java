package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.*;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPEconomy;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.SetupTeleport;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
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
        ConfigParser<EconomyKeys> eco = (ConfigParser<EconomyKeys>) rtp.configs.getParser(EconomyKeys.class);
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

        List<String> biomeList = rtpArgs.get("biome");
        List<String> shapeNames = rtpArgs.get("shape");
        double price = 0.0;
        RTPEconomy economy = RTP.economy;
        if(economy!=null && !senderId.equals(CommandsAPI.serverId)) {
            for (RTPPlayer player : players) {
                if(player.uuid().equals(senderId)) price+=eco.getNumber(EconomyKeys.price, 0.0).doubleValue();
                else if(player.hasPermission("rtp.notme")) continue;
                else price+=eco.getNumber(EconomyKeys.otherPrice, 0.0).doubleValue();
                if (shapeNames != null) price += eco.getNumber(EconomyKeys.paramsPrice, 0.0).doubleValue();
                if (biomeList != null) price += eco.getNumber(EconomyKeys.biomePrice, 0.0).doubleValue();
            }
        }
        double bal = economy.bal(senderId);
        double floor = eco.getNumber(EconomyKeys.balanceFloor, 0.0).doubleValue();
        if(bal-price<floor) {
            RTP.serverAccessor.sendMessage(senderId,LangKeys.notEnoughMoney);
            return true;
        }


        for(int i = 0; i < players.size(); i++) {
            RTPPlayer player = players.get(i);

            //get their data
            TeleportData data = rtp.latestTeleportData.get(player.uuid());
            //if player has an incomplete teleport
            if(data != null) {
                if(!data.completed) {
                    String msg = (String) langParser.getConfigValue(LangKeys.alreadyTeleporting, "");
                    RTP.serverAccessor.sendMessage(senderId, player.uuid(), msg);
                    failEvent(sender,msg);
                    continue;
                }

                rtp.priorTeleportData.put(player.uuid(), data);
            }
            data = new TeleportData();
            data.time = timingsStart;
            rtp.latestTeleportData.put(player.uuid(), data);

            String regionName;
            List<String> regionNames = rtpArgs.get("region");
            if(rtpArgs.containsKey("region")) {
                //todo: get one region from the list
                regionName = pickOne(regionNames,"default");
            }
            else {
                String worldName;
                //get region parameter from world options
                if(rtpArgs.containsKey("world")) {
                    //get one world from the list
                    worldName = pickOne(rtpArgs.get("world"),"default");
                }
                else {
                    //use player's world
                    worldName = player.getLocation().world().name();
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

            Region region;
            try {
                region = selectionAPI.getRegionOrDefault(regionName);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                rtp.processingPlayers.remove(senderId);
                rtp.latestTeleportData.remove(senderId);
                return true;
            }
            RTPWorld rtpWorld = (RTPWorld) region.getData().get(RegionKeys.world);
            Objects.requireNonNull(rtpWorld);

            //check for wbo
            boolean doWBO = false;
            if(rtpArgs.containsKey("worldBorderOverride")) {
                List<String> WBOVals = rtpArgs.get("worldBorderOverride");
                if(WBOVals.size() > i) doWBO = Boolean.parseBoolean(WBOVals.get(i));
                else doWBO = Boolean.parseBoolean(WBOVals.get(0));

                if(doWBO) {
                    region = region.clone();
                    region.set(RegionKeys.shape, RTP.serverAccessor.getShape(rtpWorld.name()));
                }
            }

            if(economy!=null) {
                RTP.log(Level.WARNING, "economy exists");
                if (player.uuid().equals(senderId)) data.cost += eco.getNumber(EconomyKeys.price, 0.0).doubleValue();
                else if (player.hasPermission("rtp.notme")) continue;
                else data.cost += eco.getNumber(EconomyKeys.otherPrice, 0.0).doubleValue();
                if (shapeNames != null || doWBO) data.cost += eco.getNumber(EconomyKeys.paramsPrice, 0.0).doubleValue();
                if (biomeList != null) data.cost += eco.getNumber(EconomyKeys.biomePrice, 0.0).doubleValue();

                if(economy.bal(senderId)-data.cost<floor) {
                    RTP.serverAccessor.sendMessage(senderId, LangKeys.notEnoughMoney);
                    return true;
                }

                boolean take = economy.take(senderId, data.cost);
                RTP.log(Level.WARNING, "cost: " + data.cost);
                if (!take) {
                    RTP.serverAccessor.sendMessage(senderId, LangKeys.notEnoughMoney);
                    return true;
                }
            }

            Set<String> biomes = null;
            if(biomeList!=null) biomes = new HashSet<>(biomeList);

            for(int j = 0; j<1 && shapeNames!=null && shapeNames.size()>0; j++) {
                Object o = region.getData().get(RegionKeys.shape);

                Shape<?> originalShape;
                if(!(o instanceof Shape<?> shape1)) break;
                originalShape = shape1;

                region = region.clone();
                rtp.selectionAPI.tempRegions.put(senderId,region);
                String shapeName = pickOne(shapeNames,"CIRCLE");
                Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
                Shape<?> shape = (Shape<?>) factory.get(shapeName);

                EnumMap<?, Object> originalShapeData = originalShape.getData();
                for(var entry : shape.getData().entrySet()) {
                    String name = entry.getKey().name();
                    if(name.equalsIgnoreCase("name")) continue;
                    if(name.equalsIgnoreCase("version")) continue;
                    if(rtpArgs.containsKey(name)) {
                        String string = pickOne(rtpArgs.get(name), "");

                        Object value;
                        if(string.equalsIgnoreCase("true")) {
                            value = true;
                        }
                        else if(string.equalsIgnoreCase("false")) {
                            value = false;
                        }
                        else {
                            try {
                                value = Long.parseLong(string);
                            } catch (IllegalArgumentException ignored) {
                                try {
                                    value = Double.parseDouble(string);
                                } catch (IllegalArgumentException ignored2) {
                                    try {
                                        value = Boolean.valueOf(string);
                                    } catch (IllegalArgumentException ignored3) {
                                        value = string;
                                    }
                                }
                            }
                        }

                        entry.setValue(value);
                    }

                    Enum<?> e;
                    try {
                        e = Enum.valueOf(originalShape.myClass, name);
                    }catch (IllegalArgumentException ignored) {
                        continue;
                    }

                    Object o1 = originalShapeData.get(e);
                    if((o1 instanceof Number) || entry.getValue().getClass().isAssignableFrom(o1.getClass()))
                        entry.setValue(o1);
                }
                region.set(RegionKeys.shape, shape);
            }

            //todo: vert params

            SetupTeleport setupTeleport = new SetupTeleport(sender, player, region, biomes);
            data.nextTask = setupTeleport;

            long delay = sender.delay();
            data.delay = delay;
            if(delay>0) {
                String msg = langParser.getConfigValue(LangKeys.delayMessage,"").toString();
                RTP.serverAccessor.sendMessage(senderId, player.uuid(),msg);
            }

            if(!syncLoading) {
                syncLoading = biomes == null
                        && region.hasLocation(player.uuid())
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
