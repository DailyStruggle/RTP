package io.github.dailystruggle.rtp.api.configuration.enums;

/**
 * Message keys for {@code messages/commands.yml} (ADR-071): scan-command feedback,
 * {@code /rtp info} display lines and colour-band thresholds, the generalized menu
 * framework rows, admin-panel / prefab / visualization rows, config-view book
 * pages, map-binding feedback, and multi-config submenu strings.
 *
 * <p>One enum per {@code messages/<concern>.yml} file (ADR-071).
 */
public enum CommandMessages {
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
  /** Title template for the in-game boss-bar shown while a world scan is running. */
  scanBossBar,
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
  /** Info line showing the per-origin breakdown of chunk loads. Empty template skips silently. */
  infoLoadsByOrigin,
  /** Info line showing the chunk-ticket leak rate (tickets not released in time). */
  infoLeakRate,
  /** Info line showing the cumulative depth of the player queue. */
  infoQueueDepth,
  /** Info line showing the count of in-flight pipeline tasks. */
  infoPendingTeleports,
  /** Info line showing the rolling-mean pipeline latency. */
  infoAvgPipelineMs,
  /** Info line showing pipeline-latency percentiles. See ADR-053 / REQ-RTP-OBS-004. */
  infoPipelinePercentiles,
  /** Info line showing the slow-teleport audit counter and its threshold. See ADR-053. */
  infoSlowPipeline,
  /** Info line showing the queue-growth audit counter and its threshold. See ADR-053. */
  infoQueueGrowth,
  /** Info line showing JVM heap usage. */
  infoHeap,
  /** Header line for the {@code Health - pipeline} group in {@code /rtp info}. */
  infoHealthPipelineHeader,
  /** Info line showing TPS (1m / 5m / 15m windows). */
  infoTps,
  /** Info line showing the live server MSPT contribution. */
  infoMSPTLive,
  /** Info line showing the configured player soft-cap and current player count. */
  infoSoftCap,
  /** Info line showing the most recent database round-trip latency in milliseconds. */
  infoDatabaseLatencyMs,
  /** Info line showing the generation success / failure rate. See ADR-052. Empty template skips silently. */
  infoFailureRate,
  /** Info line naming the single most common rejection cause and its share. See ADR-052. */
  infoTopRejectionCause,
  /** Info line showing the per-cause rejection breakdown. See ADR-052. Empty template skips silently. */
  infoFailureBreakdown,
  /** Lower bound for the green band on TPS (1m/5m/15m). Default {@code 19.5}. */
  infoThresholdTpsGreen,
  /** Lower bound for the yellow band on TPS (1m/5m/15m). Default {@code 18.0}. */
  infoThresholdTpsYellow,
  /** Upper bound (inclusive) for the green band on server MSPT in ms. Default {@code 30.0}. */
  infoThresholdMsptGreen,
  /** Upper bound (inclusive) for the yellow band on server MSPT in ms. Default {@code 45.0}. */
  infoThresholdMsptYellow,
  /** Lower bound for the green band on L1 cache fill. Default {@code 0.50}. */
  infoThresholdCacheFillGreen,
  /** Lower bound for the yellow band on L1 cache fill. Default {@code 0.25}. */
  infoThresholdCacheFillYellow,
  /** Lower bound (inclusive) in seconds for the yellow band on network-transport last-success age. Default {@code 5.0}. */
  infoThresholdNetworkAgeYellow,
  /** Header line introducing the per-Folia-region table in {@code /rtp info}. Empty template skips silently. */
  infoFoliaRegionsHeader,
  /** Per-row template for a single Folia region's metrics in {@code /rtp info}. Empty template skips silently. */
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
  /** Header line introducing the biome-occupancy leaderboard shown by {@code /rtp info biomes}. Empty template skips silently. */
  infoBiomeActivityHeader,
  /** Per-row template for a single biome in the {@code /rtp info biomes} occupancy leaderboard. Empty template skips silently. */
  infoBiomeActivityRow,
  /** Line shown by {@code /rtp info biomes} when no occupancy has been sampled yet. Empty template skips silently. */
  infoBiomeActivityEmpty,
  /** Detailed world information block shown by {@code /rtp info <world>}. */
  worldInfo,
  /** Detailed region information block shown by {@code /rtp info <world> <region>}. */
  regionInfo,
  // --- Generalized menu framework (ADR-035 / ADR-044) ---
  /** Sent when a concrete menu command is malformed, refers to an unknown path/parameter, or fails its permission gate. */
  menuInvalid,
  /** Sent when a menu redeem cannot resolve the calling player UUID. */
  menuUnknownPlayer,
  /** Hover-fallback template used by the menu reflector when no YAML block-comment is available. {@code [type]} placeholder. */
  menuHoverFallbackType,
  /** Hover-fallback template used by the menu reflector for a small curated value set. {@code [values]} placeholder. */
  menuHoverFallbackBounds,
  /** Label for the "go back one level" navigation row prepended to every non-root menu page. */
  menuBack,
  /** Label template for the "execute the assembled command" row. {@code [command]} placeholder. */
  menuExecute,
  /** Header row shown at the top of a parameter-value picker sub-page. {@code [param]} / {@code [command]} placeholders. */
  menuPickValue,
  /** Label for the "type a custom value..." fallback row on a parameter-value picker page. */
  menuTypeValue,
  /** Non-clickable header row prepended to every non-root menu page surfacing the assembled command. {@code [command]} placeholder. */
  menuConstructed,
  /** Non-clickable title row prepended to the root {@code /rtp menu} page. */
  menuRootTitle,
  /** Non-clickable subtitle/hint row prepended to the root {@code /rtp menu} page. */
  menuRootHint,
  /** Label template for the "previous page" navigation row on paginated menu pages. {@code [page]} placeholder. */
  menuPagePrev,
  /** Label template for the "next page" navigation row on paginated menu pages. {@code [page]} placeholder. */
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
  /** Front-page row (admin): single entry point into the curated admin panel. No placeholders. */
  menuFrontPageRowAdmin,
  /** Hover text for the admin-panel entry row on the front page. No placeholders. */
  menuFrontPageHoverAdmin,
  // --- Admin panel book page ---
  /** Non-clickable title row at the top of the admin panel. No placeholders. */
  menuAdminPanelTitle,
  /** Non-clickable hint row below the admin-panel title. No placeholders. */
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
  /** Admin-panel row: reload all config files. Destructive - hover warns. No placeholders. */
  menuAdminPanelRowReload,
  /** Hover text for the admin-panel reload row. No placeholders. */
  menuAdminPanelHoverReload,
  /** Admin-panel row: open the reflected /rtp command tree. No placeholders. */
  menuAdminPanelRowBrowse,
  /** Hover text for the admin-panel browse row. No placeholders. */
  menuAdminPanelHoverBrowse,
  /** Admin-panel row: return to the curated front page. No placeholders. */
  menuAdminPanelRowBack,
  // --- Visualizations submenu ---
  /** Admin-panel row: open the Visualizations submenu. No placeholders. */
  menuAdminPanelRowVisualizations,
  /** Hover text for the admin-panel Visualizations row. No placeholders. */
  menuAdminPanelHoverVisualizations,
  /** Section divider above the Visualizations block on the admin panel. */
  menuAdminPanelSectionVisualizations,
  /** Non-clickable title row at the top of the Visualizations submenu. No placeholders. */
  menuVisualizationsTitle,
  /** Non-clickable hint row below the Visualizations title. No placeholders. */
  menuVisualizationsHint,
  /** Visualizations row template; placeholder: {@code [region]}. */
  menuVisualizationsRowRegion,
  /** Hover text for a Visualizations region row; placeholder: {@code [region]}. */
  menuVisualizationsHoverRegion,
  /** Non-clickable hint row shown when no regions are configured. No placeholders. */
  menuVisualizationsEmpty,
  /** Visualizations submenu back row (returns to the admin panel). No placeholders. */
  menuVisualizationsRowBack,
  // --- Admin panel Setup section: curated prefabs ---
  /** Section divider above the Setup (quick start) block on the admin panel. */
  menuAdminPanelSectionSetup,
  /** Setup row: apply the survival-default identity overlay. No placeholders. */
  menuPrefabSurvivalDefaultRow,
  /** Hover text for the survival-default prefab row. No placeholders. */
  menuPrefabSurvivalDefaultHover,
  /** Setup row: apply the low-performance prefab. No placeholders. */
  menuPrefabLowPerformanceRow,
  /** Hover text for the low-performance prefab row. No placeholders. */
  menuPrefabLowPerformanceHover,
  /** Setup row: apply the high-performance prefab. No placeholders. */
  menuPrefabHighPerformanceRow,
  /** Hover text for the high-performance prefab row. No placeholders. */
  menuPrefabHighPerformanceHover,
  /** Setup row: apply the folia-tuned prefab. No placeholders. */
  menuPrefabFoliaTunedRow,
  /** Hover text for the folia-tuned prefab row. No placeholders. */
  menuPrefabFoliaTunedHover,
  /** Setup row: apply the multi-world prefab. No placeholders. */
  menuPrefabMultiWorldRow,
  /** Hover text for the multi-world prefab row. No placeholders. */
  menuPrefabMultiWorldHover,
  /** Confirmation-menu title row. Placeholder: {@code [prefab]}. */
  menuPrefabConfirmTitle,
  /** Confirmation-menu non-clickable hint row below the title. No placeholders. */
  menuPrefabConfirmHint,
  /** Confirmation-menu footer row: confirm and write the prefab to disk. No placeholders. */
  menuPrefabConfirmRow,
  /** Confirmation-menu footer row: cancel and return to the admin panel. No placeholders. */
  menuPrefabCancelRow,
  // --- Info book ---
  /** Clickable row label for the {@code Refresh} affordance at the bottom of the {@code /rtp info} book. No placeholders. */
  infoBookRefreshRow,
  /** Hover text for the info-book refresh row. No placeholders. */
  infoBookRefreshHover,
  /** Clickable row label for the {@code Switch to chat} affordance at the bottom of the {@code /rtp info} book. No placeholders. */
  infoBookSwitchToTextRow,
  /** Hover text for the info-book "switch to chat" row. No placeholders. */
  infoBookSwitchToTextHover,
  /** Footer row rendered when the {@code /rtp info} book content exceeds the book page cap. No placeholders. */
  infoBookOverflowFooter,
  /** Non-clickable note row indicating that periodic auto-refresh for the info book is not currently supported. No placeholders. */
  infoBookAutoRefreshDeferredNote,
  // --- Stage C config-view book pages ---
  /** Non-clickable title row at the top of the config-file selector page. */
  configSelectorTitle,
  /** Label for the back-to-menu-root navigation row on the config selector page. */
  configSelectorBackRow,
  /** Non-clickable title row at the top of a single config-file page. {@code [file]} placeholder. */
  configFileTitle,
  /** Label for the back-to-selector navigation row on a single config-file page. */
  configFileBackRow,
  /** Non-clickable hint row shown when a config file has no editable keys. */
  configFileEmptyHint,
  /** Per-key row label on a config-file page. {@code [key]} / {@code [value]} placeholders. */
  configKeyRowFormat,
  /** Hover text for a per-key row on a config-file page. {@code [key]} / {@code [value]} / {@code [type]} placeholders. */
  configKeyHoverFormat,
  /** Non-clickable title row at the top of a shape/vert type-picker page. {@code [param]} / {@code [current]} placeholders. */
  configTypePickerTitle,
  /** Non-clickable title row at the top of a shape/vert sub-parameter page. {@code [param]} / {@code [type]} placeholders. */
  configSubParamPageTitle,
  /** Hint sent when the book view falls back to the legacy raw-YAML chat dump. */
  configViewRawHint,
  /** Placeholder text rendered in place of a config value that has not been set. */
  configValueUnsetPlaceholder,
  /** Non-clickable header row separating the "Changeable" list from the "Pending" staging-cart list. No placeholders. */
  configPendingHeader,
  /** Per-row label for a staged (pending) {@code key=value} entry on a config-file page. {@code [key]} / {@code [value]} placeholders. */
  configPendingRowFormat,
  /** Hover text shown when the viewer hovers over a pending (staged) row. No placeholders. */
  configPendingRowHover,
  /** Clickable label for the "apply staged changes" row on a config-file page. No placeholders. */
  configApplyRow,
  /** Clickable label for the "discard staged changes" row on a config-file page. No placeholders. */
  configDiscardRow,
  // --- ADR-047 declarative chart composition bridge (REQ-RTP-MAP-006) ---
  /** Sent to the viewer when an {@code OPEN_MAP} menu action fires but no concrete {@code MapBinding} is registered. No placeholders. */
  mapBindingMissing,
  /** Sent to the viewer when {@code MapDispatch} receives a {@code ChartSpec} whose {@code Kind} has no registered resolver. No placeholders. */
  mapResolverMissing,
  /** Sent to the viewer when a resolver runs but the underlying data source is unavailable. {@code [region]} placeholder. */
  mapUnavailable,
  /** Sent to the viewer when the active {@code MapBinding} cannot allocate a map handle right now. No placeholders. */
  mapBusy,
  /** Label for the clickable row in the {@code /rtp info} menu that mints a bad-points heatmap chart. No placeholders. */
  menuInfoBadPointsLabel,
  // --- MultiConfig submenu ---
  /** Generic header fallback for the multi-config selector page. {@code [parserKind]} placeholder. */
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
  /** Hover text for the Add row. {@code [name]} placeholder. */
  multiconfigRowAddHover,
  /** Hover text for an entry row in normal mode. {@code [name]} placeholder. */
  multiconfigRowEditHover,
  /** Hover text for an entry row in remove-mode. {@code [name]} placeholder. */
  multiconfigRowRemoveHover,
  /** Confirm-delete page title row. {@code [name]} placeholder. */
  multiconfigConfirmTitle,
  /** Confirm-delete page Yes-row label. No placeholders. */
  multiconfigConfirmYes,
  /** Confirm-delete page Cancel-row label. No placeholders. */
  multiconfigConfirmNo,
  /** Hover text shown on a locked default-region row explaining why removal is blocked. No placeholders. */
  multiconfigLockRegionDefault,
  /** Hover text shown on a locked world row explaining that the world is still loaded. {@code [name]} placeholder. */
  multiconfigLockWorldLoaded,
  /** Result message after a successful ADD. {@code [name]} placeholder. */
  multiconfigResultAdded,
  /** Result message after a successful REMOVE. {@code [name]} placeholder. */
  multiconfigResultRemoved,
  /** Result message when ADD failed. {@code [name]} / {@code [reason]} placeholders. */
  multiconfigResultAddFailed,
  /** Result message when REMOVE failed. {@code [name]} / {@code [reason]} placeholders. */
  multiconfigResultRemoveFailed,
  /** Result message when the proposed entry name is rejected by the sanitiser. {@code [name]} placeholder. */
  multiconfigResultNameInvalid,
  /** Result message when an ADD collides with an existing entry name. {@code [name]} placeholder. */
  multiconfigResultNameTaken
}
