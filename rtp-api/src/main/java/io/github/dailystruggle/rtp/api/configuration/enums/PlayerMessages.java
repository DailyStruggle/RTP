package io.github.dailystruggle.rtp.api.configuration.enums;

/**
 * Message keys for {@code messages/player.yml} (ADR-071): the teleport-lifecycle
 * messages, time-unit labels, per-player state notifications, title/action-bar
 * display, command-argument/permission errors, and the base {@code /rtp} command
 * usage/description strings sent directly to a player.
 *
 * <p>One enum per {@code messages/<concern>.yml} file (ADR-071).
 */
public enum PlayerMessages {
  // --- Teleport lifecycle ---
  /** Sent when a player issues {@code /rtp} while a teleport is already in progress for them. */
  alreadyTeleporting,
  /** Sent when a player issues {@code /rtp} while the plugin is reloading its configuration. */
  teleportDeniedReloading,
  /** Sent to the player during the countdown before the teleport fires. */
  delayMessage,
  /** Sent while the destination chunks are being loaded asynchronously. */
  chunkLoading,
  /** Sent to the player when they are successfully teleported. */
  teleportMessage,
  /** Sent when a pending teleport is cancelled (e.g. the player moved during the delay). */
  teleportCancel,
  /**
   * Sent when the optional PvP / combat-tag gate refuses (or aborts) a {@code /rtp}
   * because the player is currently considered in combat. Configurable per
   * REQ-RTP-F-013, surfaces S-007. No placeholders.
   */
  pvpInCombat,
  /** Sent when no safe location could be found after exhausting the configured attempts. */
  unsafe,
  /** Sent when the player attempts to teleport before their cooldown has expired. */
  cooldownMessage,
  /**
   * Sent when the player has reached the configured usage cap
   * ({@code lockAfterUses}) and is locked out until the reset window elapses
   * (BetterRTP {@code LockAfter} parity). Configurable per REQ-RTP-F-013.
   * Placeholder: {@code [remainingLockTime]} - the formatted time left until
   * the rolling {@code lockAfterResetSeconds} window resets; resolves to an
   * empty string when no reset window is configured (an absolute cap), in
   * which case the operator should phrase the message without the placeholder.
   */
  lockedAfterUses,
  /** Sent when the pre-generation queue is empty and no location is immediately available. */
  noLocationsQueued,
  /** Sent to notify the player that the queue is being replenished. */
  queueUpdate,
  /** Sent when the player does not have sufficient funds to cover the configured RTP cost. */
  notEnoughMoney,
  /** Sent when the specified world name does not match any loaded world. */
  invalidWorld,
  // --- Command argument / permission errors ---
  /** Sent when a command argument cannot be parsed or is out of range. */
  badArg,
  /** Sent when an unrecognized subcommand or parameter is provided. */
  invalidCommand,
  /** Sent when the sender lacks the required permission for the requested command. */
  noPerms,
  /** Sent when a command is issued while the plugin is reloading or busy. */
  busy,
  /** Sent when the console attempts to run a command that requires a player sender. */
  consoleCmdNotAllowed,
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
  // --- Help / command usage ---
  /** Usage string for the base {@code /rtp} command. */
  rtp,
  /** Description for base {@code /rtp} command. */
  rtp_description,
  /** Output of {@code /rtp help}. */
  help,
  /** Description for {@code /rtp help} command. */
  help_description,
  /** Usage string for {@code /rtp reload}. */
  reload,
  /** Description for {@code /rtp reload} command. */
  reload_description,
  /** Usage string for {@code /rtp scan}. */
  scan,
  /** Description for {@code /rtp scan} command. */
  scan_description,
  /** Description for {@code /rtp config} command. */
  config_description,
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
  PLAYER_TELEPORTING
}
