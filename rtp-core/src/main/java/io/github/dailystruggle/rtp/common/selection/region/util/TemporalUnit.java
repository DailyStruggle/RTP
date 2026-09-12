package io.github.dailystruggle.rtp.common.selection.region.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Enumeration of temporal duration units supported by RTP for time-based configurations
 * and command arguments.
 *
 * <p>In Minecraft:
 * 1 second = 20 game ticks (1 tick = 50ms = 0.05s).
 * 1 minute = 60 seconds = 1200 ticks.
 * 1 hour = 60 minutes = 3600 seconds = 72000 ticks.
 * 1 day = 24 hours = 86400 seconds = 1728000 ticks.
 * 1 millisecond = 0.001 seconds = 0.02 ticks.
 */
public enum TemporalUnit {
  // Game native units
  TICK(0.05, "t", "tick", "ticks"),

  // Metric / SI and calendar time units
  MILLISECOND(0.001, "ms", "milli", "millis", "millisecond", "milliseconds"),
  SECOND(1.0, "s", "sec", "second", "seconds"),
  MINUTE(60.0, "m", "min", "minute", "minutes"),
  HOUR(3600.0, "h", "hr", "hour", "hours"),
  DAY(86400.0, "d", "day", "days"),
  WEEK(604800.0, "w", "week", "weeks");

  private final double secondsPerUnit;
  private final String[] aliases;

  private static final Map<String, TemporalUnit> ALIAS_MAP;

  static {
    Map<String, TemporalUnit> map = new HashMap<>();
    for (TemporalUnit unit : values()) {
      registerAlias(map, unit.name().toLowerCase(Locale.ROOT), unit);
      for (String alias : unit.aliases) {
        registerAlias(map, alias.toLowerCase(Locale.ROOT), unit);
      }
    }
    ALIAS_MAP = Collections.unmodifiableMap(map);
  }

  private static void registerAlias(Map<String, TemporalUnit> map, String key, TemporalUnit unit) {
    TemporalUnit existing = map.put(key, unit);
    if (existing != null && existing != unit) {
      throw realCollision(key, existing, unit);
    }
  }

  private static IllegalStateException realCollision(String key, TemporalUnit first, TemporalUnit second) {
    return new IllegalStateException("TemporalUnit alias collision on '" + key + "' between " + first + " and " + second);
  }

  TemporalUnit(double secondsPerUnit, String... aliases) {
    this.secondsPerUnit = secondsPerUnit;
    this.aliases = aliases;
  }

  /**
   * Number of seconds represented by 1.0 of this unit.
   */
  public double getSecondsPerUnit() {
    return secondsPerUnit;
  }

  /**
   * Primary short suffix, e.g. "t", "ms", "s", "m", "h", "d".
   */
  public String getPrimarySuffix() {
    return (aliases.length > 0) ? aliases[0] : name().toLowerCase(Locale.ROOT);
  }

  /**
   * Convert a magnitude in this unit to seconds.
   */
  public double toSeconds(double value) {
    return value * secondsPerUnit;
  }

  /**
   * Convert a magnitude in this unit to milliseconds (1 second = 1000 ms).
   */
  public double toMillis(double value) {
    return toSeconds(value) * 1000.0;
  }

  /**
   * Convert a magnitude in this unit to game ticks (1 tick = 50ms = 0.05s, 20 ticks = 1s).
   */
  public double toTicks(double value) {
    return toSeconds(value) * 20.0;
  }

  /**
   * Find a TemporalUnit by alias or name (case-insensitive).
   *
   * @param token unit string (e.g. "t", "s", "min", "hours")
   * @return matching TemporalUnit or null if unrecognized
   */
  public static TemporalUnit fromString(String token) {
    if (token == null || token.isEmpty()) return null;
    return ALIAS_MAP.get(token.trim().toLowerCase(Locale.ROOT));
  }
}
