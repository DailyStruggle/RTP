package leafcraft.rtp.bukkit.tools;

import leafcraft.rtp.common.RTP;
import leafcraft.rtp.bukkit.tools.softdepends.PAPIChecker;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SendMessage {
    public static final UUID serverId = new UUID(0,0);

    private static final Pattern hexColorPattern1 = Pattern.compile("(&?#[0-9a-fA-F]{6})");
    private static final Pattern hexColorPattern2 = Pattern.compile("(&[0-9a-fA-F]&[0-9a-fA-F]&[0-9a-fA-F]&[0-9a-fA-F]&[0-9a-fA-F]&[0-9a-fA-F])");

    public static void sendMessage(CommandSender sender, Player player, String message) {
        if(message.equals("")) return;
        sendMessage(player,message);
        if(!sender.getName().equals(player.getName())) {
            sendMessage(sender, message);
        }
    }

    public static void sendMessage(CommandSender sender, String message) {
        if(message.equals("")) return;
        if(sender instanceof Player) sendMessage((Player) sender,message);
        else {
            message = format(Bukkit.getOfflinePlayer(serverId),message);
            if(RTP.getInstance().serverAccessor.getServerIntVersion() >=12) {
                BaseComponent[] components = TextComponent.fromLegacyText(message);
                sender.spigot().sendMessage(components);
            }
            else sender.sendMessage(message);
        }
    }

    public static void sendMessage(Player player, String message) {
        if(message.equals("")) return;
        message = format(player,message);
        if(RTP.getInstance().serverAccessor.getServerIntVersion() >=12) {
            BaseComponent[] components = TextComponent.fromLegacyText(message);
            player.spigot().sendMessage(components);
        }
        else player.sendMessage(message);
    }

    public static void sendMessage(CommandSender sender, String message, String hover, String click) {
        if(message.equals("")) return;

        OfflinePlayer player;
        if(sender instanceof Player) player = (Player) sender;
        else player = Bukkit.getOfflinePlayer(serverId).getPlayer();

        message = format(player,message);

        if(RTP.getInstance().serverAccessor.getServerIntVersion() >=12) {
            BaseComponent[] textComponents = TextComponent.fromLegacyText(message);

            if (!hover.equals("")) {
                BaseComponent[] hoverComponents = TextComponent.fromLegacyText(format(player, hover));
                //noinspection deprecation
                HoverEvent hoverEvent = (RTP.getInstance().serverAccessor.getServerIntVersion()>=16)
                        ? new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hoverComponents))
                        : new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverComponents);
                for (BaseComponent component : textComponents) {
                    component.setHoverEvent(hoverEvent);
                }
            }

            if (!click.equals("")) {
                ClickEvent clickEvent = new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, click);
                for (BaseComponent component : textComponents) {
                    component.setClickEvent(clickEvent);
                }
            }

            sender.spigot().sendMessage(textComponents);
        }
        else sender.sendMessage(message);
    }

    public static String format(OfflinePlayer player, String text) {
        text = PAPIChecker.fillPlaceholders(player,text);
        text = ChatColor.translateAlternateColorCodes('&',text);
        text = Hex2Color(text);
        return text;
    }

    private static String Hex2Color(String text) {
        //reduce patterns
        Matcher matcher2 = hexColorPattern2.matcher(text);
        while (matcher2.find()) {
            String hexColor = text.substring(matcher2.start(), matcher2.end());
            String shortColor = "#" + hexColor.replaceAll("&","");
            text = text.replaceAll(hexColor, shortColor);
        }

        //colorize
        Matcher matcher1 = hexColorPattern1.matcher(text);
        while (matcher1.find()) {
            String hexColor = text.substring(matcher1.start(), matcher1.end());
            String bukkitColor;
            if(RTP.getInstance().serverAccessor.getServerIntVersion() < 16) {
                StringBuilder bukkitColorCode = new StringBuilder('\u00A7' + "x");
                for (int i = hexColor.indexOf('#')+1; i < hexColor.length(); i++) {
                    bukkitColorCode.append('\u00A7').append(hexColor.charAt(i));
                }
                bukkitColor = bukkitColorCode.toString().toLowerCase();
            }
            else {
                bukkitColor = ChatColor.of(hexColor.substring(hexColor.indexOf('#'))).toString();
            }
            text = text.replaceAll(hexColor, bukkitColor);
            matcher1.reset(text);
        }
        return text;
    }

    public static void log(Level level, String message) {
        if(message.isBlank()) return;

        message = format(Bukkit.getOfflinePlayer(serverId),message);

        Bukkit.getLogger().log(level,message);
    }
}
