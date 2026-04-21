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

  /**
   * Returns the ChatColor prefix for a given log level, or null if the level should be
   * routed through the plain {@link Logger} path instead of the colored console-sender path.
   */
  private static @Nullable ChatColor colorFor(Level level) {
    switch (level.intValue()) {
      case 1000: // Level.SEVERE
        return ChatColor.RED;
      case 900: // Level.WARNING
        return ChatColor.YELLOW;
      case 800: // Level.INFO
        return ChatColor.WHITE;
      case 700: // Level.CONFIG
        return ChatColor.GREEN;
      case 500: // Level.FINE
        return ChatColor.AQUA;
      case 400: // Level.FINER
        return ChatColor.DARK_AQUA;
      case 300: // Level.FINEST
        return ChatColor.GRAY;
      default:
        return null;
    }
  }

  /**
   * Returns true if {@link Bukkit#getLogger()} would publish a record at the given level.
   * Callers that are about to perform expensive formatting should short-circuit on false so
   * FINE/FINER/FINEST debug paths don't pay the placeholder/PAPI cost when disabled.
   */
  private static boolean isLoggable(Level level) {
    Logger logger = Bukkit.getLogger();
    return logger == null || logger.isLoggable(level);
  }

  public static void log(Level level, String message) {
    if (Objects.isNull(message) || message.isEmpty()) return;
    if (!isLoggable(level)) return;

    message = format(null, message);
    intercept(message);

    if (RTP.serverAccessor.getServerIntVersion() <= 12) message = ChatColor.stripColor(message);

    ChatColor color = colorFor(level);
    if (color == null) {
      Bukkit.getLogger().log(level, message);
      return;
    }

    // Map CONFIG → INFO for the legacy (<=1.12) Bukkit logger path; newer servers use the
    // console-sender route which carries its own color and level via the component payload.
    Level legacyLevel = (level.equals(Level.CONFIG)) ? Level.INFO : level;
    boolean modern = RTP.serverAccessor.getServerIntVersion() > 12;

    for (String s : message.split("\n")) {
      String colored = color + s;
      if (modern) {
        Bukkit.getConsoleSender().spigot().sendMessage(TextComponent.fromLegacyText(colored));
      } else {
        Bukkit.getLogger().log(legacyLevel, colored);
      }
    }
  }

  public static void log(Level level, String message, Throwable throwable) {
    if (Objects.isNull(message) || message.isEmpty()) return;
    if (!isLoggable(level)) return;

    String formatted = format(null, message);
    intercept(formatted);

    ChatColor color = colorFor(level);
    if (color == null) {
      Bukkit.getLogger().log(level, message);
    } else {
      CommandSender.Spigot spigot = Bukkit.getConsoleSender().spigot();
      // INFO keeps its historical "no color prefix" rendering in the throwable overload.
      String prefixed = level.equals(Level.INFO) ? formatted : color + formatted;
      spigot.sendMessage(TextComponent.fromLegacyText(prefixed));
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
