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
    List<String> lore = new ArrayList<>();
    RtpTargetStatus.Availability availability = entry.availability();
    lore.add("&7Status: " + statusColor(availability) + availability.name());
    if (availability == RtpTargetStatus.Availability.ON_COOLDOWN
        && entry.remainingCooldownMillis() > 0L) {
      long secs = (entry.remainingCooldownMillis() + 999L) / 1000L;
      lore.add("&7Cooldown: &e" + secs + "s");
    }
    if (entry.cost() > 0.0) {
      lore.add("&7Cost: &6" + entry.cost());
    }
    lore.add("");
    lore.add(entry.ready() ? "&aClick to teleport!" : "&cUnavailable right now.");
    return lore;
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
