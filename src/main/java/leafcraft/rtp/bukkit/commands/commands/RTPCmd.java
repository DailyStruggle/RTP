package leafcraft.rtp.bukkit.commands.commands;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.*;
import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.configuration.enums.ConfigKeys;
import leafcraft.rtp.api.configuration.enums.LangKeys;
import leafcraft.rtp.api.configuration.enums.RegionKeys;
import leafcraft.rtp.api.configuration.enums.WorldKeys;
import leafcraft.rtp.api.playerData.TeleportData;
import leafcraft.rtp.api.selection.RegionParams;
import leafcraft.rtp.bukkit.commands.parameters.RegionParameter;
import leafcraft.rtp.bukkit.api.substitutions.BukkitRTPWorld;
import leafcraft.rtp.bukkit.RTPBukkitPlugin;
import leafcraft.rtp.bukkit.tools.SendMessage;
import leafcraft.rtp.bukkit.tools.softdepends.PAPIChecker;
import leafcraft.rtp.bukkit.tools.softdepends.VaultChecker;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class RTPCmd extends BukkitTreeCommand {

    public RTPCmd(Plugin plugin) {
        super(plugin, null);
        addParameter("world", new WorldParameter(
                "rtp.world",
                "select a world to teleport to",
                (sender, s) -> sender.hasPermission("rtp.world") && sender.hasPermission("rtp.worlds."+s)));
        addParameter("region", new RegionParameter(
                "rtp.region",
                "select a region to teleport to",
                (sender, s) -> sender.hasPermission("rtp.region") && sender.hasPermission("rtp.regions."+s)));
        addParameter("player", new OnlinePlayerParameter(
                "rtp.other",
                "teleport someone else",
                (sender, s) -> Bukkit.getPlayer(s)!=null && !Bukkit.getPlayer(s).hasPermission("rtp.notme")));
        addParameter("shape", new RegionParameter(
                "rtp.params",
                "",
                (sender, s) -> sender.hasPermission("rtp.params")));
        addParameter("radius", new IntegerParameter(
                "rtp.params",
                "",
                (sender, s) -> sender.hasPermission("rtp.params"), 64,128,256,512,1024,2048,4096,8192));
        addParameter("centerRadius", new IntegerParameter(
                "rtp.params",
                "",
                (sender, s) -> sender.hasPermission("rtp.params"), 16,32,64,128,256,512,1024,2048));
        addParameter("centerX", new IntegerParameter(
                "rtp.params",
                "",
                (sender, s) -> sender.hasPermission("rtp.params"),"~","~-",0));
        addParameter("centerZ", new IntegerParameter(
                "rtp.params",
                "",
                (sender, s) -> sender.hasPermission("rtp.params"),"~","~-",0));
        addParameter("weight", new FloatParameter(
                "rtp.params",
                "",
                (sender, s) -> sender.hasPermission("rtp.params"), 0.01,0.5,0.99));
        addParameter("minY", new IntegerParameter(
                "rtp.params",
                "",
                (sender, s) -> sender.hasPermission("rtp.params"),"~","~-",32));
        addParameter("maxY", new IntegerParameter(
                "rtp.params",
                "",
                (sender, s) -> sender.hasPermission("rtp.params"),"~","~-",127));
        addParameter("requireSkyLight", new BooleanParameter(
                "rtp.params",
                "",
                (sender, s) -> sender.hasPermission("rtp.params")));
        addParameter("worldBorderOverride", new RegionParameter(
                "rtp.params",
                "",
                (sender, s) -> sender.hasPermission("rtp.params")));
    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> rtpArgs, CommandsAPICommand nextCommand) {
        RTPBukkitPlugin plugin = RTPBukkitPlugin.getInstance();
        RTPAPI api = RTPAPI.getInstance();

        long start = System.nanoTime();

        if(!sender.hasPermission("rtp.use")) {
            String msg = (String) api.configs.lang.getConfigValue(LangKeys.noPerms, "");
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        for(Map.Entry<String,List<String>> entry : rtpArgs.entrySet()) {
            if(!sender.hasPermission("rtp.world")) rtpArgs.remove("world");
            if(!sender.hasPermission("rtp.other")) rtpArgs.remove("player");

        }

        TeleportData senderData = (sender instanceof  Player)
                ? api.latestTeleportData.getOrDefault(((Player) sender).getUniqueId(), new TeleportData())
                : new TeleportData();
        if(senderData.sender == null) {
            senderData.sender = CommandsAPI.serverId;
        }

        //set up player parameter
        List<Player> players;
        Map<UUID,TeleportData> newTeleportDataMap = new HashMap<>();
        if(sender.hasPermission("rtp.other") && rtpArgs.containsKey("player")) {
            List<String> playerNames = rtpArgs.get("player");
            if(playerNames != null && playerNames.size()>0) {
                players = new ArrayList<>(playerNames.size());
                for (String playerName : playerNames) {
                    Player p = Bukkit.getPlayer(playerName);
                    if (p == null) {
                        String msg = (String) api.configs.lang.getConfigValue(LangKeys.badArg, "player:" + rtpArgs.get("player"));
                        SendMessage.sendMessage(sender, msg);
                        continue;
                    }

                    TeleportData lastTeleportData = api.latestTeleportData.get(p.getUniqueId());
                    if(lastTeleportData != null && !lastTeleportData.completed) {
                        String msg = (String) api.configs.lang.getConfigValue(LangKeys.alreadyTeleporting,"");
                        SendMessage.sendMessage(sender, p, msg);
                        continue;
                    }
                    players.add(p);
                    TeleportData newTeleportData = new TeleportData();
                    newTeleportData.completed = false;
                    newTeleportData.time = start;
                    newTeleportDataMap.put(p.getUniqueId(),newTeleportData);
                }
            } else if(sender instanceof Player) {
                players = new ArrayList<>(1);
                players.add((Player) sender);
            }
            else {
                String msg = (String) api.configs.lang.getConfigValue(LangKeys.consoleCmdNotAllowed,"");
                SendMessage.sendMessage(sender,msg);
                return true;
            }
        }
        else if(sender instanceof Player) {
            players = new ArrayList<>(1);
            players.add((Player) sender);
        }
        else {
            String msg = (String) api.configs.lang.getConfigValue(LangKeys.consoleCmdNotAllowed,"");
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        //set up world parameter, one for each player
        List<World> worlds = new ArrayList<>(players.size());
        if(sender.hasPermission("rtp.region") && rtpArgs.containsKey("region")) {
            List<String> regionNames = rtpArgs.get("region");
            if(regionNames.size()==0) {
                String msg = (String) api.configs.lang.getConfigValue(LangKeys.badArg, "region:");
                SendMessage.sendMessage(sender,msg);
                return true;
            }

            for(int i = 0; i < players.size(); i++) {
                int select = ThreadLocalRandom.current().nextInt(regionNames.size());
                String regionName = regionNames.get(select);
                String worldName = (String) api.configs.regions.getParser(regionName).getConfigValue(RegionKeys.world, "");
                if (worldName == null
                        || worldName.isBlank()
                        || (!sender.hasPermission("rtp.regions." + regionName)
                        && (Boolean) api.configs.worlds.getParser(worldName).getConfigValue(WorldKeys.requirePermission, true))) {
                    String msg = (String) api.configs.lang.getConfigValue(LangKeys.badArg, "region:" + regionName);

                    SendMessage.sendMessage(sender, msg);
                    return true;
                }
                if (!api.configs.checkWorldExists(worldName) || !sender.hasPermission("rtp.worlds." + worldName)) {
                    String msg = (String) api.configs.lang.getConfigValue(LangKeys.badArg, "world:" + worldName);
                    SendMessage.sendMessage(sender, msg);

                    return true;
                }
                worlds.add(Bukkit.getWorld(worldName));
            }
        }
        else {
            if (rtpArgs.containsKey("world") && sender.hasPermission("rtp.world")) {
                List<String> worldNames = rtpArgs.get("world");
                if(worldNames.size()==0) {
                    String msg = (String) api.configs.lang.getConfigValue(LangKeys.badArg, "world:");
                    SendMessage.sendMessage(sender,msg);
                    return true;
                }

                for (Player player : players) {
                    String worldName;
                    World world;
                    int select = ThreadLocalRandom.current().nextInt(worldNames.size());
                    worldName = worldNames.get(select);
                    if (!api.configs.checkWorldExists(worldName)) {
                        String msg = (String) api.configs.lang.getConfigValue(LangKeys.badArg, "world:" + worldName);
                        SendMessage.sendMessage(sender, msg);
                        return true;
                    }
                    if (sender.hasPermission("rtp.worlds." + worldName) ||
                            !((Boolean) api.configs.worlds.getParser(worldName).getConfigValue( WorldKeys.requirePermission, true)))
                        world = Bukkit.getWorld(worldName);
                    else {
                        world = player.getWorld();
                    }
                    worlds.add(world);
                }
            }
            else {
                for (Player player : players) {
                    World world = player.getWorld();
                    worlds.add(world);
                }
            }
        }

        for(World world : worlds) {
            String worldName = Objects.requireNonNull(world).getName();
            if (!sender.hasPermission("rtp.worlds." + worldName) && (Boolean) api.configs.worlds.getParser(worldName).getConfigValue( WorldKeys.requirePermission, true)) {
                world = Bukkit.getWorld((String) api.configs.worlds.getParser(worldName).getConfigValue( WorldKeys.override, "[0]"));
                if (world == null || !api.configs.checkWorldExists(world.getName())) {
                    String msg = (String) api.configs.lang.getConfigValue(LangKeys.badArg, "world:" + worldName);
                    SendMessage.sendMessage(sender, msg);
                    return true;
                }
            }
        }

        //check time
        if(!sender.hasPermission("rtp.noCooldown")) {
            long lastTime;
            if(sender instanceof Player) {
                TeleportData teleportData = RTPAPI.getInstance().latestTeleportData.get(((Player)sender).getUniqueId());
                if(teleportData != null) {
                    lastTime = teleportData.time;
                }
                else lastTime = 0;
            }
            else lastTime = 0;

            long cooldownTime = TimeUnit.SECONDS.toNanos((Long) api.configs.config.getConfigValue(ConfigKeys.teleportCooldown,0));
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
                if (days > 0) replacement += days + (String) api.configs.lang.getConfigValue(LangKeys.days, 0) + " ";
                if (days > 0 || hours > 0) replacement += hours + (String) api.configs.lang.getConfigValue(LangKeys.hours, 0) + " ";
                if (days > 0 || hours > 0 || minutes > 0) replacement += minutes + (String) api.configs.lang.getConfigValue(LangKeys.minutes, 0) + " ";
                replacement += seconds + (String) api.configs.lang.getConfigValue(LangKeys.seconds, 0);
                String msg = (String) api.configs.lang.getConfigValue(LangKeys.cooldownMessage, replacement);

                SendMessage.sendMessage(sender, msg);
                return true;
            }
        }

        if(rtpArgs.containsKey("near")) {
            //select from one of these
            List<String> playerNames = rtpArgs.get("near");
            if(playerNames.size()==0) {
                String msg = (String) api.configs.lang.getConfigValue(LangKeys.badArg, "near:");
                SendMessage.sendMessage(sender,msg);
                return true;
            }

            String playerName = playerNames.get(ThreadLocalRandom.current().nextInt(playerNames.size()));
            Player targetPlayer = Bukkit.getPlayer(playerName);

            //todo: get price
            double price = 0.0; // api.configs.config.getConfigValue(ConfigKeys.nearSelfPrice);
            Economy economy = VaultChecker.getEconomy();
            boolean has = true;
            if (economy != null
                    && sender instanceof Player
                    && !sender.hasPermission("rtp.near.free"))
                has = economy.has((Player) sender, price);

            if(playerName.equalsIgnoreCase("random") && sender.hasPermission("rtp.near.random")) {
                Collection<? extends Player> randomPlayers = Bukkit.getOnlinePlayers();
                List<Player> validPlayers = new ArrayList<>(randomPlayers.size());
                for(Player player1 : randomPlayers) {
                    if(player1.getName().equals(sender.getName())) continue;
                    if(player1.hasPermission("rtp.near.notme")) continue;
                    validPlayers.add(player1);
                }
                if(validPlayers.size()>0) {
                    int selection = ThreadLocalRandom.current().nextInt(validPlayers.size());
                    targetPlayer = validPlayers.get(selection);
                    playerName = targetPlayer.getName();
                }
                else {
                    String msg = (String) api.configs.lang.getConfigValue(LangKeys.badArg, "near:" + playerName);

                    SendMessage.sendMessage(sender,msg);
                    return true;
                }
            }
            else if (!playerName.equals(sender.getName()) && !sender.hasPermission("rtp.near.other")) {
                String msg = (String) api.configs.lang.getConfigValue(LangKeys.noPerms,"");

                SendMessage.sendMessage(sender,msg);
                return true;
            }
            else if (!sender.hasPermission("rtp.near")) {
                String msg = (String) api.configs.lang.getConfigValue(LangKeys.noPerms,"");

                SendMessage.sendMessage(sender,msg);
                return true;
            }

            if (targetPlayer == null) {
                String msg = (String) api.configs.lang.getConfigValue(LangKeys.badArg, "near:" + playerName);

                SendMessage.sendMessage(sender,msg);
                return true;
            }

            if(!has) {
                String msg = (String) api.configs.lang.getConfigValue(LangKeys.notEnoughMoney,String.valueOf(price));
                PAPIChecker.fillPlaceholders((Player)sender,msg);
                SendMessage.sendMessage(sender,msg);
                return true;
            }

            if(sender instanceof Player
                    && (economy != null)
                    && !sender.hasPermission("rtp.near.free")) {
                economy.withdrawPlayer((Player)sender,price);
                senderData.cost = price;
            }

            //todo
            for (int i = 0; i < players.size(); i++) {
                Player p = players.get(i);
                TeleportData teleportData = newTeleportDataMap.getOrDefault(p.getUniqueId(), new TeleportData());
                RegionParams regionParams;
                Map<String,String> newParams;
                newParams = new HashMap<>();
                worlds.set(i,targetPlayer.getWorld());
                newParams.putIfAbsent("world", targetPlayer.getWorld().getName());
                regionParams = new RegionParams(new BukkitRTPWorld(targetPlayer.getWorld()), newParams);
                teleportData.givenParams = regionParams;
                newTeleportDataMap.put(p.getUniqueId(),teleportData);
            }
        }

        List<Biome> biomes = new ArrayList<>(players.size());
        if(rtpArgs.containsKey("biome")) {
            List<String> biomeNames = rtpArgs.get("biome");
            if(biomeNames.size()==0) {
                String msg = (String) api.configs.lang.getConfigValue(LangKeys.badArg, "biome:");
                SendMessage.sendMessage(sender,msg);
                return true;
            }

            String biomeName = biomeNames.get(ThreadLocalRandom.current().nextInt(biomeNames.size()));

            Biome biome;
            try {
                biome = Biome.valueOf(biomeName.toUpperCase());
            } catch (IllegalArgumentException | NullPointerException exception) {
                biome = null;
            }
            if(biome == null) {
                String msg = (String) api.configs.lang.getConfigValue(LangKeys.badArg, "biome:"+rtpArgs.get("biome"));
                SendMessage.sendMessage(sender,msg);
                return true;
            }

            //todo
            double price = 0.0; // api.configs.config.biomePrice;
            Economy economy = VaultChecker.getEconomy();
            boolean has = true;
            if (economy != null
                    && sender instanceof Player
                    && !sender.hasPermission("rtp.biome.free"))
                has = economy.has((Player) sender, price);

            if (!sender.hasPermission("rtp.biome")) {
                String msg = (String) api.configs.lang.getConfigValue(LangKeys.noPerms,"");
                SendMessage.sendMessage(sender,msg);
                return true;
            }

            if(!has) {
                String msg = (String) api.configs.lang.getConfigValue(LangKeys.notEnoughMoney,String.valueOf(price));
                PAPIChecker.fillPlaceholders((Player)sender,msg);
                SendMessage.sendMessage(sender,msg);
                return true;
            }

            if(sender instanceof Player && !sender.hasPermission("rtp.biome.free")) {
                Objects.requireNonNull(economy).withdrawPlayer((Player)sender,price);
                senderData.cost = price;
            }
        }

        //set up parameters for selection
        List<RegionParams> paramsList = new ArrayList<>(players.size());
        for (World world : worlds) {
            //todo: fill out params to feed in
            paramsList.add(new RegionParams());
        }

        StringBuilder biomeConsolidator = new StringBuilder();
        if(rtpArgs.containsKey("biome")) {
            List<String> biomeNamesInput = rtpArgs.get("biome");
            for(String biomeName : biomeNamesInput) {
                try{
                    Biome.valueOf(biomeName.toUpperCase());
                    biomeConsolidator.append(biomeName).append(',');
                }
                catch (IllegalArgumentException e) {
                    SendMessage.sendMessage(sender,(String) api.configs.lang.getConfigValue(LangKeys.badArg,"biome:"+biomeName));
                }
            }
        }

        //todo: uncomment
        //mark a successful command

        SendMessage.sendMessage(sender,"todo: config files");



        //todo: give player list
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
//                    || RTPAPI.getInstance().getServerIntVersion()<=8) {
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

    private static String pickOne(List<String> param, Predicate<? super String> validator, String d) {
        if(param == null || param.size()==0) return d;
        List<String> validSelections = param.stream().filter(validator).collect(Collectors.toList());
        if(validSelections.size() == 0) return d;
        int sel = ThreadLocalRandom.current().nextInt(param.size());
        return param.get(sel);
    }
}
