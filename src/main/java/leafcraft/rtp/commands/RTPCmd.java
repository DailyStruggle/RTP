package leafcraft.rtp.commands;

import leafcraft.rtp.API.Commands.SubCommand;
import leafcraft.rtp.API.customEvents.TeleportCommandSuccessEvent;
import leafcraft.rtp.API.selection.SyncState;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.SetupTeleport;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.SendMessage;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.softdepends.PAPIChecker;
import leafcraft.rtp.tools.softdepends.VaultChecker;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class RTPCmd implements CommandExecutor {
    private final Map<String, SubCommand> rtpCommands;
    private final Map<String,String> rtpParams = new HashMap<>();

    public RTPCmd(SubCommand command) {
        for(Map.Entry<String, ArrayList<String>> entry : Objects.requireNonNull(command.getSubParams())) {
            for(String param : entry.getValue()) {
                this.rtpParams.put(param,entry.getKey());
            }
        }
        rtpCommands = command.getSubCommands();
    }

    public void setSubCommand(String name, SubCommand subCommand) {
        rtpCommands.put(name,subCommand);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String[] args) {
        if(!command.getName().equalsIgnoreCase("rtp") && !command.getName().equalsIgnoreCase("wild")) return true;

        RTP plugin = RTP.getPlugin();
        Configs configs = RTP.getConfigs();
        Cache cache = RTP.getCache();

        long start = System.nanoTime();

        if(args.length > 0 && rtpCommands.containsKey(args[0])) {
            if(!sender.hasPermission(rtpCommands.get(args[0]).getPerm())) {
                String msg = configs.lang.getLog("noPerms");
                SendMessage.sendMessage(sender,msg);
            }
            else {
                return Objects.requireNonNull(rtpCommands.get(args[0]).getCommandExecutor())
                        .onCommand(sender,command,label,Arrays.copyOfRange(args, 1, args.length));
            }
            return true;
        }

        if(!sender.hasPermission("rtp.use")) {
            String msg = configs.lang.getLog("noPerms");

            SendMessage.sendMessage(sender,msg);
            return true;
        }

        //--teleport logic--
        //check for args
        Map<String,String> rtpArgs = new HashMap<>();
        for (String s : args) {
            int idx = s.indexOf(':');
            String arg = idx > 0 ? s.substring(0, idx) : s;
            if (this.rtpParams.containsKey(arg) && sender.hasPermission(rtpParams.get(arg)) && idx < s.length() - 1) {
                rtpArgs.putIfAbsent(arg, s.substring(idx + 1)); //only use first instance
            }
            if (s.equalsIgnoreCase("near:random")) rtpArgs.putIfAbsent("near", "random");
        }

        //set up player parameter
        Player player;
        if(sender.hasPermission("rtp.other") && rtpArgs.containsKey("player")) {
            player = Bukkit.getPlayer(rtpArgs.get("player"));
            if(player == null) {
                String msg = configs.lang.getLog("badArg", "player:"+rtpArgs.get("player"));
                SendMessage.sendMessage(sender,msg);
                return true;
            }
        }
        else if(sender instanceof Player) {
            player = (Player) sender;
        }
        else {
            String msg = configs.lang.getLog("consoleCmdNotAllowed");

            SendMessage.sendMessage(sender,msg);
            return true;
        }

        if(cache.setupTeleports.containsKey(player.getUniqueId())
                || cache.loadChunks.containsKey(player.getUniqueId())
                || cache.doTeleports.containsKey(player.getUniqueId())
                || cache.todoTP.containsKey(player.getUniqueId())
                || cache.queuedPlayers.contains(player.getUniqueId()))
        {
            String msg = configs.lang.getLog("alreadyTeleporting");
            PAPIChecker.fillPlaceholders(player,msg);
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        //set up world parameter
        World world;
        if(sender.hasPermission("rtp.region") && rtpArgs.containsKey("region")) {
            String regionName = rtpArgs.get("region");
            String worldName = (String) configs.regions.getRegionSetting(regionName,"world","");
            if (worldName == null
                    || worldName.equals("")
                    || (!sender.hasPermission("rtp.regions."+regionName)
                    && (Boolean)configs.worlds.getWorldSetting(worldName,"requirePermission",true))) {
                String msg = configs.lang.getLog("badArg", "region:" + regionName);

                SendMessage.sendMessage(sender,msg);
                return true;
            }
            if (!configs.worlds.checkWorldExists(worldName) || !sender.hasPermission("rtp.worlds."+worldName)) {
                String msg = configs.lang.getLog("badArg", "world:" + worldName);
                SendMessage.sendMessage(sender,msg);
                return true;
            }
            world = Bukkit.getWorld(worldName);
        }
        else {
            if (rtpArgs.containsKey("world") && sender.hasPermission("rtp.world")) {
                String worldName = rtpArgs.get("world");
                worldName = configs.worlds.worldPlaceholder2Name(worldName);
                if (!configs.worlds.checkWorldExists(worldName)) {
                    String msg = configs.lang.getLog("badArg", "world:" + worldName);
                    SendMessage.sendMessage(sender,msg);
                    return true;
                }
                if(sender.hasPermission("rtp.worlds."+worldName) || !((Boolean)configs.worlds.getWorldSetting(worldName,"requirePermission",true)))
                    world = Bukkit.getWorld(rtpArgs.get("world"));
                else {
                    world = player.getWorld();
                }
            }
            else {
                world = player.getWorld();
            }
        }
        String worldName = Objects.requireNonNull(world).getName();
        if (!sender.hasPermission("rtp.worlds." + worldName) && (Boolean) configs.worlds.getWorldSetting(worldName, "requirePermission", true)) {
            world = Bukkit.getWorld((String) configs.worlds.getWorldSetting(worldName,"override","[0]"));
            if(world == null || !configs.worlds.checkWorldExists(world.getName())) {
                String msg = configs.lang.getLog("badArg", "world:" + worldName);
                SendMessage.sendMessage(sender,msg);
                return true;
            }
        }

        //check time
        if(!sender.hasPermission("rtp.noCooldown")) {
            long lastTime = (sender instanceof Player) ? cache.lastTeleportTime.getOrDefault(((Player) sender).getUniqueId(), 0L) : 0;
            long cooldownTime = TimeUnit.SECONDS.toNanos(configs.config.teleportCooldown);
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
                if (days > 0) replacement += days + configs.lang.getLog("days") + " ";
                if (days > 0 || hours > 0) replacement += hours + configs.lang.getLog("hours") + " ";
                if (days > 0 || hours > 0 || minutes > 0) replacement += minutes + configs.lang.getLog("minutes") + " ";
                replacement += seconds + configs.lang.getLog("seconds");
                String msg = configs.lang.getLog("cooldownMessage", replacement);

                SendMessage.sendMessage(sender, msg);
                return true;
            }
        }

        if(rtpArgs.containsKey("near")) {
            String playerName = rtpArgs.get("near");
            Player targetPlayer = Bukkit.getPlayer(playerName);

            double price = configs.config.nearSelfPrice;
            Economy economy = VaultChecker.getEconomy();
            boolean has = true;
            if (economy != null
                    && sender instanceof Player
                    && !sender.hasPermission("rtp.near.free"))
                has = economy.has((Player) sender, price);

            if(playerName.equalsIgnoreCase("random") && sender.hasPermission("rtp.near.random")) {
                Collection<? extends Player> players = Bukkit.getOnlinePlayers();
                List<Player> validPlayers = new ArrayList<>(players.size());
                for(Player player1 : players) {
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
                    String msg = configs.lang.getLog("badArg", "near:" + playerName);

                    SendMessage.sendMessage(sender,msg);
                    return true;
                }
            }
            else if (!playerName.equals(sender.getName()) && !sender.hasPermission("rtp.near.other")) {
                String msg = configs.lang.getLog("noPerms");

                SendMessage.sendMessage(sender,msg);
                return true;
            }
            else if (!sender.hasPermission("rtp.near")) {
                String msg = configs.lang.getLog("noPerms");

                SendMessage.sendMessage(sender,msg);
                return true;
            }

            if (targetPlayer == null) {
                String msg = configs.lang.getLog("badArg", "near:" + playerName);

                SendMessage.sendMessage(sender,msg);
                return true;
            }

            if(!has) {
                String msg = configs.lang.getLog("notEnoughMoney",String.valueOf(price));
                PAPIChecker.fillPlaceholders((Player)sender,msg);
                SendMessage.sendMessage(sender,msg);
                return true;
            }

            if(sender instanceof Player
                    && (economy != null)
                    && !sender.hasPermission("rtp.near.free")) {
                economy.withdrawPlayer((Player)sender,price);
                cache.currentTeleportCost.put(((Player) sender).getUniqueId(), price);
            }

            String shapeStr = (String) configs.worlds.getWorldSetting(worldName, "nearShape", "CIRCLE");
            Integer radius = (Integer) configs.worlds.getWorldSetting(worldName, "nearRadius", 16);
            Integer centerRadius = (Integer) configs.worlds.getWorldSetting(worldName, "nearCenterRadius", 8);
            Integer minY = (Integer) configs.worlds.getWorldSetting(worldName, "nearMinY", 48);
            Integer maxY = (Integer) configs.worlds.getWorldSetting(worldName, "nearMaxY", 127);
            rtpArgs.putIfAbsent("shape", shapeStr);
            rtpArgs.putIfAbsent("radius",radius.toString());
            rtpArgs.putIfAbsent("centerRadius",centerRadius.toString());
            rtpArgs.putIfAbsent("minY",minY.toString());
            rtpArgs.putIfAbsent("maxY",maxY.toString());
            rtpArgs.putIfAbsent("centerX", String.valueOf(targetPlayer.getLocation().getChunk().getX()));
            rtpArgs.putIfAbsent("centerZ", String.valueOf(targetPlayer.getLocation().getChunk().getZ()));
            world = targetPlayer.getWorld();
            rtpArgs.putIfAbsent("world", world.getName());
        }
        else if(rtpArgs.containsKey("biome")) {
            Biome biome;
            try {
                biome = Biome.valueOf(rtpArgs.get("biome"));
            } catch (IllegalArgumentException | NullPointerException exception) {
                biome = null;
            }
            if(biome == null) {
                String msg = configs.lang.getLog("badArg", "biome:"+rtpArgs.get("biome"));
                SendMessage.sendMessage(sender,msg);
                return true;
            }

            double price = configs.config.biomePrice;
            Economy economy = VaultChecker.getEconomy();
            boolean has = true;
            if (economy != null
                    && sender instanceof Player
                    && !sender.hasPermission("rtp.biome.free"))
                has = economy.has((Player) sender, price);

            if (!sender.hasPermission("rtp.biome")) {
                String msg = configs.lang.getLog("noPerms");
                SendMessage.sendMessage(sender,msg);
                return true;
            }

            if(!has) {
                String msg = configs.lang.getLog("notEnoughMoney",String.valueOf(price));
                PAPIChecker.fillPlaceholders((Player)sender,msg);
                SendMessage.sendMessage(sender,msg);
                return true;
            }

            if(sender instanceof Player && !sender.hasPermission("rtp.biome.free")) {
                Objects.requireNonNull(economy).withdrawPlayer((Player)sender,price);
                cache.currentTeleportCost.put(((Player) sender).getUniqueId(), price);
            }
        }

        //set up parameters for selection
        RandomSelectParams rsParams = new RandomSelectParams(Objects.requireNonNull(world),rtpArgs);

        if(rsParams.params.containsKey("biome")) {
            try {
                Biome.valueOf(rsParams.params.get("biome"));
            } catch (IllegalArgumentException | NullPointerException exception) {
                String msg = configs.lang.getLog("badArg", "biome:"+rsParams.params.get("biome"));
                SendMessage.sendMessage(sender,msg);
                return true;
            }
        }

        boolean hasQueued = cache.permRegions.containsKey(rsParams) && cache.permRegions.get(rsParams).hasQueuedLocation(player.getUniqueId());

        //mark a successful command
        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->
                Bukkit.getPluginManager().callEvent(new TeleportCommandSuccessEvent(sender,player)));

        //prep teleportation
        cache.lastTeleportTime.put(player.getUniqueId(), start);
        cache.playerFromLocations.put(player.getUniqueId(),player.getLocation());
        cache.commandSenderLookup.put(player.getUniqueId(),sender);
        SetupTeleport setupTeleport = new SetupTeleport(sender,player, rsParams);
        if ((cache.permRegions.containsKey(rsParams)
                && hasQueued
                && sender.hasPermission("rtp.noDelay")
                && !rsParams.params.containsKey("biome"))
                || configs.config.syncLoading
                || RTP.getServerIntVersion()<=8) {
            setupTeleport.setupTeleportNow(SyncState.SYNC);
        } else {
            setupTeleport.runTaskAsynchronously(plugin);
            cache.setupTeleports.put(player.getUniqueId(), setupTeleport);
        }

        return true;
    }
}
