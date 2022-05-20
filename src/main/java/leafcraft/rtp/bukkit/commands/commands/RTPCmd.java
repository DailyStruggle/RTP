package leafcraft.rtp.bukkit.commands.commands;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.*;
import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import leafcraft.rtp.common.RTP;
import leafcraft.rtp.common.configuration.ConfigParser;
import leafcraft.rtp.common.configuration.MultiConfigParser;
import leafcraft.rtp.common.configuration.enums.ConfigKeys;
import leafcraft.rtp.common.configuration.enums.LangKeys;
import leafcraft.rtp.common.configuration.enums.RegionKeys;
import leafcraft.rtp.common.configuration.enums.WorldKeys;
import leafcraft.rtp.common.factory.Factory;
import leafcraft.rtp.common.playerData.TeleportData;
import leafcraft.rtp.common.selection.SelectionAPI;
import leafcraft.rtp.common.selection.region.Region;
import leafcraft.rtp.common.selection.region.selectors.shapes.Shape;
import leafcraft.rtp.bukkit.commands.parameters.RegionParameter;
import leafcraft.rtp.bukkit.RTPBukkitPlugin;
import leafcraft.rtp.bukkit.commands.parameters.ShapeParameter;
import leafcraft.rtp.bukkit.tools.SendMessage;
import leafcraft.rtp.common.substitutions.RTPWorld;
import leafcraft.rtp.common.tasks.SetupTeleport;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class RTPCmd extends BukkitTreeCommand {
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

    public RTPCmd(Plugin plugin) {
        super(plugin, null);

        //region name parameter
        // filter by region exists and sender permission
        addParameter("region", new RegionParameter(
                "rtp.region",
                "select a region to teleport to",
                (sender, s) -> RTP.getInstance().selectionAPI.regionNames().contains(s) && sender.hasPermission("rtp.regions."+s)));

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
    }

    //synchronous command component
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        long start = System.nanoTime();

        RTP api = RTP.getInstance();

        ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) api.configs.getParser(ConfigKeys.class);
        ConfigParser<LangKeys> langParser = (ConfigParser<LangKeys>) api.configs.getParser(LangKeys.class);

        //--------------------------------------------------------------------------------------------------------------
        //guard command perms with custom message
        if(!sender.hasPermission("rtp.use")) {
            String msg = (String) langParser.getConfigValue(LangKeys.noPerms, "");
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        //--------------------------------------------------------------------------------------------------------------
        //guard last teleport time synchronously to prevent spam
        TeleportData senderData = (sender instanceof  Player)
                ? api.latestTeleportData.getOrDefault(((Player) sender).getUniqueId(), new TeleportData())
                : new TeleportData();
        if(senderData.sender == null) {
            senderData.sender = CommandsAPI.serverId;
        }

        ConcurrentHashMap<UUID, TeleportData> latestTeleportData = RTP.getInstance().latestTeleportData;
        UUID uuid = (sender instanceof Player) ? ((Player) sender).getUniqueId() : CommandsAPI.serverId;
        if(!latestTeleportData.containsKey(uuid)) {
            TeleportData teleportData = new TeleportData();
            teleportData.time = 0;
            latestTeleportData.put(uuid,teleportData);
        }

        if(!sender.hasPermission("rtp.noCooldown")) {
            long lastTime;

            TeleportData teleportData = latestTeleportData.get(uuid);
            lastTime = teleportData.time;

            long cooldownTime = TimeUnit.SECONDS.toNanos((Long) configParser.getConfigValue(ConfigKeys.teleportCooldown,0));
            Set<PermissionAttachmentInfo> perms = sender.getEffectivePermissions();

            for(PermissionAttachmentInfo perm : perms) {
                if(!perm.getValue()) continue;
                String node = perm.getPermission();
                if(node.startsWith("rtp.cooldown.")) {
                    String[] val = node.split("\\.");
                    if(val.length<3 || val[2]==null || val[2].equals("")) continue;
                    int number;
                    try {
                        number = Integer.parseInt(val[2]);
                    } catch (NumberFormatException exception) {
                        Bukkit.getLogger().warning("[rtp] invalid permission: " + node);
                        continue;
                    }
                    cooldownTime = TimeUnit.SECONDS.toNanos(number);
                    break;
                }
            }

            if ((start - lastTime) < cooldownTime){
                long remaining = (lastTime - start) + cooldownTime;
                long days = TimeUnit.NANOSECONDS.toDays(remaining);
                long hours = TimeUnit.NANOSECONDS.toHours(remaining) % 24;
                long minutes = TimeUnit.NANOSECONDS.toMinutes(remaining) % 60;
                long seconds = TimeUnit.NANOSECONDS.toSeconds(remaining) % 60;
                String replacement = "";
                if (days > 0) replacement += days + (String) langParser.getConfigValue(LangKeys.days, 0) + " ";
                if (days > 0 || hours > 0) replacement += hours + (String) langParser.getConfigValue(LangKeys.hours, 0) + " ";
                if (days > 0 || hours > 0 || minutes > 0) replacement += minutes + (String) langParser.getConfigValue(LangKeys.minutes, 0) + " ";
                replacement += seconds + (String) langParser.getConfigValue(LangKeys.seconds, 0);
                String msg = (String) langParser.getConfigValue(LangKeys.cooldownMessage, replacement);

                SendMessage.sendMessage(sender, msg);
                return true;
            }

            teleportData.priorTime = teleportData.time;
            teleportData.time = start;
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

        return super.onCommand(sender, command, label, args);
    }

    //async command component
    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> rtpArgs, CommandsAPICommand nextCommand) {
        long start = System.nanoTime();
        RTPBukkitPlugin plugin = RTPBukkitPlugin.getInstance();
        RTP api = RTP.getInstance();
        int numBaseArgs = 0;

        ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) api.configs.getParser(ConfigKeys.class);
        ConfigParser<LangKeys> langParser = (ConfigParser<LangKeys>) api.configs.getParser(LangKeys.class);

        MultiConfigParser<WorldKeys> worldsParser = (MultiConfigParser<WorldKeys>) api.configs.getParser(WorldKeys.class);
        MultiConfigParser<RegionKeys> regionsParser = (MultiConfigParser<RegionKeys>) api.configs.getParser(RegionKeys.class);

        //--------------------------------------------------------------------------------------------------------------
        //collect target players to teleport
        List<Player> players = new ArrayList<>();
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

                //get their data
                TeleportData lastTeleportData = api.latestTeleportData.get(p.getUniqueId());
                //if player has an incomplete teleport
                if(lastTeleportData != null) {
                    if(!lastTeleportData.completed) {
                        String msg = (String) langParser.getConfigValue(LangKeys.alreadyTeleporting, "");
                        SendMessage.sendMessage(sender, p, msg);
                        continue;
                    }

                    lastTeleportData.priorTime = lastTeleportData.time;
                }
                else {
                    lastTeleportData = new TeleportData();
                    api.latestTeleportData.put(p.getUniqueId(),lastTeleportData);
                }

                players.add(p);

                lastTeleportData.time = start;
                lastTeleportData.completed = false;
            }
        }
        else if(sender instanceof Player) { //if no players but sender is a player, use sender's location
            players = new ArrayList<>(1);
            players.add((Player) sender);
        }
        else { //if no players and sender isn't a player, idk who to send
            String msg = (String) langParser.getConfigValue(LangKeys.consoleCmdNotAllowed,"");
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        for(int i = 0; i < players.size(); i++) {
            Player player = players.get(i);
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
                    //use player's world
                    worldName = player.getWorld().getName();
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
            String worldName = (String) region.getData().get(RegionKeys.world);
            RTPWorld rtpWorld = api.serverAccessor.getRTPWorld(worldName);
            Objects.requireNonNull(rtpWorld);

            SendMessage.sendMessage(sender,"C");

            //check for wbo
            if(rtpArgs.containsKey("worldBorderOverride")) {
                boolean doWBO;
                List<String> WBOVals = rtpArgs.get("worldBorderOverride");
                if(WBOVals.size() > i) doWBO = Boolean.parseBoolean(WBOVals.get(i));
                else doWBO = Boolean.parseBoolean(WBOVals.get(0));

                if(doWBO) {
                    region = region.clone();
                    region.set(RegionKeys.shape, api.serverAccessor.getShape(worldName));
                }
            }

            //todo: shape params
            //todo: vert params
            //todo: biomes

            if(region.hasLocation(player.getUniqueId())) {
                //todo: initiate teleport action if here
            }

            UUID senderId = (sender instanceof Player) ? ((Player) sender).getUniqueId() : CommandsAPI.serverId;

            //todo: default case, setupTeleport
            SetupTeleport setupTeleport = new SetupTeleport(senderId, player.getUniqueId(), region, null);
            api.setupTeleportPipeline.add(setupTeleport);
        }
        //todo
//        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->
//                Bukkit.getPluginManager().callEvent(new TeleportCommandSuccessEvent(sender,players.getFromString(0))));
//
//        for (int i = 0; i < players.size(); i++) {
//            Player p = players.getFromString(i);
//            World w = worlds.getFromString(i);
//            RegionParams rsParams = paramsList.getFromString(i);
//
//            ConcurrentHashMap<RegionParams, Region> permRegions = api.selectionAPI.permRegions;
//
//            boolean hasQueued = permRegions.containsKey(rsParams)
//                    && permRegions.getFromString(rsParams).hasQueuedLocation(p.getUniqueId());
//
//            //prep teleportation
//            TeleportData teleportData = new TeleportData();
//            teleportData.time = start;
//            teleportData.originalLocation = new long[]{p.getLocation().getBlockX(),p.getLocation().getBlockY(),p.getLocation().getBlockZ()};
//            teleportData.sender = senderData.sender;
//
//            SetupTeleport setupTeleport = new SetupTeleport(sender,p, rsParams);
//            if ((permRegions.containsKey(rsParams)
//                    && hasQueued
//                    && sender.hasPermission("rtp.noDelay")
//                    && !rsParams.params.containsKey("biome"))
//                    || api.configs.config.syncLoading
//                    || RTP.getInstance().getServerIntVersion()<=8) {
//                setupTeleport.setupTeleportNow(); //todo: go down this rabbit hole
//            } else {
//                setupTeleport.runTaskAsynchronously(plugin);
//            }
//        }

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
