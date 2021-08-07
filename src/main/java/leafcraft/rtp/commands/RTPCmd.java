package leafcraft.rtp.commands;

import leafcraft.rtp.tasks.SetupTeleport;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Config;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class RTPCmd implements CommandExecutor {
    private leafcraft.rtp.RTP plugin;
    private Config config;
    private Map<String,String> perms = new HashMap<>();
    private Map<String,String> rtpParams = new HashMap<>();

    private Cache cache;

    public RTPCmd(leafcraft.rtp.RTP plugin, Config config, Cache cache) {
        this.plugin = plugin;
        this.config = config;
        this.cache = cache;

        this.perms.put("set","rtp.set");
        this.perms.put("help","rtp.use");
        this.perms.put("reload","rtp.reload");

        this.rtpParams.put("player", "rtp.other");
        this.rtpParams.put("world", "rtp.world");

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
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(!command.getName().equalsIgnoreCase("rtp") && !command.getName().equalsIgnoreCase("wild")) return true;

        if(args.length > 0 && this.perms.containsKey(args[0])) {
            if(!sender.hasPermission(this.perms.get(args[0]))) {
                sender.sendMessage(this.config.getLog("noPerms"));
            }
            else {
                plugin.getCommand("rtp " + args[0]).execute(sender, label, Arrays.copyOfRange(args, 1, args.length));
            }
            return true;
        }

        if(!sender.hasPermission("rtp.use")) {
            sender.sendMessage(this.config.getLog("noPerms"));
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
        }

        //set up player parameter
        Player player;
        if(sender.hasPermission("rtp.other") && rtpArgs.containsKey("player")) {
            player = Bukkit.getPlayer(rtpArgs.get("player"));
            if(player == null) {
                sender.sendMessage(this.config.getLog("badArg", "player:"+rtpArgs.get("player")));
                return true;
            }
        }
        else if(sender instanceof Player) {
            player = (Player) sender;
        }
        else {
            sender.sendMessage(this.config.getLog("consoleCmdNotAllowed"));
            return true;
        }

        //set up world parameter
        World world;
        if(sender.hasPermission("rtp.world") && rtpArgs.containsKey("world")) {
            String worldName = rtpArgs.get("world");
            if(!this.config.checkWorldExists(worldName)) {
                sender.sendMessage(this.config.getLog("badArg", "world:"+worldName));
                return true;
            }
            world = Bukkit.getWorld(rtpArgs.get("world"));
        }
        else {
            world = player.getWorld();
        }
        if(!sender.hasPermission("rtp.worlds."+world.getName())) {
            String worldName = this.config.getWorldOverride(world.getName());
            if(!this.config.checkWorldExists(worldName)) {
                Bukkit.getLogger().log(Level.WARNING, this.config.getLog("invalidWorld", worldName));
                return true;
            }
            world = Bukkit.getWorld(worldName);
        }

        //check time
        long time = System.currentTimeMillis();
        long lastTime = (sender instanceof Player) ? this.cache.lastTeleportTime.getOrDefault(sender.getName(),Long.valueOf(0)) : 0;
        long cooldownTime = TimeUnit.SECONDS.toMillis((Integer)this.config.getConfigValue("teleportCooldown",300));
        if(!sender.hasPermission("rtp.instant")
                && time - lastTime < cooldownTime) {
            long remaining = (lastTime+cooldownTime)-time;
            long days = TimeUnit.MILLISECONDS.toDays(remaining);
            long hours = TimeUnit.MILLISECONDS.toHours(remaining)%24;
            long minutes = TimeUnit.MILLISECONDS.toMinutes(remaining)%60;
            long seconds = TimeUnit.MILLISECONDS.toSeconds(remaining)%60;
            String replacement = new String();
            if(days>0) replacement += days + this.config.getLog("days") + " ";
            if(days>0 || hours>0) replacement += hours + this.config.getLog("hours") + " ";
            if(days>0 || hours>0 || minutes>0) replacement += minutes + this.config.getLog("minutes") + " ";
            replacement += seconds + this.config.getLog("seconds");
            sender.sendMessage(config.getLog("cooldownMessage", replacement));
            return true;
        }

        RandomSelectParams rsParams = new RandomSelectParams(world,rtpArgs,config);
        new SetupTeleport(this.plugin,sender,player,this.config, this.cache, rsParams).runTaskAsynchronously(this.plugin);
        this.cache.lastTeleportTime.put(player.getName(), time);
        this.cache.playerFromLocations.put(player.getName(),player.getLocation());

        return true;
    }


}
