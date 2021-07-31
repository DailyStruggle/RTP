package leafcraft.rtp.commands;

import leafcraft.rtp.tasks.SetupTeleport;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Config;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

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

        this.perms.put("help","rtp.use");
        this.perms.put("reload","rtp.reload");

        // TODO: rtp set
        this.rtpParams.put("player", "rtp.other");
        this.rtpParams.put("world", "rtp.world");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if(!command.getName().equalsIgnoreCase("rtp") && !command.getName().equalsIgnoreCase("wild")) return true;

        if(args.length > 0 && this.perms.containsKey(args[0])) {
            if(!sender.hasPermission(this.perms.get(args[0])))
                sender.sendMessage(this.config.getLog("noPerms"));
            else {
                plugin.getCommand("rtp " + args[0]).execute(sender, label, Arrays.copyOfRange(args, 1, args.length));
            }
            return true;
        }

        //check for args
        Map<String,String> rtpArgs = new HashMap<>();
        for(int i = 0; i < args.length; i++) {
            int idx = args[i].indexOf(':');
            String arg = idx>0 ? args[i].substring(0,idx) : args[i];
            if(this.rtpParams.keySet().contains(arg) && idx < args[i].length()-1) {
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

        Long time = System.currentTimeMillis();
        Long lastTime = (sender instanceof Player) ? this.cache.getLastTeleportTime((Player) sender) : 0;
        Long cooldownTime = TimeUnit.SECONDS.toMillis((Integer)this.config.getConfigValue("teleportCooldown",300));
        if(!sender.hasPermission("rtp.use"))
            sender.sendMessage(this.config.getLog("noPerms"));
        else if(!sender.hasPermission("rtp.instant")
                && time - lastTime < cooldownTime) {
            Long remaining = (lastTime+cooldownTime)-time;
            Long days = TimeUnit.MILLISECONDS.toDays(remaining);
            Long hours = TimeUnit.MILLISECONDS.toHours(remaining)%24;
            Long minutes = TimeUnit.MILLISECONDS.toMinutes(remaining)%60;
            Long seconds = TimeUnit.MILLISECONDS.toSeconds(remaining)%60;
            String replacement = new String();
            if(days>0) replacement += days + this.config.getLog("days") + " ";
            if(days>0 || hours>0) replacement += hours + this.config.getLog("hours") + " ";
            if(days>0 || hours>0 || minutes>0) replacement += minutes + this.config.getLog("minutes") + " ";
            replacement += seconds + this.config.getLog("seconds");
            sender.sendMessage(config.getLog("cooldownMessage", replacement));
        }
        else {
            new SetupTeleport(this.plugin,sender,player,world,this.config, this.cache).runTask(this.plugin);
            this.cache.setLastTeleportTime(player, time);
            this.cache.setPlayerFromLocation(player,player.getLocation());
        }

        return true;
    }
}
