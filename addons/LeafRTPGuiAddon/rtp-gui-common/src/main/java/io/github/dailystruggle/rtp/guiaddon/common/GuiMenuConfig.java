package io.github.dailystruggle.rtp.guiaddon.common;

import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.api.RtpTargetStatus;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;

/**
 * Typed, null-safe view over the {@code guimenu.yml} {@link ConfigParser}.
 *
 * <p>Reads are always live: each getter resolves from the currently-registered
 * parser, so a {@code /rtp reload} is reflected immediately without rebuilding this
 * object. Every getter falls back to the built-in "DonutSMP-style" default when the
 * parser is absent (core not ready) or a key is missing/malformed, so a deleted key
 * or a typo can never break the menu.
 *
 * <p>Material values are returned as platform-neutral <em>names</em>; the renderer
 * maps them to its own item type.
 */
public final class GuiMenuConfig {

  /** Shared instance; all reads are live against the registered parser. */
  public static final GuiMenuConfig INSTANCE = new GuiMenuConfig();

  GuiMenuConfig() {}

  @SuppressWarnings("unchecked")
  private ConfigParser<GuiMenuKeys> parser() {
    if (RTP.configs == null) return null;
    return (ConfigParser<GuiMenuKeys>) RTP.configs.getParser(GuiMenuKeys.class);
  }

  private String str(GuiMenuKeys key, String fallback) {
    ConfigParser<GuiMenuKeys> p = parser();
    if (p == null) return fallback;
    Object v = p.getConfigValue(key, fallback);
    if (v == null) return fallback;
    String s = String.valueOf(v);
    return s.isEmpty() ? fallback : s;
  }

