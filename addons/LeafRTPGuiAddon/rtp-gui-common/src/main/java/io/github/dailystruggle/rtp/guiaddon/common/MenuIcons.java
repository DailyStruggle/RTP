package io.github.dailystruggle.rtp.guiaddon.common;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.api.RtpTargetStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Platform-neutral text content for menu icons: display labels and lore lines.
 *
 * <p>Centralises the <em>what the icon says</em> half of menu rendering, the
 * counterpart to {@link MenuLayout}'s <em>where the icon goes</em>. Both the Bukkit
 * chest renderer and the Fabric / NeoForge container screens consume these helpers
 * so a destination tile reads identically on every platform; previously only the
 * Bukkit renderer built lore (status / cooldown / cost / call-to-action) while the
 * Fabric / NeoForge screens showed a bare name.
 *
 * <p>Strings may carry RTP/legacy {@code &x} colour codes; each renderer applies its
 * own colour translation (Bukkit {@code ChatColor}, Fabric/NeoForge {@code Component}).
 */
public final class MenuIcons {

  private MenuIcons() {}

  /**
   * Lore lines for a destination entry: availability status, optional cooldown and
   * cost, and a ready / unavailable call-to-action. Lines may contain {@code &} codes.
   *
   * @param entry the destination row
   * @return an ordered, mutable list of lore lines; never {@code null}
   */
  public static List<String> entryLore(MenuEntry entry) {
    return entryLore(entry, GuiMenuConfig.INSTANCE);
  }

  /**
   * Lore lines for a destination entry with configurable call-to-action strings.
   *
   * @param entry the destination row
   * @param readyText call to action text when ready
   * @param unavailableText call to action text when unavailable
   * @return an ordered, mutable list of lore lines; never {@code null}
   */
  public static List<String> entryLore(MenuEntry entry, String readyText, String unavailableText) {
    return entryLore(entry, GuiMenuConfig.INSTANCE);
  }

  /**
   * Lore lines for a destination entry evaluated against configurable lore templates.
   *
   * @param entry the destination row
   * @param config the menu configuration view
   * @return an ordered, mutable list of lore lines; never {@code null}
   */
  public static List<String> entryLore(MenuEntry entry, GuiMenuConfig config) {
    List<String> lore = new ArrayList<>();
    if (entry.target() != null && entry.target().kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.ACTION) {
      String name = entry.target().name();
      if (name != null) {
        switch (name) {
          case "action:operator:setup":
            lore.add("&7Interactive 5-stage setup wizard");
            lore.add("&7Configure worlds, gameplay & profiles");
            lore.add("");
            lore.add("&aClick to launch wizard");
            return lore;
          case "action:operator:import":
            lore.add("&7Import configs from foreign plugins");
            lore.add("&7Auto-detects BetterRTP and others");
            lore.add("");
            lore.add("&aClick to scan & import");
            return lore;
          case "action:operator:config":
            lore.add("&7Edit regions, boundaries & costs");
            lore.add("");
            lore.add("&aClick to open editor");
            return lore;
          case "action:operator:visualizations":
            lore.add("&7Map visualizations of search space");
            lore.add("&7Heatmaps, biomes & bad-location voids");
            lore.add("");
            lore.add("&aClick to open visualizations");
            return lore;
          case "action:operator:status":
            lore.add("&7Real-time queues, memory & metrics");
            lore.add("");
            lore.add("&aClick to view status");
            return lore;
          case "action:operator:adminbook":
            lore.add("&7Master administrative book panel");
            lore.add("&7Full command tree & scan crawlers");
            lore.add("");
            lore.add("&aClick to open panel");
            return lore;
          case "action:operator:reload":
            lore.add("&7Reload configuration from disk");
            lore.add("");
            lore.add("&aClick to reload");
            return lore;
          case "menu:operator":
            lore.add("&7Operator management tools & setup");
            lore.add("");
            lore.add("&aClick to open hub");
            return lore;
          case "menu:main":
            lore.add("&7Return to destination menu");
            lore.add("");
            lore.add("&aClick to return");
            return lore;
          default:
            if (name.startsWith("menu:biomes:")) {
              lore.add("&7Browse available destination biomes");
              lore.add("");
              lore.add("&aClick to view biomes");
              return lore;
            }
            if (name.startsWith("menu:actions:")) {
              lore.add("&7Browse special teleport actions");
              lore.add("");
              lore.add("&aClick to view actions");
              return lore;
            }
            break;
        }
      }
    }

    if (config == null) config = GuiMenuConfig.INSTANCE;
    RtpTargetStatus.Availability availability = entry.availability();
    List<String> template;
    switch (availability) {
      case READY:
        template = config.loreReady();
        break;
      case ON_COOLDOWN:
        template = config.loreCooldown();
        break;
      case IN_COMBAT:
        template = config.loreCombat();
        break;
      case NO_FUNDS:
        template = config.loreNoFunds();
        break;
      case NO_PERMISSION:
      case DISABLED:
      default:
        template = config.loreNoPermission();
        break;
    }

    if (template != null) {
      for (String line : template) {
        lore.add(expandPlaceholders(line, entry));
      }
    }
    return lore;
  }

