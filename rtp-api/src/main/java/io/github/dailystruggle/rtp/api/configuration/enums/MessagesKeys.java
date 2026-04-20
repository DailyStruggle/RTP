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
  /** Info line showing the chunk-ticket leak rate (tickets not released in time). */
  infoLeakRate,
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
  showDevTag
}
