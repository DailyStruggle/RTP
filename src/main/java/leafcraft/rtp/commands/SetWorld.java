package leafcraft.rtp.commands;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.SendMessage;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.logging.Level;

public class SetWorld implements CommandExecutor {
    private static final Set<String> worldParams = new HashSet<>();
    static {
        worldParams.add("world");
        worldParams.add("name");
        worldParams.add("region");
        worldParams.add("override");
    }

    private static Configs configs = null;

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if(configs == null) configs = RTP.getConfigs();

        if(!sender.hasPermission("rtp.setWorld")) {
            String msg = configs.lang.getLog("noPerms");
            
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        Map<String,String> worldArgs = new HashMap<>();
        for (String s : args) {
            int idx = s.indexOf(':');
            String arg = idx > 0 ? s.substring(0, idx) : s;
            if (worldParams.contains(arg)) {
                worldArgs.putIfAbsent(arg, s.substring(idx + 1)); //only use first instance
            }
        }

        World world;
        if(worldArgs.containsKey("world")) {
            String worldName = worldArgs.get("world");
            worldName = configs.worlds.worldPlaceholder2Name(worldName);
            if(!configs.worlds.checkWorldExists(worldName)) {
                String msg = configs.lang.getLog("invalidWorld",worldName);
                
                SendMessage.sendMessage(sender,msg);
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
                String msg = configs.lang.getLog("consoleCmdNotAllowed");
                SendMessage.sendMessage(sender,msg);
                return true;
            }
        }

        if(worldArgs.containsKey("region")) {
            String region = worldArgs.get("region");
            //check region exists
            String probe = (String) configs.regions.getRegionSetting(region,"world","");
            if(probe.equals("")) {
                RandomSelectParams params = new RandomSelectParams(Objects.requireNonNull(world),null);
                configs.regions.setRegion(region,params);
            }
        }

        for(Map.Entry<String,String> entry : worldArgs.entrySet()) {
            if(entry.getKey().equals("world")) continue;
            Integer result = configs.worlds.updateWorldSetting(Objects.requireNonNull(world),entry.getKey(),entry.getValue());
            if(result<0) {
                String msg = configs.lang.getLog("badArg", entry.getValue());
                SendMessage.sendMessage(sender,msg);
            }
        }
        Bukkit.getLogger().log(Level.INFO,configs.lang.getLog("updatingWorlds"));
        if(sender instanceof Player){
            String msg = configs.lang.getLog("updatingWorlds");
            SendMessage.sendMessage(sender,msg);
        }
        configs.worlds.update();
        Bukkit.getLogger().log(Level.INFO,configs.lang.getLog("updatedWorlds"));
        if(sender instanceof Player) {
            String msg = configs.lang.getLog("updatedWorlds");
            SendMessage.sendMessage(sender,msg);
        }

        return true;
    }
}