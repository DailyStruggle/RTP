package leafcraft.rtp.commands;

import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.softdepends.PAPIChecker;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

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
            if(sender instanceof Player) msg = PAPIChecker.fillPlaceholders((Player)sender,msg);
            if(!msg.equals("")) sender.sendMessage(msg);
            return true;
        }

        for(Map.Entry<String,String> entry : perms.entrySet()) {
            if(sender.hasPermission(entry.getValue())){
                TextComponent msg = new TextComponent(configs.lang.getLog(entry.getKey()));
                String arg;
                if(entry.getKey().equals("rtp")) arg = "";
                else arg = entry.getKey();

                msg.setHoverEvent( new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder( "/rtp " + arg ).create()));
                msg.setClickEvent( new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/rtp " + arg));
                if(!msg.getText().equals("")) sender.spigot().sendMessage(msg);
            }
        }

        return true;
    }
}
