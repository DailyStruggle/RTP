package leafcraft.rtp.commands;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public class SetWorld implements CommandExecutor {
    private final RTP plugin;
    private final Configs configs;
    Cache cache;

    private final Set<String> worldParams = new HashSet<>();

    public SetWorld(RTP plugin, Configs configs) {
        this.plugin = plugin;
        this.configs = configs;
        this.cache = plugin.cache;

        worldParams.add("world");
        worldParams.add("name");
        worldParams.add("region");
        worldParams.add("override");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(!sender.hasPermission("rtp.setWorld")) {
            sender.sendMessage(configs.lang.getLog("noPerms"));
            return true;
        }

        Map<String,String> worldArgs = new HashMap<>();
        for(int i = 0; i < args.length; i++) {
            int idx = args[i].indexOf(':');
            String arg = idx>0 ? args[i].substring(0,idx) : args[i];
            if(this.worldParams.contains(arg)) {
                worldArgs.putIfAbsent(arg,args[i].substring(idx+1)); //only use first instance
            }
        }

        World world;
        if(worldArgs.containsKey("world")) {
            String worldName = worldArgs.get("world");
            worldName = configs.worlds.worldPlaceholder2Name(worldName);
            if(!configs.worlds.checkWorldExists(worldName)) {
                sender.sendMessage(configs.lang.getLog("invalidWorld",worldName));
                return true;
            }
            world = Bukkit.getWorld(worldName);
        }
        else {
            if(sender instanceof Player) {
                world = ((Player) sender).getWorld();
                worldArgs.put("world",world.getName());
            }
            else {
                sender.sendMessage(configs.lang.getLog("consoleCmdNotAllowed"));
                return true;
            }
        }

        if(worldArgs.containsKey("region")) {
            String region = worldArgs.get("region");
            //check region exists
            String probe = (String) configs.regions.getRegionSetting(region,"world","");
            if(probe.equals("")) {
                RandomSelectParams params = new RandomSelectParams(world,new HashMap<>(),configs);
                configs.regions.addRegion(region,params);
            }
        }

        for(Map.Entry<String,String> entry : worldArgs.entrySet()) {
            if(entry.getKey().equals("world")) continue;
            Integer result = configs.worlds.updateWorldSetting(world,entry.getKey(),entry.getValue());
            if(result<0) sender.sendMessage(configs.lang.getLog("badArg",entry.getValue()));
        }
        Bukkit.getLogger().log(Level.INFO,configs.lang.getLog("updatingWorlds"));
        if(sender instanceof Player)sender.sendMessage(configs.lang.getLog("updatingWorlds"));
        configs.worlds.update();
        Bukkit.getLogger().log(Level.INFO,configs.lang.getLog("updatedWorlds"));
        if(sender instanceof Player)sender.sendMessage(configs.lang.getLog("updatedWorlds"));

        return true;
    }
}