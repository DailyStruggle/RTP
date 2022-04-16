package leafcraft.rtp.bukkit.commands.commands.help;

import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import leafcraft.rtp.bukkit.tools.SendMessage;
import leafcraft.rtp.bukkit.tools.configuration.Configs;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HelpCmd extends BukkitTreeCommand {
    private final Map<String,String> perms = new HashMap<>();

    public HelpCmd(Plugin plugin, CommandsAPICommand parent) {
        super(plugin, parent);

        this.perms.put("rtp","rtp.see");
        this.perms.put("help","rtp.see");
        this.perms.put("reload","rtp.reload");
        this.perms.put("setWorld","rtp.setWorld");
        this.perms.put("setRegion","rtp.setRegion");
        this.perms.put("fill","rtp.fill");
    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        if(!sender.hasPermission("rtp.see")) {
            String msg = Configs.lang.getLog("noPerms");
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        for(Map.Entry<String,String> entry : perms.entrySet()) {
            if(sender.hasPermission(entry.getValue())){
                String arg;
                if(entry.getKey().equals("rtp")) arg = "";
                else arg = entry.getKey();

                String msg = Configs.lang.getLog(entry.getKey());
                String hover = "/rtp " + arg;
                String click = "/rtp " + arg;

                SendMessage.sendMessage(sender,msg,hover,click);
            }
        }
        return true;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String permission() {
        return null;
    }

    @Override
    public String description() {
        return null;
    }
}