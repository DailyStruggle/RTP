package io.github.dailystruggle.rtp.common.configuration.enums;

public enum LoggingKeys {
  detailed_reload,
  detailed_region_init,
  command,
  teleport,
  event_changeworld,
  event_join,
  event_respawn,
  event_move,
  event_teleport,
  selection_failure,
  system_memory_tracker,
  system_database,
  /**
   * Plugin-scoped minimum log level applied on top of the Bukkit logger's own level. The
   * effective gate is the finer of the two filters. Accepts standard {@link
   * java.util.logging.Level} names (SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST,
   * ALL, OFF). Default {@code ALL} keeps behavior additive.
   */
  min_level,
  version
}
