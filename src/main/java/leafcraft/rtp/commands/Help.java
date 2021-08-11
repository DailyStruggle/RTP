package leafcraft.rtp.commands;

import leafcraft.rtp.tools.Configuration.Configs;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.HashMap;
import java.util.Map;

public class Help implements CommandExecutor {
    private Configs configs;
    private Map<String,String> perms = new HashMap<String,String>();

    public Help(Configs configs) {
        this.configs = configs;
        this.perms.put("rtp","rtp.see");
        this.perms.put("help","rtp.see");
        this.perms.put("reload","rtp.reload");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(!sender.hasPermission("rtp.see")) {
            sender.sendMessage(configs.lang.getLog("noPerms"));
            return true;
        }

        for(Map.Entry<String,String> entry : perms.entrySet()) {
            if(sender.hasPermission(entry.getValue())){
                TextComponent msg = new TextComponent(configs.lang.getLog(entry.getKey()));
                String arg;
                if(entry.getKey().equals("rtp")) arg = "";
                else arg = entry.getKey();

                msg.setHoverEvent( new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text( "/rtp " + arg )));
                msg.setClickEvent( new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rtp " + arg));
                sender.spigot().sendMessage(msg);
            }
        }

        return true;
    }
}
