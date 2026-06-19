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

  private GuiMenuConfig() {}

  @SuppressWarnings("unchecked")
  private ConfigParser<GuiMenuKeys> parser() {
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
    Object v = (parser() == null) ? null : parser().getConfigValue(key, fallback);
    if (v instanceof Number) return ((Number) v).intValue();
    try {
      return (v == null) ? fallback : Integer.parseInt(String.valueOf(v).trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private boolean bool(GuiMenuKeys key, boolean fallback) {
    Object v = (parser() == null) ? null : parser().getConfigValue(key, fallback);
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

  public String dashboardIconName() {
    return str(GuiMenuKeys.iconDashboard, "PAPER");
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
      switch (availability) {
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
    if (advertisedBlock != null && !advertisedBlock.trim().isEmpty()) {
      return advertisedBlock.trim();
    }
    // Then this server's environment -> block translation of the advertised
    // environment string (consumer-side, custom-dimension friendly).
    String envBlock = environmentBlock(environment);
    if (envBlock != null) {
      return envBlock;
    }
    switch (kind) {
      case WORLD:
        return str(GuiMenuKeys.iconWorld, "GRASS_BLOCK");
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
