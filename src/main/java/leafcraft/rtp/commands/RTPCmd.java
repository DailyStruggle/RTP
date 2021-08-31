package leafcraft.rtp.commands;

import leafcraft.rtp.tasks.SetupTeleport;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.softdepends.PAPIChecker;
import leafcraft.rtp.tools.softdepends.VaultChecker;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class RTPCmd implements CommandExecutor {
    private final leafcraft.rtp.RTP plugin;
    private final Configs configs;
    private final Map<String,String> rtpCommands = new HashMap<>();
    private final Map<String,String> rtpParams = new HashMap<>();

    private final Cache cache;

    public RTPCmd(leafcraft.rtp.RTP plugin, Configs configs, Cache cache) {
        this.plugin = plugin;
        this.configs = configs;
        this.cache = cache;

        this.rtpCommands.put("help","rtp.use");
        this.rtpCommands.put("reload","rtp.reload");
        this.rtpCommands.put("setRegion","rtp.setRegion");
        this.rtpCommands.put("setWorld","rtp.setWorld");
        this.rtpCommands.put("fill","rtp.fill");

        this.rtpParams.put("player", "rtp.other");
        this.rtpParams.put("world", "rtp.world");
        this.rtpParams.put("region","rtp.region");
        this.rtpParams.put("shape","rtp.params");
        this.rtpParams.put("radius","rtp.params");
        this.rtpParams.put("centerRadius","rtp.params");
        this.rtpParams.put("centerX","rtp.params");
        this.rtpParams.put("centerZ","rtp.params");
        this.rtpParams.put("weight","rtp.params");
        this.rtpParams.put("minY","rtp.params");
        this.rtpParams.put("maxY","rtp.params");
        this.rtpParams.put("requireSkyLight","rtp.params");
        this.rtpParams.put("worldBorderOverride","rtp.params");
        this.rtpParams.put("near","rtp.near");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(!command.getName().equalsIgnoreCase("rtp") && !command.getName().equalsIgnoreCase("wild")) return true;

        long start = System.nanoTime();

        if(args.length > 0 && rtpCommands.containsKey(args[0])) {
            if(!sender.hasPermission(rtpCommands.get(args[0]))) {
                String msg = configs.lang.getLog("noPerms");
                if(sender instanceof Player) msg = PAPIChecker.fillPlaceholders((Player)sender,msg);
                sender.sendMessage(msg);
            }
            else {
                plugin.getCommand("rtp " + args[0]).execute(sender, label, Arrays.copyOfRange(args, 1, args.length));
            }
            return true;
        }

        if(!sender.hasPermission("rtp.use")) {
            String msg = configs.lang.getLog("noPerms");
            if(sender instanceof Player) msg = PAPIChecker.fillPlaceholders((Player)sender,msg);
            sender.sendMessage(msg);
            return true;
        }

        //--teleport logic--
        //check for args
        Map<String,String> rtpArgs = new HashMap<>();
        for(int i = 0; i < args.length; i++) {
            int idx = args[i].indexOf(':');
            String arg = idx>0 ? args[i].substring(0,idx) : args[i];
            if(this.rtpParams.containsKey(arg) && sender.hasPermission(rtpParams.get(arg)) && idx < args[i].length()-1) {
                rtpArgs.putIfAbsent(arg,args[i].substring(idx+1)); //only use first instance
            }
            if(args[i].equalsIgnoreCase("near:random")) rtpArgs.putIfAbsent("near","random");
        }

        //set up player parameter
        Player player;
        if(sender.hasPermission("rtp.other") && rtpArgs.containsKey("player")) {
            player = Bukkit.getPlayer(rtpArgs.get("player"));
            if(player == null) {
                String msg = configs.lang.getLog("badArg", "player:"+rtpArgs.get("player"));
                if(sender instanceof Player) msg = PAPIChecker.fillPlaceholders((Player)sender,msg);
                sender.sendMessage(msg);
                return true;
            }
        }
        else if(sender instanceof Player) {
            player = (Player) sender;
        }
        else {
            String msg = configs.lang.getLog("consoleCmdNotAllowed");
            if(sender instanceof Player) msg = PAPIChecker.fillPlaceholders((Player)sender,msg);
            sender.sendMessage(msg);
            return true;
        }

        //set up world parameter
        World world;
        if(sender.hasPermission("rtp.region") && rtpArgs.containsKey("region")) {
            String regionName = rtpArgs.get("region");
            String worldName = (String) configs.regions.getRegionSetting(regionName,"world","");
            if (worldName == null
                    || worldName == ""
                    || (!sender.hasPermission("rtp.regions."+regionName)
                    && (Boolean)configs.worlds.getWorldSetting(worldName,"requirePermission",true))) {
                String msg = configs.lang.getLog("badArg", "region:" + regionName);
                if(sender instanceof Player) msg = PAPIChecker.fillPlaceholders((Player)sender,msg);
                sender.sendMessage(msg);
                return true;
            }
            if (!configs.worlds.checkWorldExists(worldName) || !sender.hasPermission("rtp.worlds."+worldName)) {
                String msg = configs.lang.getLog("badArg", "world:" + worldName);
                if(sender instanceof Player) msg = PAPIChecker.fillPlaceholders((Player)sender,msg);
                sender.sendMessage(msg);
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
                    if(sender instanceof Player) msg = PAPIChecker.fillPlaceholders((Player)sender,msg);
                    sender.sendMessage(msg);
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
        String worldName = world.getName();
        if (!sender.hasPermission("rtp.worlds." + worldName) && (Boolean) configs.worlds.getWorldSetting(worldName, "requirePermission", true)) {
            world = Bukkit.getWorld((String) configs.worlds.getWorldSetting(worldName,"override","world"));
        }

        //check time
        long lastTime = (sender instanceof Player) ? this.cache.lastTeleportTime.getOrDefault(((Player) sender).getUniqueId(),0L) : 0;
        long cooldownTime = TimeUnit.SECONDS.toNanos(configs.config.teleportCooldown);
        if(!sender.hasPermission("rtp.noCooldown")
                && start - lastTime < cooldownTime) {
            long remaining = (lastTime-start)+cooldownTime;
            long days = TimeUnit.NANOSECONDS.toDays(remaining);
            long hours = TimeUnit.NANOSECONDS.toHours(remaining)%24;
            long minutes = TimeUnit.NANOSECONDS.toMinutes(remaining)%60;
            long seconds = TimeUnit.NANOSECONDS.toSeconds(remaining)%60;
            String replacement = "";
            if(days>0) replacement += days + configs.lang.getLog("days") + " ";
            if(days>0 || hours>0) replacement += hours + configs.lang.getLog("hours") + " ";
            if(days>0 || hours>0 || minutes>0) replacement += minutes + configs.lang.getLog("minutes") + " ";
            replacement += seconds + configs.lang.getLog("seconds");
            String msg = configs.lang.getLog("cooldownMessage", replacement);
            if(sender instanceof Player) msg = PAPIChecker.fillPlaceholders((Player)sender,msg);
            sender.sendMessage(msg);
            return true;
        }

        if(rtpArgs.containsKey("near")) {
            String playerName = rtpArgs.get("near");
            Player targetPlayer = Bukkit.getPlayer(playerName);


            int price = configs.config.nearSelfPrice;
            Economy economy = VaultChecker.getEconomy();
            boolean has = true;
            if (economy != null && sender instanceof Player) has = economy.has((Player) sender, price);

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
                    if (sender instanceof Player) msg = PAPIChecker.fillPlaceholders((Player) sender, msg);
                    sender.sendMessage(msg);
                    return true;
                }
            }
            else if (!playerName.equals(sender.getName()) && !sender.hasPermission("rtp.near.other")) {
                String msg = configs.lang.getLog("noPerms");
                if (sender instanceof Player) msg = PAPIChecker.fillPlaceholders((Player) sender, msg);
                sender.sendMessage(msg);
                return true;
            }
            else if (!sender.hasPermission("rtp.near")) {
                String msg = configs.lang.getLog("noPerms");
                if (sender instanceof Player) msg = PAPIChecker.fillPlaceholders((Player) sender, msg);
                sender.sendMessage(msg);
                return true;
            }

            if (targetPlayer == null) {
                String msg = configs.lang.getLog("badArg", "near:" + playerName);
                if (sender instanceof Player) msg = PAPIChecker.fillPlaceholders((Player) sender, msg);
                sender.sendMessage(msg);
                return true;
            }

            if(!has) {
                String msg = configs.lang.getLog("notEnoughMoney",String.valueOf(price));
                PAPIChecker.fillPlaceholders((Player)sender,msg);
                sender.sendMessage(msg);
                return true;
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

        //set up parameters for selection
        RandomSelectParams rsParams = new RandomSelectParams(world,rtpArgs,configs);

        //prep teleportation
        this.cache.lastTeleportTime.put(player.getUniqueId(), start);
        this.cache.playerFromLocations.put(player.getUniqueId(),player.getLocation());
        SetupTeleport setupTeleport = new SetupTeleport(plugin,sender,player,configs, cache, rsParams);
        cache.setupTeleports.put(player.getUniqueId(),setupTeleport);
        if(cache.permRegions.containsKey(rsParams) && cache.permRegions.get(rsParams).hasQueuedLocation(player)) {
            setupTeleport.setupTeleportNow(false);
        }
        else {
            setupTeleport.runTaskAsynchronously(plugin);
        }

        return true;
    }
}
