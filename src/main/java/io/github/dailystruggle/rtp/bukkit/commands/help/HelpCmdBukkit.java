package io.github.dailystruggle.rtp.bukkit.commands.help;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.bukkit.commands.BukkitBaseRTPCmd;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HelpCmdBukkit extends BukkitBaseRTPCmd {
    private final Map<String,String> perms = new ConcurrentHashMap<>();

    public HelpCmdBukkit(Plugin plugin, CommandsAPICommand parent) {
        super(plugin, parent);

        this.perms.put("rtp","rtp.see");
        this.perms.put("help","rtp.see");
    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        ConfigParser<LangKeys> lang = (ConfigParser<LangKeys>) RTP.getInstance().configs.getParser(LangKeys.class);
        if(!sender.hasPermission("rtp.see")) {
            String msg = String.valueOf(lang.getConfigValue(LangKeys.noPerms,""));
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        for(Map.Entry<String,String> entry : perms.entrySet()) {
            if(sender.hasPermission(entry.getValue())){
                String arg;
                if(entry.getKey().equals("rtp")) arg = "";
                else arg = entry.getKey();

                String msg = String.valueOf(lang.getConfigValue(LangKeys.valueOf(entry.getKey()),""));
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
        return "rtp.see";
    }

    @Override
    public String description() {
        return "see this";
    }
}
