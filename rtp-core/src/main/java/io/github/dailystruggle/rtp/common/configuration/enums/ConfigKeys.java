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
  /** Generalized menu framework configuration (ADR-035 / ADR-044). */
  menu,
  /** Configuration version */
  version
}
