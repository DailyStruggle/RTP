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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class RTPCmd implements CommandExecutor {
    private leafcraft.rtp.RTP plugin;
    private Config config;
    private Map<String,String> perms = new HashMap<String,String>();
    private Cache cache;

    public RTPCmd(leafcraft.rtp.RTP plugin, Config config, Cache cache) {
        this.plugin = plugin;
        this.config = config;
        this.cache = cache;

        this.perms.put("help","rtp.use");
        this.perms.put("reload","rtp.reload");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if(!command.getName().equalsIgnoreCase("rtp")) return true;

        if(args.length == 0) {
            if(     !(sender instanceof Player) ) {
                sender.sendMessage(this.config.getLog("consoleCmdNotAllowed"));
                return true;
            }
            Player player = (Player)sender;

            Long time = System.currentTimeMillis();
            Long lastTime = this.cache.getLastTeleportTime((Player) sender);
            Long cooldownTime = TimeUnit.SECONDS.toMillis((Integer)this.config.getConfigValue("teleportCooldown",300));

            World world = (player.hasPermission("rtp.worlds."+player.getWorld().getName())) ?
                    player.getWorld() : Bukkit.getWorld((String)this.config.getConfigValue("defaultWorld", "world"));

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
                new SetupTeleport(this.plugin, player,this.config, this.cache).runTask(this.plugin);
                this.cache.setLastTeleportTime(player, time);
                this.cache.setPlayerFromLocation(player,player.getLocation());
            }
        }
        else if(!this.perms.containsKey(args[0]))
            sender.sendMessage(this.config.getLog("badArg", args[0]));
        else if(!sender.hasPermission(this.perms.get(args[0])))
            sender.sendMessage(this.config.getLog("noPerms"));
        else
            plugin.getCommand("rtp " + args[0]).execute(sender, label, Arrays.copyOfRange(args, 1, args.length));

        return true;
    }
}
