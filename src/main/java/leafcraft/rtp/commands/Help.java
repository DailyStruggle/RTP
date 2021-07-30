package leafcraft.rtp.commands;

import leafcraft.rtp.tools.Config;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class Help implements CommandExecutor {
    private Config config;
    private Map<String,String> perms = new HashMap<String,String>();

    public Help(Config config) {
        this.config = config;
        this.perms.put("rtp","rtp.see");
        this.perms.put("help","rtp.see");
        this.perms.put("reload","rtp.reload");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        for(Map.Entry<String,String> entry : this.perms.entrySet()) {
            if(sender.hasPermission(entry.getValue())){
                TextComponent msg = new TextComponent(this.config.getLog(entry.getKey()));
                String arg;
                if(entry.getKey().equals("rtp")) arg = "";
                else arg = entry.getKey();
                msg.setHoverEvent( new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder( "/rtp " + arg ).create()));
                msg.setClickEvent( new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rtp " + arg));
                sender.spigot().sendMessage(msg);
            }
        }

        return true;
    }
}
