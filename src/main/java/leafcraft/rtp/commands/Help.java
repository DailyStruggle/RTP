package leafcraft.rtp.commands;

import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.SendMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.HashMap;
import java.util.Map;

public class Help implements CommandExecutor {
    private final Configs configs;
    private final Map<String,String> perms = new HashMap<String,String>();

    public Help(Configs configs) {
        this.configs = configs;
        this.perms.put("rtp","rtp.see");
        this.perms.put("help","rtp.see");
        this.perms.put("reload","rtp.reload");
        this.perms.put("setWorld","rtp.setWorld");
        this.perms.put("setRegion","rtp.setRegion");
        this.perms.put("fill","rtp.fill");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(!sender.hasPermission("rtp.see")) {
            String msg = configs.lang.getLog("noPerms");
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        for(Map.Entry<String,String> entry : perms.entrySet()) {
            if(sender.hasPermission(entry.getValue())){
                String arg;
                if(entry.getKey().equals("rtp")) arg = "";
                else arg = entry.getKey();

                String msg = configs.lang.getLog(entry.getKey());
                String hover = "/rtp " + arg;
                String click = "/rtp " + arg;

                SendMessage.sendMessage(sender,msg,hover,click);
            }
        }

        return true;
    }
}
