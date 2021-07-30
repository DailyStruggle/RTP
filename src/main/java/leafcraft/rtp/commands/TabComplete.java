package leafcraft.rtp.commands;

import leafcraft.rtp.tools.Config;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TabComplete implements TabCompleter {
    private Map<String,String> subCommands = new HashMap<String,String>();

    private Config config;

    public TabComplete(Config config) {
        //load OnePlayerSleep.commands and permission nodes into map
        subCommands.put("reload","rtp.reload");
        subCommands.put("help","rtp.see");
        this.config = config;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (!sender.hasPermission("rtp.see")) return null;
        List<String> res = new ArrayList<>();

        switch (args.length) {
            case 1: { //help, reload, world
                // TODO: rtp player by name
                // TODO: rtp by world
                List<String> subCom = new ArrayList<String>();
                for(Map.Entry<String,String> entry : subCommands.entrySet()) {
                    if(sender.hasPermission(entry.getValue()))
                        subCom.add(entry.getKey());
                }
                StringUtil.copyPartialMatches(args[0],subCom,res);
                break;
            }
            case 2: //
        }

        return res;
    }
}
