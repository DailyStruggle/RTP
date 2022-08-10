package io.github.dailystruggle.rtp.bukkit.commands;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.EnumParameter;
import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.OnlinePlayerParameter;
import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.WorldParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.commands.reload.ReloadCmdBukkit;
import io.github.dailystruggle.rtp.bukkit.events.TeleportCommandFailEvent;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPCommandSender;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.ShapeParameter;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.SetupTeleport;
import org.bukkit.Bukkit;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;

public class RTPCmdBukkit extends BukkitBaseRTPCmd {
    //for optimizing parameters,
    private final Factory<Shape<?>> shapeFactory
            = (Factory<Shape<?>>) RTP.getInstance().factoryMap.get(RTP.factoryNames.shape);

    private final SelectionAPI selectionAPI = RTP.getInstance().selectionAPI;

    private final Semaphore senderChecksGuard = new Semaphore(1);
    private final List<Predicate<CommandSender>> senderChecks = new ArrayList<>();

    public void addSenderCheck(Predicate<CommandSender> senderCheck) {
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

    public RTPCmdBukkit(Plugin plugin) {
        super(plugin, null);

        //region name parameter
        // filter by region exists and sender permission
        addParameter("region", new RegionParameter(
                "rtp.region",
                "select a region to teleport to",
                (uuid, s) -> RTP.getInstance().selectionAPI.regionNames().contains(s) && RTP.getInstance().serverAccessor.getSender(uuid).hasPermission("rtp.regions."+s)));

        //target player parameter
        // filter by player exists and player permission
        addParameter("player", new OnlinePlayerParameter(
                "rtp.other",
                "teleport someone else",
                (sender, s) -> {
                    Player player = Bukkit.getPlayer(s);
                    return player != null && !player.hasPermission("rtp.notme");
                }));

        //world name parameter
        // filter by world exists and sender permission
        addParameter("world", new WorldParameter(
                "rtp.world",
                "select a world to teleport to",
                (sender, s) -> Bukkit.getWorld(s)!=null && sender.hasPermission("rtp.worlds." + s)));

        //biome name parameter
        // filter by biome exists and sender permission
        addParameter("biome", new EnumParameter<>(
                "rtp.biome",
                "select a world to teleport to",
                (sender, s) -> Biome.valueOf(s.toUpperCase())!=null && sender.hasPermission("rtp.biome." + s),
                Biome.class));

        //wbo parameter
        addParameter("worldBorderOverride", new RegionParameter(
                "rtp.params",
                "override shape with worldborder",
                (sender, s) -> true));
        ShapeParameter shapeParameter = new ShapeParameter(
                "rtp.params",
                "adjust shape of target region",
                (sender, s) -> this.shapeFactory.contains(s));
        addParameter("shape", shapeParameter);
        Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(),()->{
            Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.getInstance().factoryMap.get(RTP.factoryNames.shape);
            for(var e : factory.map.entrySet()) {
                shapeParameter.putShape(e.getKey(),e.getValue().getParameters());
            }
        });

        addSubCommand(new ReloadCmdBukkit(plugin,this));
    }

    //synchronous command component
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        RTP rtp = RTP.getInstance();

        long timingsStart = System.nanoTime();

        UUID senderId = (sender instanceof Player player) ? player.getUniqueId() : CommandsAPI.serverId;
        if(rtp.processingPlayers.contains(senderId)) {
            rtp.serverAccessor.sendMessage(senderId,LangKeys.alreadyTeleporting);
            return true;
        }

        //--------------------------------------------------------------------------------------------------------------
        //guard command perms with custom message
        if(!sender.hasPermission("rtp.use")) {
            rtp.serverAccessor.sendMessage(senderId, LangKeys.noPerms);
            return true;
        }

        //--------------------------------------------------------------------------------------------------------------
        //guard last teleport time synchronously to prevent spam
        TeleportData senderData = (sender instanceof  Player)
                ? rtp.latestTeleportData.getOrDefault(((Player) sender).getUniqueId(), new TeleportData())
                : new TeleportData();
        if(senderData.sender == null) {
            senderData.sender = new BukkitRTPCommandSender(sender);
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

        if(!validSender) {
            return true;
        }

        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) rtp.configs.getParser(PerformanceKeys.class);
        boolean syncLoading = false;
        Object configValue = perf.getConfigValue(PerformanceKeys.syncLoading, false);
        if(configValue instanceof String s) {
            configValue = Boolean.parseBoolean(s);
        }
        if(configValue instanceof Boolean b) syncLoading = b;

