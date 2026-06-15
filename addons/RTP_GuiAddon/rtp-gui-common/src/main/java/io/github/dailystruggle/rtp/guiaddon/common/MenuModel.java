package io.github.dailystruggle.rtp.guiaddon.common;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.api.RtpTargetStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Platform-neutral, point-in-time description of the destination menu for one player.
 *
 * <p>Built entirely from the stable {@code rtp-api} surface ({@link
 * RTPAPI#getAllowedTargets}, {@link RTPAPI#getTargetStatus}, {@link
 * RTPAPI#getMetricsSnapshot}) plus the resolved {@link GuiMenuConfig}. Contains no
 * platform types, so every renderer (Bukkit chest, Fabric/NeoForge screen, ...)
 * consumes the same model.
 */
public final class MenuModel {

  private final String title;
  private final int rows;
  private final String fillerName;
  private final boolean showDashboard;
  private final String dashboardIconName;
  private final List<MenuEntry> entries;
  private final MetricsSnapshot metrics;

  private MenuModel(
      String title,
      int rows,
      String fillerName,
      boolean showDashboard,
      String dashboardIconName,
      List<MenuEntry> entries,
      MetricsSnapshot metrics) {
    this.title = title;
    this.rows = rows;
    this.fillerName = fillerName;
    this.showDashboard = showDashboard;
    this.dashboardIconName = dashboardIconName;
    this.entries = Collections.unmodifiableList(entries);
    this.metrics = metrics;
  }

  /**
   * Builds the model for {@code playerId} using the supplied config. Reads target
   * status point-in-time; the renderer should open promptly after building.
   *
   * @param playerId the viewing player
   * @param config the resolved menu configuration
   * @return an immutable model; never {@code null}
   */
  public static MenuModel build(UUID playerId, GuiMenuConfig config) {
    List<MenuEntry> entries = new ArrayList<>();
    List<RtpTarget> targets = RTPAPI.getAllowedTargets(playerId);
    if (targets != null) {
      for (RtpTarget target : targets) {
        RtpTargetStatus status = RTPAPI.getTargetStatus(playerId, target);
        RtpTargetStatus.Availability availability =
            (status == null) ? RtpTargetStatus.Availability.UNKNOWN : status.availability();
        entries.add(
            new MenuEntry(
                target,
                availability,
                displayName(target, status),
                config.iconName(target, status),
                (status == null) ? 0L : status.remainingCooldownMillis(),
                (status == null) ? 0.0 : status.cost()));
      }
    }

    // Hide "dead" placeholder rows (the BARRIER icon - a target the player can
    // neither select nor will ever be able to, i.e. DISABLED / NO_PERMISSION)
    // whenever at least one genuinely selectable destination exists. On a lobby
    // backend with no local RTP world but live cross-server regions, this drops
    // the leftover local-default barrier while keeping the network regions.
    if (entries.stream().anyMatch(MenuModel::isSelectable)) {
      entries.removeIf(e -> !isSelectable(e));
    }

    MetricsSnapshot metrics = config.showDashboard() ? RTPAPI.getMetricsSnapshot() : null;
    return new MenuModel(
        config.title(),
        config.rows(),
        config.fillerName(),
        config.showDashboard(),
        config.dashboardIconName(),
        entries,
        metrics);
  }

  /**
   * A destination is "selectable" unless it is permanently dead for this player
   * (no permission or disabled). Cooldown / no-funds rows stay visible because
   * they become usable shortly; only the BARRIER placeholders are pruned.
   */
  private static boolean isSelectable(MenuEntry entry) {
    RtpTargetStatus.Availability a = entry.availability();
    return a != RtpTargetStatus.Availability.NO_PERMISSION
        && a != RtpTargetStatus.Availability.DISABLED;
  }

  private static String displayName(RtpTarget target, RtpTargetStatus status) {
    // Prefer the operator-configured cosmetic label (region displayName for a
    // local target, or the peer-advertised label for a cross-server one). May
    // contain RTP color/gradient codes; the renderer applies color formatting.
    if (status != null && status.label() != null && !status.label().isEmpty()) {
      return status.label();
    }
    if (target == null) return "Random teleport";
    switch (target.kind()) {
      case WORLD:
        return "World: " + target.name();
      case REGION:
        return "Region: " + target.name();
      case NETWORK:
        return target.serverId() + " - " + target.name();
      case DEFAULT:
      default:
        return "Random teleport";
    }
  }

  public String title() {
    return title;
  }

  public int rows() {
    return rows;
  }

  public String fillerName() {
    return fillerName;
  }

  public boolean showDashboard() {
    return showDashboard;
  }

  public String dashboardIconName() {
    return dashboardIconName;
  }

  /** Immutable, ordered list of destination rows. */
  public List<MenuEntry> entries() {
    return entries;
  }

  /** Server-health snapshot for the dashboard tile, or {@code null} if disabled/unavailable. */
  public MetricsSnapshot metrics() {
    return metrics;
  }
}
