package io.github.dailystruggle.rtp.guiaddon.common;

/**
 * Configuration keys for the RTP GUI addon menu.
 *
 * <p>Enum constants are matched against YAML keys in {@code guimenu.yml} by RTP's
 * {@link io.github.dailystruggle.rtp.common.configuration.ConfigParser}, exactly like
 * the reference {@code LeafRTPCountdownAddon}. Registering the parser with
 * {@code RTP.configs} gives first-boot default-file creation, {@code /rtp reload}
 * integration, and save/reload for free, on every platform, with no Bukkit config
 * API. The values are deliberately platform-neutral strings (material <em>names</em>,
 * not {@code org.bukkit.Material}); each renderer maps them to its own item type.
 *
 * <p>The default values produce a "DonutSMP-style" destination picker out of the box.
 */
public enum GuiMenuKeys {
  /**
   * Renderer style to open for a bare {@code /rtp} (e.g. {@code chest}, {@code book}).
   * Matched against each installed renderer's {@code key()}; an unknown value falls
   * back to any available renderer.
   */
  menuStyle,
  /** Inventory/menu title. Supports '&amp;' color codes. */
  menuTitle,
  /** Number of chest rows (1-6); ignored by renderers without a grid. */
  menuRows,
  /** Material name used to fill empty slots, or {@code AIR} to leave them blank. */
  menuFiller,
  /** When true, show a server-health (TPS / MSPT / players) tile. */
  showDashboard,
  /** When true, show special scripted actions hub in GUI if action service is loaded. */
  showActions,
  /** Title for the actions sub-menu. Supports '&amp;' color codes. */
  titleActionsMenu,
  /** Display title for the action selector button on the main menu. */
  titleActionsSelector,
  /** Icon material name for the action selector button on the main menu. */
  iconActionsSelector,
  /** Fallback icon material name for actions without an explicit icon. */
  iconActionDefault,
  /** When true, show operator tools button in GUI if player has permission. */
  showOperatorTools,
  /** Permission required to view/use the operator tools button. */
  permissionOperatorTools,
  /** Title for the operator tools sub-menu. Supports '&amp;' color codes. */
  titleOperatorMenu,
  /** Display title for the operator selector button on the main menu. */
  titleOperatorSelector,
  /** Icon material name for the operator selector button on the main menu. */
  iconOperatorSelector,
  /** Icon material name for the setup wizard button in the operator menu. */
  iconOperatorSetup,
  /** Icon material name for the import button in the operator menu. */
  iconOperatorImport,
  /** Icon material name for the config editor button in the operator menu. */
  iconOperatorConfig,
  /** Icon material name for the visualizations button in the operator menu. */
  iconOperatorVisualizations,
  /** Icon material name for the status/metrics button in the operator menu. */
  iconOperatorStatus,
  /** Icon material name for the master admin book button in the operator menu. */
  iconOperatorAdminBook,
  /** Icon material name for the quick reload button in the operator menu. */
  iconOperatorReload,
  /** When true, groups biomes into a dedicated paginated sub-menu from the main menu. */
  groupBiomesIntoSubmenu,
  /** Title for the biomes sub-menu. Supports '&amp;' color codes. */
  titleBiomeMenu,
  /** Display title for the biome selector button on the main menu. */
  titleBiomeSelector,
  /** Icon material name for the biome selector button on the main menu. */
  iconBiomeSelector,
  /** Icon material name for previous page button in paginated menus. */
  iconPreviousPage,
  /** Icon material name for next page button in paginated menus. */
  iconNextPage,
  /** Icon material name for back to main menu button in sub-menus. */
  iconBackToMainMenu,

  /** Icon material name for the default-region target. */
  iconDefault,
  /** Icon material name for a world target. */
  iconWorld,
  /** Icon material name for a named-region target. */
  iconRegion,
  /** Icon material name for a biome target. */
  iconBiome,
  /** Icon material name for a cross-server (network/peer) region target. */
  iconNetwork,
  /**
   * Per-region icon override map: a YAML mapping from a region key to a material name.
   * The key is the (unqualified) region or world name for a local target, or the
   * {@code server:region} qualified form for a cross-server (network/peer) target.
   * A region not listed here falls back to the representative per-kind icon
   * ({@link #iconRegion} / {@link #iconWorld} / {@link #iconNetwork}).
   */
  regionIcons,
  /**
   * Per-biome icon override map: a YAML mapping from a biome name (case-insensitive)
   * to a material name.
   */
  biomeIcons,
  /**
   * Consumer-side environment-to-block translation map: a YAML mapping from a
   * world environment string (e.g. {@code NORMAL}, {@code NETHER},
   * {@code THE_END}, or a custom-dimension name) to a material name. Used to
   * pick a recognisable icon for a cross-server target from the environment its
   * destination backend advertised, when no explicit {@link #regionIcons}
   * override is set and the backend did not advertise a block of its own.
   * Operators extend this for custom dimensions their backends run.
   */
  environmentIcons,
  /** Icon material name for a target currently on cooldown. */
  iconOnCooldown,
  /** Icon material name for an unavailable (no permission / disabled) target. */
  iconUnavailable,
  /** Icon material name for a target the player cannot afford. */
  iconNoFunds,
  /** Icon material name for the server-health dashboard tile. */
  iconDashboard,

  /** Lore line shown on a ready target. */
  textReady,
  /** Lore line shown on an unavailable target. */
  textUnavailable,
  /** Message sent while a teleport is being resolved. */
  textSearching,
  /** Message sent on a successful teleport. */
  textSuccess,
  /** Message sent when a cross-server teleport request is accepted and queued. */
  textQueued,
  /** Prefix prepended to a teleport-failure reason. */
  textFailurePrefix,
}