        if(!senderId.equals(CommandsAPI.serverId)) rtp.processingPlayers.add(senderId);
        boolean res = super.onCommand(sender, command, label, args);

        if(syncLoading) {
            CommandsAPI.execute(Long.MAX_VALUE);
        }

        return res;
    }

    //async command component
    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> rtpArgs, CommandsAPICommand nextCommand) {
        long timingsStart = System.nanoTime();

        UUID senderId = (sender instanceof Player player) ? player.getUniqueId() : CommandsAPI.serverId;
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
        List<Player> players = new ArrayList<>();
        BukkitRTPCommandSender rtpCommandSender = new BukkitRTPCommandSender(sender);
        if(rtpArgs.containsKey("player")) { //if players are listed, use those.
            List<String> playerNames = rtpArgs.get("player");
            for (String playerName : playerNames) {
                //double check the player is still valid by the time we get here
                Player p = Bukkit.getPlayer(playerName);
                if (p == null) {
                    String msg = (String) langParser.getConfigValue(LangKeys.badArg, "player:" + rtpArgs.get("player"));
                    SendMessage.sendMessage(sender, msg);
                    continue;
                }

                players.add(p);
            }
        }
        else if(sender instanceof Player p) { //if no players but sender is a player, use sender's location
            players = new ArrayList<>(1);
            players.add(p);
        }
        else { //if no players and sender isn't a player, idk who to send
            String msg = (String) langParser.getConfigValue(LangKeys.consoleCmdNotAllowed,"");
            TeleportCommandFailEvent event = new TeleportCommandFailEvent(rtpCommandSender,msg);
            Bukkit.getPluginManager().callEvent(event);
            SendMessage.sendMessage(sender,event.getFailMsg());
            rtp.processingPlayers.remove(senderId);
            return true;
        }

        for(int i = 0; i < players.size(); i++) {
            Player p = players.get(i);

            //get their data
            TeleportData lastTeleportData = rtp.latestTeleportData.get(p.getUniqueId());
            //if p has an incomplete teleport
            if(lastTeleportData != null) {
                if(!lastTeleportData.completed) {
                    String msg = (String) langParser.getConfigValue(LangKeys.alreadyTeleporting, "");
                    SendMessage.sendMessage(sender, p, msg);
                    TeleportCommandFailEvent event = new TeleportCommandFailEvent(rtpCommandSender,msg);
                    Bukkit.getPluginManager().callEvent(event);
                    continue;
                }

                rtp.priorTeleportData.put(p.getUniqueId(),lastTeleportData);
            }
            lastTeleportData = new TeleportData();
            lastTeleportData.time = timingsStart;
            rtp.latestTeleportData.put(p.getUniqueId(),lastTeleportData);

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
                    worldName = p.getWorld().getName();
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
                    region.set(RegionKeys.shape, rtp.serverAccessor.getShape(rtpWorld.name()));
                }
            }

            List<String> biomeList = rtpArgs.get("biome");
            Set<String> biomes = null;
            if(biomeList!=null) biomes = new HashSet<>(biomeList);

            //todo: shape params
            //todo: vert params
            //todo: biomes

            SetupTeleport setupTeleport = new SetupTeleport(rtpCommandSender, new BukkitRTPPlayer(p), region, biomes);
            lastTeleportData.nextTask = setupTeleport;

            long delay = rtpCommandSender.delay();
            lastTeleportData.delay = delay;
            if(delay>0) {
                String msg = langParser.getConfigValue(LangKeys.delayMessage,"").toString();
                rtp.serverAccessor.sendMessage(rtpCommandSender.uuid(),p.getUniqueId(),msg);
            }

            if(region.hasLocation(p.getUniqueId()) && syncLoading && delay<=0) {
                setupTeleport.run();
            }
            else {
                rtp.setupTeleportPipeline.add(setupTeleport);
            }
        }

        return true;
    }

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
