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

  MenuModel(
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
    boolean groupBiomes = config.groupBiomesIntoSubmenu();
    boolean hasBiomes = false;

    // Track seen destination keys and display names to deduplicate entries
    // before displaying (e.g. redundant targets pointing to the same region).
    java.util.Set<String> seenKeys = new java.util.HashSet<>();
    java.util.Set<String> seenLabels = new java.util.HashSet<>();

    if (targets != null) {
      for (RtpTarget target : targets) {
        if (target == null) continue;
        // Regions already cover all destination coordinates, shapes, and worlds;
        // world selection targets are redundant.
        if (target.kind() == RtpTarget.Kind.WORLD) {
          continue;
        }
        if (target.kind() == RtpTarget.Kind.BIOME) {
          hasBiomes = true;
          if (groupBiomes) {
            continue; // group into dedicated sub-menu button below
          }
        }
        // Deduplicate targets by their canonical target key
        String targetKey = target.kind() + ":" + (target.name() == null ? "" : target.name().toLowerCase(java.util.Locale.ROOT));
        if (!seenKeys.add(targetKey)) {
          continue;
        }

        RtpTargetStatus status = RTPAPI.getTargetStatus(playerId, target);
        RtpTargetStatus.Availability availability =
            (status == null) ? RtpTargetStatus.Availability.UNKNOWN : status.availability();

        String label = displayName(target, status);
        String normalizedLabel = label.toLowerCase(java.util.Locale.ROOT).trim();
        if (!seenLabels.add(normalizedLabel)) {
          continue; // skip duplicate display label
        }

        entries.add(
            new MenuEntry(
                target,
                availability,
                label,
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

    // Lobby case: a dispatch-only backend has no local RTP world, so its bare
    // local-default / local-region rows resolve to a dead BARRIER ("no
    // regions"). When the menu also carries cross-server NETWORK destinations,
    // those are the only meaningful picks - drop the leftover local barriers
    // even while the network rows are still reported DISABLED (e.g. a peer
    // heartbeat not yet observed as reachable), so the lobby never shows its
    // own "no regions" placeholder next to the network regions.
    if (entries.stream().anyMatch(MenuModel::isNetwork)) {
      entries.removeIf(e -> !isNetwork(e) && !isSelectable(e));
    }

    // If biomes are grouped and available, add the Biome Selector entry to the main menu
    if (groupBiomes && hasBiomes) {
      entries.add(
          new MenuEntry(
              RtpTarget.action("menu:biomes:0"),
              RtpTargetStatus.Availability.READY,
              config.titleBiomeSelector(),
              config.iconBiomeSelector(),
              0L,
              0.0));
    }

    // If actions are enabled and action engine is loaded, check for permitted GUI-eligible actions
    if (config.showActions() && RTPAPI.hasActions()) {
      io.github.dailystruggle.rtp.api.action.ActionService actionService = RTPAPI.actions();
      if (actionService != null) {
        boolean hasActions = false;
        for (String actionId : actionService.getActionIds()) {
          var defOpt = actionService.getAction(actionId);
          if (defOpt.isEmpty()) continue;
          var def = defOpt.get();
          if (!def.isGuiEligible()) continue;
          if (def.permission() == null || def.permission().isBlank() || RTPAPI.checkPermission(playerId, def.permission()) || RTPAPI.checkPermission(playerId, "rtp.action.*")) {
            hasActions = true;
            break;
          }
        }
        if (hasActions) {
          entries.add(
              new MenuEntry(
                  RtpTarget.action("menu:actions:0"),
                  RtpTargetStatus.Availability.READY,
                  config.titleActionsSelector(),
                  config.iconActionsSelector(),
                  0L,
                  0.0));
        }
      }
    }

    // If operator tools are enabled, check if player has operator permission
    if (config.showOperatorTools()) {
      String perm = config.permissionOperatorTools();
      boolean hasPerm = (perm != null && !perm.isBlank() && RTPAPI.checkPermission(playerId, perm))
          || RTPAPI.checkPermission(playerId, "rtp.admin");
      if (hasPerm) {
        entries.add(
            new MenuEntry(
                RtpTarget.action("menu:operator"),
                RtpTargetStatus.Availability.READY,
                config.titleOperatorSelector(),
                config.iconOperatorSelector(),
                0L,
                0.0));
      }
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
   * Builds the paginated biomes sub-menu model for {@code playerId}.
   *
   * @param playerId the viewing player
   * @param config the resolved menu configuration
   * @param page zero-based page index
   * @return an immutable model; never {@code null}
   */
  public static MenuModel buildBiomeMenu(UUID playerId, GuiMenuConfig config, int page) {
    List<MenuEntry> allBiomeEntries = new ArrayList<>();
    List<RtpTarget> targets = RTPAPI.getAllowedTargets(playerId);
    if (targets != null) {
      for (RtpTarget target : targets) {
        if (target.kind() != RtpTarget.Kind.BIOME) continue;
        RtpTargetStatus status = RTPAPI.getTargetStatus(playerId, target);
        RtpTargetStatus.Availability availability =
            (status == null) ? RtpTargetStatus.Availability.UNKNOWN : status.availability();
        allBiomeEntries.add(
            new MenuEntry(
                target,
                availability,
                displayName(target, status),
                config.iconName(target, status),
                (status == null) ? 0L : status.remainingCooldownMillis(),
                (status == null) ? 0.0 : status.cost()));
      }
    }

    if (allBiomeEntries.stream().anyMatch(MenuModel::isSelectable)) {
      allBiomeEntries.removeIf(e -> !isSelectable(e));
    }

    // Usable inner slots: 7 columns x 3 inner rows = 21 items per page
    int pageSize = 21;
    int totalBiomes = allBiomeEntries.size();
    int maxPage = Math.max(0, (int) Math.ceil(totalBiomes / (double) pageSize) - 1);
    int currentPage = Math.max(0, Math.min(page, maxPage));

    int startIndex = currentPage * pageSize;
    int endIndex = Math.min(startIndex + pageSize, totalBiomes);
    List<MenuEntry> pageEntries = new ArrayList<>();
    if (startIndex < totalBiomes) {
      pageEntries.addAll(allBiomeEntries.subList(startIndex, endIndex));
    }

    // Add navigation buttons:
    // Previous Page
    if (currentPage > 0) {
      pageEntries.add(
          new MenuEntry(
              RtpTarget.action("menu:biomes:" + (currentPage - 1)),
              RtpTargetStatus.Availability.READY,
              "&e[Previous Page]",
              config.iconPreviousPage(),
              0L,
              0.0));
    }
    // Back to main menu
    pageEntries.add(
        new MenuEntry(
            RtpTarget.action("menu:main"),
            RtpTargetStatus.Availability.READY,
            "&c[Back to Worlds]",
            config.iconBackToMainMenu(),
            0L,
            0.0));
    // Next Page
    if (currentPage < maxPage) {
      pageEntries.add(
          new MenuEntry(
              RtpTarget.action("menu:biomes:" + (currentPage + 1)),
              RtpTargetStatus.Availability.READY,
              "&e[Next Page]",
              config.iconNextPage(),
              0L,
              0.0));
    }

    MetricsSnapshot metrics = config.showDashboard() ? RTPAPI.getMetricsSnapshot() : null;
    return new MenuModel(
        config.titleBiomeMenu() + " (" + (currentPage + 1) + "/" + (maxPage + 1) + ")",
        6,
        config.fillerName(),
        config.showDashboard(),
        config.dashboardIconName(),
        pageEntries,
        metrics);
  }

  /**
   * Builds the paginated scripted actions sub-menu model for {@code playerId}.
   *
   * @param playerId the viewing player
   * @param config   the resolved menu configuration
   * @param page     zero-based page index
   * @return an immutable model; never {@code null}
   */
  public static MenuModel buildActionsMenu(UUID playerId, GuiMenuConfig config, int page) {
    List<MenuEntry> allActionEntries = new ArrayList<>();
    if (RTPAPI.hasActions()) {
      var actionService = RTPAPI.actions();
      if (actionService != null) {
        List<String> sortedActionIds = new ArrayList<>(actionService.getActionIds());
        sortedActionIds.sort(String.CASE_INSENSITIVE_ORDER);
        for (String actionId : sortedActionIds) {
          var defOpt = actionService.getAction(actionId);
          if (defOpt.isEmpty()) continue;
          var def = defOpt.get();
          if (!def.isGuiEligible()) continue;
          if (def.permission() != null && !def.permission().isBlank()
              && !RTPAPI.checkPermission(playerId, def.permission())
              && !RTPAPI.checkPermission(playerId, "rtp.action.*")) {
            continue;
          }

          String title = (def.title() != null && !def.title().isBlank())
              ? def.title()
              : (def.alias() != null && !def.alias().isBlank() ? def.alias() : def.id());
          String icon = (def.icon() != null && !def.icon().isBlank())
              ? def.icon()
              : config.iconActionDefault();

          allActionEntries.add(
              new MenuEntry(
                  RtpTarget.action("action:trigger:" + def.id()),
                  RtpTargetStatus.Availability.READY,
                  title,
                  icon,
                  0L,
                  0.0));
        }
      }
    }

    int pageSize = 21;
    int totalActions = allActionEntries.size();
    int maxPage = Math.max(0, (int) Math.ceil(totalActions / (double) pageSize) - 1);
    int currentPage = Math.max(0, Math.min(page, maxPage));

    int startIndex = currentPage * pageSize;
    int endIndex = Math.min(startIndex + pageSize, totalActions);
    List<MenuEntry> pageEntries = new ArrayList<>();
    if (startIndex < totalActions) {
      pageEntries.addAll(allActionEntries.subList(startIndex, endIndex));
    }

    // Add navigation buttons:
    if (currentPage > 0) {
      pageEntries.add(
          new MenuEntry(
              RtpTarget.action("menu:actions:" + (currentPage - 1)),
              RtpTargetStatus.Availability.READY,
              "&e[Previous Page]",
              config.iconPreviousPage(),
              0L,
              0.0));
    }
    pageEntries.add(
        new MenuEntry(
            RtpTarget.action("menu:main"),
            RtpTargetStatus.Availability.READY,
            "&c[Back to Worlds]",
            config.iconBackToMainMenu(),
            0L,
            0.0));
    if (currentPage < maxPage) {
      pageEntries.add(
          new MenuEntry(
              RtpTarget.action("menu:actions:" + (currentPage + 1)),
              RtpTargetStatus.Availability.READY,
              "&e[Next Page]",
              config.iconNextPage(),
              0L,
              0.0));
    }

    MetricsSnapshot metrics = config.showDashboard() ? RTPAPI.getMetricsSnapshot() : null;
    return new MenuModel(
        config.titleActionsMenu() + (maxPage > 0 ? " (" + (currentPage + 1) + "/" + (maxPage + 1) + ")" : ""),
        6,
        config.fillerName(),
        config.showDashboard(),
        config.dashboardIconName(),
        pageEntries,
        metrics);
  }

  /**
   * Builds the operator control hub sub-menu model for {@code playerId}.
   *
   * @param playerId the viewing player
   * @param config the resolved menu configuration
   * @return an immutable model; never {@code null}
   */
  public static MenuModel buildOperatorMenu(UUID playerId, GuiMenuConfig config) {
    List<MenuEntry> entries = new ArrayList<>();

    // 1. Setup Wizard (rtp.admin.setup)
    if (RTPAPI.checkPermission(playerId, "rtp.admin.setup") || RTPAPI.checkPermission(playerId, "rtp.admin")) {
      entries.add(
          new MenuEntry(
              RtpTarget.action("action:operator:setup"),
              RtpTargetStatus.Availability.READY,
              "&a&lSetup Wizard",
              config.iconOperatorSetup(),
              0L,
              0.0));
    }

    // 2. Import Foreign Configs (rtp.config)
    if (RTPAPI.checkPermission(playerId, "rtp.config") || RTPAPI.checkPermission(playerId, "rtp.admin")) {
      entries.add(
          new MenuEntry(
              RtpTarget.action("action:operator:import"),
              RtpTargetStatus.Availability.READY,
              "&e&lImport Configs",
              config.iconOperatorImport(),
              0L,
              0.0));
    }

    // 3. Config Editor (rtp.config)
    if (RTPAPI.checkPermission(playerId, "rtp.config") || RTPAPI.checkPermission(playerId, "rtp.admin")) {
      entries.add(
          new MenuEntry(
              RtpTarget.action("action:operator:config"),
              RtpTargetStatus.Availability.READY,
              "&b&lConfig Editor",
              config.iconOperatorConfig(),
              0L,
              0.0));
    }

    // 4. Visualizations (rtp.see)
    if (RTPAPI.checkPermission(playerId, "rtp.see") || RTPAPI.checkPermission(playerId, "rtp.admin")) {
      entries.add(
          new MenuEntry(
              RtpTarget.action("action:operator:visualizations"),
              RtpTargetStatus.Availability.READY,
              "&d&lVisualizations",
              config.iconOperatorVisualizations(),
              0L,
              0.0));
    }

    // 5. Status & Metrics (rtp.info)
    if (RTPAPI.checkPermission(playerId, "rtp.info") || RTPAPI.checkPermission(playerId, "rtp.admin")) {
      entries.add(
          new MenuEntry(
              RtpTarget.action("action:operator:status"),
              RtpTargetStatus.Availability.READY,
              "&f&lStatus & Metrics",
              config.iconOperatorStatus(),
              0L,
              0.0));
    }

    // 6. Master Admin Book Panel (rtp.menu.admin)
    if (RTPAPI.checkPermission(playerId, "rtp.menu.admin") || RTPAPI.checkPermission(playerId, "rtp.admin")) {
      entries.add(
          new MenuEntry(
              RtpTarget.action("action:operator:adminbook"),
              RtpTargetStatus.Availability.READY,
              "&6&lAdmin Book Panel",
              config.iconOperatorAdminBook(),
              0L,
              0.0));
    }

    // 7. Quick Reload (rtp.reload)
    if (RTPAPI.checkPermission(playerId, "rtp.reload") || RTPAPI.checkPermission(playerId, "rtp.admin")) {
      entries.add(
          new MenuEntry(
              RtpTarget.action("action:operator:reload"),
              RtpTargetStatus.Availability.READY,
              "&c&lQuick Reload",
              config.iconOperatorReload(),
              0L,
              0.0));
    }

    // 8. Navigation: Back to main menu
    entries.add(
        new MenuEntry(
            RtpTarget.action("menu:main"),
            RtpTargetStatus.Availability.READY,
            "&c[Back to Worlds]",
            config.iconBackToMainMenu(),
            0L,
            0.0));

    MetricsSnapshot metrics = config.showDashboard() ? RTPAPI.getMetricsSnapshot() : null;
    return new MenuModel(
        config.titleOperatorMenu(),
        6,
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

  /** True when this row is a cross-server (network/peer) destination. */
  private static boolean isNetwork(MenuEntry entry) {
    return entry.target() != null
        && entry.target().kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.NETWORK;
  }

  /** Package-private for testability. */
  static String displayName(RtpTarget target, RtpTargetStatus status) {
    // Prefer the operator-configured cosmetic label (region displayName for a
    // local target, or the peer-advertised label for a cross-server one). May
    // contain RTP color/gradient codes; the renderer applies color formatting.
    if (status != null && status.label() != null && !status.label().isEmpty()) {
      // Ignore an unconfigured "default" fallback label that leaks from older core
      // or uncustomized regions, so default targets format with "Random teleport".
      if (!"default".equalsIgnoreCase(status.label().trim()) || (target != null && target.kind() == RtpTarget.Kind.REGION && "default".equalsIgnoreCase(target.name()))) {
        return status.label();
      }
    }
    if (target == null) return "Random teleport";
    switch (target.kind()) {
      case WORLD:
        return "World: " + target.name();
      case BIOME:
        return "Biome: " + target.name();
      case REGION:
      case NETWORK:
        return "Region: " + target.name();
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
