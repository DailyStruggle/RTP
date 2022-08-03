package io.github.dailystruggle.rtp.bukkit.tools;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkit.tools.softdepends.PAPIChecker;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.apache.commons.lang.text.StrSubstitutor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SendMessage {
    private static final Pattern hexColorPattern1 = Pattern.compile("(&?#[0-9a-fA-F]{6})");
    private static final Pattern hexColorPattern2 = Pattern.compile("(&[0-9a-fA-F]&[0-9a-fA-F]&[0-9a-fA-F]&[0-9a-fA-F]&[0-9a-fA-F]&[0-9a-fA-F])");

    private static final Map<String, Function<UUID,String>> placeholders = new ConcurrentHashMap<>();
    private static RTP rtp = null;
    private static ConfigParser<LangKeys> lang = null;
    static {
        Bukkit.getScheduler().runTaskLater(RTPBukkitPlugin.getInstance(),() -> {
            rtp = RTP.getInstance();
            lang = (ConfigParser<LangKeys>) rtp.configs.getParser(LangKeys.class);
        },2);

        placeholders.put("plugin",uuid -> {
            if(lang == null) return "[RTP]";
            return String.valueOf(lang.getConfigValue(LangKeys.plugin,""));
        });
        placeholders.put("delay",uuid -> {
            if(rtp == null) return "0";
            if(rtp.serverAccessor==null) return "0";
            RTPCommandSender commandSender = rtp.serverAccessor.getSender(uuid);
            if(commandSender instanceof BukkitRTPPlayer rtpPlayer) {
                Set<PermissionAttachmentInfo> perms = rtpPlayer.player().getEffectivePermissions();
                Number time = rtp.configs.getParser(ConfigKeys.class).getNumber(ConfigKeys.teleportDelay,0);
                for(PermissionAttachmentInfo perm : perms) {
                    if(!perm.getValue()) continue;
                    String node = perm.getPermission();
                    if(node.startsWith("rtp.delay.")) {
                        String[] val = node.split("\\.");
                        if(val.length<3 || val[2]==null || val[2].equals("")) continue;
                        int number;
                        try {
                            number = Integer.parseInt(val[2]);
                        } catch (NumberFormatException exception) {
                            Bukkit.getLogger().warning("[rtp] invalid permission: " + node);
                            continue;
                        }
                        time = number;
                        break;
                    }
                }
                return time.toString();
            }
            return "0";
        });
        placeholders.put("cooldown",uuid -> {
            if(rtp == null) return "0";
            if(rtp.serverAccessor==null) return "0";
            RTPCommandSender commandSender = rtp.serverAccessor.getSender(uuid);
            if(commandSender instanceof BukkitRTPPlayer rtpPlayer) {
                Set<PermissionAttachmentInfo> perms = rtpPlayer.player().getEffectivePermissions();
                Number time = rtp.configs.getParser(ConfigKeys.class).getNumber(ConfigKeys.teleportCooldown,0);
                for(PermissionAttachmentInfo perm : perms) {
                    if(!perm.getValue()) continue;
                    String node = perm.getPermission();
                    if(node.startsWith("rtp.cooldown.")) {
                        String[] val = node.split("\\.");
                        if(val.length<3 || val[2]==null || val[2].equals("")) continue;
                        int number;
                        try {
                            number = Integer.parseInt(val[2]);
                        } catch (NumberFormatException exception) {
                            Bukkit.getLogger().warning("[rtp] invalid permission: " + node);
                            continue;
                        }
                        time = number;
                        break;
                    }
                }
                return time.toString();
            }
            return "0";
        });
        placeholders.put("remainingCooldown",uuid -> {
            if(rtp == null) return "0";
            if(rtp.serverAccessor==null) return "0";

            long start = System.nanoTime();

            RTPCommandSender commandSender = rtp.serverAccessor.getSender(uuid);
            if(commandSender instanceof BukkitRTPPlayer rtpPlayer) {
                Set<PermissionAttachmentInfo> perms = rtpPlayer.player().getEffectivePermissions();
                TeleportData teleportData = rtp.latestTeleportData.get(uuid);
                if(teleportData==null) return "0s";
                long lastTime = teleportData.time;
                Number cooldownTime = rtp.configs.getParser(ConfigKeys.class).getNumber(ConfigKeys.teleportCooldown,0);
                for(PermissionAttachmentInfo perm : perms) {
                    if(!perm.getValue()) continue;
                    String node = perm.getPermission();
                    if(node.startsWith("rtp.cooldown.")) {
                        String[] val = node.split("\\.");
                        if(val.length<3 || val[2]==null || val[2].equals("")) continue;
                        int number;
                        try {
                            number = Integer.parseInt(val[2]);
                        } catch (NumberFormatException exception) {
                            Bukkit.getLogger().warning("[rtp] invalid permission: " + node);
                            continue;
                        }
                        cooldownTime = number;
                        break;
                    }
                }

                long remaining = (lastTime - start) + cooldownTime.longValue();
                long days = TimeUnit.NANOSECONDS.toDays(remaining);
                long hours = TimeUnit.NANOSECONDS.toHours(remaining) % 24;
                long minutes = TimeUnit.NANOSECONDS.toMinutes(remaining) % 60;
                long seconds = TimeUnit.NANOSECONDS.toSeconds(remaining) % 60;
                String replacement = "";
                if (days > 0) replacement += days + (String) lang.getConfigValue(LangKeys.days, 0) + " ";
                if (days > 0 || hours > 0) replacement += hours + (String) lang.getConfigValue(LangKeys.hours, 0) + " ";
                if (days > 0 || hours > 0 || minutes > 0) replacement += minutes + (String) lang.getConfigValue(LangKeys.minutes, 0) + " ";
                replacement += seconds + (String) lang.getConfigValue(LangKeys.seconds, 0);

                return replacement;
            }
            return "0";
        });
        placeholders.put("queueLocation",uuid -> {
            if(rtp == null) return "0";
            TeleportData teleportData = rtp.latestTeleportData.get(uuid);
            if(teleportData == null) return "0";
            return String.valueOf(teleportData.queueLocation);
        });
        placeholders.put("chunks",uuid -> {
            if(rtp == null) return "0";
            return String.valueOf(rtp.forceLoads.size());
        });

    }

    public static void sendMessage(CommandSender target1, CommandSender target2, String message) {
        if(message.equals("")) return;
        sendMessage(target1,message);
        if(!target1.getName().equals(target2.getName())) {
            sendMessage(target2, message);
        }
    }

    public static void sendMessage(CommandSender sender, String message) {
        if(message.equals("")) return;
        if(sender instanceof Player) sendMessage((Player) sender,message);
        else {
            message = format(Bukkit.getOfflinePlayer(CommandsAPI.serverId),message);
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
        if(sender instanceof Player p) player = p;
        else player = Bukkit.getOfflinePlayer(CommandsAPI.serverId).getPlayer();

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

    public static String format(@Nullable OfflinePlayer player, @Nullable String text) {
        if(text == null) return "";

        //get uuid to be referenced by placeholder getters
        UUID uuid = (player!=null) ? player.getUniqueId() : CommandsAPI.serverId;

        //create a container for placeholder getter results
        // initialize with the same size as the placeholder getter map to skip reallocation
        Map<String,String> placeholders = new HashMap<>(SendMessage.placeholders.size());

        //for each placeholder getter, add placeholder and result to container
        SendMessage.placeholders.forEach((s, uuidStringFunction) -> placeholders.put(s,uuidStringFunction.apply(uuid)));

        //set up substitutor with the new placeholder results array
        // using [x] format to detect my local placeholders.
        //todo: verify arbitrary [x] won't be replaced
        StrSubstitutor sub = new StrSubstitutor(placeholders,"[","]");

        //replace all placeholders with their respective string function results
        text = sub.replace(text);

        //check PAPI exists and fill remaining PAPI placeholders
        //todo: if a null player doesn't work with another PAPI import, blame that import for not verifying its inputs.
        text = PAPIChecker.fillPlaceholders(player,text);
        text = ChatColor.translateAlternateColorCodes('&',text);
        text = Hex2Color(text);
        return text;
    }

    private static String Hex2Color(String text) {
        //reduce patterns
        if(text == null) return "";
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

        message = format(null,message);

        Bukkit.getLogger().log(level,message);
    }

    public static void log(Level level, String message, Exception exception) {
        if(message.isBlank()) return;

        message = format(null,message);

        Bukkit.getLogger().log(level,message,exception);
    }
}
