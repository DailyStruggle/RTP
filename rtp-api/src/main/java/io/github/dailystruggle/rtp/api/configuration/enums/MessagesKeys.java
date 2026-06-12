package io.github.dailystruggle.rtp.api.configuration.enums;

/**
 * Keys identifying every user-facing message in the {@code messages.yml} configuration file.
 *
 * <p>Pass a constant from this enum to
 * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#sendMessage(java.util.UUID, MessagesKeys)}
 * (and its overloads) to send the corresponding configured message to a player or
 * the console. The platform implementation resolves the key to the locale string,
 * applies any registered placeholder replacements, and formats colour codes.
 *
 * <p>Constants are grouped below by functional area for readability.
 */
public enum MessagesKeys {
  // --- Placeholder / template support ---
  /** Token set defining available placeholder variables for message templates. */
  placeholders,
  // --- Teleport lifecycle ---
  /** Sent when a player issues {@code /rtp} while a teleport is already in progress for them. */
  alreadyTeleporting,
  /** Sent when a player issues {@code /rtp} while the plugin is reloading its configuration. */
  teleportDeniedReloading,
  /** Sent to the player during the countdown before the teleport fires. */
  delayMessage,
  /** Sent to the player when they are successfully teleported. */
  teleportMessage,
  /** Sent while the destination chunks are being loaded asynchronously. */
  chunkLoading,
  /** Sent when a pending teleport is cancelled (e.g. the player moved during the delay). */
  teleportCancel,
  /** Sent when no safe location could be found after exhausting the configured attempts. */
  unsafe,
  /** Sent when the player attempts to teleport before their cooldown has expired. */
  cooldownMessage,
  /** Sent when the pre-generation queue is empty and no location is immediately available. */
  noLocationsQueued,
  /** Sent to notify the player that the queue is being replenished. */
  queueUpdate,
  /** Sent when the player does not have sufficient funds to cover the configured RTP cost. */
  notEnoughMoney,
  // --- Time unit labels (used in cooldown / delay display) ---
  /** Label for the "days" time unit. */
  days,
  /** Label for the "hours" time unit. */
  hours,
  /** Label for the "minutes" time unit. */
  minutes,
  /** Label for the "seconds" time unit. */
  seconds,
  /** Label for the "milliseconds" time unit. */
  millis,
  // --- Configuration / reload events ---
  /** Logged when a legacy configuration file is detected and migrated. */
  oldFile,
  /** Logged when a new world is discovered and a default region config is created for it. */
  newWorld,
  /** Sent to the command sender when a configuration reload starts. */
  reloading,
  /** Sent to the command sender when a configuration reload completes successfully. */
  reloaded,
  /** Sent when the plugin begins applying a configuration or data update. */
  updating,
  /** Sent when the configuration or data update completes successfully. */
  updated,
  // --- Command argument / permission errors ---
  /** Sent when a command argument cannot be parsed or is out of range. */
  badArg,
  /** Sent to confirm that a named location has been saved successfully. */
  locationSaved,
  /** Sent to confirm that a named location has been loaded successfully. */
  locationLoaded,
  /** Sent when the console attempts to run a command that requires a player sender. */
  consoleCmdNotAllowed,
  /** Sent when a command is issued while the plugin is reloading or busy. */
  busy,
  /**
   * Sent when the optional PvP / combat-tag gate refuses (or aborts) a {@code /rtp}
   * because the player is currently considered in combat. Configurable per
   * REQ-RTP-F-013, surfaces S-007. No placeholders.
   */
  pvpInCombat,
  /** Sent when an unrecognized subcommand or parameter is provided. */
  invalidCommand,
  /** Sent when the sender lacks the required permission for the requested command. */
  noPerms,
  /** Sent when the specified world name does not match any loaded world. */
  invalidWorld,
  // --- Queue scan operations ---
  /** Sent when a {@code /rtp scan} operation begins pre-generating locations. */
  scanStart,
  /** Sent when the scan queue is reset (cleared). */
  scanReset,
  /** Sent when an in-progress scan is cancelled. */
  scanCancel,
  /** Sent when a scan operation is paused. */
  scanPause,
  /** Sent when a paused scan operation is resumed. */
  scanResume,
  /** Sent in response to a scan status query when a scan is currently running. */
  scanRunning,
  /** Sent in response to a scan status query when no scan is currently running. */
  scanNotRunning,
  /** Template for the scan status report line shown by {@code /rtp scan status}. */
  scanStatus,
  // --- /rtp info display ---
  /** Header title line of the {@code /rtp info} output. */
  infoTitle,
  /** Info line showing the number of active chunk tickets held by the plugin. */
  infoTickets,
  /** Info line showing the number of teleports completed since the last reload. */
  infoTeleports,
  /** Info line showing the plugin's current MSPT contribution. */
  infoMSPT,
  /** Info line showing the total number of chunk loads performed since startup. */
  infoTotalLoads,
  /**
   * Info line showing the per-origin breakdown of chunk loads
   * (the {@code [loadsByOrigin]} placeholder, sourced from
   * {@link io.github.dailystruggle.rtp.api.world.RTPWorld#chunkLoadsByOrigin}).
   * Empty template skips silently — locales without this key keep working.
   */
  infoLoadsByOrigin,
  /** Info line showing the chunk-ticket leak rate (tickets not released in time). */
  infoLeakRate,
  /**
   * Info line showing the cumulative depth of {@code RegionQueueManager.playerQueue}
   * (the {@code [queueDepth]} placeholder, sourced from {@code Metrics.snapshot()}).
   * Part of the metrics health block — see {@code docs/dev/METRICS_PLAN.md > /rtp info}.
   */
  infoQueueDepth,
  /**
   * Info line showing the count of in-flight {@code TeleportPipelineTask}s
   * (the {@code [pendingTeleports]} placeholder).
   */
  infoPendingTeleports,
  /**
   * Info line showing the rolling-mean pipeline latency
   * (the {@code [avgPipelineMs]} placeholder).
   */
  infoAvgPipelineMs,
  /**
   * Info line showing pipeline-latency percentiles
   * (the {@code [pipelineMsP50]} / {@code [pipelineMsP75]} / {@code [pipelineMsP90]} /
   * {@code [pipelineMsP95]} / {@code [pipelineMsP99]} / {@code [pipelineSampleCount]}
   * placeholders). See ADR-053 / REQ-RTP-OBS-004.
   */
  infoPipelinePercentiles,
  /**
   * Info line showing the slow-teleport audit counter and its threshold
   * (the {@code [slowPipelineCount]} / {@code [slowPipelineThresholdMs]} placeholders).
   * Counts immediate/unqueued teleports only. See ADR-053 §2a / REQ-RTP-OBS-005.
   */
  infoSlowPipeline,
  /**
   * Info line showing the queue-growth audit counter and its threshold
   * (the {@code [queueGrowthWarnCount]} / {@code [queueGrowthWarnThreshold]} placeholders).
   * See ADR-053 §2b / REQ-RTP-OBS-006.
   */
  infoQueueGrowth,
  /**
   * Info line showing JVM heap usage
   * (the {@code [heapUsedMb]} / {@code [heapMaxMb]} placeholders).
   */
  infoHeap,
  /**
   * Header line for the {@code Health — pipeline} group in {@code /rtp info}
   * (operator-facing labelled block, METRICS_PLAN.md > /rtp info Surface).
   */
  infoHealthPipelineHeader,
  /**
   * Info line showing TPS (1m / 5m / 15m windows)
   * (the {@code [tps1m]} / {@code [tps5m]} / {@code [tps15m]} placeholders).
   */
  infoTps,
  /**
   * Info line showing the live server MSPT contribution
   * (the {@code [mspt]} / {@code [tickBudgetUtilisation]} placeholders).
   */
  infoMSPTLive,
  /**
   * Info line showing the configured player soft-cap and current player count
   * (the {@code [softCap]} / {@code [playerCount]} placeholders).
   */
  infoSoftCap,
  /**
   * Info line showing the most recent database round-trip latency in milliseconds
   * (the {@code [databaseLatencyMs]} placeholder).
   */
  infoDatabaseLatencyMs,
  /**
   * Info line showing the generation success / failure rate
   * (the {@code [genSuccessRate]} / {@code [genFailureRate]} / {@code [genOutcomeTotal]}
   * placeholders, sourced from the process-global {@code RtpOutcomeStats}). See ADR-052.
   * Empty template skips silently — locales without this key keep working.
   */
  infoFailureRate,
  /**
   * Info line showing the per-cause rejection breakdown
   * (the {@code [genFailureBreakdown]} placeholder, a comma-separated {@code cause=N}
   * list over {@code LocationGenerator.FailTypes}, sourced from {@code RtpOutcomeStats}).
   * The operator-facing analogue of a competitor's {@code /rtp unsafe-stats}. See ADR-052.
   * Empty template skips silently.
   */
  infoFailureBreakdown,
  /**
   * Info line naming the single most common rejection cause and its share
   * (the {@code [genTopRejectionCause]} / {@code [genTopRejectionShare]} placeholders,
   * sourced from {@code RtpOutcomeStats}). See ADR-052. Empty template skips silently.
   */
  infoTopRejectionCause,
  // --- /rtp info colour-band thresholds (B12 / METRICS_PLAN.md > Health colour coding) ---
  // Doubles stored as YAML scalars; consumed by ColourBands in rtp-core to wrap
  // coloured-variant placeholders (e.g. [tps1mColoured]) with &a/&e/&c codes.
  // Missing or malformed keys silently fall back to the documented defaults so
  // operators never see a thrown exception when downgrading from a customised
  // messages.yml; see ColourBands#parseDouble for the resolution policy.
  /** Lower bound for the green band on TPS (1m/5m/15m). Default {@code 19.5}. */
  infoThresholdTpsGreen,
  /** Lower bound for the yellow band on TPS (1m/5m/15m). Default {@code 18.0}. */
  infoThresholdTpsYellow,
  /** Upper bound (inclusive) for the green band on server MSPT in ms. Default {@code 30.0}. */
  infoThresholdMsptGreen,
  /** Upper bound (inclusive) for the yellow band on server MSPT in ms. Default {@code 45.0}. */
  infoThresholdMsptYellow,
  /** Lower bound for the green band on L1 cache fill (fraction in {@code [0.0, 1.0]}). Default {@code 0.50}. */
  infoThresholdCacheFillGreen,
  /** Lower bound for the yellow band on L1 cache fill (fraction in {@code [0.0, 1.0]}). Default {@code 0.25}. */
  infoThresholdCacheFillYellow,
  /** Lower bound (inclusive) in seconds for the yellow band on network-transport last-success age. Default {@code 5.0}. */
  infoThresholdNetworkAgeYellow,
  /**
   * Header line introducing the per-Folia-region table in {@code /rtp info}.
   * Rendered only when {@code MetricsSnapshot.foliaRegions()} is non-empty
   * (Folia runtime). Empty template skips silently — non-Folia locales and
   * locales without the new key continue to work unchanged. See
   * {@code docs/dev/METRICS_PLAN.md > Folia Aggregation} and
   * {@code docs/dev/scratch/PROPOSAL-section-c-folia-fabric-metrics.md §1.3}.
   */
  infoFoliaRegionsHeader,
  /**
   * Per-row template for a single Folia region's metrics in {@code /rtp info},
   * substituted once per entry in {@code MetricsSnapshot.foliaRegions()}.
   * Supports the row-local tokens {@code [regionId]}, {@code [tps1m]},
   * {@code [mspt]}, {@code [playerCount]}, {@code [queueDepth]}, and
   * {@code [tickBudgetUtilisation]}. Empty template skips silently.
   */
  infoFoliaRegion,
  /** Header for the diagnostic disclaimer block in {@code /rtp info} output. */
  infoDisclaimerHeader,
  /** Disclaimer text reminding server operators to include info output in bug reports. */
  infoDisclaimer,
  /** Column header for the per-world section of {@code /rtp info} (player view). */
  infoWorldHeader,
  /** Column header for the per-world section of {@code /rtp info} (console view). */
  infoConsoleWorldHeader,
  /** Template for a single world entry line in {@code /rtp info} (player view). */
  infoWorld,
  /** Column header for the per-region section of {@code /rtp info} (player view). */
  infoRegionHeader,
  /** Column header for the per-region section of {@code /rtp info} (console view). */
  infoConsoleRegionHeader,
  /** Template for a single region entry line in {@code /rtp info} (player view). */
  infoRegion,
  /** Detailed world information block shown by {@code /rtp info <world>}. */
  worldInfo,
  /** Detailed region information block shown by {@code /rtp info <world> <region>}. */
  regionInfo,
  // --- Help / command usage ---
  /** Usage string for the base {@code /rtp} command. */
  rtp,
  /** Output of {@code /rtp help}. */
  help,
  /** Usage string for {@code /rtp reload}. */
  reload,
  /** Usage string for {@code /rtp scan}. */
  scan,
  /** Description for base {@code /rtp} command. */
  rtp_description,
  /** Description for {@code /rtp help} command. */
  help_description,
  /** Description for {@code /rtp reload} command. */
  reload_description,
  /** Description for {@code /rtp config} command. */
  config_description,
  /** Description for {@code /rtp scan} command. */
  scan_description,
  /** Description for {@code /rtp info} command. */
  info_description,
  /** Description for {@code /rtp test} command. */
  test_description,
  // --- Player teleport state notifications ---
  /** Sent when the player's teleport slot becomes available (cooldown expired). */
  PLAYER_AVAILABLE,
  /** Sent to notify the player of their remaining cooldown duration. */
  PLAYER_COOLDOWN,
  /** Sent while the teleport pipeline is being set up for the player. */
  PLAYER_SETUP,
  /** Sent while the destination chunks are being loaded for the player. */
  PLAYER_LOADING,
  /** Sent immediately before the player is teleported to the destination. */
  PLAYER_TELEPORTING,
  // --- Title / action bar display ---
  /** Title text shown on-screen during the teleport sequence. */
  title,
  /** Subtitle text shown below the title during the teleport sequence. */
  subtitle,
  /** Title fade-in duration in ticks. */
  fadeIn,
  /** Title stay duration in ticks. */
  stay,
  /** Title fade-out duration in ticks. */
  fadeOut,
  /** Action bar message displayed during the teleport sequence. */
  actionbar,
  // --- Miscellaneous ---
  /** Version string shown in the plugin startup banner and {@code /rtp version}. */
  version,
  /** Whether to append the developer tag to relevant messages; controls a boolean display flag. */
  showDevTag,
  // --- Generalized menu framework (ADR-035 / ADR-044) ---
  /** Sent when a concrete menu command is malformed, refers to an unknown
   *  path/parameter, or fails its server-side permission gate. ADR-050: menu
   *  clicks are concrete commands, so there is no token to expire. */
  menuInvalid,
  /** Sent when a menu redeem cannot resolve the calling player UUID. */
  menuUnknownPlayer,
  /**
   * Hover-fallback template used by the menu reflector when no YAML block-comment is
   * available for a parameter. The {@code [type]} placeholder is replaced with the
   * declared parameter type (e.g. {@code boolean}, {@code integer}).
   */
  menuHoverFallbackType,
  /**
   * Hover-fallback template used by the menu reflector when a parameter exposes a
   * small curated value set. The {@code [values]} placeholder is replaced with the
   * comma-joined list of accepted values.
   */
  menuHoverFallbackBounds,
  /**
   * Label for the "go back one level" navigation row prepended to every non-root
   * menu page. Configurable per REQ-RTP-F-013. No placeholders.
   */
  menuBack,
  /**
   * Label template for the "execute the assembled command" row prepended to
   * every runnable non-root menu page. The {@code [command]} placeholder is
   * replaced with the assembled {@code /rtp …} invocation. Configurable per
   * REQ-RTP-F-013.
   */
  menuExecute,
  /**
   * Header row shown at the top of a parameter-value picker sub-page
   * (Stage A.2). The {@code [param]} placeholder is replaced with the
   * parameter name, and {@code [command]} with the assembled
   * {@code /rtp …} invocation the chosen value will be appended to.
   * Configurable per REQ-RTP-F-013.
   */
  menuPickValue,
  /**
   * Label for the "✎ type a custom value..." fallback row on a
   * parameter-value picker page (Stage A.2). Clicking it pre-fills the
   * player's chat with the assembled command up to {@code paramName:} so
   * they can type any value, including ones not in the suggestion list.
   * Configurable per REQ-RTP-F-013.
   */
  menuTypeValue,
  /**
   * Non-clickable header row prepended to every non-root menu page
   * (Stage A.4). Surfaces the currently-constructed {@code /rtp …}
   * invocation — including any staged {@code name:value} parameter
   * assignments — so the player can see what is being assembled before
   * pressing the Execute row. The {@code [command]} placeholder is
   * replaced with the assembled invocation. Configurable per
   * REQ-RTP-F-013.
   */
  menuConstructed,
  /**
   * Non-clickable title row prepended to the root {@code /rtp menu} page
   * (Stage A.5). Provides a welcoming header so the menu has visible framing
   * before the player begins descending into subcommands. No placeholders.
   * Configurable per REQ-RTP-F-013.
   */
  menuRootTitle,
  /**
   * Non-clickable subtitle/hint row prepended to the root {@code /rtp menu}
   * page below {@link #menuRootTitle} (Stage A.5). Short orientation text
   * telling the player what to do next. No placeholders. Configurable per
   * REQ-RTP-F-013.
   */
  menuRootHint,
  /**
   * Label template for the "previous page" navigation row appended to
   * paginated menu pages (Stage A.6). Clicking it dispatches a
   * {@link io.github.dailystruggle.rtp.api.menu.MenuAction.ChangePage}
   * to the previous page of the same model. The {@code [page]} placeholder
   * is replaced with the 1-based human-readable previous page number.
   * Configurable per REQ-RTP-F-013.
   */
  menuPagePrev,
  /**
   * Label template for the "next page" navigation row appended to paginated
   * menu pages (Stage A.6). Clicking it dispatches a
   * {@link io.github.dailystruggle.rtp.api.menu.MenuAction.ChangePage}
   * to the next page of the same model. The {@code [page]} placeholder is
   * replaced with the 1-based human-readable next page number. Configurable
   * per REQ-RTP-F-013.
   */
  menuPageNext,
  // --- Stage B front-page row labels (curated landing page) ---
  /** Section divider on the front page above teleport rows (player view). */
  menuFrontPageSectionTeleport,
  /** Front-page row: instant teleport. No placeholders. */
  menuFrontPageRowTeleport,
  /** Front-page row: open the region parameter-value picker. No placeholders. */
  menuFrontPageRowRegion,
  /** Front-page row: open the world parameter-value picker (ADR-065). No placeholders. */
  menuFrontPageRowWorld,
  /** Front-page row: open the biome parameter-value picker. No placeholders. */
  menuFrontPageRowBiome,
  /** Front-page row: show help. No placeholders. */
  menuFrontPageRowHelp,
  /** Front-page row (admin): single entry point into the curated admin panel (PROPOSAL-admin-panel.md v2). No placeholders. */
  menuFrontPageRowAdmin,
  /** Hover text for the admin-panel entry row on the front page. No placeholders. */
  menuFrontPageHoverAdmin,
  // --- Admin panel book page (PROPOSAL-admin-panel.md v2) ---
  /** Non-clickable title row at the top of the admin panel. No placeholders. */
  menuAdminPanelTitle,
  /** Non-clickable hint row below {@link #menuAdminPanelTitle}. No placeholders. */
  menuAdminPanelHint,
  /** Section divider above the Configuration block on the admin panel. */
  menuAdminPanelSectionConfig,
  /** Section divider above the Diagnostics block on the admin panel. */
  menuAdminPanelSectionDiagnostics,
  /** Section divider above the Lifecycle block on the admin panel. */
  menuAdminPanelSectionLifecycle,
  /** Section divider above the Browse block on the admin panel. */
  menuAdminPanelSectionBrowse,
  /** Admin-panel row: open the curated config selector. No placeholders. */
  menuAdminPanelRowConfig,
  /** Hover text for the admin-panel config-editor row. No placeholders. */
  menuAdminPanelHoverConfig,
  /** Admin-panel row: open the regions multi-config submenu. No placeholders. */
  menuAdminPanelRowRegions,
  /** Hover text for the admin-panel regions row. No placeholders. */
  menuAdminPanelHoverRegions,
  /** Admin-panel row: open the worlds multi-config submenu. No placeholders. */
  menuAdminPanelRowWorlds,
  /** Hover text for the admin-panel worlds row. No placeholders. */
  menuAdminPanelHoverWorlds,
  /** Admin-panel row: open the effects multi-config submenu. No placeholders. */
  menuAdminPanelRowEffects,
  /** Hover text for the admin-panel effects row. No placeholders. */
  menuAdminPanelHoverEffects,
  /** Admin-panel row: run /rtp info. No placeholders. */
  menuAdminPanelRowInfo,
  /** Hover text for the admin-panel info row. No placeholders. */
  menuAdminPanelHoverInfo,
  /** Admin-panel row: run full diagnostics. No placeholders. */
  menuAdminPanelRowDiagnostics,
  /** Hover text for the admin-panel diagnostics row. No placeholders. */
  menuAdminPanelHoverDiagnostics,
  /** Admin-panel row: run /rtp test memory. No placeholders. */
  menuAdminPanelRowMemory,
  /** Hover text for the admin-panel memory-tracker row. No placeholders. */
  menuAdminPanelHoverMemory,
  /** Admin-panel row: open the scan submenu. No placeholders. */
  menuAdminPanelRowScan,
  /** Hover text for the admin-panel scan-control row. No placeholders. */
  menuAdminPanelHoverScan,
  /** Admin-panel row: reload all config files. Destructive — hover warns. No placeholders. */
  menuAdminPanelRowReload,
  /** Hover text for the admin-panel reload row. No placeholders. */
  menuAdminPanelHoverReload,
  /** Admin-panel row: open the reflected /rtp command tree. No placeholders. */
  menuAdminPanelRowBrowse,
  /** Hover text for the admin-panel browse row. No placeholders. */
  menuAdminPanelHoverBrowse,
  /** Admin-panel row: return to the curated front page. No placeholders. */
  menuAdminPanelRowBack,
  // --- Visualizations submenu (admin map of bad locations per region) ---
  /** Admin-panel row: open the Visualizations submenu. No placeholders. */
  menuAdminPanelRowVisualizations,
  /** Hover text for the admin-panel Visualizations row. No placeholders. */
  menuAdminPanelHoverVisualizations,
  /** Section divider above the Visualizations block on the admin panel. */
  menuAdminPanelSectionVisualizations,
  /** Non-clickable title row at the top of the Visualizations submenu. No placeholders. */
  menuVisualizationsTitle,
  /** Non-clickable hint row below {@link #menuVisualizationsTitle}. No placeholders. */
  menuVisualizationsHint,
  /** Visualizations row template; placeholder: {@code [region]} (region name). */
  menuVisualizationsRowRegion,
  /** Hover text for a Visualizations region row; placeholder: {@code [region]}. */
  menuVisualizationsHoverRegion,
  /** Non-clickable hint row shown when no regions are configured. No placeholders. */
  menuVisualizationsEmpty,
  /** Visualizations submenu back row (returns to the admin panel). No placeholders. */
  menuVisualizationsRowBack,
  // --- Admin panel Setup section: curated prefabs (PROPOSAL-admin-panel-prefabs.md v3.1) ---
  /** Section divider above the Setup (quick start) block on the admin panel. */
  menuAdminPanelSectionSetup,
  /** Setup row: apply the survival-default identity overlay (reset to shipped defaults). No placeholders. */
  menuPrefabSurvivalDefaultRow,
  /** Hover text for the survival-default prefab row. No placeholders. */
  menuPrefabSurvivalDefaultHover,
  /** Setup row: apply the low-performance prefab (longer pulse, smaller caches, login cache off). No placeholders. */
  menuPrefabLowPerformanceRow,
  /** Hover text for the low-performance prefab row. No placeholders. */
  menuPrefabLowPerformanceHover,
  /** Setup row: apply the high-performance prefab (short pulse, large caches, login cache on). No placeholders. */
  menuPrefabHighPerformanceRow,
  /** Hover text for the high-performance prefab row. No placeholders. */
  menuPrefabHighPerformanceHover,
  /** Setup row: apply the folia-tuned prefab (regional scheduler tuning). No placeholders. */
  menuPrefabFoliaTunedRow,
  /** Hover text for the folia-tuned prefab row. No placeholders. */
  menuPrefabFoliaTunedHover,
  /** Setup row: apply the multi-world prefab (one region per world synthesised from the current default). No placeholders. */
  menuPrefabMultiWorldRow,
  /** Hover text for the multi-world prefab row. No placeholders. */
  menuPrefabMultiWorldHover,
  /** Confirmation-menu title row. Placeholder: {@code [prefab]} (prefab id or display name). */
  menuPrefabConfirmTitle,
  /** Confirmation-menu non-clickable hint row below the title. No placeholders. */
  menuPrefabConfirmHint,
  /** Confirmation-menu footer row: confirm and write the prefab to disk. No placeholders. */
  menuPrefabConfirmRow,
  /** Confirmation-menu footer row: cancel and return to the admin panel. No placeholders. */
  menuPrefabCancelRow,
  // --- Info book (PROPOSAL-info-as-book.md section 4.7) ---
  /**
   * Clickable row label for the {@code Refresh} affordance at the bottom of the
   * {@code /rtp info} book. Re-renders the current scope against a fresh
   * {@code MetricsSnapshot}. No placeholders.
   */
  infoBookRefreshRow,
  /** Hover text for the info-book refresh row. No placeholders. */
  infoBookRefreshHover,
  /**
   * Clickable row label for the {@code Switch to chat} affordance at the bottom
   * of the {@code /rtp info} book. Re-runs the same scope through the legacy
   * chat path without minting a new book token. No placeholders.
   */
  infoBookSwitchToTextRow,
  /** Hover text for the info-book "switch to chat" row. No placeholders. */
  infoBookSwitchToTextHover,
  /**
   * Footer row rendered when the {@code /rtp info} book content exceeds the
   * book page cap. Clicking re-runs the same scope in chat mode so no data is
   * lost. No placeholders.
   */
  infoBookOverflowFooter,
  /**
   * Non-clickable note row indicating that periodic auto-refresh for the info
   * book is not currently supported on this build (it is deferred to a later
   * milestone; users may click {@link #infoBookRefreshRow} to refresh
   * manually). No placeholders.
   */
  infoBookAutoRefreshDeferredNote,
  // --- Stage C config-view book pages (PROPOSAL-config-view-as-book v3.7) ---
  /** Non-clickable title row at the top of the config-file selector page (Stage C.1). */
  configSelectorTitle,
  /** Label for the back-to-menu-root navigation row on the config selector page. */
  configSelectorBackRow,
  /**
   * Non-clickable title row at the top of a single config-file page (Stage C.2).
   * The {@code [file]} placeholder is replaced with the config file name.
   */
  configFileTitle,
  /** Label for the back-to-selector navigation row on a single config-file page. */
  configFileBackRow,
  /** Non-clickable hint row shown when a config file has no editable keys. */
  configFileEmptyHint,
  /**
   * Per-key row label on a config-file page. Placeholders: {@code [key]},
   * {@code [value]}.
   */
  configKeyRowFormat,
  /**
   * Hover text for a per-key row on a config-file page. Placeholders:
   * {@code [key]}, {@code [value]}, {@code [type]}.
   */
  configKeyHoverFormat,
  /**
   * Non-clickable title row at the top of a shape/vert type-picker page
   * (Stage C.3a). Placeholders: {@code [param]} ("shape" or "vert"),
   * {@code [current]} (currently-active type name).
   */
  configTypePickerTitle,
  /**
   * Non-clickable title row at the top of a shape/vert sub-parameter page
   * (Stage C.3b). Placeholders: {@code [param]} ("shape" or "vert"),
   * {@code [type]} (the active type name).
   */
  configSubParamPageTitle,
  /**
   * Hint sent when the book view falls back to the legacy raw-YAML chat dump
   * (no book renderer available on this platform).
   */
  configViewRawHint,
  /** Placeholder text rendered in place of a config value that has not been set. */
  configValueUnsetPlaceholder,
  /**
   * Non-clickable header row separating the "Changeable" list from the
   * "Pending" staging-cart list on a config-file page when the viewer has
   * staged one or more uncommitted edits. No placeholders. Configurable per
   * REQ-RTP-F-013.
   */
  configPendingHeader,
  /**
   * Per-row label for a staged (pending) {@code key=value} entry on a
   * config-file page. Clicking the row dispatches
   * {@link io.github.dailystruggle.rtp.api.menu.MenuAction.UnstageConfigValue}
   * to remove it from the cart. Placeholders: {@code [key]}, {@code [value]}.
   * Configurable per REQ-RTP-F-013.
   */
  configPendingRowFormat,
  /**
   * Hover text shown when the viewer hovers over a pending (staged) row
   * on a config-file page. No placeholders. Configurable per REQ-RTP-F-013.
   */
  configPendingRowHover,
  /**
   * Clickable label for the "apply staged changes" row on a config-file
   * page. Dispatches
   * {@link io.github.dailystruggle.rtp.api.menu.MenuAction.ApplyStagedConfig}
   * which runs the assembled {@code /rtp config <file> k1=v1 ...} command
   * and clears the cart. No placeholders. Configurable per REQ-RTP-F-013.
   */
  configApplyRow,
  /**
   * Clickable label for the "discard staged changes" row on a config-file
   * page. Dispatches
   * {@link io.github.dailystruggle.rtp.api.menu.MenuAction.DiscardStagedConfig}
   * which clears the cart without applying. No placeholders. Configurable
   * per REQ-RTP-F-013.
   */
  configDiscardRow,
  // --- ADR-047 declarative chart composition bridge (REQ-RTP-MAP-006) ---
  /**
   * Sent to the viewer when an {@code OPEN_MAP} menu action fires but no
   * concrete {@code MapBinding} is registered (the {@code NoopMapBinding}
   * is still active, e.g. on the Lite assembly or before {@code RTPHooks}
   * wires the platform binding). Configurable per REQ-RTP-F-013, surfaces
   * S-004 (no silent discard) and REQ-RTP-MAP-001 (require-by-contract).
   * No placeholders.
   */
  mapBindingMissing,
  /**
   * Sent to the viewer when {@code MapDispatch} receives a {@code ChartSpec}
   * whose {@code Kind} has no registered {@code ChartSpecResolver} (e.g.
   * Stage 1 viewer clicks a Stage 3 sparkline before the resolver lands).
   * Configurable per REQ-RTP-F-013, surfaces S-004 and S-007 (configurable
   * "invalid command" feedback). No placeholders.
   */
  mapResolverMissing,
  /**
   * Sent to the viewer when a resolver runs but the underlying data source
   * is unavailable for the requested target (e.g. unknown region name, no
   * default region, world unloaded). Configurable per REQ-RTP-F-013, paired
   * with a WARNING-level {@code RTP.log} entry per S-004. Placeholder
   * {@code [region]} carries the requested region name; empty if unknown.
   */
  mapUnavailable,
  /**
   * Sent to the viewer when the active {@code MapBinding} cannot allocate a
   * map handle right now (binding-defined back-pressure, e.g. per-viewer cap
   * reached). Configurable per REQ-RTP-F-013, surfaces S-007. No placeholders.
   */
  mapBusy,
  /**
   * Label for the clickable row in the {@code /rtp info} menu that, on click,
   * mints a {@code ChartSpec(BAD_POINTS_HEATMAP, ...)} token and asks the
   * active {@code MapBinding} to render a 128x128 heatmap of the current
   * region's known bad-point spiral indices. The row is omitted at render
   * time when {@code NoopMapBinding} is the installed binding (gate per
   * Stage 2 of {@code CHECKLIST-metrics-to-maps.md}). Configurable per
   * REQ-RTP-F-013; surfaces ADR-047 / REQ-RTP-MAP-006. No placeholders.
   */
  menuInfoBadPointsLabel,
  // --- L6 cross-server network mode (rtp-proxy-ADR-014) ---
  /**
   * Sent to the player when {@code /rtp} (optionally with {@code region=<name>})
   * is enrolled on the cross-server wait queue. Placeholder {@code [position]}
   * carries the FIFO position (0 == head). Configurable per REQ-RTP-F-013.
   */
  networkQueued,
  /**
   * Sent when a player runs {@code /rtp*} while already holding a non-terminal
   * cross-server enrolment (ADR-015 / REQ-RTP-NET-015 Slice 4 command-lock).
   * Placeholder {@code [position]} carries the FIFO position when known;
   * an empty value indicates the proxy has not yet assigned one.
   * Configurable per REQ-RTP-F-013.
   */
  alreadyQueued,
  /**
   * Sent when a cross-server waitlist entry was reaped after exceeding its
   * configured TTL (ADR-015 Slice 5). No placeholders. Configurable per
   * REQ-RTP-F-013.
   */
  networkTimedOut,
  /**
   * Sent when a cross-server waitlist enrolment is rejected because the
   * shared waitlist has reached its configured maximum size (ADR-015
   * {@code REJECTED_FULL}). No placeholders. Configurable per REQ-RTP-F-013.
   */
  waitlistFull,
  /**
   * Sent when the proxy has picked a backend for this request and the
   * reservation is being claimed. No placeholders. Configurable per REQ-RTP-F-013.
   */
  networkRouting,
  /**
   * Sent when a coordinate has been reserved on the destination backend
   * and the player is about to be transferred. Placeholder {@code [server]}
   * carries the destination server id. Configurable per REQ-RTP-F-013.
   */
  networkReserved,
  /**
   * Sent immediately before the cross-server hop fires. Placeholder
   * {@code [server]} carries the destination server id. Configurable per
   * REQ-RTP-F-013.
   */
  networkTransferring,
  /**
   * Sent when the network router declines a cross-server hop and falls back
   * to the local pipeline (kill-switch, queue full, rate limit, no live peer,
   * etc.). Placeholder {@code [reason]} carries a short reason code.
   * Configurable per REQ-RTP-F-013.
   */
  networkFallback,
  /**
   * Sent when an enrolled cross-server teleport ultimately failed at a
   * terminal stage. Placeholder {@code [reason]} carries the failure reason
   * code. Configurable per REQ-RTP-F-013.
   */
  networkFailed,
  /**
   * Sent when the player explicitly asked for {@code region=<name>} but no
   * live backend in the network snapshot advertises that region. Placeholder
   * {@code [region]} carries the requested region name. Configurable per
   * REQ-RTP-F-013.
   */
  networkRegionUnavailable,
  /**
   * Sent when {@code region=<name>} is advertised by multiple backends and
   * the operator's collision policy requires a pinned server. Placeholder
   * {@code [region]} carries the requested region name. Configurable per
   * REQ-RTP-F-013.
   */
  networkRegionAmbiguous,
  // --- MultiConfig submenu (CHECKLIST-multiconfig-menu / PROPOSAL-multiconfig-menu.md §4) ---
  /**
   * Generic header fallback for the multi-config selector page when the kind
   * has no dedicated header key. Placeholder {@code [parserKind]} carries the
   * raw kind name. Configurable per REQ-RTP-F-013.
   */
  multiconfigHeaderDefault,
  /** Header row label for the regions multi-config selector page. No placeholders. */
  multiconfigHeaderRegions,
  /** Header row label for the worlds multi-config selector page. No placeholders. */
  multiconfigHeaderWorlds,
  /** Toggle-row label when remove-mode is OFF. No placeholders. */
  multiconfigToggleRemoveModeOff,
  /** Toggle-row label when remove-mode is ON. No placeholders. */
  multiconfigToggleRemoveModeOn,
  /** Selector row label for the "+ Add new..." entry. No placeholders. */
  multiconfigRowAdd,
  /**
   * Hover text for the Add row. Placeholder {@code [name]} carries the
   * prefilled default name (e.g. {@code default1}). Configurable per REQ-RTP-F-013.
   */
  multiconfigRowAddHover,
  /**
   * Hover text for an entry row in normal mode. Placeholder {@code [name]}
   * carries the entry name. Configurable per REQ-RTP-F-013.
   */
  multiconfigRowEditHover,
  /**
   * Hover text for an entry row in remove-mode. Placeholder {@code [name]}
   * carries the entry name. Configurable per REQ-RTP-F-013.
   */
  multiconfigRowRemoveHover,
  /**
   * Confirm-delete page title row. Placeholder {@code [name]} carries the
   * entry name. Configurable per REQ-RTP-F-013.
   */
  multiconfigConfirmTitle,
  /** Confirm-delete page Yes-row label. No placeholders. */
  multiconfigConfirmYes,
  /** Confirm-delete page Cancel-row label. No placeholders. */
  multiconfigConfirmNo,
  /**
   * Hover text shown on a locked (grayed) default-region row explaining why
   * removal is blocked. No placeholders. Configurable per REQ-RTP-F-013.
   */
  multiconfigLockRegionDefault,
  /**
   * Hover text shown on a locked (grayed) world row explaining that the
   * world is still loaded on the server. Placeholder {@code [name]} carries
   * the world name. Configurable per REQ-RTP-F-013.
   */
  multiconfigLockWorldLoaded,
  /** Result message after a successful ADD. Placeholder {@code [name]}. */
  multiconfigResultAdded,
  /** Result message after a successful REMOVE. Placeholder {@code [name]}. */
  multiconfigResultRemoved,
  /**
   * Result message when ADD failed. Placeholders {@code [name]} and
   * {@code [reason]}. Configurable per REQ-RTP-F-013.
   */
  multiconfigResultAddFailed,
  /**
   * Result message when REMOVE failed. Placeholders {@code [name]} and
   * {@code [reason]}. Configurable per REQ-RTP-F-013.
   */
  multiconfigResultRemoveFailed,
  /**
   * Result message when the proposed entry name is rejected by the
   * {@code MULTICONFIG_ENTRY_NAME_REGEX} sanitiser. Placeholder {@code [name]}.
   */
  multiconfigResultNameInvalid,
  /**
   * Result message when an ADD collides with an existing entry name.
   * Placeholder {@code [name]}.
   */
  multiconfigResultNameTaken
}