  /**
   * Expands lore placeholders with concrete values from {@code entry}.
   * Supports {status}, {cooldown}, {delay}, {cost}, {target}, {world}, {region}.
   */
  public static String expandPlaceholders(String line, MenuEntry entry) {
    if (line == null || !line.contains("{")) return line;
    RtpTargetStatus.Availability avail = entry.availability();
    long cdMillis = (avail == RtpTargetStatus.Availability.IN_COMBAT)
        ? entry.combatRemainingMillis()
        : entry.remainingCooldownMillis();

    String costStr;
    if (entry.cost() <= 0.0) {
      costStr = "Free";
    } else if (entry.cost() == Math.floor(entry.cost())) {
      costStr = String.format(java.util.Locale.ROOT, "%.0f", entry.cost());
    } else {
      costStr = String.valueOf(entry.cost());
    }

    String worldName = "";
    String regionName = "";
    if (entry.target() != null) {
      if (entry.target().kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.WORLD) {
        worldName = entry.target().name() != null ? entry.target().name() : "";
      } else if (entry.target().kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.REGION) {
        regionName = entry.target().name() != null ? entry.target().name() : "";
      }
    }

    return line.replace("{status}", statusColor(avail) + avail.name())
        .replace("{cooldown}", formatDuration(cdMillis))
        .replace("{delay}", formatDuration(entry.delayMillis()))
        .replace("{cost}", costStr)
        .replace("{target}", entry.displayName() != null ? entry.displayName() : "")
        .replace("{world}", worldName)
        .replace("{region}", regionName);
  }

  /**
   * Formats millisecond duration into concise human-readable units (e.g. 45s, 1m 30s, 2h).
   */
  public static String formatDuration(long millis) {
    if (millis <= 0L) return "0s";
    long totalSeconds = (millis + 999L) / 1000L;
    if (totalSeconds < 60L) {
      return totalSeconds + "s";
    }
    long minutes = totalSeconds / 60L;
    long seconds = totalSeconds % 60L;
    if (minutes < 60L) {
      return (seconds > 0) ? (minutes + "m " + seconds + "s") : (minutes + "m");
    }
    long hours = minutes / 60L;
    long remainingMinutes = minutes % 60L;
    if (remainingMinutes > 0) {
      return hours + "h " + remainingMinutes + "m";
    }
    return hours + "h";
  }

  /** Display label for the dashboard tile. */
  public static String dashboardTitle() {
    return "&6Server health";
  }

  /**
   * Lore lines for the server-health dashboard tile. Lines may contain {@code &} codes.
   *
   * @param model the menu model (carries the metrics snapshot)
   * @return an ordered, mutable list of lore lines; never {@code null}
   */
  public static List<String> dashboardLore(MenuModel model) {
    List<String> lore = new ArrayList<>();
    MetricsSnapshot snapshot = model.metrics();
    if (snapshot == null) {
      lore.add("&7Metrics unavailable.");
    } else {
      lore.add("&7TPS (1m): &b" + format(snapshot.tps1m));
      lore.add("&7MSPT: &b" + format(snapshot.mspt) + " ms");
      lore.add("&7Players: &b" + snapshot.playerCount);
    }
    return lore;
  }

  /** A legacy colour code (no leading {@code &}) for an availability state. */
  private static String statusColor(RtpTargetStatus.Availability availability) {
    switch (availability) {
      case READY:
        return "&a";
      case ON_COOLDOWN:
      case NO_FUNDS:
        return "&e";
      case NO_PERMISSION:
      case DISABLED:
        return "&c";
      default:
        return "&7";
    }
  }

  private static String format(double value) {
    return Double.isNaN(value) ? "n/a" : String.format("%.1f", value);
  }
}
