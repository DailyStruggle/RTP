package io.github.dailystruggle.rtp.common.configuration.enums;

/** Configuration keys for RTP */
public enum ConfigKeys {
  /** Delay before teleportation */
  teleportDelay,
  /** Cooldown after teleportation */
  teleportCooldown,
  /** Distance to cancel teleportation */
  cancelDistance,
  /** Commands to run as console */
  consoleCommands,
  /** Commands to run as player */
  playerCommands,
  /** Database configuration */
  database,
  /** Network configuration */
  network,
  /** Active language for user-facing messages (see REQ-RTP-F-013, ADR-020). */
  language,
  /** Configuration version */
  version
}
