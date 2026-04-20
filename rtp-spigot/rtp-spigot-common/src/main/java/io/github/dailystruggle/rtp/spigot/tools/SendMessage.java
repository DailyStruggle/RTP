package io.github.dailystruggle.rtp.spigot.tools;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.spigot.tools.softdepends.PAPIChecker;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.tools.PlaceholderProvider;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public class SendMessage {
  public static final Map<String, Function<UUID, String>> placeholders = PlaceholderProvider.placeholders;
  private static final Pattern hexColorPattern1 = Pattern.compile("(&?#[0-9a-fA-F]{6})");
  private static final Pattern hexColorPattern2 =
      Pattern.compile("(&[0-9a-fA-F]&[0-9a-fA-F]&[0-9a-fA-F]&[0-9a-fA-F]&[0-9a-fA-F]&[0-9a-fA-F])");
  private static final List<Consumer<String>> interceptors = new CopyOnWriteArrayList<>();
  private static ConfigParser<MessagesKeys> lang = null;

  public static void addInterceptor(Consumer<String> interceptor) {
    interceptors.add(interceptor);
  }

  public static void removeInterceptor(Consumer<String> interceptor) {
    interceptors.remove(interceptor);
  }

  private static void intercept(String message) {
    for (Consumer<String> interceptor : interceptors) {
      interceptor.accept(message);
    }
  }

  private static ConfigParser<MessagesKeys> getLang() {
    if (lang == null && RTP.configs != null) {
      lang = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
    }
    return lang;
  }

  public static void sendMessage(CommandSender target1, CommandSender target2, String message) {
    if (message == null || message.isEmpty()) return;
    sendMessage(target1, message);
    if (!target1.getName().equals(target2.getName())) {
      sendMessage(target2, message);
    }
  }

  public static void sendMessage(CommandSender sender, String message) {
    if (message == null || message.isEmpty()) return;
    intercept(message);
    if (sender instanceof Player) sendMessage((Player) sender, message);
    else {
      message = format(Bukkit.getOfflinePlayer(RTPAPI.serverId), message);
      if (RTP.serverAccessor.getServerIntVersion() > 12) {
        BaseComponent[] components = TextComponent.fromLegacyText(message);
        sender.spigot().sendMessage(components);
      } else sender.sendMessage(message);
    }
  }

  public static void sendMessage(Player player, String message) {
    if (message == null || message.isEmpty()) return;
    intercept(message);
    message = format(player, message);
    if (RTP.serverAccessor.getServerIntVersion() > 12) {
      BaseComponent[] components = TextComponent.fromLegacyText(message);
      player.spigot().sendMessage(components);
    } else player.sendMessage(message);
  }

  public static void sendMessage(
      RTPCommandSender sender, String message, String hover, String click) {
    if (message.isEmpty()) return;
    intercept(message);

    OfflinePlayer player;
    if (sender instanceof Player) player = (OfflinePlayer) sender;
    else player = Bukkit.getOfflinePlayer(RTPAPI.serverId).getPlayer();

    message = format(player, message);

    if (RTP.serverAccessor.getServerIntVersion() > 12) {
      BaseComponent[] textComponents = TextComponent.fromLegacyText(message);

      if (!hover.isEmpty()) {
        BaseComponent[] hoverComponents = TextComponent.fromLegacyText(format(player, hover));
        HoverEvent hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hoverComponents));
        for (BaseComponent component : textComponents) {
          component.setHoverEvent(hoverEvent);
        }
      }

      if (!click.isEmpty()) {
        ClickEvent clickEvent = new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, click);
        for (BaseComponent component : textComponents) {
          component.setClickEvent(clickEvent);
        }
      }

      // In a version-independent rtp-plugin, we use the API to send messages.
      // If it's a Bukkit server, we know the player's spigot() method is available if we check the
      // server version.
      if (RTP.serverAccessor.getServerIntVersion() > 12) {
        Player bukkitPlayer = Bukkit.getPlayer(sender.uuid());
        if (bukkitPlayer != null) {
          bukkitPlayer.spigot().sendMessage(textComponents);
        } else if (sender.uuid().equals(RTPAPI.serverId)) {
          Bukkit.getConsoleSender().spigot().sendMessage(textComponents);
        }
      }
    } else sender.sendMessage(message);
  }

  public static String format(@Nullable OfflinePlayer player, @Nullable String text) {
    if (text == null) return "";

    // get uuid to be referenced by placeholder getters
    UUID uuid = (player != null) ? player.getUniqueId() : RTPAPI.serverId;

    text = PlaceholderProvider.fillPlaceholders(text, uuid);

    if (getLang() != null) {
      text = PlaceholderProvider.fillNumericPlaceholders(text);
    }

    // check PAPI exists and scan remaining PAPI placeholders
    // todo: if a null player doesn't work with another PAPI import, blame that import for not
    // verifying its inputs.
    text = PAPIChecker.fillPlaceholders(player, text);

    text = ChatColor.translateAlternateColorCodes('&', text);
    text = Hex2Color(text);
    return text;
  }

  public static String formatDry(@Nullable OfflinePlayer player, @Nullable String text) {
    if (text == null) return "";

    // get uuid to be referenced by placeholder getters
    UUID uuid = (player != null) ? player.getUniqueId() : RTPAPI.serverId;

    text = PlaceholderProvider.fillPlaceholders(text, uuid);

    // check PAPI exists and scan remaining PAPI placeholders
    // todo: if a null player doesn't work with another PAPI import, blame that import for not
    // verifying its inputs.
    text = PAPIChecker.fillPlaceholders(player, text);

    text = ChatColor.translateAlternateColorCodes('&', text);
    text = Hex2Color(text);
    return text;
  }

  public static String formatNoColor(@Nullable OfflinePlayer player, @Nullable String text) {
    if (text == null) return "";

    // get uuid to be referenced by placeholder getters
    UUID uuid = (player != null) ? player.getUniqueId() : RTPAPI.serverId;

    text = PlaceholderProvider.fillPlaceholders(text, uuid);

    if (getLang() != null) {
      text = PlaceholderProvider.fillNumericPlaceholders(text);
    }

    // check PAPI exists and scan remaining PAPI placeholders
    // todo: if a null player doesn't work with another PAPI import, blame that import for not
    // verifying its inputs.
    text = PAPIChecker.fillPlaceholders(player, text);

    return text;
  }

  private static String Hex2Color(String text) {
    // reduce patterns
    if (text == null) return "";
    Matcher matcher2 = hexColorPattern2.matcher(text);
    while (matcher2.find()) {
      String hexColor = text.substring(matcher2.start(), matcher2.end());
      String shortColor = "#" + hexColor.replace("&", "");
      text = text.replaceAll(hexColor, shortColor);
    }

    // colorize
    Matcher matcher1 = hexColorPattern1.matcher(text);
    while (matcher1.find()) {
      String hexColor = text.substring(matcher1.start(), matcher1.end());
      String bukkitColor;
      StringBuilder bukkitColorCode = new StringBuilder("§x");
      for (int i = hexColor.indexOf('#') + 1; i < hexColor.length(); i++) {
        bukkitColorCode.append("§").append(hexColor.charAt(i));
      }
      bukkitColor = bukkitColorCode.toString().toLowerCase();
      text = text.replaceAll(hexColor, bukkitColor);
      matcher1.reset(text);
    }
    return text;
  }

  public static void log(Level level, String message) {
    if (Objects.isNull(message) || message.isEmpty()) return;

    message = format(null, message);
    intercept(message);

    if (RTP.serverAccessor.getServerIntVersion() <= 12) message = ChatColor.stripColor(message);

    if (level.equals(Level.INFO)) {
      String[] split = message.split("\n");
      for (String s : split) {
        s = ChatColor.WHITE + s;
        if (RTP.serverAccessor.getServerIntVersion() > 12) {
          BaseComponent[] baseComponents = TextComponent.fromLegacyText(s);
          Bukkit.getConsoleSender().spigot().sendMessage(baseComponents);
        } else Bukkit.getLogger().log(Level.INFO, s);
      }
    } else if (level.equals(Level.CONFIG)) {
      String[] split = message.split("\n");
      for (String s : split) {
        s = ChatColor.GREEN + s;
        if (RTP.serverAccessor.getServerIntVersion() > 12) {
          BaseComponent[] baseComponents = TextComponent.fromLegacyText(s);
          Bukkit.getConsoleSender().spigot().sendMessage(baseComponents);
        } else Bukkit.getLogger().log(Level.INFO, s);
      }
    } else if (level.equals(Level.WARNING)) {
      String[] split = message.split("\n");
      for (String s : split) {
        s = ChatColor.YELLOW + s;
        if (RTP.serverAccessor.getServerIntVersion() > 12) {
          BaseComponent[] baseComponents = TextComponent.fromLegacyText(s);
          Bukkit.getConsoleSender().spigot().sendMessage(baseComponents);
        } else Bukkit.getLogger().log(Level.WARNING, s);
      }
    } else {
      Logger logger = Bukkit.getLogger();
      logger.log(level, message);
    }
  }

  public static void log(Level level, String message, Throwable throwable) {
    if (Objects.isNull(message) || message.isEmpty()) return;

    String formatted = format(null, message);
    intercept(formatted);

    CommandSender.Spigot spigot = Bukkit.getConsoleSender().spigot();

    if (level.equals(Level.INFO))
      spigot.sendMessage(TextComponent.fromLegacyText(formatted));
    else if (level.equals(Level.CONFIG))
      spigot.sendMessage(TextComponent.fromLegacyText(ChatColor.GREEN + formatted));
    else if (level.equals(Level.WARNING))
      spigot.sendMessage(TextComponent.fromLegacyText(ChatColor.YELLOW + formatted));
    else if (level.equals(Level.SEVERE))
      spigot.sendMessage(TextComponent.fromLegacyText(ChatColor.RED + formatted));
    else {
      Logger logger = Bukkit.getLogger();
      logger.log(level, message);
    }

    if (throwable != null) {
      Bukkit.getLogger().log(level, message, throwable);
    }
  }

  public static void title(
      Player player, String title, String subtitle, int in, int stay, int out) {
    boolean noTitle = title == null || title.isEmpty();
    boolean noSubtitle = subtitle == null || subtitle.isEmpty();

    if (noTitle && noSubtitle) return;

    if (title != null) {
      title = format(player, title);
    }
    if (subtitle != null) {
      subtitle = format(player, subtitle);
    }

    player.sendTitle(title, subtitle, in, stay, out);
  }

  public static void actionbar(Player player, String bar) {
    if (bar == null || bar.isEmpty()) return;
    bar = Hex2Color(ChatColor.translateAlternateColorCodes('&', bar));
    BaseComponent[] components = TextComponent.fromLegacyText(bar);
    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, components);
  }
}