  private int integer(GuiMenuKeys key, int fallback) {
    ConfigParser<GuiMenuKeys> p = parser();
    Object v = (p == null) ? null : p.getConfigValue(key, fallback);
    if (v instanceof Number) return ((Number) v).intValue();
    try {
      return (v == null) ? fallback : Integer.parseInt(String.valueOf(v).trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private boolean bool(GuiMenuKeys key, boolean fallback) {
    ConfigParser<GuiMenuKeys> p = parser();
    Object v = (p == null) ? null : p.getConfigValue(key, fallback);
    if (v instanceof Boolean) return (Boolean) v;
    return (v == null) ? fallback : Boolean.parseBoolean(String.valueOf(v).trim());
  }

  /** Configured renderer style for a bare {@code /rtp} (e.g. {@code chest}, {@code book}). */
  public String menuStyle() {
    return str(GuiMenuKeys.menuStyle, "chest");
  }

  public String title() {
    return str(GuiMenuKeys.menuTitle, "&1&lRandom Teleport");
  }

  /**
   * Resolves the operator-configured per-region icon override for {@code target}, if any.
   *
   * <p>The {@code regionIcons} config section maps a region key to a material name. The
   * key is the {@code server:region} qualified form for a cross-server (network) target,
   * or the unqualified region/world name for a local target. This is how an operator
   * pins a specific block to a region (e.g. a custom block per cross-server destination).
   *
   * @param target the target; may be {@code null}
   * @return the configured material name, or {@code null} when no override is set
   */
  public String regionIconOverride(RtpTarget target) {
    if (target == null) return null;
    ConfigParser<GuiMenuKeys> p = parser();
    if (p == null) return null;
    java.util.Map<String, Object> overrides = p.getMap(GuiMenuKeys.regionIcons);
    if (overrides == null || overrides.isEmpty()) return null;
    String key;
    switch (target.kind()) {
      case NETWORK:
        key = target.serverId() + ":" + target.name();
        break;
      case REGION:
      case WORLD:
        key = target.name();
        break;
      case DEFAULT:
      default:
        return null;
    }
    if (key == null) return null;
    Object v = overrides.get(key);
    if (v == null) return null;
    String s = String.valueOf(v).trim();
    return s.isEmpty() ? null : s;
  }

  /** Chest rows clamped to the legal 1-6 range. */
  public int rows() {
    return Math.max(1, Math.min(6, integer(GuiMenuKeys.menuRows, 6)));
  }

  public String fillerName() {
    return str(GuiMenuKeys.menuFiller, "GRAY_STAINED_GLASS_PANE");
  }

  public boolean showDashboard() {
    return bool(GuiMenuKeys.showDashboard, true);
  }

  public boolean showActions() {
    return bool(GuiMenuKeys.showActions, true);
  }

  public String titleActionsMenu() {
    return str(GuiMenuKeys.titleActionsMenu, "&6&lSpecial Teleports");
  }

  public String titleActionsSelector() {
    return str(GuiMenuKeys.titleActionsSelector, "&6&lSpecial Teleports...");
  }

  public String iconActionsSelector() {
    return str(GuiMenuKeys.iconActionsSelector, "NETHERITE_SWORD");
  }

  public String iconActionDefault() {
    return str(GuiMenuKeys.iconActionDefault, "DIAMOND_SWORD");
  }

  public boolean showOperatorTools() {
    return bool(GuiMenuKeys.showOperatorTools, true);
  }

  public String permissionOperatorTools() {
    return str(GuiMenuKeys.permissionOperatorTools, "rtp.menu.admin");
  }

  public String titleOperatorMenu() {
    return str(GuiMenuKeys.titleOperatorMenu, "&6&lOperator Control Hub");
  }

  public String titleOperatorSelector() {
    return str(GuiMenuKeys.titleOperatorSelector, "&6&lOperator Tools...");
  }

  public String iconOperatorSelector() {
    return str(GuiMenuKeys.iconOperatorSelector, "COMMAND_BLOCK");
  }

  public String iconOperatorSetup() {
    return str(GuiMenuKeys.iconOperatorSetup, "NETHER_STAR");
  }

  public String iconOperatorImport() {
    return str(GuiMenuKeys.iconOperatorImport, "HOPPER");
  }

  public String iconOperatorConfig() {
    return str(GuiMenuKeys.iconOperatorConfig, "REPEATER");
  }

  public String iconOperatorVisualizations() {
    return str(GuiMenuKeys.iconOperatorVisualizations, "FILLED_MAP");
  }

  public String iconOperatorStatus() {
    return str(GuiMenuKeys.iconOperatorStatus, "CLOCK");
  }

  public String iconOperatorAdminBook() {
    return str(GuiMenuKeys.iconOperatorAdminBook, "WRITABLE_BOOK");
  }

  public String iconOperatorReload() {
    return str(GuiMenuKeys.iconOperatorReload, "REDSTONE_TORCH");
  }

  public boolean groupBiomesIntoSubmenu() {
    return bool(GuiMenuKeys.groupBiomesIntoSubmenu, true);
  }

  public String titleBiomeMenu() {
    return str(GuiMenuKeys.titleBiomeMenu, "&1&lSelect Biome");
  }

  public String titleBiomeSelector() {
    return str(GuiMenuKeys.titleBiomeSelector, "&a&lSelect Biome...");
  }

  public String iconBiomeSelector() {
    return str(GuiMenuKeys.iconBiomeSelector, "OAK_SAPLING");
  }

  public String iconPreviousPage() {
    return str(GuiMenuKeys.iconPreviousPage, "ARROW");
  }

  public String iconNextPage() {
    return str(GuiMenuKeys.iconNextPage, "ARROW");
  }

  public String iconBackToMainMenu() {
    return str(GuiMenuKeys.iconBackToMainMenu, "BARRIER");
  }

  public String textPreviousPage() {
    return str(GuiMenuKeys.textPreviousPage, "&e[Previous Page]");
  }

  public String textNextPage() {
    return str(GuiMenuKeys.textNextPage, "&e[Next Page]");
  }

  public String textBackToMainMenu() {
    return str(GuiMenuKeys.textBackToMainMenu, "&c[Back to Worlds]");
  }

  public String titleOperatorSetup() {
    return str(GuiMenuKeys.titleOperatorSetup, "&a&lSetup Wizard");
  }

  public String titleOperatorImport() {
    return str(GuiMenuKeys.titleOperatorImport, "&e&lImport Configs");
  }

  public String titleOperatorConfig() {
    return str(GuiMenuKeys.titleOperatorConfig, "&b&lConfig Editor");
  }

  public String titleOperatorVisualizations() {
    return str(GuiMenuKeys.titleOperatorVisualizations, "&d&lVisualizations");
  }

  public String titleOperatorStatus() {
    return str(GuiMenuKeys.titleOperatorStatus, "&f&lStatus & Metrics");
  }

  public String titleOperatorAdminBook() {
    return str(GuiMenuKeys.titleOperatorAdminBook, "&6&lAdmin Book Panel");
  }

  public String titleOperatorReload() {
    return str(GuiMenuKeys.titleOperatorReload, "&c&lQuick Reload");
  }

  public String dashboardIconName() {
    return str(GuiMenuKeys.iconDashboard, "PAPER");
  }

  public boolean barrierOnUnavailable() {
    return bool(GuiMenuKeys.barrierOnUnavailable, true);
  }

  public String iconInCombat() {
    return str(GuiMenuKeys.iconInCombat, "BARRIER");
  }

  public java.util.List<String> loreReady() {
    return listStr(GuiMenuKeys.loreReady, defaultLoreReady());
  }

  public java.util.List<String> loreCooldown() {
    return listStr(GuiMenuKeys.loreCooldown, defaultLoreCooldown());
  }

  public java.util.List<String> loreCombat() {
    return listStr(GuiMenuKeys.loreCombat, defaultLoreCombat());
  }

  public java.util.List<String> loreNoFunds() {
    return listStr(GuiMenuKeys.loreNoFunds, defaultLoreNoFunds());
  }

  public java.util.List<String> loreNoPermission() {
    return listStr(GuiMenuKeys.loreNoPermission, defaultLoreNoPermission());
  }

  @SuppressWarnings("unchecked")
  private java.util.List<String> listStr(GuiMenuKeys key, java.util.List<String> fallback) {
    ConfigParser<GuiMenuKeys> p = parser();
    if (p == null) return fallback;
    Object v = p.getConfigValue(key, fallback);
    if (v instanceof java.util.List<?> list) {
      java.util.List<String> result = new java.util.ArrayList<>(list.size());
      for (Object o : list) {
        if (o != null) result.add(String.valueOf(o));
      }
      return result;
    }
    return fallback;
  }

  private static java.util.List<String> defaultLoreReady() {
    return java.util.List.of(
        "&7Status: &aREADY",
        "&7Cost: &6{cost}",
        "&7Warmup: &e{delay}",
        "",
        "&aClick to teleport!"
    );
  }

  private static java.util.List<String> defaultLoreCooldown() {
    return java.util.List.of(
        "&7Status: &eON COOLDOWN",
        "&cCooldown remaining: &e{cooldown}",
        "",
        "&cCannot teleport right now."
    );
  }

  private static java.util.List<String> defaultLoreCombat() {
    return java.util.List.of(
        "&7Status: &cIN COMBAT",
        "&cCombat tag remaining: &e{cooldown}",
        "",
        "&cCannot teleport while in combat."
    );
  }

  private static java.util.List<String> defaultLoreNoFunds() {
    return java.util.List.of(
        "&7Status: &eINSUFFICIENT FUNDS",
        "&7Required: &6{cost}",
        "",
        "&cYou cannot afford this teleport."
    );
  }

  private static java.util.List<String> defaultLoreNoPermission() {
    return java.util.List.of(
        "&7Status: &cLOCKED",
        "",
        "&cYou lack permission for this region."
    );
  }

  public String textReady() {
    return str(GuiMenuKeys.textReady, "&aClick to teleport!");
  }

  public String textUnavailable() {
    return str(GuiMenuKeys.textUnavailable, "&cUnavailable right now.");
  }

  public String textSearching() {
    return str(GuiMenuKeys.textSearching, "&bFinding you a spot...");
  }

  public String textSuccess() {
    return str(GuiMenuKeys.textSuccess, "&aTeleported!");
  }

  public String textQueued() {
    return str(GuiMenuKeys.textQueued, "&aQueued for a cross-server destination - sit tight!");
  }

  public String textFailurePrefix() {
    return str(GuiMenuKeys.textFailurePrefix, "&cTeleport failed: ");
  }

  /**
   * Resolves the icon material name for a target given its availability, applying the
   * availability override (cooldown/unavailable/funds) before the per-kind default.
   *
   * @param target the target; may be {@code null} (treated as the default region)
   * @param availability the resolved availability; may be {@code null} (treated as unavailable)
   * @return a platform-neutral material name, never {@code null}
   */
  public String iconName(RtpTarget target, RtpTargetStatus.Availability availability) {
    return iconName(target, availability, null, null);
  }

  /**
   * Resolves the icon material name for a target using its full status, so the
   * destination's advertised representative block / environment (carried by a
   * cross-server {@link RtpTargetStatus}) can drive a recognisable icon.
   *
   * <p>Resolution order once the availability override (cooldown / unavailable /
   * funds) has been applied:
   * <ol>
   *   <li>operator {@code regionIcons} override for this region;</li>
   *   <li>the block the destination backend advertised ({@link RtpTargetStatus#iconBlock()});</li>
   *   <li>this server's {@code environmentIcons} translation of the advertised
   *       environment ({@link RtpTargetStatus#environment()}) - custom-dimension
   *       friendly, since it is resolved locally;</li>
   *   <li>the per-kind representative block.</li>
   * </ol>
   *
   * @param target the target; may be {@code null} (treated as the default region)
   * @param status the per-target status; may be {@code null}
   * @return a platform-neutral material name, never {@code null}
   */
  public String iconName(RtpTarget target, RtpTargetStatus status) {
    if (status == null) {
      return iconName(target, (RtpTargetStatus.Availability) null, null, null);
    }
    return iconName(target, status.availability(), status.iconBlock(), status.environment());
  }

  private String iconName(RtpTarget target, RtpTargetStatus.Availability availability,
      String advertisedBlock, String environment) {
    if (availability != null) {
      if (barrierOnUnavailable() && availability != RtpTargetStatus.Availability.READY) {
        if (availability == RtpTargetStatus.Availability.IN_COMBAT) {
          return iconInCombat();
        }
        return str(GuiMenuKeys.iconUnavailable, "BARRIER");
      }
      switch (availability) {
        case IN_COMBAT:
          return iconInCombat();
        case ON_COOLDOWN:
          return str(GuiMenuKeys.iconOnCooldown, "CLOCK");
        case NO_FUNDS:
          return str(GuiMenuKeys.iconNoFunds, "GOLD_NUGGET");
        case NO_PERMISSION:
        case DISABLED:
          return str(GuiMenuKeys.iconUnavailable, "BARRIER");
        case READY:
        default:
          break; // fall through to the per-kind icon
      }
    }
    RtpTarget.Kind kind = (target == null) ? RtpTarget.Kind.DEFAULT : target.kind();
    // Operator-configured per-region block wins over everything else.
    String override = regionIconOverride(target);
    if (override != null) {
      return override;
    }
    // Then the block the destination backend advertised for this region
    // (provider-side region -> block), if any.
    if (advertisedBlock != null && !advertisedBlock.isBlank()) {
      return advertisedBlock.trim();
    }
    // Then this server's environment -> block translation of the advertised
    // environment string (consumer-side, custom-dimension friendly).
    String envBlock = environmentBlock(environment);
    if (envBlock != null) {
      return envBlock;
    }
    switch (kind) {
      case WORLD: {
        if (target != null && target.name() != null) {
          String wName = target.name().toUpperCase(java.util.Locale.ROOT);
          if (wName.contains("NETHER")) return "NETHERRACK";
          if (wName.contains("END")) return "END_STONE";
        }
        return str(GuiMenuKeys.iconWorld, "GRASS_BLOCK");
      }
      case BIOME: {
        String biomeOverride = biomeIconOverride(target);
        if (biomeOverride != null) return biomeOverride;
        String mappedBiome = defaultBiomeIcon(target != null ? target.name() : null);
        return str(GuiMenuKeys.iconBiome, mappedBiome);
      }
      case REGION:
        // Default to the most common overworld surface block (grass) rather than a
        // FILLED_MAP, which does not render in a vanilla client without a mod.
        return str(GuiMenuKeys.iconRegion, "GRASS_BLOCK");
      case NETWORK:
        return str(GuiMenuKeys.iconNetwork, "ENDER_PEARL");
      case DEFAULT:
      default:
        return str(GuiMenuKeys.iconDefault, "COMPASS");
    }
  }

  /**
   * Resolves the operator-configured per-biome icon override for {@code target}, if any.
   *
   * @param target the target
   * @return material name override, or {@code null}
   */
  public String biomeIconOverride(RtpTarget target) {
    if (target == null || target.name() == null) return null;
    ConfigParser<GuiMenuKeys> p = parser();
    if (p == null) return null;
    java.util.Map<String, Object> overrides = p.getMap(GuiMenuKeys.biomeIcons);
    if (overrides == null || overrides.isEmpty()) return null;
    for (java.util.Map.Entry<String, Object> entry : overrides.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(target.name()) && entry.getValue() != null) {
        String s = String.valueOf(entry.getValue()).trim();
        if (!s.isEmpty()) return s;
      }
    }
    return null;
  }

  /**
   * Default representative icons for vanilla and custom biomes (Saplings / Sand / Snow / etc.).
   */
  public static String defaultBiomeIcon(String biomeName) {
    if (biomeName == null) return "OAK_SAPLING";
    String upper = biomeName.toUpperCase(java.util.Locale.ROOT);
    if (upper.contains("DESERT") || upper.contains("BEACH") || upper.contains("BADLANDS")) {
      return "SAND";
    }
    if (upper.contains("SNOW") || upper.contains("ICE") || upper.contains("FROZEN") || upper.contains("GROVE")) {
      return "SNOW_BLOCK";
    }
    if (upper.contains("NETHER") || upper.contains("CRIMSON") || upper.contains("WARPED") || upper.contains("SOUL")) {
      return "NETHERRACK";
    }
    if (upper.contains("END")) {
      return "END_STONE";
    }
    if (upper.contains("JUNGLE")) {
      return "JUNGLE_SAPLING";
    }
    if (upper.contains("SPRUCE") || upper.contains("TAIGA")) {
      return "SPRUCE_SAPLING";
    }
    if (upper.contains("BIRCH")) {
      return "BIRCH_SAPLING";
    }
    if (upper.contains("CHERRY")) {
      return "CHERRY_SAPLING";
    }
    if (upper.contains("DARK_OAK")) {
      return "DARK_OAK_SAPLING";
    }
    if (upper.contains("SWAMP") || upper.contains("MANGROVE")) {
      return "LILY_PAD";
    }
    if (upper.contains("OCEAN") || upper.contains("RIVER")) {
      return "WATER_BUCKET";
    }
    return "OAK_SAPLING";
  }

  /**
   * Translates a world environment string into a representative block material
   * name using this server's {@code environmentIcons} config map, with built-in
   * defaults for the three vanilla environments. Custom dimensions can be mapped
   * by adding an {@code environmentIcons} entry; an unmapped, non-vanilla
   * environment yields {@code null} so the caller falls back to the per-kind icon.
   *
   * @param environment the environment string (e.g. {@code "NETHER"}); may be {@code null}
   * @return the mapped material name, or {@code null} when none applies
   */
  public String environmentBlock(String environment) {
    if (environment == null) return null;
    String env = environment.trim();
    if (env.isEmpty()) return null;
    ConfigParser<GuiMenuKeys> p = parser();
    if (p != null) {
      java.util.Map<String, Object> map = p.getMap(GuiMenuKeys.environmentIcons);
      if (map != null && !map.isEmpty()) {
        Object v = map.get(env);
        if (v != null) {
          String s = String.valueOf(v).trim();
          if (!s.isEmpty()) return s;
        }
      }
    }
    switch (env) {
      case "NORMAL":
        return "GRASS_BLOCK";
      case "NETHER":
        return "NETHERRACK";
      case "THE_END":
        return "END_STONE";
      default:
        return null;
    }
  }
}
