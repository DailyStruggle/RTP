package leafcraft.rtp.commands;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Config;
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

public class SetCmd implements CommandExecutor {
    private RTP plugin;
    private Config config;

    private Set<String> setParams = new HashSet<>();

    public SetCmd(leafcraft.rtp.RTP plugin, Config config) {
        this.plugin = plugin;
        this.config = config;

        setParams.add("world");
        setParams.add("shape");
        setParams.add("radius");
        setParams.add("centerRadius");
        setParams.add("centerX");
        setParams.add("centerZ");
        setParams.add("weight");
        setParams.add("minY");
        setParams.add("maxY");
        setParams.add("requireSkyLight");
        setParams.add("requirePermission");
        setParams.add("override");

    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(!sender.hasPermission("rtp.set")) {
            sender.sendMessage(config.getLog("noPerms"));
            return true;
        }

        Map<String,String> setArgs = new HashMap<>();
        for(int i = 0; i < args.length; i++) {
            int idx = args[i].indexOf(':');
            String arg = idx>0 ? args[i].substring(0,idx) : args[i];
            if(this.setParams.contains(arg)) {
                setArgs.putIfAbsent(arg,args[i].substring(idx+1)); //only use first instance
            }
        }

        World world;
        if(setArgs.containsKey("world")) {
            String worldName = setArgs.get("world");
            if(!this.config.checkWorldExists(worldName)) {
                sender.sendMessage(this.config.getLog("invalidWorld",worldName));
                return true;
            }
            world = Bukkit.getWorld(worldName);
        }
        else {
            if(sender instanceof Player)
                world = ((Player) sender).getWorld();
            else {
                sender.sendMessage(this.config.getLog("consoleCmdNotAllowed"));
                return true;
            }
        }

        for(Map.Entry<String,String> entry : setArgs.entrySet()) {
            if(entry.getKey().equals("world")) continue;
            Integer isCoord = 0;
            if(entry.getKey().endsWith("X")) isCoord = 1;
            else if(entry.getKey().endsWith("Y")) isCoord = 2;
            else if(entry.getKey().endsWith("Z")) isCoord = 3;

            if( isCoord>0 ) {
                Integer res = 0;
                if(entry.getValue().startsWith("~")) {
                    if(!(sender instanceof Player)) {
                        sender.sendMessage(this.config.getLog("consoleCmdNotAllowed"));
                        continue;
                    }
                    switch(isCoord) {
                        case 1: res = ((Player)sender).getLocation().getBlockX(); break;
                        case 2: res = ((Player)sender).getLocation().getBlockY(); break;
                        case 3: res = ((Player)sender).getLocation().getBlockZ(); break;
                    }
                    String numStr;
                    if(entry.getValue().startsWith("~-")) {
                        numStr = entry.getValue().substring(2);
                        try{
                            if(numStr.length()>0) res -= Integer.valueOf(numStr);
                        }
                        catch (NumberFormatException exception) {
                            sender.sendMessage(this.config.getLog("badArg",entry.getKey()+":"+entry.getValue()));
                            continue;
                        }
                    }
                    else {
                        numStr = entry.getValue().substring(1);
                        try{
                            if(numStr.length()>0) res += Integer.valueOf(numStr);
                        }
                        catch (NumberFormatException exception) {
                            sender.sendMessage(this.config.getLog("badArg",entry.getKey()+":"+entry.getValue()));
                            continue;
                        }
                    }
                    entry.setValue(res.toString());
                }
            }

            if(entry.getKey().equals("override")) {
                String worldName = entry.getValue();
                if(!this.config.checkWorldExists(worldName)) {
                    sender.sendMessage(this.config.getLog("invalidWorld",worldName));
                    return true;
                }
            }
            Integer result = this.config.updateWorldSetting(world,entry.getKey(),entry.getValue());
            if(result<0) sender.sendMessage(this.config.getLog("badArg",entry.getValue()));
        }
        Bukkit.getLogger().log(Level.INFO,this.config.getLog("updatingWorlds"));
        if(sender instanceof Player)sender.sendMessage(this.config.getLog("updatingWorlds"));
        this.config.fillWorldsFile();
        Bukkit.getLogger().log(Level.INFO,this.config.getLog("updatedWorlds"));
        if(sender instanceof Player)sender.sendMessage(this.config.getLog("updatedWorlds"));

        return true;
    }
}
